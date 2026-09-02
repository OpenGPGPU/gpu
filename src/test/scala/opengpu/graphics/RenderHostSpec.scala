package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

/** Verifies the M6 host interface: the MMIO register file, the engine control
  * state machine (START -> BUSY -> DONE), the completion interrupt, and that a
  * host programmes the registers and drives a real draw through `RenderCore`.
  */
class RenderHostSpec extends AnyFlatSpec {
  behavior of "RenderHost"

  private def q(v: Double): Int = (v * (1 << 16)).toInt

  /** Word-addressed (by byte address) memory model. */
  private class MemModel {
    val words = mutable.LongMap[Int]()
    def word(a: Long): Long = words.getOrElse(a, 0) & 0xffffffffL
    def wwrite(a: Long, d: Int): Unit = words(a) = d & 0xffffffff
  }

  private def encode(
    tri: Seq[((Int, Int, Int, Int), (Int, Int, Int), Int)]
  ): Seq[Int] = {
    val w = Seq.newBuilder[Int]
    for (i <- 0 until 3) { w += tri(i)._1._1; w += tri(i)._1._2; w += tri(i)._1._3; w += tri(i)._1._4 }
    for (i <- 0 until 3) { w += tri(i)._2._1; w += tri(i)._2._2; w += tri(i)._2._3 }
    for (i <- 0 until 3) { w += tri(i)._3 }
    w += 0; w += 0 // shader descriptor (unused on the fixed-function path)
    for (_ <- 0 until 6) { w += 0 } // uv0..uv2
    for (_ <- 0 until 8) { w += 0 } // state override + reserved
    w.result()
  }

  private def regWrite(
    dut: RenderHost,
    addr: Int,
    data: Int
  ): Unit = {
    dut.io.reg.req.valid.poke(true.B)
    dut.io.reg.req.bits.isWrite.poke(true.B)
    dut.io.reg.req.bits.addr.poke(addr.U)
    dut.io.reg.req.bits.data.poke(data.U)
    dut.io.reg.req.bits.strb.poke(0xf.U)
    dut.clock.step()
    dut.io.reg.req.valid.poke(false.B)
  }

  private def regRead(dut: RenderHost, addr: Int): BigInt = {
    dut.io.reg.req.valid.poke(true.B)
    dut.io.reg.req.bits.isWrite.poke(false.B)
    dut.io.reg.req.bits.addr.poke(addr.U)
    dut.io.reg.req.bits.data.poke(0.U)
    dut.io.reg.req.bits.strb.poke(0xf.U)
    assert(dut.io.reg.resp.valid.peek().litToBoolean,
      s"read of 0x$addr%x must produce a response")
    val data = dut.io.reg.resp.bits.data.peek().litValue
    dut.clock.step()
    dut.io.reg.req.valid.poke(false.B)
    data
  }

  /** Serves the renderer's command-buffer / framebuffer word ports against the
    * memory model until `until` holds (command-port writes, e.g. IH record
    * writes from the job queue, are acknowledged with a write response).  Once
    * `until` holds, `drainCycles` further serviced cycles run so in-flight
    * responses and pointer updates settle before the caller inspects state.
    * Both ports are multi-outstanding: requests are captured on fire and their
    * address-tagged responses presented on a later cycle (the parallel output
    * merger keeps several reads in flight). */
  private def serviceMem(
    dut: RenderHost,
    m: MemModel,
    maxCycles: Int,
    drainCycles: Int = 0
  )(until: => Boolean): Unit = {
    val cbQ = scala.collection.mutable.Queue.empty[(Boolean, Long, Long)]
    val fbQ = scala.collection.mutable.Queue.empty[(Boolean, Long, Long)]
    var guard = 0
    var untilSeen = false
    var drained = 0
    dut.io.kernelMemReq.ready.poke(true.B)
    dut.io.kernelMemResp.valid.poke(false.B)
    dut.io.kernelWordMemReq.ready.poke(true.B)
    dut.io.kernelWordMemResp.valid.poke(false.B)
    while ((!untilSeen || drained < drainCycles) && guard < maxCycles) {
      // Command-buffer port.
      dut.io.cbMem.req.ready.poke(true.B)
      if (cbQ.nonEmpty) {
        val (isWrite, a, d) = cbQ.head
        dut.io.cbMem.resp.valid.poke(true.B)
        dut.io.cbMem.resp.bits.data.poke(d.U)
        dut.io.cbMem.resp.bits.write.poke(isWrite.B)
        dut.io.cbMem.resp.bits.addr.poke(a.U)
        if (dut.io.cbMem.resp.ready.peek().litToBoolean) cbQ.dequeue()
      } else dut.io.cbMem.resp.valid.poke(false.B)
      if (dut.io.cbMem.req.valid.peek().litToBoolean &&
          dut.io.cbMem.req.ready.peek().litToBoolean) {
        val a = dut.io.cbMem.req.bits.addr.peek().litValue.toLong
        if (dut.io.cbMem.req.bits.write.peek().litToBoolean) {
          m.wwrite(a, dut.io.cbMem.req.bits.data.peek().litValue.toInt)
          cbQ.enqueue((true, a, 0L))
        } else cbQ.enqueue((false, a, m.word(a)))
      }

      // Framebuffer port.
      dut.io.fbMem.req.ready.poke(true.B)
      if (fbQ.nonEmpty) {
        val (isWrite, a, d) = fbQ.head
        dut.io.fbMem.resp.valid.poke(true.B)
        dut.io.fbMem.resp.bits.data.poke(d.U)
        dut.io.fbMem.resp.bits.write.poke(isWrite.B)
        dut.io.fbMem.resp.bits.addr.poke(a.U)
        if (dut.io.fbMem.resp.ready.peek().litToBoolean) fbQ.dequeue()
      } else dut.io.fbMem.resp.valid.poke(false.B)
      if (dut.io.fbMem.req.valid.peek().litToBoolean &&
          dut.io.fbMem.req.ready.peek().litToBoolean) {
        val a = dut.io.fbMem.req.bits.addr.peek().litValue.toLong
        if (dut.io.fbMem.req.bits.write.peek().litToBoolean)
          m.wwrite(a, dut.io.fbMem.req.bits.data.peek().litValue.toInt)
        else fbQ.enqueue((false, a, m.word(a)))
      }

      dut.clock.step()
      guard += 1
      if (!untilSeen && until) { untilSeen = true; drained = 0 }
      else if (untilSeen) drained += 1
    }
    assert(guard < maxCycles, "memory-serviced phase did not finish in time")
  }

  it should "expose the device ID and a program-able register file" in {
    simulate(new RenderHost(
      gpuConfig = GpuConfig(lanes = 4, warps = 2), fragCore = true,
      deviceId = 0x4755, version = 0x0001)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      assert(regRead(dut, RenderHostRegs.ID) == 0x47550001L,
        "device ID register must report device<<16 | version")
      assert(regRead(dut, RenderHostRegs.CAPABILITIES) == 0x803L,
         "fragment-core builds must advertise support, batch capacity and the job queue")

      // An unmapped address yields ok=false.
      dut.io.reg.req.valid.poke(true.B)
      dut.io.reg.req.bits.isWrite.poke(false.B)
      dut.io.reg.req.bits.addr.poke(0x200.U)
      dut.io.reg.req.bits.data.poke(0.U)
      dut.io.reg.req.bits.strb.poke(0xf.U)
      assert(!dut.io.reg.resp.bits.ok.peek().litToBoolean)
      dut.clock.step()
      dut.io.reg.req.valid.poke(false.B)

      // Unaligned (non 4-byte) address is rejected as well.
      dut.io.reg.req.valid.poke(true.B)
      dut.io.reg.req.bits.isWrite.poke(false.B)
      dut.io.reg.req.bits.addr.poke(0x11.U)
      dut.io.reg.req.bits.data.poke(0.U)
      dut.io.reg.req.bits.strb.poke(0xf.U)
      assert(!dut.io.reg.resp.bits.ok.peek().litToBoolean)
      dut.clock.step()
      dut.io.reg.req.valid.poke(false.B)

      // Program the configuration registers and read them back.
      regWrite(dut, RenderHostRegs.CMD_BASE, 0x4000)
      regWrite(dut, RenderHostRegs.CMD_COUNT, 1)
      regWrite(dut, RenderHostRegs.COLOR_BASE, 0x8000)
      regWrite(dut, RenderHostRegs.DEPTH_BASE, 0x9000)
      regWrite(dut, RenderHostRegs.STRIDE, 64)
      regWrite(dut, RenderHostRegs.DEPTH_TEST_ENABLE, 1)
      regWrite(dut, RenderHostRegs.DEPTH_FUNC, 0)
      regWrite(dut, RenderHostRegs.DEPTH_WRITE_ENABLE, 1)
      regWrite(dut, RenderHostRegs.CULL_MODE, 2)
      regWrite(dut, RenderHostRegs.TEX_BASE, 0xa000)
      regWrite(dut, RenderHostRegs.SCANOUT_BASE, 0xb000)
      regWrite(dut, RenderHostRegs.SCANOUT_STRIDE, 128)
      regWrite(dut, RenderHostRegs.SCANOUT_WIDTH, 16)
      regWrite(dut, RenderHostRegs.SCANOUT_HEIGHT, 16)
      regWrite(dut, RenderHostRegs.SCANOUT_FORMAT, 0)
      regWrite(dut, RenderHostRegs.SCANOUT_CONTROL, 1)
      assert(regRead(dut, RenderHostRegs.CMD_BASE) == 0x4000L)
      assert(regRead(dut, RenderHostRegs.CMD_COUNT) == 1L)
      assert(regRead(dut, RenderHostRegs.COLOR_BASE) == 0x8000L)
      assert(regRead(dut, RenderHostRegs.DEPTH_BASE) == 0x9000L)
      assert(regRead(dut, RenderHostRegs.STRIDE) == 64L)
      assert(regRead(dut, RenderHostRegs.DEPTH_TEST_ENABLE) == 1L)
      assert(regRead(dut, RenderHostRegs.DEPTH_FUNC) == 0L)
      assert(regRead(dut, RenderHostRegs.DEPTH_WRITE_ENABLE) == 1L)
      assert(regRead(dut, RenderHostRegs.CULL_MODE) == 2L)
      assert(regRead(dut, RenderHostRegs.TEX_BASE) == 0xa000L)
      assert(regRead(dut, RenderHostRegs.SCANOUT_BASE) == 0xb000L)
      assert(regRead(dut, RenderHostRegs.SCANOUT_STRIDE) == 128L)
      assert(regRead(dut, RenderHostRegs.SCANOUT_WIDTH) == 16L)
      assert(regRead(dut, RenderHostRegs.SCANOUT_HEIGHT) == 16L)
      assert(regRead(dut, RenderHostRegs.SCANOUT_FORMAT) == 0L)
      assert(regRead(dut, RenderHostRegs.SCANOUT_CONTROL) == 1L)
      assert(regRead(dut, RenderHostRegs.SCANOUT_STATUS) == 1L)
      // Idle status: BUSY=0, DONE=0.
      assert((regRead(dut, RenderHostRegs.STATUS) & 0x3) == 0L)
    }
  }

  it should "drive a draw from the register file and raise done and the interrupt" in {
    val config = GraphicsConfig(screenWidth = 16, screenHeight = 16, subPixelBits = 8)
    val cfg = GpuConfig(lanes = 4, warps = 2)
    val stride = 16 * 4
    val colorBase = 0x8000
    val depthBase = 0x9000
    val cmdBase = 0x4000

    def tri(x: Int, y: Int, d: Int) = ((x, y, 0, q(1.0)), (255, 0, 0), d)
    val record = Seq(
      tri(q(-1.0), q(-1.0), 0x10),
      tri(q(1.0), q(-1.0), 0x10),
      tri(q(-1.0), q(1.0), 0x10)
    )

    val m = new MemModel
    encode(record).zipWithIndex.foreach { case (w, i) => m.wwrite(cmdBase + i * 4, w) }
    for (i <- 0 until (16 * 16)) m.wwrite(depthBase + i * 4, 0xffffffff)

    simulate(new RenderHost(config, cfg, fragCore = false)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)

      // Program the render via the register file.
      regWrite(dut, RenderHostRegs.CMD_BASE, cmdBase)
      regWrite(dut, RenderHostRegs.CMD_COUNT, 1)
      regWrite(dut, RenderHostRegs.COLOR_BASE, colorBase)
      regWrite(dut, RenderHostRegs.DEPTH_BASE, depthBase)
      regWrite(dut, RenderHostRegs.STRIDE, stride)
      regWrite(dut, RenderHostRegs.DEPTH_TEST_ENABLE, 1)
      regWrite(dut, RenderHostRegs.DEPTH_FUNC, 0)
      regWrite(dut, RenderHostRegs.DEPTH_WRITE_ENABLE, 1)
      regWrite(dut, RenderHostRegs.CULL_MODE, 0)
      regWrite(dut, RenderHostRegs.IRQ, 1) // enable the completion interrupt

      // Service the renderer's command-buffer / framebuffer word ports.
      dut.io.cbMem.req.ready.poke(true.B)
      dut.io.cbMem.resp.valid.poke(false.B)
      dut.io.fbMem.req.ready.poke(true.B)
      dut.io.fbMem.resp.valid.poke(false.B)
      // The core-backed kernel queue is unused on the fixed-function path.
      dut.io.kernelMemReq.ready.poke(true.B)
      dut.io.kernelMemResp.valid.poke(false.B)
      dut.io.kernelWordMemReq.ready.poke(true.B)
      dut.io.kernelWordMemResp.valid.poke(false.B)

      // Kick the engine.
      regWrite(dut, RenderHostRegs.CONTROL, 1)
      serviceMem(dut, m, 60000)(dut.io.irq.peek().litToBoolean)

      // Completion state: BUSY=0, DONE=1; the interrupt fired.
      assert(dut.io.irq.peek().litToBoolean, "completion interrupt must assert")
      assert((regRead(dut, RenderHostRegs.STATUS) & 0x3) == 0x2L,
        "STATUS must report DONE (busy cleared)")

      def rgb(x: Int, y: Int): (Int, Int, Int) = {
        val c = m.word(colorBase + (y * 16 + x) * 4).toInt
        (((c >> 24) & 0xff), ((c >> 16) & 0xff), ((c >> 8) & 0xff))
      }
      // The register-file-driven draw rendered and depth-tested the triangle.
      assert(rgb(5, 5) == (255, 0, 0), s"interior (5,5) should be red, got ${rgb(5, 5)}")
      assert(m.word(depthBase + (5 * 16 + 5) * 4) == 0x10,
        s"depth (5,5) should be 0x10, got 0x${m.word(depthBase + (5 * 16 + 5) * 4).toHexString}")

      // Clearing DONE by writing 1 to STATUS's DONE bit (W1C).
      regWrite(dut, RenderHostRegs.STATUS, 0x2) // bit1 = DONE clear
      assert((regRead(dut, RenderHostRegs.STATUS) & 0x2) == 0L,
        "writing the DONE bit must clear it")

      // Clear the interrupt by writing IRQ bit1 = 1 (W1C on PENDING).
      regWrite(dut, RenderHostRegs.IRQ, 0x3) // ENABLE=1, clear PENDING
      assert(!dut.io.irq.peek().litToBoolean,
        "clearing IRQ.PENDING must deassert the interrupt")
      assert((regRead(dut, RenderHostRegs.IRQ) & 0x1) == 0x1,
        "acknowledging IRQ.PENDING must preserve IRQ.ENABLE")
    }
  }

  it should "run queued jobs from the host ring and record completions in the IH ring" in {
    val config = GraphicsConfig(screenWidth = 16, screenHeight = 16, subPixelBits = 8)
    val cfg = GpuConfig(lanes = 4, warps = 2)
    val stride = 16 * 4
    val colorBase = 0x8000
    val depthBase = 0x9000
    val ringBase = 0x10000
    val ihBase = 0x20000
    val wordsPerJob = 16
    val wordsPerIh = 4

    def tri(x: Int, y: Int, d: Int, c: (Int, Int, Int)) =
      ((x, y, 0, q(1.0)), c, d)
    def encodeDraw(record: Seq[((Int, Int, Int, Int), (Int, Int, Int), Int)]) = {
      val w = Seq.newBuilder[Int]
      for (i <- 0 until 3) {
        w += record(i)._1._1; w += record(i)._1._2
        w += record(i)._1._3; w += record(i)._1._4
      }
      for (i <- 0 until 3) { w += record(i)._2._1; w += record(i)._2._2; w += record(i)._2._3 }
      for (i <- 0 until 3) { w += record(i)._3 }
      w += 0; w += 0 // shader descriptor (unused on the fixed-function path)
      for (_ <- 0 until 6) { w += 0 } // uv0..uv2
      for (_ <- 0 until 8) { w += 0 } // state override + reserved
      w.result()
    }

    // Job 1: red triangle at depth 0x20; job 2: green triangle closer (0x10).
    val record1 = encodeDraw(Seq(
      tri(q(-1.0), q(-1.0), 0x20, (255, 0, 0)),
      tri(q(1.0), q(-1.0), 0x20, (255, 0, 0)),
      tri(q(-1.0), q(1.0), 0x20, (255, 0, 0))))
    val record2 = encodeDraw(Seq(
      tri(q(-1.0), q(-1.0), 0x10, (0, 255, 0)),
      tri(q(1.0), q(-1.0), 0x10, (0, 255, 0)),
      tri(q(-1.0), q(1.0), 0x10, (0, 255, 0))))
    val cmdBase1 = 0x4000
    val cmdBase2 = 0x4200

    def descriptor(jobId: Int, count: Int, cmdBase: Int): Seq[Int] =
      Seq((jobId & 0xffff) | ((count & 0xffff) << 16),
        cmdBase, colorBase, depthBase, stride,
        // depth test on (LESS), depth write on, cull none
        1 | (1 << 7),
        0, 0, 0) ++ Seq.fill(7)(0)

    val m = new MemModel
    record1.zipWithIndex.foreach { case (w, i) => m.wwrite(cmdBase1 + i * 4, w) }
    record2.zipWithIndex.foreach { case (w, i) => m.wwrite(cmdBase2 + i * 4, w) }
    for (i <- 0 until (16 * 16)) m.wwrite(depthBase + i * 4, 0xffffffff)
    descriptor(1, 1, cmdBase1).zipWithIndex.foreach {
      case (w, i) => m.wwrite(ringBase + i * 4, w)
    }
    descriptor(2, 1, cmdBase2).zipWithIndex.foreach {
      case (w, i) => m.wwrite(ringBase + wordsPerJob * 4 + i * 4, w)
    }

    simulate(new RenderHost(config, cfg, fragCore = false)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)

      dut.io.cbMem.req.ready.poke(true.B)
      dut.io.cbMem.resp.valid.poke(false.B)
      dut.io.fbMem.req.ready.poke(true.B)
      dut.io.fbMem.resp.valid.poke(false.B)

      // Program the rings, ring the doorbell, then enable the queue.
      regWrite(dut, RenderHostRegs.JOB_RING_BASE, ringBase)
      regWrite(dut, RenderHostRegs.JOB_RING_SIZE, 2)
      regWrite(dut, RenderHostRegs.JOB_WPTR, 2) // two descriptors queued
      regWrite(dut, RenderHostRegs.IH_BASE, ihBase)
      regWrite(dut, RenderHostRegs.IH_SIZE, 4)
      regWrite(dut, RenderHostRegs.JOB_CONTROL, 1) // ENABLE
      regWrite(dut, RenderHostRegs.IRQ, 1)         // completion IRQ enable

      // Wait until job 2's IH record has been written into host memory.
      // (The condition must not step the clock: register reads would let
      // in-flight memory handshakes complete unserviced.)
      serviceMem(dut, m, 200000, drainCycles = 8) {
        m.word(ihBase + wordsPerIh * 4) != 0
      }

      // Both jobs consumed; the device read pointer caught the doorbell up.
      assert(regRead(dut, RenderHostRegs.JOB_RPTR) == 2L,
        "both descriptors must be fetched from the ring")
      assert(regRead(dut, RenderHostRegs.IH_WPTR) == 2L,
        "both completions must be recorded in the IH ring")
      assert((regRead(dut, RenderHostRegs.JOB_CONTROL) & 0x100L) == 0L,
        "the queue must not report a running job at the end")

      // IH record details: job id, DONE flag, ring slot, status.
      def ihWord(entry: Int, word: Int): BigInt =
        m.word(ihBase + entry * wordsPerIh * 4 + word * 4)
      for ((jobId, entry) <- Seq((1, 0), (2, 1))) {
        val hdr = ihWord(entry, 0)
        assert((hdr & 0xffff) == jobId,
          s"IH entry $entry must carry job id $jobId, got $hdr")
        assert((hdr & (1 << 16)) != 0, s"IH entry $entry must flag DONE")
        assert((hdr & (1 << 17)) == 0, s"IH entry $entry must not flag ERROR")
        assert((ihWord(entry, 1) & 0xffff) == entry,
          s"IH entry $entry must carry the job-ring slot index")
        assert(ihWord(entry, 2) == 0, s"IH entry $entry must report status 0")
      }

      // Jobs ran strictly in order: the closer green triangle wins (5,5).
      val c = m.word(colorBase + (5 * 16 + 5) * 4).toInt
      assert(((c >> 16) & 0xff) == 255 && ((c >> 8) & 0xff) == 0 &&
        ((c >> 24) & 0xff) == 0, s"(5,5) should be green after job 2, got 0x${c.toHexString}")
      assert(m.word(depthBase + (5 * 16 + 5) * 4) == 0x10,
        s"depth (5,5) must come from job 2, got " +
        s"0x${m.word(depthBase + (5 * 16 + 5) * 4).toHexString}")

      // Completion raised the interrupt; STATUS reports DONE.
      assert((regRead(dut, RenderHostRegs.STATUS) & 0x3) == 0x2L,
        "STATUS must report DONE after the last queued job")
    }
  }
}
