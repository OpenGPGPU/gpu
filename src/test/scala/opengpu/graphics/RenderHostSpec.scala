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
    def line(a: Long): BigInt = (0 until 16).foldLeft(BigInt(0)) {
      case (value, i) => value | (BigInt(word(a + i * 4)) << (i * 32))
    }
    def lwrite(a: Long, d: BigInt): Unit =
      for (i <- 0 until 16)
        wwrite(a + i * 4, (d >> (i * 32)).toInt)
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
    val kwQ = scala.collection.mutable.Queue.empty[(Boolean, Long, BigInt, BigInt)]
    var guard = 0
    var untilSeen = false
    var drained = 0
    dut.io.kernelMemReq.ready.poke(true.B)
    dut.io.kernelMemResp.valid.poke(false.B)
    dut.io.kernelWordMemReq.ready.poke(true.B)
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
        fbQ.enqueue((dut.io.fbMem.req.bits.write.peek().litToBoolean, a, m.word(a)))
      }

      // Shared line port used by core staging, fill, blit and strided copy.
      dut.io.kernelWordMemReq.ready.poke(true.B)
      if (kwQ.nonEmpty) {
        val (isWrite, a, d, id) = kwQ.head
        dut.io.kernelWordMemResp.valid.poke(true.B)
        dut.io.kernelWordMemResp.bits.readData.poke(d.U)
        dut.io.kernelWordMemResp.bits.transactionId.poke(id.U)
        dut.io.kernelWordMemResp.bits.fault.poke(false.B)
        if (dut.io.kernelWordMemResp.ready.peek().litToBoolean) kwQ.dequeue()
      } else dut.io.kernelWordMemResp.valid.poke(false.B)
      if (dut.io.kernelWordMemReq.valid.peek().litToBoolean &&
          dut.io.kernelWordMemReq.ready.peek().litToBoolean) {
        val a = dut.io.kernelWordMemReq.bits.address.peek().litValue.toLong
        val id = dut.io.kernelWordMemReq.bits.transactionId.peek().litValue
        val isWrite = dut.io.kernelWordMemReq.bits.isWrite.peek().litToBoolean
        if (isWrite) {
          val d = dut.io.kernelWordMemReq.bits.writeData.peek().litValue
          m.lwrite(a, d)
          kwQ.enqueue((true, a, 0, id))
        } else kwQ.enqueue((false, a, m.line(a), id))
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
      dut.io.externalCompletion.poke(false.B)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      assert(regRead(dut, RenderHostRegs.ID) == 0x47550001L,
        "device ID register must report device<<16 | version")
      assert(regRead(dut, RenderHostRegs.CAPABILITIES) == 0xa08b9L,
         "fragment-core builds must advertise support, batch capacity, persistent depth and programmable MSAA")

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
      regWrite(dut, RenderHostRegs.MSAA_CONFIG, 2)
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
      assert(regRead(dut, RenderHostRegs.MSAA_CONFIG) == 2L)
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

  it should "advertise the vertex-core capability when elaborated" in {
    simulate(new RenderHost(
      gpuConfig = GpuConfig(lanes = 4, warps = 2), fragCore = true,
      vertCore = true)) { dut =>
      dut.io.externalCompletion.poke(false.B)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      assert(regRead(dut, RenderHostRegs.CAPABILITIES) == 0xa08bdL,
        "shared vertex/fragment-core builds must advertise both shader stages and programmable MSAA")
    }
  }

  it should "latch an external completion in the shared IRQ pending bit" in {
    simulate(new RenderHost(gpuConfig = GpuConfig(lanes = 4, warps = 2))) {
      dut =>
        dut.io.externalCompletion.poke(false.B)
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        regWrite(dut, RenderHostRegs.IRQ, 1)

        dut.io.externalCompletion.poke(true.B)
        dut.clock.step()
        dut.io.externalCompletion.poke(false.B)
        dut.io.irq.expect(true.B)
        assert((regRead(dut, RenderHostRegs.IRQ) & 0x3L) == 0x3L)

        regWrite(dut, RenderHostRegs.IRQ, 3)
        dut.io.irq.expect(false.B)
        assert((regRead(dut, RenderHostRegs.IRQ) & 0x3L) == 0x1L)
    }
  }

  it should "copy an aligned colour region through the hardware blit engine" in {
    val cfg = GpuConfig(lanes = 4, warps = 2)
    val source = 0x1000
    val destination = 0x2000
    val m = new MemModel
    for (i <- 0 until 16) m.wwrite(source + i * 4, 0x10203040 + i)

    simulate(new RenderHost(gpuConfig = cfg)) { dut =>
      dut.io.externalCompletion.poke(false.B)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.cbMem.req.ready.poke(true.B)
      dut.io.cbMem.resp.valid.poke(false.B)
      dut.io.fbMem.req.ready.poke(true.B)
      dut.io.fbMem.resp.valid.poke(false.B)
      dut.io.kernelMemReq.ready.poke(true.B)
      dut.io.kernelMemResp.valid.poke(false.B)

      regWrite(dut, RenderHostRegs.BLIT_SRC_BASE, source)
      regWrite(dut, RenderHostRegs.BLIT_DST_BASE, destination)
      regWrite(dut, RenderHostRegs.BLIT_BYTES, 64)
      regWrite(dut, RenderHostRegs.BLIT_START, 1)
      serviceMem(dut, m, 100, drainCycles = 8) {
        (0 until 16).forall(i =>
          m.word(destination + i * 4) == m.word(source + i * 4))
      }
      assert((regRead(dut, RenderHostRegs.STATUS) & 0x14) == 0,
        "successful blit must clear BLIT_BUSY without setting ERROR")
    }
  }

  it should "copy selected rows through the hardware strided engine" in {
    val cfg = GpuConfig(lanes = 4, warps = 2)
    val source = 0x1000
    val destination = 0x4000
    val sourceStride = 128
    val destinationStride = 192
    val m = new MemModel
    for (row <- 0 until 2; i <- 0 until 16)
      m.wwrite(source + row * sourceStride + i * 4,
        0x10203040 + row * 0x100 + i)

    simulate(new RenderHost(gpuConfig = cfg)) { dut =>
      dut.io.externalCompletion.poke(false.B)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.cbMem.req.ready.poke(true.B)
      dut.io.cbMem.resp.valid.poke(false.B)
      dut.io.fbMem.req.ready.poke(true.B)
      dut.io.fbMem.resp.valid.poke(false.B)
      dut.io.kernelMemReq.ready.poke(true.B)
      dut.io.kernelMemResp.valid.poke(false.B)

      regWrite(dut, RenderHostRegs.STRIDED_SRC_BASE, source)
      regWrite(dut, RenderHostRegs.STRIDED_DST_BASE, destination)
      regWrite(dut, RenderHostRegs.STRIDED_WIDTH, 64)
      regWrite(dut, RenderHostRegs.STRIDED_HEIGHT, 2)
      regWrite(dut, RenderHostRegs.STRIDED_SRC_STRIDE, sourceStride)
      regWrite(dut, RenderHostRegs.STRIDED_DST_STRIDE, destinationStride)
      regWrite(dut, RenderHostRegs.STRIDED_START, 1)
      serviceMem(dut, m, 200, drainCycles = 8) {
        (0 until 2).forall(row => (0 until 16).forall(i =>
          m.word(destination + row * destinationStride + i * 4) ==
            m.word(source + row * sourceStride + i * 4)))
      }
      assert((regRead(dut, RenderHostRegs.STATUS) & 0x24) == 0,
        "successful strided copy must clear STRIDED_BUSY without ERROR")
      assert(m.word(destination + 64) == 0,
        "strided copy must preserve the destination row gap")
    }
  }

  it should "advertise MSAA on both backends, selecting the backend by bit 0" in {
    val cfg = GpuConfig(lanes = 4, warps = 2)
    for ((fragCore, expected) <- Seq(false -> 0xa08b8L, true -> 0xa08b9L)) {
      simulate(new RenderHost(gpuConfig = cfg, fragCore = fragCore)) { dut =>
        dut.io.externalCompletion.poke(false.B)
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        val cap = regRead(dut, RenderHostRegs.CAPABILITIES)
        assert((cap & (1L << 7)) != 0L,
          s"fragCore=$fragCore must advertise MSAA, got 0x${cap.toString(16)}")
        assert(((cap >> 16) & 0x3L) == 2L,
          s"maximum sample mode must be log2(4) = 2, got 0x${cap.toString(16)}")
        assert(cap == expected,
          s"fragCore=$fragCore capability word 0x${cap.toString(16)} != 0x${expected.toHexString}")
        // The render backend is the fragment-core bit's business: bit 7 says
        // only that a nonzero sampleMode is accepted by whichever path bit 7
        // pairs with.
        assert(((cap >> GpuCapabilities.FragmentCore) & 1L) ==
          (if (fragCore) 1L else 0L),
          "the backend must be selected by the fragment-core bit, not the MSAA bit")
      }
    }
  }

  for (mode <- Seq(0, 2)) {
    it should s"run a unified render command (mode=$mode) through the command port" in {
      val config = GraphicsConfig(screenWidth = 16, screenHeight = 16, subPixelBits = 8)
      val cfg = GpuConfig(lanes = 4, warps = 2)
      val m = new MemModel
      val descriptorBase = 0x1000
      val cmdBase = 0x2000
      val colorBase = 0x8000
      val depthBase = 0x9000
      val samples = 1 << mode
      val stride = 16 * samples * 4

      val verts = Seq((q(-1.0), q(-1.0)), (q(1.0), q(-1.0)), (q(-1.0), q(1.0)))
      val record = {
        val w = Seq.newBuilder[Int]
        for ((x, y) <- verts) { w += x; w += y; w += 0; w += q(1.0) }
        for (_ <- verts) { w += 255; w += 0; w += 0 }
        for (_ <- verts) { w += 0x10 }
        w += 0; w += 0
        for (_ <- 0 until 6) { w += 0 }
        for (_ <- 0 until 8) { w += 0 }
        w.result()
      }
      // Word 5: depth-test enable (bit0), LESS (bits 6:4 = 0), depth write (bit7).
      // Word 9: sample mode.
      val descriptor = Seq((1 << 16) | 3, cmdBase, colorBase, depthBase,
        stride, 0x81, 0, 0, 0, mode) ++ Seq.fill(6)(0)
      record.zipWithIndex.foreach { case (w, i) => m.wwrite(cmdBase + i * 4, w) }
      descriptor.zipWithIndex.foreach {
        case (w, i) => m.wwrite(descriptorBase + i * 4, w)
      }
      val words = 16 * 16 * samples
      for (i <- 0 until words) m.wwrite(colorBase + i * 4, 0x12345678)
      for (i <- 0 until words) m.wwrite(depthBase + i * 4, 0x00ffffff)

      simulate(new RenderHost(config, cfg, fragCore = false)) { dut =>
        dut.io.externalCompletion.poke(false.B)
        dut.io.renderCompletion.ready.poke(false.B)
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)

        dut.io.renderCommand.bits.descriptorId.poke(3.U)
        dut.io.renderCommand.bits.descriptorAddress.poke(descriptorBase.U)
        dut.io.renderCommand.bits.bytes.poke(160.U)
        dut.io.renderCommand.valid.poke(true.B)

        serviceMem(dut, m, 400000, drainCycles = 4) {
          dut.io.renderCompletion.valid.peek().litToBoolean
        }
        dut.io.renderCommand.valid.poke(false.B)

        dut.io.renderCompletion.valid.expect(true.B)
        dut.io.renderCompletion.bits.descriptorId.expect(3.U)
        dut.io.renderCompletion.bits.success.expect(true.B)
        // Every covered sample of pixel (5,5) takes the draw colour.
        for (s <- 0 until samples) {
          val addr = colorBase + 5 * stride + (5 * samples + s) * 4
          assert(m.word(addr) == 0xff0000ffL,
            s"mode=$mode sample $s of (5,5) should be red, " +
              f"got 0x${m.word(addr)}%08x")
        }
        assert(m.word(colorBase) == 0x12345678L,
          "an uncovered pixel must keep its caller-initialized sentinel")
      }
    }
  }

  for (capacity <- Seq(1, 2, 4)) {
    it should s"reject invalid descriptor sample words and recover (capacity=$capacity)" in {
      simulate(new RenderHost(
        GraphicsConfig(screenWidth = 16, screenHeight = 16, maxSampleCount = capacity),
        GpuConfig(lanes = 4, warps = 2))) { dut =>
        dut.io.externalCompletion.poke(false.B)
        dut.io.renderCommand.valid.poke(false.B)
        dut.io.renderCompletion.ready.poke(false.B)
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        val m = new MemModel
        val modes = Seq(0x100, 3, 0x80000000) ++
          (Integer.numberOfTrailingZeros(capacity) + 1 to 2) ++ Seq(0)
        for ((mode, id) <- modes.zipWithIndex) {
          val base = 0x1000
          for (i <- 0 until 16) m.wwrite(base + i * 4, if (i == 9) mode else 0)
          dut.io.renderCommand.bits.descriptorId.poke(id.U)
          dut.io.renderCommand.bits.descriptorAddress.poke(base.U)
          dut.io.renderCommand.bits.bytes.poke(64.U)
          dut.io.renderCommand.valid.poke(true.B)
          dut.io.renderCommand.ready.expect(true.B)
          dut.clock.step()
          dut.io.renderCommand.valid.poke(false.B)
          serviceMem(dut, m, 2000) {
            dut.io.fbMem.req.valid.expect(false.B)
            dut.io.renderCompletion.valid.peek().litToBoolean
          }
          dut.io.renderCompletion.bits.descriptorId.expect(id.U)
          dut.io.renderCompletion.bits.success.expect((mode == 0).B)
          dut.io.renderCompletion.bits.status.expect((if (mode == 0) 0 else 2).U)
          // Completion backpressure keeps the engine owned and reset draining.
          dut.io.drained.expect(false.B)
          dut.io.renderCommand.ready.expect(false.B)
          dut.clock.step(8)
          dut.io.renderCompletion.valid.expect(true.B)
          dut.io.renderCompletion.ready.poke(true.B)
          dut.clock.step()
          dut.io.renderCompletion.ready.poke(false.B)
          dut.io.drained.expect(true.B)
        }
      }
    }
  }

  it should "fail a descriptor fetch fault before launch and retain it until completion" in {
    simulate(new RenderHost(
      GraphicsConfig(screenWidth = 16, screenHeight = 16),
      GpuConfig(lanes = 4, warps = 2), textureFaultReporting = true)) { dut =>
      dut.io.externalCompletion.poke(false.B)
      dut.io.textureFault.get.poke(false.B)
      dut.io.renderCompletion.ready.poke(false.B)
      dut.io.cbMem.req.ready.poke(true.B)
      dut.io.cbMem.resp.valid.poke(false.B)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.renderCommand.valid.poke(true.B)
      dut.io.renderCommand.bits.descriptorAddress.poke(0x1000.U)
      dut.io.renderCommand.bits.descriptorId.poke(7.U)
      dut.io.renderCommand.bits.bytes.poke(64.U)
      dut.clock.step()
      dut.io.renderCommand.valid.poke(false.B)
      dut.io.cbMem.req.valid.expect(true.B)
      dut.clock.step()
      dut.io.cbMem.resp.valid.poke(true.B)
      dut.io.cbMem.resp.bits.data.poke(0.U)
      dut.io.cbMem.resp.bits.addr.poke(0x1000.U)
      dut.io.cbMem.resp.bits.write.poke(false.B)
      dut.io.textureFault.get.poke(true.B)
      dut.clock.step()
      dut.io.cbMem.resp.valid.poke(false.B)
      dut.io.textureFault.get.poke(false.B)
      dut.clock.step(4)
      dut.io.cbMem.req.valid.expect(false.B)
      dut.io.fbMem.req.valid.expect(false.B)
      dut.io.renderCompletion.valid.expect(true.B)
      dut.io.renderCompletion.bits.success.expect(false.B)
      dut.io.renderCompletion.bits.status.expect(1.U)
      dut.io.drained.expect(false.B)
      dut.io.renderCompletion.ready.poke(true.B)
      dut.clock.step()
      dut.io.drained.expect(true.B)
    }
  }

}
