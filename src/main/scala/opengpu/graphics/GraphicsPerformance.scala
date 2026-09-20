package opengpu.graphics

import chisel3._

/** Event wires only: unused instrumentation is removed from production RTL. */
class GraphicsPerformanceEvents extends Bundle {
  val omStall = Bool()
  val omConflict = Bool()
  val rasterStall = Bool()
  val stagingReadBytes = UInt(8.W)
  val stagingWriteBytes = UInt(8.W)
}
