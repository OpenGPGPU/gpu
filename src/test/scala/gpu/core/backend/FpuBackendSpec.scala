package gpu.core.backend

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class FpuBackendSpec extends AnyFlatSpec {
  behavior of "FpuBackend"

  it should "commit a compare result to the scalar register file" in {
    simulate(new FpuBackend(GpuConfig(warps = 2))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.redirect.ready.poke(true.B)
      dut.io.unimplemented.ready.poke(false.B)
      dut.io.scalarReserve.ready.poke(true.B)
      dut.io.scalarWriteback.ready.poke(true.B)
      dut.io.initialize.valid.poke(false.B)
      dut.io.initialize.bits.poke(0.U.asTypeOf(dut.io.initialize.bits))
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
      dut.io.memoryFault.ready.poke(true.B)

      def writeFp(reg: Int, data: BigInt): Unit = {
        dut.io.initialize.valid.poke(true.B)
        dut.io.initialize.bits.warpId.poke(0.U)
        dut.io.initialize.bits.rd.poke(reg.U)
        dut.io.initialize.bits.data.poke(data.U)
        dut.clock.step()
        dut.io.initialize.valid.poke(false.B)
      }
      writeFp(1, BigInt("3f800000", 16))
      writeFp(2, BigInt("3f800000", 16))

      // feq.s x5, f1, f2
      dut.io.in.bits.instruction.poke(
        "b1010000_00010_00001_010_00101_1010011".U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.decoded.readsRs1.poke(true.B)
      dut.io.in.bits.decoded.readsRs2.poke(true.B)
      dut.io.in.bits.decoded.writesInteger.poke(true.B)
      dut.io.in.bits.decoded.setsFlags.poke(true.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.scalarReserve.valid.expect(true.B)
      dut.io.scalarReserve.bits.rd.expect(5.U)

      var cycles = 0
      while (!dut.io.scalarWriteback.valid.peek().litToBoolean &&
          cycles < 12) {
        dut.clock.step()
        cycles += 1
      }
      dut.io.scalarWriteback.valid.expect(true.B)
      dut.io.scalarWriteback.bits.warpId.expect(0.U)
      dut.io.scalarWriteback.bits.rd.expect(5.U)
      dut.io.scalarWriteback.bits.data.expect(1.U)
      dut.io.committedIntegerWriteback.valid.expect(true.B)
      dut.io.committedIntegerWriteback.bits.rd.expect(5.U)
    }
  }

  it should "move a scalar register into an FP register" in {
    simulate(new FpuBackend(GpuConfig(warps = 2))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.redirect.ready.poke(true.B)
      dut.io.unimplemented.ready.poke(false.B)
      dut.io.scalarReserve.ready.poke(true.B)
      dut.io.scalarWriteback.ready.poke(true.B)
      dut.io.initialize.valid.poke(false.B)
      dut.io.initialize.bits.poke(0.U.asTypeOf(dut.io.initialize.bits))
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
      dut.io.memoryFault.ready.poke(true.B)
      dut.io.scalarRs1Data.poke("hdeadbeef".U)

      // fmv.w.x f4, x1
      dut.io.in.bits.instruction.poke(
        "b1111000_00000_00001_000_00100_1010011".U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.decoded.writesFp.poke(true.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.committedWriteback.valid.peek().litToBoolean &&
          cycles < 12) {
        dut.clock.step()
        cycles += 1
      }
      dut.io.committedWriteback.valid.expect(true.B)
      dut.io.committedWriteback.bits.warpId.expect(0.U)
      dut.io.committedWriteback.bits.rd.expect(4.U)
      dut.io.committedWriteback.bits.data.expect("hdeadbeef".U)
    }
  }

  it should "convert an FP register to a signed integer" in {
    simulate(new FpuBackend(GpuConfig(warps = 2))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.redirect.ready.poke(true.B)
      dut.io.unimplemented.ready.poke(false.B)
      dut.io.scalarReserve.ready.poke(true.B)
      dut.io.scalarWriteback.ready.poke(true.B)
      dut.io.initialize.valid.poke(false.B)
      dut.io.initialize.bits.poke(0.U.asTypeOf(dut.io.initialize.bits))
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
      dut.io.memoryFault.ready.poke(true.B)

      dut.io.initialize.valid.poke(true.B)
      dut.io.initialize.bits.warpId.poke(0.U)
      dut.io.initialize.bits.rd.poke(1.U)
      dut.io.initialize.bits.data.poke("h3f800000".U)
      dut.clock.step()
      dut.io.initialize.valid.poke(false.B)

      // fcvt.w.s x5, f1
      dut.io.in.bits.instruction.poke(
        "b1100000_00000_00001_000_00101_1010011".U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.decoded.readsRs1.poke(true.B)
      dut.io.in.bits.decoded.writesInteger.poke(true.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.scalarWriteback.valid.peek().litToBoolean &&
          cycles < 12) {
        dut.clock.step()
        cycles += 1
      }
      dut.io.scalarWriteback.valid.expect(true.B)
      dut.io.scalarWriteback.bits.rd.expect(5.U)
      dut.io.scalarWriteback.bits.data.expect(1.U)
    }
  }

  it should "convert a scalar integer to an FP register" in {
    simulate(new FpuBackend(GpuConfig(warps = 2))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.redirect.ready.poke(true.B)
      dut.io.unimplemented.ready.poke(false.B)
      dut.io.scalarReserve.ready.poke(true.B)
      dut.io.scalarWriteback.ready.poke(true.B)
      dut.io.initialize.valid.poke(false.B)
      dut.io.initialize.bits.poke(0.U.asTypeOf(dut.io.initialize.bits))
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
      dut.io.memoryFault.ready.poke(true.B)
      dut.io.scalarRs1Data.poke(5.U)

      // fcvt.s.w f4, x1
      dut.io.in.bits.instruction.poke(
        "b1101000_00000_00001_000_00100_1010011".U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.decoded.writesFp.poke(true.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.committedWriteback.valid.peek().litToBoolean &&
          cycles < 12) {
        dut.clock.step()
        cycles += 1
      }
      dut.io.committedWriteback.valid.expect(true.B)
      dut.io.committedWriteback.bits.rd.expect(4.U)
      dut.io.committedWriteback.bits.data.expect("h40a00000".U)
    }
  }

  it should "load an FP word through the memory port" in {
    simulate(new FpuBackend(GpuConfig(warps = 2))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.redirect.ready.poke(true.B)
      dut.io.unimplemented.ready.poke(false.B)
      dut.io.scalarReserve.ready.poke(true.B)
      dut.io.scalarWriteback.ready.poke(true.B)
      dut.io.initialize.valid.poke(false.B)
      dut.io.initialize.bits.poke(0.U.asTypeOf(dut.io.initialize.bits))
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.poke(
        0.U.asTypeOf(dut.io.memoryResponse.bits))
      dut.io.memoryFault.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.scalarRs1Data.poke("h1000".U)

      // flw f4, 0(x1)
      dut.io.in.bits.instruction.poke(
        "b000000000000_00001_010_00100_0000111".U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.decoded.memoryRead.poke(true.B)
      dut.io.in.bits.decoded.writesFp.poke(true.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      var requestCycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean &&
          requestCycles < 8) {
        dut.clock.step()
        requestCycles += 1
      }
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.lineAddress.expect("h1000".U)
      dut.clock.step()

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.readData.poke(
        (BigInt("deadbeef", 16) << 0).U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.committedWriteback.valid.peek().litToBoolean &&
          cycles < 12) {
        dut.clock.step()
        cycles += 1
      }
      dut.io.committedWriteback.valid.expect(true.B)
      dut.io.committedWriteback.bits.rd.expect(4.U)
      dut.io.committedWriteback.bits.data.expect("hdeadbeef".U)
    }
  }

  it should "expose an FVF read of an initialized FP register" in {
    simulate(new FpuBackend(GpuConfig(warps = 2))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.redirect.ready.poke(true.B)
      dut.io.unimplemented.ready.poke(true.B)
      dut.io.scalarReserve.ready.poke(true.B)
      dut.io.scalarWriteback.ready.poke(true.B)
      dut.io.initialize.valid.poke(false.B)
      dut.io.initialize.bits.poke(0.U.asTypeOf(dut.io.initialize.bits))
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
      dut.io.memoryFault.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.fvfRead.warpId.poke(0.U)
      dut.io.fvfRead.rs1.poke(1.U)
      dut.io.fvfRead.rs2.poke(0.U)
      dut.io.fvfRead.rs3.poke(0.U)

      dut.io.initialize.valid.poke(true.B)
      dut.io.initialize.bits.warpId.poke(0.U)
      dut.io.initialize.bits.rd.poke(1.U)
      dut.io.initialize.bits.data.poke("h3fc00000".U)
      dut.clock.step()
      dut.io.initialize.valid.poke(false.B)

      dut.io.fvfData.expect("h3fc00000".U)
      dut.io.fpuBusyByWarp(0).expect(0.U)
      dut.io.fpuBusyByWarp(1).expect(0.U)
    }
  }

  it should "use the per-warp dynamic frm for fcvt rounding" in {
    simulate(new FpuBackend(GpuConfig(warps = 2))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.redirect.ready.poke(true.B)
      dut.io.unimplemented.ready.poke(false.B)
      dut.io.scalarReserve.ready.poke(true.B)
      dut.io.scalarWriteback.ready.poke(true.B)
      dut.io.initialize.valid.poke(false.B)
      dut.io.initialize.bits.poke(0.U.asTypeOf(dut.io.initialize.bits))
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
      dut.io.memoryFault.ready.poke(true.B)
      for (warp <- 0 until 2) {
        dut.io.frm(warp).poke(0.U)
      }
      dut.io.frm(0).poke("b011".U) // RUP

      dut.io.initialize.valid.poke(true.B)
      dut.io.initialize.bits.warpId.poke(0.U)
      dut.io.initialize.bits.rd.poke(1.U)
      dut.io.initialize.bits.data.poke("h3fc00000".U) // 1.5
      dut.clock.step()
      dut.io.initialize.valid.poke(false.B)

      // fcvt.w.s x5, f1 with rm=dynamic (111).
      dut.io.in.bits.instruction.poke(
        "b1100000_00000_00001_111_00101_1010011".U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.decoded.rm.poke("b111".U)
      dut.io.in.bits.decoded.readsRs1.poke(true.B)
      dut.io.in.bits.decoded.writesInteger.poke(true.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.scalarWriteback.valid.peek().litToBoolean &&
          cycles < 12) {
        dut.clock.step()
        cycles += 1
      }
      dut.io.scalarWriteback.valid.expect(true.B)
      dut.io.scalarWriteback.bits.rd.expect(5.U)
      dut.io.scalarWriteback.bits.data.expect(2.U)
    }
  }
}
