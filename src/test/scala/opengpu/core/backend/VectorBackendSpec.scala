package opengpu.core.backend

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import opengpu.core.frontend.decode.VectorUnit
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
        cycles < 24
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

      // vdiv.vx v3, v2, x1 with x1 = 5.
      val divideInstruction =
        (BigInt(0x21) << 26) | (BigInt(1) << 25) |
          (BigInt(2) << 20) | (BigInt(1) << 15) |
          (BigInt(6) << 12) | (BigInt(3) << 7) | 0x57
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke(divideInstruction.U)
      dut.io.in.bits.pc.poke("h1004".U)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.divide)
      dut.io.in.bits.decoded.funct6.poke("h21".U)
      dut.io.in.bits.decoded.operandType.poke("b110".U)
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
        cycles < 40
      ) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.committedVectorWriteback.valid.peek().litToBoolean)
      dut.io.committedVectorWriteback.bits.vd.expect(3.U)
      dut.io.committedVectorWriteback.bits.data(0).expect(6.U)
      dut.io.committedVectorWriteback.bits.data(1).expect(6.U)
      dut.io.committedVectorWriteback.bits.data(2).expect(6.U)
      dut.io.committedVectorWriteback.bits.data(3).expect(7.U)

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

  it should "execute FVF FP add and reverse-divide from a scalar FP operand" in {
    val config = GpuConfig(lanes = 2, warps = 1)
    simulate(new VectorBackend(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.scalarRs1Data.poke(4.U)
      dut.io.scalarRs2Data.poke(0.U)
      dut.io.scalarFpData.poke("h40000000".U) // 2.0
      for (warp <- 0 until config.warps) {
        dut.io.scalarFpBusy(warp).poke(0.U)
      }
      dut.io.initialize.valid.poke(false.B)
      dut.io.initialize.bits.warpId.poke(0.U)
      dut.io.initialize.bits.vd.poke(0.U)
      for (lane <- 0 until config.lanes) {
        dut.io.initialize.bits.data(lane).poke(0.U)
      }
      dut.io.scalarWriteback.ready.poke(true.B)
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
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.activeMask.poke("b11".U)
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
      var configCycles = 0
      while (
        !dut.io.scalarWriteback.valid.peek().litToBoolean &&
        configCycles < 6
      ) {
        dut.clock.step()
        configCycles += 1
      }
      assert(dut.io.scalarWriteback.valid.peek().litToBoolean)
      dut.io.scalarWriteback.ready.poke(true.B)
      dut.clock.step()

      def initialize(register: Int, values: Seq[Int]): Unit = {
        dut.io.initialize.valid.poke(true.B)
        dut.io.initialize.bits.warpId.poke(0.U)
        dut.io.initialize.bits.vd.poke(register.U)
        for (lane <- 0 until config.lanes) {
          dut.io.initialize.bits.data(lane).poke(values(lane).U)
        }
        var initCycles = 0
        while (
          !dut.io.initialize.ready.peek().litToBoolean &&
          initCycles < 4
        ) {
          dut.clock.step()
          initCycles += 1
        }
        dut.io.initialize.ready.expect(true.B)
        dut.clock.step()
        dut.io.initialize.valid.poke(false.B)
      }
      initialize(2, Seq(0x3f800000, 0x40400000)) // 1.0, 3.0
      initialize(3, Seq(0x11111111, 0x22222222))

      // vfadd.vf v3, v2, f1
      val addInstruction =
        (BigInt(1) << 25) | (BigInt(2) << 20) | (BigInt(1) << 15) |
          (BigInt(5) << 12) | (BigInt(3) << 7) | 0x57
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke(addInstruction.U)
      dut.io.in.bits.pc.poke("h1008".U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.activeMask.poke("b11".U)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.floatingPoint)
      dut.io.in.bits.decoded.funct6.poke("h00".U)
      dut.io.in.bits.decoded.operandType.poke("b101".U)
      dut.io.in.bits.decoded.vm.poke(true.B)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(true.B)
      dut.io.in.bits.decoded.readsScalar.poke(false.B)
      dut.io.in.bits.decoded.readsFloat.poke(true.B)
      dut.io.in.bits.decoded.writesVd.poke(true.B)
      dut.io.in.bits.decoded.configure.poke(false.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      var cycles = 0
      while (
        !dut.io.committedVectorWriteback.valid.peek().litToBoolean &&
        cycles < 16
      ) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.committedVectorWriteback.valid.peek().litToBoolean)
      dut.io.committedVectorWriteback.bits.warpId.expect(0.U)
      dut.io.committedVectorWriteback.bits.vd.expect(3.U)
      dut.io.committedVectorWriteback.bits.data(0)
        .expect(BigInt("40400000", 16).U) // 3.0
      dut.io.committedVectorWriteback.bits.data(1)
        .expect(BigInt("40a00000", 16).U) // 5.0
      dut.io.committedVectorFlags.valid.expect(true.B)
      dut.io.committedVectorFlags.bits.flags.expect(0.U)

      // vfrdiv.vf v3, v2, f1 with f1 still 2.0 and v2 = {1.0, 3.0}.
      val rdivInstruction =
        (BigInt(0x21) << 26) | (BigInt(1) << 25) |
          (BigInt(2) << 20) | (BigInt(1) << 15) |
          (BigInt(5) << 12) | (BigInt(3) << 7) | 0x57
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke(rdivInstruction.U)
      dut.io.in.bits.pc.poke("h100c".U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.activeMask.poke("b11".U)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.floatingPoint)
      dut.io.in.bits.decoded.funct6.poke("h21".U)
      dut.io.in.bits.decoded.operandType.poke("b101".U)
      dut.io.in.bits.decoded.vm.poke(true.B)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(true.B)
      dut.io.in.bits.decoded.readsScalar.poke(false.B)
      dut.io.in.bits.decoded.readsFloat.poke(true.B)
      dut.io.in.bits.decoded.writesVd.poke(true.B)
      dut.io.in.bits.decoded.configure.poke(false.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      cycles = 0
      while (
        !dut.io.committedVectorWriteback.valid.peek().litToBoolean &&
        cycles < 160
      ) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.committedVectorWriteback.valid.peek().litToBoolean)
      dut.io.committedVectorWriteback.bits.vd.expect(3.U)
      dut.io.committedVectorWriteback.bits.data(0)
        .expect(BigInt("40000000", 16).U) // 2.0 / 1.0
      dut.io.committedVectorWriteback.bits.data(1)
        .expect(BigInt("3f2aaaab", 16).U) // 2.0 / 3.0
      dut.io.committedVectorFlags.valid.expect(true.B)
      assert((dut.io.committedVectorFlags.bits.flags.peek().litValue & 1) != 0)
    }
  }

  it should "execute vfcvt, vfsqrt, vfclass, and vfrec7 through vector writeback" in {
    val config = GpuConfig(lanes = 2, warps = 1)
    simulate(new VectorBackend(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.scalarRs1Data.poke(4.U)
      dut.io.scalarRs2Data.poke(0.U)
      dut.io.scalarFpData.poke(0.U)
      for (warp <- 0 until config.warps) {
        dut.io.scalarFpBusy(warp).poke(0.U)
      }
      dut.io.initialize.valid.poke(false.B)
      dut.io.initialize.bits.warpId.poke(0.U)
      dut.io.initialize.bits.vd.poke(0.U)
      for (lane <- 0 until config.lanes) {
        dut.io.initialize.bits.data(lane).poke(0.U)
      }
      dut.io.scalarWriteback.ready.poke(true.B)
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
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.activeMask.poke("b11".U)
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
      while (
        !dut.io.scalarWriteback.valid.peek().litToBoolean &&
        cycles < 6
      ) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.scalarWriteback.valid.peek().litToBoolean)
      dut.io.scalarWriteback.ready.poke(true.B)
      dut.clock.step()

      def initialize(register: Int, values: Seq[BigInt]): Unit = {
        dut.io.initialize.valid.poke(true.B)
        dut.io.initialize.bits.warpId.poke(0.U)
        dut.io.initialize.bits.vd.poke(register.U)
        for (lane <- 0 until config.lanes) {
          dut.io.initialize.bits.data(lane).poke(values(lane).U)
        }
        var initCycles = 0
        while (
          !dut.io.initialize.ready.peek().litToBoolean &&
          initCycles < 4
        ) {
          dut.clock.step()
          initCycles += 1
        }
        dut.io.initialize.ready.expect(true.B)
        dut.clock.step()
        dut.io.initialize.valid.poke(false.B)
      }
      initialize(2, Seq(BigInt(5), BigInt("fffffffb", 16)))
      initialize(3, Seq(BigInt("11111111", 16), BigInt("22222222", 16)))

      // vfcvt.f.x.v v3, v2
      val cvtInstruction =
        (BigInt(0x12) << 26) | (BigInt(1) << 25) |
          (BigInt(2) << 20) | (BigInt(3) << 15) |
          (BigInt(1) << 12) | (BigInt(3) << 7) | 0x57
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke(cvtInstruction.U)
      dut.io.in.bits.pc.poke("h1004".U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.activeMask.poke("b11".U)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.floatingPoint)
      dut.io.in.bits.decoded.funct6.poke("h12".U)
      dut.io.in.bits.decoded.operandType.poke("b001".U)
      dut.io.in.bits.decoded.vm.poke(true.B)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(true.B)
      dut.io.in.bits.decoded.readsScalar.poke(false.B)
      dut.io.in.bits.decoded.readsFloat.poke(false.B)
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
      dut.io.committedVectorWriteback.bits.vd.expect(3.U)
      dut.io.committedVectorWriteback.bits.data(0)
        .expect(BigInt("40a00000", 16).U) // 5.0
      dut.io.committedVectorWriteback.bits.data(1)
        .expect(BigInt("c0a00000", 16).U) // -5.0
      dut.io.committedVectorFlags.valid.expect(true.B)
      dut.io.committedVectorFlags.bits.flags.expect(0.U)

      // vfsqrt.v v3, v2 with v2 = {4.0, 1.0}.
      initialize(2, Seq(BigInt("40800000", 16), BigInt("3f800000", 16)))
      val sqrtInstruction =
        (BigInt(0x13) << 26) | (BigInt(1) << 25) |
          (BigInt(2) << 20) | (BigInt(0) << 15) |
          (BigInt(1) << 12) | (BigInt(3) << 7) | 0x57
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke(sqrtInstruction.U)
      dut.io.in.bits.pc.poke("h1008".U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.activeMask.poke("b11".U)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.floatingPoint)
      dut.io.in.bits.decoded.funct6.poke("h13".U)
      dut.io.in.bits.decoded.operandType.poke("b001".U)
      dut.io.in.bits.decoded.vm.poke(true.B)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(true.B)
      dut.io.in.bits.decoded.readsScalar.poke(false.B)
      dut.io.in.bits.decoded.readsFloat.poke(false.B)
      dut.io.in.bits.decoded.writesVd.poke(true.B)
      dut.io.in.bits.decoded.configure.poke(false.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      cycles = 0
      while (
        !dut.io.committedVectorWriteback.valid.peek().litToBoolean &&
        cycles < 80
      ) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.committedVectorWriteback.valid.peek().litToBoolean)
      dut.io.committedVectorWriteback.bits.vd.expect(3.U)
      dut.io.committedVectorWriteback.bits.data(0)
        .expect(BigInt("40000000", 16).U) // sqrt(4)
      dut.io.committedVectorWriteback.bits.data(1)
        .expect(BigInt("3f800000", 16).U) // sqrt(1)
      dut.io.committedVectorFlags.valid.expect(true.B)
      dut.io.committedVectorFlags.bits.flags.expect(0.U)

      // vfclass.v v3, v2 with v2 = {-0.0, +inf}.
      initialize(2, Seq(BigInt("80000000", 16), BigInt("7f800000", 16)))
      val classInstruction =
        (BigInt(0x13) << 26) | (BigInt(1) << 25) |
          (BigInt(2) << 20) | (BigInt(16) << 15) |
          (BigInt(1) << 12) | (BigInt(3) << 7) | 0x57
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke(classInstruction.U)
      dut.io.in.bits.pc.poke("h100c".U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.activeMask.poke("b11".U)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.floatingPoint)
      dut.io.in.bits.decoded.funct6.poke("h13".U)
      dut.io.in.bits.decoded.operandType.poke("b001".U)
      dut.io.in.bits.decoded.vm.poke(true.B)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(true.B)
      dut.io.in.bits.decoded.readsScalar.poke(false.B)
      dut.io.in.bits.decoded.readsFloat.poke(false.B)
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
      dut.io.committedVectorWriteback.bits.vd.expect(3.U)
      dut.io.committedVectorWriteback.bits.data(0)
        .expect(BigInt("8", 16).U) // -0.0 -> bit 3
      dut.io.committedVectorWriteback.bits.data(1)
        .expect(BigInt("80", 16).U) // +inf -> bit 7
      dut.io.committedVectorFlags.valid.expect(true.B)
      dut.io.committedVectorFlags.bits.flags.expect(0.U)

      // vfrec7.v v3, v2 with v2 = {1.0, 3.0}.
      initialize(2, Seq(BigInt("3f800000", 16), BigInt("40400000", 16)))
      val recInstruction =
        (BigInt(0x13) << 26) | (BigInt(1) << 25) |
          (BigInt(2) << 20) | (BigInt(4) << 15) |
          (BigInt(1) << 12) | (BigInt(3) << 7) | 0x57
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke(recInstruction.U)
      dut.io.in.bits.pc.poke("h1010".U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.activeMask.poke("b11".U)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.floatingPoint)
      dut.io.in.bits.decoded.funct6.poke("h13".U)
      dut.io.in.bits.decoded.operandType.poke("b001".U)
      dut.io.in.bits.decoded.vm.poke(true.B)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(true.B)
      dut.io.in.bits.decoded.readsScalar.poke(false.B)
      dut.io.in.bits.decoded.readsFloat.poke(false.B)
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
      dut.io.committedVectorWriteback.bits.vd.expect(3.U)
      dut.io.committedVectorWriteback.bits.data(0)
        .expect(BigInt("3f7f0000", 16).U) // recip7(1.0)
      dut.io.committedVectorWriteback.bits.data(1)
        .expect(BigInt("3eaa0000", 16).U) // recip7(3.0)
      dut.io.committedVectorFlags.valid.expect(true.B)
      dut.io.committedVectorFlags.bits.flags.expect(0.U)
    }
  }

}
