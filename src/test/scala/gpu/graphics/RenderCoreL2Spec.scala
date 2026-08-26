package gpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
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

  it should "shade a draw and write the framebuffer through one shared L2" in {
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
    // output at +128 (4*stride).
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
      vle32(6, 2), addi(6, 5, 128), vse32(6, 2), cease)
    program.zipWithIndex.foreach { case (w, i) => m.wwrite(shaderPc + i * 4, w) }
    for (i <- 0 until (16 * 16)) m.wwrite(depthBase + i * 4, 0xffffffff)
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
      // Depth write enabled but no test: the depth buffer read at the OM
      // happens via the framebuffer word bridge into the L2.  The OM's depth
      // RMW through the shared cache is covered by OutputMergerSpec / the
      // non-L2 RenderCoreSpec; here we isolate what the L2 arbitration
      // contributes (shading + write-through to one off-chip port).
      dut.io.depthTestEnable.poke(false.B)
      dut.io.depthFunc.poke(0.U)
      dut.io.depthWriteEnable.poke(true.B)
      dut.io.cullMode.poke(0.U)
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
      val respQ = mutable.Queue.empty[(BigInt, BigInt)]
      var guard = 0
      while (!dut.io.done.peek().litToBoolean && guard < 200000) {
        dut.io.memoryRequest.ready.poke(true.B)
        val hadPending = respQ.nonEmpty
        if (dut.io.memoryRequest.valid.peek().litToBoolean &&
            dut.io.memoryRequest.ready.peek().litToBoolean) {
          val a = dut.io.memoryRequest.bits.address.peek().litValue.toLong
          val id = dut.io.memoryRequest.bits.transactionId.peek().litValue
          val isWrite = dut.io.memoryRequest.bits.isWrite.peek().litToBoolean
          val data =
            if (isWrite) {
              val wd = dut.io.memoryRequest.bits.writeData.peek().litValue
              val bm = dut.io.memoryRequest.bits.byteMask.peek().litValue
              m.lineWrite(a, wd, bm)
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
        } else {
          dut.io.memoryResponse.valid.poke(false.B)
        }
        dut.clock.step()
        guard += 1
      }
      assert(guard < 200000, "core-backed renderer over the L2 did not drain")

      def rgb(x: Int, y: Int): (Int, Int, Int) = {
        val c = m.word(colorBase + (y * 16 + x) * 4).toInt
        (((c >> 24) & 0xff), ((c >> 16) & 0xff), ((c >> 8) & 0xff))
      }
      // Every covered pixel is shaded by the core kernel and depth-tested
      // through the shared L2, then written to the single off-chip memory.
      // The full-screen triangle covers these interior pixels.
      assert(rgb(2, 2) == (255, 0, 0), s"core-shaded (2,2) got ${rgb(2, 2)}")
      assert(rgb(5, 5) == (255, 0, 0), s"core-shaded (5,5) got ${rgb(5, 5)}")
      assert(rgb(8, 2) == (255, 0, 0), s"core-shaded (8,2) got ${rgb(8, 2)}")
      // The OM wrote the fragment depth through the same L2.
      assert(m.word(depthBase + (5 * 16 + 5) * 4) == 0x10,
        s"core-shaded depth (5,5) got 0x${m.word(depthBase + (5 * 16 + 5) * 4).toHexString}")
      // The shader kernel ran on the compute unit through the L2 and produced
      // the per-fragment output colour in the kernarg output region.
      assert(m.word(kernarg + 128 + 2 * 4) == 0xff0000ffL,
        s"kernarg output word should be the shaded colour")
    }
  }
}
