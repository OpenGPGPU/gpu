package opengpu.system

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class ComputeMemoryAxiMasterSpec extends AnyFlatSpec {
  behavior of "ComputeMemoryAxiMaster"

  private def initialize(dut: ComputeMemoryAxiMaster): Unit = {
    dut.io.request.valid.poke(false.B)
    dut.io.request.bits.poke(0.U.asTypeOf(dut.io.request.bits))
    dut.io.response.ready.poke(false.B)
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
    dut.clock.step()
  }

  private def submit(
    dut: ComputeMemoryAxiMaster,
    address: BigInt,
    data: BigInt,
    mask: BigInt,
    write: Boolean,
    sizeLog2: Int,
    id: Int
  ): Unit = {
    dut.io.request.bits.address.poke(address.U)
    dut.io.request.bits.writeData.poke(data.U)
    dut.io.request.bits.byteMask.poke(mask.U)
    dut.io.request.bits.isWrite.poke(write.B)
    dut.io.request.bits.sizeLog2.poke(sizeLog2.U)
    dut.io.request.bits.cacheClient.poke(false.B)
    dut.io.request.bits.cacheResident.poke(false.B)
    dut.io.request.bits.transactionId.poke(id.U)
    dut.io.request.valid.poke(true.B)
    dut.io.request.ready.expect(true.B)
    dut.clock.step()
    dut.io.request.valid.poke(false.B)
  }

  it should "convert a cache-line read into an AXI INCR burst" in {
    simulate(new ComputeMemoryAxiMaster(
      GpuConfig(), maxOutstanding = 16, axiDataBytes = 8)) { dut =>
      initialize(dut)
      submit(dut, 0x4000, 0, 0, write = false, sizeLog2 = 6, id = 9)

      dut.io.m_axi_arvalid.expect(true.B)
      dut.io.m_axi_arid.expect(9.U)
      dut.io.m_axi_araddr.expect(0x4000.U)
      dut.io.m_axi_arlen.expect(7.U)
      dut.io.m_axi_arsize.expect(3.U)
      dut.io.m_axi_arburst.expect(1.U)
      dut.io.m_axi_arready.poke(true.B)
      dut.clock.step()
      dut.io.m_axi_arready.poke(false.B)

      var expected = BigInt(0)
      for (beat <- 0 until 8) {
        val word = BigInt("1122334400000000", 16) + beat
        expected |= word << (beat * 64)
        dut.io.m_axi_rid.poke(9.U)
        dut.io.m_axi_rdata.poke(word.U)
        dut.io.m_axi_rresp.poke(0.U)
        dut.io.m_axi_rlast.poke((beat == 7).B)
        dut.io.m_axi_rvalid.poke(true.B)
        dut.io.m_axi_rready.expect(true.B)
        dut.clock.step()
      }
      dut.io.m_axi_rvalid.poke(false.B)

      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.transactionId.expect(9.U)
      dut.io.response.bits.readData.expect(expected.U)
      dut.io.response.bits.fault.expect(false.B)
      dut.io.response.ready.poke(true.B)
      dut.clock.step()
      dut.io.request.ready.expect(true.B)
    }
  }

  it should "place a narrow word in the addressed AXI byte lanes" in {
    simulate(new ComputeMemoryAxiMaster(
      GpuConfig(), maxOutstanding = 8, axiDataBytes = 8)) { dut =>
      initialize(dut)
      submit(dut, 0x1004, 0xaabbccddL, 0xf, write = true,
        sizeLog2 = 2, id = 5)

      dut.io.m_axi_awvalid.expect(true.B)
      dut.io.m_axi_awaddr.expect(0x1004.U)
      dut.io.m_axi_awlen.expect(0.U)
      dut.io.m_axi_awsize.expect(2.U)
      dut.io.m_axi_wvalid.expect(true.B)
      dut.io.m_axi_wdata.expect(BigInt("aabbccdd00000000", 16).U)
      dut.io.m_axi_wstrb.expect(0xf0.U)
      dut.io.m_axi_wlast.expect(true.B)

      // AW and W are independent: accept data first, then the address.
      dut.io.m_axi_wready.poke(true.B)
      dut.clock.step()
      dut.io.m_axi_wready.poke(false.B)
      dut.io.m_axi_wvalid.expect(false.B)
      dut.io.m_axi_awvalid.expect(true.B)
      dut.io.m_axi_awready.poke(true.B)
      dut.clock.step()
      dut.io.m_axi_awready.poke(false.B)

      dut.io.m_axi_bready.expect(true.B)
      dut.io.m_axi_bid.poke(5.U)
      dut.io.m_axi_bresp.poke(2.U) // SLVERR
      dut.io.m_axi_bvalid.poke(true.B)
      dut.clock.step()
      dut.io.m_axi_bvalid.poke(false.B)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.transactionId.expect(5.U)
      dut.io.response.bits.readData.expect(0.U)
      dut.io.response.bits.fault.expect(true.B)
    }
  }

  it should "extract a narrow read and hold its response under backpressure" in {
    simulate(new ComputeMemoryAxiMaster(
      GpuConfig(), maxOutstanding = 4, axiDataBytes = 8)) { dut =>
      initialize(dut)
      submit(dut, 0x2004, 0, 0, write = false, sizeLog2 = 2, id = 2)
      dut.io.m_axi_arready.poke(true.B)
      dut.clock.step()
      dut.io.m_axi_arready.poke(false.B)
      dut.io.m_axi_rid.poke(2.U)
      dut.io.m_axi_rdata.poke(BigInt("deadbeef55667788", 16).U)
      dut.io.m_axi_rresp.poke(0.U)
      dut.io.m_axi_rlast.poke(true.B)
      dut.io.m_axi_rvalid.poke(true.B)
      dut.clock.step()
      dut.io.m_axi_rvalid.poke(false.B)

      for (_ <- 0 until 3) {
        dut.io.response.valid.expect(true.B)
        dut.io.response.bits.readData.expect(0xdeadbeefL.U)
        dut.io.response.bits.transactionId.expect(2.U)
        dut.io.request.ready.expect(false.B)
        dut.clock.step()
      }
      dut.io.response.ready.poke(true.B)
      dut.clock.step()
      dut.io.request.ready.expect(true.B)
    }
  }
}
