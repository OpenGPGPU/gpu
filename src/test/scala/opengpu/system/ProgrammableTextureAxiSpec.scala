package opengpu.system

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.{GpuCommandMmioRegs, GraphicsConfig, RenderHostRegs}
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Exercise shader instruction fetch and vtex.sample through the host's AXI clients. */
class ProgrammableTextureAxiSpec extends AnyFlatSpec with GpuHostTestSupport {
  for (scenario <- Seq("translation", "invalid PTE", "page-table bus fault",
      "texture bus fault", "reset during texture read", "instruction translation",
      "instruction non-executable fault", "instruction invalid PTE fault", "instruction page-table bus fault",
      "instruction code bus fault", "instruction ASID switch",
      "instruction VPN flush", "instruction ASID flush",
      "instruction divergent fragment",
      "instruction guest-sized fragment",
      "instruction vertex translation", "instruction vertex non-executable fault")) {
    it should s"complete programmable texture rendering with $scenario and recover" in {
      val side = if (scenario == "instruction guest-sized fragment") 16 else 4
      val framebufferBytes = side * side * 4
      val gfx = GraphicsConfig(screenWidth = side, screenHeight = side, subPixelBits = 8)
      val gpu = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
      val vertexShader = scenario.startsWith("instruction vertex")
      simulate(new GpuHostSystemAxi(gfx, gpu, fragCore = true, vertCore = vertexShader)) { dut =>
        initialize(dut)
        val words = mutable.LongMap.empty[BigInt]
        val reads = mutable.ArrayBuffer.empty[BigInt]
        val writes = mutable.ArrayBuffer.empty[BigInt]
        val descriptor = 0x1000
        val commands = 0x2000
        val translatedInstructions = scenario.startsWith("instruction")
        val program = if (translatedInstructions) 0x1000000 else 0x3000
        val programVa = if (translatedInstructions) 0xc00000 else program
        val instructionRoot = 0x31000
        val vertexVa = 0x1800000
        val vertexPa = 0x1c00000
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
          Seq(programVa.toLong, kernarg.toLong) ++ Seq.fill(8)(0L) ++
          Seq(320L) ++ Seq.fill(5)(0L)
        assert(record.size == 40)
        store(commands, record)
        store(descriptor, Seq(1L << 16, commands.toLong, color.toLong, 0x20000L,
          side * 4L, 0x81L, textureVa.toLong, 0x10001L, 0x101L) ++ Seq.fill(7)(0L))
        store(0x20000, Seq.fill(side * side)(0x00ffffffL))
        store(program, Seq(
          0x0080006fL, 0L, // jal +8: relative control flow must retain the virtual PC
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
        // The guest's shader crosses a cache line and retires warp zero early;
        // the surviving warp samples texture and writes derivative depth.
        val divergentFragment = scenario.endsWith("fragment")
        if (divergentFragment) store(program, Seq(
          0x00241293L, 0x005082b3L, 0xc1027057L, 0x08028313L,
          0x02036087L, 0x0a028313L, 0x02036107L, 0x0620812bL,
          0x00041a63L, 0x2e1081d7L, 0x10028313L, 0x020361a7L,
          0x30500073L, 0x04028313L, 0x02036207L, 0x3210022bL,
          0x0e028313L, 0x02036227L, 0x0c028313L, 0x02036127L,
          0x30500073L))
        store(texturePa, Seq(texel))
        store(textureVa, Seq(0x00ff00ffL)) // poison physical VA alias
        store(root, Seq(0xcfL, if (scenario == "invalid PTE") 0L else (0x800L << 10) | 0x43L))
        store(instructionRoot + 12, Seq(if (scenario == "instruction invalid PTE fault") 0L else
          (0x1000L << 10) | (if (scenario == "instruction non-executable fault") 0x43L else 0x49L)))
        if (vertexShader) {
          store(instructionRoot + 24, Seq((0x1c00L << 10) |
            (if (scenario.contains("fault")) 0x43L else 0x49L)))
          store(commands, Seq(0x5000L, 3L, 32L, vertexVa.toLong, 0x6000L, 0L, 0L) ++
            Seq.fill(17)(0L) ++ Seq(programVa.toLong, kernarg.toLong) ++ Seq.fill(8)(0L) ++
            Seq(320L) ++ Seq.fill(5)(0L))
          for (v <- 0 until 3) {
            store(0x5000 + v * 32, vertices.slice(v * 4, v * 4 + 4) ++
              Seq(0xffffffffL, 16L, 0L, 0L))
          }
          store(vertexPa, Seq(0xc1007057L | 3L << 15) ++ (0 until 8).flatMap { f =>
            Seq((f * 32L) << 20 | 1L << 15 | 5L << 7 | 0x13L,
              0x02006007L | 5L << 15 | 1L << 7,
              ((8 + f) * 32L) << 20 | 1L << 15 | 5L << 7 | 0x13L,
              0x02006027L | 5L << 15 | 1L << 7)
          } ++ Seq(0x30500073L))
        }

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
        if (translatedInstructions) {
          axiWrite(dut, GpuCommandMmioRegs.INSTRUCTION_SATP,
            0x80400000 | (instructionRoot >> 12))
        }
        axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, 1)
        axiWrite(dut, RenderHostRegs.IRQ, 1)
        val fault = scenario.contains("fault") || scenario == "invalid PTE"
        val resetting = scenario.startsWith("reset")
        var resetIssued = false
        submit(17)
        serviceMemoryMaster(dut, readLine,
          a => (scenario == "page-table bus fault" && a == root + 4) ||
            (scenario == "texture bus fault" && a == texturePa) ||
            (scenario == "instruction page-table bus fault" && a == instructionRoot + 12) ||
            (scenario == "instruction code bus fault" && a == program),
          writeAckDelay = 12, maxCycles = 300000, readResponseDelay = 2,
          onReadAccepted = a => if (resetting && a == texturePa && !resetIssued) {
            resetIssued = true
            axiWrite(dut, GpuCommandMmioRegs.RESET, 1)
            for (_ <- 0 until 3) {
              assert((axiRead(dut, GpuCommandMmioRegs.STATUS) & 8) != 0,
                "reset acknowledged with an accepted texture read still outstanding")
              dut.io.m_irq.expect(false.B)
            }
          })(writeLine) { dut.io.m_irq.peek().litToBoolean }
        val instructionFault = translatedInstructions && fault
        if (!instructionFault) {
          assert(reads.contains(BigInt(root + 4)), "vtex.sample must walk the texture mapping")
        }
        if (translatedInstructions) {
          assert(reads.contains(BigInt(instructionRoot + (if (vertexShader) 24 else 12))),
            "shader must walk its code mapping")
          assert(!reads.exists(a => a >= programVa && a < programVa + 4096),
            "instruction VA escaped onto physical AXI")
          assert(!reads.exists(a => a >= vertexVa && a < vertexVa + 4096),
            "vertex instruction VA escaped onto physical AXI")
          if (instructionFault) {
            assert(!writes.exists(a => a >= color && a < color + framebufferBytes),
              "failed shader must not emit framebuffer pixels")
            if (scenario != "instruction code bus fault") {
              assert(!reads.contains(BigInt(program)), "failed translation must suppress the code fetch")
            }
          }
        }
        assert(!reads.exists(a => a >= textureVa && a < textureVa + 4096),
          "texture VA escaped onto physical AXI")
        if (scenario == "invalid PTE" || scenario == "page-table bus fault" || instructionFault) {
          assert(!reads.contains(BigInt(texturePa)), "failed walk must suppress the texel read")
        } else {
          assert(reads.contains(BigInt(texturePa)), "texture must be fetched from the translated PA")
        }
        if (!fault) {
          assert(if (divergentFragment) (0 until side * side).exists(i =>
            words.getOrElse(color + i * 4L, BigInt(0)) == texel)
            else words.getOrElse(color + 1 * 16L + 1 * 4, BigInt(0)) == texel,
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
          assert(((completion >> 11) & 15) == (if (fault) 1 else 0))
          axiWrite(dut, GpuCommandMmioRegs.COMPLETION_POP, 1)
        }
        // Repair and flush, then reuse the ID. A fresh framebuffer detects a
        // stale completion, and clearing observations proves this job did work.
        store(root + 4, Seq((0x800L << 10) | 0x43L))
        store(instructionRoot + 24, Seq((0x1c00L << 10) | 0x49L))
        val remapping = scenario == "instruction ASID switch" || scenario.endsWith("flush")
        val recoveryProgram = if (remapping) 0x1400000 else program
        if (translatedInstructions) {
          for (i <- 0 until 16) words(recoveryProgram + i * 4L) =
            words.getOrElse(program + i * 4L, BigInt(0))
          val recoveryRoot = if (scenario == "instruction ASID switch") 0x32000 else instructionRoot
          store(recoveryRoot + 12, Seq(((recoveryProgram.toLong >> 12) << 10) | 0x49L))
          if (scenario == "instruction ASID switch") {
            axiWrite(dut, GpuCommandMmioRegs.INSTRUCTION_SATP, 0x80800000 | (recoveryRoot >> 12))
          }
        }
        if (scenario == "instruction VPN flush") {
          axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, programVa | 4)
        } else if (scenario == "instruction ASID flush") {
          axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, (1 << 3) | 2)
        } else if (scenario != "instruction ASID switch") {
          axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, 1)
        }
        axiWrite(dut, RenderHostRegs.IRQ, 3)
        val recoveryColor = color + 0x1000
        store(descriptor + 8, Seq(recoveryColor.toLong, 0x21000L))
        store(recoveryColor, Seq.fill(side * side)(0x12345678L))
        store(0x21000, Seq.fill(side * side)(0x00ffffffL))
        reads.clear(); writes.clear()
        submit(17)
        serviceMemoryMaster(dut, readLine, writeAckDelay = 12, maxCycles = 300000)(writeLine) {
          dut.io.m_irq.peek().litToBoolean
        }
        assert((axiRead(dut, RenderHostRegs.STATUS) & 7) == 2)
        val completion = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
        assert((completion & 255) == 17 && ((completion >> 15) & 1) == 1)
        if (!remapping || scenario == "instruction ASID flush") {
          assert(reads.contains(BigInt(root + 4)), "recovery must walk the repaired mapping")
        }
        if (remapping) {
          assert(reads.contains(BigInt(recoveryProgram)), "relaunch must fetch the new physical code page")
        }
        assert(!reads.exists(a => a >= textureVa && a < textureVa + 4096))
        assert(writes.exists(a => a >= recoveryColor && a < recoveryColor + framebufferBytes))
        assert(if (divergentFragment) (0 until side * side).exists(i => words(recoveryColor + i * 4L) == texel)
          else words(recoveryColor + 1 * 16L + 1 * 4) == texel,
          "framebuffer must contain the sampled physical texel")
      }
    }
  }
}
