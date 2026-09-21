package opengpu.system

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.{GpuCommandMmioRegs, GraphicsConfig, RenderHostRegs}
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Exercise vtex.sample all the way through the host's translated AXI client. */
class ProgrammableTextureAxiSpec extends AnyFlatSpec with GpuHostTestSupport {
  for (scenario <- Seq("translation", "invalid PTE", "page-table bus fault",
      "texture bus fault", "reset during texture read")) {
    it should s"complete programmable texture rendering with $scenario and recover" in {
      val gfx = GraphicsConfig(screenWidth = 4, screenHeight = 4, subPixelBits = 8)
      val gpu = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
      simulate(new GpuHostSystemAxi(gfx, gpu, fragCore = true)) { dut =>
        initialize(dut)
        val words = mutable.LongMap.empty[BigInt]
        val reads = mutable.ArrayBuffer.empty[BigInt]
        val writes = mutable.ArrayBuffer.empty[BigInt]
        val descriptor = 0x1000
        val commands = 0x2000
        val program = 0x3000
        val kernarg = 0x4000
        val color = 0x10000
        val root = 0x30000
        val textureVa = 0x400000
        val texturePa = 0x800000
        val texel = 0xff00ffffL
        def store(base: Int, data: Seq[Long]): Unit = data.zipWithIndex.foreach {
          case (v, i) => words(base.toLong + i * 4) = BigInt(v & 0xffffffffL)
        }
        val vertices = Seq(-65536L, -65536L, 0L, 65536L,
          65536L, -65536L, 0L, 65536L, -65536L, 65536L, 0L, 65536L)
        // ABI 0: eight lanes' worth of SoA staging; u/v at 128/160,
        // output colour at 192. Two warps use disjoint four-lane slices.
        // The 4x4 draw fills both eight-lane batches, exercising retirement
        // when the final full batch is parked behind the executing shader.
        val record = vertices ++ Seq.fill(9)(255L) ++ Seq.fill(3)(16L) ++
          Seq(program.toLong, kernarg.toLong) ++ Seq.fill(8)(0L) ++
          Seq(320L) ++ Seq.fill(5)(0L)
        assert(record.size == 40)
        store(commands, record)
        store(descriptor, Seq(1L << 16, commands.toLong, color.toLong, 0x20000L,
          16L, 0x81L, textureVa.toLong, 0x10001L, 0x101L) ++ Seq.fill(7)(0L))
        store(0x20000, Seq.fill(16)(0x00ffffffL))
        store(program, Seq(
          2L << 20 | 8L << 15 | 1L << 12 | 5L << 7 | 0x13L, // slli t0,s0,2
          5L << 20 | 1L << 15 | 5L << 7 | 0x33L, // add t0,ra,t0
          0xc1007057L | 4L << 15, // vsetivli 4
          128L << 20 | 5L << 15 | 6L << 7 | 0x13L,
          0x02006007L | 6L << 15 | 1L << 7, // vle32 v1,u
          160L << 20 | 5L << 15 | 6L << 7 | 0x13L,
          0x02006007L | 6L << 15 | 2L << 7, // vle32 v2,v
          1L << 26 | 1L << 25 | 2L << 20 | 1L << 15 | 3L << 7 | 0x2bL,
          192L << 20 | 5L << 15 | 6L << 7 | 0x13L,
          0x02006027L | 6L << 15 | 3L << 7, // vse32 sampled colour
          0x30500073L))
        store(texturePa, Seq(texel))
        store(textureVa, Seq(0x00ff00ffL)) // poison physical VA alias
        store(root, Seq(0xcfL, if (scenario == "invalid PTE") 0L else (0x800L << 10) | 0x43L))
        def readLine(addr: BigInt): BigInt = {
          reads += addr
          val base = addr.toLong & ~63L
          (0 until 16).foldLeft(BigInt(0))((line, i) =>
            line | (words.getOrElse(base + i * 4, BigInt(0)) << (32 * i)))
        }
        def writeLine(addr: BigInt, data: BigInt, mask: BigInt): Unit = {
          writes += addr
          val base = addr.toLong & ~63L
          for (b <- 0 until 64 if mask.testBit(b)) {
            val a = base + (b / 4) * 4
            val shift = (b % 4) * 8
            words(a) = (words.getOrElse(a, BigInt(0)) & ~(BigInt(255) << shift)) |
              (((data >> (b * 8)) & 255) << shift)
          }
        }
        def submit(id: Int): Unit = {
          axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, id)
          axiWrite(dut, GpuCommandMmioRegs.OPCODE, GpuCommandOpcode.render.litValue.toInt)
          axiWrite(dut, GpuCommandMmioRegs.SOURCE, descriptor)
          axiWrite(dut, GpuCommandMmioRegs.BYTES, 64)
          axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
        }
        axiWrite(dut, GpuCommandMmioRegs.VECTOR_SATP, 0x80400000 | (root >> 12))
        axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, 1)
        axiWrite(dut, RenderHostRegs.IRQ, 1)
        val fault = scenario.contains("fault") || scenario == "invalid PTE"
        val resetting = scenario.startsWith("reset")
        var resetIssued = false
        submit(17)
        serviceMemoryMaster(dut, readLine,
          a => (scenario == "page-table bus fault" && a == root + 4) ||
            (scenario == "texture bus fault" && a == texturePa),
          writeAckDelay = 12, maxCycles = 40000, readResponseDelay = 2,
          onReadAccepted = a => if (resetting && a == texturePa && !resetIssued) {
            resetIssued = true
            axiWrite(dut, GpuCommandMmioRegs.RESET, 1)
            for (_ <- 0 until 3) {
              assert((axiRead(dut, GpuCommandMmioRegs.STATUS) & 8) != 0,
                "reset acknowledged with an accepted texture read still outstanding")
              dut.io.m_irq.expect(false.B)
            }
          })(writeLine) { dut.io.m_irq.peek().litToBoolean }
        assert(reads.contains(BigInt(root + 4)), "vtex.sample must walk the texture mapping")
        assert(!reads.exists(a => a >= textureVa && a < textureVa + 4096),
          "texture VA escaped onto physical AXI")
        if (scenario == "invalid PTE" || scenario == "page-table bus fault") {
          assert(!reads.contains(BigInt(texturePa)), "failed walk must suppress the texel read")
        } else {
          assert(reads.contains(BigInt(texturePa)), "texture must be fetched from the translated PA")
        }
        if (!fault) {
          assert(words.getOrElse(color + 1 * 16L + 1 * 4, BigInt(0)) == texel,
            "successful render/reset drain must commit sampled colour")
        }
        if (resetting) {
          assert(resetIssued)
          assert((axiRead(dut, GpuCommandMmioRegs.STATUS) & 10) == 0,
            "reset must drain and discard the old completion")
        } else {
          assert((axiRead(dut, RenderHostRegs.STATUS) & 7) == (if (fault) 6 else 2))
          val completion = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
          assert((completion & 255) == 17)
          assert(((completion >> 15) & 1) == (if (fault) 0 else 1))
          axiWrite(dut, GpuCommandMmioRegs.COMPLETION_POP, 1)
        }
        // Repair and flush, then reuse the ID. A fresh framebuffer detects a
        // stale completion, and clearing observations proves this job did work.
        store(root + 4, Seq((0x800L << 10) | 0x43L))
        axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, 1)
        axiWrite(dut, RenderHostRegs.IRQ, 3)
        val recoveryColor = color + 0x1000
        store(descriptor + 8, Seq(recoveryColor.toLong, 0x21000L))
        store(recoveryColor, Seq.fill(16)(0x12345678L))
        store(0x21000, Seq.fill(16)(0x00ffffffL))
        reads.clear(); writes.clear()
        submit(17)
        serviceMemoryMaster(dut, readLine, writeAckDelay = 12, maxCycles = 40000)(writeLine) {
          dut.io.m_irq.peek().litToBoolean
        }
        assert((axiRead(dut, RenderHostRegs.STATUS) & 7) == 2)
        val completion = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
        assert((completion & 255) == 17 && ((completion >> 15) & 1) == 1)
        assert(reads.contains(BigInt(root + 4)), "recovery must walk the repaired mapping")
        assert(!reads.exists(a => a >= textureVa && a < textureVa + 4096))
        assert(writes.exists(a => a >= recoveryColor && a < recoveryColor + 64))
        assert(words(recoveryColor + 1 * 16L + 1 * 4) == texel,
          "framebuffer must contain the sampled physical texel")
      }
    }
  }
}
