package gpu.graphics

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.GpuComputeUnit
import gpu.core.execute.control.SimtBranchRequest
import gpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}
import gpu.core.trap.CoreTrapEvent
import gpu.dispatch.KernelCompletion

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
  })

  private val emit = Module(new KernelEmit(config))
  private val cu = Module(new GpuComputeUnit(config))

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
  cu.io.l1Invalidate.valid := false.B
  cu.io.l1Invalidate.bits.lineAddress := 0.U
  cu.io.l1InvalidateDone.ready := true.B
  cu.io.globalAtomicRequest.ready := true.B
  cu.io.globalAtomicResponse.valid := false.B
  cu.io.globalAtomicResponse.bits := 0.U.asTypeOf(cu.io.globalAtomicResponse.bits)
}
