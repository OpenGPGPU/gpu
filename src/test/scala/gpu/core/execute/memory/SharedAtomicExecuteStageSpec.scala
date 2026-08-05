package gpu.core.execute.memory

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import gpu.core.memory.AtomicMemoryOp
import org.scalatest.flatspec.AnyFlatSpec

class SharedAtomicExecuteStageSpec extends AnyFlatSpec {
  behavior of "SharedAtomicExecuteStage"

  it should "issue a shared AMO and commit the returned old value" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new SharedAtomicExecuteStage(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.atomicRequest.ready.poke(true.B)
      dut.io.atomicResponse.valid.poke(false.B)
      dut.io.atomicResponse.bits.poke(
        0.U.asTypeOf(dut.io.atomicResponse.bits))
      dut.io.globalAtomicRequest.ready.poke(true.B)
      dut.io.globalAtomicResponse.valid.poke(false.B)
      dut.io.globalAtomicResponse.bits.poke(
        0.U.asTypeOf(dut.io.globalAtomicResponse.bits))
      dut.io.out.ready.poke(true.B)
      dut.io.unimplemented.ready.poke(false.B)
      dut.io.fault.ready.poke(true.B)

      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.in.bits.decode.warpId.poke(1.U)
      dut.io.in.bits.decode.pc.poke(0x200.U)
      dut.io.in.bits.decode.activeMask.poke(0xf.U)
      dut.io.in.bits.decode.decoded.rd.poke(3.U)
      dut.io.in.bits.decode.decoded.atomicOp.poke(AtomicMemoryOp.add)
      dut.io.in.bits.rs1Data.poke(config.sharedMemoryBase.U)
      dut.io.in.bits.rs2Data.poke(7.U)
      dut.clock.step(); dut.io.in.valid.poke(false.B)

      dut.io.atomicRequest.valid.expect(true.B)
      dut.io.atomicRequest.bits.address.expect(config.sharedMemoryBase.U)
      dut.io.atomicRequest.bits.operand.expect(7.U)
      dut.clock.step()
      dut.io.atomicResponse.valid.poke(true.B)
      dut.io.atomicResponse.bits.warpId.poke(1.U)
      dut.io.atomicResponse.bits.oldValue.poke(10.U)
      dut.io.atomicResponse.bits.fault.poke(false.B)
      dut.clock.step(); dut.io.atomicResponse.valid.poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.rd.expect(3.U)
      dut.io.out.bits.data.expect(10.U)
      dut.io.out.bits.nextPc.expect(0x204.U)
    }
  }

  it should "route a global AMO to L2 and commit its returned old value" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new SharedAtomicExecuteStage(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.atomicRequest.ready.poke(true.B)
      dut.io.atomicResponse.valid.poke(false.B)
      dut.io.globalAtomicRequest.ready.poke(true.B)
      dut.io.globalAtomicResponse.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.io.unimplemented.ready.poke(false.B)
      dut.io.fault.ready.poke(true.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.in.bits.rs1Data.poke(0x80000000L.U)
      dut.io.in.bits.rs2Data.poke(9.U)
      dut.io.in.bits.decode.decoded.rd.poke(4.U)
      dut.io.in.bits.decode.decoded.atomicOp.poke(AtomicMemoryOp.xor)
      dut.clock.step(); dut.io.in.valid.poke(false.B)
      dut.io.atomicRequest.valid.expect(false.B)
      dut.io.globalAtomicRequest.valid.expect(true.B)
      dut.io.globalAtomicRequest.bits.address.expect(0x80000000L.U)
      dut.clock.step()
      dut.io.globalAtomicResponse.valid.poke(true.B)
      dut.io.globalAtomicResponse.bits.oldValue.poke(0x55.U)
      dut.io.globalAtomicResponse.bits.fault.poke(false.B)
      dut.clock.step(); dut.io.globalAtomicResponse.valid.poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.rd.expect(4.U)
      dut.io.out.bits.data.expect(0x55.U)
    }
  }

  it should "report a misaligned AMO as a precise scalar memory fault" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new SharedAtomicExecuteStage(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.atomicRequest.ready.poke(true.B)
      dut.io.atomicResponse.valid.poke(false.B)
      dut.io.globalAtomicRequest.ready.poke(true.B)
      dut.io.globalAtomicResponse.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.io.unimplemented.ready.poke(false.B)
      dut.io.fault.ready.poke(false.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.in.bits.decode.warpId.poke(1.U)
      dut.io.in.bits.decode.pc.poke(0x300.U)
      dut.io.in.bits.decode.decoded.rd.poke(7.U)
      dut.io.in.bits.decode.decoded.writeRd.poke(true.B)
      dut.io.in.bits.rs1Data.poke(0x80000002L.U)
      dut.clock.step(); dut.io.in.valid.poke(false.B)
      dut.io.atomicRequest.valid.expect(false.B)
      dut.io.globalAtomicRequest.valid.expect(false.B)
      dut.io.fault.valid.expect(true.B)
      dut.io.fault.bits.address.expect(0x80000002L.U)
      dut.io.fault.bits.misaligned.expect(true.B)
      dut.io.fault.bits.isStore.expect(true.B)
      dut.io.fault.bits.rd.expect(7.U)
    }
  }
}
