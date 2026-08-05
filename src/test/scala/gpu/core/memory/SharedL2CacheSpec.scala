package gpu.core.memory

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class SharedL2CacheSpec extends AnyFlatSpec {
  behavior of "SharedL2Cache"

  private def initialize(dut: SharedL2Cache): Unit = {
    dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
    dut.io.request.valid.poke(false.B)
    dut.io.request.bits.poke(0.U.asTypeOf(dut.io.request.bits))
    dut.io.response.ready.poke(true.B)
    dut.io.memoryRequest.ready.poke(true.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
    (0 until 2).foreach { cu =>
      dut.io.invalidate(cu).ready.poke(true.B)
      dut.io.invalidateDone(cu).valid.poke(false.B)
      dut.io.invalidateDone(cu).bits.poke(
        0.U.asTypeOf(dut.io.invalidateDone(cu).bits))
      dut.io.atomicRequest(cu).valid.poke(false.B)
      dut.io.atomicRequest(cu).bits.poke(
        0.U.asTypeOf(dut.io.atomicRequest(cu).bits))
      dut.io.atomicResponse(cu).ready.poke(true.B)
    }
  }

  private def requestLine(
    dut: SharedL2Cache,
    address: BigInt,
    id: Int,
    isWrite: Boolean = false,
    data: BigInt = 0,
    mask: BigInt = 0
  ): Unit = {
    dut.io.request.bits.address.poke(address.U)
    dut.io.request.bits.transactionId.poke(id.U)
    dut.io.request.bits.sizeLog2.poke(6.U)
    dut.io.request.bits.isWrite.poke(isWrite.B)
    dut.io.request.bits.writeData.poke(data.U)
    dut.io.request.bits.byteMask.poke(mask.U)
    dut.io.request.bits.cacheClient.poke(true.B)
    dut.io.request.bits.cacheResident.poke(isWrite.B)
    var waitCycles = 0
    while (!dut.io.request.ready.peek().litToBoolean && waitCycles < 20) {
      dut.clock.step(); waitCycles += 1
    }
    assert(dut.io.request.ready.peek().litToBoolean,
      f"L2 did not accept address 0x$address%x")
    dut.io.request.valid.poke(true.B)
    dut.clock.step()
    dut.io.request.valid.poke(false.B)
  }

  private def completeLower(dut: SharedL2Cache, data: BigInt, id: Int): Unit = {
    var cycles = 0
    while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 10) {
      dut.clock.step(); cycles += 1
    }
    dut.io.memoryRequest.valid.expect(true.B)
    val lowerId = dut.io.memoryRequest.bits.transactionId.peek().litValue
    dut.clock.step()
    dut.io.memoryResponse.valid.poke(true.B)
    dut.io.memoryResponse.bits.readData.poke(data.U)
    dut.io.memoryResponse.bits.transactionId.poke(lowerId.U)
    dut.io.memoryResponse.bits.fault.poke(false.B)
    dut.clock.step()
    dut.io.memoryResponse.valid.poke(false.B)
    cycles = 0
    while (!dut.io.response.valid.peek().litToBoolean && cycles < 10) {
      dut.clock.step(); cycles += 1
    }
  }

  private def waitMemoryRequest(dut: SharedL2Cache): Unit = {
    var cycles = 0
    while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 12) {
      dut.clock.step(); cycles += 1
    }
    dut.io.memoryRequest.valid.expect(true.B)
  }

  private def waitInvalidation(dut: SharedL2Cache, cu: Int): Unit = {
    var cycles = 0
    while (!dut.io.invalidate(cu).valid.peek().litToBoolean && cycles < 12) {
      dut.clock.step(); cycles += 1
    }
    dut.io.invalidate(cu).valid.expect(true.B)
  }

  private def waitResponse(dut: SharedL2Cache): Unit = {
    var cycles = 0
    while (!dut.io.response.valid.peek().litToBoolean && cycles < 12) {
      dut.clock.step(); cycles += 1
    }
    dut.io.response.valid.expect(true.B)
  }

  it should "allocate a read miss and serve the next access as an L2 hit" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2)) { dut =>
      initialize(dut)
      val line = BigInt("0123456789abcdef", 16)
      requestLine(dut, 0x4000, 3)
      completeLower(dut, line, 3)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.readData.expect(line.U)
      dut.clock.step()

      requestLine(dut, 0x4000, 1)
      dut.clock.step()
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.readData.expect(line.U)
      dut.io.response.bits.transactionId.expect(1.U)
      dut.io.memoryRequest.valid.expect(false.B)
    }
  }

  it should "write through and merge byte-masked stores into a resident line" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2)) { dut =>
      initialize(dut)
      requestLine(dut, 0x8000, 0)
      completeLower(dut, 0x11223344L, 0)
      dut.clock.step()

      requestLine(dut, 0x8000, 1, isWrite = true,
        data = 0x0000aa00L, mask = 0x2)
      completeLower(dut, 0, 1)
      dut.clock.step()

      requestLine(dut, 0x8000, 2)
      // A non-blocking store conservatively invalidates its resident L2 line
      // until the write-through acknowledgement, so the next load refills.
      completeLower(dut, 0x1122aa44L, 2)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.readData.expect(0x1122aa44L.U)
    }
  }

  it should "bypass non-line requests without allocating them" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2)) { dut =>
      initialize(dut)
      while (!dut.io.request.ready.peek().litToBoolean) dut.clock.step()
      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.address.poke(0x1004.U)
      dut.io.request.bits.sizeLog2.poke(2.U)
      dut.io.request.bits.transactionId.poke(2.U)
      dut.clock.step(); dut.io.request.valid.poke(false.B)
      completeLower(dut, 0xdeadbeefL, 2)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.readData.expect(0xdeadbeefL.U)
    }
  }

  it should "invalidate another CU sharer before accepting a write" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2)) { dut =>
      initialize(dut)
      // CU0 local transaction 0 fills the line and becomes a sharer.
      requestLine(dut, 0xc000, 0)
      completeLower(dut, 0x55, 0)
      dut.clock.step()
      // CU1 IDs are 4..7. Its read hit adds CU1 to the sharer vector.
      requestLine(dut, 0xc000, 4)
      dut.clock.step(2)
      // CU1 store must invalidate CU0, but never itself.
      requestLine(dut, 0xc000, 5, isWrite = true, data = 0xaa, mask = 1)
      dut.clock.step()
      dut.io.invalidate(0).valid.expect(true.B)
      dut.io.invalidate(0).bits.lineAddress.expect(0xc000.U)
      dut.io.invalidate(1).valid.expect(false.B)
      dut.clock.step()
      dut.io.memoryRequest.valid.expect(false.B)
      dut.io.invalidateDone(0).valid.poke(true.B)
      dut.io.invalidateDone(0).bits.lineAddress.poke(0xc000.U)
      dut.clock.step()
      dut.io.invalidateDone(0).valid.poke(false.B)
      waitMemoryRequest(dut)
    }
  }

  it should "invalidate sharers before replacing their L2 directory entry" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 1)) { dut =>
      initialize(dut)
      requestLine(dut, 0x0000, 0)
      completeLower(dut, 0x11, 0)
      dut.clock.step()

      // Same set, different tag. The old CU0 L1 copy must be removed before
      // the new CU1 miss is allowed to reach memory.
      requestLine(dut, 0x0200, 4)
      waitInvalidation(dut, 0)
      dut.io.invalidate(0).bits.lineAddress.expect(0x0000.U)
      dut.io.memoryRequest.valid.expect(false.B)
      dut.clock.step()
      dut.io.invalidateDone(0).valid.poke(true.B)
      dut.io.invalidateDone(0).bits.lineAddress.poke(0x0000.U)
      dut.clock.step()
      dut.io.invalidateDone(0).valid.poke(false.B)
      waitMemoryRequest(dut)
    }
  }

  it should "serialize a global AMO with L1 invalidation and update the L2 line" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2)) { dut =>
      initialize(dut)
      requestLine(dut, 0x1000, 0)
      completeLower(dut, 10, 0)
      dut.clock.step()

      dut.io.atomicRequest(1).valid.poke(true.B)
      dut.io.atomicRequest(1).bits.warpId.poke(1.U)
      dut.io.atomicRequest(1).bits.address.poke(0x1000.U)
      dut.io.atomicRequest(1).bits.operand.poke(7.U)
      dut.io.atomicRequest(1).bits.operation.poke(AtomicMemoryOp.add)
      dut.clock.step(); dut.io.atomicRequest(1).valid.poke(false.B)
      dut.clock.step()
      dut.io.invalidate(0).valid.expect(true.B)
      dut.clock.step()
      dut.io.invalidateDone(0).valid.poke(true.B)
      dut.io.invalidateDone(0).bits.lineAddress.poke(0x1000.U)
      dut.clock.step(); dut.io.invalidateDone(0).valid.poke(false.B)

      // The AMO writes only the addressed word to memory.
      dut.clock.step()
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.isWrite.expect(true.B)
      dut.io.memoryRequest.bits.address.expect(0x1000.U)
      dut.io.memoryRequest.bits.byteMask.expect(0xf.U)
      dut.io.memoryRequest.bits.writeData.expect(17.U)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      dut.io.atomicResponse(1).valid.expect(true.B)
      dut.io.atomicResponse(1).bits.oldValue.expect(10.U)
      dut.io.atomicResponse(1).bits.fault.expect(false.B)
      dut.clock.step()

      requestLine(dut, 0x1000, 4)
      dut.clock.step()
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.readData.expect(17.U)
      dut.io.memoryRequest.valid.expect(false.B)
    }
  }

  it should "refill an AMO miss and update the addressed word offset" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2)) { dut =>
      initialize(dut)
      dut.io.atomicRequest(0).valid.poke(true.B)
      dut.io.atomicRequest(0).bits.warpId.poke(0.U)
      dut.io.atomicRequest(0).bits.address.poke(0x2004.U)
      dut.io.atomicRequest(0).bits.operand.poke(3.U)
      dut.io.atomicRequest(0).bits.operation.poke(AtomicMemoryOp.add)
      dut.clock.step(); dut.io.atomicRequest(0).valid.poke(false.B)
      dut.clock.step()
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.isWrite.expect(false.B)
      dut.io.memoryRequest.bits.address.expect(0x2000.U)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.readData.poke((BigInt(5) << 32).U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      dut.clock.step()
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.isWrite.expect(true.B)
      dut.io.memoryRequest.bits.byteMask.expect(0xf0.U)
      dut.io.memoryRequest.bits.writeData.expect((BigInt(8) << 32).U)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      dut.io.atomicResponse(0).valid.expect(true.B)
      dut.io.atomicResponse(0).bits.oldValue.expect(5.U)
    }
  }

  it should "serve a hit in one slice while another slice waits for memory" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2,
      banks = 2)) { dut =>
      initialize(dut)
      requestLine(dut, 0x0000, 0)
      completeLower(dut, 0x11, 0)
      dut.clock.step()
      requestLine(dut, 0x0100, 1)
      completeLower(dut, 0x22, 1)
      dut.clock.step()

      // 0x0000 and 0x0400 select slice 0; 0x0100 selects slice 1.
      requestLine(dut, 0x0400, 2)
      waitMemoryRequest(dut)
      dut.clock.step() // lower request accepted, but response is withheld

      requestLine(dut, 0x0100, 3)
      dut.clock.step()
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.transactionId.expect(3.U)
      dut.io.response.bits.readData.expect(0x22.U)
    }
  }

  it should "issue misses from different slices and route out-of-order responses" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2,
      banks = 2)) { dut =>
      initialize(dut)

      requestLine(dut, 0x0000, 2)
      waitMemoryRequest(dut)
      val lower0 = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()

      requestLine(dut, 0x0100, 5)
      waitMemoryRequest(dut)
      val lower1 = dut.io.memoryRequest.bits.transactionId.peek().litValue
      assert(lower1 != lower0)
      dut.clock.step()

      // Return slice 1 first even though slice 0 missed first.
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.transactionId.poke(lower1.U)
      dut.io.memoryResponse.bits.readData.poke(0x55.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      waitResponse(dut)
      dut.io.response.bits.transactionId.expect(5.U)
      dut.io.response.bits.readData.expect(0x55.U)
      dut.clock.step()

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.transactionId.poke(lower0.U)
      dut.io.memoryResponse.bits.readData.poke(0x22.U)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      waitResponse(dut)
      dut.io.response.bits.transactionId.expect(2.U)
      dut.io.response.bits.readData.expect(0x22.U)
    }
  }

  it should "merge loads to an outstanding line into one lower request" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2,
      banks = 2)) { dut =>
      initialize(dut)
      requestLine(dut, 0x3000, 1)
      waitMemoryRequest(dut)
      val lowerId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()

      // The slice is waiting for refill, but accepts another load to the same
      // line without issuing another lower-memory transaction.
      requestLine(dut, 0x3000, 6)
      dut.clock.step()
      dut.io.memoryRequest.valid.expect(false.B)

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.transactionId.poke(lowerId.U)
      dut.io.memoryResponse.bits.readData.poke(0xabcdef.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)

      waitResponse(dut)
      dut.io.response.bits.transactionId.expect(1.U)
      dut.io.response.bits.readData.expect(0xabcdef.U)
      dut.clock.step()
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.transactionId.expect(6.U)
      dut.io.response.bits.readData.expect(0xabcdef.U)
    }
  }

  it should "track every CU whose load merged into an MSHR" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2,
      banks = 2)) { dut =>
      initialize(dut)
      requestLine(dut, 0x5000, 0) // CU0
      waitMemoryRequest(dut)
      val lowerId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      requestLine(dut, 0x5000, 4) // CU1 merges
      dut.clock.step()

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.transactionId.poke(lowerId.U)
      dut.io.memoryResponse.bits.readData.poke(0x12.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      dut.clock.step(2) // consume both merged responses

      // CU0 writes its resident line. CU1 must be invalidated because its
      // merged load also allocated the line in its private L1.
      requestLine(dut, 0x5000, 1, isWrite = true, data = 0x34, mask = 1)
      waitInvalidation(dut, 1)
      dut.io.invalidate(0).valid.expect(false.B)
      dut.io.invalidate(1).bits.lineAddress.expect(0x5000.U)
    }
  }

  it should "issue different-line misses concurrently within one slice" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2,
      banks = 2, requestQueueDepth = 2)) { dut =>
      initialize(dut)
      requestLine(dut, 0x6000, 0)
      waitMemoryRequest(dut)
      val lower0 = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()

      // Same slice, different line: the second MSHR reaches lower memory
      // without waiting for the first refill.
      requestLine(dut, 0x6040, 1)
      waitMemoryRequest(dut)
      val lower1 = dut.io.memoryRequest.bits.transactionId.peek().litValue
      assert(lower1 != lower0)
      dut.io.memoryRequest.bits.address.expect(0x6040.U)
      dut.clock.step()

      // Refill the younger miss first and observe its response first.
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.transactionId.poke(lower1.U)
      dut.io.memoryResponse.bits.readData.poke(0x64.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      var cycles = 0
      while (!dut.io.response.valid.peek().litToBoolean && cycles < 8) {
        dut.clock.step(); cycles += 1
      }
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.transactionId.expect(1.U)
      dut.io.response.bits.readData.expect(0x64.U)
      dut.clock.step()

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.transactionId.poke(lower0.U)
      dut.io.memoryResponse.bits.readData.poke(0x60.U)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      cycles = 0
      while (!dut.io.response.valid.peek().litToBoolean && cycles < 8) {
        dut.clock.step(); cycles += 1
      }
      dut.io.response.bits.transactionId.expect(0.U)
      dut.io.response.bits.readData.expect(0x60.U)
      dut.clock.step()

      // Both out-of-order fills must now be resident in their recorded slots.
      requestLine(dut, 0x6040, 2)
      waitResponse(dut)
      dut.io.response.bits.readData.expect(0x64.U)
      dut.io.memoryRequest.valid.expect(false.B)
      dut.clock.step()
      requestLine(dut, 0x6000, 3)
      waitResponse(dut)
      dut.io.response.bits.readData.expect(0x60.U)
      dut.io.memoryRequest.valid.expect(false.B)
    }
  }

  it should "hold a miss that conflicts with an outstanding victim slot" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 1,
      banks = 2, requestQueueDepth = 2)) { dut =>
      initialize(dut)
      requestLine(dut, 0x7000, 0)
      waitMemoryRequest(dut)
      val lower0 = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()

      // Same bank and set, different tag, with only one way available.
      requestLine(dut, 0x7200, 1)
      dut.clock.step(4)
      dut.io.memoryRequest.valid.expect(false.B)

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.transactionId.poke(lower0.U)
      dut.io.memoryResponse.bits.readData.poke(0x70.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      waitResponse(dut)
      dut.clock.step()

      waitInvalidation(dut, 0)
      dut.io.invalidate(0).bits.lineAddress.expect(0x7000.U)
      dut.clock.step()
      dut.io.invalidateDone(0).valid.poke(true.B)
      dut.io.invalidateDone(0).bits.lineAddress.poke(0x7000.U)
      dut.clock.step(); dut.io.invalidateDone(0).valid.poke(false.B)
      waitMemoryRequest(dut)
      dut.io.memoryRequest.bits.address.expect(0x7200.U)
    }
  }

  it should "rotate to another way for concurrent misses in one set" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2,
      banks = 2, requestQueueDepth = 2)) { dut =>
      initialize(dut)
      requestLine(dut, 0x7400, 0)
      waitMemoryRequest(dut)
      val lower0 = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()

      // Same bank/set and another tag. way0 is reserved, so lookup must rotate
      // to the still-invalid way1 instead of waiting for lower0.
      requestLine(dut, 0x7600, 1)
      waitMemoryRequest(dut)
      val lower1 = dut.io.memoryRequest.bits.transactionId.peek().litValue
      assert(lower1 != lower0)
      dut.io.memoryRequest.bits.address.expect(0x7600.U)
    }
  }

  it should "issue different-line stores concurrently within one slice" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2,
      banks = 2)) { dut =>
      initialize(dut)
      dut.io.memoryRequest.ready.poke(false.B)
      requestLine(dut, 0x1000, 1, isWrite = true, data = 0x11, mask = 1)
      requestLine(dut, 0x1080, 2, isWrite = true, data = 0x22, mask = 1)

      dut.io.memoryRequest.ready.poke(true.B)
      var lower = Seq.empty[(BigInt, BigInt)]
      var cycles = 0
      while (lower.size < 2 && cycles < 20) {
        if (dut.io.memoryRequest.valid.peek().litToBoolean) {
          lower :+= (
            dut.io.memoryRequest.bits.address.peek().litValue,
            dut.io.memoryRequest.bits.transactionId.peek().litValue)
        }
        dut.clock.step(); cycles += 1
      }
      assert(lower.map(_._1).toSet == Set[BigInt](0x1000, 0x1080))

      dut.io.response.ready.poke(false.B)
      lower.reverse.foreach { case (_, id) =>
        dut.io.memoryResponse.bits.transactionId.poke(id.U)
        dut.io.memoryResponse.bits.fault.poke(false.B)
        dut.io.memoryResponse.valid.poke(true.B)
        dut.io.memoryResponse.ready.expect(true.B)
        dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      }
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.transactionId.expect(1.U)
      dut.io.response.ready.poke(true.B); dut.clock.step()
      dut.io.response.bits.transactionId.expect(2.U)
    }
  }
}
