package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

/** Verifies that a command-driven draw can be rendered through a single shared
  * L2 that arbitrates all four graphics line clients onto one off-chip memory
  * port.
  *
  * `RenderCoreL2` wraps `RenderCore` (command-buffer, framebuffer, shader
  * kernel, and kernarg word clients) behind one `SharedL2Cache`.  This spec
  * drives the same lane-aware batched fragment shader used in `RenderCoreSpec`
  * and checks that the shaded colour and depth reach the single shared
  * off-chip memory through the L2, rather than a per-client model.
  */
class RenderCoreL2Spec extends AnyFlatSpec {
  behavior of "RenderCoreL2"

  private def q(v: Double): Int = (v * (1 << 16)).toInt

  private def encode(
    tri: Seq[((Int, Int, Int, Int), (Int, Int, Int), Int)],
    shaderPc: Int,
    kernarg: Int
  ): Seq[Int] = {
    val w = Seq.newBuilder[Int]
    for (i <- 0 until 3) {
      w += tri(i)._1._1; w += tri(i)._1._2; w += tri(i)._1._3; w += tri(i)._1._4
    }
    for (i <- 0 until 3) {
      w += tri(i)._2._1; w += tri(i)._2._2; w += tri(i)._2._3
    }
    for (i <- 0 until 3) { w += tri(i)._3 }
    w += shaderPc; w += kernarg
    // uv0..uv2 as unsigned Q16.16 (unused by these tests)
    for (_ <- 0 until 6) { w += 0 }
    for (_ <- 0 until 8) { w += 0 } // state override + reserved
    w.result()
  }

  private class MemModel {
    val words = mutable.LongMap[Int]()
    def word(a: Long): Long = words.getOrElse(a, 0) & 0xffffffffL
    def wwrite(a: Long, d: Int): Unit = words(a) = d & 0xffffffff
    def lineRead(a: Long): BigInt =
      (0 until 16).map(i => BigInt(word(a + i * 4)) << (i * 32))
        .foldLeft(BigInt(0))(_ | _)
    def lineWrite(a: Long, wd: BigInt, bm: BigInt): Unit = {
      for (wi <- 0 until 16) {
        var wo = word(a + wi * 4); val base = wi * 4
        for (b <- 0 until 4) {
          val byte = base + b
          if (((bm >> byte) & 1) != 0) {
            wo = (wo & ~(0xffL << (b * 8))) |
              ((((wd >> (byte * 8)) & 0xff).toLong) << (b * 8))
          }
        }
        words(a + wi * 4) = wo.toInt & 0xffffffff
      }
    }
  }

  it should "wait for delayed lower-memory writes before completing a shared-L2 shader draw" in {
    val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16, subPixelBits = 8)
    val cfg = GpuConfig(lanes = 4, warps = 2)
    val stride = 16 * 4
    val colorBase = 0x8000
    val depthBase = 0x9000
    val cmdBase = 0x4000
    val shaderPc = 0x1000
    val kernarg = 0x6000
    def tri(x: Int, y: Int, d: Int) = ((x, y, 0, q(1.0)), (255, 0, 0), d)
    val record = Seq(
      tri(q(-1.0), q(-1.0), 0x10),
      tri(q(1.0), q(-1.0), 0x10),
      tri(q(-1.0), q(1.0), 0x10)
    )

    val m = new MemModel
    encode(record, shaderPc, kernarg).zipWithIndex
      .foreach { case (w, i) => m.wwrite(cmdBase + i * 4, w) }

    // Lane-aware batched pass-through over the SoA kernarg ABI.  Each warp
    // bases the kernarg with its localLinearBase (x8 << 2), reads the packed
    // input colour at +96 (3*stride for warps=2,lanes=4), and writes it to the
    // output at +192 (6*stride).
    def slli(rd: Int, rs1: Int, sh: Int): Int =
      (sh << 20) | (rs1 << 15) | (1 << 12) | (rd << 7) | 0x13
    def add(rd: Int, rs1: Int, rs2: Int): Int =
      (rs2 << 20) | (rs1 << 15) | (rd << 7) | 0x33
    def addi(rd: Int, rs1: Int, imm: Int): Int =
      ((imm & 0xfff) << 20) | (rs1 << 15) | (rd << 7) | 0x13
    def vsetivli(uimm: Int): Int =
      (0x3 << 30) | (0x10 << 20) | (uimm << 15) | (0x7 << 12) | 0x57
    def vle32(rs1: Int, vd: Int): Int =
      (1 << 25) | (0x6 << 12) | (vd << 7) | (rs1 << 15) | 0x07
    def vse32(rs1: Int, vs3: Int): Int =
      (1 << 25) | (0x6 << 12) | (vs3 << 7) | (rs1 << 15) | 0x27
    val cease = 0x30500073
    val program = Seq(
      slli(5, 8, 2), add(5, 1, 5), vsetivli(4), addi(6, 5, 96),
      vle32(6, 2), addi(6, 5, 192), vse32(6, 2), cease)
    program.zipWithIndex.foreach { case (w, i) => m.wwrite(shaderPc + i * 4, w) }
    for (i <- 0 until (16 * 16)) m.wwrite(depthBase + i * 4, 0x00ffffff) // stencil 0, depth far
    // Record the kernarg base so the shader descriptor points at the same
    // SoA ABI the fixed-function stage writes.
    m.wwrite(cmdBase + 24 * 4 + 4, kernarg)

    simulate(new RenderCoreL2(gfx, cfg, fragCore = true)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.cmdBase.poke(cmdBase.U)
      dut.io.cmdCount.poke(1.U)
      dut.io.colorBase.poke(colorBase.U)
      dut.io.depthBase.poke(depthBase.U)
      dut.io.stride.poke(stride.U)
      // Full depth test through the shared L2: the OM's per-pixel
      // read-modify-write runs over the framebuffer word bridge while the
      // previous fragment's write acknowledgements are still in flight.
      // Write acks are tagged (OmMemoryResponse.write) so the OM cannot
      // mistake an overtaking ack (data=0) for the depth word it awaits.
      dut.io.depthTestEnable.poke(true.B)
      dut.io.depthFunc.poke(0.U)
      dut.io.depthWriteEnable.poke(true.B)
      dut.io.cullMode.poke(0.U)
      dut.io.sampleMode.poke(0.U)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.bits.transactionId.poke(0.U)
      dut.io.memoryResponse.bits.readData.poke(0.U)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.clearPerformanceCounters.poke(false.B)

      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      // Service the L2 off-chip line port.  The L2 registers its lower
      // transaction slot on the request-fire edge, so a request committed this
      // cycle only becomes eligible for a response on the following cycle.
      //
      // `done` now implies the store drain: it rises only once the shared L2
      // reports no unfinished transaction (store queue empty, all lower
      // writes acknowledged, no response awaiting consumption), so the
      // framebuffer is complete in the off-chip memory the moment done is
      // observed - no quiet-cycle fence required.
      case class LowerResponse(id: BigInt, addr: Long, data: BigInt,
        write: Boolean, mask: BigInt, due: Int)
      val respQ = mutable.Queue.empty[LowerResponse]
      var guard = 0
      var doneSeen = false
      var delayedWriteCycles = 0
      while (!doneSeen && guard < 200000) {
        dut.io.memoryRequest.ready.poke((guard % 5 != 0).B)
        // Hold responses without making writes visible in backing memory.
        dut.io.memoryResponse.valid.poke(false.B)
        if (respQ.nonEmpty) {
          val r = respQ.head
          if (r.write) {
            assert(!dut.io.done.peek().litToBoolean,
              "DONE must wait for lower-memory write acknowledgements")
            if (r.due > guard) delayedWriteCycles += 1
          }
          if (r.due <= guard) {
            dut.io.memoryResponse.valid.poke(true.B)
            dut.io.memoryResponse.bits.transactionId.poke(r.id.U)
            dut.io.memoryResponse.bits.readData.poke((if (r.write) BigInt(0) else r.data).U)
            dut.io.memoryResponse.bits.fault.poke(false.B)
            if (dut.io.memoryResponse.ready.peek().litToBoolean) {
              if (r.write) m.lineWrite(r.addr, r.data, r.mask)
              respQ.dequeue()
            }
          }
        }
        if (dut.io.memoryRequest.valid.peek().litToBoolean &&
            dut.io.memoryRequest.ready.peek().litToBoolean) {
          val a = dut.io.memoryRequest.bits.address.peek().litValue.toLong
          val id = dut.io.memoryRequest.bits.transactionId.peek().litValue
          val write = dut.io.memoryRequest.bits.isWrite.peek().litToBoolean
          val data = if (write) dut.io.memoryRequest.bits.writeData.peek().litValue else m.lineRead(a)
          val mask = dut.io.memoryRequest.bits.byteMask.peek().litValue
          val delay = if (write && a >= colorBase && a < depthBase + stride * 16) 31 else 1
          respQ.enqueue(LowerResponse(id, a, data, write, mask, guard + delay))
        }
        if (dut.io.done.peek().litToBoolean) doneSeen = true
        dut.clock.step()
        guard += 1
      }
      assert(doneSeen, "core-backed renderer over the L2 did not drain")
      assert(respQ.isEmpty, "DONE must not leave any lower-memory response pending")
      assert(delayedWriteCycles >= 30, "exercise delayed framebuffer visibility")

      def rgb(x: Int, y: Int): (Int, Int, Int) = {
        val c = m.word(colorBase + (y * 16 + x) * 4).toInt
        (((c >> 24) & 0xff), ((c >> 16) & 0xff), ((c >> 8) & 0xff))
      }
      // Every covered pixel is shaded by the core kernel and depth-tested
      // through the shared L2, then written to the single off-chip memory.
      // Sampling at integer coordinates with the top-left fill rule, the
      // triangle covers exactly {x >= 1, y >= 1, x+y <= 16}: the top and left
      // edges (y=0, x=0) are not top-left edges, the diagonal is.  Every such
      // pixel is touched exactly once and 0x10 < 0xffffffff passes LESS, so a
      // black pixel or a stale depth word here means the depth RMW through
      // the L2 read a stale line.
      for (y <- 0 until 16; x <- 0 until 16 if x >= 1 && y >= 1 && x + y <= 16) {
        assert(rgb(x, y) == (255, 0, 0),
          s"covered pixel ($x,$y) rejected or mis-shaded, got ${rgb(x, y)}")
        assert(m.word(depthBase + (y * 16 + x) * 4) == 0x10,
          s"covered depth ($x,$y) got 0x${m.word(depthBase + (y * 16 + x) * 4).toHexString}")
      }
      // The OM wrote the fragment depth through the same L2.
      assert(m.word(depthBase + (5 * 16 + 5) * 4) == 0x10,
        s"core-shaded depth (5,5) got 0x${m.word(depthBase + (5 * 16 + 5) * 4).toHexString}")
      // The shader kernel ran on the compute unit through the L2 and produced
      // the per-fragment output colour in the kernarg output region.
      assert(m.word(kernarg + 192 + 2 * 4) == 0xff0000ffL,
        s"kernarg output word should be the shaded colour")
    }
  }

  it should "match 1x texture and 4x texture blend stencil images through shared L2" in {
    for (sampleMode <- Seq(0, 2)) {
      val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16, subPixelBits = 8)
      val cfg = GpuConfig(lanes = 4, warps = 2)
      val samples = 1 << sampleMode
      val stride = 16 * samples * 4
      val colorBase = 0x8000
      val depthBase = 0x9000
      val cmdBase = 0x4000
      val texBase = 0xA000
      val texelCentre = q(0.5 / 16.0)
      val farCentre = q(1.0 + 0.5 / 16.0)

      def tri(x: Int, y: Int, d: Int, u: Int, v: Int) =
        ((x, y, 0, q(1.0)), (255, 255, 255), d, u, v)
      val record = Seq(
        tri(q(-1.0), q(-1.0), 0x10, texelCentre, texelCentre),
        tri(q(1.0), q(-1.0), 0x10, farCentre, texelCentre),
        tri(q(-1.0), q(1.0), 0x10, texelCentre, farCentre)
      )

      val m = new MemModel
      // 32-word record: verts, colours, depth, descriptor, uv pairs.
      val w = Seq.newBuilder[Int]
      for (i <- 0 until 3) {
        w += record(i)._1._1; w += record(i)._1._2; w += record(i)._1._3; w += record(i)._1._4
      }
      for (i <- 0 until 3) {
        w += record(i)._2._1; w += record(i)._2._2; w += record(i)._2._3
      }
      for (i <- 0 until 3) { w += record(i)._3 }
      w += 0; w += 0 // descriptor
      for (i <- 0 until 3) { w += record(i)._4; w += record(i)._5 }
      if (sampleMode == 2) {
        w += (1 | 2 | (1 << 7) | (1 << 10) | (1 << 17)) // override, depth, texture, stencil
        w += 0; w += 0 // LOD and kernarg bank
        w += (1 | (2 << 4) | (3 << 8)) // blend SRC_COLOR + ONE_MINUS_SRC_COLOR
        w += 4 // stencil EQUAL, all ops KEEP
        w += (0x5a | (0xff << 8) | (0xff << 16)) // reference and masks
        w += 0; w += 0
      } else for (_ <- 0 until 8) { w += 0 }
      w.result().zipWithIndex.foreach { case (word, i) =>
        m.wwrite(cmdBase + i * 4, word)
      }
      def sampleOffset(x: Int, y: Int, s: Int): Int =
        (y * 16 * samples + x * samples + s) * 4
      def destination(x: Int, y: Int): Int =
        ((x * 7 + 13) << 24) | ((y * 9 + 21) << 16) |
          (((x + y) * 4 + 33) << 8) | 0xff
      for (y <- 0 until 16; x <- 0 until 16; s <- 0 until samples) {
        val passStencil = (x + y + s) % 3 != 0
        val stencil = if (passStencil) 0x5a else 0
        m.wwrite(depthBase + sampleOffset(x, y, s),
          if (sampleMode == 2) (stencil << 24) | 0x40 else 0x00ffffff)
        if (sampleMode == 2)
          m.wwrite(colorBase + sampleOffset(x, y, s), destination(x, y))
      }
      def texel(x: Int, y: Int): Int = {
        val r = x * 7 + y * 5 + 3
        val g = x * 4 + y * 9 + 11
        val b = x * 6 + y * 3 + 17
        (r << 24) | (g << 16) | (b << 8) | 0xff
      }
      for (y <- 0 until 16; x <- 0 until 16)
        m.wwrite(texBase + (y * 16 + x) * 4, texel(x, y))

      simulate(new RenderCoreL2(gfx, cfg, fragCore = false)) { dut =>
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        dut.io.cmdBase.poke(cmdBase.U)
        dut.io.cmdCount.poke(1.U)
        dut.io.colorBase.poke(colorBase.U)
        dut.io.depthBase.poke(depthBase.U)
        dut.io.stride.poke(stride.U)
        dut.io.depthTestEnable.poke(true.B)
        dut.io.depthFunc.poke(0.U)
        dut.io.depthWriteEnable.poke(true.B)
        dut.io.cullMode.poke(0.U)
        dut.io.sampleMode.poke(sampleMode.U)
        dut.io.texEnable.poke(true.B)
        dut.io.texBase.poke(texBase.U)
        dut.io.texWidth.poke(16.U)
        dut.io.texHeight.poke(16.U)
        dut.io.texWrapClamp.poke(false.B)
        dut.io.texMaxLevel.poke(0.U)
        dut.io.memoryResponse.valid.poke(false.B)
        dut.io.memoryResponse.bits.fault.poke(false.B)
        dut.io.memoryResponse.bits.transactionId.poke(0.U)
        dut.io.memoryResponse.bits.readData.poke(0.U)
        dut.io.memoryRequest.ready.poke(true.B)
        dut.io.clearPerformanceCounters.poke(false.B)

        dut.io.start.poke(true.B)
        dut.clock.step()
        dut.io.start.poke(false.B)

        val respQ = mutable.Queue.empty[(BigInt, BigInt)]
        var guard = 0
        var doneSeen = false
        while (!doneSeen && guard < 400000) {
          dut.io.memoryRequest.ready.poke(true.B)
          val hadPending = respQ.nonEmpty
          if (dut.io.memoryRequest.valid.peek().litToBoolean &&
              dut.io.memoryRequest.ready.peek().litToBoolean) {
            val a = dut.io.memoryRequest.bits.address.peek().litValue.toLong
            val id = dut.io.memoryRequest.bits.transactionId.peek().litValue
            val isWrite = dut.io.memoryRequest.bits.isWrite.peek().litToBoolean
            val data =
              if (isWrite) {
                m.lineWrite(a, dut.io.memoryRequest.bits.writeData.peek().litValue,
                  dut.io.memoryRequest.bits.byteMask.peek().litValue)
                BigInt(0)
              } else m.lineRead(a)
            respQ.enqueue((id, data))
          }
          if (hadPending) {
            val (id, data) = respQ.head
            dut.io.memoryResponse.valid.poke(true.B)
            dut.io.memoryResponse.bits.transactionId.poke(id.U)
            dut.io.memoryResponse.bits.readData.poke(data.U)
            dut.io.memoryResponse.bits.fault.poke(false.B)
            if (dut.io.memoryResponse.ready.peek().litToBoolean) respQ.dequeue()
          } else dut.io.memoryResponse.valid.poke(false.B)
          if (dut.io.done.peek().litToBoolean) doneSeen = true
          dut.clock.step()
          guard += 1
        }
        assert(doneSeen, "textured renderer did not drain")

        def scale(c: Int, factor: Int): Int = (c * factor + 127) / 255
        def channel(word: Int, shift: Int): Int = (word >>> shift) & 0xff
        var partialSamples = 0
        var differingSamples = 0
        var edgeChannelDifference = 0
        var largestChannelDifference = 0
        // Texture sampling uses the pixel-centre UV for every covered sample.
        // Coverage itself uses the quarter-pixel sample position.
        for (y <- 0 until 16; x <- 0 until 16;
             ((sx, sy), s) <- Msaa.positions(sampleMode).zipWithIndex) {
          val inside = 4 * x + sx > 0 && 4 * y + sy > 0 &&
            4 * (x + y) + sx + sy <= 64
          val passStencil = (x + y + s) % 3 != 0
          val t = texel(x, y)
          if (sampleMode == 2 && inside) {
            val covered = Msaa.positions(sampleMode).count { case (px, py) =>
              4 * x + px > 0 && 4 * y + py > 0 &&
                4 * (x + y) + px + py <= 64
            }
            if (covered < samples) {
              partialSamples += 1
              val nx = (x + (if (sx < 0) 15 else 1)) % 16
              val ny = (y + (if (sy < 0) 15 else 1)) % 16
              // The UV gradient is one texel per pixel. A quarter-pixel
              // sample shifts the bilinear footprint 1/4 texel on each axis.
              val shifts = Seq(24, 16, 8)
              val differences = shifts.map { shift =>
                val perSample = (9 * channel(t, shift) +
                  3 * channel(texel(nx, y), shift) +
                  3 * channel(texel(x, ny), shift) +
                  channel(texel(nx, ny), shift) + 8) / 16
                math.abs(perSample - channel(t, shift))
              }
              val difference = differences.sum
              if (difference > 0) differingSamples += 1
              edgeChannelDifference += difference
              largestChannelDifference = largestChannelDifference.max(differences.max)
            }
          }
          val src = Seq(24, 16, 8).map(shift => channel(t, shift) * 255 >>> 8)
          val expectedColor = if (sampleMode == 0) {
            if (inside) (src(0).toLong << 24) | (src(1).toLong << 16) |
              (src(2).toLong << 8) | 0xffL else 0L
          } else if (inside && passStencil) {
            val dst = destination(x, y)
            val mixed = Seq(24, 16, 8).zipWithIndex.map { case (shift, i) =>
              math.min(255, scale(src(i), src(i)) +
                scale(channel(dst, shift), 255 - src(i)))
            }
            (mixed(0).toLong << 24) | (mixed(1).toLong << 16) |
              (mixed(2).toLong << 8) | 0xffL
          } else destination(x, y) & 0xffffffffL
          val expectedDepth = if (sampleMode == 0) {
            if (inside) 0x10L else 0x00ffffffL
          } else if (inside && passStencil) 0x5a000010L
          else ((if (passStencil) 0x5aL else 0L) << 24) | 0x40L
          val actualColor = m.word(colorBase + sampleOffset(x, y, s))
          val actualDepth = m.word(depthBase + sampleOffset(x, y, s))
          assert(actualColor == expectedColor,
            f"mode $sampleMode sample $s color ($x,$y): got 0x$actualColor%08x expected 0x$expectedColor%08x")
          assert(actualDepth == expectedDepth,
            f"mode $sampleMode sample $s depth ($x,$y): got 0x$actualDepth%08x expected 0x$expectedDepth%08x")
        }
        if (sampleMode == 2) {
          assert(partialSamples > 0 && differingSamples > 0,
            "edge-texture scene must expose a per-sample colour difference")
          println(s"EDGE_TEXTURE_4X partial_samples=$partialSamples " +
            s"differing_samples=$differingSamples " +
            s"channel_difference_sum=$edgeChannelDifference " +
            s"max_channel_difference=$largestChannelDifference")
        }
      }
    }
  }
}
