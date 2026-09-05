package opengpu.system

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import opengpu.graphics.{GraphicsConfig, RenderHostRegs}
import org.scalatest.flatspec.AnyFlatSpec

class GpuHostSystemAxiSpec extends AnyFlatSpec {
  behavior of "GpuHostSystemAxi"

  private def initialize(dut: GpuHostSystemAxi): Unit = {
    dut.io.s_axi_awvalid.poke(false.B)
    dut.io.s_axi_wvalid.poke(false.B)
    dut.io.s_axi_wlast.poke(false.B)
    dut.io.s_axi_bready.poke(false.B)
    dut.io.s_axi_arvalid.poke(false.B)
    dut.io.s_axi_rready.poke(false.B)
    dut.io.gpuCommand.valid.poke(false.B)
    dut.io.gpuCommand.bits.poke(0.U.asTypeOf(dut.io.gpuCommand.bits))
    dut.io.gpuCompletion.ready.poke(true.B)
    dut.io.memoryRequest.ready.poke(true.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.poke(
      0.U.asTypeOf(dut.io.memoryResponse.bits))
    dut.io.kernelMemReq.ready.poke(true.B)
    dut.io.kernelMemResp.valid.poke(false.B)
    dut.io.kernelMemResp.bits.poke(
      0.U.asTypeOf(dut.io.kernelMemResp.bits))
    dut.io.kernelL1Invalidate.valid.poke(false.B)
    dut.io.kernelL1Invalidate.bits.poke(
      0.U.asTypeOf(dut.io.kernelL1Invalidate.bits))
    dut.io.kernelL1InvalidateDone.ready.poke(true.B)
    dut.io.kernelGlobalAtomicRequest.ready.poke(true.B)
    dut.io.kernelGlobalAtomicResponse.valid.poke(false.B)
    dut.io.kernelGlobalAtomicResponse.bits.poke(
      0.U.asTypeOf(dut.io.kernelGlobalAtomicResponse.bits))
    dut.io.s_axi_aresetn.poke(false.B)
    dut.clock.step()
    dut.io.s_axi_aresetn.poke(true.B)
    dut.clock.step()
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
    dut.io.s_axi_wdata.poke(data.U)
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
      dut.clock.step()
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
      dut.clock.step()
      if (addressDone) dut.io.s_axi_arvalid.poke(false.B)
      cycles += 1
    }
    assert(responseDone, f"AXI read from 0x$address%x timed out")
    dut.io.s_axi_arvalid.poke(false.B)
    dut.io.s_axi_rready.poke(false.B)
    result
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

      // Prevent the request from escaping during the AXI START transaction.
      dut.io.memoryRequest.ready.poke(false.B)
      axiWrite(dut, RenderHostRegs.CLEAR_START, 1)
      dut.io.memoryRequest.ready.poke(true.B)

      var cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 80) {
        dut.clock.step()
        cycles += 1
      }
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.address.expect(base.U)
      dut.io.memoryRequest.bits.isWrite.expect(true.B)
      dut.io.memoryRequest.bits.byteMask.expect(((BigInt(1) << 64) - 1).U)
      dut.io.memoryRequest.bits.writeData.expect(expectedLine.U)
      val lowerId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()

      dut.io.memoryResponse.bits.transactionId.poke(lowerId.U)
      dut.io.memoryResponse.bits.readData.poke(0.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.valid.poke(true.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)

      cycles = 0
      var status = axiRead(dut, RenderHostRegs.STATUS)
      while ((status & 0x8L) != 0L && cycles < 40) {
        status = axiRead(dut, RenderHostRegs.STATUS)
        cycles += 1
      }
      assert(cycles < 40, "clear did not retire through the shared L2")
      assert((status & 0xcL) == 0L,
        f"clear must finish without BUSY or ERROR set, status=0x$status%x")
      dut.io.performance.lowerWriteRequests.expect(1.U)
      dut.io.performance.l2.storesAccepted.expect(1.U)
    }
  }

  it should "serve command-buffer words from the shared L2" in {
    val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16)
    val gpu = GpuConfig(
      lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuHostSystemAxi(gfx, gpu)) { dut =>
      initialize(dut)

      val commandBase = 0x4000
      axiWrite(dut, RenderHostRegs.CMD_BASE, commandBase)
      axiWrite(dut, RenderHostRegs.CMD_COUNT, 1)
      axiWrite(dut, RenderHostRegs.COLOR_BASE, 0x8000)
      axiWrite(dut, RenderHostRegs.DEPTH_BASE, 0x9000)
      axiWrite(dut, RenderHostRegs.STRIDE, 64)
      dut.io.memoryRequest.ready.poke(false.B)
      axiWrite(dut, RenderHostRegs.CONTROL, 1)
      dut.io.memoryRequest.ready.poke(true.B)

      var cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 100) {
        dut.clock.step()
        cycles += 1
      }
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.address.expect(commandBase.U)
      dut.io.memoryRequest.bits.isWrite.expect(false.B)
      val firstId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()

      // Sixteen zero command words occupy the first cache line. Consuming the
      // returned line must let the command parser advance to the next line.
      dut.io.memoryResponse.bits.transactionId.poke(firstId.U)
      dut.io.memoryResponse.bits.readData.poke(0.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.valid.poke(true.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)

      cycles = 0
      var sawSecondLine = false
      while (!sawSecondLine && cycles < 200) {
        if (dut.io.memoryRequest.valid.peek().litToBoolean &&
            dut.io.memoryRequest.bits.address.peek().litValue ==
              commandBase + 64) {
          dut.io.memoryRequest.bits.isWrite.expect(false.B)
          sawSecondLine = true
        } else {
          dut.clock.step()
          cycles += 1
        }
      }
      assert(sawSecondLine,
        "command parser did not consume the first shared-L2 cache line")
      dut.clock.step()
      dut.io.performance.lowerReadRequests.expect(2.U)
    }
  }
}
