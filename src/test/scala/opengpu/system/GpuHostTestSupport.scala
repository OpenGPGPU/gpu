package opengpu.system

import chisel3._
import chisel3.simulator.EphemeralSimulator._

/** Shared cycle-accurate AXI stimulus for integration tests and workload measurement. */
trait GpuHostTestSupport {
  protected def initialize(dut: GpuHostSystemAxi): Unit = {
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

  protected def axiWrite(
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

  protected def axiRead(dut: GpuHostSystemAxi, address: Int): BigInt = {
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

  protected case class MemoryWrite(
    address: BigInt, id: BigInt, data: BigInt, mask: BigInt)

  protected def acceptMemoryWrite(dut: GpuHostSystemAxi): MemoryWrite = {
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

  protected def acceptMemoryRead(
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
    * (address, data, byte strobe). `onReadAccepted` runs after the AR handshake
    * with memory handshakes paused, allowing control-MMIO traffic while the
    * burst remains outstanding. Use a positive `readResponseDelay` to invoke
    * it before any R beat has been presented. */
  protected def serviceMemoryMaster(
    dut: GpuHostSystemAxi,
    readLine: BigInt => BigInt,
    readFault: BigInt => Boolean = _ => false,
    writeAckDelay: Int = 0,
    maxCycles: Int = 20000,
    readResponseDelay: Int = 0,
    onReadAccepted: BigInt => Unit = _ => ()
  )(onWrite: (BigInt, BigInt, BigInt) => Unit)(until: => Boolean): Unit = {
    val mask64 = (BigInt(1) << 64) - 1
    var arDone = false
    var arAddr = BigInt(0)
    var arId = BigInt(0)
    var rBeat = 0
    var rLine = BigInt(0)
    var rBeats = 8
    var rFault = false
    var rDelay = 0
    var awDone = false
    var awAddr = BigInt(0)
    var awId = BigInt(0)
    var wBeat = 0
    var wData = BigInt(0)
    var wStrb = BigInt(0)
    var wDone = false
    var bPending = false
    var bDelay = 0
    var guard = 0
    while (!until && guard < maxCycles) {
      var acceptedRead = false
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
        rDelay = readResponseDelay
        acceptedRead = true
      }
      // Read data channel: narrow PTE reads use one lane-aligned beat.
      if (arDone && rDelay == 0) {
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
        if (rDelay > 0) rDelay -= 1
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
        bDelay = writeAckDelay
      }
      // Write response channel.
      dut.io.m_axi_bvalid.poke((bPending && bDelay == 0).B)
      if (bDelay > 0) bDelay -= 1
      dut.io.m_axi_bid.poke(awId.U)
      dut.io.m_axi_bresp.poke(0.U)
      if (dut.io.m_axi_bvalid.peek().litToBoolean && dut.io.m_axi_bready.peek().litToBoolean) {
        bPending = false
        awDone = false
        wDone = false
        wBeat = 0
        wData = BigInt(0)
        wStrb = BigInt(0)
      }
      dut.io.s_axi_aclk.step()
      if (acceptedRead) {
        // Pause memory handshakes while the hook drives control MMIO. Local
        // burst state survives, including an accepted AR awaiting its R data.
        dut.io.m_axi_arready.poke(false.B)
        dut.io.m_axi_rvalid.poke(false.B)
        dut.io.m_axi_awready.poke(false.B)
        dut.io.m_axi_wready.poke(false.B)
        dut.io.m_axi_bvalid.poke(false.B)
        onReadAccepted(arAddr)
      }
      guard += 1
    }
    assert(guard < maxCycles, "AXI memory master service timed out")
  }
}
