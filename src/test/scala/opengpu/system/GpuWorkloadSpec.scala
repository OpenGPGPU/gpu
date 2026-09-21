package opengpu.system

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.{GpuCommandMmioRegs, GraphicsConfig, RenderHostRegs}
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Opt-in reproducible workload sweeps. Run through scripts/benchmark_gpu.py. */
class GpuWorkloadSpec extends AnyFlatSpec with GpuHostTestSupport {
  behavior of "GPU workload"
  private val enabled = sys.env.get("GPU_BENCHMARK").contains("1")
  private val cases = for (size <- Seq(16, 32); mode <- Seq(0, 1, 2))
    yield (s"flat_${size}_${1 << mode}x", size, mode, false, false, 1)
  private val workloads = cases ++ Seq(
    ("shader_16_1x", 16, 0, true, false, 1),
    ("shader_16_4x", 16, 2, true, false, 1),
    ("texture_16_1x", 16, 0, false, true, 1),
    ("overdraw_16_1x", 16, 0, false, false, 4))
  for ((name, size, mode, programmable, textured, draws) <- workloads if enabled &&
    sys.env.get("GPU_BENCHMARK_CASES").forall(_.split(",").contains(name))) {
    it should s"measure $name" in {
      val gpu = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
      val gfx = GraphicsConfig(screenWidth = size, screenHeight = size)
      simulate(new GpuHostSystemAxi(gfx, gpu, fragCore = programmable,
        exposePerformance = true)) { dut =>
        initialize(dut)
        val words = mutable.Map.empty[BigInt, BigInt]
        def store(base: Int, values: Seq[Long]): Unit = values.zipWithIndex.foreach {
          case (value, i) => words(BigInt(base + i * 4)) = BigInt(value & 0xffffffffL)
        }
        val stride = size * (1 << mode) * 4
        val descriptorBase = 0x1000
        val commandBase = 0x2000
        val colorBase = 0x10000
        val depthBase = 0x20000
        val programBase = 0x3000
        val kernargBase = 0x4000
        val textureBase = 0x6000
        val vertices = Seq(-65536L, -65536L, 0L, 65536L,
          65536L, -65536L, 0L, 65536L, -65536L, 65536L, 0L, 65536L)
        val record = vertices ++ Seq.fill(3)(Seq(255L, 0L, 0L)).flatten ++
          Seq.fill(3)(16L) ++ Seq(if (programmable) programBase.toLong else 0L,
            if (programmable) kernargBase.toLong else 0L) ++
          Seq.fill(6)(0L) ++ Seq(0L, 0L, if (programmable) 320L else 0L) ++ Seq.fill(5)(0L)
        assert(record.size == 40)
        for (i <- 0 until draws) store(commandBase + i * 160, record)
        store(descriptorBase, Seq(draws.toLong << 16, commandBase.toLong,
          colorBase.toLong, depthBase.toLong, stride.toLong, 0x81L,
          textureBase.toLong, 0x00010001L, if (textured) 0x101L else 0L, mode.toLong) ++ Seq.fill(6)(0L))
        store(colorBase, Seq.fill(size * size * (1 << mode))(0x12345678L))
        store(depthBase, Seq.fill(size * size * (1 << mode))(0x00ffffffL))
        store(textureBase, Seq(0xff0000ffL))
        val arrayStride = 32L // 4 lanes * 2 warps * 4-byte words
        store(programBase, Seq(
          2L << 20 | 8L << 15 | 1L << 12 | 5L << 7 | 0x13L,
          5L << 20 | 1L << 15 | 5L << 7 | 0x33L,
          0xc1007057L | 4L << 15,
          (3 * arrayStride) << 20 | 5L << 15 | 6L << 7 | 0x13L,
          0x02006007L | 6L << 15 | 2L << 7,
          (6 * arrayStride) << 20 | 5L << 15 | 6L << 7 | 0x13L,
          0x02006027L | 6L << 15 | 2L << 7,
          0x30500073L))
        // Enable Sv32 using an identity superpage. This measures actual TLB
        // miss/walk costs on command, framebuffer and texture clients.
        store(0x7000, Seq(0xcfL)) // V/R/W/X/A/D, physical superpage 0
        axiWrite(dut, GpuCommandMmioRegs.VECTOR_SATP, 0x80400007)
        axiWrite(dut, GpuCommandMmioRegs.INSTRUCTION_SATP, 0x80400007)
        def readLine(address: BigInt): BigInt = {
          val base = address & ~BigInt(63)
          (0 until 16).foldLeft(BigInt(0)) { (line, i) =>
            line | (words.getOrElse(base + i * 4, BigInt(0)) << (32 * i))
          }
        }
        def writeLine(address: BigInt, data: BigInt, mask: BigInt): Unit = {
          for (i <- 0 until 64 if mask.testBit(i)) {
            val a = address + i
            val aligned = a & ~BigInt(3)
            val shift = (a.toInt & 3) * 8
            val old = words.getOrElse(aligned, BigInt(0))
            words(aligned) = (old & ~(BigInt(255) << shift)) |
              (((data >> (i * 8)) & 255) << shift)
          }
        }
        axiWrite(dut, RenderHostRegs.IRQ, 1)
        axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 1)
        axiWrite(dut, GpuCommandMmioRegs.OPCODE, GpuCommandOpcode.render.litValue.toInt)
        axiWrite(dut, GpuCommandMmioRegs.SOURCE, descriptorBase)
        axiWrite(dut, GpuCommandMmioRegs.BYTES, 64)
        def snapshot(): Map[String, BigInt] = {
          val p = dut.io.performance.get
          Map("cycles" -> p.system.cycles.peek().litValue,
            "om_stall_cycles" -> p.omStallCycles.peek().litValue,
            "om_conflict_cycles" -> p.omConflictCycles.peek().litValue,
            "raster_stall_cycles" -> p.rasterStallCycles.peek().litValue,
            "staging_read_bytes" -> p.stagingReadBytes.peek().litValue,
            "staging_write_bytes" -> p.stagingWriteBytes.peek().litValue,
            "lower_read_bytes" -> p.lowerReadBytes.peek().litValue,
            "lower_write_bytes" -> p.lowerWriteBytes.peek().litValue,
            "l2_load_misses" -> p.system.l2.loadMisses.peek().litValue,
            "l2_load_hits" -> p.system.l2.loadHits.peek().litValue,
            "l2_mshr_merges" -> p.system.l2.mshrMerges.peek().litValue) ++
            Seq("staging", "command", "framebuffer", "texture").zipWithIndex.flatMap { case (client, i) =>
              Seq(s"${client}_tlb_misses" -> p.translationMisses(i).peek().litValue,
                s"${client}_translation_stall_cycles" -> p.translationStallCycles(i).peek().litValue,
                s"${client}_walk_cycles" -> p.walkCycles(i).peek().litValue)
            }
        }
        val before = snapshot()
        axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
        serviceMemoryMaster(dut, readLine, writeAckDelay = 4, maxCycles = 500000)(writeLine) {
          dut.io.m_irq.peek().litToBoolean
        }
        val after = snapshot()
        assert((axiRead(dut, RenderHostRegs.STATUS) & 7) == 2, "render must complete successfully")
        val pixel = colorBase + (size / 3) * stride + (size / 3) * (1 << mode) * 4
        // Non-textured draws pass the interpolated vertex colour through
        // unchanged.  Texturing applies the MODULATE convention
        // `(frag * texel)(15, 8)`, so a full-scale red fragment against a
        // full-scale red texel yields 0xff * 0xff >> 8 == 0xfe.  Either way any
        // other value means the expected source never reached the framebuffer.
        val expected = if (textured) 0xfe0000ffL else 0xff0000ffL
        assert(words(BigInt(pixel)) == expected,
          s"covered pixel differs: ${words(BigInt(pixel))} (expected $expected)")
        assert(words(BigInt(colorBase)) == 0x12345678L, "uncovered pixel was overwritten")
        val counters = after.toSeq.sortBy(_._1).map { case (key, value) =>
          "\"" + key + "\":" + (value - before(key))
        }
        println("GPU_BENCHMARK_RESULT {\"workload\":\"" + name + "\",\"size\":" + size +
          ",\"samples\":" + (1 << mode) + "," + counters.mkString(",") + "}")
      }
    }
  }
}
