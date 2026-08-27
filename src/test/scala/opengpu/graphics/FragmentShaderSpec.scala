package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class FragmentShaderSpec extends AnyFlatSpec {
  behavior of "FragmentShader"

  it should "tint and bias a fragment colour with saturation" in {
    val config = GraphicsConfig()
    simulate(new FragmentShader(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.fragIn.valid.poke(true.B)
      dut.io.fragIn.bits.x.poke(5.S)
      dut.io.fragIn.bits.y.poke(6.S)
      dut.io.fragIn.bits.depth.poke(0x10.S)
      dut.io.out.ready.poke(true.B)
      // tint (1,128,255), bias (+1,-100,+10)
      dut.io.uniformTint(0).poke(1.U)
      dut.io.uniformTint(1).poke(128.U)
      dut.io.uniformTint(2).poke(255.U)
      dut.io.uniformBias(0).poke(1.S)
      dut.io.uniformBias(1).poke((-100).S)
      dut.io.uniformBias(2).poke(10.S)

      // r=128 -> (128*1)>>8=0 + 1 = 1
      // g=128 -> (128*128)>>8=64 + (-100) = -36 -> clamp 0
      // b=255 -> (255*255)>>8=254 + 10 = 264 -> clamp 255
      dut.io.fragIn.bits.color.r.poke(128.U)
      dut.io.fragIn.bits.color.g.poke(128.U)
      dut.io.fragIn.bits.color.b.poke(255.U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.color.r.expect(1.U)
      dut.io.out.bits.color.g.expect(0.U)
      dut.io.out.bits.color.b.expect(255.U)
      dut.io.out.bits.x.expect(5.S)
      dut.io.out.bits.depth.expect(0x10.S)
    }
  }
}
