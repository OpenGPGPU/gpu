package opengpu.system

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import opengpu.command.GpuCommandOpcode
import opengpu.core.memory.AtomicMemoryOp
import org.scalatest.flatspec.AnyFlatSpec

class GpuSystemSpec extends AnyFlatSpec {
  behavior of "GpuSystem"

  private def initialize(dut: GpuSystem, numComputeUnits: Int): Unit = {
    dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
    dut.io.command.valid.poke(false.B)
    dut.io.command.bits.poke(0.U.asTypeOf(dut.io.command.bits))
    dut.io.commandCompletion.ready.poke(true.B)
    dut.io.copyDescriptor.valid.poke(false.B)
    dut.io.copyDescriptor.bits.poke(
      0.U.asTypeOf(dut.io.copyDescriptor.bits))
    dut.io.copyCompletion.ready.poke(true.B)
    dut.io.fillDescriptor.valid.poke(false.B)
    dut.io.fillDescriptor.bits.poke(
      0.U.asTypeOf(dut.io.fillDescriptor.bits))
    dut.io.fillCompletion.ready.poke(true.B)
    dut.io.stridedCopyDescriptor.valid.poke(false.B)
    dut.io.stridedCopyDescriptor.bits.poke(
      0.U.asTypeOf(dut.io.stridedCopyDescriptor.bits))
    dut.io.stridedCopyCompletion.ready.poke(true.B)
    dut.io.gpuCommand.valid.poke(false.B)
    dut.io.gpuCommand.bits.poke(0.U.asTypeOf(dut.io.gpuCommand.bits))
    dut.io.gpuCompletion.ready.poke(true.B)
    dut.io.graphicsHostRequest.valid.poke(false.B)
    dut.io.graphicsHostRequest.bits.poke(
      0.U.asTypeOf(dut.io.graphicsHostRequest.bits))
    dut.io.graphicsHostResponse.ready.poke(true.B)
    dut.io.graphicsShaderRequest.valid.poke(false.B)
    dut.io.graphicsShaderRequest.bits.poke(
      0.U.asTypeOf(dut.io.graphicsShaderRequest.bits))
    dut.io.graphicsShaderResponse.ready.poke(true.B)
    dut.io.graphicsShaderL1Invalidate.ready.poke(true.B)
    dut.io.graphicsShaderL1InvalidateDone.valid.poke(false.B)
    dut.io.graphicsShaderL1InvalidateDone.bits.poke(
      0.U.asTypeOf(dut.io.graphicsShaderL1InvalidateDone.bits))
    dut.io.graphicsShaderAtomicRequest.valid.poke(false.B)
    dut.io.graphicsShaderAtomicRequest.bits.poke(
      0.U.asTypeOf(dut.io.graphicsShaderAtomicRequest.bits))
    dut.io.graphicsShaderAtomicResponse.ready.poke(true.B)
    dut.io.memoryRequest.ready.poke(true.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.poke(
      0.U.asTypeOf(dut.io.memoryResponse.bits))
    dut.io.clearPerformanceCounters.poke(false.B)
    dut.io.invalidateInstructionCache.poke(false.B)
    dut.io.instructionSatp.poke(0.U)
    dut.io.instructionTlbFlush.valid.poke(false.B)
    dut.io.instructionTlbFlush.bits.poke(
      0.U.asTypeOf(dut.io.instructionTlbFlush.bits))
    dut.io.vectorSatp.poke(0.U)
    dut.io.vectorTlbFlush.valid.poke(false.B)
    dut.io.vectorTlbFlush.bits.poke(
      0.U.asTypeOf(dut.io.vectorTlbFlush.bits))
    (0 until numComputeUnits).foreach { cu =>
      dut.io.fpu(cu).ready.poke(false.B)
      dut.io.vector(cu).ready.poke(false.B)
      dut.io.scalarMemory(cu).ready.poke(false.B)
      dut.io.unsupportedSystem(cu).ready.poke(false.B)
      dut.io.trap(cu).ready.poke(false.B)
      dut.io.simtBranch(cu).valid.poke(false.B)
      dut.io.simtBranch(cu).bits.poke(
        0.U.asTypeOf(dut.io.simtBranch(cu).bits))
    }
  }

  it should "elaborate two compute units behind tagged dispatch and shared memory" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new GpuSystem(config, numComputeUnits = 2)) { dut =>
      initialize(dut, 2)

      dut.io.busyComputeUnits.expect(0.U)
      dut.io.command.ready.expect(true.B)
      dut.io.commandProcessorBusy.expect(false.B)
      dut.io.queuedCommands.expect(0.U)
      dut.io.inFlightCommands.expect(0.U)
      dut.io.copyEngineBusy.expect(false.B)
      dut.io.fillEngineBusy.expect(false.B)
      dut.io.stridedCopyEngineBusy.expect(false.B)
      dut.io.activeWarps(0).expect(0.U)
      dut.io.activeWarps(1).expect(0.U)
    }
  }

  it should "fill a cache line through the shared L2" in {
    val config = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuSystem(config, numComputeUnits = 2)) { dut =>
      initialize(dut, 2)
      val pattern = BigInt("a5a55a5a", 16)
      val line = BigInt(List.fill(16)("a5a55a5a").mkString, 16)
      dut.io.fillDescriptor.bits.descriptorId.poke(6.U)
      dut.io.fillDescriptor.bits.destinationAddress.poke(0x3000.U)
      dut.io.fillDescriptor.bits.bytes.poke(64.U)
      dut.io.fillDescriptor.bits.pattern.poke(pattern.U)
      dut.io.fillDescriptor.valid.poke(true.B)
      dut.clock.step(); dut.io.fillDescriptor.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 30) {
        dut.clock.step(); cycles += 1
      }
      dut.io.memoryRequest.bits.address.expect(0x3000.U)
      dut.io.memoryRequest.bits.isWrite.expect(true.B)
      dut.io.memoryRequest.bits.writeData.expect(line.U)
      val id = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      dut.io.memoryResponse.bits.transactionId.poke(id.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.valid.poke(true.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)

      cycles = 0
      while (!dut.io.fillCompletion.valid.peek().litToBoolean && cycles < 20) {
        dut.clock.step(); cycles += 1
      }
      dut.io.fillCompletion.valid.expect(true.B)
      dut.io.fillCompletion.bits.descriptorId.expect(6.U)
      dut.io.fillCompletion.bits.bytesFilled.expect(64.U)
    }
  }

  it should "route graphics-host line traffic through the shared L2" in {
    val config = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuSystem(config, numComputeUnits = 2)) { dut =>
      initialize(dut, 2)
      val line = BigInt("0123456789abcdef", 16)

      dut.io.graphicsHostRequest.bits.address.poke(0x5000.U)
      dut.io.graphicsHostRequest.bits.writeData.poke(0.U)
      dut.io.graphicsHostRequest.bits.byteMask.poke(0.U)
      dut.io.graphicsHostRequest.bits.isWrite.poke(false.B)
      dut.io.graphicsHostRequest.bits.sizeLog2.poke(6.U)
      dut.io.graphicsHostRequest.bits.cacheClient.poke(false.B)
      dut.io.graphicsHostRequest.bits.cacheResident.poke(false.B)
      dut.io.graphicsHostRequest.bits.transactionId.poke(5.U)
      dut.io.graphicsHostRequest.valid.poke(true.B)
      dut.clock.step()
      dut.io.graphicsHostRequest.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 30) {
        dut.clock.step(); cycles += 1
      }
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.address.expect(0x5000.U)
      val lowerId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()

      dut.io.memoryResponse.bits.transactionId.poke(lowerId.U)
      dut.io.memoryResponse.bits.readData.poke(line.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.valid.poke(true.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)

      cycles = 0
      while (!dut.io.graphicsHostResponse.valid.peek().litToBoolean &&
          cycles < 30) {
        dut.clock.step(); cycles += 1
      }
      dut.io.graphicsHostResponse.valid.expect(true.B)
      dut.io.graphicsHostResponse.bits.transactionId.expect(5.U)
      dut.io.graphicsHostResponse.bits.readData.expect(line.U)
      dut.io.graphicsHostResponse.bits.fault.expect(false.B)
    }
  }

  it should "coherently attach the graphics shader to the shared L2" in {
    val config = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuSystem(config, numComputeUnits = 2)) { dut =>
      initialize(dut, 2)
      val address = 0x5800
      val line = BigInt("cafebabedeadbeef", 16)

      dut.io.graphicsShaderRequest.bits.poke(
        0.U.asTypeOf(dut.io.graphicsShaderRequest.bits))
      dut.io.graphicsShaderRequest.bits.address.poke(address.U)
      dut.io.graphicsShaderRequest.bits.sizeLog2.poke(6.U)
      dut.io.graphicsShaderRequest.bits.cacheClient.poke(true.B)
      dut.io.graphicsShaderRequest.bits.transactionId.poke(3.U)
      dut.io.graphicsShaderRequest.valid.poke(true.B)
      dut.clock.step()
      dut.io.graphicsShaderRequest.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 40) {
        dut.clock.step(); cycles += 1
      }
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.address.expect(address.U)
      val readId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      dut.io.memoryResponse.bits.transactionId.poke(readId.U)
      dut.io.memoryResponse.bits.readData.poke(line.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.valid.poke(true.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)

      cycles = 0
      while (!dut.io.graphicsShaderResponse.valid.peek().litToBoolean &&
          cycles < 40) {
        dut.clock.step(); cycles += 1
      }
      dut.io.graphicsShaderResponse.valid.expect(true.B)
      dut.io.graphicsShaderResponse.bits.transactionId.expect(3.U)
      dut.io.graphicsShaderResponse.bits.readData.expect(line.U)
      dut.clock.step()

      // A foreign graphics word/line client writing the cached line must
      // invalidate the graphics shader's private copy before reaching memory.
      dut.io.graphicsHostRequest.bits.poke(
        0.U.asTypeOf(dut.io.graphicsHostRequest.bits))
      dut.io.graphicsHostRequest.bits.address.poke(address.U)
      dut.io.graphicsHostRequest.bits.writeData.poke("h11223344".U)
      dut.io.graphicsHostRequest.bits.byteMask.poke(0xf.U)
      dut.io.graphicsHostRequest.bits.isWrite.poke(true.B)
      dut.io.graphicsHostRequest.bits.sizeLog2.poke(6.U)
      dut.io.graphicsHostRequest.bits.transactionId.poke(7.U)
      dut.io.graphicsHostRequest.valid.poke(true.B)
      while (!dut.io.graphicsHostRequest.ready.peek().litToBoolean)
        dut.clock.step()
      dut.clock.step()
      dut.io.graphicsHostRequest.valid.poke(false.B)

      cycles = 0
      while (!dut.io.graphicsShaderL1Invalidate.valid.peek().litToBoolean &&
          cycles < 40) {
        dut.io.memoryRequest.valid.expect(false.B)
        dut.clock.step(); cycles += 1
      }
      dut.io.graphicsShaderL1Invalidate.valid.expect(true.B)
      dut.io.graphicsShaderL1Invalidate.bits.lineAddress.expect(address.U)
      dut.clock.step()
      dut.io.graphicsShaderL1InvalidateDone.bits.lineAddress.poke(address.U)
      dut.io.graphicsShaderL1InvalidateDone.valid.poke(true.B)
      dut.clock.step()
      dut.io.graphicsShaderL1InvalidateDone.valid.poke(false.B)

      cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 40) {
        dut.clock.step(); cycles += 1
      }
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.address.expect(address.U)
      dut.io.memoryRequest.bits.isWrite.expect(true.B)
    }
  }

  it should "route graphics-shader global atomics through the shared L2" in {
    val config = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuSystem(config, numComputeUnits = 2)) { dut =>
      initialize(dut, 2)
      dut.io.graphicsShaderAtomicRequest.bits.warpId.poke(1.U)
      dut.io.graphicsShaderAtomicRequest.bits.address.poke(0x6004.U)
      dut.io.graphicsShaderAtomicRequest.bits.operand.poke(3.U)
      dut.io.graphicsShaderAtomicRequest.bits.operation.poke(AtomicMemoryOp.add)
      dut.io.graphicsShaderAtomicRequest.valid.poke(true.B)
      while (!dut.io.graphicsShaderAtomicRequest.ready.peek().litToBoolean)
        dut.clock.step()
      dut.clock.step()
      dut.io.graphicsShaderAtomicRequest.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 40) {
        dut.clock.step(); cycles += 1
      }
      dut.io.memoryRequest.bits.isWrite.expect(false.B)
      dut.io.memoryRequest.bits.address.expect(0x6000.U)
      val readId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      dut.io.memoryResponse.bits.transactionId.poke(readId.U)
      dut.io.memoryResponse.bits.readData.poke((BigInt(5) << 32).U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.valid.poke(true.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)

      cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 40) {
        dut.clock.step(); cycles += 1
      }
      dut.io.memoryRequest.bits.isWrite.expect(true.B)
      dut.io.memoryRequest.bits.address.expect(0x6000.U)
      dut.io.memoryRequest.bits.byteMask.expect(0xf0.U)
      dut.io.memoryRequest.bits.writeData.expect((BigInt(8) << 32).U)
      val writeId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      dut.io.memoryResponse.bits.transactionId.poke(writeId.U)
      dut.io.memoryResponse.bits.readData.poke(0.U)
      dut.io.memoryResponse.valid.poke(true.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)

      cycles = 0
      while (!dut.io.graphicsShaderAtomicResponse.valid.peek().litToBoolean &&
          cycles < 40) {
        dut.clock.step(); cycles += 1
      }
      dut.io.graphicsShaderAtomicResponse.valid.expect(true.B)
      dut.io.graphicsShaderAtomicResponse.bits.warpId.expect(1.U)
      dut.io.graphicsShaderAtomicResponse.bits.oldValue.expect(5.U)
      dut.io.graphicsShaderAtomicResponse.bits.fault.expect(false.B)
    }
  }

  it should "copy strided rows through the shared L2" in {
    val config = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuSystem(config, numComputeUnits = 2)) { dut =>
      initialize(dut, 2)
      val rows = Seq(
        (BigInt(0x1000), BigInt(0x4000), BigInt("1111222233334444", 16)),
        (BigInt(0x1100), BigInt(0x4180), BigInt("aaaabbbbccccdddd", 16)))
      dut.io.stridedCopyDescriptor.bits.descriptorId.poke(11.U)
      dut.io.stridedCopyDescriptor.bits.sourceAddress.poke(0x1000.U)
      dut.io.stridedCopyDescriptor.bits.destinationAddress.poke(0x4000.U)
      dut.io.stridedCopyDescriptor.bits.widthBytes.poke(64.U)
      dut.io.stridedCopyDescriptor.bits.height.poke(2.U)
      dut.io.stridedCopyDescriptor.bits.sourceStride.poke(0x100.U)
      dut.io.stridedCopyDescriptor.bits.destinationStride.poke(0x180.U)
      dut.io.stridedCopyDescriptor.valid.poke(true.B)
      dut.clock.step(); dut.io.stridedCopyDescriptor.valid.poke(false.B)

      rows.foreach { case (source, destination, data) =>
        var cycles = 0
        while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 30) {
          dut.clock.step(); cycles += 1
        }
        dut.io.memoryRequest.valid.expect(true.B)
        dut.io.memoryRequest.bits.address.expect(source.U)
        dut.io.memoryRequest.bits.isWrite.expect(false.B)
        val readId = dut.io.memoryRequest.bits.transactionId.peek().litValue
        dut.clock.step()
        dut.io.memoryResponse.bits.transactionId.poke(readId.U)
        dut.io.memoryResponse.bits.readData.poke(data.U)
        dut.io.memoryResponse.bits.fault.poke(false.B)
        dut.io.memoryResponse.valid.poke(true.B)
        dut.io.memoryResponse.ready.expect(true.B)
        dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)

        cycles = 0
        while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 30) {
          dut.clock.step(); cycles += 1
        }
        dut.io.memoryRequest.valid.expect(true.B)
        dut.io.memoryRequest.bits.address.expect(destination.U)
        dut.io.memoryRequest.bits.isWrite.expect(true.B)
        dut.io.memoryRequest.bits.writeData.expect(data.U)
        val writeId = dut.io.memoryRequest.bits.transactionId.peek().litValue
        dut.clock.step()
        dut.io.memoryResponse.bits.transactionId.poke(writeId.U)
        dut.io.memoryResponse.bits.readData.poke(0.U)
        dut.io.memoryResponse.bits.fault.poke(false.B)
        dut.io.memoryResponse.valid.poke(true.B)
        dut.io.memoryResponse.ready.expect(true.B)
        dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      }

      var cycles = 0
      while (!dut.io.stridedCopyCompletion.valid.peek().litToBoolean &&
          cycles < 30) {
        dut.clock.step(); cycles += 1
      }
      dut.io.stridedCopyCompletion.valid.expect(true.B)
      dut.io.stridedCopyCompletion.bits.descriptorId.expect(11.U)
      dut.io.stridedCopyCompletion.bits.success.expect(true.B)
      dut.io.stridedCopyCompletion.bits.bytesCopied.expect(128.U)
    }
  }

  it should "copy a cache line through the shared L2 and lower-memory port" in {
    val config = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuSystem(config, numComputeUnits = 2)) { dut =>
      initialize(dut, 2)
      val line = BigInt("0123456789abcdef", 16)
      dut.io.copyDescriptor.bits.descriptorId.poke(7.U)
      dut.io.copyDescriptor.bits.sourceAddress.poke(0x1000.U)
      dut.io.copyDescriptor.bits.destinationAddress.poke(0x2000.U)
      dut.io.copyDescriptor.bits.bytes.poke(64.U)
      dut.io.copyDescriptor.valid.poke(true.B)
      dut.io.copyDescriptor.ready.expect(true.B)
      dut.clock.step(); dut.io.copyDescriptor.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 20) {
        dut.clock.step(); cycles += 1
      }
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.address.expect(0x1000.U)
      dut.io.memoryRequest.bits.isWrite.expect(false.B)
      val readId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      dut.io.memoryResponse.bits.transactionId.poke(readId.U)
      dut.io.memoryResponse.bits.readData.poke(line.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.valid.poke(true.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)

      cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 30) {
        dut.clock.step(); cycles += 1
      }
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.address.expect(0x2000.U)
      dut.io.memoryRequest.bits.isWrite.expect(true.B)
      dut.io.memoryRequest.bits.writeData.expect(line.U)
      val writeId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      dut.io.memoryResponse.bits.transactionId.poke(writeId.U)
      dut.io.memoryResponse.bits.readData.poke(0.U)
      dut.io.memoryResponse.valid.poke(true.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)

      cycles = 0
      while (!dut.io.copyCompletion.valid.peek().litToBoolean && cycles < 20) {
        dut.clock.step(); cycles += 1
      }
      dut.io.copyCompletion.valid.expect(true.B)
      dut.io.copyCompletion.bits.descriptorId.expect(7.U)
      dut.io.copyCompletion.bits.success.expect(true.B)
      dut.io.copyCompletion.bits.bytesCopied.expect(64.U)
    }
  }

  it should "copy two lines through separate L2 banks with out-of-order responses" in {
    val config = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuSystem(config, numComputeUnits = 2)) { dut =>
      initialize(dut, 2)
      val sourceLines = Map[BigInt, BigInt](
        BigInt(0x1000) -> BigInt("1111222233334444", 16),
        BigInt(0x1040) -> BigInt("aaaabbbbccccdddd", 16))
      dut.io.copyDescriptor.bits.descriptorId.poke(9.U)
      dut.io.copyDescriptor.bits.sourceAddress.poke(0x1000.U)
      dut.io.copyDescriptor.bits.destinationAddress.poke(0x2000.U)
      dut.io.copyDescriptor.bits.bytes.poke(128.U)
      dut.io.copyDescriptor.valid.poke(true.B)
      dut.clock.step(); dut.io.copyDescriptor.valid.poke(false.B)

      var reads = Seq.empty[(BigInt, BigInt)]
      var cycles = 0
      while (reads.size < 2 && cycles < 40) {
        if (dut.io.memoryRequest.valid.peek().litToBoolean) {
          dut.io.memoryRequest.bits.isWrite.expect(false.B)
          reads :+= (
            dut.io.memoryRequest.bits.address.peek().litValue,
            dut.io.memoryRequest.bits.transactionId.peek().litValue)
        }
        dut.clock.step(); cycles += 1
      }
      assert(reads.map(_._1).toSet == Set[BigInt](0x1000, 0x1040))

      // Hold outgoing requests while both refill responses are returned. This
      // makes the subsequent write requests observable independently.
      dut.io.memoryRequest.ready.poke(false.B)
      reads.reverse.foreach { case (address, id) =>
        dut.io.memoryResponse.bits.transactionId.poke(id.U)
        dut.io.memoryResponse.bits.readData.poke(sourceLines(address).U)
        dut.io.memoryResponse.bits.fault.poke(false.B)
        dut.io.memoryResponse.valid.poke(true.B)
        dut.io.memoryResponse.ready.expect(true.B)
        dut.clock.step()
        dut.io.memoryResponse.valid.poke(false.B)
      }

      dut.io.memoryRequest.ready.poke(true.B)
      var writes = Seq.empty[(BigInt, BigInt, BigInt)]
      cycles = 0
      while (writes.size < 2 && cycles < 40) {
        if (dut.io.memoryRequest.valid.peek().litToBoolean) {
          dut.io.memoryRequest.bits.isWrite.expect(true.B)
          writes :+= (
            dut.io.memoryRequest.bits.address.peek().litValue,
            dut.io.memoryRequest.bits.writeData.peek().litValue,
            dut.io.memoryRequest.bits.transactionId.peek().litValue)
        }
        dut.clock.step()
        cycles += 1
      }
      assert(writes.map(_._1).toSet == Set[BigInt](0x2000, 0x2040))
      writes.foreach { case (address, data, _) =>
        assert(data == sourceLines(address - 0x1000))
      }

      writes.reverse.foreach { case (_, _, id) =>
        dut.io.memoryResponse.bits.transactionId.poke(id.U)
        dut.io.memoryResponse.bits.readData.poke(0.U)
        dut.io.memoryResponse.bits.fault.poke(false.B)
        dut.io.memoryResponse.valid.poke(true.B)
        dut.io.memoryResponse.ready.expect(true.B)
        dut.clock.step()
        dut.io.memoryResponse.valid.poke(false.B)
      }

      cycles = 0
      while (!dut.io.copyCompletion.valid.peek().litToBoolean && cycles < 30) {
        dut.clock.step(); cycles += 1
      }
      dut.io.copyCompletion.valid.expect(true.B)
      dut.io.copyCompletion.bits.descriptorId.expect(9.U)
      dut.io.copyCompletion.bits.success.expect(true.B)
      dut.io.copyCompletion.bits.bytesCopied.expect(128.U)
    }
  }

  it should "execute a fill through the unified command interface" in {
    val config = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuSystem(config, numComputeUnits = 2,
      enableUnifiedCommands = true)) { dut =>
      initialize(dut, 2)
      val pattern = BigInt("13579bdf", 16)
      val line = BigInt(List.fill(16)("13579bdf").mkString, 16)
      dut.io.command.ready.expect(false.B)
      dut.io.fillDescriptor.ready.expect(false.B)
      dut.io.gpuCommand.bits.commandId.poke(12.U)
      dut.io.gpuCommand.bits.opcode.poke(GpuCommandOpcode.fill)
      dut.io.gpuCommand.bits.destinationAddress.poke(0x5000.U)
      dut.io.gpuCommand.bits.bytes.poke(64.U)
      dut.io.gpuCommand.bits.pattern.poke(pattern.U)
      dut.io.gpuCommand.valid.poke(true.B)
      dut.io.gpuCommand.ready.expect(true.B)
      dut.clock.step(); dut.io.gpuCommand.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 30) {
        dut.clock.step(); cycles += 1
      }
      dut.io.memoryRequest.bits.address.expect(0x5000.U)
      dut.io.memoryRequest.bits.isWrite.expect(true.B)
      dut.io.memoryRequest.bits.writeData.expect(line.U)
      val transactionId =
        dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      dut.io.memoryResponse.bits.transactionId.poke(transactionId.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.ready.expect(true.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)

      cycles = 0
      while (!dut.io.gpuCompletion.valid.peek().litToBoolean && cycles < 20) {
        dut.clock.step(); cycles += 1
      }
      dut.io.gpuCompletion.valid.expect(true.B)
      dut.io.gpuCompletion.bits.commandId.expect(12.U)
      dut.io.gpuCompletion.bits.opcode.expect(GpuCommandOpcode.fill)
      dut.io.gpuCompletion.bits.success.expect(true.B)
      dut.io.gpuCompletion.bits.bytesProcessed.expect(64.U)
      dut.io.performance.lowerWriteRequests.expect(1.U)
      dut.clock.step()
      dut.io.performance.dmaBytesCompleted.expect(64.U)
      dut.io.clearPerformanceCounters.poke(true.B)
      dut.clock.step(); dut.io.clearPerformanceCounters.poke(false.B)
      dut.io.performance.cycles.expect(0.U)
      dut.io.performance.lowerWriteRequests.expect(0.U)
      dut.io.performance.dmaBytesCompleted.expect(0.U)
    }
  }
}
