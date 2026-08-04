package gpu.top

import chisel3._
import gpu.config.GpuConfig
import gpu.core.frontend.decode.{DecodePipe, DecodePipeIO}

/** GPU-core integration shell exposing the canonical one-stage decode path.
  *
  * Execution units will attach to the three routed outputs as they are added.
  */
class GpuTop(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new DecodePipeIO(config))

  val decodePipe = Module(new DecodePipe(config))
  io <> decodePipe.io
}
