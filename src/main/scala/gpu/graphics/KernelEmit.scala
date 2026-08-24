package gpu.graphics

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.dispatch.KernelLaunch

/** Graphics-side kernel-launch generator (Phase D connectivity).
  *
  * Commercial-GPU-aligned unified shading runs a vertex/fragment shader as a
  * kernel on the core's SIMT warps.  The core exposes this via
  * `GpuComputeUnit.io.kernel : Decoupled(KernelLaunch)`; here the graphics
  * pipeline assembles a `KernelLaunch` from a draw's shader descriptor (entry
  * PC, kernel-argument buffer, and grid/local sizes laid out as fragments x
  * pixels), so the two subsystems talk over the same kernel interface.
  */
class KernelEmit(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val kernelPc = Input(UInt(config.xLen.W))
    val kernargAddress = Input(UInt(config.xLen.W))
    val gridX = Input(UInt(32.W))
    val gridY = Input(UInt(32.W))
    val gridZ = Input(UInt(32.W))
    val localX = Input(UInt(16.W))
    val localY = Input(UInt(16.W))
    val localZ = Input(UInt(16.W))
    val valid = Input(Bool())
    val ready = Input(Bool())
    val kernel = new KernelLaunch(config)
  })

  io.kernel.kernelPc := io.kernelPc
  io.kernel.kernargAddress := io.kernargAddress
  io.kernel.gridSize(0) := io.gridX
  io.kernel.gridSize(1) := io.gridY
  io.kernel.gridSize(2) := io.gridZ
  io.kernel.localSize(0) := io.localX
  io.kernel.localSize(1) := io.localY
  io.kernel.localSize(2) := io.localZ
}
