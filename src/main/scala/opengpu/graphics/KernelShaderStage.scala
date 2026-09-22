package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.GpuComputeUnit
import opengpu.core.execute.control.SimtBranchRequest
import opengpu.core.backend.issue.ScalarIssuedInstruction
import opengpu.core.backend.{VectorCommitRequest, VectorTextureRequest}
import opengpu.core.backend.writeback.ScalarCommitRequest
import opengpu.core.memory.{
  CacheLineInvalidate,
  ComputeMemoryRequest,
  ComputeMemoryResponse,
  SharedAtomicRequest,
  SharedAtomicResponse
}
import opengpu.core.trap.CoreTrapEvent
import opengpu.dispatch.KernelCompletion

/** The core-backed shader unit (Phase D connectivity).
  *
  * Commercial-GPU-aligned shading runs a fragment/vertex shader as a kernel on
  * the compute unit's SIMT warps.  This module is the graphics-side driver: it
  * consumes a draw's shader descriptor (entry PC + kernarg buffer + grid/local
  * sizes), assembles a `KernelLaunch` via `KernelEmit`, and hands it to a
  * `GpuComputeUnit`.  The shader program, kernarg buffer and output buffer all
  * live in the line-based memory exposed on `memoryRequest/memoryResponse`; the
  * `completion` port reports draw-done once the kernel retires, and `trap`
  * surfaces a kernel fault to the owner.
  *
  * This replaces the fixed-function `ShaderFragStage`: the shader is a compiled
  * RV32 program run on the core, so the same RISC-V toolchain and core machinery
  * (fetch/decode/issue/RF/ALU/FPU/commit) are reused instead of a private
  * shader core.
  */
class KernelShaderStage(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val instructionSatp = Input(UInt(32.W))
    val instructionTlbFlush = Flipped(Valid(new opengpu.core.memory.VectorTlbFlush(config)))
    val vectorSatp = Input(UInt(32.W))
    val vectorTlbFlush = Flipped(Valid(new opengpu.core.memory.VectorTlbFlush(config)))
    val launch = new Bundle {
      val valid = Input(Bool())
      val ready = Output(Bool())
      val kernelPc = Input(UInt(config.xLen.W))
      val kernargAddress = Input(UInt(config.xLen.W))
      val gridX = Input(UInt(32.W))
      val gridY = Input(UInt(32.W))
      val gridZ = Input(UInt(32.W))
      val localX = Input(UInt(16.W))
      val localY = Input(UInt(16.W))
      val localZ = Input(UInt(16.W))
    }
    val completion = Decoupled(new KernelCompletion)
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse()))
    val trap = Decoupled(new CoreTrapEvent(config))
    val simtBranch = Flipped(Decoupled(new SimtBranchRequest(config)))
    val l1Invalidate = Flipped(Decoupled(new CacheLineInvalidate(config)))
    val l1InvalidateDone = Decoupled(new CacheLineInvalidate(config))
    val globalAtomicRequest = Decoupled(new SharedAtomicRequest(config))
    val globalAtomicResponse = Flipped(Decoupled(new SharedAtomicResponse(config)))
    /** tex.sample sideband (issued instruction out, commit request in). */
    val texSample = Decoupled(new ScalarIssuedInstruction(config))
    val texWriteback = Flipped(Decoupled(new ScalarCommitRequest(config)))
    val vectorTexSample = Decoupled(new VectorTextureRequest(config))
    val vectorTexWriteback =
      Flipped(Decoupled(new VectorCommitRequest(config)))
  })

  private val emit = Module(new KernelEmit(config))
  private val cu = Module(new GpuComputeUnit(config, finishOnTrap = true))

  io.launch.ready := cu.io.kernel.ready
  cu.io.kernel.valid := io.launch.valid
  cu.io.kernel.bits := emit.io.kernel

  emit.io.kernelPc := io.launch.kernelPc
  emit.io.kernargAddress := io.launch.kernargAddress
  emit.io.gridX := io.launch.gridX
  emit.io.gridY := io.launch.gridY
  emit.io.gridZ := io.launch.gridZ
  emit.io.localX := io.launch.localX
  emit.io.localY := io.launch.localY
  emit.io.localZ := io.launch.localZ
  emit.io.valid := io.launch.valid
  emit.io.ready := cu.io.kernel.ready

  io.completion <> cu.io.completion
  io.memoryRequest <> cu.io.memoryRequest
  cu.io.memoryResponse <> io.memoryResponse
  io.trap <> cu.io.trap
  io.simtBranch <> cu.io.simtBranch
  cu.io.l1Invalidate <> io.l1Invalidate
  io.l1InvalidateDone <> cu.io.l1InvalidateDone
  io.globalAtomicRequest <> cu.io.globalAtomicRequest
  cu.io.globalAtomicResponse <> io.globalAtomicResponse
  io.texSample <> cu.io.texSample
  cu.io.texWriteback <> io.texWriteback
  io.vectorTexSample <> cu.io.vectorTexSample
  cu.io.vectorTexWriteback <> io.vectorTexWriteback

  // Instruction fetch is PA-tagged after the TLB. Remapped snapshot pages can
  // reuse a physical line, so a TLB shootdown must also drop I-cache contents.
  cu.io.invalidateInstructionCache := io.instructionTlbFlush.valid
  cu.io.instructionSatp := io.instructionSatp
  cu.io.instructionTlbFlush := io.instructionTlbFlush
  // Staging word-bridge and shader kernarg/data loads share VECTOR_SATP so
  // draw-record addresses can be private VAs under the context ASID.
  cu.io.vectorSatp := io.vectorSatp
  cu.io.vectorTlbFlush := io.vectorTlbFlush
  // Unsupported execution handoffs have no completion service here. Shader
  // validation excludes them; any system instruction that reaches the CU is
  // converted to an illegal-instruction trap and failed completion there.
  cu.io.fpu.ready := false.B
  cu.io.vector.ready := false.B
  cu.io.memory.ready := false.B
}
