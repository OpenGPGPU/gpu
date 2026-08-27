package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class OutputMergerSpec extends AnyFlatSpec {
  behavior of "OutputMerger"

  private val W = 4
  private val H = 4
  private val stride = W * 4 // RGBA8888 => 4 bytes/pixel
  private val colorBase = 0x1000
  private val depthBase = 0x2000

  private def addrOf(base: Int, x: Int, y: Int): UInt =
    ((base + (y * stride) + x * 4).U)(31, 0)

  // Drive the OM against a byte-array memory model (color + depth buffers are
  // slices of a shared array).  Reads have one-cycle latency; writes commit.
  // Tests read the buffer back afterwards.
  private def runFragment(
    dut: OutputMerger, mem: Array[Int],
    x: Int, y: Int, color: Int, depth: Int,
    depthTestEnable: Boolean, depthFunc: Int, depthWrite: Boolean
  ): Unit = {
    dut.io.fragIn.valid.poke(true.B)
    dut.io.fragIn.bits.x.poke(x.U)
    dut.io.fragIn.bits.y.poke(y.U)
    dut.io.fragIn.bits.color.poke((color.toLong & 0xffffffffL).U)
    dut.io.fragIn.bits.depth.poke((depth.toLong & 0xffffffffL).U)
    dut.io.fragIn.ready.expect(true.B)
    dut.clock.step()

    var lastReadValid = false
    var lastReadData = 0L
    var guard = 0
    while (!dut.io.fragIn.ready.peek().litToBoolean && guard < 80) {
      dut.io.mem.req.ready.poke(true.B)
      // Respond to a read issued on the previous cycle.
      if (lastReadValid) {
        dut.io.mem.resp.valid.poke(true.B)
        dut.io.mem.resp.bits.data.poke(lastReadData.U)
        lastReadValid = false
      } else {
        dut.io.mem.resp.valid.poke(false.B)
        dut.io.mem.resp.bits.data.poke(0.U)
      }
      // Issue this cycle's request (ready is high so it fires).
      if (dut.io.mem.req.valid.peek().litToBoolean) {
        val addr = dut.io.mem.req.bits.addr.peek().litValue.toInt
        val write = dut.io.mem.req.bits.write.peek().litToBoolean
        val data = dut.io.mem.req.bits.data.peek().litValue.toInt
        if (write) mem(addr) = data
        else { lastReadValid = true; lastReadData = mem(addr) & 0xffffffffL }
      }
      dut.clock.step()
      guard += 1
    }
    dut.io.fragIn.valid.poke(false.B)
    dut.clock.step()
  }

  it should "write color and depth for a passing fragment" in {
    simulate(new OutputMerger(GraphicsConfig())) { dut =>
      val mem = Array.fill(1 << 15)(0xffffffff)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.colorBase.poke(colorBase.U)
      dut.io.depthBase.poke(depthBase.U)
      dut.io.stride.poke(stride.U)
      dut.io.depthTestEnable.poke(true.B)
      dut.io.depthFunc.poke(0.U) // less
      dut.io.depthWriteEnable.poke(true.B)
      dut.io.mem.resp.valid.poke(false.B)
      dut.io.blendEnable.poke(false.B)

      runFragment(dut, mem, 1, 1, 0x11223344, 0x00000010, true, 0, true)
      assert(mem(colorBase + (1 * stride) + 1 * 4) == 0x11223344)
    }
  }

  it should "keep the nearer (smaller depth) of two overlapping fragments" in {
    simulate(new OutputMerger(GraphicsConfig())) { dut =>
      val mem = Array.fill(1 << 15)(0xffffffff)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.colorBase.poke(colorBase.U)
      dut.io.depthBase.poke(depthBase.U)
      dut.io.stride.poke(stride.U)
      dut.io.depthTestEnable.poke(true.B)
      dut.io.depthFunc.poke(0.U) // less
      dut.io.depthWriteEnable.poke(true.B)
      dut.io.mem.resp.valid.poke(false.B)
      dut.io.blendEnable.poke(false.B)

      // Far fragment first, then near fragment.
      runFragment(dut, mem, 1, 1, 0xAA000000, 0x00000050, true, 0, true)
      runFragment(dut, mem, 1, 1, 0x00BB0000, 0x00000010, true, 0, true)
      val color = mem(colorBase + (1 * stride) + 1 * 4)
      assert(color == 0x00BB0000, s"nearer fragment should win, got 0x${color.toHexString}")

      // Near fragment first, then far fragment — far must be rejected.
      runFragment(dut, mem, 2, 2, 0x0000CC00, 0x00000008, true, 0, true)
      runFragment(dut, mem, 2, 2, 0x000000DD, 0x00000060, true, 0, true)
      assert(mem(colorBase + (2 * stride) + 2 * 4) == 0x0000CC00)
    }
  }

  it should "reject a fragment that fails the depth test and not write it" in {
    simulate(new OutputMerger(GraphicsConfig())) { dut =>
      val mem = Array.fill(1 << 15)(0xffffffff)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.colorBase.poke(colorBase.U)
      dut.io.depthBase.poke(depthBase.U)
      dut.io.stride.poke(stride.U)
      dut.io.depthTestEnable.poke(true.B)
      dut.io.depthFunc.poke(0.U) // less
      dut.io.depthWriteEnable.poke(true.B)
      dut.io.mem.resp.valid.poke(false.B)
      dut.io.mem.resp.bits.write.poke(false.B)
      dut.io.blendEnable.poke(false.B)

      runFragment(dut, mem, 0, 0, 0x11111111, 0x00000010, true, 0, true)
      runFragment(dut, mem, 0, 0, 0x22222222, 0x00000020, true, 0, true) // larger depth => fail
      val color = mem(colorBase + 0 + 0)
      assert(color == 0x11111111, s"failing fragment must not overwrite, got 0x${color.toHexString}")
    }
  }

  it should "ignore write acknowledgements that arrive while a depth read is in flight" in {
    simulate(new OutputMerger(GraphicsConfig())) { dut =>
      val mem = Array.fill(1 << 15)(0xffffffff)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.colorBase.poke(colorBase.U)
      dut.io.depthBase.poke(depthBase.U)
      dut.io.stride.poke(stride.U)
      dut.io.depthTestEnable.poke(true.B)
      dut.io.depthFunc.poke(0.U) // less
      dut.io.depthWriteEnable.poke(true.B)
      dut.io.mem.resp.valid.poke(false.B)
      dut.io.mem.resp.bits.write.poke(false.B)
      dut.io.blendEnable.poke(false.B)

      // Memory model emulating an out-of-order shared memory (the L2 path):
      // write acknowledgements are held back and released ONLY once a later
      // read is in flight, so an ack overtakes the read response.  The OM
      // must pop the ack (tagged write=true, data=0) and keep waiting for the
      // real read data; consuming the ack as depth (0) would fail every
      // LESS test and reject the fragment.
      val respQ = scala.collection.mutable.Queue.empty[(Boolean, Long)]
      var heldAcks = 0
      def runFragOOO(x: Int, y: Int, color: Int, depth: Int): Unit = {
        dut.io.fragIn.valid.poke(true.B)
        dut.io.fragIn.bits.x.poke(x.U)
        dut.io.fragIn.bits.y.poke(y.U)
        dut.io.fragIn.bits.color.poke((color.toLong & 0xffffffffL).U)
        dut.io.fragIn.bits.depth.poke((depth.toLong & 0xffffffffL).U)
        dut.io.fragIn.ready.expect(true.B)
        dut.clock.step()
        var guard = 0
        while (!dut.io.fragIn.ready.peek().litToBoolean && guard < 80) {
          dut.io.mem.req.ready.poke(true.B)
          if (respQ.nonEmpty) {
            val (isWrite, data) = respQ.dequeue()
            dut.io.mem.resp.valid.poke(true.B)
            dut.io.mem.resp.bits.write.poke(isWrite.B)
            dut.io.mem.resp.bits.data.poke(data.U)
          } else {
            dut.io.mem.resp.valid.poke(false.B)
          }
          if (dut.io.mem.req.valid.peek().litToBoolean) {
            val addr = dut.io.mem.req.bits.addr.peek().litValue.toInt
            val write = dut.io.mem.req.bits.write.peek().litToBoolean
            val data = dut.io.mem.req.bits.data.peek().litValue.toInt
            if (write) {
              mem(addr) = data
              heldAcks += 1
            } else {
              // Release every held write ack ahead of this read's response.
              while (heldAcks > 0) { respQ.enqueue((true, 0L)); heldAcks -= 1 }
              respQ.enqueue((false, mem(addr) & 0xffffffffL))
            }
          }
          dut.clock.step()
          guard += 1
        }
        assert(guard < 80, "OM did not drain")
        dut.io.fragIn.valid.poke(false.B)
        dut.clock.step()
      }

      runFragOOO(1, 1, 0xAA000000, 0x00000010) // passes against the clear value
      // Nearer fragment: must read back the stored 0x10, not the ack's 0.
      runFragOOO(1, 1, 0x00BB0000, 0x00000008)
      val color = mem(colorBase + (1 * stride) + 1 * 4)
      assert(color == 0x00BB0000,
        s"nearer fragment must pass the depth test despite overtaking write acks, got 0x${color.toHexString}")
      assert(mem(depthBase + (1 * stride) + 1 * 4) == 0x00000008,
        "depth buffer must hold the winning fragment's depth")
    }
  }
}
