package gpu.core.backend

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import gpu.core.frontend.decode.VectorUnit
import org.scalatest.flatspec.AnyFlatSpec

class VectorBackendSpec extends AnyFlatSpec {
  behavior of "VectorBackend"

  it should "carry vsetvli through issue and commit its scalar vl result" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new VectorBackend(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.scalarRs1Data.poke(4.U)
      dut.io.scalarRs2Data.poke(0.U)
      dut.io.initialize.valid.poke(false.B)
      dut.io.initialize.bits.warpId.poke(0.U)
      dut.io.initialize.bits.vd.poke(0.U)
      for (lane <- 0 until config.lanes) {
        dut.io.initialize.bits.data(lane).poke(0.U)
      }
      dut.io.scalarWriteback.ready.poke(false.B)
      dut.io.redirect.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryFault.ready.poke(true.B)
      dut.io.memoryResponse.bits.faultMask.poke(0.U)
      dut.io.memoryResponse.bits.pageFault.poke(false.B)
      for (lane <- 0 until config.lanes) {
        dut.io.memoryResponse.bits.readData(lane).poke(0.U)
      }
      dut.io.scalarReserve.ready.poke(true.B)
      dut.io.unimplemented.ready.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      // vsetvli x1, x2, e32,m1
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke("h010170d7".U)
      dut.io.in.bits.pc.poke("h1000".U)
      dut.io.in.bits.warpId.poke(1.U)
      dut.io.in.bits.activeMask.poke("b1111".U)
      dut.io.in.bits.decoded.recognized.poke(true.B)
      dut.io.in.bits.decoded.valid.poke(true.B)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.configuration)
      dut.io.in.bits.decoded.funct6.poke(0.U)
      dut.io.in.bits.decoded.operandType.poke(7.U)
      dut.io.in.bits.decoded.vm.poke(false.B)
      dut.io.in.bits.decoded.nf.poke(0.U)
      dut.io.in.bits.decoded.mop.poke(0.U)
      dut.io.in.bits.decoded.elementWidth.poke(7.U)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(false.B)
      dut.io.in.bits.decoded.readsScalar.poke(false.B)
      dut.io.in.bits.decoded.readsFloat.poke(false.B)
      dut.io.in.bits.decoded.writesVd.poke(false.B)
      dut.io.in.bits.decoded.memoryRead.poke(false.B)
      dut.io.in.bits.decoded.memoryWrite.poke(false.B)
      dut.io.in.bits.decoded.configure.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.scalarWriteback.valid.peek().litToBoolean && cycles < 6) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.scalarWriteback.valid.peek().litToBoolean)
      dut.io.scalarWriteback.bits.warpId.expect(1.U)
      dut.io.scalarWriteback.bits.rd.expect(1.U)
      dut.io.scalarWriteback.bits.data.expect(4.U)
      dut.clock.step(2)
      dut.io.scalarWriteback.valid.expect(true.B)
      dut.io.scalarWriteback.bits.data.expect(4.U)
      dut.io.scalarWriteback.ready.poke(true.B)
      dut.clock.step()
      dut.io.scalarWriteback.valid.expect(false.B)

      def initialize(register: Int, base: Int): Unit = {
        dut.io.initialize.valid.poke(true.B)
        dut.io.initialize.bits.warpId.poke(1.U)
        dut.io.initialize.bits.vd.poke(register.U)
        for (lane <- 0 until config.lanes) {
          dut.io.initialize.bits.data(lane).poke((base + lane).U)
        }
        dut.io.initialize.ready.expect(true.B)
        dut.clock.step()
        dut.io.initialize.valid.poke(false.B)
      }
      initialize(2, 0x20)
      initialize(3, 0x30)

      // vadd.vx v3, v2, x1
      val addInstruction =
        (BigInt(1) << 25) | (BigInt(2) << 20) | (BigInt(1) << 15) |
          (BigInt(4) << 12) | (BigInt(3) << 7) | 0x57
      dut.io.scalarRs1Data.poke(5.U)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke(addInstruction.U)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.alu)
      dut.io.in.bits.decoded.funct6.poke(0.U)
      dut.io.in.bits.decoded.operandType.poke(4.U)
      dut.io.in.bits.decoded.vm.poke(true.B)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(true.B)
      dut.io.in.bits.decoded.readsScalar.poke(true.B)
      dut.io.in.bits.decoded.writesVd.poke(true.B)
      dut.io.in.bits.decoded.configure.poke(false.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      cycles = 0
      while (
        !dut.io.committedVectorWriteback.valid.peek().litToBoolean &&
        cycles < 12
      ) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.committedVectorWriteback.valid.peek().litToBoolean)
      dut.io.committedVectorWriteback.bits.warpId.expect(1.U)
      dut.io.committedVectorWriteback.bits.vd.expect(3.U)
      for (lane <- 0 until config.lanes) {
        dut.io.committedVectorWriteback.bits.data(lane)
          .expect((0x25 + lane).U)
      }

      // vle32.v v4, (x1): the backend must wait for the external response.
      dut.io.memoryRequest.ready.poke(false.B)
      val loadInstruction =
        (BigInt(1) << 25) | (BigInt(1) << 15) |
          (BigInt(6) << 12) | (BigInt(4) << 7) | 0x07
      dut.io.scalarRs1Data.poke(0x400.U)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke(loadInstruction.U)
      dut.io.in.bits.pc.poke(0x1004.U)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.loadStore)
      dut.io.in.bits.decoded.vm.poke(true.B)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(false.B)
      dut.io.in.bits.decoded.readsScalar.poke(true.B)
      dut.io.in.bits.decoded.writesVd.poke(true.B)
      dut.io.in.bits.decoded.memoryRead.poke(true.B)
      dut.io.in.bits.decoded.memoryWrite.poke(false.B)
      while (!dut.io.in.ready.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 12) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.memoryRequest.valid.peek().litToBoolean)
      dut.io.memoryRequest.bits.laneMask.expect("b1111".U)
      for (lane <- 0 until config.lanes) {
        dut.io.memoryRequest.bits.addresses(lane)
          .expect((0x400 + lane * 4).U)
      }
      dut.io.memoryRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.committedVectorWriteback.valid.expect(false.B)

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.faultMask.poke(0.U)
      dut.io.memoryResponse.bits.pageFault.poke(false.B)
      for (lane <- 0 until config.lanes) {
        dut.io.memoryResponse.bits.readData(lane).poke((0x80 + lane).U)
      }
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      cycles = 0
      while (
        !dut.io.committedVectorWriteback.valid.peek().litToBoolean &&
        cycles < 8
      ) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.committedVectorWriteback.valid.peek().litToBoolean)
      dut.io.committedVectorWriteback.bits.vd.expect(4.U)
      for (lane <- 0 until config.lanes) {
        dut.io.committedVectorWriteback.bits.data(lane)
          .expect((0x80 + lane).U)
      }
    }
  }
}
