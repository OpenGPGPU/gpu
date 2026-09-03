package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

import java.nio.file.{Files, Paths}

class RenderCoreSpec extends AnyFlatSpec {
  behavior of "RenderCore"

  private def q(v: Double): Int = (v * (1 << 16)).toInt

  private def encode(
    tri: Seq[((Int, Int, Int, Int), (Int, Int, Int), Int)],
    shaderPc: Int,
    kernarg: Int,
    state: Int = 0,
    kernargBankStride: Int = 0
  ): Seq[Int] = {
    val w = Seq.newBuilder[Int]
    for (i <- 0 until 3) { w += tri(i)._1._1; w += tri(i)._1._2; w += tri(i)._1._3; w += tri(i)._1._4 }
    for (i <- 0 until 3) { w += tri(i)._2._1; w += tri(i)._2._2; w += tri(i)._2._3 }
    for (i <- 0 until 3) { w += tri(i)._3 }
    w += shaderPc; w += kernarg
    for (_ <- 0 until 6) { w += 0 } // uv0..uv2
    w += state
    w += 0 // word 33: LOD bias/min-level
    w += kernargBankStride // word 34: optional alternate kernarg bank
    for (_ <- 0 until 5) { w += 0 } // reserved
    w.result()
  }

  private def rgbOf(fbMem: Array[Int], colorBase: Int, x: Int, y: Int): (Int, Int, Int) = {
    val c = fbMem(colorBase / 4 + (y * 16 + x))
    (((c >> 24) & 0xff), ((c >> 16) & 0xff), ((c >> 8) & 0xff))
  }

  it should "elaborate the vertex-core command path with the shared shader CU" in {
    simulate(new RenderCore(
      GraphicsConfig(screenWidth = 16, screenHeight = 16),
      GpuConfig(lanes = 4, warps = 2), fragCore = true, vertCore = true)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
    }
  }

  it should "apply independent per-draw state in one command buffer" in {
    val config = GraphicsConfig(screenWidth = 16, screenHeight = 16, subPixelBits = 8)
    val stride = 16 * 4
    val colorBase = 0x8000
    val depthBase = 0x9000
    val cmdBase = 0x4000

    def tri(x: Int, y: Int, r: Int, g: Int, b: Int, d: Int) = ((x, y, 0, q(1.0)), (r, g, b), d)
    val red = Seq(
      tri(q(-1.0), q(-1.0), 255, 0, 0, 0x10),
      tri(q(1.0), q(-1.0), 255, 0, 0, 0x10),
      tri(q(-1.0), q(1.0), 255, 0, 0, 0x10)
    )
    val green = Seq(
      tri(q(-0.5), q(-0.5), 0, 255, 0, 0x08),
      tri(q(0.5), q(-0.5), 0, 255, 0, 0x08),
      tri(q(-0.5), q(0.5), 0, 255, 0, 0x08)
    )

    val cbMem = Array.fill(1 << 15)(0)
    // Draw 0 inherits LESS from the job. Draw 1 overrides depth to ALWAYS,
    // so its farther red fragments replace green in the overlap.
    val redAlways = 1 | 2 | (3 << 4) | (1 << 7)
    (encode(green, 0x9000, 0x20000) ++
      encode(red, 0x9000, 0x20000, state = redAlways))
      .zipWithIndex.foreach { case (w, i) => cbMem(cmdBase / 4 + i) = w }

    simulate(new RenderCore(config)) { dut =>
      val fbMem = Array.fill(1 << 16)(0)
      for (i <- 0 until (16 * 16)) fbMem(depthBase / 4 + i) = 0xffffffff

      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.cmdBase.poke(cmdBase.U)
      dut.io.cmdCount.poke(2.U)
      dut.io.colorBase.poke(colorBase.U)
      dut.io.depthBase.poke(depthBase.U)
      dut.io.stride.poke(stride.U)
      dut.io.depthTestEnable.poke(true.B)
      dut.io.depthFunc.poke(0.U)
      dut.io.depthWriteEnable.poke(true.B)
      dut.io.cullMode.poke(0.U)
      dut.io.texEnable.poke(false.B)
      dut.io.texBase.poke(0.U)
      dut.io.texWidth.poke(0.U)
      dut.io.texHeight.poke(0.U)
      dut.io.texWrapClamp.poke(false.B)
      dut.io.texMaxLevel.poke(0.U)
      dut.io.texMem.req.ready.poke(true.B)
      dut.io.texMem.resp.valid.poke(false.B)
      dut.io.cbMem.req.ready.poke(true.B)
      dut.io.cbMem.resp.valid.poke(false.B)
      dut.io.fbMem.req.ready.poke(true.B)
      dut.io.fbMem.resp.valid.poke(false.B)

      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      // Multi-outstanding models: requests are captured on fire and their
      // address-tagged responses presented on a later cycle (the parallel
      // output merger keeps several reads in flight).
      val cbQ = scala.collection.mutable.Queue.empty[(Int, Long)]
      val fbQ = scala.collection.mutable.Queue.empty[(Int, Long)]
      var guard = 0
      while (!dut.io.done.peek().litToBoolean && guard < 20000) {
        dut.io.cbMem.req.ready.poke(true.B)
        if (cbQ.nonEmpty) {
          val (a, d) = cbQ.head
          dut.io.cbMem.resp.valid.poke(true.B)
          dut.io.cbMem.resp.bits.data.poke(d.U)
          dut.io.cbMem.resp.bits.addr.poke(a.U)
        } else dut.io.cbMem.resp.valid.poke(false.B)
        if (cbQ.nonEmpty && dut.io.cbMem.resp.ready.peek().litToBoolean) cbQ.dequeue()
        if (dut.io.cbMem.req.valid.peek().litToBoolean && dut.io.cbMem.req.ready.peek().litToBoolean) {
          val a = dut.io.cbMem.req.bits.addr.peek().litValue.toInt
          cbQ.enqueue((a, cbMem(a / 4) & 0xffffffffL))
        }
        dut.io.fbMem.req.ready.poke(true.B)
        if (fbQ.nonEmpty) {
          val (a, d) = fbQ.head
          dut.io.fbMem.resp.valid.poke(true.B)
          dut.io.fbMem.resp.bits.data.poke(d.U)
          dut.io.fbMem.resp.bits.addr.poke(a.U)
        } else dut.io.fbMem.resp.valid.poke(false.B)
        if (fbQ.nonEmpty && dut.io.fbMem.resp.ready.peek().litToBoolean) fbQ.dequeue()
        if (dut.io.fbMem.req.valid.peek().litToBoolean && dut.io.fbMem.req.ready.peek().litToBoolean) {
          val a = dut.io.fbMem.req.bits.addr.peek().litValue.toInt
          val write = dut.io.fbMem.req.bits.write.peek().litToBoolean
          val data = dut.io.fbMem.req.bits.data.peek().litValue.toInt
          if (write) fbMem(a / 4) = data
          else fbQ.enqueue((a, fbMem(a / 4) & 0xffffffffL))
        }
        dut.clock.step()
        guard += 1
      }
      assert(guard < 20000, "did not drain")
      def rgb(x: Int, y: Int): (Int, Int, Int) = rgbOf(fbMem, colorBase, x, y)
      // Per-draw ALWAYS makes the farther second draw win in the overlap.
      assert(rgb(5, 5) == (255, 0, 0), s"red override (5,5) should win, got ${rgb(5, 5)}")
      assert(fbMem(depthBase / 4 + 5 * 16 + 5) == 0x10,
        "ALWAYS override must replace the nearer 0x08 depth with 0x10")

      val px = new Array[Int](16 * 16 * 3); var i = 0
      for (y <- 0 until 16; x <- 0 until 16) {
        val c = fbMem(colorBase / 4 + (y * 16 + x))
        px(i) = (c >> 24) & 0xff; px(i + 1) = (c >> 16) & 0xff; px(i + 2) = (c >> 8) & 0xff
        i += 3
      }
      val p = Paths.get("generated/scene.ppm")
      Files.createDirectories(p.getParent)
      Files.write(p, ("P6\n16 16\n255\n").getBytes("US-ASCII") ++ px.map(_.toByte))
      System.err.println(s"wrote ${p.toAbsolutePath}")
    }
  }

  it should "render a command-driven scene through a core-backed kernel (fragCore)" in {
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

    // Unified word-addressed model (byte address -> word) shared by the OM,
    // the compute unit and the word->line bridge.
    val m = scala.collection.mutable.LongMap[Int]()
    def word(a: Long): Long = m.getOrElse(a, 0) & 0xffffffffL
    def wwrite(a: Long, d: Int): Unit = m(a) = d & 0xffffffff
    def lineRead(a: Long): BigInt =
      (0 until 16).map(i => BigInt(word(a + i * 4)) << (i * 32)).foldLeft(BigInt(0))(_ | _)
    def lineWrite(a: Long, wd: BigInt, bm: BigInt): Unit = {
      for (wi <- 0 until 16) {
        var wo = word(a + wi * 4); val base = wi * 4
        for (b <- 0 until 4) {
          val byte = base + b
          if (((bm >> byte) & 1) != 0) {
            wo = (wo & ~(0xffL << (b * 8))) | ((((wd >> (byte * 8)) & 0xff).toLong) << (b * 8))
          }
        }
        m(a + wi * 4) = wo.toInt & 0xffffffff
      }
    }
    encode(record, shaderPc, kernarg).zipWithIndex.foreach { case (w, i) => wwrite(cmdBase + i * 4, w) }
    // Lane-aware batched pass-through over the SoA kernarg ABI (stride = 32
    // for warps=2, lanes=4): colour inputs at kernarg+96+4*i, outputs at
    // kernarg+192+4*i.  Each warp shades its own slice of the batch:
    // x8 = localLinearBase gives the warp's first fragment index, so one
    // vector load/store pair moves all four lanes.
    //   slli x5, x8, 2 ; add x5, x1, x5 ; vsetivli x0, 4, e32 ;
    //   addi x6, x5, 96 ; vle32.v v2, (x6) ;
    //   addi x6, x5, 192 ; vse32.v v2, (x6) ;
    //   load input depth; add one; store output depth; cease
    def slli(rd: Int, rs1: Int, sh: Int): Int = (sh << 20) | (rs1 << 15) | (1 << 12) | (rd << 7) | 0x13
    def add(rd: Int, rs1: Int, rs2: Int): Int = (rs2 << 20) | (rs1 << 15) | (rd << 7) | 0x33
    def addi(rd: Int, rs1: Int, imm: Int): Int = ((imm & 0xfff) << 20) | (rs1 << 15) | (rd << 7) | 0x13
    def vsetivli(uimm: Int): Int = (0x3 << 30) | (0x10 << 20) | (uimm << 15) | (0x7 << 12) | 0x57
    def vle32(rs1: Int, vd: Int): Int = (1 << 25) | (0x6 << 12) | (vd << 7) | (rs1 << 15) | 0x07
    def vse32(rs1: Int, vs3: Int): Int = (1 << 25) | (0x6 << 12) | (vs3 << 7) | (rs1 << 15) | 0x27
    def vaddVi(vd: Int, vs2: Int, imm: Int): Int =
      (1 << 25) | (vs2 << 20) | ((imm & 0x1f) << 15) |
        (3 << 12) | (vd << 7) | 0x57
    val cease = 0x30500073
    val program = Seq(
      slli(5, 8, 2), add(5, 1, 5), vsetivli(4), addi(6, 5, 96),
      vle32(6, 2), addi(6, 5, 192), vse32(6, 2),
      addi(6, 5, 64), vle32(6, 3), vaddVi(3, 3, 1),
      addi(6, 5, 224), vse32(6, 3), cease)
    program.zipWithIndex.foreach { case (w, i) => wwrite(shaderPc + i * 4, w) }
    for (i <- 0 until (16 * 16)) wwrite(depthBase + i * 4, 0xffffffff) // depth far

    simulate(new RenderCore(gfx, cfg, fragCore = true)) { dut =>
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
      dut.io.cbMem.req.ready.poke(true.B)
      dut.io.cbMem.resp.valid.poke(false.B)
      dut.io.fbMem.req.ready.poke(true.B)
      dut.io.fbMem.resp.valid.poke(false.B)
      dut.io.kernelMemReq.ready.poke(true.B)
      dut.io.kernelMemResp.valid.poke(false.B)
      dut.io.kernelMemResp.bits.readData.poke(0.U)
      dut.io.kernelMemResp.bits.fault.poke(false.B)
      dut.io.kernelMemResp.bits.transactionId.poke(0.U)
      dut.io.kernelWordMemReq.ready.poke(true.B)
      dut.io.kernelWordMemResp.valid.poke(false.B)
      dut.io.kernelWordMemResp.bits.readData.poke(0.U)
      dut.io.kernelWordMemResp.bits.fault.poke(false.B)
      dut.io.kernelWordMemResp.bits.transactionId.poke(0.U)

      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      // Deferred-response queues: capture a request on the cycle it fires and
      // hold its response until the DUT's memory port actually accepts it.
      val kuQ = scala.collection.mutable.Queue.empty[(BigInt, BigInt)]
      val wuQ = scala.collection.mutable.Queue.empty[(BigInt, BigInt)]
      // Multi-outstanding models: requests captured on fire, address-tagged
      // responses presented later (the parallel output merger keeps several
      // reads in flight).
      val cbQ = scala.collection.mutable.Queue.empty[(Long, Long)]
      val fbQ = scala.collection.mutable.Queue.empty[(Long, Long)]
      var guard = 0
      while (!dut.io.done.peek().litToBoolean && guard < 60000) {
        dut.io.cbMem.req.ready.poke(true.B)
        if (cbQ.nonEmpty) {
          val (a, d) = cbQ.head
          dut.io.cbMem.resp.valid.poke(true.B)
          dut.io.cbMem.resp.bits.data.poke(d.U)
          dut.io.cbMem.resp.bits.addr.poke(a.U)
        } else dut.io.cbMem.resp.valid.poke(false.B)
        if (cbQ.nonEmpty && dut.io.cbMem.resp.ready.peek().litToBoolean) cbQ.dequeue()
        if (dut.io.cbMem.req.valid.peek().litToBoolean && dut.io.cbMem.req.ready.peek().litToBoolean)
          if (!dut.io.cbMem.req.bits.write.peek().litToBoolean) {
            cbQ.enqueue((dut.io.cbMem.req.bits.addr.peek().litValue.toLong,
              word(dut.io.cbMem.req.bits.addr.peek().litValue.toLong)))
          }

        dut.io.fbMem.req.ready.poke(true.B)
        if (fbQ.nonEmpty) {
          val (a, d) = fbQ.head
          dut.io.fbMem.resp.valid.poke(true.B)
          dut.io.fbMem.resp.bits.data.poke(d.U)
          dut.io.fbMem.resp.bits.addr.poke(a.U)
        } else dut.io.fbMem.resp.valid.poke(false.B)
        if (fbQ.nonEmpty && dut.io.fbMem.resp.ready.peek().litToBoolean) fbQ.dequeue()
        if (dut.io.fbMem.req.valid.peek().litToBoolean && dut.io.fbMem.req.ready.peek().litToBoolean) {
          val a = dut.io.fbMem.req.bits.addr.peek().litValue.toLong
          val w = dut.io.fbMem.req.bits.write.peek().litToBoolean
          if (w) wwrite(a, dut.io.fbMem.req.bits.data.peek().litValue.toInt)
          else fbQ.enqueue((a, word(a)))
        }

        // compute-unit line port response
        if (kuQ.nonEmpty) {
          dut.io.kernelMemResp.valid.poke(true.B)
          dut.io.kernelMemResp.bits.transactionId.poke(kuQ.head._1.U)
          dut.io.kernelMemResp.bits.readData.poke(kuQ.head._2.U)
          dut.io.kernelMemResp.bits.fault.poke(false.B)
        } else dut.io.kernelMemResp.valid.poke(false.B)
        if (kuQ.nonEmpty && dut.io.kernelMemResp.ready.peek().litToBoolean) kuQ.dequeue()
        if (dut.io.kernelMemReq.valid.peek().litToBoolean && dut.io.kernelMemReq.ready.peek().litToBoolean) {
          val a = dut.io.kernelMemReq.bits.address.peek().litValue.toLong
          val id = dut.io.kernelMemReq.bits.transactionId.peek().litValue
          if (dut.io.kernelMemReq.bits.isWrite.peek().litToBoolean) {
            lineWrite(a, dut.io.kernelMemReq.bits.writeData.peek().litValue,
              dut.io.kernelMemReq.bits.byteMask.peek().litValue); kuQ.enqueue((id, BigInt(0)))
          } else kuQ.enqueue((id, lineRead(a)))
        }

        // word->line bridge line port response
        if (wuQ.nonEmpty) {
          dut.io.kernelWordMemResp.valid.poke(true.B)
          dut.io.kernelWordMemResp.bits.transactionId.poke(wuQ.head._1.U)
          dut.io.kernelWordMemResp.bits.readData.poke(wuQ.head._2.U)
          dut.io.kernelWordMemResp.bits.fault.poke(false.B)
        } else dut.io.kernelWordMemResp.valid.poke(false.B)
        if (wuQ.nonEmpty && dut.io.kernelWordMemResp.ready.peek().litToBoolean) wuQ.dequeue()
        if (dut.io.kernelWordMemReq.valid.peek().litToBoolean && dut.io.kernelWordMemReq.ready.peek().litToBoolean) {
          val a = dut.io.kernelWordMemReq.bits.address.peek().litValue.toLong
          val id = dut.io.kernelWordMemReq.bits.transactionId.peek().litValue
          if (dut.io.kernelWordMemReq.bits.isWrite.peek().litToBoolean) {
            lineWrite(a, dut.io.kernelWordMemReq.bits.writeData.peek().litValue,
              dut.io.kernelWordMemReq.bits.byteMask.peek().litValue); wuQ.enqueue((id, BigInt(0)))
          } else wuQ.enqueue((id, lineRead(a)))
        }

        dut.clock.step()
        guard += 1
      }
      assert(guard < 60000, "core-backed core did not drain")

      def depth(x: Int, y: Int): Int =
        word(depthBase + (y * 16 + x) * 4).toInt
      def rgb(x: Int, y: Int): (Int, Int, Int) = {
        val c = word(colorBase + (y * 16 + x) * 4).toInt
        (((c >> 24) & 0xff), ((c >> 16) & 0xff), ((c >> 8) & 0xff))
      }
      // The lane-aware vector kernel shades every lane of each flushed batch,
      // so every covered pixel gets the interpolated (red) colour — not just
      // the fragment that landed in a scalar slot.  Several interior pixels
      // plus the depth write pin the full rasterize -> kernel -> OM path.
      assert(rgb(2, 2) == (255, 0, 0), s"core-shaded (2,2) should be red, got ${rgb(2, 2)}")
      assert(rgb(5, 5) == (255, 0, 0), s"core-shaded (5,5) should be red, got ${rgb(5, 5)}")
      assert(rgb(8, 2) == (255, 0, 0), s"core-shaded (8,2) should be red, got ${rgb(8, 2)}")
      assert(depth(5, 5) == 0x11, s"shader depth (5,5) should be 0x11, got ${depth(5, 5)}")
    }
  }

  it should "render two core-backed draws in one command buffer (fragCore)" in {
    // The draw-token regression: the second draw's admission must not let its
    // front-end state interleave with the first draw's tail batch, and both
    // draws' OM writes must land.  Draw 0 is green at depth 0x08 (job-level
    // LESS); draw 1 is a larger red triangle at depth 0x10 with a state
    // override (test enable, func = greater-equal, write enable), so its
    // shader-written 0x11 replaces green in the overlap.
    val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16, subPixelBits = 8)
    val cfg = GpuConfig(lanes = 4, warps = 2)
    val stride = 16 * 4
    val colorBase = 0x8000
    val depthBase = 0x9000
    val cmdBase = 0x4000
    val shaderPc = 0x1000
    val kernarg = 0x6000
    def tri(x: Int, y: Int, r: Int, g: Int, b: Int, d: Int) =
      ((x, y, 0, q(1.0)), (r, g, b), d)
    val green = Seq(
      tri(q(-0.5), q(-0.5), 0, 255, 0, 0x08),
      tri(q(0.5), q(-0.5), 0, 255, 0, 0x08),
      tri(q(-0.5), q(0.5), 0, 255, 0, 0x08)
    )
    val red = Seq(
      tri(q(-1.0), q(-1.0), 255, 0, 0, 0x10),
      tri(q(1.0), q(-1.0), 255, 0, 0, 0x10),
      tri(q(-1.0), q(1.0), 255, 0, 0, 0x10)
    )
    // override | depthTestEnable | depthFunc=3 (greater-equal) | depthWriteEnable
    val redOverride = 1 | 2 | (3 << 4) | (1 << 7)

    val m = scala.collection.mutable.LongMap[Int]()
    def word(a: Long): Long = m.getOrElse(a, 0) & 0xffffffffL
    def wwrite(a: Long, d: Int): Unit = m(a) = d & 0xffffffff
    def lineRead(a: Long): BigInt =
      (0 until 16).map(i => BigInt(word(a + i * 4)) << (i * 32)).foldLeft(BigInt(0))(_ | _)
    def lineWrite(a: Long, wd: BigInt, bm: BigInt): Unit = {
      for (wi <- 0 until 16) {
        var wo = word(a + wi * 4); val base = wi * 4
        for (b <- 0 until 4) {
          val byte = base + b
          if (((bm >> byte) & 1) != 0) {
            wo = (wo & ~(0xffL << (b * 8))) | ((((wd >> (byte * 8)) & 0xff).toLong) << (b * 8))
          }
        }
        m(a + wi * 4) = wo.toInt & 0xffffffff
      }
    }
    // A distinct scratch allocation prevents the compute unit's cached input
    // lines from aliasing the first draw's writes; this keeps the regression
    // about draw-context ordering rather than cache coherency.
    (encode(green, shaderPc, kernarg) ++
      encode(red, shaderPc, kernarg + 0x1000, state = redOverride))
      .zipWithIndex.foreach { case (w, i) => wwrite(cmdBase + i * 4, w) }

    // Same lane-aware pass-through kernel as the single-draw test: colour
    // inputs -> outputs, depth outputs = depth inputs + 1.
    def slli(rd: Int, rs1: Int, sh: Int): Int = (sh << 20) | (rs1 << 15) | (1 << 12) | (rd << 7) | 0x13
    def add(rd: Int, rs1: Int, rs2: Int): Int = (rs2 << 20) | (rs1 << 15) | (rd << 7) | 0x33
    def addi(rd: Int, rs1: Int, imm: Int): Int = ((imm & 0xfff) << 20) | (rs1 << 15) | (rd << 7) | 0x13
    def vsetivli(uimm: Int): Int = (0x3 << 30) | (0x10 << 20) | (uimm << 15) | (0x7 << 12) | 0x57
    def vle32(rs1: Int, vd: Int): Int = (1 << 25) | (0x6 << 12) | (vd << 7) | (rs1 << 15) | 0x07
    def vse32(rs1: Int, vs3: Int): Int = (1 << 25) | (0x6 << 12) | (vs3 << 7) | (rs1 << 15) | 0x27
    def vaddVi(vd: Int, vs2: Int, imm: Int): Int =
      (1 << 25) | (vs2 << 20) | ((imm & 0x1f) << 15) |
        (3 << 12) | (vd << 7) | 0x57
    val cease = 0x30500073
    val program = Seq(
      slli(5, 8, 2), add(5, 1, 5), vsetivli(4), addi(6, 5, 96),
      vle32(6, 2), addi(6, 5, 192), vse32(6, 2),
      addi(6, 5, 64), vle32(6, 3), vaddVi(3, 3, 1),
      addi(6, 5, 224), vse32(6, 3), cease)
    program.zipWithIndex.foreach { case (w, i) => wwrite(shaderPc + i * 4, w) }
    for (i <- 0 until (16 * 16)) wwrite(depthBase + i * 4, 0xffffffff) // depth far

    simulate(new RenderCore(gfx, cfg, fragCore = true)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.cmdBase.poke(cmdBase.U)
      dut.io.cmdCount.poke(2.U)
      dut.io.colorBase.poke(colorBase.U)
      dut.io.depthBase.poke(depthBase.U)
      dut.io.stride.poke(stride.U)
      dut.io.depthTestEnable.poke(true.B)
      dut.io.depthFunc.poke(0.U)
      dut.io.depthWriteEnable.poke(true.B)
      dut.io.cullMode.poke(0.U)
      dut.io.cbMem.req.ready.poke(true.B)
      dut.io.cbMem.resp.valid.poke(false.B)
      dut.io.fbMem.req.ready.poke(true.B)
      dut.io.fbMem.resp.valid.poke(false.B)
      dut.io.kernelMemReq.ready.poke(true.B)
      dut.io.kernelMemResp.valid.poke(false.B)
      dut.io.kernelMemResp.bits.readData.poke(0.U)
      dut.io.kernelMemResp.bits.fault.poke(false.B)
      dut.io.kernelMemResp.bits.transactionId.poke(0.U)
      dut.io.kernelWordMemReq.ready.poke(true.B)
      dut.io.kernelWordMemResp.valid.poke(false.B)
      dut.io.kernelWordMemResp.bits.readData.poke(0.U)
      dut.io.kernelWordMemResp.bits.fault.poke(false.B)
      dut.io.kernelWordMemResp.bits.transactionId.poke(0.U)

      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      val kuQ = scala.collection.mutable.Queue.empty[(BigInt, BigInt)]
      val wuQ = scala.collection.mutable.Queue.empty[(BigInt, BigInt)]
      // Multi-outstanding models: requests captured on fire, address-tagged
      // responses presented later (the parallel output merger keeps several
      // reads in flight).
      val cbQ = scala.collection.mutable.Queue.empty[(Long, Long)]
      val fbQ = scala.collection.mutable.Queue.empty[(Long, Long)]
      var guard = 0
      while (!dut.io.done.peek().litToBoolean && guard < 120000) {
        dut.io.cbMem.req.ready.poke(true.B)
        if (cbQ.nonEmpty) {
          val (a, d) = cbQ.head
          dut.io.cbMem.resp.valid.poke(true.B)
          dut.io.cbMem.resp.bits.data.poke(d.U)
          dut.io.cbMem.resp.bits.addr.poke(a.U)
        } else dut.io.cbMem.resp.valid.poke(false.B)
        if (cbQ.nonEmpty && dut.io.cbMem.resp.ready.peek().litToBoolean) cbQ.dequeue()
        if (dut.io.cbMem.req.valid.peek().litToBoolean && dut.io.cbMem.req.ready.peek().litToBoolean)
          if (!dut.io.cbMem.req.bits.write.peek().litToBoolean) {
            cbQ.enqueue((dut.io.cbMem.req.bits.addr.peek().litValue.toLong,
              word(dut.io.cbMem.req.bits.addr.peek().litValue.toLong)))
          }

        dut.io.fbMem.req.ready.poke(true.B)
        if (fbQ.nonEmpty) {
          val (a, d) = fbQ.head
          dut.io.fbMem.resp.valid.poke(true.B)
          dut.io.fbMem.resp.bits.data.poke(d.U)
          dut.io.fbMem.resp.bits.addr.poke(a.U)
        } else dut.io.fbMem.resp.valid.poke(false.B)
        if (fbQ.nonEmpty && dut.io.fbMem.resp.ready.peek().litToBoolean) fbQ.dequeue()
        if (dut.io.fbMem.req.valid.peek().litToBoolean && dut.io.fbMem.req.ready.peek().litToBoolean) {
          val a = dut.io.fbMem.req.bits.addr.peek().litValue.toLong
          val w = dut.io.fbMem.req.bits.write.peek().litToBoolean
          if (w) { wwrite(a, dut.io.fbMem.req.bits.data.peek().litValue.toInt) }
          else { fbQ.enqueue((a, word(a))) }
        }

        if (kuQ.nonEmpty) {
          dut.io.kernelMemResp.valid.poke(true.B)
          dut.io.kernelMemResp.bits.transactionId.poke(kuQ.head._1.U)
          dut.io.kernelMemResp.bits.readData.poke(kuQ.head._2.U)
          dut.io.kernelMemResp.bits.fault.poke(false.B)
        } else dut.io.kernelMemResp.valid.poke(false.B)
        if (kuQ.nonEmpty && dut.io.kernelMemResp.ready.peek().litToBoolean) kuQ.dequeue()
        if (dut.io.kernelMemReq.valid.peek().litToBoolean && dut.io.kernelMemReq.ready.peek().litToBoolean) {
          val a = dut.io.kernelMemReq.bits.address.peek().litValue.toLong
          val id = dut.io.kernelMemReq.bits.transactionId.peek().litValue
          if (dut.io.kernelMemReq.bits.isWrite.peek().litToBoolean) {
            lineWrite(a, dut.io.kernelMemReq.bits.writeData.peek().litValue,
              dut.io.kernelMemReq.bits.byteMask.peek().litValue); kuQ.enqueue((id, BigInt(0)))
          } else kuQ.enqueue((id, lineRead(a)))
        }

        if (wuQ.nonEmpty) {
          dut.io.kernelWordMemResp.valid.poke(true.B)
          dut.io.kernelWordMemResp.bits.transactionId.poke(wuQ.head._1.U)
          dut.io.kernelWordMemResp.bits.readData.poke(wuQ.head._2.U)
          dut.io.kernelWordMemResp.bits.fault.poke(false.B)
        } else dut.io.kernelWordMemResp.valid.poke(false.B)
        if (wuQ.nonEmpty && dut.io.kernelWordMemResp.ready.peek().litToBoolean) wuQ.dequeue()
        if (dut.io.kernelWordMemReq.valid.peek().litToBoolean &&
          dut.io.kernelWordMemReq.ready.peek().litToBoolean) {
          val a = dut.io.kernelWordMemReq.bits.address.peek().litValue.toLong
          val id = dut.io.kernelWordMemReq.bits.transactionId.peek().litValue
          if (dut.io.kernelWordMemReq.bits.isWrite.peek().litToBoolean) {
            lineWrite(a, dut.io.kernelWordMemReq.bits.writeData.peek().litValue,
              dut.io.kernelWordMemReq.bits.byteMask.peek().litValue); wuQ.enqueue((id, BigInt(0)))
          } else wuQ.enqueue((id, lineRead(a)))
        }

        dut.clock.step()
        guard += 1
      }
      assert(guard < 120000, "two-draw core-backed render did not drain")

      def depth(x: Int, y: Int): Int =
        word(depthBase + (y * 16 + x) * 4).toInt
      def rgb(x: Int, y: Int): (Int, Int, Int) = {
        val c = word(colorBase + (y * 16 + x) * 4).toInt
        (((c >> 24) & 0xff), ((c >> 16) & 0xff), ((c >> 8) & 0xff))
      }
      // The greater-equal override lets the farther second draw replace the
      // nearer first draw in the overlap: the second draw's OM write must land.
      assert(rgb(5, 5) == (255, 0, 0), s"second draw (5,5) should be red, got ${rgb(5, 5)}")
      assert(depth(5, 5) == 0x11, s"second draw depth (5,5) should be 0x11, got ${depth(5, 5)}")
      // A pixel off the anti-diagonal half-plane is covered by neither draw.
      assert(rgb(14, 3) == (0, 0, 0), s"uncovered (14,3) should be black, got ${rgb(14, 3)}")
      assert(depth(14, 3) == 0xffffffff, s"uncovered depth (14,3) should be far, got ${depth(14, 3)}")
    }
  }
}
