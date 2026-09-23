package opengpu.command

import chisel3._
import opengpu.config.GpuConfig

/** One validated MSAA resolve operation: average the `1 << sampleMode`
  * interleaved RGBA8888 samples of a logical `width` x `height` region into a
  * packed single-sample word per pixel.
  *
  * Both strides are physical row strides in bytes, so the source and
  * destination may carry row padding.  The router only transports the
  * descriptor; range admission, buffer validation and fence publication belong
  * to the typed driver operation that builds it.
  */
class ResolveDescriptor(config: GpuConfig, val commandIdWidth: Int) extends Bundle {
  val descriptorId = UInt(commandIdWidth.W)
  /** Source/destination bases for the resolve engine (VA under VECTOR_SATP,
    * or physical when translation is Bare). */
  val sourceAddress = UInt(config.xLen.W)
  val destinationAddress = UInt(config.xLen.W)
  /** Physical source base for the pre-resolve L2 line invalidate. Equal to
    * `sourceAddress` when the engine also runs on physical addresses. */
  val invalidateAddress = UInt(config.xLen.W)
  /** Logical extent in pixels. */
  val imgWidth = UInt(16.W)
  val imgHeight = UInt(16.W)
  /** Row strides in bytes (same under VA or PA when the mapping is contiguous). */
  val sourceStride = UInt(32.W)
  val destinationStride = UInt(32.W)
  /** 0 = 1x, 1 = 2x, 2 = 4x. */
  val sampleMode = UInt(2.W)
}

object ResolveStatus {
  val width = 4
  val success = 0.U(width.W)
  /** A source read or destination write was rejected by the memory system. */
  val readFault = 5.U(width.W)
  val writeFault = 6.U(width.W)
}

class ResolveCompletion(val commandIdWidth: Int) extends Bundle {
  val descriptorId = UInt(commandIdWidth.W)
  val status = UInt(ResolveStatus.width.W)
  val success = Bool()
  /** Average bytes written to the destination. */
  val bytesResolved = UInt(64.W)
}
