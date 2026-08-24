package gpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class KernelRenderCoreSpec extends AnyFlatSpec {
  behavior of "KernelRenderCore"

  private def q(v: Double): Int = (v * (1 << 16)).toInt

  // Pass-through shader: copy kernarg word0 (packed colour) to word1, halt.
  private val lw = BigInt("0000a503", 16)
  private val sw = BigInt("00a0a223", 16)
  private val cease = BigInt("30500073", 16)

  // Unified word-addressed memory model (byte address -> 32-bit word).
  private class Mem {
    val m = scala.collection.mutable.LongMap[Int]()
    def word(addr: Long): Long = m.getOrElse(addr, 0) & 0xffffffffL
    def wordWrite(addr: Long, data: Int): Unit = m(addr) = data & 0xffffffff
    def lineRead(lineAddr: Long): BigInt =
      (0 until 16).map { i => BigInt(word(lineAddr + i * 4)) << (i * 32) }
        .foldLeft(BigInt(0))(_ | _)
    def lineWrite(lineAddr: Long, writeData: BigInt, byteMask: BigInt): Unit = {
      for (wi <- 0 until 16) {
        var wo = word(lineAddr + wi * 4)
        val base = wi * 4
        for (b <- 0 until 4) {
          val byte = base + b
          if (((byteMask >> byte) & 1) != 0) {
            val wb = (((writeData >> (byte * 8)) & 0xff)).toLong
            wo = (wo & ~(0xffL << (b * 8))) | (wb << (b * 8))
          }
        }
        m(lineAddr + wi * 4) = wo.toInt & 0xffffffff
      }
    }
  }

  private def encode(
    tri: Seq[((Int, Int, Int), (Int, Int, Int), Int)],
    shaderPc: Int,
    kernarg: Int
  ): Seq[Int] = {
    val w = Seq.newBuilder[Int]
    for (i <- 0 until 3) {
      w += tri(i)._1._1; w += tri(i)._1._2; w += tri(i)._1._3; w += q(1.0)
    }
    for (i <- 0 until 3) { w += tri(i)._2._1; w += tri(i)._2._2; w += tri(i)._2._3 }
    for (i <- 0 until 3) { w += tri(i)._3 }
    w += shaderPc; w += kernarg
    w.result()
  }

  it should "render a command-driven scene through a core-backed kernel" in {
    val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16, subPixelBits = 8)
    val cfg = GpuConfig(lanes = 4, warps = 2)
    val stride = 16 * 4
    val colorBase = 0x8000
    val depthBase = 0x9000
    val cmdBase = 0x4000
    val shaderPc = 0x1000
    val kernarg = 0x2000
    val r = 0xab; val g = 0xcd; val b = 0xef

    def tri(x: Int, y: Int, d: Int) = ((x, y, 0), (r, g, b), d)
    val record = Seq(
      tri(q(-1.0), q(-1.0), 0x10),
      tri(q(1.0), q(-1.0), 0x10),
      tri(q(-1.0), q(1.0), 0x10)
    )

    val mem = new Mem
    (encode(record, shaderPc, kernarg)).zipWithIndex.foreach { case (w, i) =>
      mem.wordWrite(cmdBase + i * 4, w)
    }
    mem.wordWrite(shaderPc + 0, lw.toInt)
    mem.wordWrite(shaderPc + 4, sw.toInt)
    mem.wordWrite(shaderPc + 8, cease.toInt)
    for (i <- 0 until (16 * 16)) mem.wordWrite(depthBase + i * 4, 0xffffffff)

    simulate(new KernelRenderCore(cfg, gfx)) { dut =>
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

      var cbR = false; var cbD = 0L
      var fbR = false; var fbD = 0L
      var kuR = false; var kuId = BigInt(0); var kuD = BigInt(0)
      var wuR = false; var wuId = BigInt(0); var wuD = BigInt(0)
      var guard = 0
      while (!dut.io.done.peek().litToBoolean && guard < 60000) {
        // command-buffer word port
        dut.io.cbMem.req.ready.poke(true.B)
        if (cbR) { dut.io.cbMem.resp.valid.poke(true.B); dut.io.cbMem.resp.bits.data.poke(cbD.U); cbR = false }
        else dut.io.cbMem.resp.valid.poke(false.B)
        if (dut.io.cbMem.req.valid.peek().litToBoolean && dut.io.cbMem.req.ready.peek().litToBoolean) {
          val a = dut.io.cbMem.req.bits.addr.peek().litValue.toLong
          if (!dut.io.cbMem.req.bits.write.peek().litToBoolean) { cbR = true; cbD = mem.word(a) }
        }
        // framebuffer word port (OM RMW)
        dut.io.fbMem.req.ready.poke(true.B)
        if (fbR) { dut.io.fbMem.resp.valid.poke(true.B); dut.io.fbMem.resp.bits.data.poke(fbD.U); fbR = false }
        else dut.io.fbMem.resp.valid.poke(false.B)
        if (dut.io.fbMem.req.valid.peek().litToBoolean && dut.io.fbMem.req.ready.peek().litToBoolean) {
          val a = dut.io.fbMem.req.bits.addr.peek().litValue.toLong
          val write = dut.io.fbMem.req.bits.write.peek().litToBoolean
          val data = dut.io.fbMem.req.bits.data.peek().litValue.toLong
          if (write) mem.wordWrite(a, data.toInt)
          else { fbR = true; fbD = mem.word(a) }
        }
        // compute-unit line port
        dut.io.kernelMemResp.valid.poke(kuR)
        if (kuR) {
          dut.io.kernelMemResp.bits.transactionId.poke(kuId.U)
          dut.io.kernelMemResp.bits.readData.poke(kuD.U)
          dut.io.kernelMemResp.bits.fault.poke(false.B)
        }
        if (dut.io.kernelMemReq.valid.peek().litToBoolean && dut.io.kernelMemReq.ready.peek().litToBoolean) {
          val a = dut.io.kernelMemReq.bits.address.peek().litValue.toLong
          val id = dut.io.kernelMemReq.bits.transactionId.peek().litValue
          val write = dut.io.kernelMemReq.bits.isWrite.peek().litToBoolean
          if (write) {
            mem.lineWrite(a, dut.io.kernelMemReq.bits.writeData.peek().litValue,
              dut.io.kernelMemReq.bits.byteMask.peek().litValue); kuD = 0
          } else kuD = mem.lineRead(a)
          kuId = id; kuR = true
        } else kuR = false
        // word->line bridge line port
        dut.io.kernelWordMemResp.valid.poke(wuR)
        if (wuR) {
          dut.io.kernelWordMemResp.bits.transactionId.poke(wuId.U)
          dut.io.kernelWordMemResp.bits.readData.poke(wuD.U)
          dut.io.kernelWordMemResp.bits.fault.poke(false.B)
        }
        if (dut.io.kernelWordMemReq.valid.peek().litToBoolean && dut.io.kernelWordMemReq.ready.peek().litToBoolean) {
          val a = dut.io.kernelWordMemReq.bits.address.peek().litValue.toLong
          val id = dut.io.kernelWordMemReq.bits.transactionId.peek().litValue
          val write = dut.io.kernelWordMemReq.bits.isWrite.peek().litToBoolean
          if (write) {
            mem.lineWrite(a, dut.io.kernelWordMemReq.bits.writeData.peek().litValue,
              dut.io.kernelWordMemReq.bits.byteMask.peek().litValue); wuD = 0
          } else wuD = mem.lineRead(a)
          wuId = id; wuR = true
        } else wuR = false
        dut.clock.step()
        guard += 1
      }
      assert(guard < 60000, "did not drain")

      def rgb(x: Int, y: Int): (Int, Int, Int) = {
        val c = mem.word(colorBase + (y * 16 + x) * 4).toInt
        (((c >> 24) & 0xff), ((c >> 16) & 0xff), ((c >> 8) & 0xff))
      }
      assert(rgb(5, 5) == (r, g, b),
        s"kernel-shaded pixel (5,5) should be ($r,$g,$b), got ${rgb(5, 5)}")
    }
  }
}
