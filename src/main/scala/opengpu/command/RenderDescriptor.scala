package opengpu.command

import chisel3._
import opengpu.config.GpuConfig

/** One unified render command: the virtual address of a 16-word render
  * descriptor (the `gpu_job_record` layout) in the submitting context's VM.
  * The graphics engine fetches the descriptor and its command buffer through
  * the translated command client, so a draw needs no dedicated physical word
  * port.
  */
class RenderDescriptor(config: GpuConfig, val commandIdWidth: Int)
    extends Bundle {
  val descriptorId = UInt(commandIdWidth.W)
  val descriptorAddress = UInt(config.xLen.W)
  /** Descriptor byte length; the fetcher reads ceil(bytes / 64) lines. */
  val bytes = UInt(32.W)
}

object RenderStatus {
  val width = 4
  val success = 0.U(width.W)
}

class RenderCompletion(val commandIdWidth: Int) extends Bundle {
  val descriptorId = UInt(commandIdWidth.W)
  val status = UInt(RenderStatus.width.W)
  val success = Bool()
  /** Bytes of command records the graphics engine consumed. */
  val bytesProcessed = UInt(64.W)
}
