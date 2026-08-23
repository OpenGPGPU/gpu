package gpu.core

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class GpuCoreSpec extends AnyFlatSpec {
  behavior of "GpuCore"

  private def defaults(dut: GpuCore, lanes: Int): Unit = {
    dut.io.launch.valid.poke(false.B)
    dut.io.scalarInitialize.valid.poke(false.B)
    dut.io.scalarInitialize.bits.warpId.poke(0.U)
    dut.io.scalarInitialize.bits.rd.poke(0.U)
    dut.io.scalarInitialize.bits.data.poke(0.U)
    dut.io.fetchRequest.ready.poke(true.B)
    dut.io.fetchResponse.valid.poke(false.B)
    dut.io.fetchResponse.bits.warpId.poke(0.U)
    dut.io.fetchResponse.bits.instruction.poke(0.U)
    dut.io.fetchResponse.bits.accessFault.poke(false.B)
    dut.io.fpu.ready.poke(false.B)
    dut.io.fpuInitialize.valid.poke(false.B)
    dut.io.vector.ready.poke(false.B)
    dut.io.vectorInitialize.valid.poke(false.B)
    dut.io.vectorMemoryRequest.ready.poke(true.B)
    dut.io.vectorMemoryResponse.valid.poke(false.B)
    dut.io.vectorSatp.poke("h80000000".U)
    dut.io.vectorTlbFlush.valid.poke(false.B)
    dut.io.vectorTlbFlush.bits.virtualPageNumberValid.poke(false.B)
    dut.io.vectorTlbFlush.bits.virtualPageNumber.poke(0.U)
    dut.io.vectorTlbFlush.bits.asidValid.poke(false.B)
    dut.io.vectorTlbFlush.bits.asid.poke(0.U)
    dut.io.vectorPageTableRequest.ready.poke(true.B)
    dut.io.vectorPageTableResponse.valid.poke(false.B)
    dut.io.vectorPageTableResponse.bits.pte.poke(0.U)
    dut.io.vectorPageTableResponse.bits.fault.poke(false.B)
    dut.io.vectorMemoryResponse.bits.fault.poke(false.B)
    dut.io.vectorMemoryResponse.bits.readData.poke(0.U)
    dut.io.vectorInitialize.bits.warpId.poke(0.U)
    dut.io.vectorInitialize.bits.vd.poke(0.U)
    for (lane <- 0 until lanes) {
      dut.io.vectorInitialize.bits.data(lane).poke(0.U)
    }
    dut.io.memory.ready.poke(false.B)
    dut.io.system.ready.poke(false.B)
    dut.io.trap.ready.poke(false.B)
    dut.io.faultResume.valid.poke(false.B)
    dut.io.faultResume.bits.warpId.poke(0.U)
    dut.io.faultResume.bits.pc.poke(0.U)
    dut.io.faultResume.bits.activeMask.poke(0.U)
    dut.io.simtBranch.valid.poke(false.B)
    dut.io.restore.valid.poke(false.B)
    dut.io.restore.bits.poke(0.U)
    dut.io.finish.valid.poke(false.B)
    dut.io.finish.bits.poke(0.U)
    dut.io.l1Invalidate.valid.poke(false.B)
    dut.io.l1Invalidate.bits.lineAddress.poke(0.U)
    dut.io.l1InvalidateDone.ready.poke(true.B)
    dut.io.globalAtomicRequest.ready.poke(true.B)
    dut.io.globalAtomicResponse.valid.poke(false.B)
    dut.io.globalAtomicResponse.bits.poke(
      0.U.asTypeOf(dut.io.globalAtomicResponse.bits))
  }

  it should "advance fetch from a committed scalar branch" in {
    simulate(new GpuCore(GpuConfig(lanes = 4, warps = 2))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      defaults(dut, 4)

      dut.io.launch.valid.poke(true.B)
      dut.io.launch.bits.startPc.poke(0x100.U)
      dut.io.launch.bits.activeMask.poke(0xf.U)
      dut.clock.step()
      dut.io.launch.valid.poke(false.B)

      while (!dut.io.fetchRequest.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.io.fetchRequest.bits.pc.expect(0x100.U)
      dut.clock.step()

      // jal x1, +8
      dut.io.fetchResponse.valid.poke(true.B)
      dut.io.fetchResponse.bits.instruction.poke("h008000ef".U)
      dut.io.fetchResponse.bits.accessFault.poke(false.B)
      while (!dut.io.fetchResponse.ready.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.clock.step()
      dut.io.fetchResponse.valid.poke(false.B)

      var sawLink = false
      var sawRedirectedFetch = false
      for (_ <- 0 until 20) {
        if (dut.io.committedWriteback.valid.peek().litToBoolean) {
          dut.io.committedWriteback.bits.rd.expect(1.U)
          dut.io.committedWriteback.bits.data.expect(0x104.U)
          sawLink = true
        }
        if (dut.io.fetchRequest.valid.peek().litToBoolean) {
          dut.io.fetchRequest.bits.pc.expect(0x108.U)
          sawRedirectedFetch = true
        }
        dut.clock.step()
      }
      assert(sawLink)
      assert(sawRedirectedFetch)
    }
  }

  it should "make scalar initialization visible to the first instruction" in {
    val config = GpuConfig(lanes = 4, warps = 1)
    simulate(new GpuCore(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      defaults(dut, config.lanes)
      dut.io.fetchRequest.ready.poke(false.B)
      dut.io.scalarInitialize.valid.poke(true.B)
      dut.io.scalarInitialize.bits.warpId.poke(0.U)
      dut.io.scalarInitialize.bits.rd.poke(1.U)
      dut.io.scalarInitialize.bits.data.poke(0x40.U)
      dut.io.scalarInitialize.ready.expect(true.B)
      dut.clock.step(); dut.io.scalarInitialize.valid.poke(false.B)

      dut.io.launch.valid.poke(true.B)
      dut.io.launch.bits.startPc.poke(0x180.U)
      dut.io.launch.bits.activeMask.poke(0xf.U)
      dut.clock.step(); dut.io.launch.valid.poke(false.B)
      while (!dut.io.fetchRequest.valid.peek().litToBoolean) { dut.clock.step() }
      dut.io.fetchRequest.ready.poke(true.B); dut.clock.step()
      dut.io.fetchRequest.ready.poke(false.B)
      dut.io.fetchResponse.valid.poke(true.B)
      dut.io.fetchResponse.bits.instruction.poke("h00108113".U) // addi x2,x1,1
      while (!dut.io.fetchResponse.ready.peek().litToBoolean) { dut.clock.step() }
      dut.clock.step(); dut.io.fetchResponse.valid.poke(false.B)

      var sawWrite = false
      for (_ <- 0 until 24) {
        if (dut.io.committedWriteback.valid.peek().litToBoolean) {
          dut.io.committedWriteback.bits.rd.expect(2.U)
          dut.io.committedWriteback.bits.data.expect(0x41.U)
          sawWrite = true
        }
        dut.clock.step()
      }
      assert(sawWrite)
    }
  }

  it should "execute a configured vector add through the integrated core" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new GpuCore(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      defaults(dut, config.lanes)
      dut.io.fetchRequest.ready.poke(false.B)

      for ((register, base) <- Seq((2, 0x20), (3, 0x30))) {
        dut.io.vectorInitialize.valid.poke(true.B)
        dut.io.vectorInitialize.bits.warpId.poke(0.U)
        dut.io.vectorInitialize.bits.vd.poke(register.U)
        for (lane <- 0 until config.lanes) {
          dut.io.vectorInitialize.bits.data(lane).poke((base + lane).U)
        }
        dut.io.vectorInitialize.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.vectorInitialize.valid.poke(false.B)

      dut.io.launch.valid.poke(true.B)
      dut.io.launch.bits.startPc.poke(0x200.U)
      dut.io.launch.bits.activeMask.poke("b1111".U)
      dut.clock.step()
      dut.io.launch.valid.poke(false.B)

      def respond(instruction: BigInt): Unit = {
        var requestCycles = 0
        while (
          !dut.io.fetchRequest.valid.peek().litToBoolean &&
          requestCycles < 16
        ) {
          dut.clock.step()
          requestCycles += 1
        }
        assert(dut.io.fetchRequest.valid.peek().litToBoolean)
        dut.io.fetchRequest.ready.poke(true.B)
        dut.clock.step()
        dut.io.fetchRequest.ready.poke(false.B)
        dut.io.fetchResponse.valid.poke(true.B)
        dut.io.fetchResponse.bits.instruction.poke(instruction.U)
        var responseCycles = 0
        while (
          !dut.io.fetchResponse.ready.peek().litToBoolean &&
          responseCycles < 16
        ) {
          dut.clock.step()
          responseCycles += 1
        }
        assert(dut.io.fetchResponse.ready.peek().litToBoolean)
        dut.clock.step()
        dut.io.fetchResponse.valid.poke(false.B)
      }

      val vsetivli =
        (BigInt(3) << 30) | (BigInt(0x10) << 20) |
          (BigInt(4) << 15) | (BigInt(7) << 12) | 0x57
      val vaddVi =
        (BigInt(1) << 25) | (BigInt(2) << 20) |
          (BigInt(3) << 15) | (BigInt(3) << 12) |
          (BigInt(3) << 7) | 0x57
      respond(vsetivli)
      respond(vaddVi)

      var sawVectorWrite = false
      for (_ <- 0 until 24) {
        if (dut.io.committedVectorWriteback.valid.peek().litToBoolean) {
          dut.io.committedVectorWriteback.bits.vd.expect(3.U)
          for (lane <- 0 until config.lanes) {
            dut.io.committedVectorWriteback.bits.data(lane)
              .expect((0x23 + lane).U)
          }
          sawVectorWrite = true
        }
        dut.clock.step()
      }
      assert(sawVectorWrite)

      // vle32.v v4, (x0). The four lanes access byte
      // addresses 0, 4, 8, and 12 in cache line zero.
      dut.io.vectorMemoryRequest.ready.poke(false.B)
      val vle32 =
        (BigInt(1) << 25) | (BigInt(6) << 12) |
          (BigInt(4) << 7) | 0x07
      respond(vle32)
      var walkCycles = 0
      while (
        !dut.io.vectorPageTableRequest.valid.peek().litToBoolean &&
        walkCycles < 24
      ) {
        dut.clock.step()
        walkCycles += 1
      }
      assert(dut.io.vectorPageTableRequest.valid.peek().litToBoolean)
      dut.io.vectorPageTableRequest.bits.address.expect(0.U)
      dut.clock.step()
      // Identity-mapped level-one superpage with V|R|W|A|D.
      dut.io.vectorPageTableResponse.valid.poke(true.B)
      dut.io.vectorPageTableResponse.bits.pte.poke("hc7".U)
      dut.clock.step()
      dut.io.vectorPageTableResponse.valid.poke(false.B)
      var memoryCycles = 0
      while (
        !dut.io.vectorMemoryRequest.valid.peek().litToBoolean &&
        memoryCycles < 24
      ) {
        dut.clock.step()
        memoryCycles += 1
      }
      assert(dut.io.vectorMemoryRequest.valid.peek().litToBoolean)
      dut.io.vectorMemoryRequest.bits.lineAddress.expect(0.U)
      dut.io.vectorMemoryRequest.bits.isWrite.expect(false.B)
      dut.io.vectorMemoryRequest.ready.poke(true.B)
      dut.clock.step()

      val lineData = (0 until config.lanes).foldLeft(BigInt(0)) {
        case (value, lane) =>
          value | (BigInt(0x100 + lane) << ((lane * 4) * 8))
      }
      dut.io.vectorMemoryResponse.valid.poke(true.B)
      dut.io.vectorMemoryResponse.bits.readData.poke(lineData.U)
      dut.io.vectorMemoryResponse.bits.fault.poke(false.B)
      dut.clock.step()
      dut.io.vectorMemoryResponse.valid.poke(false.B)

      sawVectorWrite = false
      for (_ <- 0 until 24) {
        if (dut.io.committedVectorWriteback.valid.peek().litToBoolean) {
          dut.io.committedVectorWriteback.bits.vd.expect(4.U)
          for (lane <- 0 until config.lanes) {
            dut.io.committedVectorWriteback.bits.data(lane)
              .expect((0x100 + lane).U)
          }
          sawVectorWrite = true
        }
        dut.clock.step()
      }
      assert(sawVectorWrite)
    }
  }

  it should "execute a scalar load through the shared translated cache" in {
    val config = GpuConfig(lanes = 4, warps = 1)
    simulate(new GpuCore(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      defaults(dut, config.lanes)
      dut.io.fetchRequest.ready.poke(false.B)
      dut.io.launch.valid.poke(true.B)
      dut.io.launch.bits.startPc.poke(0x300.U)
      dut.io.launch.bits.activeMask.poke(0xf.U)
      dut.clock.step(); dut.io.launch.valid.poke(false.B)
      var cycles = 0
      while (!dut.io.fetchRequest.valid.peek().litToBoolean && cycles < 16) {
        dut.clock.step(); cycles += 1
      }
      assert(dut.io.fetchRequest.valid.peek().litToBoolean)
      dut.io.fetchRequest.ready.poke(true.B); dut.clock.step()
      dut.io.fetchRequest.ready.poke(false.B)
      dut.io.fetchResponse.valid.poke(true.B)
      dut.io.fetchResponse.bits.instruction.poke("h00002083".U) // lw x1, 0(x0)
      while (!dut.io.fetchResponse.ready.peek().litToBoolean) { dut.clock.step() }
      dut.clock.step(); dut.io.fetchResponse.valid.poke(false.B)

      cycles = 0
      while (!dut.io.vectorPageTableRequest.valid.peek().litToBoolean && cycles < 24) {
        dut.clock.step(); cycles += 1
      }
      assert(dut.io.vectorPageTableRequest.valid.peek().litToBoolean)
      dut.clock.step()
      dut.io.vectorPageTableResponse.valid.poke(true.B)
      dut.io.vectorPageTableResponse.bits.pte.poke("hc7".U)
      dut.clock.step(); dut.io.vectorPageTableResponse.valid.poke(false.B)

      cycles = 0
      while (!dut.io.vectorMemoryRequest.valid.peek().litToBoolean && cycles < 24) {
        dut.clock.step(); cycles += 1
      }
      assert(dut.io.vectorMemoryRequest.valid.peek().litToBoolean)
      dut.io.vectorMemoryRequest.bits.lineAddress.expect(0.U)
      dut.clock.step()
      dut.io.vectorMemoryResponse.valid.poke(true.B)
      dut.io.vectorMemoryResponse.bits.readData.poke("hdeadbeef".U)
      dut.clock.step(); dut.io.vectorMemoryResponse.valid.poke(false.B)

      var sawWrite = false
      for (_ <- 0 until 24) {
        if (dut.io.committedWriteback.valid.peek().litToBoolean) {
          dut.io.committedWriteback.bits.rd.expect(1.U)
          dut.io.committedWriteback.bits.data.expect("hdeadbeef".U)
          sawWrite = true
        }
        dut.clock.step()
      }
      assert(sawWrite)
    }
  }

  it should "execute an FP load through the shared translated cache" in {
    val config = GpuConfig(lanes = 4, warps = 1)
    simulate(new GpuCore(config, enableFpuBackend = true)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      defaults(dut, config.lanes)
      dut.io.fetchRequest.ready.poke(false.B)
      dut.io.launch.valid.poke(true.B)
      dut.io.launch.bits.startPc.poke(0x340.U)
      dut.io.launch.bits.activeMask.poke(0xf.U)
      dut.clock.step(); dut.io.launch.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.fetchRequest.valid.peek().litToBoolean && cycles < 16) {
        dut.clock.step(); cycles += 1
      }
      assert(dut.io.fetchRequest.valid.peek().litToBoolean)
      dut.io.fetchRequest.ready.poke(true.B); dut.clock.step()
      dut.io.fetchRequest.ready.poke(false.B)
      dut.io.fetchResponse.valid.poke(true.B)
      dut.io.fetchResponse.bits.instruction.poke(
        "b000000000000_00000_010_00100_0000111".U) // flw f4, 0(x0)
      while (!dut.io.fetchResponse.ready.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.clock.step(); dut.io.fetchResponse.valid.poke(false.B)

      cycles = 0
      while (!dut.io.vectorPageTableRequest.valid.peek().litToBoolean &&
          cycles < 24) {
        dut.clock.step(); cycles += 1
      }
      assert(dut.io.vectorPageTableRequest.valid.peek().litToBoolean)
      dut.clock.step()
      dut.io.vectorPageTableResponse.valid.poke(true.B)
      dut.io.vectorPageTableResponse.bits.pte.poke("hc7".U)
      dut.clock.step(); dut.io.vectorPageTableResponse.valid.poke(false.B)

      cycles = 0
      while (!dut.io.vectorMemoryRequest.valid.peek().litToBoolean &&
          cycles < 24) {
        dut.clock.step(); cycles += 1
      }
      assert(dut.io.vectorMemoryRequest.valid.peek().litToBoolean)
      dut.io.vectorMemoryRequest.bits.lineAddress.expect(0.U)
      dut.clock.step()
      dut.io.vectorMemoryResponse.valid.poke(true.B)
      dut.io.vectorMemoryResponse.bits.readData.poke("hdeadbeef".U)
      dut.io.vectorMemoryResponse.bits.fault.poke(false.B)
      dut.clock.step(); dut.io.vectorMemoryResponse.valid.poke(false.B)

      var sawWrite = false
      for (_ <- 0 until 32) {
        if (dut.io.committedFpuWriteback.valid.peek().litToBoolean) {
          dut.io.committedFpuWriteback.bits.rd.expect(4.U)
          dut.io.committedFpuWriteback.bits.data.expect("hdeadbeef".U)
          sawWrite = true
        }
        dut.clock.step()
      }
      assert(sawWrite)
    }
  }

  it should "resume a fault-blocked warp from the commanded PC" in {
    val config = GpuConfig(lanes = 4, warps = 1)
    simulate(new GpuCore(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      defaults(dut, config.lanes)
      dut.io.fetchRequest.ready.poke(false.B)
      dut.io.trap.ready.poke(true.B)
      dut.io.launch.valid.poke(true.B)
      dut.io.launch.bits.startPc.poke(0x500.U)
      dut.io.launch.bits.activeMask.poke(0xf.U)
      dut.clock.step(); dut.io.launch.valid.poke(false.B)
      while (!dut.io.fetchRequest.valid.peek().litToBoolean) { dut.clock.step() }
      dut.io.fetchRequest.ready.poke(true.B); dut.clock.step()
      dut.io.fetchRequest.ready.poke(false.B)
      dut.io.fetchResponse.valid.poke(true.B)
      dut.io.fetchResponse.bits.instruction.poke("hffffffff".U)
      while (!dut.io.fetchResponse.ready.peek().litToBoolean) { dut.clock.step() }
      dut.clock.step(); dut.io.fetchResponse.valid.poke(false.B)
      var cycles = 0
      while (!dut.io.trap.valid.peek().litToBoolean && cycles < 16) {
        dut.clock.step(); cycles += 1
      }
      assert(dut.io.trap.valid.peek().litToBoolean)
      dut.io.trap.bits.cause.expect(gpu.core.trap.TrapCause.illegalInstruction.U)
      dut.clock.step()

      dut.io.faultResume.valid.poke(true.B)
      dut.io.faultResume.bits.warpId.poke(0.U)
      dut.io.faultResume.bits.pc.poke(0x600.U)
      dut.io.faultResume.bits.activeMask.poke(0x5.U)
      while (!dut.io.faultResume.ready.peek().litToBoolean) { dut.clock.step() }
      dut.clock.step(); dut.io.faultResume.valid.poke(false.B)
      cycles = 0
      while (!dut.io.fetchRequest.valid.peek().litToBoolean && cycles < 16) {
        dut.clock.step(); cycles += 1
      }
      assert(dut.io.fetchRequest.valid.peek().litToBoolean)
      dut.io.fetchRequest.bits.pc.expect(0x600.U)
    }
  }
}
