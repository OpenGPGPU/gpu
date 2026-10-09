package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TileAttachmentStoreSpec extends AnyFlatSpec {
  behavior of "TileAttachmentStore"

  it should "reuse loaded words and store only dirty words before changing tiles" in {
    simulate(new TileAttachmentStore) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.fragmentValid.poke(false.B)
      dut.io.fragmentFire.poke(false.B)
      dut.io.fragmentX.poke(3.U)
      dut.io.fragmentY.poke(4.U)
      dut.io.colorBase.poke(0x1000.U)
      dut.io.depthBase.poke(0x2000.U)
      dut.io.stride.poke(64.U)
      dut.io.sampleMode.poke(0.U)
      dut.io.omDrained.poke(true.B)
      dut.io.finish.poke(false.B)
      dut.io.wordIndex.poke(0x43.U)
      dut.io.depthPlane.poke(true.B)
      dut.io.om.req.valid.poke(false.B)
      dut.io.om.resp.ready.poke(true.B)
      dut.io.mem.req.ready.poke(true.B)
      dut.io.mem.resp.valid.poke(false.B)

      dut.io.permit.expect(true.B)
      dut.io.fragmentValid.poke(true.B)
      dut.io.fragmentFire.poke(true.B)
      dut.clock.step()
      dut.io.fragmentValid.poke(false.B)
      dut.io.fragmentFire.poke(false.B)
      dut.io.drained.expect(false.B)

      val depthAddr = 0x2000 + 4 * 64 + 3 * 4
      val colorAddr = 0x1000 + 4 * 64 + 3 * 4
      def request(addr: Int, write: Boolean, data: Long, depth: Boolean): Unit = {
        dut.io.wordIndex.poke(0x43.U)
        dut.io.depthPlane.poke(depth.B)
        dut.io.om.req.bits.addr.poke(addr.U)
        dut.io.om.req.bits.write.poke(write.B)
        dut.io.om.req.bits.data.poke(data.U)
        dut.io.om.req.valid.poke(true.B)
        var waited = 0
        while (!dut.io.om.req.ready.peek().litToBoolean && waited < 20) {
          dut.clock.step()
          waited += 1
        }
        assert(waited < 20)
        dut.clock.step()
        dut.io.om.req.valid.poke(false.B)
      }
      def omResponse(addr: Int, write: Boolean, data: Long): Unit = {
        dut.io.om.resp.valid.expect(true.B)
        dut.io.om.resp.bits.addr.expect(addr.U)
        dut.io.om.resp.bits.write.expect(write.B)
        dut.io.om.resp.bits.data.expect(data.U)
        dut.clock.step()
      }
      def memoryResponse(addr: Int, write: Boolean, data: Long): Unit = {
        dut.io.mem.resp.bits.addr.poke(addr.U)
        dut.io.mem.resp.bits.write.poke(write.B)
        dut.io.mem.resp.bits.data.poke(data.U)
        dut.io.mem.resp.valid.poke(true.B)
        dut.clock.step()
        dut.io.mem.resp.valid.poke(false.B)
      }

      request(depthAddr, write = false, data = 0, depth = true)
      dut.io.mem.req.valid.expect(true.B)
      dut.io.mem.req.bits.addr.expect(depthAddr.U)
      dut.io.mem.req.bits.write.expect(false.B)
      dut.clock.step()
      memoryResponse(depthAddr, write = false, data = 0x00ffffffL)
      omResponse(depthAddr, write = false, 0x00ffffffL)

      request(depthAddr, write = true, data = 0x00123456L, depth = true)
      dut.io.mem.req.valid.expect(false.B)
      omResponse(depthAddr, write = true, 0)
      request(depthAddr, write = false, data = 0, depth = true)
      dut.io.mem.req.valid.expect(false.B)
      omResponse(depthAddr, write = false, 0x00123456L)

      request(colorAddr, write = true, data = 0xaabbccddL, depth = false)
      omResponse(colorAddr, write = true, 0)

      dut.io.fragmentX.poke(17.U)
      dut.io.fragmentValid.poke(true.B)
      dut.io.omDrained.poke(false.B)
      dut.io.permit.expect(false.B)
      dut.clock.step()
      dut.io.permit.expect(false.B)
      // The next tile is blocked, but an older OM entry must still be able
      // to read the resident tile; otherwise the drain state deadlocks.
      request(colorAddr, write = false, data = 0, depth = false)
      dut.io.mem.req.valid.expect(false.B)
      omResponse(colorAddr, write = false, 0xaabbccddL)
      dut.io.omDrained.poke(true.B)
      dut.clock.step()
      for ((addr, data) <- Seq(
        (depthAddr, 0x00123456L), (colorAddr, 0xaabbccddL))) {
        dut.io.mem.req.valid.expect(true.B)
        dut.io.mem.req.bits.write.expect(true.B)
        dut.io.mem.req.bits.addr.expect(addr.U)
        dut.io.mem.req.bits.data.expect(data.U)
        dut.clock.step()
        dut.io.permit.expect(false.B)
        dut.io.drained.expect(false.B)
        memoryResponse(addr, write = true, data = 0)
      }
      dut.clock.step()
      dut.io.permit.expect(true.B)
      dut.io.drained.expect(true.B)
    }
  }
}
