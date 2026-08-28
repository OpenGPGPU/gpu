package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import opengpu.core.GpuComputeUnit
import opengpu.core.memory._
import opengpu.dispatch.{KernelCompletion, KernelLaunch}
import org.scalatest.flatspec.AnyFlatSpec

/** Composes the compute unit (the SIMT-kernel shader) and the word->line bridge
  * as the two clients that share one line-based physical memory, exposing both
  * clients' memory ports.  The harness backs those ports with a shared memory
  * model, proving the word-level graphics stages and the kernel operate on the
  * same address space / line format.
  */
class KernelSharedCompute(config: GpuConfig) extends Module {
  val io = IO(new Bundle {
    val launch = Flipped(Decoupled(new KernelLaunch(config)))
    val completion = Decoupled(new KernelCompletion)
    val word = Flipped(Decoupled(new OmMemoryRequest))
    val wordOut = Decoupled(new OmMemoryResponse)
    val cuMemRequest = Decoupled(new ComputeMemoryRequest(config))
    val cuMemResponse = Flipped(Decoupled(new ComputeMemoryResponse()))
    val bridgeMemRequest = Decoupled(new ComputeMemoryRequest(config))
    val bridgeMemResponse = Flipped(Decoupled(new ComputeMemoryResponse()))
  })

  private val cu = Module(new GpuComputeUnit(config))
  private val bridge = Module(new OmWordToLinePort(config))

  cu.io.kernel.valid := io.launch.valid
  cu.io.kernel.bits := io.launch.bits
  io.launch.ready := cu.io.kernel.ready
  io.completion <> cu.io.completion
  io.cuMemRequest <> cu.io.memoryRequest
  cu.io.memoryResponse <> io.cuMemResponse
  io.bridgeMemRequest <> bridge.io.memoryRequest
  bridge.io.memoryResponse <> io.bridgeMemResponse
  bridge.io.in <> io.word
  io.wordOut <> bridge.io.out

  cu.io.invalidateInstructionCache := false.B
  cu.io.instructionSatp := 0.U
  cu.io.instructionTlbFlush.valid := false.B
  cu.io.instructionTlbFlush.bits := 0.U.asTypeOf(cu.io.instructionTlbFlush.bits)
  cu.io.vectorSatp := 0.U
  cu.io.vectorTlbFlush.valid := false.B
  cu.io.vectorTlbFlush.bits := 0.U.asTypeOf(cu.io.vectorTlbFlush.bits)
  cu.io.fpu.ready := false.B
  cu.io.vector.ready := false.B
  cu.io.memory.ready := false.B
  cu.io.unsupportedSystem.ready := false.B
  cu.io.texSample.ready := false.B
  cu.io.texWriteback.valid := false.B
  cu.io.texWriteback.bits := 0.U.asTypeOf(cu.io.texWriteback.bits)
  cu.io.trap.ready := false.B
  cu.io.simtBranch.valid := false.B
  cu.io.simtBranch.bits := 0.U.asTypeOf(cu.io.simtBranch.bits)
  cu.io.l1Invalidate.valid := false.B
  cu.io.l1Invalidate.bits.lineAddress := 0.U
  cu.io.l1InvalidateDone.ready := true.B
  cu.io.globalAtomicRequest.ready := true.B
  cu.io.globalAtomicResponse.valid := false.B
  cu.io.globalAtomicResponse.bits := 0.U.asTypeOf(cu.io.globalAtomicResponse.bits)
}

class KernelSharedMemorySpec extends AnyFlatSpec {
  behavior of "GpuComputeUnit + OmWordToLinePort"

  private val lw = BigInt("0000a483", 16)   // lw x9, 0(x1): read a varying
  private val sw = BigInt("0090a223", 16)   // sw x9, 4(x1): write an output word
  private val cease = BigInt("30500073", 16)

  // Shared physical line memory model, keyed by line address.  Both the kernel
  // (compute unit) and the word client (bridge) read and write the same model.
  private class MemModel {
    val lines = scala.collection.mutable.LongMap[BigInt]()
    def putWord(line: Long, wordIdx: Int, value: BigInt): Unit = {
      val base = lines.getOrElse(line, BigInt(0))
      val mask = (BigInt(0xffffffffL) << (wordIdx * 32))
      lines(line) = (base & ~mask) | (value << (wordIdx * 32))
    }
    def applyWrite(addr: Long, writeData: BigInt, byteMask: BigInt): Unit = {
      var line = lines.getOrElse(addr, BigInt(0))
      for (b <- 0 until 64) {
        if (((byteMask >> b) & 1) != 0) {
          line = (line & ~(BigInt(0xff) << (b * 8))) |
            (((writeData >> (b * 8)) & 0xff) << (b * 8))
        }
      }
      lines(addr) = line
    }
    def readLine(addr: Long): BigInt = lines.getOrElse(addr, BigInt(0))
  }

  it should "let a word client and a SIMT kernel share one line memory" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelSharedCompute(config)) { dut =>
      val mem = new MemModel
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.launch.valid.poke(false.B)
      dut.io.completion.ready.poke(true.B)
      dut.io.word.valid.poke(false.B)
      dut.io.word.bits.write.poke(false.B)
      dut.io.word.bits.addr.poke(0.U)
      dut.io.word.bits.data.poke(0.U)
      dut.io.wordOut.ready.poke(true.B)
      dut.io.cuMemResponse.valid.poke(false.B)
      dut.io.cuMemResponse.bits.readData.poke(0.U)
      dut.io.cuMemResponse.bits.fault.poke(false.B)
      dut.io.cuMemResponse.bits.transactionId.poke(0.U)
      dut.io.bridgeMemResponse.valid.poke(false.B)
      dut.io.bridgeMemResponse.bits.readData.poke(0.U)
      dut.io.bridgeMemResponse.bits.fault.poke(false.B)
      dut.io.bridgeMemResponse.bits.transactionId.poke(0.U)
      dut.io.cuMemRequest.ready.poke(true.B)
      dut.io.bridgeMemRequest.ready.poke(true.B)

      // Single outstanding word operation on the bridge (write or read).
      def issueWord(write: Boolean, addr: Long, data: BigInt): BigInt = {
        dut.io.bridgeMemRequest.ready.poke(true.B)
        dut.io.word.valid.poke(true.B)
        dut.io.word.bits.write.poke(write.B)
        dut.io.word.bits.addr.poke(addr.U)
        dut.io.word.bits.data.poke(data.U(32.W))
        var g = 0
        while (!dut.io.word.ready.peek().litToBoolean && g < 40) {
          dut.clock.step(); g += 1
        }
        assert(dut.io.word.ready.peek().litToBoolean, "word request stalled")
        // The bridge mem request fires with the word request this cycle.
        val id = dut.io.bridgeMemRequest.bits.transactionId.peek().litValue
        val lineAddr = dut.io.bridgeMemRequest.bits.address.peek().litValue.toLong
        val respData =
          if (write) {
            val wd = dut.io.bridgeMemRequest.bits.writeData.peek().litValue
            val bm = dut.io.bridgeMemRequest.bits.byteMask.peek().litValue
            mem.applyWrite(lineAddr, wd, bm)
            BigInt(0)
          } else mem.readLine(lineAddr)
        dut.clock.step()  // word + mem request fire; slot commits
        dut.io.word.valid.poke(false.B)
        // Present the response this cycle; the bridge routes it to wordOut.
        dut.io.bridgeMemResponse.valid.poke(true.B)
        dut.io.bridgeMemResponse.bits.transactionId.poke(id.U)
        dut.io.bridgeMemResponse.bits.readData.poke(respData.U)
        dut.io.bridgeMemResponse.bits.fault.poke(false.B)
        assert(dut.io.wordOut.valid.peek().litToBoolean, "no word out")
        val out = if (write) BigInt(0) else
          dut.io.wordOut.bits.data.peek().litValue
        dut.clock.step()
        dut.io.bridgeMemResponse.valid.poke(false.B)
        out
      }

      // Seed the program and a varying into the shared line memory.
      issueWord(write = true, addr = 0x1000L, data = lw)
      issueWord(write = true, addr = 0x1004L, data = sw)
      issueWord(write = true, addr = 0x1008L, data = cease)
      issueWord(write = true, addr = 0x8000L, data = BigInt("cafe0001", 16))
      issueWord(write = true, addr = 0x8004L, data = BigInt(0))

      // Launch the kernel.
      dut.io.launch.valid.poke(true.B)
      dut.io.launch.bits.kernelPc.poke(0x1000.U)
      dut.io.launch.bits.kernargAddress.poke(0x8000.U)
      (0 until 3).foreach { i =>
        dut.io.launch.bits.gridSize(i).poke(1.U)
        dut.io.launch.bits.localSize(i).poke(Seq(3, 1, 1)(i).U)
      }
      dut.clock.step(); dut.io.launch.valid.poke(false.B)

      // Service the compute-unit memory with the shared model (deferred 1-cycle).
      var cuResp = false; var cuRespId = BigInt(0); var cuRespData = BigInt(0)
      var guard = 0
      while (!dut.io.completion.valid.peek().litToBoolean && guard < 300) {
        dut.io.cuMemResponse.valid.poke(cuResp)
        if (cuResp) {
          dut.io.cuMemResponse.bits.transactionId.poke(cuRespId.U)
          dut.io.cuMemResponse.bits.readData.poke(cuRespData.U)
          dut.io.cuMemResponse.bits.fault.poke(false.B)
        }
        val fired = dut.io.cuMemRequest.valid.peek().litToBoolean &&
          dut.io.cuMemRequest.ready.peek().litToBoolean
        if (fired) {
          val addr = dut.io.cuMemRequest.bits.address.peek().litValue.toLong
          val id = dut.io.cuMemRequest.bits.transactionId.peek().litValue
          val isWrite = dut.io.cuMemRequest.bits.isWrite.peek().litToBoolean
          if (isWrite) {
            val wd = dut.io.cuMemRequest.bits.writeData.peek().litValue
            val bm = dut.io.cuMemRequest.bits.byteMask.peek().litValue
            mem.applyWrite(addr, wd, bm)
            cuRespData = 0
          } else cuRespData = mem.readLine(addr)
          cuRespId = id; cuResp = true
          System.err.println(s"CU fired addr=$addr wr=$isWrite id=$id")
        } else cuResp = false
        dut.clock.step()
        guard += 1
      }
      assert(guard < 300, "kernel did not complete within a bounded time")
      dut.io.completion.bits.success.expect(true.B)

      // Read the output word the kernel wrote, through the word->line bridge.
      val out = issueWord(write = false, addr = 0x8004L, data = 0)
      assert(out == BigInt("cafe0001", 16),
        s"kernel output word must equal the loaded varying, got 0x${out.toString(16)}")
    }
  }
}
