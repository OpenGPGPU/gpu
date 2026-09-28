package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class KernelShaderStageSpec extends AnyFlatSpec {
  behavior of "KernelShaderStage"

  private def idle(dut: KernelShaderStage): Unit = {
    dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
    dut.io.instructionSatp.poke(0.U)
    dut.io.instructionTlbFlush.valid.poke(false.B)
    dut.io.instructionTlbFlush.bits.poke(0.U.asTypeOf(dut.io.instructionTlbFlush.bits))
    dut.io.vectorSatp.poke(0.U)
    dut.io.vectorTlbFlush.valid.poke(false.B)
    dut.io.vectorTlbFlush.bits.poke(0.U.asTypeOf(dut.io.vectorTlbFlush.bits))
    dut.io.launch.valid.poke(false.B)
    dut.io.launch.kernelPc.poke(0.U)
    dut.io.launch.kernargAddress.poke(0.U)
    dut.io.launch.gridX.poke(1.U); dut.io.launch.gridY.poke(1.U); dut.io.launch.gridZ.poke(1.U)
    dut.io.launch.localX.poke(3.U); dut.io.launch.localY.poke(1.U); dut.io.launch.localZ.poke(1.U)
    dut.io.completion.ready.poke(true.B)
    dut.io.memoryRequest.ready.poke(true.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.readData.poke(0.U)
    dut.io.memoryResponse.bits.fault.poke(false.B)
    dut.io.memoryResponse.bits.transactionId.poke(0.U)
    dut.io.trap.ready.poke(true.B)
    dut.io.simtBranch.valid.poke(false.B)
    dut.io.simtBranch.bits.poke(0.U.asTypeOf(dut.io.simtBranch.bits))
  }

  /** Drive one memory response and hold it until the consumer is ready.
   * memoryResponse is a Flipped Decoupled input, so a single-cycle pulse is
   * only safe if ready happens to be high that cycle.
   */
  private def respond(dut: KernelShaderStage, id: BigInt, data: BigInt): Unit = {
    dut.io.memoryResponse.valid.poke(true.B)
    dut.io.memoryResponse.bits.transactionId.poke(id.U)
    dut.io.memoryResponse.bits.readData.poke(data.U)
    var cycles = 0
    while (!dut.io.memoryResponse.ready.peek().litToBoolean && cycles < 40) {
      dut.clock.step(); cycles += 1
    }
    assert(dut.io.memoryResponse.ready.peek().litToBoolean,
      s"memory response was never accepted: 0x$data.toString(16)")
    dut.clock.step()
    dut.io.memoryResponse.valid.poke(false.B)
  }

  /** Wait for the next memory request and return its transaction id. */
  private def awaitRequest(dut: KernelShaderStage, limit: Int = 60): BigInt = {
    var cycles = 0
    while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < limit) {
      dut.clock.step(); cycles += 1
    }
    assert(dut.io.memoryRequest.valid.peek().litToBoolean, "expected a memory request")
    dut.io.memoryRequest.bits.transactionId.peek().litValue
  }

  private def awaitCompletion(dut: KernelShaderStage, limit: Int = 80): Unit = {
    var cycles = 0
    while (!dut.io.completion.valid.peek().litToBoolean && cycles < limit) {
      dut.clock.step(); cycles += 1
    }
    dut.io.completion.valid.expect(true.B)
    dut.io.completion.bits.success.expect(true.B)
  }

  it should "emit a draw's shader descriptor via KernelEmit and run it to completion" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)

      // A draw's shader descriptor: entry PC + kernarg buffer + grid/local.
      // The KernelEmit-produced launch must drive the instruction fetch at the
      // descriptor's kernel PC, exactly as the compute unit harness drove it.
      dut.io.launch.kernelPc.poke(0x1000.U)
      dut.io.launch.kernargAddress.poke(0x8000.U)
      dut.io.launch.localX.poke(3.U)
      dut.io.launch.valid.poke(true.B)
      assert(dut.io.launch.ready.peek().litToBoolean, "launch must be accepted")
      dut.clock.step(); dut.io.launch.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 40) {
        dut.clock.step(); cycles += 1
      }
      assert(dut.io.memoryRequest.valid.peek().litToBoolean)
      dut.io.memoryRequest.bits.address.expect(0x1000.U)
      val fetchId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.transactionId.poke(fetchId.U)
      dut.io.memoryResponse.bits.readData.poke(
        (BigInt("30500073", 16) << 32 | BigInt("00500093", 16)).U)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)

      cycles = 0
      while (!dut.io.completion.valid.peek().litToBoolean && cycles < 40) {
        dut.clock.step(); cycles += 1
      }
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  // A `uniform float` lowers to flw off the kernarg base, which the CU
  // preloads into x1, so the shader needs no prologue. The shader CU used to
  // be built without FpuBackend: flw could not decode, never issued its load
  // and hung the warp. This covers that the FP decode and memory datapath is
  // now live on the graphics side.
  //
  // Known gap, deliberately not asserted here: the f-register writeback is not
  // yet observable from this stage. GpuComputeUnit passes committedFpuWriteback
  // out of the core but KernelShaderStage does not consume it, and
  // GpuComputeUnit ties fpuInitialize invalid, so a value loaded by flw cannot
  // yet be read back by a following scalar f-register instruction. Reading it
  // as a vector `.vf` operand goes through the separate fpu.fvfRead sideband
  // and is unaffected, which is the path a compiler needs first.
  it should "retire a scalar FP load from kernarg on the shader CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)

      val memory = Map[BigInt, BigInt](
        // 0x1000: flw f1, 0(x1) then cease
        BigInt(0x1000) -> BigInt("305000730000a087", 16),
        // kernarg[0] = 5.0f, which x1 already points at
        BigInt(0x8000) -> BigInt("40a00000", 16))
      val fetched = scala.collection.mutable.ArrayBuffer[BigInt]()
      val traps = scala.collection.mutable.ArrayBuffer[(BigInt, BigInt, BigInt)]()

      dut.io.launch.kernelPc.poke(0x1000.U)
      dut.io.launch.kernargAddress.poke(0x8000.U)
      dut.io.launch.localX.poke(3.U)
      dut.io.launch.valid.poke(true.B)
      assert(dut.io.launch.ready.peek().litToBoolean, "launch must be accepted")
      dut.clock.step(); dut.io.launch.valid.poke(false.B)

      // Service the memory port the way KernelFragStageSpec does: re-present
      // the pending response every cycle so a beat is never dropped.
      var resp = false
      var id = BigInt(0)
      var data = BigInt(0)
      var guard = 0
      var done = false
      while (!done && guard < 300) {
        dut.io.memoryResponse.valid.poke(resp)
        if (resp) {
          dut.io.memoryResponse.bits.transactionId.poke(id.U)
          dut.io.memoryResponse.bits.readData.poke(data.U)
          dut.io.memoryResponse.bits.fault.poke(false.B)
        }
        val fired = dut.io.memoryRequest.valid.peek().litToBoolean &&
          dut.io.memoryRequest.ready.peek().litToBoolean
        if (fired) {
          val addr = dut.io.memoryRequest.bits.address.peek().litValue
          id = dut.io.memoryRequest.bits.transactionId.peek().litValue
          if (!dut.io.memoryRequest.bits.isWrite.peek().litToBoolean) {
            fetched += addr
            data = memory.getOrElse(addr, BigInt(0))
          } else data = BigInt(0)
          resp = true
        } else resp = false
        if (dut.io.trap.valid.peek().litToBoolean)
          traps += ((dut.io.trap.bits.pc.peek().litValue,
            dut.io.trap.bits.cause.peek().litValue,
            dut.io.trap.bits.tval.peek().litValue))
        dut.clock.step()
        guard += 1
        done = dut.io.completion.valid.peek().litToBoolean
      }
      dut.io.memoryResponse.valid.poke(false.B)

      val where = fetched.map(a => f"0x${a.toString(16)}").mkString(",")
      val why = traps
        .map(t => s"pc=0x${t._1.toString(16)} cause=${t._2} tval=0x${t._3.toString(16)}")
        .mkString("; ")
      assert(done, s"shader CU never completed; fetched [$where]; traps [$why]")
      assert(traps.isEmpty, s"shader trapped [$why]; fetched [$where]")
      // The load must actually reach the kernarg base rather than stalling the
      // warp, which is what a missing FpuBackend caused.
      assert(fetched.contains(BigInt(0x8000)),
        s"flw never issued its kernarg load; fetched [$where]")
      dut.io.completion.bits.success.expect(true.B)
    }
  }
}
