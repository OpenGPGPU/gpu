package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

class TexSampleUnitSpec extends AnyFlatSpec {
  behavior of "TexSampleUnit"

  private def texel(r: Int, g: Int, b: Int): BigInt =
    (BigInt(r) << 24) | (BigInt(g) << 16) | (BigInt(b) << 8) | 0xff

  it should "serialize active vector lanes and preserve masked destination lanes" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new TexSampleUnit(config)) { dut =>
      val words = Map[Long, BigInt](
        0x2000L -> texel(0, 0, 0),
        0x2004L -> texel(255, 0, 0),
        0x2008L -> texel(0, 255, 0),
        0x200cL -> texel(255, 255, 0)
      )
      val pending = mutable.Queue.empty[Long]

      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.texBase.poke(0x2000.U)
      dut.io.texWidth.poke(2.U)
      dut.io.texHeight.poke(2.U)
      dut.io.wrapClamp.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.commit.ready.poke(true.B)
      dut.io.vectorIn.valid.poke(true.B)
      dut.io.vectorIn.bits.poke(0.U.asTypeOf(dut.io.vectorIn.bits))
      dut.io.vectorIn.bits.issued.decode.warpId.poke(1.U)
      dut.io.vectorIn.bits.issued.decode.pc.poke(0x300.U)
      dut.io.vectorIn.bits.issued.decode.activeMask.poke(0xf.U)
      dut.io.vectorIn.bits.issued.decode.instruction.poke("h062081ab".U)
      dut.io.vectorIn.bits.executionMask.poke("b1011".U)
      val coords = Seq(0x4000, 0xc000, 0x4000, 0xc000)
      val vCoords = Seq(0x4000, 0x4000, 0xc000, 0xc000)
      val old = Seq(0x11, 0x22, 0xdeadbeefL, 0x44)
      for (lane <- 0 until config.lanes) {
        dut.io.vectorIn.bits.issued.vs1Data(lane).poke(coords(lane).U)
        dut.io.vectorIn.bits.issued.vs2Data(lane).poke(vCoords(lane).U)
        dut.io.vectorIn.bits.issued.oldVdData(lane).poke(old(lane).U)
      }
      dut.io.vectorCommit.ready.poke(false.B)
      dut.io.mem.req.ready.poke(true.B)
      dut.io.mem.resp.valid.poke(false.B)
      dut.io.mem.resp.bits.poke(0.U.asTypeOf(dut.io.mem.resp.bits))

      dut.io.vectorIn.ready.expect(true.B)
      dut.clock.step()
      dut.io.vectorIn.valid.poke(false.B)

      var guard = 0
      var requests = 0
      var responses = 0
      while (!dut.io.vectorCommit.valid.peek().litToBoolean && guard < 300) {
        if (pending.nonEmpty) {
          dut.io.mem.resp.valid.poke(true.B)
          dut.io.mem.resp.bits.data.poke(words(pending.front).U)
          dut.io.mem.resp.bits.write.poke(false.B)
          if (dut.io.mem.resp.ready.peek().litToBoolean) {
            pending.dequeue()
            responses += 1
          }
        } else dut.io.mem.resp.valid.poke(false.B)

        if (dut.io.mem.req.valid.peek().litToBoolean &&
          dut.io.mem.req.ready.peek().litToBoolean) {
          pending.enqueue(dut.io.mem.req.bits.addr.peek().litValue.toLong)
          requests += 1
        }
        dut.clock.step()
        guard += 1
      }

      assert(dut.io.vectorCommit.valid.peek().litToBoolean,
        s"vector sample did not complete (requests=$requests, responses=$responses, pending=$pending)")
      dut.io.vectorCommit.bits.writeback.warpId.expect(1.U)
      dut.io.vectorCommit.bits.writeback.vd.expect(3.U)
      dut.io.vectorCommit.bits.writeback.data(0).expect(texel(0, 0, 0).U)
      dut.io.vectorCommit.bits.writeback.data(1).expect(texel(255, 0, 0).U)
      dut.io.vectorCommit.bits.writeback.data(2).expect("hdeadbeef".U)
      dut.io.vectorCommit.bits.writeback.data(3).expect(texel(255, 255, 0).U)
      dut.io.vectorCommit.bits.pc.expect(0x300.U)
      dut.clock.step(2)
      dut.io.vectorCommit.valid.expect(true.B)
      dut.io.vectorCommit.ready.poke(true.B)
      dut.clock.step()
      dut.io.vectorCommit.valid.expect(false.B)
    }
  }
}
