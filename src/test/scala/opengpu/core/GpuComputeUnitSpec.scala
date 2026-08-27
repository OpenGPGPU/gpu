package opengpu.core

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class GpuComputeUnitSpec extends AnyFlatSpec {
  behavior of "GpuComputeUnit"

  it should "run a dispatched warp through cease and complete its kernel" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new GpuComputeUnit(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.kernel.valid.poke(false.B)
      dut.io.completion.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.readData.poke(0.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.bits.transactionId.poke(0.U)
      dut.io.invalidateInstructionCache.poke(false.B)
      dut.io.instructionSatp.poke(0.U)
      dut.io.instructionTlbFlush.valid.poke(false.B)
      dut.io.instructionTlbFlush.bits.poke(
        0.U.asTypeOf(dut.io.instructionTlbFlush.bits))
      dut.io.vectorSatp.poke(0.U)
      dut.io.vectorTlbFlush.valid.poke(false.B)
      dut.io.vectorTlbFlush.bits.poke(0.U.asTypeOf(dut.io.vectorTlbFlush.bits))
      dut.io.fpu.ready.poke(false.B)
      dut.io.vector.ready.poke(false.B)
      dut.io.memory.ready.poke(false.B)
      dut.io.unsupportedSystem.ready.poke(false.B)
      dut.io.trap.ready.poke(false.B)
      dut.io.simtBranch.valid.poke(false.B)
      dut.io.simtBranch.bits.poke(0.U.asTypeOf(dut.io.simtBranch.bits))
      dut.io.l1Invalidate.valid.poke(false.B)
      dut.io.l1Invalidate.bits.lineAddress.poke(0.U)
      dut.io.l1InvalidateDone.ready.poke(true.B)
      dut.io.globalAtomicRequest.ready.poke(true.B)
      dut.io.globalAtomicResponse.valid.poke(false.B)
      dut.io.globalAtomicResponse.bits.poke(
        0.U.asTypeOf(dut.io.globalAtomicResponse.bits))

      dut.io.kernel.valid.poke(true.B)
      dut.io.kernel.bits.kernelPc.poke(0x1000.U)
      dut.io.kernel.bits.kernargAddress.poke(0x8000.U)
      (0 until 3).foreach { i =>
        dut.io.kernel.bits.gridSize(i).poke(1.U)
        dut.io.kernel.bits.localSize(i).poke(Seq(3, 1, 1)(i).U)
      }
      dut.clock.step(); dut.io.kernel.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 40) {
        dut.clock.step(); cycles += 1
      }
      assert(dut.io.memoryRequest.valid.peek().litToBoolean)
      dut.io.memoryRequest.bits.address.expect(0x1000.U)
      dut.io.memoryRequest.bits.sizeLog2.expect(6.U)
      val fetchTransactionId =
        dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.readData.poke("h30500073".U)
      dut.io.memoryResponse.bits.transactionId.poke(fetchTransactionId.U)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)

      cycles = 0
      while (!dut.io.completion.valid.peek().litToBoolean && cycles < 40) {
        dut.clock.step(); cycles += 1
      }
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.success.expect(true.B)
      dut.io.active.expect(0.U)
    }
  }

  it should "run a two-instruction kernel (addi + cease) from one refill line" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new GpuComputeUnit(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.kernel.valid.poke(false.B)
      dut.io.completion.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.readData.poke(0.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.bits.transactionId.poke(0.U)
      dut.io.invalidateInstructionCache.poke(false.B)
      dut.io.instructionSatp.poke(0.U)
      dut.io.instructionTlbFlush.valid.poke(false.B)
      dut.io.instructionTlbFlush.bits.poke(
        0.U.asTypeOf(dut.io.instructionTlbFlush.bits))
      dut.io.vectorSatp.poke(0.U)
      dut.io.vectorTlbFlush.valid.poke(false.B)
      dut.io.vectorTlbFlush.bits.poke(0.U.asTypeOf(dut.io.vectorTlbFlush.bits))
      dut.io.fpu.ready.poke(false.B)
      dut.io.vector.ready.poke(false.B)
      dut.io.memory.ready.poke(false.B)
      dut.io.unsupportedSystem.ready.poke(false.B)
      dut.io.trap.ready.poke(false.B)
      dut.io.simtBranch.valid.poke(false.B)
      dut.io.simtBranch.bits.poke(0.U.asTypeOf(dut.io.simtBranch.bits))
      dut.io.l1Invalidate.valid.poke(false.B)
      dut.io.l1Invalidate.bits.lineAddress.poke(0.U)
      dut.io.l1InvalidateDone.ready.poke(true.B)
      dut.io.globalAtomicRequest.ready.poke(true.B)
      dut.io.globalAtomicResponse.valid.poke(false.B)
      dut.io.globalAtomicResponse.bits.poke(
        0.U.asTypeOf(dut.io.globalAtomicResponse.bits))

      dut.io.kernel.valid.poke(true.B)
      dut.io.kernel.bits.kernelPc.poke(0x1000.U)
      dut.io.kernel.bits.kernargAddress.poke(0x8000.U)
      (0 until 3).foreach { i =>
        dut.io.kernel.bits.gridSize(i).poke(1.U)
        dut.io.kernel.bits.localSize(i).poke(Seq(3, 1, 1)(i).U)
      }
      dut.clock.step(); dut.io.kernel.valid.poke(false.B)

      // One refill line supplies both consecutive PCs: word0 = `addi x1, x0, 5`,
      // word1 = the custom `cease` opcode.  The second fetch is a line hit.
      var cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 40) {
        dut.clock.step(); cycles += 1
      }
      assert(dut.io.memoryRequest.valid.peek().litToBoolean)
      dut.io.memoryRequest.bits.address.expect(0x1000.U)
      val fetchTransactionId =
        dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.readData.poke(
        (BigInt("30500073", 16) << 32 | BigInt("00500093", 16)).U)
      dut.io.memoryResponse.bits.transactionId.poke(fetchTransactionId.U)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)

      cycles = 0
      while (!dut.io.completion.valid.peek().litToBoolean && cycles < 40) {
        dut.clock.step(); cycles += 1
      }
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.success.expect(true.B)
      dut.io.active.expect(0.U)
    }
  }

  it should "read a kernarg word through the data path (lw via x1) then complete" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new GpuComputeUnit(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.kernel.valid.poke(false.B)
      dut.io.completion.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.readData.poke(0.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.bits.transactionId.poke(0.U)
      dut.io.invalidateInstructionCache.poke(false.B)
      dut.io.instructionSatp.poke(0.U)
      dut.io.instructionTlbFlush.valid.poke(false.B)
      dut.io.instructionTlbFlush.bits.poke(
        0.U.asTypeOf(dut.io.instructionTlbFlush.bits))
      dut.io.vectorSatp.poke(0.U)
      dut.io.vectorTlbFlush.valid.poke(false.B)
      dut.io.vectorTlbFlush.bits.poke(0.U.asTypeOf(dut.io.vectorTlbFlush.bits))
      dut.io.fpu.ready.poke(false.B)
      dut.io.vector.ready.poke(false.B)
      dut.io.memory.ready.poke(false.B)
      dut.io.unsupportedSystem.ready.poke(false.B)
      dut.io.trap.ready.poke(false.B)
      dut.io.simtBranch.valid.poke(false.B)
      dut.io.simtBranch.bits.poke(0.U.asTypeOf(dut.io.simtBranch.bits))
      dut.io.l1Invalidate.valid.poke(false.B)
      dut.io.l1Invalidate.bits.lineAddress.poke(0.U)
      dut.io.l1InvalidateDone.ready.poke(true.B)
      dut.io.globalAtomicRequest.ready.poke(true.B)
      dut.io.globalAtomicResponse.valid.poke(false.B)
      dut.io.globalAtomicResponse.bits.poke(
        0.U.asTypeOf(dut.io.globalAtomicResponse.bits))

      // Kernel ABI (WarpContextInitializer): x1 = kernarg address.  The program
      // is one refill line: word0 = `lw x9, 0(x1)` (read a varying/uniform),
      // word1 = `sw x9, 4(x1)` (write an output word), word2 = `cease`.  This
      // exercises the whole data path through the memory interconnect: a scalar
      // load (source 1 read) and a scalar store (source 1 write, whose response
      // must release the transaction slot), then completion.
      dut.io.kernel.valid.poke(true.B)
      dut.io.kernel.bits.kernelPc.poke(0x1000.U)
      dut.io.kernel.bits.kernargAddress.poke(0x8000.U)
      (0 until 3).foreach { i =>
        dut.io.kernel.bits.gridSize(i).poke(1.U)
        dut.io.kernel.bits.localSize(i).poke(Seq(3, 1, 1)(i).U)
      }
      dut.clock.step(); dut.io.kernel.valid.poke(false.B)

      // Little-endian word order within the refill line.
      val lw = BigInt("0000a483", 16)   // lw x9, 0(x1)
      val sw = BigInt("0090a223", 16)   // sw x9, 4(x1)
      val cease = BigInt("30500073", 16)
      def programLine: BigInt = (cease << 64) | (sw << 32) | lw
      def kernargLine: BigInt =
        BigInt("cafe0001", 16) // varying/uniform word at kernarg offset 0
      def lineFor(addr: Long): BigInt =
        if ((addr & 0xffffffffL) == 0x1000L) programLine else kernargLine

      // Deferred-response harness: capture a request on the cycle it fires,
      // present its response on the *next* cycle (honouring the >=1-cycle
      // request-to-response spacing the interconnect requires).  Count data-path
      // traffic so we prove a read and a write each traversed the interconnect.
      var respValid = false; var respId = BigInt(0); var respData = BigInt(0)
      var dataReads = 0; var dataWrites = 0; var firstDataAddr = -1L
      var guard = 0
      while (!dut.io.completion.valid.peek().litToBoolean && guard < 200) {
        if (respValid) {
          dut.io.memoryResponse.valid.poke(true.B)
          dut.io.memoryResponse.bits.transactionId.poke(respId.U)
          dut.io.memoryResponse.bits.readData.poke(respData.U)
        } else dut.io.memoryResponse.valid.poke(false.B)
        val fired = dut.io.memoryRequest.valid.peek().litToBoolean &&
          dut.io.memoryRequest.ready.peek().litToBoolean
        if (fired) {
          val id = dut.io.memoryRequest.bits.transactionId.peek().litValue
          val addr = dut.io.memoryRequest.bits.address.peek().litValue.toLong
          val isWrite = dut.io.memoryRequest.bits.isWrite.peek().litToBoolean
          if ((addr & 0xffffffffL) != 0x1000L) {
            if (isWrite) dataWrites += 1 else dataReads += 1
            firstDataAddr = addr
          }
          respId = id
          respData = lineFor(addr)
          respValid = true
        } else respValid = false
        dut.clock.step()
        guard += 1
      }
      assert(guard < 200, "did not complete within a bounded number of cycles")
      assert(firstDataAddr == 0x8000L,
        f"kernarg read must target x1=kernargAddress (0x8000), got 0x$firstDataAddr%x")
      assert(dataReads >= 1, "kernel must perform a kernarg read")
      assert(dataWrites >= 1, "kernel must perform an output write")
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.success.expect(true.B)
      dut.io.active.expect(0.U)
    }
  }

  it should "dispatch a grid of multiple workgroups and complete" in {
    // gridSize=(2,1,1), localSize=(4,1,1) with lanes=4 -> 2 workgroups of 1
    // warp each, dispatched back to back through the same warp slots.
    val config = GpuConfig(lanes = 4, warps = 4)
    simulate(new GpuComputeUnit(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.kernel.valid.poke(false.B)
      dut.io.completion.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.readData.poke(0.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.bits.transactionId.poke(0.U)
      dut.io.invalidateInstructionCache.poke(false.B)
      dut.io.instructionSatp.poke(0.U)
      dut.io.instructionTlbFlush.valid.poke(false.B)
      dut.io.instructionTlbFlush.bits.poke(
        0.U.asTypeOf(dut.io.instructionTlbFlush.bits))
      dut.io.vectorSatp.poke(0.U)
      dut.io.vectorTlbFlush.valid.poke(false.B)
      dut.io.vectorTlbFlush.bits.poke(0.U.asTypeOf(dut.io.vectorTlbFlush.bits))
      dut.io.fpu.ready.poke(false.B)
      dut.io.vector.ready.poke(false.B)
      dut.io.memory.ready.poke(false.B)
      dut.io.unsupportedSystem.ready.poke(false.B)
      dut.io.trap.ready.poke(false.B)
      dut.io.simtBranch.valid.poke(false.B)
      dut.io.simtBranch.bits.poke(0.U.asTypeOf(dut.io.simtBranch.bits))
      dut.io.l1Invalidate.valid.poke(false.B)
      dut.io.l1Invalidate.bits.lineAddress.poke(0.U)
      dut.io.l1InvalidateDone.ready.poke(true.B)
      dut.io.globalAtomicRequest.ready.poke(true.B)
      dut.io.globalAtomicResponse.valid.poke(false.B)
      dut.io.globalAtomicResponse.bits.poke(
        0.U.asTypeOf(dut.io.globalAtomicResponse.bits))

      dut.io.kernel.valid.poke(true.B)
      dut.io.kernel.bits.kernelPc.poke(0x1000.U)
      dut.io.kernel.bits.kernargAddress.poke(0x8000.U)
      val grid = Seq(2, 1, 1)
      val local = Seq(4, 1, 1)
      (0 until 3).foreach { i =>
        dut.io.kernel.bits.gridSize(i).poke(grid(i).U)
        dut.io.kernel.bits.localSize(i).poke(local(i).U)
      }
      dut.clock.step(); dut.io.kernel.valid.poke(false.B)

      // The whole grid shares one 64-byte instruction line (addi x1,x0,5 ; cease),
      // so a single refill serves every warp (subsequent fetches are line hits).
      // Deferred-response harness: capture a request on the cycle it fires,
      // present its response a few cycles later to widen the in-flight window.
      val responseDelay = 3
      var pendingId = BigInt(0); var pendingCycles = -1
      var guard = 0
      while (!dut.io.completion.valid.peek().litToBoolean && guard < 200) {
        if (pendingCycles == 0) {
          dut.io.memoryResponse.valid.poke(true.B)
          dut.io.memoryResponse.bits.transactionId.poke(pendingId.U)
          dut.io.memoryResponse.bits.readData.poke(
            (BigInt("30500073", 16) << 32 | BigInt("00500093", 16)).U)
        } else dut.io.memoryResponse.valid.poke(false.B)
        val fired = dut.io.memoryRequest.valid.peek().litToBoolean &&
          dut.io.memoryRequest.ready.peek().litToBoolean
        if (fired && pendingCycles < 0) {
          pendingId = dut.io.memoryRequest.bits.transactionId.peek().litValue
          pendingCycles = responseDelay
        } else if (pendingCycles > 0) pendingCycles -= 1
        else if (pendingCycles == 0 &&
          dut.io.memoryResponse.ready.peek().litToBoolean) pendingCycles = -1
        dut.clock.step()
        guard += 1
      }
      assert(guard < 200, "did not complete within a bounded number of cycles")
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.success.expect(true.B)
      dut.io.active.expect(0.U)
    }
  }

  it should "dispatch three concurrent warps without launch slot mismatch" in {
    // localSize=(12,1,1) with lanes=4 -> 3 warps in one workgroup.  Warp C is
    // initialized while warps A/B are still running; A finishes during C's
    // initialization window, freeing a lower slot than the one C snapshotted.
    // The scheduler must launch C into the snapshotted slot, not its own pick.
    val config = GpuConfig(lanes = 4, warps = 4)
    simulate(new GpuComputeUnit(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.kernel.valid.poke(false.B)
      dut.io.completion.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.readData.poke(0.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.bits.transactionId.poke(0.U)
      dut.io.invalidateInstructionCache.poke(false.B)
      dut.io.instructionSatp.poke(0.U)
      dut.io.instructionTlbFlush.valid.poke(false.B)
      dut.io.instructionTlbFlush.bits.poke(
        0.U.asTypeOf(dut.io.instructionTlbFlush.bits))
      dut.io.vectorSatp.poke(0.U)
      dut.io.vectorTlbFlush.valid.poke(false.B)
      dut.io.vectorTlbFlush.bits.poke(0.U.asTypeOf(dut.io.vectorTlbFlush.bits))
      dut.io.fpu.ready.poke(false.B)
      dut.io.vector.ready.poke(false.B)
      dut.io.memory.ready.poke(false.B)
      dut.io.unsupportedSystem.ready.poke(false.B)
      dut.io.trap.ready.poke(false.B)
      dut.io.simtBranch.valid.poke(false.B)
      dut.io.simtBranch.bits.poke(0.U.asTypeOf(dut.io.simtBranch.bits))
      dut.io.l1Invalidate.valid.poke(false.B)
      dut.io.l1Invalidate.bits.lineAddress.poke(0.U)
      dut.io.l1InvalidateDone.ready.poke(true.B)
      dut.io.globalAtomicRequest.ready.poke(true.B)
      dut.io.globalAtomicResponse.valid.poke(false.B)
      dut.io.globalAtomicResponse.bits.poke(
        0.U.asTypeOf(dut.io.globalAtomicResponse.bits))

      dut.io.kernel.valid.poke(true.B)
      dut.io.kernel.bits.kernelPc.poke(0x1000.U)
      dut.io.kernel.bits.kernargAddress.poke(0x8000.U)
      val grid = Seq(1, 1, 1)
      val local = Seq(12, 1, 1)
      (0 until 3).foreach { i =>
        dut.io.kernel.bits.gridSize(i).poke(grid(i).U)
        dut.io.kernel.bits.localSize(i).poke(local(i).U)
      }
      dut.clock.step(); dut.io.kernel.valid.poke(false.B)

      // One refill line (addi x1,x0,5 ; cease) serves every warp; answer each
      // request on the next cycle so warps finish fast enough to overlap the
      // initialization window of later warps.
      var pendingId = BigInt(0); var respond = false
      var guard = 0
      while (!dut.io.completion.valid.peek().litToBoolean && guard < 200) {
        if (respond) {
          dut.io.memoryResponse.valid.poke(true.B)
          dut.io.memoryResponse.bits.transactionId.poke(pendingId.U)
          dut.io.memoryResponse.bits.readData.poke(
            (BigInt("30500073", 16) << 32 | BigInt("00500093", 16)).U)
        } else dut.io.memoryResponse.valid.poke(false.B)
        val fired = dut.io.memoryRequest.valid.peek().litToBoolean &&
          dut.io.memoryRequest.ready.peek().litToBoolean
        if (fired) {
          pendingId = dut.io.memoryRequest.bits.transactionId.peek().litValue
          respond = true
        } else respond = false
        dut.clock.step()
        guard += 1
      }
      assert(guard < 200, "did not complete within a bounded number of cycles")
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.success.expect(true.B)
      dut.io.active.expect(0.U)
    }
  }

  it should "dispatch a kernel of several warps within one workgroup and complete" in {
    // gridSize=(1,1,1), localSize=(8,1,1) with lanes=4 -> 2 warps in one
    // workgroup, so a single kernel launch covers a 2-warp grid (warps=4).
    val config = GpuConfig(lanes = 4, warps = 4)
    simulate(new GpuComputeUnit(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.kernel.valid.poke(false.B)
      dut.io.completion.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.readData.poke(0.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.bits.transactionId.poke(0.U)
      dut.io.invalidateInstructionCache.poke(false.B)
      dut.io.instructionSatp.poke(0.U)
      dut.io.instructionTlbFlush.valid.poke(false.B)
      dut.io.instructionTlbFlush.bits.poke(
        0.U.asTypeOf(dut.io.instructionTlbFlush.bits))
      dut.io.vectorSatp.poke(0.U)
      dut.io.vectorTlbFlush.valid.poke(false.B)
      dut.io.vectorTlbFlush.bits.poke(0.U.asTypeOf(dut.io.vectorTlbFlush.bits))
      dut.io.fpu.ready.poke(false.B)
      dut.io.vector.ready.poke(false.B)
      dut.io.memory.ready.poke(false.B)
      dut.io.unsupportedSystem.ready.poke(false.B)
      dut.io.trap.ready.poke(false.B)
      dut.io.simtBranch.valid.poke(false.B)
      dut.io.simtBranch.bits.poke(0.U.asTypeOf(dut.io.simtBranch.bits))
      dut.io.l1Invalidate.valid.poke(false.B)
      dut.io.l1Invalidate.bits.lineAddress.poke(0.U)
      dut.io.l1InvalidateDone.ready.poke(true.B)
      dut.io.globalAtomicRequest.ready.poke(true.B)
      dut.io.globalAtomicResponse.valid.poke(false.B)
      dut.io.globalAtomicResponse.bits.poke(
        0.U.asTypeOf(dut.io.globalAtomicResponse.bits))

      dut.io.kernel.valid.poke(true.B)
      dut.io.kernel.bits.kernelPc.poke(0x1000.U)
      dut.io.kernel.bits.kernargAddress.poke(0x8000.U)
      val grid = Seq(1, 1, 1)
      val local = Seq(8, 1, 1)
      (0 until 3).foreach { i =>
        dut.io.kernel.bits.gridSize(i).poke(grid(i).U)
        dut.io.kernel.bits.localSize(i).poke(local(i).U)
      }
      dut.clock.step(); dut.io.kernel.valid.poke(false.B)

      // The whole grid shares one 64-byte instruction line (addi x1,x0,5 ; cease),
      // so a single refill serves every warp (subsequent fetches are line hits).
      var cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 40) {
        dut.clock.step(); cycles += 1
      }
      assert(dut.io.memoryRequest.valid.peek().litToBoolean)
      dut.io.memoryRequest.bits.address.expect(0x1000.U)
      val fetchTransactionId =
        dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.readData.poke(
        (BigInt("30500073", 16) << 32 | BigInt("00500093", 16)).U)
      dut.io.memoryResponse.bits.transactionId.poke(fetchTransactionId.U)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)

      cycles = 0
      while (!dut.io.completion.valid.peek().litToBoolean && cycles < 80) {
        dut.clock.step(); cycles += 1
      }
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.success.expect(true.B)
      dut.io.active.expect(0.U)
    }
  }
}
