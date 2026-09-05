package opengpu.core

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.register.{
  FpuRegisterWrite,
  ScalarRegisterWrite,
  VectorRegisterWrite
}
import opengpu.core.backend.FpuFlags
import opengpu.core.execute.control.SimtPath
import opengpu.core.memory.{InstructionCache, InstructionLineRequest, InstructionLineResponse}
import opengpu.core.system.{WarpSystemControl, WorkgroupBarrierController}
import opengpu.dispatch.{KernelCompletion, KernelLaunch, SingleCuKernelController}

/** FlashSim-sized closed GPU: one compute unit from kernel launch through
  * scalar, vector, FPU, and shared memory, with a small instruction cache.
  *
  * This is the synthesizable programmable GPU without the host AXI shell,
  * L2, or DMA engines. IMEM refill stays at 8-byte lines so the harness can
  * reuse the FrontendScalarFpu stimulus. Vector/data-cache misses are unused
  * by the register-only kernel and stay tied off inside the wrapper.
  */
class Gpu(
  config: GpuConfig,
  sets: Int,
  ways: Int,
  lineBytes: Int,
  missEntries: Int
) extends Module {
  val io = IO(new Bundle {
    val kernel = Flipped(Decoupled(new KernelLaunch(config)))
    val completion = Decoupled(new KernelCompletion)
    val fpuInitialize = Flipped(Decoupled(new FpuRegisterWrite(config)))
    val finish = Flipped(Valid(UInt(config.warpIdWidth.W)))
    val committedWriteback = Valid(new ScalarRegisterWrite(config))
    val committedVectorWriteback = Valid(new VectorRegisterWrite(config))
    val committedFpuWriteback = Valid(new FpuRegisterWrite(config))
    val committedFpuFlags = Valid(new FpuFlags(config))
    val active = Output(UInt(config.warps.W))
    val blocked = Output(UInt(config.warps.W))
    val barrierWaiting = Output(UInt(config.warps.W))
    val lowerRequest =
      Decoupled(new InstructionLineRequest(config, lineBytes, missEntries))
    val lowerResponse = Flipped(
      Decoupled(new InstructionLineResponse(lineBytes, missEntries)))
    val invalidate = Input(Bool())
  })

  private val controller = Module(new SingleCuKernelController(config))
  private val core = Module(
    new GpuCore(
      config,
      useBlackBoxes = false,
      enableFpuBackend = true,
      vectorCacheSets = 8,
      vectorCacheWays = 2))
  private val system = Module(new WarpSystemControl(config))
  private val barrier = Module(new WorkgroupBarrierController(config))
  private val icache = Module(
    new InstructionCache(config, sets, ways, lineBytes, missEntries))

  controller.io.kernel <> io.kernel
  io.completion <> controller.io.completion
  controller.io.activeWarps := core.io.active
  core.io.scalarInitialize <> controller.io.scalarInitialize
  core.io.vectorInitialize <> controller.io.vectorInitialize
  core.io.launch <> controller.io.launch
  core.io.fpuInitialize <> io.fpuInitialize

  system.io.in <> core.io.system
  system.io.texSample.ready := true.B
  system.io.unsupported.ready := true.B
  core.io.texCommit.valid := false.B
  core.io.texCommit.bits := 0.U.asTypeOf(core.io.texCommit.bits)
  core.io.vectorTexSample.ready := true.B
  core.io.vectorTexCommit.valid := false.B
  core.io.vectorTexCommit.bits := 0.U.asTypeOf(core.io.vectorTexCommit.bits)
  core.io.restore <> system.io.restore
  barrier.io.arrive <> system.io.barrier
  barrier.io.residentWarps := controller.io.residentWarps
  barrier.io.dispatchComplete := controller.io.workgroupDispatchComplete
  barrier.io.memoryIdle := core.io.sharedMemoryIdle
  private val resumeArbiter = Module(new RRArbiter(new SimtPath(config), 2))
  resumeArbiter.io.in(0) <> system.io.resume
  resumeArbiter.io.in(1) <> barrier.io.release
  core.io.faultResume <> resumeArbiter.io.out

  private val finish = Wire(Valid(UInt(config.warpIdWidth.W)))
  finish.valid := system.io.finish.valid || io.finish.valid
  finish.bits := Mux(system.io.finish.valid, system.io.finish.bits, io.finish.bits)
  core.io.finish := finish
  controller.io.finish := finish

  icache.io.fetch <> core.io.fetchRequest
  core.io.fetchResponse <> icache.io.response
  io.lowerRequest <> icache.io.lowerRequest
  icache.io.lowerResponse <> io.lowerResponse
  icache.io.invalidate := io.invalidate

  core.io.vectorSatp := 0.U
  core.io.vectorTlbFlush.valid := false.B
  core.io.vectorTlbFlush.bits := 0.U.asTypeOf(core.io.vectorTlbFlush.bits)
  core.io.vectorMemoryRequest.ready := true.B
  core.io.vectorMemoryResponse.valid := false.B
  core.io.vectorMemoryResponse.bits :=
    0.U.asTypeOf(core.io.vectorMemoryResponse.bits)
  core.io.vectorPageTableRequest.ready := true.B
  core.io.vectorPageTableResponse.valid := false.B
  core.io.vectorPageTableResponse.bits :=
    0.U.asTypeOf(core.io.vectorPageTableResponse.bits)
  core.io.l1Invalidate.valid := false.B
  core.io.l1Invalidate.bits := 0.U.asTypeOf(core.io.l1Invalidate.bits)
  core.io.l1InvalidateDone.ready := true.B
  core.io.globalAtomicRequest.ready := true.B
  core.io.globalAtomicResponse.valid := false.B
  core.io.globalAtomicResponse.bits :=
    0.U.asTypeOf(core.io.globalAtomicResponse.bits)
  core.io.fpu.ready := true.B
  core.io.vector.ready := true.B
  core.io.memory.ready := true.B
  core.io.trap.ready := true.B
  core.io.simtBranch.valid := false.B
  core.io.simtBranch.bits := 0.U.asTypeOf(core.io.simtBranch.bits)

  io.committedWriteback := core.io.committedWriteback
  io.committedVectorWriteback := core.io.committedVectorWriteback
  io.committedFpuWriteback := core.io.committedFpuWriteback
  io.committedFpuFlags := core.io.committedFpuFlags
  io.active := core.io.active
  io.blocked := core.io.blocked
  io.barrierWaiting := barrier.io.waiting
}
