package opengpu.system

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.{GpuCommandMmioRegs, GraphicsConfig, RenderHostRegs}
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

class GpuHostSystemAxiSpec extends AnyFlatSpec with GpuHostTestSupport {
  behavior of "GpuHostSystemAxi"

  it should "route an AXI-programmed clear through the shared L2" in {
    val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16)
    val gpu = GpuConfig(
      lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuHostSystemAxi(gfx, gpu)) { dut =>
      initialize(dut)

      val base = 0x6000
      val pattern = BigInt("12345678", 16)
      val expectedLine = BigInt(List.fill(16)("12345678").mkString, 16)
      axiWrite(dut, RenderHostRegs.CLEAR_BASE, base)
      axiWrite(dut, RenderHostRegs.CLEAR_BYTES, 64)
      axiWrite(dut, RenderHostRegs.CLEAR_PATTERN, pattern.toInt)

      axiWrite(dut, RenderHostRegs.CLEAR_START, 1)
      val write = acceptMemoryWrite(dut)
      assert(write.address == base)
      assert(write.mask == (BigInt(1) << 64) - 1)
      assert(write.data == expectedLine)

      var cycles = 0
      var status = axiRead(dut, RenderHostRegs.STATUS)
      while ((status & 0x8L) != 0L && cycles < 40) {
        status = axiRead(dut, RenderHostRegs.STATUS)
        cycles += 1
      }
      assert(cycles < 40, "clear did not retire through the shared L2")
      assert((status & 0xcL) == 0L,
        f"clear must finish without BUSY or ERROR set, status=0x$status%x")
    }
  }

  it should "program the Sv32 page-table base and flush the TLBs over AXI" in {
    val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16)
    val gpu = GpuConfig(l2Sets = 8, l2Ways = 2)
    simulate(new GpuHostSystemAxi(gfx, gpu)) { dut =>
      initialize(dut)
      // Sv32 mode (bit 31), ASID 0, root PPN 0x12345.
      val satp = 0x80012345
      axiWrite(dut, GpuCommandMmioRegs.VECTOR_SATP, satp)
      axiWrite(dut, GpuCommandMmioRegs.INSTRUCTION_SATP, satp)
      axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, 1)
      val expected = BigInt("80012345", 16)
      assert(axiRead(dut, GpuCommandMmioRegs.VECTOR_SATP) == expected,
        "vector satp must read back")
      assert(axiRead(dut, GpuCommandMmioRegs.INSTRUCTION_SATP) == expected,
        "instruction satp must read back")
    }
  }

  it should "deliver unified-command completions through the shared IRQ" in {
    val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16)
    val gpu = GpuConfig(
      lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuHostSystemAxi(gfx, gpu)) { dut =>
      initialize(dut)
      assert((axiRead(dut, RenderHostRegs.CAPABILITIES) & (1 << 6)) != 0L,
        "integrated host must advertise unified-command MMIO")
      axiWrite(dut, RenderHostRegs.IRQ, 1)
      axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 9)
      axiWrite(dut, GpuCommandMmioRegs.OPCODE,
        GpuCommandOpcode.fill.litValue.toInt)
      axiWrite(dut, GpuCommandMmioRegs.DESTINATION, 0x7000)
      axiWrite(dut, GpuCommandMmioRegs.BYTES, 64)
      axiWrite(dut, GpuCommandMmioRegs.PATTERN, 0x89abcdef)
      axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)

      val write = acceptMemoryWrite(dut)
      assert(write.address == 0x7000)

      var cycles = 0
      var commandStatus = axiRead(dut, GpuCommandMmioRegs.STATUS)
      while ((commandStatus & 0x2L) == 0L && cycles < 40) {
        commandStatus = axiRead(dut, GpuCommandMmioRegs.STATUS)
        cycles += 1
      }
      assert((commandStatus & 0x2L) != 0L,
        "unified completion did not reach the MMIO result slot")
      val completion = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
      assert((completion & 0xffL) == 9L)
      assert(((completion >> 8) & 0x7L) ==
        GpuCommandOpcode.fill.litValue)
      assert(((completion >> 15) & 1L) == 1L)
      assert(axiRead(dut, GpuCommandMmioRegs.COMPLETION_BYTES_LO) == 64L)
      assert(axiRead(dut, GpuCommandMmioRegs.COMPLETION_BYTES_HI) == 0L)
      dut.io.m_irq.expect(true.B)
      assert((axiRead(dut, RenderHostRegs.IRQ) & 0x3L) == 0x3L)

      axiWrite(dut, GpuCommandMmioRegs.COMPLETION_POP, 1)
      axiWrite(dut, RenderHostRegs.IRQ, 3)
      dut.io.m_irq.expect(false.B)
      assert((axiRead(dut, RenderHostRegs.IRQ) & 0x3L) == 0x1L)
    }
  }

  it should "complete a unified reset through the AXI control path" in {
    val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16)
    val gpu = GpuConfig(
      lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuHostSystemAxi(gfx, gpu)) { dut =>
      initialize(dut)
      assert((axiRead(dut, RenderHostRegs.CAPABILITIES) & (1 << 18)) != 0L,
        "integrated host must advertise the safe unified-command reset")
      axiWrite(dut, RenderHostRegs.IRQ, 1)

      // Idle command path: the drain completes and acknowledges by IRQ.
      axiWrite(dut, GpuCommandMmioRegs.RESET, 1)
      var cycles = 0
      var status = axiRead(dut, GpuCommandMmioRegs.STATUS)
      while ((status & 0x8L) != 0L && cycles < 40) {
        status = axiRead(dut, GpuCommandMmioRegs.STATUS)
        cycles += 1
      }
      assert((status & 0x8L) == 0L, "unified reset was never acknowledged")
      dut.io.m_irq.expect(true.B)

      // The reset leaves a clean, ready command slot behind.
      assert((status & 0x1L) != 0L, "command queue must report ready")
      axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 3)
      axiWrite(dut, GpuCommandMmioRegs.OPCODE,
        GpuCommandOpcode.fill.litValue.toInt)
      axiWrite(dut, GpuCommandMmioRegs.DESTINATION, 0x8000)
      axiWrite(dut, GpuCommandMmioRegs.BYTES, 64)
      axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
      val write = acceptMemoryWrite(dut)
      assert(write.address == 0x8000,
        "post-reset submission must reach the memory master")
    }
  }

  it should "reject work while a unified reset drains and recover afterwards" in {
    val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16)
    val gpu = GpuConfig(
      lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuHostSystemAxi(gfx, gpu)) { dut =>
      initialize(dut)
      axiWrite(dut, RenderHostRegs.IRQ, 1)

      // Start a fill whose memory transaction is deliberately left unserviced
      // so the command path stays busy while the reset drains.  This mirrors
      // the driver's recovery contract: RESET_BUSY maps to -EBUSY and a
      // submission racing the drain must be refused, not queued.
      axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 5)
      axiWrite(dut, GpuCommandMmioRegs.OPCODE,
        GpuCommandOpcode.fill.litValue.toInt)
      axiWrite(dut, GpuCommandMmioRegs.DESTINATION, 0x6000)
      axiWrite(dut, GpuCommandMmioRegs.BYTES, 64)
      axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)

      var cycles = 0
      while (!dut.io.m_axi_awvalid.peek().litToBoolean && cycles < 80) {
        dut.io.s_axi_aclk.step()
        cycles += 1
      }
      assert(cycles < 80, "fill never reached the memory master")

      axiWrite(dut, GpuCommandMmioRegs.RESET, 1)
      val draining = axiRead(dut, GpuCommandMmioRegs.STATUS)
      assert((draining & 0x8L) != 0L,
        "reset must report BUSY while in-flight work drains")

      // A submission during the drain is rejected and must not be executed.
      axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 6)
      axiWrite(dut, GpuCommandMmioRegs.DESTINATION, 0x7000)
      axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
      val rejected = axiRead(dut, GpuCommandMmioRegs.STATUS)
      assert((rejected & 0x10L) != 0L,
        "reset must report the refused submission")

      // Let the outstanding fill finish; the drain then acknowledges by IRQ.
      val inFlight = acceptMemoryWrite(dut)
      assert(inFlight.address == 0x6000)
      cycles = 0
      var status = axiRead(dut, GpuCommandMmioRegs.STATUS)
      while ((status & 0x8L) != 0L && cycles < 40) {
        status = axiRead(dut, GpuCommandMmioRegs.STATUS)
        cycles += 1
      }
      assert((status & 0x8L) == 0L, "unified reset never drained")
      assert((status & 0x1L) != 0L, "reset must leave a ready command slot")
      dut.io.m_irq.expect(true.B)

      // The refused command must not have reached memory; the next one does.
      axiWrite(dut, GpuCommandMmioRegs.STATUS, 0x10)
      axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 7)
      axiWrite(dut, GpuCommandMmioRegs.DESTINATION, 0x8000)
      axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
      val recovered = acceptMemoryWrite(dut)
      assert(recovered.address == 0x8000,
        "post-reset submission must reach the memory master")
    }
  }

  it should "execute a unified kernel through the AXI memory master" in {
    val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16)
    val gpu = GpuConfig(l2Sets = 8, l2Ways = 2)
    simulate(new GpuHostSystemAxi(gfx, gpu)) { dut =>
      initialize(dut)

      val programBase = 0x4000
      val kernargBase = 0x8000
      val lw = BigInt("0000a483", 16)
      val sw = BigInt("0090a223", 16)
      val cease = BigInt("30500073", 16)
      val programLine = lw | (sw << 32) | (cease << 64)
      val input = BigInt("cafe0001", 16)

      axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 11)
      axiWrite(dut, GpuCommandMmioRegs.OPCODE,
        GpuCommandOpcode.kernel.litValue.toInt)
      axiWrite(dut, GpuCommandMmioRegs.KERNEL_PC, programBase)
      axiWrite(dut, GpuCommandMmioRegs.KERNARG, kernargBase)
      Seq(GpuCommandMmioRegs.GRID_X, GpuCommandMmioRegs.GRID_Y,
        GpuCommandMmioRegs.GRID_Z, GpuCommandMmioRegs.LOCAL_X,
        GpuCommandMmioRegs.LOCAL_Y, GpuCommandMmioRegs.LOCAL_Z).foreach {
          address => axiWrite(dut, address, 1)
        }
      axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)

      val instructionRead = acceptMemoryRead(dut, programLine)
      assert(instructionRead._1 == programBase)
      val kernargRead = acceptMemoryRead(dut, input)
      assert(kernargRead._1 == kernargBase)
      val kernargWrite = acceptMemoryWrite(dut)
      assert(kernargWrite.address == kernargBase)
      assert((kernargWrite.mask & 0xf0) == 0xf0)
      assert(((kernargWrite.data >> 32) & 0xffffffffL) == input)

      var cycles = 0
      var status = axiRead(dut, GpuCommandMmioRegs.STATUS)
      while ((status & 0x2L) == 0L && cycles < 80) {
        status = axiRead(dut, GpuCommandMmioRegs.STATUS)
        cycles += 1
      }
      assert((status & 0x2L) != 0L, "kernel completion did not reach MMIO")
      val completion = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
      assert((completion & 0xffL) == 11L)
      assert(((completion >> 8) & 0x7L) ==
        GpuCommandOpcode.kernel.litValue)
      assert(((completion >> 15) & 1L) == 1L)
    }
  }

  it should "resolve a multi-row 4x region through the AXI unified command path" in {
    val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16)
    val gpu = GpuConfig(l2Sets = 8, l2Ways = 2)
    simulate(new GpuHostSystemAxi(gfx, gpu)) { dut =>
      initialize(dut)
      val srcBase = 0x1000L
      val dstBase = 0x2000L
      val srcStride = 64L
      val dstStride = 64L
      val width = 4
      val height = 2
      val samples = Seq(BigInt(0), BigInt("ffffffff", 16),
        BigInt("ff00ff00", 16), BigInt("00ff00ff", 16))
      val words = mutable.LongMap[BigInt]()
      for (y <- 0 until height; x <- 0 until width; s <- 0 until 4)
        words(srcBase + y * srcStride + (x * 4 + s) * 4L) = samples(s)
      def readLine(addr: BigInt): BigInt = {
        val base = addr.toLong & ~63L
        (0 until 16)
          .map(i => words.getOrElse(base + i * 4L, BigInt(0)) << (32 * i))
          .reduce(_ | _)
      }
      def writeLine(addr: BigInt, data: BigInt, strb: BigInt): Unit = {
        val base = addr.toLong & ~63L
        for (i <- 0 until 16) {
          var w = words.getOrElse(base + i * 4L, BigInt(0))
          for (b <- 0 until 4) {
            val byte = i * 4 + b
            if (((strb >> byte) & 1) != 0)
              w = (w & ~(BigInt(0xff) << (b * 8))) |
                (((data >> (byte * 8)) & 0xff) << (b * 8))
          }
          words(base + i * 4L) = w
        }
      }

      axiWrite(dut, RenderHostRegs.IRQ, 1)
      axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 7)
      axiWrite(dut, GpuCommandMmioRegs.OPCODE,
        GpuCommandOpcode.resolve.litValue.toInt)
      axiWrite(dut, GpuCommandMmioRegs.SOURCE, srcBase.toInt)
      axiWrite(dut, GpuCommandMmioRegs.DESTINATION, dstBase.toInt)
      axiWrite(dut, GpuCommandMmioRegs.WIDTH, width)
      axiWrite(dut, GpuCommandMmioRegs.HEIGHT, height)
      axiWrite(dut, GpuCommandMmioRegs.SOURCE_STRIDE, srcStride.toInt)
      axiWrite(dut, GpuCommandMmioRegs.DESTINATION_STRIDE, dstStride.toInt)
      axiWrite(dut, GpuCommandMmioRegs.SAMPLE_MODE, 2)
      axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)

      serviceMemoryMaster(dut, readLine)(writeLine) {
        dut.io.m_irq.peek().litToBoolean
      }

      dut.io.m_irq.expect(true.B)
      val completion = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
      assert((completion & 0xffL) == 7L, s"completion id was $completion")
      assert(((completion >> 8) & 0x7L) ==
        GpuCommandOpcode.resolve.litValue)
      assert(((completion >> 15) & 1L) == 1L)
      assert(axiRead(dut, GpuCommandMmioRegs.COMPLETION_BYTES_LO) == 32L)
      for (y <- 0 until height; x <- 0 until width) {
        val got = words.getOrElse(dstBase + y * dstStride + x * 4L, BigInt(0))
        assert(got == BigInt(0x80808080L),
          s"dst($x,$y)=0x${got.toString(16)} expected 0x80808080")
      }
    }
  }

  for ((busFault, textureBusFault) <- Seq(
    (false, false), (true, false), (false, true))) {
    it should s"report texture faults to the host and recover (pageBusFault=$busFault, textureBusFault=$textureBusFault)" in {
      val gfx = GraphicsConfig(screenWidth = 4, screenHeight = 4, subPixelBits = 8)
      val gpu = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
      simulate(new GpuHostSystemAxi(gfx, gpu)) { dut =>
        initialize(dut)
        val words = mutable.LongMap[BigInt]()
        val reads = mutable.ArrayBuffer[BigInt]()
        val cmdBase = 0x4000
        val colorBase = 0x8000
        val depthBase = 0x9000
        val rootBase = 0x30000
        val textureVa = 0x400000
        val texturePa = 0x800000
        val draw = Array.fill(44)(0)
        Seq((-65536, -65536), (65536, -65536), (-65536, 65536))
          .zipWithIndex.foreach { case ((x, y), i) =>
            draw(i * 4) = x; draw(i * 4 + 1) = y
            draw(i * 4 + 3) = 65536
            for (c <- 0 until 3) draw(12 + i * 3 + c) = 255
          }
        def ww(address: Long, value: Int): Unit =
          words(address) = BigInt(value.toLong & 0xffffffffL)
        draw.zipWithIndex.foreach { case (v, i) => ww(cmdBase + 4L * i, v) }
        ww(texturePa, 0xff00ffff)
        // The command-draw port translates through the same page tables, so
        // the low 4 MiB must identity-map uncached exactly as the driver's
        // global identity map does; the texture VA aliases on top.
        ww(rootBase, (2 << 8) | 0xcf)
        if (textureBusFault) ww(rootBase + 4, (0x800 << 10) | 0x43)
        def readLine(address: BigInt): BigInt = {
          reads += address
          val base = address.toLong & ~63L
          (0 until 16).map(i => words.getOrElse(base + i * 4L, BigInt(0)) << (i * 32))
            .reduce(_ | _)
        }
        def writeLine(address: BigInt, data: BigInt, mask: BigInt): Unit = {
          val base = address.toLong & ~63L
          for (b <- 0 until 64 if mask.testBit(b)) {
            val word = base + (b / 4) * 4L
            val shift = (b % 4) * 8
            words(word) = (words.getOrElse(word, BigInt(0)) & ~(BigInt(255) << shift)) |
              (((data >> (b * 8)) & 255) << shift)
          }
        }
        axiWrite(dut, GpuCommandMmioRegs.VECTOR_SATP, 0x80000000 | (rootBase >> 12))
        axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, 1)
        axiWrite(dut, RenderHostRegs.IRQ, 1)
        // Unified render descriptor: cmd/colour/depth/stride, depth test+write,
        // and a 1x1 texture at the virtual address the translator resolves.
        val descriptorBase = 0x50000
        val descriptor = Seq((1 << 16) | 1, cmdBase, colorBase, depthBase,
          16, 0x81, textureVa, (1 << 16) | 1, 0x101) ++ Seq.fill(7)(0)
        descriptor.zipWithIndex.foreach { case (v, i) =>
          ww(descriptorBase + i * 4L, v)
        }
        def submitRender(id: Int): Unit = {
          axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, id)
          axiWrite(dut, GpuCommandMmioRegs.OPCODE,
            GpuCommandOpcode.render.litValue.toInt)
          axiWrite(dut, GpuCommandMmioRegs.SOURCE, descriptorBase)
          axiWrite(dut, GpuCommandMmioRegs.BYTES, 64)
          axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
        }
        submitRender(1)
        serviceMemoryMaster(dut, readLine,
          address => (busFault && address == rootBase + 4) ||
            (textureBusFault && address == texturePa))(writeLine) {
          dut.io.m_irq.peek().litToBoolean
        }
        assert(reads.contains(BigInt(rootBase + 4)), "texture must attempt a page walk")
        assert(!reads.exists(a => a >= textureVa && a < textureVa + 4096),
          "faulted virtual address must never reach physical memory")
        assert((axiRead(dut, RenderHostRegs.STATUS) & 7) == 6, "DONE and ERROR, not BUSY")
        // Pop the unified completion before the next submission.
        axiWrite(dut, GpuCommandMmioRegs.COMPLETION_POP, 1)
        // Repair the PTE and run again: per-job failure cannot poison success.
        ww(rootBase + 4, (0x800 << 10) | 0x43)
        axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, 1)
        axiWrite(dut, RenderHostRegs.IRQ, 3)
        submitRender(2)
        serviceMemoryMaster(dut, readLine)(writeLine) {
          dut.io.m_irq.peek().litToBoolean
        }
        assert(reads.contains(BigInt(texturePa)), "repaired mapping must access translated PA")
        assert((axiRead(dut, RenderHostRegs.STATUS) & 7) == 2)
      }
    }
  }

  it should "drain a unified render and delayed writes before acknowledging reset" in {
    val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16)
    val gpu = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuHostSystemAxi(gfx, gpu)) { dut =>
      initialize(dut)
      val words = mutable.Map.empty[BigInt, BigInt]
      def store(base: Int, data: Seq[Long]): Unit =
        data.zipWithIndex.foreach { case (value, i) =>
          words(BigInt(base + i * 4)) = BigInt(value & 0xffffffffL)
        }
      val vertices = Seq(-65536L, -65536L, 0L, 65536L,
        65536L, -65536L, 0L, 65536L, -65536L, 65536L, 0L, 65536L)
      store(0x2000, vertices ++ Seq.fill(3)(Seq(255L, 0L, 0L)).flatten ++
        Seq.fill(3)(16L) ++ Seq.fill(16)(0L))
      store(0x1000, Seq(1L << 16, 0x2000L, 0x8000L, 0x9000L,
        64L, 0L) ++ Seq.fill(10)(0L))
      def readLine(address: BigInt): BigInt = {
        val base = address & ~BigInt(63)
        (0 until 16).foldLeft(BigInt(0)) { (line, i) =>
          line | (words.getOrElse(base + i * 4, BigInt(0)) << (32 * i))
        }
      }
      var writes = 0
      def writeLine(address: BigInt, data: BigInt, mask: BigInt): Unit = {
        writes += 1
        for (i <- 0 until 16 if ((mask >> (i * 4)) & 15) == 15)
          words(address + i * 4) = (data >> (32 * i)) & 0xffffffffL
      }
      axiWrite(dut, RenderHostRegs.IRQ, 1)
      axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 11)
      axiWrite(dut, GpuCommandMmioRegs.OPCODE, GpuCommandOpcode.render.litValue.toInt)
      axiWrite(dut, GpuCommandMmioRegs.SOURCE, 0x1000)
      axiWrite(dut, GpuCommandMmioRegs.BYTES, 64)
      axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
      // Descriptor fetch has begun; memory has not responded yet. The reset
      // must cover subsequent rasterization and stores, including L2-idle gaps.
      axiWrite(dut, GpuCommandMmioRegs.RESET, 1)
      assert((axiRead(dut, GpuCommandMmioRegs.STATUS) & 8) != 0)
      serviceMemoryMaster(dut, readLine, writeAckDelay = 12)(writeLine) {
        dut.io.m_irq.peek().litToBoolean
      }
      assert(writes > 0, "reset acknowledged before the render reached memory")
      assert(words.getOrElse(BigInt(0x8000 + 5 * 64 + 5 * 4), BigInt(0)) == 0xff0000ffL)
      assert((axiRead(dut, RenderHostRegs.STATUS) & 1) == 0, "render still busy after reset")
      assert((axiRead(dut, GpuCommandMmioRegs.STATUS) & 10) == 0,
        "reset must discard the old render completion and clear RESET_BUSY")
      // Reuse the ID after reset. No stale render completion may occupy its slot.
      axiWrite(dut, RenderHostRegs.IRQ, 3)
      axiWrite(dut, GpuCommandMmioRegs.OPCODE, GpuCommandOpcode.fill.litValue.toInt)
      axiWrite(dut, GpuCommandMmioRegs.DESTINATION, 0xa000)
      axiWrite(dut, GpuCommandMmioRegs.BYTES, 64)
      axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
      assert(acceptMemoryWrite(dut).address == 0xa000)
    }
  }

  it should "reject invalid descriptor sample words through AXI, retain ERROR/completion, and recover" in {
    val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16)
    val gpu = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuHostSystemAxi(gfx, gpu)) { dut =>
      initialize(dut)
      val words = mutable.Map.empty[BigInt, BigInt]
      def store(base: Int, data: Seq[Long]): Unit =
        data.zipWithIndex.foreach { case (value, i) =>
          words(BigInt(base + i * 4)) = BigInt(value & 0xffffffffL)
        }
      def readLine(address: BigInt): BigInt = {
        val base = address & ~BigInt(63)
        (0 until 16).foldLeft(BigInt(0)) { (line, i) =>
          line | (words.getOrElse(base + i * 4, BigInt(0)) << (32 * i))
        }
      }
      def writeLine(address: BigInt, data: BigInt, mask: BigInt): Unit = {
        for (i <- 0 until 16 if ((mask >> (i * 4)) & 15) == 15)
          words(address + i * 4) = (data >> (32 * i)) & 0xffffffffL
      }
      def submitRender(id: Int, descriptor: Int): Unit = {
        axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, id)
        axiWrite(dut, GpuCommandMmioRegs.OPCODE, GpuCommandOpcode.render.litValue.toInt)
        axiWrite(dut, GpuCommandMmioRegs.SOURCE, descriptor)
        axiWrite(dut, GpuCommandMmioRegs.BYTES, 64)
        axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
      }
      def expectCompletion(id: Int, status: Int, success: Boolean): Unit = {
        assert((axiRead(dut, GpuCommandMmioRegs.STATUS) & 2) != 0,
          "unified completion must be visible")
        val completion = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
        assert((completion & 0xff) == id)
        assert(((completion >> 8) & 7) == GpuCommandOpcode.render.litValue)
        assert(((completion >> 11) & 0xf) == status)
        assert(((completion >> 15) & 1) == (if (success) 1 else 0))
      }

      // Reserved bits and mode 3 must complete with invalid-sample status
      // without launching the renderer; ERROR W1C must leave the completion
      // payload intact; a later draw recovers after POP.
      val invalidModes = Seq(0x100L, 3L, 0x80000000L)
      val recoveryDescriptor = 0x4000
      val recoveryCmd = 0x5000
      val recoveryColor = 0x8000
      val vertices = Seq(-65536L, -65536L, 0L, 65536L,
        65536L, -65536L, 0L, 65536L, -65536L, 65536L, 0L, 65536L)
      store(recoveryCmd, vertices ++ Seq.fill(3)(Seq(255L, 0L, 0L)).flatten ++
        Seq.fill(3)(16L) ++ Seq.fill(16)(0L))
      store(recoveryDescriptor, Seq(1L << 16, recoveryCmd.toLong, recoveryColor.toLong,
        0x9000L, 64L, 0L) ++ Seq.fill(10)(0L))
      axiWrite(dut, RenderHostRegs.IRQ, 1)
      for ((mode, idx) <- invalidModes.zipWithIndex) {
        val descriptor = 0x3000 + idx * 0x40
        store(descriptor, Seq(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, mode) ++
          Seq.fill(6)(0L))
        submitRender(0x20 + idx, descriptor)
        serviceMemoryMaster(dut, readLine)(writeLine) {
          dut.io.m_irq.peek().litToBoolean
        }
        assert((axiRead(dut, RenderHostRegs.STATUS) & 7) == 6,
          "invalid sample mode must complete DONE|ERROR without BUSY")
        expectCompletion(0x20 + idx, status = 2, success = false)

        // Clearing STATUS.ERROR must not rewrite the owning completion.
        axiWrite(dut, RenderHostRegs.STATUS, 0x4)
        assert((axiRead(dut, RenderHostRegs.STATUS) & 4) == 0)
        expectCompletion(0x20 + idx, status = 2, success = false)

        // Hold the completion across a few idle cycles, then POP and recover.
        for (_ <- 0 until 8) dut.io.s_axi_aclk.step()
        expectCompletion(0x20 + idx, status = 2, success = false)
        axiWrite(dut, GpuCommandMmioRegs.COMPLETION_POP, 1)
        axiWrite(dut, RenderHostRegs.IRQ, 3)

        store(recoveryColor, Seq.fill(64)(0x12345678L))
        submitRender(0x30 + idx, recoveryDescriptor)
        serviceMemoryMaster(dut, readLine, writeAckDelay = 4, maxCycles = 80000)(writeLine) {
          dut.io.m_irq.peek().litToBoolean
        }
        expectCompletion(0x30 + idx, status = 0, success = true)
        assert(words.getOrElse(BigInt(recoveryColor + 5 * 64 + 5 * 4), BigInt(0)) == 0xff0000ffL)
        axiWrite(dut, GpuCommandMmioRegs.COMPLETION_POP, 1)
        axiWrite(dut, RenderHostRegs.IRQ, 3)
      }
    }
  }

  it should "run mixed sample modes in sequence through the AXI unified path" in {
    val gfx = GraphicsConfig(screenWidth = 4, screenHeight = 4)
    val gpu = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuHostSystemAxi(gfx, gpu)) { dut =>
      initialize(dut)
      val words = mutable.Map.empty[BigInt, BigInt]
      def store(base: Int, data: Seq[Long]): Unit =
        data.zipWithIndex.foreach { case (value, i) =>
          words(BigInt(base + i * 4)) = BigInt(value & 0xffffffffL)
        }
      def readLine(address: BigInt): BigInt = {
        val base = address & ~BigInt(63)
        (0 until 16).foldLeft(BigInt(0)) { (line, i) =>
          line | (words.getOrElse(base + i * 4, BigInt(0)) << (32 * i))
        }
      }
      def writeLine(address: BigInt, data: BigInt, mask: BigInt): Unit = {
        for (i <- 0 until 16 if ((mask >> (i * 4)) & 15) == 15)
          words(address + i * 4) = (data >> (32 * i)) & 0xffffffffL
      }
      val vertices = Seq(-65536L, -65536L, 0L, 65536L,
        65536L, -65536L, 0L, 65536L, -65536L, 65536L, 0L, 65536L)
      // Distinct framebuffers prove each mode's draw really launched; one colour
      // keeps the packing contract identical across 1x/2x/4x.
      val draws = Seq(
        (0, 0x2000, 0x8000, 0x9000),
        (2, 0x2100, 0xa000, 0xb000),
        (1, 0x2200, 0xc000, 0xd000))
      val expected = 0xff0000ffL
      axiWrite(dut, RenderHostRegs.IRQ, 1)
      for (((mode, cmd, color, depth), id) <- draws.zipWithIndex) {
        val samples = 1 << mode
        val stride = 4 * samples * 4
        store(cmd, vertices ++ Seq.fill(3)(Seq(255L, 0L, 0L)).flatten ++
          Seq.fill(3)(16L) ++ Seq.fill(16)(0L))
        store(0x1000, Seq(1L << 16, cmd.toLong, color.toLong, depth.toLong,
          stride.toLong, 0x81L, 0L, 0L, 0L, mode.toLong) ++ Seq.fill(6)(0L))
        for (i <- 0 until 4 * 4 * samples) {
          store(color + i * 4, Seq(0x12345678L))
          store(depth + i * 4, Seq(0x00ffffffL))
        }
        axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 0x40 + id)
        axiWrite(dut, GpuCommandMmioRegs.OPCODE, GpuCommandOpcode.render.litValue.toInt)
        axiWrite(dut, GpuCommandMmioRegs.SOURCE, 0x1000)
        axiWrite(dut, GpuCommandMmioRegs.BYTES, 64)
        axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
        serviceMemoryMaster(dut, readLine, writeAckDelay = 4, maxCycles = 80000)(writeLine) {
          dut.io.m_irq.peek().litToBoolean
        }
        val completion = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
        assert((completion & 0xff) == 0x40 + id)
        assert(((completion >> 15) & 1) == 1, s"mode $mode must succeed")
        val painted = (0 until 4 * 4 * samples).exists { i =>
          words.getOrElse(BigInt(color + i * 4), BigInt(0)) == expected
        }
        assert(painted, f"mode=$mode must paint at least one sample 0x$expected%08x")
        axiWrite(dut, GpuCommandMmioRegs.COMPLETION_POP, 1)
        axiWrite(dut, RenderHostRegs.IRQ, 3)
      }
    }
  }

  it should "fail a descriptor fetch bus fault before launch, retain it until POP, and recover" in {
    val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16)
    val gpu = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuHostSystemAxi(gfx, gpu)) { dut =>
      initialize(dut)
      val words = mutable.Map.empty[BigInt, BigInt]
      def store(base: Int, data: Seq[Long]): Unit =
        data.zipWithIndex.foreach { case (value, i) =>
          words(BigInt(base + i * 4)) = BigInt(value & 0xffffffffL)
        }
      def readLine(address: BigInt): BigInt = {
        val base = address & ~BigInt(63)
        (0 until 16).foldLeft(BigInt(0)) { (line, i) =>
          line | (words.getOrElse(base + i * 4, BigInt(0)) << (32 * i))
        }
      }
      def writeLine(address: BigInt, data: BigInt, mask: BigInt): Unit = {
        for (i <- 0 until 16 if ((mask >> (i * 4)) & 15) == 15)
          words(address + i * 4) = (data >> (32 * i)) & 0xffffffffL
      }
      val vertices = Seq(-65536L, -65536L, 0L, 65536L,
        65536L, -65536L, 0L, 65536L, -65536L, 65536L, 0L, 65536L)
      store(0x2000, vertices ++ Seq.fill(3)(Seq(255L, 0L, 0L)).flatten ++
        Seq.fill(3)(16L) ++ Seq.fill(16)(0L))
      store(0x1000, Seq(1L << 16, 0x2000L, 0x8000L, 0x9000L,
        64L, 0L) ++ Seq.fill(10)(0L))
      store(0x8000, Seq.fill(16)(0x12345678L))

      axiWrite(dut, RenderHostRegs.IRQ, 1)
      axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 0x51)
      axiWrite(dut, GpuCommandMmioRegs.OPCODE, GpuCommandOpcode.render.litValue.toInt)
      axiWrite(dut, GpuCommandMmioRegs.SOURCE, 0x1000)
      axiWrite(dut, GpuCommandMmioRegs.BYTES, 64)
      axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
      // Fault the descriptor line itself; the renderer must not launch.
      serviceMemoryMaster(dut, readLine, address => address == 0x1000)(writeLine) {
        dut.io.m_irq.peek().litToBoolean
      }
      assert((axiRead(dut, RenderHostRegs.STATUS) & 7) == 6, "DONE and ERROR, not BUSY")
      val faulted = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
      assert((faulted & 0xff) == 0x51)
      assert(((faulted >> 11) & 0xf) == 1, "descriptor fault status must be memory fault")
      assert(((faulted >> 15) & 1) == 0)
      assert(words.getOrElse(BigInt(0x8000), BigInt(0)) == 0x12345678L,
        "failed descriptor fetch must not emit pixels")

      // STATUS.ERROR W1C leaves the completion payload intact.
      axiWrite(dut, RenderHostRegs.STATUS, 0x4)
      assert((axiRead(dut, GpuCommandMmioRegs.COMPLETION) & 0xff) == 0x51)
      assert(((axiRead(dut, GpuCommandMmioRegs.COMPLETION) >> 11) & 0xf) == 1)

      axiWrite(dut, GpuCommandMmioRegs.COMPLETION_POP, 1)
      axiWrite(dut, RenderHostRegs.IRQ, 3)
      axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 0x52)
      axiWrite(dut, GpuCommandMmioRegs.OPCODE, GpuCommandOpcode.render.litValue.toInt)
      axiWrite(dut, GpuCommandMmioRegs.SOURCE, 0x1000)
      axiWrite(dut, GpuCommandMmioRegs.BYTES, 64)
      axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
      serviceMemoryMaster(dut, readLine, writeAckDelay = 4)(writeLine) {
        dut.io.m_irq.peek().litToBoolean
      }
      val recovered = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
      assert((recovered & 0xff) == 0x52)
      assert(((recovered >> 15) & 1) == 1)
      assert(words.getOrElse(BigInt(0x8000 + 5 * 64 + 5 * 4), BigInt(0)) == 0xff0000ffL)
    }
  }

}
