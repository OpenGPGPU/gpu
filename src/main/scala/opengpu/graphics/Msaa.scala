package opengpu.graphics

import chisel3._
import chisel3.util._

/** Fixed, centre-relative quarter-pixel sample positions. */
object Msaa {
  def positions(mode: Int): Seq[(Int, Int)] = mode match {
    case 0 => Seq((0, 0))
    case 1 => Seq((-1, -1), (1, 1))
    case 2 => Seq((-1, -1), (1, -1), (-1, 1), (1, 1))
    case _ => throw new IllegalArgumentException("reserved sample mode")
  }
}

/** Coverage from pixel-centre edges and exact quarter-pixel edge deltas.
  * No multiplier is needed in the per-pixel path.
  */
class SampleCoverage(maxSampleCount: Int = 4, edgeWidth: Int = 64) extends Module {
  require(Set(1, 2, 4)(maxSampleCount))
  val io = IO(new Bundle {
    val sampleMode = Input(UInt(2.W))
    val edges = Input(Vec(3, SInt(edgeWidth.W)))
    val quarterDx = Input(Vec(3, SInt(edgeWidth.W)))
    val quarterDy = Input(Vec(3, SInt(edgeWidth.W)))
    val front = Input(Bool())
    val topLeft = Input(Vec(3, Bool()))
    val mask = Output(UInt(maxSampleCount.W))
  })
  val masks = (0 to log2Ceil(maxSampleCount)).map { mode =>
    val bits = Msaa.positions(mode).map { case (x, y) =>
      (0 until 3).map { e =>
        val dx = if (x == 0) 0.S else if (x > 0) io.quarterDx(e) else -io.quarterDx(e)
        val dy = if (y == 0) 0.S else if (y > 0) io.quarterDy(e) else -io.quarterDy(e)
        val value = io.edges(e) +& dx +& dy
        Mux(io.front, value > 0.S, value < 0.S) ||
          (value === 0.S && io.topLeft(e))
      }.reduce(_ && _)
    }
    // Cat maps the lowest sample index to bit 0; the OR widens the shorter
    // non-power-of-two mode masks to the fixed output width.
    mode.U -> (0.U(maxSampleCount.W) | Cat(bits.reverse))
  }
  io.mask := MuxLookup(io.sampleMode, 0.U(maxSampleCount.W))(masks)
}

/** Post-shader pixel. The producer has already selected shader versus
  * interpolated sample depth. The draw context stays live until drained.
  */
class SamplePixel(maxSampleCount: Int = 4) extends Bundle {
  val x = UInt(16.W)
  val y = UInt(16.W)
  val color = UInt(32.W)
  val coverageMask = UInt(maxSampleCount.W)
  val depths = Vec(maxSampleCount, UInt(30.W))
}

/** Serial sample expansion, retaining the pixel through arbitrary OM stalls.
  * A zero mask consumes an input without generating an output.
  */
class SampleExpander(maxSampleCount: Int = 4) extends Module {
  require(Set(1, 2, 4)(maxSampleCount))
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new SamplePixel(maxSampleCount)))
    val out = Decoupled(new OmFragment)
    val drained = Output(Bool())
  })
  val pixel = Reg(new SamplePixel(maxSampleCount))
  val remaining = RegInit(0.U(maxSampleCount.W))
  val sample = if (maxSampleCount == 1) 0.U else PriorityEncoder(remaining)
  io.in.ready := !remaining.orR
  io.drained := !remaining.orR
  io.out.valid := remaining.orR
  io.out.bits.x := pixel.x
  io.out.bits.y := pixel.y
  io.out.bits.color := pixel.color
  io.out.bits.depth := pixel.depths(sample)
  io.out.bits.sampleIndex := sample
  when(io.in.fire) {
    pixel := io.in.bits
    remaining := io.in.bits.coverageMask
  }
  when(io.out.fire) { remaining := remaining & (remaining - 1.U) }
}
