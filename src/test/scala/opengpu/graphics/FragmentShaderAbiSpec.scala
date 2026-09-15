package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class FragmentShaderAbiHarness extends Module {
  val io = IO(new Bundle {
    val sampleMode = Input(UInt(2.W))
    val control = Input(UInt(32.W))
    val abi1 = Output(Bool())
    val emit = Output(Bool())
    val depthOverride = Output(Bool())
  })
  io.abi1 := FragmentShaderAbi.usesMultisample(io.sampleMode)
  io.emit := FragmentShaderAbi.emits(io.control, io.abi1)
  io.depthOverride := FragmentShaderAbi.overridesDepth(io.control, io.abi1)
}

class FragmentShaderAbiSpec extends AnyFlatSpec {
  behavior of "Fragment shader ABI"

  it should "preserve legacy nonzero words and enforce the multisample control contract" in {
    // Expected emit decisions for ABI 0 and ABI 1, independent of the decoder.
    val cases = Seq(
      (0L, false, false), (1L, true, true), (2L, true, false),
      (3L, true, true), (4L, true, false), (5L, true, false),
      (7L, true, false), (0x80000000L, true, false),
      (0x80000001L, true, false), (0xffffffffL, true, false))
    simulate(new FragmentShaderAbiHarness) { dut =>
      for (mode <- Seq(0, 1, 2); (word, legacyEmit, msaaEmit) <- cases) {
        dut.io.sampleMode.poke(mode.U)
        dut.io.control.poke(word.U)
        dut.io.abi1.expect((mode != 0).B)
        val emit = if (mode == 0) legacyEmit else msaaEmit
        dut.io.emit.expect(emit.B, s"mode=$mode control=0x${word.toHexString}")
        // Depth selection is meaningful only for emitted pixels.
        if (emit) dut.io.depthOverride.expect((mode == 0 || word == 3).B)
      }
    }
  }
}
