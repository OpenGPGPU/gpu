package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

class VertexTileReplaySpec extends AnyFlatSpec {
  behavior of "vertex tile replay"

  private def addi(rd: Int, rs: Int, imm: Int): Int =
    ((imm & 0xfff) << 20) | (rs << 15) | (rd << 7) | 0x13
  private def slli(rd: Int, rs: Int, shift: Int): Int =
    (shift << 20) | (rs << 15) | (1 << 12) | (rd << 7) | 0x13
  private def add(rd: Int, a: Int, b: Int): Int =
    (b << 20) | (a << 15) | (rd << 7) | 0x33
  private def vsetivli(lanes: Int): Int =
    0xc1007057 | (lanes << 15)
  private def vle32(base: Int, vd: Int): Int =
    0x02006007 | (base << 15) | (vd << 7)
  private def vse32(base: Int, vs: Int): Int =
    0x02006027 | (base << 15) | (vs << 7)

  it should "drain two tile chunks from one vertex command" in {
    val cfg = GraphicsConfig(screenWidth = 16, screenHeight = 16,
      subPixelBits = 8, tileSize = 16, tileAttachments = true, tileBinning = true)
    val command = 0x4000L
    val vertexProgram = 0x1000L
    val fragmentProgram = 0x2000L
    val vertices = 0x3000L
    val vertexArgs = 0x6000L
    val fragmentArgs = 0x8000L
    val color = 0xa000L
    val depth = 0xb000L
    val words = mutable.LongMap.empty[Int]
    def read(a: Long): Long = words.getOrElse(a, 0) & 0xffffffffL
    def write(a: Long, value: Long): Unit = words(a) = value.toInt
    def lineRead(a: Long): BigInt =
      (0 until 16).foldLeft(BigInt(0)) { (line, i) =>
        line | (BigInt(read(a + i * 4)) << (i * 32))
      }
    def lineWrite(a: Long, data: BigInt, mask: BigInt): Unit = {
      for (b <- 0 until 64 if mask.testBit(b)) {
        val address = a + (b & ~3)
        val shift = (b & 3) * 8
        write(address, (read(address) & ~(0xffL << shift)) |
          (((data >> (b * 8)).toLong & 0xffL) << shift))
      }
    }
    val vertCode = Seq(slli(6, 8, 2), add(6, 1, 6), vsetivli(3)) ++
      (0 until 8).flatMap { f =>
      Seq(addi(5, 6, f * 32), vle32(5, 1),
        addi(5, 6, (8 + f) * 32), vse32(5, 1))
    } ++ Seq(0x30500073)
    vertCode.zipWithIndex.foreach { case (w, i) => write(vertexProgram + i * 4, w) }
    val fragCode = Seq(slli(5, 8, 2), add(5, 1, 5), vsetivli(4),
      addi(6, 5, 96), vle32(6, 2), addi(6, 5, 192), vse32(6, 2),
      0x30500073)
    fragCode.zipWithIndex.foreach { case (w, i) => write(fragmentProgram + i * 4, w) }
    val first = Seq((-32768, -32768), (0, -32768), (-32768, 0))
    val ninth = Seq((16384, 16384), (49152, 16384), (16384, 49152))
    (0 until 27).foreach { i =>
      val (x, y) = (if (i < 24) first else ninth)(i % 3)
      val base = vertices + i * 32
      Seq(x.toLong, y.toLong, 0L, 65536L, 0xff0000ffL, 16L, 0L, 0L)
        .zipWithIndex.foreach { case (w, j) => write(base + j * 4, w) }
    }
    val record = Array.fill[Long](40)(0)
    record(0) = vertices
    record(1) = 27
    record(2) = 32
    record(3) = vertexProgram
    record(4) = vertexArgs
    record(5) = 0x1000 // alternate staging banks between vertex batches
    record(24) = fragmentProgram
    record(25) = fragmentArgs
    // The ninth triangle covers a different pixel after the chunk boundary.
    record.zipWithIndex.foreach { case (w, i) => write(command + i * 4, w) }
    for (i <- 0 until 256) write(depth + i * 4, 0x00ffffffL)

    simulate(new RenderCore(cfg, GpuConfig(lanes = 4, warps = 2),
      fragCore = true, vertCore = true)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.cmdBase.poke(command.U)
      dut.io.cmdCount.poke(1.U)
      dut.io.colorBase.poke(color.U)
      dut.io.depthBase.poke(depth.U)
      dut.io.stride.poke(64.U)
      dut.io.depthTestEnable.poke(false.B)
      dut.io.depthWriteEnable.poke(false.B)
      dut.io.cullMode.poke(0.U)
      dut.io.sampleMode.poke(0.U)
      dut.io.cbMem.req.ready.poke(true.B)
      dut.io.cbMem.resp.valid.poke(false.B)
      dut.io.fbMem.req.ready.poke(true.B)
      dut.io.fbMem.resp.valid.poke(false.B)
      dut.io.kernelMemReq.ready.poke(true.B)
      dut.io.kernelMemResp.valid.poke(false.B)
      dut.io.kernelWordMemReq.ready.poke(true.B)
      dut.io.kernelWordMemResp.valid.poke(false.B)
      dut.io.start.poke(true.B); dut.clock.step(); dut.io.start.poke(false.B)

      val cbQueue = mutable.Queue.empty[(Long, Long)]
      val fbQueue = mutable.Queue.empty[(Boolean, Long, Long)]
      val lineQueue = mutable.Queue.empty[(BigInt, BigInt)]
      val wordQueue = mutable.Queue.empty[(BigInt, BigInt)]
      var cycles = 0
      while (!dut.io.done.peek().litToBoolean && cycles < 60000) {
        dut.io.cbMem.resp.valid.poke(cbQueue.nonEmpty.B)
        if (cbQueue.nonEmpty) {
          dut.io.cbMem.resp.bits.addr.poke(cbQueue.head._1.U)
          dut.io.cbMem.resp.bits.data.poke(cbQueue.head._2.U)
        }
        if (cbQueue.nonEmpty && dut.io.cbMem.resp.ready.peek().litToBoolean)
          cbQueue.dequeue()
        if (dut.io.cbMem.req.valid.peek().litToBoolean) {
          val a = dut.io.cbMem.req.bits.addr.peek().litValue.toLong
          cbQueue.enqueue((a, read(a)))
        }

        dut.io.fbMem.resp.valid.poke(fbQueue.nonEmpty.B)
        if (fbQueue.nonEmpty) {
          dut.io.fbMem.resp.bits.write.poke(fbQueue.head._1.B)
          dut.io.fbMem.resp.bits.addr.poke(fbQueue.head._2.U)
          dut.io.fbMem.resp.bits.data.poke(fbQueue.head._3.U)
        }
        if (fbQueue.nonEmpty && dut.io.fbMem.resp.ready.peek().litToBoolean)
          fbQueue.dequeue()
        if (dut.io.fbMem.req.valid.peek().litToBoolean) {
          val a = dut.io.fbMem.req.bits.addr.peek().litValue.toLong
          val isWrite = dut.io.fbMem.req.bits.write.peek().litToBoolean
          if (isWrite) write(a, dut.io.fbMem.req.bits.data.peek().litValue.toLong)
          fbQueue.enqueue((isWrite, a, read(a)))
        }

        def serviceLines(wordPort: Boolean): Unit = {
          val queue = if (wordPort) wordQueue else lineQueue
          val resp = if (wordPort) dut.io.kernelWordMemResp else dut.io.kernelMemResp
          val req = if (wordPort) dut.io.kernelWordMemReq else dut.io.kernelMemReq
          resp.valid.poke(queue.nonEmpty.B)
          if (queue.nonEmpty) {
            resp.bits.transactionId.poke(queue.head._1.U)
            resp.bits.readData.poke(queue.head._2.U)
            resp.bits.fault.poke(false.B)
          }
          if (queue.nonEmpty && resp.ready.peek().litToBoolean) queue.dequeue()
          if (req.valid.peek().litToBoolean && req.ready.peek().litToBoolean) {
            val a = req.bits.address.peek().litValue.toLong
            val id = req.bits.transactionId.peek().litValue
            if (req.bits.isWrite.peek().litToBoolean) {
              lineWrite(a, req.bits.writeData.peek().litValue,
                req.bits.byteMask.peek().litValue)
              queue.enqueue((id, BigInt(0)))
            } else queue.enqueue((id, lineRead(a)))
          }
        }
        serviceLines(wordPort = false)
        serviceLines(wordPort = true)
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 60000, "vertex tile replay did not drain")
      assert(read(color + (5 * 16 + 5) * 4) == 0xff0000ffL,
        f"vertex tile replay pixel (5,5) was 0x${read(color + (5 * 16 + 5) * 4)}%08x")
      assert(read(color + (11 * 16 + 11) * 4) == 0xff0000ffL,
        "the ninth triangle must survive the chunk boundary")
      assert(read(color + (14 * 16 + 3) * 4) == 0L,
        "uncovered vertex tile replay pixel must remain clear")
    }
  }
}
