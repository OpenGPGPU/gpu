package opengpu.system

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.{GpuCommandMmioRegs, GraphicsConfig, RenderHostRegs}
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

class GpuHostSystemAxiSpec extends AnyFlatSpec {
  behavior of "GpuHostSystemAxi"

  private def initialize(dut: GpuHostSystemAxi): Unit = {
    dut.io.s_axi_awvalid.poke(false.B)
    dut.io.s_axi_wvalid.poke(false.B)
    dut.io.s_axi_wlast.poke(false.B)
    dut.io.s_axi_bready.poke(false.B)
    dut.io.s_axi_arvalid.poke(false.B)
    dut.io.s_axi_rready.poke(false.B)
    dut.io.m_axi_awready.poke(false.B)
    dut.io.m_axi_wready.poke(false.B)
    dut.io.m_axi_bid.poke(0.U)
    dut.io.m_axi_bresp.poke(0.U)
    dut.io.m_axi_bvalid.poke(false.B)
    dut.io.m_axi_arready.poke(false.B)
    dut.io.m_axi_rid.poke(0.U)
    dut.io.m_axi_rdata.poke(0.U)
    dut.io.m_axi_rresp.poke(0.U)
    dut.io.m_axi_rlast.poke(false.B)
    dut.io.m_axi_rvalid.poke(false.B)
    dut.io.s_axi_aresetn.poke(false.B)
    dut.io.s_axi_aclk.step()
    dut.io.s_axi_aresetn.poke(true.B)
    dut.io.s_axi_aclk.step()
  }

  private def axiWrite(
    dut: GpuHostSystemAxi,
    address: Int,
    data: Int
  ): Unit = {
    dut.io.s_axi_awaddr.poke(address.U)
    dut.io.s_axi_awlen.poke(0.U)
    dut.io.s_axi_awsize.poke(2.U)
    dut.io.s_axi_awburst.poke(0.U)
    dut.io.s_axi_awvalid.poke(true.B)
    dut.io.s_axi_wdata.poke((data.toLong & 0xffffffffL).U)
    dut.io.s_axi_wstrb.poke(0xf.U)
    dut.io.s_axi_wlast.poke(true.B)
    dut.io.s_axi_wvalid.poke(true.B)
    dut.io.s_axi_bready.poke(true.B)

    var addressDone = false
    var dataDone = false
    var responseDone = false
    var cycles = 0
    while (!responseDone && cycles < 64) {
      if (!addressDone && dut.io.s_axi_awready.peek().litToBoolean)
        addressDone = true
      if (!dataDone && dut.io.s_axi_wready.peek().litToBoolean)
        dataDone = true
      if (dut.io.s_axi_bvalid.peek().litToBoolean)
        responseDone = true
      dut.io.s_axi_aclk.step()
      if (addressDone) dut.io.s_axi_awvalid.poke(false.B)
      if (dataDone) dut.io.s_axi_wvalid.poke(false.B)
      cycles += 1
    }
    assert(responseDone, f"AXI write to 0x$address%x timed out")
    dut.io.s_axi_awvalid.poke(false.B)
    dut.io.s_axi_wvalid.poke(false.B)
    dut.io.s_axi_wlast.poke(false.B)
    dut.io.s_axi_bready.poke(false.B)
  }

  private def axiRead(dut: GpuHostSystemAxi, address: Int): BigInt = {
    dut.io.s_axi_araddr.poke(address.U)
    dut.io.s_axi_arlen.poke(0.U)
    dut.io.s_axi_arsize.poke(2.U)
    dut.io.s_axi_arburst.poke(0.U)
    dut.io.s_axi_arvalid.poke(true.B)
    dut.io.s_axi_rready.poke(true.B)

    var addressDone = false
    var responseDone = false
    var result = BigInt(0)
    var cycles = 0
    while (!responseDone && cycles < 64) {
      if (!addressDone && dut.io.s_axi_arready.peek().litToBoolean)
        addressDone = true
      if (dut.io.s_axi_rvalid.peek().litToBoolean) {
        result = dut.io.s_axi_rdata.peek().litValue
        responseDone = true
      }
      dut.io.s_axi_aclk.step()
      if (addressDone) dut.io.s_axi_arvalid.poke(false.B)
      cycles += 1
    }
    assert(responseDone, f"AXI read from 0x$address%x timed out")
    dut.io.s_axi_arvalid.poke(false.B)
    dut.io.s_axi_rready.poke(false.B)
    result
  }

  private case class MemoryWrite(
    address: BigInt, id: BigInt, data: BigInt, mask: BigInt)

  private def acceptMemoryWrite(dut: GpuHostSystemAxi): MemoryWrite = {
    dut.io.m_axi_awready.poke(true.B)
    dut.io.m_axi_wready.poke(true.B)
    var address = BigInt(0)
    var id = BigInt(0)
    var data = BigInt(0)
    var mask = BigInt(0)
    var addressDone = false
    var dataDone = false
    var beat = 0
    var cycles = 0
    while ((!addressDone || !dataDone) && cycles < 160) {
      if (!addressDone && dut.io.m_axi_awvalid.peek().litToBoolean) {
        address = dut.io.m_axi_awaddr.peek().litValue
        id = dut.io.m_axi_awid.peek().litValue
        dut.io.m_axi_awburst.expect(1.U)
        addressDone = true
      }
      if (!dataDone && dut.io.m_axi_wvalid.peek().litToBoolean) {
        data |= dut.io.m_axi_wdata.peek().litValue << (beat * 64)
        mask |= dut.io.m_axi_wstrb.peek().litValue << (beat * 8)
        dataDone = dut.io.m_axi_wlast.peek().litToBoolean
        beat += 1
      }
      dut.io.s_axi_aclk.step()
      cycles += 1
    }
    assert(addressDone && dataDone, "AXI memory write timed out")
    dut.io.m_axi_awready.poke(false.B)
    dut.io.m_axi_wready.poke(false.B)
    dut.io.m_axi_bid.poke(id.U)
    dut.io.m_axi_bresp.poke(0.U)
    dut.io.m_axi_bvalid.poke(true.B)
    dut.io.m_axi_bready.expect(true.B)
    dut.io.s_axi_aclk.step()
    dut.io.m_axi_bvalid.poke(false.B)
    MemoryWrite(address, id, data, mask)
  }

  private def acceptMemoryRead(
    dut: GpuHostSystemAxi,
    line: BigInt,
    respond: Boolean = true
  ): (BigInt, BigInt) = {
    dut.io.m_axi_arready.poke(true.B)
    var address = BigInt(0)
    var id = BigInt(0)
    var cycles = 0
    while (!dut.io.m_axi_arvalid.peek().litToBoolean && cycles < 160) {
      dut.io.s_axi_aclk.step()
      cycles += 1
    }
    assert(cycles < 160, "AXI memory read address timed out")
    address = dut.io.m_axi_araddr.peek().litValue
    id = dut.io.m_axi_arid.peek().litValue
    dut.io.m_axi_arlen.expect(7.U)
    dut.io.m_axi_arsize.expect(3.U)
    dut.io.m_axi_arburst.expect(1.U)
    dut.io.s_axi_aclk.step()
    dut.io.m_axi_arready.poke(false.B)
    if (respond) {
      for (beat <- 0 until 8) {
        dut.io.m_axi_rid.poke(id.U)
        dut.io.m_axi_rdata.poke(((line >> (beat * 64)) &
          ((BigInt(1) << 64) - 1)).U)
        dut.io.m_axi_rresp.poke(0.U)
        dut.io.m_axi_rlast.poke((beat == 7).B)
        dut.io.m_axi_rvalid.poke(true.B)
        dut.io.m_axi_rready.expect(true.B)
        dut.io.s_axi_aclk.step()
      }
      dut.io.m_axi_rvalid.poke(false.B)
      dut.io.m_axi_rlast.poke(false.B)
    }
    (address, id)
  }

  /** Services the 64-bit AXI memory master until `until` holds, accepting any
    * mix of full-line and narrow reads and full-line writes. `readLine` supplies the 64-byte line
    * for a read address; `onWrite` receives every completed write burst as
    * (address, data, byte strobe). */
  private def serviceMemoryMaster(
    dut: GpuHostSystemAxi,
    readLine: BigInt => BigInt,
    readFault: BigInt => Boolean = _ => false
  )(onWrite: (BigInt, BigInt, BigInt) => Unit)(until: => Boolean): Unit = {
    val mask64 = (BigInt(1) << 64) - 1
    var arDone = false
    var arAddr = BigInt(0)
    var arId = BigInt(0)
    var rBeat = 0
    var rLine = BigInt(0)
    var rBeats = 8
    var rFault = false
    var awDone = false
    var awAddr = BigInt(0)
    var awId = BigInt(0)
    var wBeat = 0
    var wData = BigInt(0)
    var wStrb = BigInt(0)
    var wDone = false
    var bPending = false
    var guard = 0
    while (!until && guard < 20000) {
      // Read address channel.
      dut.io.m_axi_arready.poke((!arDone && rBeat == 0).B)
      if (!arDone && dut.io.m_axi_arvalid.peek().litToBoolean &&
          dut.io.m_axi_arready.peek().litToBoolean) {
        arAddr = dut.io.m_axi_araddr.peek().litValue
        arId = dut.io.m_axi_arid.peek().litValue
        rLine = readLine(arAddr) >> ((arAddr.toInt & 56) * 8)
        rBeats = dut.io.m_axi_arlen.peek().litValue.toInt + 1
        rFault = readFault(arAddr)
        arDone = true
        rBeat = 0
      }
      // Read data channel: narrow PTE reads use one lane-aligned beat.
      if (arDone) {
        dut.io.m_axi_rvalid.poke(true.B)
        dut.io.m_axi_rid.poke(arId.U)
        dut.io.m_axi_rdata.poke(((rLine >> (rBeat * 64)) & mask64).U)
        dut.io.m_axi_rresp.poke((if (rFault) 2 else 0).U)
        dut.io.m_axi_rlast.poke((rBeat == rBeats - 1).B)
        if (dut.io.m_axi_rready.peek().litToBoolean) {
          if (rBeat == rBeats - 1) { arDone = false; rBeat = 0 } else rBeat += 1
        }
      } else {
        dut.io.m_axi_rvalid.poke(false.B)
        dut.io.m_axi_rlast.poke(false.B)
      }
      // Write address channel.
      dut.io.m_axi_awready.poke((!awDone && !wDone).B)
      if (!awDone && dut.io.m_axi_awvalid.peek().litToBoolean &&
          dut.io.m_axi_awready.peek().litToBoolean) {
        awAddr = dut.io.m_axi_awaddr.peek().litValue
        awId = dut.io.m_axi_awid.peek().litValue
        awDone = true
      }
      // Write data channel.
      dut.io.m_axi_wready.poke((!wDone).B)
      if (!wDone && dut.io.m_axi_wvalid.peek().litToBoolean &&
          dut.io.m_axi_wready.peek().litToBoolean) {
        wData |= dut.io.m_axi_wdata.peek().litValue << (wBeat * 64)
        wStrb |= dut.io.m_axi_wstrb.peek().litValue << (wBeat * 8)
        wBeat += 1
        if (dut.io.m_axi_wlast.peek().litToBoolean) wDone = true
      }
      if (awDone && wDone && !bPending) {
        onWrite(awAddr, wData, wStrb)
        bPending = true
      }
      // Write response channel.
      dut.io.m_axi_bvalid.poke(bPending.B)
      dut.io.m_axi_bid.poke(awId.U)
      dut.io.m_axi_bresp.poke(0.U)
      if (bPending && dut.io.m_axi_bready.peek().litToBoolean) {
        bPending = false
        awDone = false
        wDone = false
        wBeat = 0
        wData = BigInt(0)
        wStrb = BigInt(0)
      }
      dut.io.s_axi_aclk.step()
      guard += 1
    }
    assert(guard < 20000, "AXI memory master service timed out")
  }
  it should "route an AXI-programmed clear through the shared L2" in {
    val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16)
    val gpu = GpuConfig(
      lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuHostSystemAxi(gfx, gpu)) { dut =>
      initialize(dut)

      val base = 0x6000
      val pattern = BigInt("12345678", 16)
      val expectedLine = BigInt(List.fill(16)("12345678").mkString, 16)
      axiWrite(dut, RenderHostRegs.CLEAR_BASE, base)
      axiWrite(dut, RenderHostRegs.CLEAR_BYTES, 64)
      axiWrite(dut, RenderHostRegs.CLEAR_PATTERN, pattern.toInt)

      axiWrite(dut, RenderHostRegs.CLEAR_START, 1)
      val write = acceptMemoryWrite(dut)
      assert(write.address == base)
      assert(write.mask == (BigInt(1) << 64) - 1)
      assert(write.data == expectedLine)

      var cycles = 0
      var status = axiRead(dut, RenderHostRegs.STATUS)
      while ((status & 0x8L) != 0L && cycles < 40) {
        status = axiRead(dut, RenderHostRegs.STATUS)
        cycles += 1
      }
      assert(cycles < 40, "clear did not retire through the shared L2")
      assert((status & 0xcL) == 0L,
        f"clear must finish without BUSY or ERROR set, status=0x$status%x")
    }
  }

  it should "program the Sv32 page-table base and flush the TLBs over AXI" in {
    val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16)
    val gpu = GpuConfig(l2Sets = 8, l2Ways = 2)
    simulate(new GpuHostSystemAxi(gfx, gpu)) { dut =>
      initialize(dut)
      // Sv32 mode (bit 31), ASID 0, root PPN 0x12345.
      val satp = 0x80012345
      axiWrite(dut, GpuCommandMmioRegs.VECTOR_SATP, satp)
      axiWrite(dut, GpuCommandMmioRegs.INSTRUCTION_SATP, satp)
      axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, 1)
      val expected = BigInt("80012345", 16)
      assert(axiRead(dut, GpuCommandMmioRegs.VECTOR_SATP) == expected,
        "vector satp must read back")
      assert(axiRead(dut, GpuCommandMmioRegs.INSTRUCTION_SATP) == expected,
        "instruction satp must read back")
    }
  }

  it should "deliver unified-command completions through the shared IRQ" in {
    val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16)
    val gpu = GpuConfig(
      lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuHostSystemAxi(gfx, gpu)) { dut =>
      initialize(dut)
      assert((axiRead(dut, RenderHostRegs.CAPABILITIES) & (1 << 6)) != 0L,
        "integrated host must advertise unified-command MMIO")
      axiWrite(dut, RenderHostRegs.IRQ, 1)
      axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 9)
      axiWrite(dut, GpuCommandMmioRegs.OPCODE,
        GpuCommandOpcode.fill.litValue.toInt)
      axiWrite(dut, GpuCommandMmioRegs.DESTINATION, 0x7000)
      axiWrite(dut, GpuCommandMmioRegs.BYTES, 64)
      axiWrite(dut, GpuCommandMmioRegs.PATTERN, 0x89abcdef)
      axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)

      val write = acceptMemoryWrite(dut)
      assert(write.address == 0x7000)

      var cycles = 0
      var commandStatus = axiRead(dut, GpuCommandMmioRegs.STATUS)
      while ((commandStatus & 0x2L) == 0L && cycles < 40) {
        commandStatus = axiRead(dut, GpuCommandMmioRegs.STATUS)
        cycles += 1
      }
      assert((commandStatus & 0x2L) != 0L,
        "unified completion did not reach the MMIO result slot")
      val completion = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
      assert((completion & 0xffL) == 9L)
      assert(((completion >> 8) & 0x7L) ==
        GpuCommandOpcode.fill.litValue)
      assert(((completion >> 15) & 1L) == 1L)
      assert(axiRead(dut, GpuCommandMmioRegs.COMPLETION_BYTES_LO) == 64L)
      assert(axiRead(dut, GpuCommandMmioRegs.COMPLETION_BYTES_HI) == 0L)
      dut.io.m_irq.expect(true.B)
      assert((axiRead(dut, RenderHostRegs.IRQ) & 0x3L) == 0x3L)

      axiWrite(dut, GpuCommandMmioRegs.COMPLETION_POP, 1)
      axiWrite(dut, RenderHostRegs.IRQ, 3)
      dut.io.m_irq.expect(false.B)
      assert((axiRead(dut, RenderHostRegs.IRQ) & 0x3L) == 0x1L)
    }
  }

  it should "complete a unified reset through the AXI control path" in {
    val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16)
    val gpu = GpuConfig(
      lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuHostSystemAxi(gfx, gpu)) { dut =>
      initialize(dut)
      assert((axiRead(dut, RenderHostRegs.CAPABILITIES) & (1 << 18)) != 0L,
        "integrated host must advertise the safe unified-command reset")
      axiWrite(dut, RenderHostRegs.IRQ, 1)

      // Idle command path: the drain completes and acknowledges by IRQ.
      axiWrite(dut, GpuCommandMmioRegs.RESET, 1)
      var cycles = 0
      var status = axiRead(dut, GpuCommandMmioRegs.STATUS)
      while ((status & 0x8L) != 0L && cycles < 40) {
        status = axiRead(dut, GpuCommandMmioRegs.STATUS)
        cycles += 1
      }
      assert((status & 0x8L) == 0L, "unified reset was never acknowledged")
      dut.io.m_irq.expect(true.B)

      // The reset leaves a clean, ready command slot behind.
      assert((status & 0x1L) != 0L, "command queue must report ready")
      axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 3)
      axiWrite(dut, GpuCommandMmioRegs.OPCODE,
        GpuCommandOpcode.fill.litValue.toInt)
      axiWrite(dut, GpuCommandMmioRegs.DESTINATION, 0x8000)
      axiWrite(dut, GpuCommandMmioRegs.BYTES, 64)
      axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
      val write = acceptMemoryWrite(dut)
      assert(write.address == 0x8000,
        "post-reset submission must reach the memory master")
    }
  }

  it should "reject work while a unified reset drains and recover afterwards" in {
    val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16)
    val gpu = GpuConfig(
      lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuHostSystemAxi(gfx, gpu)) { dut =>
      initialize(dut)
      axiWrite(dut, RenderHostRegs.IRQ, 1)

      // Start a fill whose memory transaction is deliberately left unserviced
      // so the command path stays busy while the reset drains.  This mirrors
      // the driver's recovery contract: RESET_BUSY maps to -EBUSY and a
      // submission racing the drain must be refused, not queued.
      axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 5)
      axiWrite(dut, GpuCommandMmioRegs.OPCODE,
        GpuCommandOpcode.fill.litValue.toInt)
      axiWrite(dut, GpuCommandMmioRegs.DESTINATION, 0x6000)
      axiWrite(dut, GpuCommandMmioRegs.BYTES, 64)
      axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)

      var cycles = 0
      while (!dut.io.m_axi_awvalid.peek().litToBoolean && cycles < 80) {
        dut.io.s_axi_aclk.step()
        cycles += 1
      }
      assert(cycles < 80, "fill never reached the memory master")

      axiWrite(dut, GpuCommandMmioRegs.RESET, 1)
      val draining = axiRead(dut, GpuCommandMmioRegs.STATUS)
      assert((draining & 0x8L) != 0L,
        "reset must report BUSY while in-flight work drains")

      // A submission during the drain is rejected and must not be executed.
      axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 6)
      axiWrite(dut, GpuCommandMmioRegs.DESTINATION, 0x7000)
      axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
      val rejected = axiRead(dut, GpuCommandMmioRegs.STATUS)
      assert((rejected & 0x10L) != 0L,
        "reset must report the refused submission")

      // Let the outstanding fill finish; the drain then acknowledges by IRQ.
      val inFlight = acceptMemoryWrite(dut)
      assert(inFlight.address == 0x6000)
      cycles = 0
      var status = axiRead(dut, GpuCommandMmioRegs.STATUS)
      while ((status & 0x8L) != 0L && cycles < 40) {
        status = axiRead(dut, GpuCommandMmioRegs.STATUS)
        cycles += 1
      }
      assert((status & 0x8L) == 0L, "unified reset never drained")
      assert((status & 0x1L) != 0L, "reset must leave a ready command slot")
      dut.io.m_irq.expect(true.B)

      // The refused command must not have reached memory; the next one does.
      axiWrite(dut, GpuCommandMmioRegs.STATUS, 0x10)
      axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 7)
      axiWrite(dut, GpuCommandMmioRegs.DESTINATION, 0x8000)
      axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
      val recovered = acceptMemoryWrite(dut)
      assert(recovered.address == 0x8000,
        "post-reset submission must reach the memory master")
    }
  }

  it should "execute a unified kernel through the AXI memory master" in {
    val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16)
    val gpu = GpuConfig(l2Sets = 8, l2Ways = 2)
    simulate(new GpuHostSystemAxi(gfx, gpu)) { dut =>
      initialize(dut)

      val programBase = 0x4000
      val kernargBase = 0x8000
      val lw = BigInt("0000a483", 16)
      val sw = BigInt("0090a223", 16)
      val cease = BigInt("30500073", 16)
      val programLine = lw | (sw << 32) | (cease << 64)
      val input = BigInt("cafe0001", 16)

      axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 11)
      axiWrite(dut, GpuCommandMmioRegs.OPCODE,
        GpuCommandOpcode.kernel.litValue.toInt)
      axiWrite(dut, GpuCommandMmioRegs.KERNEL_PC, programBase)
      axiWrite(dut, GpuCommandMmioRegs.KERNARG, kernargBase)
      Seq(GpuCommandMmioRegs.GRID_X, GpuCommandMmioRegs.GRID_Y,
        GpuCommandMmioRegs.GRID_Z, GpuCommandMmioRegs.LOCAL_X,
        GpuCommandMmioRegs.LOCAL_Y, GpuCommandMmioRegs.LOCAL_Z).foreach {
          address => axiWrite(dut, address, 1)
        }
      axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)

      val instructionRead = acceptMemoryRead(dut, programLine)
      assert(instructionRead._1 == programBase)
      val kernargRead = acceptMemoryRead(dut, input)
      assert(kernargRead._1 == kernargBase)
      val kernargWrite = acceptMemoryWrite(dut)
      assert(kernargWrite.address == kernargBase)
      assert((kernargWrite.mask & 0xf0) == 0xf0)
      assert(((kernargWrite.data >> 32) & 0xffffffffL) == input)

      var cycles = 0
      var status = axiRead(dut, GpuCommandMmioRegs.STATUS)
      while ((status & 0x2L) == 0L && cycles < 80) {
        status = axiRead(dut, GpuCommandMmioRegs.STATUS)
        cycles += 1
      }
      assert((status & 0x2L) != 0L, "kernel completion did not reach MMIO")
      val completion = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
      assert((completion & 0xffL) == 11L)
      assert(((completion >> 8) & 0x7L) ==
        GpuCommandOpcode.kernel.litValue)
      assert(((completion >> 15) & 1L) == 1L)
    }
  }

  it should "resolve a multi-row 4x region through the AXI unified command path" in {
    val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16)
    val gpu = GpuConfig(l2Sets = 8, l2Ways = 2)
    simulate(new GpuHostSystemAxi(gfx, gpu)) { dut =>
      initialize(dut)
      val srcBase = 0x1000L
      val dstBase = 0x2000L
      val srcStride = 64L
      val dstStride = 64L
      val width = 4
      val height = 2
      val samples = Seq(BigInt(0), BigInt("ffffffff", 16),
        BigInt("ff00ff00", 16), BigInt("00ff00ff", 16))
      val words = mutable.LongMap[BigInt]()
      for (y <- 0 until height; x <- 0 until width; s <- 0 until 4)
        words(srcBase + y * srcStride + (x * 4 + s) * 4L) = samples(s)
      def readLine(addr: BigInt): BigInt = {
        val base = addr.toLong & ~63L
        (0 until 16)
          .map(i => words.getOrElse(base + i * 4L, BigInt(0)) << (32 * i))
          .reduce(_ | _)
      }
      def writeLine(addr: BigInt, data: BigInt, strb: BigInt): Unit = {
        val base = addr.toLong & ~63L
        for (i <- 0 until 16) {
          var w = words.getOrElse(base + i * 4L, BigInt(0))
          for (b <- 0 until 4) {
            val byte = i * 4 + b
            if (((strb >> byte) & 1) != 0)
              w = (w & ~(BigInt(0xff) << (b * 8))) |
                (((data >> (byte * 8)) & 0xff) << (b * 8))
          }
          words(base + i * 4L) = w
        }
      }

      axiWrite(dut, RenderHostRegs.IRQ, 1)
      axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 7)
      axiWrite(dut, GpuCommandMmioRegs.OPCODE,
        GpuCommandOpcode.resolve.litValue.toInt)
      axiWrite(dut, GpuCommandMmioRegs.SOURCE, srcBase.toInt)
      axiWrite(dut, GpuCommandMmioRegs.DESTINATION, dstBase.toInt)
      axiWrite(dut, GpuCommandMmioRegs.WIDTH, width)
      axiWrite(dut, GpuCommandMmioRegs.HEIGHT, height)
      axiWrite(dut, GpuCommandMmioRegs.SOURCE_STRIDE, srcStride.toInt)
      axiWrite(dut, GpuCommandMmioRegs.DESTINATION_STRIDE, dstStride.toInt)
      axiWrite(dut, GpuCommandMmioRegs.SAMPLE_MODE, 2)
      axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)

      serviceMemoryMaster(dut, readLine)(writeLine) {
        dut.io.m_irq.peek().litToBoolean
      }

      dut.io.m_irq.expect(true.B)
      val completion = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
      assert((completion & 0xffL) == 7L, s"completion id was $completion")
      assert(((completion >> 8) & 0x7L) ==
        GpuCommandOpcode.resolve.litValue)
      assert(((completion >> 15) & 1L) == 1L)
      assert(axiRead(dut, GpuCommandMmioRegs.COMPLETION_BYTES_LO) == 32L)
      for (y <- 0 until height; x <- 0 until width) {
        val got = words.getOrElse(dstBase + y * dstStride + x * 4L, BigInt(0))
        assert(got == BigInt(0x80808080L),
          s"dst($x,$y)=0x${got.toString(16)} expected 0x80808080")
      }
    }
  }

  for ((busFault, textureBusFault) <- Seq(
    (false, false), (true, false), (false, true))) {
    it should s"report texture faults to the host and recover (pageBusFault=$busFault, textureBusFault=$textureBusFault)" in {
      val gfx = GraphicsConfig(screenWidth = 4, screenHeight = 4, subPixelBits = 8)
      val gpu = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
      simulate(new GpuHostSystemAxi(gfx, gpu)) { dut =>
        initialize(dut)
        val words = mutable.LongMap[BigInt]()
        val reads = mutable.ArrayBuffer[BigInt]()
        val cmdBase = 0x4000
        val colorBase = 0x8000
        val depthBase = 0x9000
        val rootBase = 0x30000
        val textureVa = 0x400000
        val texturePa = 0x800000
        val draw = Array.fill(44)(0)
        Seq((-65536, -65536), (65536, -65536), (-65536, 65536))
          .zipWithIndex.foreach { case ((x, y), i) =>
            draw(i * 4) = x; draw(i * 4 + 1) = y
            draw(i * 4 + 3) = 65536
            for (c <- 0 until 3) draw(12 + i * 3 + c) = 255
          }
        def ww(address: Long, value: Int): Unit =
          words(address) = BigInt(value.toLong & 0xffffffffL)
        draw.zipWithIndex.foreach { case (v, i) => ww(cmdBase + 4L * i, v) }
        ww(texturePa, 0xff00ffff)
        // The command-draw port translates through the same page tables, so
        // the low 4 MiB must identity-map uncached exactly as the driver's
        // global identity map does; the texture VA aliases on top.
        ww(rootBase, (2 << 8) | 0xcf)
        if (textureBusFault) ww(rootBase + 4, (0x800 << 10) | 0x43)
        def readLine(address: BigInt): BigInt = {
          reads += address
          val base = address.toLong & ~63L
          (0 until 16).map(i => words.getOrElse(base + i * 4L, BigInt(0)) << (i * 32))
            .reduce(_ | _)
        }
        def writeLine(address: BigInt, data: BigInt, mask: BigInt): Unit = {
          val base = address.toLong & ~63L
          for (b <- 0 until 64 if mask.testBit(b)) {
            val word = base + (b / 4) * 4L
            val shift = (b % 4) * 8
            words(word) = (words.getOrElse(word, BigInt(0)) & ~(BigInt(255) << shift)) |
              (((data >> (b * 8)) & 255) << shift)
          }
        }
        axiWrite(dut, GpuCommandMmioRegs.VECTOR_SATP, 0x80000000 | (rootBase >> 12))
        axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, 1)
        axiWrite(dut, RenderHostRegs.IRQ, 1)
        // Unified render descriptor: cmd/colour/depth/stride, depth test+write,
        // and a 1x1 texture at the virtual address the translator resolves.
        val descriptorBase = 0x50000
        val descriptor = Seq((1 << 16) | 1, cmdBase, colorBase, depthBase,
          16, 0x81, textureVa, (1 << 16) | 1, 0x101) ++ Seq.fill(7)(0)
        descriptor.zipWithIndex.foreach { case (v, i) =>
          ww(descriptorBase + i * 4L, v)
        }
        def submitRender(id: Int): Unit = {
          axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, id)
          axiWrite(dut, GpuCommandMmioRegs.OPCODE,
            GpuCommandOpcode.render.litValue.toInt)
          axiWrite(dut, GpuCommandMmioRegs.SOURCE, descriptorBase)
          axiWrite(dut, GpuCommandMmioRegs.BYTES, 64)
          axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
        }
        submitRender(1)
        serviceMemoryMaster(dut, readLine,
          address => (busFault && address == rootBase + 4) ||
            (textureBusFault && address == texturePa))(writeLine) {
          dut.io.m_irq.peek().litToBoolean
        }
        assert(reads.contains(BigInt(rootBase + 4)), "texture must attempt a page walk")
        assert(!reads.exists(a => a >= textureVa && a < textureVa + 4096),
          "faulted virtual address must never reach physical memory")
        assert((axiRead(dut, RenderHostRegs.STATUS) & 7) == 6, "DONE and ERROR, not BUSY")
        // Pop the unified completion before the next submission.
        axiWrite(dut, GpuCommandMmioRegs.COMPLETION_POP, 1)
        // Repair the PTE and run again: per-job failure cannot poison success.
        ww(rootBase + 4, (0x800 << 10) | 0x43)
        axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, 1)
        axiWrite(dut, RenderHostRegs.IRQ, 3)
        submitRender(2)
        serviceMemoryMaster(dut, readLine)(writeLine) {
          dut.io.m_irq.peek().litToBoolean
        }
        assert(reads.contains(BigInt(texturePa)), "repaired mapping must access translated PA")
        assert((axiRead(dut, RenderHostRegs.STATUS) & 7) == 2)
      }
    }
  }

}
