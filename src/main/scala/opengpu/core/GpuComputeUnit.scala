package opengpu.core

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.{FpuFlags}
import opengpu.core.backend.issue.{ScalarIssuedInstruction, VectorIssuedInstruction}
import opengpu.core.backend.register.{FpuRegisterWrite, ScalarRegisterWrite, VectorRegisterWrite}
import opengpu.core.execute.control.{SimtBranchRequest, SimtPath}
import opengpu.core.frontend.{InstructionFetchRequest, InstructionFetchResponse}
import opengpu.core.frontend.decode.FpuDecodeResponse
import opengpu.core.memory._
import opengpu.core.system.{WarpSystemControl, WorkgroupBarrierController}
import opengpu.core.trap.CoreTrapEvent
import opengpu.dispatch.{KernelCompletion, KernelLaunch, SingleCuKernelController}
import opengpu.core.backend.writeback.ScalarCommitRequest

/** One usable compute unit: kernel dispatch, context initialization, core
  * execution, and instruction-driven warp/kernel completion.
  */
class GpuComputeUnit(
  config: GpuConfig = GpuConfig(),
  useBlackBoxes: Boolean = false,
  enableFpuBackend: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val kernel = Flipped(Decoupled(new KernelLaunch(config)))
    val completion = Decoupled(new KernelCompletion)

    val memoryRequest = Decoupled(new ComputeMemoryRequest(config))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse()))
    val invalidateInstructionCache = Input(Bool())
    val instructionSatp = Input(UInt(32.W))
    val instructionTlbFlush = Flipped(Valid(new VectorTlbFlush(config)))
    val vectorSatp = Input(UInt(32.W))
    val vectorTlbFlush = Flipped(Valid(new VectorTlbFlush(config)))

    val fpu = Decoupled(new FpuDecodeResponse(config))
    val vector = Decoupled(new VectorIssuedInstruction(config))
    val memory = Decoupled(new ScalarIssuedInstruction(config))
    val unsupportedSystem = Decoupled(new ScalarIssuedInstruction(config))
    val trap = Decoupled(new CoreTrapEvent(config))
    val simtBranch = Flipped(Decoupled(new SimtBranchRequest(config)))

    val committedWriteback = Valid(new ScalarRegisterWrite(config))
    val committedVectorWriteback = Valid(new VectorRegisterWrite(config))
    val committedFpuWriteback = Valid(new FpuRegisterWrite(config))
    val committedFpuFlags = Valid(new FpuFlags(config))
    val committedFpuIntegerWriteback = Valid(new ScalarRegisterWrite(config))
    val active = Output(UInt(config.warps.W))
    val blocked = Output(UInt(config.warps.W))
    val barrierWaiting = Output(UInt(config.warps.W))
    val l1Invalidate = Flipped(Decoupled(new CacheLineInvalidate(config)))
    val l1InvalidateDone = Decoupled(new CacheLineInvalidate(config))
    val globalAtomicRequest = Decoupled(new SharedAtomicRequest(config))
    val globalAtomicResponse = Flipped(Decoupled(new SharedAtomicResponse(config)))
    /** tex.sample execute-side handoff + writeback (graphics TexSampleUnit). */
    val texSample = Decoupled(new ScalarIssuedInstruction(config))
    val texWriteback = Flipped(Decoupled(new ScalarCommitRequest(config)))
  })

  private val controller = Module(new SingleCuKernelController(config))
  private val core = Module(new GpuCore(config, useBlackBoxes, enableFpuBackend))
  private val system = Module(new WarpSystemControl(config))
  private val barrier = Module(new WorkgroupBarrierController(config))
  private val instructionCache = Module(new InstructionCache(config))
  private val instructionTlb = Module(new InstructionTlb(config))
  private val instructionPageTableWalker = Module(new Sv32PageTableWalker(config))
  private val memoryInterconnect = Module(new ComputeUnitMemoryInterconnect(config))

  controller.io.kernel <> io.kernel
  io.completion <> controller.io.completion
  controller.io.activeWarps := core.io.active
  core.io.scalarInitialize <> controller.io.scalarInitialize
  core.io.vectorInitialize <> controller.io.vectorInitialize
  core.io.launch <> controller.io.launch

  system.io.in <> core.io.system
  io.texSample <> system.io.texSample
  core.io.texCommit <> io.texWriteback
  core.io.restore <> system.io.restore
  barrier.io.arrive <> system.io.barrier
  barrier.io.residentWarps := controller.io.residentWarps
  barrier.io.dispatchComplete := controller.io.workgroupDispatchComplete
  barrier.io.memoryIdle := core.io.sharedMemoryIdle
  private val resumeArbiter = Module(new RRArbiter(new SimtPath(config), 2))
  resumeArbiter.io.in(0) <> system.io.resume
  resumeArbiter.io.in(1) <> barrier.io.release
  core.io.faultResume <> resumeArbiter.io.out
  io.unsupportedSystem <> system.io.unsupported
  core.io.finish := system.io.finish
  controller.io.finish := system.io.finish

  private val instructionTranslationEnabled = io.instructionSatp(31)
  instructionTlb.io.in.valid :=
    core.io.fetchRequest.valid && instructionTranslationEnabled
  instructionTlb.io.in.bits := core.io.fetchRequest.bits
  core.io.fetchRequest.ready := Mux(
    instructionTranslationEnabled,
    instructionTlb.io.in.ready,
    instructionCache.io.fetch.ready
  )
  instructionCache.io.fetch.valid := Mux(
    instructionTranslationEnabled,
    instructionTlb.io.physicalRequest.valid,
    core.io.fetchRequest.valid
  )
  instructionCache.io.fetch.bits := Mux(
    instructionTranslationEnabled,
    instructionTlb.io.physicalRequest.bits,
    core.io.fetchRequest.bits
  )
  instructionTlb.io.physicalRequest.ready :=
    instructionTranslationEnabled && instructionCache.io.fetch.ready
  instructionTlb.io.physicalResponse.valid :=
    instructionTranslationEnabled && instructionCache.io.response.valid
  instructionTlb.io.physicalResponse.bits := instructionCache.io.response.bits
  instructionCache.io.response.ready := Mux(
    instructionTranslationEnabled,
    instructionTlb.io.physicalResponse.ready,
    core.io.fetchResponse.ready
  )
  core.io.fetchResponse.valid := Mux(
    instructionTranslationEnabled,
    instructionTlb.io.out.valid,
    instructionCache.io.response.valid
  )
  core.io.fetchResponse.bits := Mux(
    instructionTranslationEnabled,
    instructionTlb.io.out.bits,
    instructionCache.io.response.bits
  )
  instructionTlb.io.out.ready :=
    instructionTranslationEnabled && core.io.fetchResponse.ready
  memoryInterconnect.io.instructionRequest <> instructionCache.io.lowerRequest
  instructionCache.io.lowerResponse <> memoryInterconnect.io.instructionResponse
  instructionCache.io.invalidate := io.invalidateInstructionCache
  instructionTlb.io.translationEnabled := instructionTranslationEnabled
  instructionTlb.io.asid := io.instructionSatp(30, 22)
  instructionTlb.io.flush := io.instructionTlbFlush
  instructionPageTableWalker.io.rootPpn := io.instructionSatp(19, 0)
  instructionPageTableWalker.io.request <> instructionTlb.io.pageWalkRequest
  instructionTlb.io.pageWalkResponse <> instructionPageTableWalker.io.response
  memoryInterconnect.io.instructionPageTableRequest <>
    instructionPageTableWalker.io.memoryRequest
  instructionPageTableWalker.io.memoryResponse <>
    memoryInterconnect.io.instructionPageTableResponse
  memoryInterconnect.io.dataRequest <> core.io.vectorMemoryRequest
  core.io.vectorMemoryResponse <> memoryInterconnect.io.dataResponse
  core.io.vectorSatp := io.vectorSatp
  core.io.vectorTlbFlush := io.vectorTlbFlush
  memoryInterconnect.io.dataPageTableRequest <> core.io.vectorPageTableRequest
  core.io.vectorPageTableResponse <> memoryInterconnect.io.dataPageTableResponse
  io.memoryRequest <> memoryInterconnect.io.memoryRequest
  memoryInterconnect.io.memoryResponse <> io.memoryResponse
  core.io.l1Invalidate <> io.l1Invalidate
  io.l1InvalidateDone <> core.io.l1InvalidateDone
  io.globalAtomicRequest <> core.io.globalAtomicRequest
  core.io.globalAtomicResponse <> io.globalAtomicResponse

  io.fpu <> core.io.fpu
  core.io.fpuInitialize.valid := false.B
  core.io.fpuInitialize.bits := 0.U.asTypeOf(core.io.fpuInitialize.bits)
  io.vector <> core.io.vector
  io.memory <> core.io.memory
  io.trap <> core.io.trap
  core.io.simtBranch <> io.simtBranch

  io.committedWriteback := core.io.committedWriteback
  io.committedVectorWriteback := core.io.committedVectorWriteback
  io.committedFpuWriteback := core.io.committedFpuWriteback
  io.committedFpuFlags := core.io.committedFpuFlags
  io.committedFpuIntegerWriteback := core.io.committedFpuIntegerWriteback
  io.active := core.io.active
  io.blocked := core.io.blocked
  io.barrierWaiting := barrier.io.waiting
}
