package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class KernelShaderStageSpec extends AnyFlatSpec {
  behavior of "KernelShaderStage"

  private def idle(dut: KernelShaderStage): Unit = {
    dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
    dut.io.instructionSatp.poke(0.U)
    dut.io.instructionTlbFlush.valid.poke(false.B)
    dut.io.instructionTlbFlush.bits.poke(0.U.asTypeOf(dut.io.instructionTlbFlush.bits))
    dut.io.vectorSatp.poke(0.U)
    dut.io.vectorTlbFlush.valid.poke(false.B)
    dut.io.vectorTlbFlush.bits.poke(0.U.asTypeOf(dut.io.vectorTlbFlush.bits))
    dut.io.launch.valid.poke(false.B)
    dut.io.launch.kernelPc.poke(0.U)
    dut.io.launch.kernargAddress.poke(0.U)
    dut.io.launch.gridX.poke(1.U); dut.io.launch.gridY.poke(1.U); dut.io.launch.gridZ.poke(1.U)
    dut.io.launch.localX.poke(3.U); dut.io.launch.localY.poke(1.U); dut.io.launch.localZ.poke(1.U)
    dut.io.completion.ready.poke(true.B)
    dut.io.memoryRequest.ready.poke(true.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.readData.poke(0.U)
    dut.io.memoryResponse.bits.fault.poke(false.B)
    dut.io.memoryResponse.bits.transactionId.poke(0.U)
    dut.io.trap.ready.poke(true.B)
    dut.io.simtBranch.valid.poke(false.B)
    dut.io.simtBranch.bits.poke(0.U.asTypeOf(dut.io.simtBranch.bits))
  }

  /** Byte-addressed memory served as whole 64-byte lines.
    *
    * The CU's instruction cache line is 64 bytes, so a fetch response carrying
    * only the requested 8 bytes leaves the rest of the line as zeros and any
    * later PC in that line decodes as an illegal instruction. Serving whole
    * lines is what KernelFragStageSpec's MemModel does, and the memory port is
    * line-granular in both directions: a store appears as a line address plus a
    * byte mask, not a byte address.
    */
  private final class Mem {
    private val bytes = scala.collection.mutable.Map[BigInt, BigInt]()
    def putWord(addr: BigInt, value: BigInt): Unit =
      (0 until 4).foreach(i => bytes(addr + i) = (value >> (8 * i)) & 0xff)
    def line(addr: BigInt): BigInt = {
      val base = addr & ~BigInt(63)
      (0 until 8).foldLeft(BigInt(0)) { case (acc, word) =>
        val at = base + word * 8
        acc + ((0 until 8).foldLeft(BigInt(0)) { case (w, i) =>
          w + (bytes.getOrElse(at + i, BigInt(0)) << (8 * i))
        } << (64 * word))
      }
    }
  }

  private final case class Result(
      writes: scala.collection.mutable.Map[BigInt, BigInt],
      traps: Seq[(BigInt, BigInt)],
      reqs: Seq[String] = Seq.empty)

  /** Launch the shader at 0x1000 with kernarg at 0x8000 and run to completion. */
  private def runShader(dut: KernelShaderStage, mem: Mem,
                        limit: Int = 400, latency: Int = 1): Result = {
    dut.io.launch.kernelPc.poke(0x1000.U)
    dut.io.launch.kernargAddress.poke(0x8000.U)
    dut.io.launch.localX.poke(3.U)
    dut.io.launch.gridX.poke(1.U)
    dut.io.launch.valid.poke(true.B)
    assert(dut.io.launch.ready.peek().litToBoolean, "launch must be accepted")
    dut.clock.step(); dut.io.launch.valid.poke(false.B)

    val writes = scala.collection.mutable.Map[BigInt, BigInt]()
    val traps = scala.collection.mutable.ArrayBuffer[(BigInt, BigInt)]()
    val reqs = scala.collection.mutable.ArrayBuffer[String]()
    val inflight = scala.collection.mutable.Queue[(Int, BigInt, BigInt)]()
    var resp = false
    var id = BigInt(0)
    var data = BigInt(0)
    var guard = 0
    var done = false
    while (!done && guard < limit) {
      // Re-present the pending response every cycle, the way
      // KernelFragStageSpec's pump does, so a beat is never dropped.
      // A response is held until the cycle after its request, matching the
      // interconnect's one-cycle minimum, then for latency-1 further cycles.
      dut.io.memoryResponse.valid.poke(resp)
      if (resp) {
        dut.io.memoryResponse.bits.transactionId.poke(id.U)
        dut.io.memoryResponse.bits.readData.poke(data.U)
        dut.io.memoryResponse.bits.fault.poke(false.B)
      }
      val accepted = resp && dut.io.memoryResponse.ready.peek().litToBoolean
      val fired = dut.io.memoryRequest.valid.peek().litToBoolean &&
        dut.io.memoryRequest.ready.peek().litToBoolean
      if (fired) {
        val addr = dut.io.memoryRequest.bits.address.peek().litValue
        val reqId = dut.io.memoryRequest.bits.transactionId.peek().litValue
        val lineData =
          if (dut.io.memoryRequest.bits.isWrite.peek().litToBoolean) {
            val strobe = dut.io.memoryRequest.bits.byteMask.peek().litValue
            val word = dut.io.memoryRequest.bits.writeData.peek().litValue
            (0 until 64).foreach { i =>
              if (strobe.testBit(i)) writes(addr + i) = (word >> (8 * i)) & 0xff
            }
            reqs += s"W 0x${addr.toString(16)} mask=0x${strobe.toString(16)} data=0x${word.toString(16)}"
            BigInt(0)
          } else {
            reqs += s"R 0x${addr.toString(16)}"
            mem.line(addr)
          }
        inflight.enqueue((latency, reqId, lineData))
      }
      if (accepted) resp = false
      // In-order: only the oldest request counts down, and its response is
      // presented on the following cycle. latency=1 matches the interconnect's
      // one-cycle minimum.
      if (!resp && inflight.nonEmpty) {
        val head = inflight.front
        if (head._1 <= 1) {
          val ready = inflight.dequeue()
          id = ready._2
          data = ready._3
          resp = true
        } else {
          val pending = inflight.dequeue()
          inflight.prepend((pending._1 - 1, pending._2, pending._3))
        }
      }
      if (dut.io.trap.valid.peek().litToBoolean)
        traps += ((dut.io.trap.bits.pc.peek().litValue,
          dut.io.trap.bits.cause.peek().litValue))
      dut.clock.step()
      guard += 1
      done = dut.io.completion.valid.peek().litToBoolean
    }
    dut.io.memoryResponse.valid.poke(false.B)
    assert(done, s"shader CU never completed in $limit cycles; traps ${show(traps.toSeq)}")
    Result(writes, traps.toSeq, reqs.toSeq)
  }

  private def show(traps: Seq[(BigInt, BigInt)]): String =
    traps.map(t => s"pc=0x${t._1.toString(16)} cause=${t._2}").mkString("; ")

  private def storedWord(writes: scala.collection.mutable.Map[BigInt, BigInt],
                         addr: BigInt): BigInt =
    (0 until 4).foldLeft(BigInt(0)) { case (acc, i) =>
      acc + (writes.getOrElse(addr + i, BigInt(0)) << (8 * i))
    }

  it should "emit a draw's shader descriptor via KernelEmit and run it to completion" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      mem.putWord(BigInt(0x1000), BigInt("00500093", 16)) // addi x1, x0, 5
      mem.putWord(BigInt(0x1004), BigInt("30500073", 16)) // cease

      val result = runShader(dut, mem)
      assert(result.traps.isEmpty, show(result.traps))
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  // A `uniform float` lowers to flw off the kernarg base, which the CU preloads
  // into x1, so the shader needs no prologue. The shader CU used to be built
  // without FpuBackend: flw could not decode, never issued its load, and hung
  // the warp. This covers that the FP decode and memory datapath is now live on
  // the graphics side.
  it should "retire a scalar FP load from kernarg on the shader CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      mem.putWord(BigInt(0x1000), BigInt("0000a087", 16)) // flw f1, 0(x1)
      mem.putWord(BigInt(0x1004), BigInt("30500073", 16)) // cease
      mem.putWord(BigInt(0x8000), BigInt("40a00000", 16)) // kernarg[0] = 5.0f

      val result = runShader(dut, mem)
      assert(result.traps.isEmpty, show(result.traps))
      assert(result.writes.isEmpty, "a load must not write")
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  // The path a `uniform float` actually needs: flw reads the value into an
  // f-register and a vector op consumes it through the .vf operand sideband
  // (fpu.fvfRead in GpuCore), so the integer scalar register file is not
  // involved. Encodings match ProgrammableTextureAxiSpec and the fp_scalar.S
  // corpus shader.
  it should "feed a scalar FP load into a vector .vf operand on the shader CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      val program = Seq(
        BigInt(0x1000) -> BigInt("c1027057", 16), // vsetivli x0,4,e32,m1,ta,ma
        BigInt(0x1004) -> BigInt("0000a087", 16), // flw f1, 0(x1)
        BigInt(0x1008) -> BigInt("01008293", 16), // addi t0, x1, 16
        BigInt(0x100c) -> BigInt("0202e107", 16), // vle32.v v2, (t0)
        BigInt(0x1010) -> BigInt("0220d257", 16), // vfadd.vf v4, v2, f1
        BigInt(0x1014) -> BigInt("04008293", 16), // addi t0, x1, 64
        BigInt(0x1018) -> BigInt("0202e227", 16), // vse32.v v4, (t0)
        BigInt(0x101c) -> BigInt("30500073", 16)) // cease
      program.foreach { case (addr, word) => mem.putWord(addr, word) }
      mem.putWord(BigInt(0x8000), BigInt("40000000", 16)) // uniform f1 = 2.0f
      // v2 source, one 3.0f per lane.
      (0 until 4).foreach(lane =>
        mem.putWord(BigInt(0x8010) + lane * 4, BigInt("40400000", 16)))

      val result = runShader(dut, mem)
      assert(result.traps.isEmpty, show(result.traps))
      // kernarg[64] must hold 3.0f + the 2.0f uniform = 5.0f.
      // kernarg[64] must hold 3.0f + the 2.0f uniform = 5.0f. The store mask
      // observed for this VL=4 vse32.v is 0xfff, three lanes rather than four;
      // only lane 0 is asserted here because that is what this test is about,
      // and the mask width is a separate question.
      val stored = storedWord(result.writes, BigInt(0x8040))
      assert(stored == BigInt("40a00000", 16),
        s"vfadd.vf stored 0x${stored.toString(16)}, expected 40a00000 (5.0f)")
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  // A bounds check lowers to a compare against the per-lane index the CU
  // publishes in v1, then a store predicated on the resulting v0 mask. The
  // launch has three threads, so lane 3 is inactive and v1 is 0, 1, 2.
  it should "predicate a store on a vmsltu.vx mask built from v1" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      val program = Seq(
        BigInt(0x1000) -> BigInt("c1027057", 16), // vsetivli x0,4,e32,m1,ta,ma
        BigInt(0x1004) -> BigInt("00200493", 16), // addi x9, x0, 2
        BigInt(0x1008) -> BigInt("6a14c057", 16), // vmsltu.vx v0, v1, x9
        BigInt(0x100c) -> BigInt("04008293", 16), // addi t0, x1, 64
        BigInt(0x1010) -> BigInt("0202e027", 16), // vse32.v v0, (t0)
        BigInt(0x1014) -> BigInt("08008293", 16), // addi t0, x1, 128
        BigInt(0x1018) -> BigInt("0002e0a7", 16), // vse32.v v1, (t0), v0.t
        BigInt(0x101c) -> BigInt("30500073", 16)) // cease
      program.foreach { case (addr, word) => mem.putWord(addr, word) }

      val result = runShader(dut, mem)
      val trace = result.reqs.mkString("\n")
      assert(result.traps.isEmpty, show(result.traps))
      val mask = storedWord(result.writes, BigInt(0x8040))
      assert((mask & 0x7) == 0x3,
        s"v0 stored 0x${mask.toString(16)}, expected low bits 011\n$trace")
      val guarded = (0 until 3).map(lane =>
        result.writes.contains(BigInt(0x8080) + lane * 4))
      assert(guarded == Seq(true, true, false),
        s"masked store wrote lanes $guarded, expected lanes 0 and 1\n$trace")
      assert(storedWord(result.writes, BigInt(0x8084)) == 1, trace)
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  it should "store a vector load's data when the store follows it directly" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      val program = Seq(
        BigInt(0x1000) -> BigInt("c1027057", 16), // vsetivli x0,4,e32,m1,ta,ma
        BigInt(0x1004) -> BigInt("01008293", 16), // addi t0, x1, 16
        BigInt(0x1008) -> BigInt("0202e107", 16), // vle32.v v2, (t0)
        BigInt(0x100c) -> BigInt("0202e127", 16), // vse32.v v2, (t0)
        BigInt(0x1010) -> BigInt("30500073", 16)) // cease
      program.foreach { case (addr, word) => mem.putWord(addr, word) }
      (0 until 4).foreach(lane =>
        mem.putWord(BigInt(0x8010) + lane * 4, BigInt(0x100 + lane)))

      val result = runShader(dut, mem)
      val trace = result.reqs.mkString("\n")
      assert(result.traps.isEmpty, show(result.traps))
      val stored = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8010) + lane * 4))
      assert(stored == Seq(0x100, 0x101, 0x102).map(BigInt(_)),
        s"stored ${stored.map(_.toString(16))}\n$trace")
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  // A scalar FP store reads its source operand back out of the f-register
  // file, which is a different path from the .vf sideband the case above
  // covers. The store offset is S-type, split across 31:25 and 11:7 so that
  // 24:20 can hold the source register.
  it should "round trip a float through an f-register with fsw on the shader CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      val program = Seq(
        BigInt(0x1000) -> BigInt("0040a087", 16), // flw f1, 4(x1)
        BigInt(0x1004) -> BigInt("10000113", 16), // addi x2, x0, 0x100
        BigInt(0x1008) -> BigInt("00112827", 16), // fsw f1, 0x10(x2) -> [0x110]
        BigInt(0x100c) -> BigInt("30500073", 16)) // cease
      program.foreach { case (addr, word) => mem.putWord(addr, word) }
      mem.putWord(BigInt(0x8004), BigInt("40a00000", 16)) // kernarg[1] = 5.0f

      val result = runShader(dut, mem)
      assert(result.traps.isEmpty, show(result.traps))
      val stored = storedWord(result.writes, BigInt(0x110))
      assert(stored == BigInt("40a00000", 16),
        s"fsw stored 0x${stored.toString(16)}, expected 40a00000 (5.0f)")
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  // The S-type offset is two fields, so exercise each half and the sign. A
  // 0x7fc offset needs bits above the low five; -4 needs the sign to reach
  // bits 31:25. Storing from f1 at an offset that is not a multiple of four
  // was not encodable before, which is what made the earlier case pick f4.
  it should "honour both halves and the sign of a scalar FP store offset" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      val program = Seq(
        BigInt(0x1000) -> BigInt("0040a087", 16), // flw f1, 4(x1)
        BigInt(0x1004) -> BigInt("40000113", 16), // addi x2, x0, 0x400
        BigInt(0x1008) -> BigInt("7e112e27", 16), // fsw f1, 0x7fc(x2) -> [0xbfc]
        BigInt(0x100c) -> BigInt("fe112e27", 16), // fsw f1, -4(x2)   -> [0x3fc]
        BigInt(0x1010) -> BigInt("30500073", 16)) // cease
      program.foreach { case (addr, word) => mem.putWord(addr, word) }
      mem.putWord(BigInt(0x8004), BigInt("40a00000", 16)) // kernarg[1] = 5.0f

      val result = runShader(dut, mem)
      assert(result.traps.isEmpty, show(result.traps))
      for (addr <- Seq(BigInt(0xbfc), BigInt(0x3fc))) {
        val stored = storedWord(result.writes, addr)
        assert(stored == BigInt("40a00000", 16),
          s"fsw to 0x${addr.toString(16)} stored 0x${stored.toString(16)}, " +
            "expected 40a00000 (5.0f)")
      }
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  // Two uniforms lowered through scalar FP: flw both, fadd/fsub/fmul/fsgnjn/
  // fmin/fmadd, then fsw each result. fdiv, fsqrt, and the integer crossings
  // retire on the same path and are covered by the tests below.
  it should "combine two scalar floats and store the results on the shader CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      val program = Seq(
        BigInt(0x1000) -> BigInt("0000a087", 16), // flw f1, 0(x1)
        BigInt(0x1004) -> BigInt("0040a107", 16), // flw f2, 4(x1)
        BigInt(0x1008) -> BigInt("002081d3", 16), // fadd.s f3, f1, f2
        BigInt(0x100c) -> BigInt("08110253", 16), // fsub.s f4, f2, f1
        BigInt(0x1010) -> BigInt("102082d3", 16), // fmul.s f5, f1, f2
        BigInt(0x1014) -> BigInt("20209353", 16), // fsgnjn.s f6, f1, f2
        BigInt(0x1018) -> BigInt("282083d3", 16), // fmin.s f7, f1, f2
        BigInt(0x101c) -> BigInt("08208443", 16), // fmadd.s f8, f1, f2, f1
        BigInt(0x1020) -> BigInt("20000113", 16), // addi x2, x0, 0x200
        BigInt(0x1024) -> BigInt("00312027", 16), // fsw f3, 0(x2)
        BigInt(0x1028) -> BigInt("00412227", 16), // fsw f4, 4(x2)
        BigInt(0x102c) -> BigInt("00512427", 16), // fsw f5, 8(x2)
        BigInt(0x1030) -> BigInt("00612627", 16), // fsw f6, 12(x2)
        BigInt(0x1034) -> BigInt("00712827", 16), // fsw f7, 16(x2)
        BigInt(0x1038) -> BigInt("00812a27", 16), // fsw f8, 20(x2)
        BigInt(0x103c) -> BigInt("30500073", 16)) // cease
      program.foreach { case (addr, word) => mem.putWord(addr, word) }
      mem.putWord(BigInt(0x8000), BigInt("40000000", 16)) // 2.0f
      mem.putWord(BigInt(0x8004), BigInt("40400000", 16)) // 3.0f

      val result = runShader(dut, mem, limit = 2000)
      assert(result.traps.isEmpty, show(result.traps))
      val expected = Seq(
        0 -> "40a00000", // 2+3 = 5
        4 -> "3f800000", // 3-2 = 1
        8 -> "40c00000", // 2*3 = 6
        12 -> "c0000000", // fsgnjn -> -2
        16 -> "40000000", // min = 2
        20 -> "41000000") // 2*3+2 = 8
      expected.foreach { case (offset, bits) =>
        val stored = storedWord(result.writes, BigInt(0x200 + offset))
        assert(stored == BigInt(bits, 16),
          s"offset $offset stored 0x${stored.toString(16)}, expected $bits")
      }
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  // 1.0f compared with 2.0f distinguishes feq (0) from fle/flt (1). The same
  // program converts and moves both directions and stores every result.
  it should "cross scalar floats and integers on the shader CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      val program = Seq(
        BigInt(0x1000) -> BigInt("0000a087", 16), // flw f1, 0(x1)
        BigInt(0x1004) -> BigInt("0040a107", 16), // flw f2, 4(x1)
        BigInt(0x1008) -> BigInt("a020a553", 16), // feq.s x10, f1, f2
        BigInt(0x100c) -> BigInt("a02085d3", 16), // fle.s x11, f1, f2
        BigInt(0x1010) -> BigInt("a0209653", 16), // flt.s x12, f1, f2
        BigInt(0x1014) -> BigInt("c00086d3", 16), // fcvt.w.s x13, f1
        BigInt(0x1018) -> BigInt("e0009753", 16), // fclass.s x14, f1
        BigInt(0x101c) -> BigInt("e00087d3", 16), // fmv.x.w x15, f1
        BigInt(0x1020) -> BigInt("20000113", 16), // addi x2, x0, 0x200
        BigInt(0x1024) -> BigInt("00a12023", 16), // sw x10, 0(x2)
        BigInt(0x1028) -> BigInt("00b12223", 16), // sw x11, 4(x2)
        BigInt(0x102c) -> BigInt("00c12423", 16), // sw x12, 8(x2)
        BigInt(0x1030) -> BigInt("00d12623", 16), // sw x13, 12(x2)
        BigInt(0x1034) -> BigInt("00e12823", 16), // sw x14, 16(x2)
        BigInt(0x1038) -> BigInt("00f12a23", 16), // sw x15, 20(x2)
        BigInt(0x103c) -> BigInt("00500813", 16), // addi x16, x0, 5
        BigInt(0x1040) -> BigInt("d00801d3", 16), // fcvt.s.w f3, x16
        BigInt(0x1044) -> BigInt("f0080253", 16), // fmv.w.x f4, x16
        BigInt(0x1048) -> BigInt("00312c27", 16), // fsw f3, 24(x2)
        BigInt(0x104c) -> BigInt("00412e27", 16), // fsw f4, 28(x2)
        BigInt(0x1050) -> BigInt("30500073", 16)) // cease
      program.foreach { case (addr, word) => mem.putWord(addr, word) }
      mem.putWord(BigInt(0x8000), BigInt("3f800000", 16)) // 1.0f
      mem.putWord(BigInt(0x8004), BigInt("40000000", 16)) // 2.0f

      val result = runShader(dut, mem, limit = 4000)
      assert(result.traps.isEmpty, show(result.traps))
      val expected = Seq(
        0 -> "0", // feq 1.0 == 2.0
        4 -> "1", // fle 1.0 <= 2.0
        8 -> "1", // flt 1.0 < 2.0
        12 -> "1", // fcvt.w.s 1.0
        16 -> "40", // fclass positive normal
        20 -> "3f800000", // fmv.x.w
        24 -> "40a00000", // fcvt.s.w 5 -> 5.0f
        28 -> "5") // fmv.w.x
      expected.foreach { case (offset, bits) =>
        val stored = storedWord(result.writes, BigInt(0x200 + offset))
        assert(stored == BigInt(bits, 16),
          s"offset $offset stored 0x${stored.toString(16)}, expected $bits")
      }
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  // v1 holds the lane index. vmsltu against 2 sets v0 for lanes 0 and 1.
  // vfmerge writes the scalar there and vs2 on the clear lane. vfmv.v.f
  // broadcasts the same scalar onto every active lane.
  it should "merge a scalar float into a vector on the shader CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      val program = Seq(
        BigInt(0x1000) -> BigInt("c1027057", 16), // vsetivli x0,4,e32,m1,ta,ma
        BigInt(0x1004) -> BigInt("0000a087", 16), // flw f1, 0(x1)
        BigInt(0x1008) -> BigInt("00200493", 16), // addi x9, x0, 2
        BigInt(0x100c) -> BigInt("6a14c057", 16), // vmsltu.vx v0, v1, x9
        BigInt(0x1010) -> BigInt("01008293", 16), // addi x5, x1, 16
        BigInt(0x1014) -> BigInt("0202e107", 16), // vle32.v v2, (x5)
        BigInt(0x1018) -> BigInt("0202e207", 16), // vle32.v v4, (x5)
        BigInt(0x101c) -> BigInt("5c20d257", 16), // vfmerge.vfm v4, v2, f1, v0
        BigInt(0x1020) -> BigInt("04008293", 16), // addi x5, x1, 64
        BigInt(0x1024) -> BigInt("0202e227", 16), // vse32.v v4, (x5)
        BigInt(0x1028) -> BigInt("5e00d2d7", 16), // vfmv.v.f v5, f1
        BigInt(0x102c) -> BigInt("08008293", 16), // addi x5, x1, 128
        BigInt(0x1030) -> BigInt("0202e2a7", 16), // vse32.v v5, (x5)
        BigInt(0x1034) -> BigInt("30500073", 16)) // cease
      program.foreach { case (addr, word) => mem.putWord(addr, word) }
      mem.putWord(BigInt(0x8000), BigInt("40000000", 16)) // 2.0f
      val vs2 = Seq("40800000", "40a00000", "40c00000") // 4, 5, 6
      vs2.zipWithIndex.foreach { case (bits, lane) =>
        mem.putWord(BigInt(0x8010) + lane * 4, BigInt(bits, 16))
      }

      val result = runShader(dut, mem, limit = 4000)
      assert(result.traps.isEmpty, show(result.traps))
      val merged = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8040) + lane * 4))
      assert(merged == Seq("40000000", "40000000", "40c00000").map(BigInt(_, 16)),
        s"vfmerge stored ${merged.map(_.toString(16))}")
      val broadcast = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8080) + lane * 4))
      assert(broadcast == Seq("40000000", "40000000", "40000000").map(BigInt(_, 16)),
        s"vfmv stored ${broadcast.map(_.toString(16))}")
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  // 1.5 and 2.5 truncate toward zero; -1.0 stays -1. The integer vector
  // 5, -5, 0 converts back to those exact floats.
  it should "convert floats and integers on the shader CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      val program = Seq(
        BigInt(0x1000) -> BigInt("c1027057", 16), // vsetivli x0,4,e32,m1,ta,ma
        BigInt(0x1004) -> BigInt("00008293", 16), // addi x5, x1, 0
        BigInt(0x1008) -> BigInt("0202e107", 16), // vle32.v v2, (x5)
        BigInt(0x100c) -> BigInt("4a2391d7", 16), // vfcvt.rtz.x.f.v v3, v2
        BigInt(0x1010) -> BigInt("04008293", 16), // addi x5, x1, 64
        BigInt(0x1014) -> BigInt("0202e1a7", 16), // vse32.v v3, (x5)
        BigInt(0x1018) -> BigInt("01008293", 16), // addi x5, x1, 16
        BigInt(0x101c) -> BigInt("0202e207", 16), // vle32.v v4, (x5)
        BigInt(0x1020) -> BigInt("4a4192d7", 16), // vfcvt.f.x.v v5, v4
        BigInt(0x1024) -> BigInt("08008293", 16), // addi x5, x1, 128
        BigInt(0x1028) -> BigInt("0202e2a7", 16), // vse32.v v5, (x5)
        BigInt(0x102c) -> BigInt("30500073", 16)) // cease
      program.foreach { case (addr, word) => mem.putWord(addr, word) }
      Seq("3fc00000", "40200000", "bf800000").zipWithIndex.foreach {
        case (bits, lane) =>
          mem.putWord(BigInt(0x8000) + lane * 4, BigInt(bits, 16))
      }
      Seq(BigInt(5), BigInt("fffffffb", 16), BigInt(0)).zipWithIndex.foreach {
        case (bits, lane) =>
          mem.putWord(BigInt(0x8010) + lane * 4, bits)
      }

      val result = runShader(dut, mem, limit = 4000)
      assert(result.traps.isEmpty, show(result.traps))
      val ints = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8040) + lane * 4))
      assert(ints == Seq(BigInt(1), BigInt(2), BigInt("ffffffff", 16)),
        s"vfcvt.rtz.x.f.v stored ${ints.map(_.toString(16))}")
      val floats = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8080) + lane * 4))
      assert(floats == Seq("40a00000", "c0a00000", "0").map(BigInt(_, 16)),
        s"vfcvt.f.x.v stored ${floats.map(_.toString(16))}")
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  // vid.v needs no source at all, and vcompress.vm then packs the lanes whose
  // compare set v0 into the low elements of the destination. Comparing against
  // 11 selects lanes 0 and 2, so the pack is [11, 11] followed by the two
  // elements the destination already held.
  it should "index and compact elements on the shader CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      val program = Seq(
        BigInt(0x1000) -> BigInt("c1027057", 16), // vsetivli x0,4,e32,m1,ta,ma
        BigInt(0x1004) -> BigInt("0200e107", 16), // vle32.v v2, (x1)
        BigInt(0x1008) -> BigInt("02008313", 16), // addi x6, x1, 32
        BigInt(0x100c) -> BigInt("02036207", 16), // vle32.v v4, (x6)
        BigInt(0x1010) -> BigInt("00b00293", 16), // addi x5, x0, 11
        BigInt(0x1014) -> BigInt("6222c057", 16), // vmseq.vx v0, v2, x5
        BigInt(0x1018) -> BigInt("5208a2d7", 16), // vid.v v5
        BigInt(0x101c) -> BigInt("03008313", 16), // addi x6, x1, 48
        BigInt(0x1020) -> BigInt("020362a7", 16), // vse32.v v5, (x6)
        BigInt(0x1024) -> BigInt("5e202257", 16), // vcompress.vm v4, v2, v0
        BigInt(0x1028) -> BigInt("04008313", 16), // addi x6, x1, 64
        BigInt(0x102c) -> BigInt("02036227", 16), // vse32.v v4, (x6)
        BigInt(0x1030) -> BigInt("30500073", 16)) // cease
      program.foreach { case (addr, word) => mem.putWord(addr, word) }
      Seq(11, 22, 11, 33).zipWithIndex.foreach { case (value, lane) =>
        mem.putWord(BigInt(0x8000) + lane * 4, BigInt(value))
      }
      // v4 is the compress destination: 7 in every element, of which only the
      // two lowest are overwritten by the packed result.
      Seq(7, 7, 7, 7).zipWithIndex.foreach { case (value, lane) =>
        mem.putWord(BigInt(0x8020) + lane * 4, BigInt(value))
      }

      val result = runShader(dut, mem, limit = 4000)
      assert(result.traps.isEmpty, show(result.traps))
      val indices = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8030) + lane * 4))
      assert(indices == Seq(BigInt(0), BigInt(1), BigInt(2)),
        s"vid.v stored ${indices.mkString(",")}")
      val packed = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8040) + lane * 4))
      assert(packed == Seq(BigInt(11), BigInt(11), BigInt(7)),
        s"vcompress stored ${packed.mkString(",")}")
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  // viota.m gives each element the count of selected lanes below it, which
  // with a scaled copy is the byte offset the spec's scatter idiom wants:
  // comparing against 20 selects lanes 1 and 3, so the ranks are 0, 0, 1, 1 and
  // the store compacts the odd elements into the first two words.
  it should "scatter selected elements with viota on the shader CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      val program = Seq(
        BigInt(0x1000) -> BigInt("c1027057", 16), // vsetivli x0,4,e32,m1,ta,ma
        BigInt(0x1004) -> BigInt("0200e107", 16), // vle32.v v2, (x1)
        BigInt(0x1008) -> BigInt("01400293", 16), // addi x5, x0, 20
        BigInt(0x100c) -> BigInt("6222c057", 16), // vmseq.vx v0, v2, x5
        BigInt(0x1010) -> BigInt("520822d7", 16), // viota.m v5, v0
        BigInt(0x1014) -> BigInt("02008313", 16), // addi x6, x1, 32
        BigInt(0x1018) -> BigInt("020362a7", 16), // vse32.v v5, (x6)
        BigInt(0x101c) -> BigInt("965132d7", 16), // vsll.vi v5, v5, 2
        BigInt(0x1020) -> BigInt("03008313", 16), // addi x6, x1, 48
        BigInt(0x1024) -> BigInt("0e536127", 16), // vsoxei32.v v2, (x6), v5
        BigInt(0x1028) -> BigInt("30500073", 16)) // cease
      program.foreach { case (addr, word) => mem.putWord(addr, word) }
      Seq(10, 20, 30, 40).zipWithIndex.foreach { case (value, lane) =>
        mem.putWord(BigInt(0x8000) + lane * 4, BigInt(value))
      }

      val result = runShader(dut, mem, limit = 4000)
      assert(result.traps.isEmpty, show(result.traps))
      val ranks = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8020) + lane * 4))
      assert(ranks == Seq(BigInt(0), BigInt(0), BigInt(1)),
        s"viota.m stored ${ranks.mkString(",")}")
      // Three lanes run, so the scatter leaves the first two selected elements
      // packed and stops before lane 3.
      assert(storedWord(result.writes, BigInt(0x8030)) == BigInt(20),
        s"scatter stored 0x${storedWord(result.writes, BigInt(0x8030)).toString(16)}")
      assert(storedWord(result.writes, BigInt(0x8034)) == BigInt(30),
        s"scatter stored 0x${storedWord(result.writes, BigInt(0x8034)).toString(16)}")
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  // vmacc accumulates into the destination and vmadd into vs2, with the
  // destination also acting as a multiplicand in the vmadd form. The two
  // differ here because the destinations start from different values, and the
  // harness launches three lanes so only three elements are stored.
  it should "multiply-accumulate on the shader CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      val program = Seq(
        BigInt(0x1000) -> BigInt("c1027057", 16), // vsetivli x0,4,e32,m1,ta,ma
        BigInt(0x1004) -> BigInt("0200e107", 16), // vle32.v v2, (x1)
        BigInt(0x1008) -> BigInt("01008313", 16), // addi x6, x1, 16
        BigInt(0x100c) -> BigInt("02036187", 16), // vle32.v v3, (x6)
        BigInt(0x1010) -> BigInt("02008313", 16), // addi x6, x1, 32
        BigInt(0x1014) -> BigInt("02036207", 16), // vle32.v v4, (x6)
        BigInt(0x1018) -> BigInt("b62221d7", 16), // vmacc.vv v3, v4, v2
        BigInt(0x101c) -> BigInt("03008313", 16), // addi x6, x1, 48
        BigInt(0x1020) -> BigInt("020361a7", 16), // vse32.v v3, (x6)
        BigInt(0x1024) -> BigInt("04008313", 16), // addi x6, x1, 64
        BigInt(0x1028) -> BigInt("02036307", 16), // vle32.v v6, (x6)
        BigInt(0x102c) -> BigInt("a6222357", 16), // vmadd.vv v6, v4, v2
        BigInt(0x1030) -> BigInt("05008313", 16), // addi x6, x1, 80
        BigInt(0x1034) -> BigInt("02036327", 16), // vse32.v v6, (x6)
        BigInt(0x1038) -> BigInt("30500073", 16)) // cease
      program.foreach { case (addr, word) => mem.putWord(addr, word) }
      Seq(3, 5, 7, 9).zipWithIndex.foreach { case (value, lane) =>
        mem.putWord(BigInt(0x8000) + lane * 4, BigInt(value)) // v2
        mem.putWord(BigInt(0x8010) + lane * 4, BigInt(value)) // v3
      }
      Seq(2, 4, 6, 8).zipWithIndex.foreach { case (value, lane) =>
        mem.putWord(BigInt(0x8020) + lane * 4, BigInt(value)) // v4
      }
      Seq(100, 200, 300, 400).zipWithIndex.foreach { case (value, lane) =>
        mem.putWord(BigInt(0x8040) + lane * 4, BigInt(value)) // v6
      }

      val result = runShader(dut, mem, limit = 4000)
      assert(result.traps.isEmpty, show(result.traps))
      // vmacc: v3 += v4 * v2 -> 3+6, 5+20, 7+42.
      val accumulated = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8030) + lane * 4))
      assert(accumulated == Seq(BigInt(9), BigInt(25), BigInt(49)),
        s"vmacc stored ${accumulated.mkString(",")}")
      // vmadd: v6 = v4 * v6 + v2 -> 2*100+3, 4*200+5, 6*300+7.
      val summed = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8050) + lane * 4))
      assert(summed == Seq(BigInt(203), BigInt(805), BigInt(1807)),
        s"vmadd stored ${summed.mkString(",")}")
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  // The OPFRED sums fold into element 0 and leave the rest of the destination
// alone. vfredosum.vs is funct6 000011 rather than a vs1 selector, so both
// forms are ordinary OPFVV three-operand encodings.
  it should "reduce a vector of floats into element zero on the shader CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      val program = Seq(
        BigInt(0x1000) -> BigInt("c1027057", 16), // vsetivli x0,4,e32,m1,ta,ma
        BigInt(0x1004) -> BigInt("00008293", 16), // addi x5, x1, 0
        BigInt(0x1008) -> BigInt("0202e107", 16), // vle32.v v2, (x5)
        BigInt(0x100c) -> BigInt("01008313", 16), // addi x6, x1, 16
        BigInt(0x1010) -> BigInt("02036207", 16), // vle32.v v4, (x6)
        BigInt(0x1014) -> BigInt("02008313", 16), // addi x6, x1, 32
        BigInt(0x1018) -> BigInt("02036187", 16), // vle32.v v3, (x6)
        BigInt(0x101c) -> BigInt("02036307", 16), // vle32.v v6, (x6)
        BigInt(0x1020) -> BigInt("062211d7", 16), // vfredusum.vs v3, v2, v4
        BigInt(0x1024) -> BigInt("04008293", 16), // addi x5, x1, 64
        BigInt(0x1028) -> BigInt("0202e1a7", 16), // vse32.v v3, (x5)
        BigInt(0x102c) -> BigInt("0e221357", 16), // vfredosum.vs v6, v2, v4
        BigInt(0x1030) -> BigInt("08008293", 16), // addi x5, x1, 128
        BigInt(0x1034) -> BigInt("0202e327", 16), // vse32.v v6, (x5)
        BigInt(0x1038) -> BigInt("30500073", 16)) // cease
      program.foreach { case (addr, word) => mem.putWord(addr, word) }
      // v2 is reduced; the seed is 1.0 and the four elements are 2, 3, 4, 0.5.
      // runShader launches localX = 3, so element 3 is inactive: it stays out
      // of the fold and only three elements are stored.
      Seq("40000000", "40400000", "40800000", "3f000000").zipWithIndex.foreach {
        case (bits, lane) => mem.putWord(BigInt(0x8000) + lane * 4, BigInt(bits, 16))
      }
      Seq("3f800000", "3f800000", "3f800000", "3f800000").zipWithIndex.foreach {
        case (bits, lane) => mem.putWord(BigInt(0x8010) + lane * 4, BigInt(bits, 16))
      }
      // v3 and v6 both start at 7.0, which elements past 0 must keep.
      Seq("40e00000", "40e00000", "40e00000", "40e00000").zipWithIndex.foreach {
        case (bits, lane) => mem.putWord(BigInt(0x8020) + lane * 4, BigInt(bits, 16))
      }

      val result = runShader(dut, mem, limit = 8000)
      assert(result.traps.isEmpty, show(result.traps))
      // 1 + 2 + 3 + 4 = 10: the active elements only.
      val unordered = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8040) + lane * 4))
      assert(unordered == Seq(BigInt("41200000", 16),
        BigInt("40e00000", 16), BigInt("40e00000", 16)),
        s"vfredusum stored ${unordered.map(_.toString(16))}")
      val ordered = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8080) + lane * 4))
      assert(ordered(0) == BigInt("41200000", 16),
        s"vfredosum stored 0x${ordered(0).toString(16)}")
      assert(ordered.tail == Seq.fill(2)(BigInt("40e00000", 16)),
        s"vfredosum clobbered ${ordered.tail.map(_.toString(16))}")
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  // The rtz integer-to-FP forms truncate instead of rounding to nearest even:
// 2^32-1 lands one float below 2^32 and -(2^31+1) one below -(2^31). 2^31+1
// and -1 are exactly representable, so those lanes are unchanged.
  it should "convert integers to float with RTZ on the shader CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      val program = Seq(
        BigInt(0x1000) -> BigInt("c1027057", 16), // vsetivli x0,4,e32,m1,ta,ma
        BigInt(0x1004) -> BigInt("00008293", 16), // addi x5, x1, 0
        BigInt(0x1008) -> BigInt("0202e107", 16), // vle32.v v2, (x5)
        BigInt(0x100c) -> BigInt("4a2211d7", 16), // vfcvt.rtz.f.xu.v v3, v2
        BigInt(0x1010) -> BigInt("04008293", 16), // addi x5, x1, 64
        BigInt(0x1014) -> BigInt("0202e1a7", 16), // vse32.v v3, (x5)
        BigInt(0x1018) -> BigInt("4a229257", 16), // vfcvt.rtz.f.x.v v4, v2
        BigInt(0x101c) -> BigInt("08008293", 16), // addi x5, x1, 128
        BigInt(0x1020) -> BigInt("0202e227", 16), // vse32.v v4, (x5)
        BigInt(0x1024) -> BigInt("30500073", 16)) // cease
      program.foreach { case (addr, word) => mem.putWord(addr, word) }
      Seq(BigInt("ffffffff", 16), BigInt("80000001", 16), BigInt(0))
        .zipWithIndex.foreach { case (value, lane) =>
          mem.putWord(BigInt(0x8000) + lane * 4, value)
        }

      val result = runShader(dut, mem, limit = 4000)
      assert(result.traps.isEmpty, show(result.traps))
      val unsigned = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8040) + lane * 4))
      assert(unsigned == Seq("4f7fffff", "4f000000", "0").map(BigInt(_, 16)),
        s"vfcvt.rtz.f.xu.v stored ${unsigned.map(_.toString(16))}")
      val signed = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8080) + lane * 4))
      assert(signed == Seq("bf800000", "ceffffff", "0").map(BigInt(_, 16)),
        s"vfcvt.rtz.f.x.v stored ${signed.map(_.toString(16))}")
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  // v0 is set for lanes 0 and 1. Masked vfadd doubles those lanes and leaves
  // lane 2 at the old destination. Masked vfcvt.rtz.x.f.v does the same.
  it should "mask vector float arithmetic on the shader CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      val program = Seq(
        BigInt(0x1000) -> BigInt("c1027057", 16), // vsetivli x0,4,e32,m1,tu,mu
        BigInt(0x1004) -> BigInt("00200493", 16), // addi x9, x0, 2
        BigInt(0x1008) -> BigInt("6a14c057", 16), // vmsltu.vx v0, v1, x9
        BigInt(0x100c) -> BigInt("00008293", 16), // addi x5, x1, 0
        BigInt(0x1010) -> BigInt("0202e107", 16), // vle32.v v2, (x5)
        BigInt(0x1014) -> BigInt("01008293", 16), // addi x5, x1, 16
        BigInt(0x1018) -> BigInt("0202e207", 16), // vle32.v v4, (x5)
        BigInt(0x101c) -> BigInt("00211257", 16), // vfadd.vv v4, v2, v2, v0.t
        BigInt(0x1020) -> BigInt("04008293", 16), // addi x5, x1, 64
        BigInt(0x1024) -> BigInt("0202e227", 16), // vse32.v v4, (x5)
        BigInt(0x1028) -> BigInt("02008293", 16), // addi x5, x1, 32
        BigInt(0x102c) -> BigInt("0202e187", 16), // vle32.v v3, (x5)
        BigInt(0x1030) -> BigInt("482391d7", 16), // vfcvt.rtz.x.f.v v3, v2, v0.t
        BigInt(0x1034) -> BigInt("08008293", 16), // addi x5, x1, 128
        BigInt(0x1038) -> BigInt("0202e1a7", 16), // vse32.v v3, (x5)
        BigInt(0x103c) -> BigInt("30500073", 16)) // cease
      program.foreach { case (addr, word) => mem.putWord(addr, word) }
      Seq("3f800000", "40000000", "40800000").zipWithIndex.foreach {
        case (bits, lane) =>
          mem.putWord(BigInt(0x8000) + lane * 4, BigInt(bits, 16))
      }
      Seq("41000000", "41000000", "41000000").zipWithIndex.foreach {
        case (bits, lane) =>
          mem.putWord(BigInt(0x8010) + lane * 4, BigInt(bits, 16))
      }
      (0 until 3).foreach { lane =>
        mem.putWord(BigInt(0x8020) + lane * 4, BigInt("11111111", 16))
      }

      val result = runShader(dut, mem, limit = 4000)
      assert(result.traps.isEmpty, show(result.traps))
      val added = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8040) + lane * 4))
      assert(added == Seq("40000000", "40800000", "41000000").map(BigInt(_, 16)),
        s"masked vfadd stored ${added.map(_.toString(16))}")
      val converted = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8080) + lane * 4))
      assert(converted == Seq(BigInt(1), BigInt(2), BigInt("11111111", 16)),
        s"masked vfcvt stored ${converted.map(_.toString(16))}")
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  // Each lane holds one zero-extended element. vle8 reads consecutive bytes,
  // vle16 consecutive halfwords, and vlse16 stride 4 reads every other
  // halfword. The narrow stores write only those low bytes.
  it should "load and store narrow vector elements on the shader CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      val program = Seq(
        BigInt(0x1000) -> BigInt("c1027057", 16), // vsetivli x0,4,e32,m1
        BigInt(0x1004) -> BigInt("00008293", 16), // addi x5, x1, 0
        BigInt(0x1008) -> BigInt("02028107", 16), // vle8.v v2, (x5)
        BigInt(0x100c) -> BigInt("04008293", 16), // addi x5, x1, 64
        BigInt(0x1010) -> BigInt("0202e127", 16), // vse32.v v2, (x5)
        BigInt(0x1014) -> BigInt("01008293", 16), // addi x5, x1, 16
        BigInt(0x1018) -> BigInt("0202d187", 16), // vle16.v v3, (x5)
        BigInt(0x101c) -> BigInt("08008293", 16), // addi x5, x1, 128
        BigInt(0x1020) -> BigInt("0202e1a7", 16), // vse32.v v3, (x5)
        BigInt(0x1024) -> BigInt("00400313", 16), // addi x6, x0, 4
        BigInt(0x1028) -> BigInt("01008293", 16), // addi x5, x1, 16
        BigInt(0x102c) -> BigInt("0a62d207", 16), // vlse16.v v4, (x5), x6
        BigInt(0x1030) -> BigInt("10008293", 16), // addi x5, x1, 256
        BigInt(0x1034) -> BigInt("0202e227", 16), // vse32.v v4, (x5)
        BigInt(0x1038) -> BigInt("0c008293", 16), // addi x5, x1, 192
        BigInt(0x103c) -> BigInt("02028127", 16), // vse8.v v2, (x5)
        BigInt(0x1040) -> BigInt("0d008293", 16), // addi x5, x1, 208
        BigInt(0x1044) -> BigInt("0202d1a7", 16), // vse16.v v3, (x5)
        BigInt(0x1048) -> BigInt("30500073", 16)) // cease
      program.foreach { case (addr, word) => mem.putWord(addr, word) }
      mem.putWord(BigInt(0x8000), BigInt("332211", 16))
      mem.putWord(BigInt(0x8010), BigInt("abcd1234", 16))
      mem.putWord(BigInt(0x8014), BigInt("ff", 16))
      mem.putWord(BigInt(0x8018), BigInt("7", 16))

      val result = runShader(dut, mem, limit = 4000)
      assert(result.traps.isEmpty, show(result.traps))
      val bytes = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8040) + lane * 4))
      assert(bytes == Seq(BigInt(0x11), BigInt(0x22), BigInt(0x33)),
        s"vle8 stored ${bytes.map(_.toString(16))}")
      val halves = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8080) + lane * 4))
      assert(halves == Seq(BigInt(0x1234), BigInt(0xabcd), BigInt(0xff)),
        s"vle16 stored ${halves.map(_.toString(16))}")
      val strided = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8100) + lane * 4))
      assert(strided == Seq(BigInt(0x1234), BigInt(0xff), BigInt(7)),
        s"vlse16 stored ${strided.map(_.toString(16))}")
      val packed8 = (0 until 3).map(lane =>
        result.writes.getOrElse(BigInt(0x80c0) + lane, BigInt(-1)))
      assert(packed8 == Seq(BigInt(0x11), BigInt(0x22), BigInt(0x33)),
        s"vse8 stored ${packed8.map(_.toString(16))}")
      assert(!result.writes.contains(BigInt(0x80c3)),
        "inactive lane must not store a byte")
      val packed16 = (0 until 6).map(i =>
        result.writes.getOrElse(BigInt(0x80d0) + i, BigInt(-1)))
      assert(packed16 == Seq(0x34, 0x12, 0xcd, 0xab, 0xff, 0x00).map(BigInt(_)),
        s"vse16 stored ${packed16.map(_.toString(16))}")
      assert(!result.writes.contains(BigInt(0x80d6)),
        "inactive lane must not store a halfword")
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  // 4.0 / 2.0 and sqrt(4.0) both retire through the scalar divide and
  // square-root lanes and store 2.0f.
  it should "divide and take a square root on the shader CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      val program = Seq(
        BigInt(0x1000) -> BigInt("0000a087", 16), // flw f1, 0(x1)
        BigInt(0x1004) -> BigInt("0040a107", 16), // flw f2, 4(x1)
        BigInt(0x1008) -> BigInt("182081d3", 16), // fdiv.s f3, f1, f2
        BigInt(0x100c) -> BigInt("58008253", 16), // fsqrt.s f4, f1
        BigInt(0x1010) -> BigInt("0030a827", 16), // fsw f3, 16(x1)
        BigInt(0x1014) -> BigInt("0040aa27", 16), // fsw f4, 20(x1)
        BigInt(0x1018) -> BigInt("30500073", 16)) // cease
      program.foreach { case (addr, word) => mem.putWord(addr, word) }
      mem.putWord(BigInt(0x8000), BigInt("40800000", 16)) // 4.0f
      mem.putWord(BigInt(0x8004), BigInt("40000000", 16)) // 2.0f

      val result = runShader(dut, mem, limit = 4000)
      assert(result.traps.isEmpty, show(result.traps))
      val quotient = storedWord(result.writes, BigInt(0x8010))
      val root = storedWord(result.writes, BigInt(0x8014))
      assert(quotient == BigInt("40000000", 16),
        s"fdiv stored 0x${quotient.toString(16)}")
      assert(root == BigInt("40000000", 16),
        s"fsqrt stored 0x${root.toString(16)}")
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  // 0x80 sign-extends through lb and stays 0x80 through lbu. The halfword
  // 0xabcd does the same through lh and lhu. sb and sh write the low bytes.
  it should "load and store scalar bytes and halfwords on the shader CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      val program = Seq(
        BigInt(0x1000) -> BigInt("00008503", 16), // lb x10, 0(x1)
        BigInt(0x1004) -> BigInt("0000c583", 16), // lbu x11, 0(x1)
        BigInt(0x1008) -> BigInt("00209603", 16), // lh x12, 2(x1)
        BigInt(0x100c) -> BigInt("0020d683", 16), // lhu x13, 2(x1)
        BigInt(0x1010) -> BigInt("00a0a823", 16), // sw x10, 16(x1)
        BigInt(0x1014) -> BigInt("00b0aa23", 16), // sw x11, 20(x1)
        BigInt(0x1018) -> BigInt("00c0ac23", 16), // sw x12, 24(x1)
        BigInt(0x101c) -> BigInt("00d0ae23", 16), // sw x13, 28(x1)
        BigInt(0x1020) -> BigInt("02a08023", 16), // sb x10, 32(x1)
        BigInt(0x1024) -> BigInt("02c09123", 16), // sh x12, 34(x1)
        BigInt(0x1028) -> BigInt("30500073", 16)) // cease
      program.foreach { case (addr, word) => mem.putWord(addr, word) }
      mem.putWord(BigInt(0x8000), BigInt("abcd0080", 16))

      val result = runShader(dut, mem, limit = 4000)
      assert(result.traps.isEmpty, show(result.traps))
      assert(storedWord(result.writes, BigInt(0x8010)) == BigInt("ffffff80", 16),
        "lb did not sign-extend")
      assert(storedWord(result.writes, BigInt(0x8014)) == BigInt(0x80),
        "lbu did not zero-extend")
      assert(storedWord(result.writes, BigInt(0x8018)) == BigInt("ffffabcd", 16),
        "lh did not sign-extend")
      assert(storedWord(result.writes, BigInt(0x801c)) == BigInt(0xabcd),
        "lhu did not zero-extend")
      assert(result.writes.getOrElse(BigInt(0x8020), BigInt(-1)) == BigInt(0x80),
        "sb dropped the low byte")
      val half = (0 until 2).map(i =>
        result.writes.getOrElse(BigInt(0x8022) + i, BigInt(-1)))
      assert(half == Seq(BigInt(0xcd), BigInt(0xab)),
        s"sh stored ${half.map(_.toString(16))}")
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  // vmerge.vvm writes vs1 on set mask lanes and vs2 on the clear lane.
  // vmv.v.x broadcasts the scalar onto every active lane.
  it should "merge an integer vector on the shader CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      val program = Seq(
        BigInt(0x1000) -> BigInt("c1027057", 16), // vsetivli x0,4,e32,m1,ta,ma
        BigInt(0x1004) -> BigInt("00200493", 16), // addi x9, x0, 2
        BigInt(0x1008) -> BigInt("6a14c057", 16), // vmsltu.vx v0, v1, x9
        BigInt(0x100c) -> BigInt("00008293", 16), // addi x5, x1, 0
        BigInt(0x1010) -> BigInt("0202e107", 16), // vle32.v v2, (x5)
        BigInt(0x1014) -> BigInt("01008293", 16), // addi x5, x1, 16
        BigInt(0x1018) -> BigInt("0202e187", 16), // vle32.v v3, (x5)
        BigInt(0x101c) -> BigInt("5c218257", 16), // vmerge.vvm v4, v2, v3, v0
        BigInt(0x1020) -> BigInt("04008293", 16), // addi x5, x1, 64
        BigInt(0x1024) -> BigInt("0202e227", 16), // vse32.v v4, (x5)
        BigInt(0x1028) -> BigInt("5e04c2d7", 16), // vmv.v.x v5, x9
        BigInt(0x102c) -> BigInt("08008293", 16), // addi x5, x1, 128
        BigInt(0x1030) -> BigInt("0202e2a7", 16), // vse32.v v5, (x5)
        BigInt(0x1034) -> BigInt("30500073", 16)) // cease
      program.foreach { case (addr, word) => mem.putWord(addr, word) }
      Seq(10, 20, 30).zipWithIndex.foreach { case (value, lane) =>
        mem.putWord(BigInt(0x8000) + lane * 4, BigInt(value))
      }
      Seq(1, 2, 3).zipWithIndex.foreach { case (value, lane) =>
        mem.putWord(BigInt(0x8010) + lane * 4, BigInt(value))
      }

      val result = runShader(dut, mem, limit = 4000)
      assert(result.traps.isEmpty, show(result.traps))
      val merged = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8040) + lane * 4))
      assert(merged == Seq(BigInt(1), BigInt(2), BigInt(30)),
        s"vmerge stored ${merged.map(_.toString(16))}")
      val broadcast = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8080) + lane * 4))
      assert(broadcast == Seq(BigInt(2), BigInt(2), BigInt(2)),
        s"vmv.v.x stored ${broadcast.map(_.toString(16))}")
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  // id < 2 is bits 0 and 1. id < 1 is bit 0. AND keeps lane 0; OR keeps 0 and 1.
  it should "combine compare masks on the shader CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      val program = Seq(
        BigInt(0x1000) -> BigInt("c1027057", 16), // vsetivli x0,4,e32,m1,ta,ma
        BigInt(0x1004) -> BigInt("00200493", 16), // addi x9, x0, 2
        BigInt(0x1008) -> BigInt("6a14c257", 16), // vmsltu.vx v4, v1, x9
        BigInt(0x100c) -> BigInt("00100513", 16), // addi x10, x0, 1
        BigInt(0x1010) -> BigInt("6a1542d7", 16), // vmsltu.vx v5, v1, x10
        BigInt(0x1014) -> BigInt("6642a057", 16), // vmand.mm v0, v4, v5
        BigInt(0x1018) -> BigInt("04008293", 16), // addi x5, x1, 64
        BigInt(0x101c) -> BigInt("0002e0a7", 16), // vse32.v v1, (x5), v0.t
        BigInt(0x1020) -> BigInt("6a42a057", 16), // vmor.mm v0, v4, v5
        BigInt(0x1024) -> BigInt("08008293", 16), // addi x5, x1, 128
        BigInt(0x1028) -> BigInt("0002e0a7", 16), // vse32.v v1, (x5), v0.t
        BigInt(0x102c) -> BigInt("30500073", 16)) // cease
      program.foreach { case (addr, word) => mem.putWord(addr, word) }

      val result = runShader(dut, mem, limit = 4000)
      assert(result.traps.isEmpty, show(result.traps))
      val anded = (0 until 3).map(lane =>
        result.writes.contains(BigInt(0x8040) + lane * 4))
      assert(anded == Seq(true, false, false),
        s"vmand store wrote lanes $anded")
      assert(storedWord(result.writes, BigInt(0x8040)) == 0)
      val ored = (0 until 3).map(lane =>
        result.writes.contains(BigInt(0x8080) + lane * 4))
      assert(ored == Seq(true, true, false),
        s"vmor store wrote lanes $ored")
      assert(storedWord(result.writes, BigInt(0x8084)) == 1)
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  // vmv.v.x fills the active lanes, vmv.s.x replaces element 0, and vmv.x.s
  // reads that element back into an integer that sw stores.
  it should "move element zero through an integer register on the shader CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      val program = Seq(
        BigInt(0x1000) -> BigInt("c1027057", 16), // vsetivli x0,4,e32,m1,ta,ma
        BigInt(0x1004) -> BigInt("00700493", 16), // addi x9, x0, 7
        BigInt(0x1008) -> BigInt("5e04c157", 16), // vmv.v.x v2, x9
        BigInt(0x100c) -> BigInt("05a00513", 16), // addi x10, x0, 0x5a
        BigInt(0x1010) -> BigInt("42056157", 16), // vmv.s.x v2, x10
        BigInt(0x1014) -> BigInt("04008293", 16), // addi x5, x1, 64
        BigInt(0x1018) -> BigInt("0202e127", 16), // vse32.v v2, (x5)
        BigInt(0x101c) -> BigInt("422025d7", 16), // vmv.x.s x11, v2
        BigInt(0x1020) -> BigInt("08b0a023", 16), // sw x11, 128(x1)
        BigInt(0x1024) -> BigInt("30500073", 16)) // cease
      program.foreach { case (addr, word) => mem.putWord(addr, word) }

      val result = runShader(dut, mem, limit = 4000)
      assert(result.traps.isEmpty, show(result.traps))
      val stored = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8040) + lane * 4))
      assert(stored == Seq(BigInt(0x5a), BigInt(7), BigInt(7)),
        s"vmv.s.x stored ${stored.map(_.toString(16))}")
      assert(storedWord(result.writes, BigInt(0x8080)) == BigInt(0x5a),
        "vmv.x.s did not return element 0")
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  // vfmv.s.f replaces element 0 of a loaded vector. vfmv.f.s reads it back
  // into an f-register that fsw stores.
  it should "move element zero through a scalar float on the shader CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      val program = Seq(
        BigInt(0x1000) -> BigInt("c1027057", 16), // vsetivli x0,4,e32,m1,ta,ma
        BigInt(0x1004) -> BigInt("0000a087", 16), // flw f1, 0(x1)
        BigInt(0x1008) -> BigInt("01008293", 16), // addi x5, x1, 16
        BigInt(0x100c) -> BigInt("0202e107", 16), // vle32.v v2, (x5)
        BigInt(0x1010) -> BigInt("4200d157", 16), // vfmv.s.f v2, f1
        BigInt(0x1014) -> BigInt("04008293", 16), // addi x5, x1, 64
        BigInt(0x1018) -> BigInt("0202e127", 16), // vse32.v v2, (x5)
        BigInt(0x101c) -> BigInt("42201157", 16), // vfmv.f.s f2, v2
        BigInt(0x1020) -> BigInt("0820a027", 16), // fsw f2, 128(x1)
        BigInt(0x1024) -> BigInt("30500073", 16)) // cease
      program.foreach { case (addr, word) => mem.putWord(addr, word) }
      mem.putWord(BigInt(0x8000), BigInt("40000000", 16)) // 2.0f
      Seq("40800000", "40a00000", "40c00000").zipWithIndex.foreach {
        case (bits, lane) =>
          mem.putWord(BigInt(0x8010) + lane * 4, BigInt(bits, 16))
      }

      val result = runShader(dut, mem, limit = 4000)
      assert(result.traps.isEmpty, show(result.traps))
      val stored = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8040) + lane * 4))
      assert(stored == Seq("40000000", "40a00000", "40c00000").map(BigInt(_, 16)),
        s"vfmv.s.f stored ${stored.map(_.toString(16))}")
      assert(storedWord(result.writes, BigInt(0x8080)) == BigInt("40000000", 16),
        "vfmv.f.s did not return element 0")
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  // VL=3 puts the vslide1down scalar on an active lane. vslide1up inserts
  // the same scalar at element 0.
  it should "slide one element by a scalar on the shader CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      val program = Seq(
        BigInt(0x1000) -> BigInt("c101f057", 16), // vsetivli x0,3,e32,m1,ta,ma
        BigInt(0x1004) -> BigInt("01008293", 16), // addi x5, x1, 16
        BigInt(0x1008) -> BigInt("0202e107", 16), // vle32.v v2, (x5)
        BigInt(0x100c) -> BigInt("05a00513", 16), // addi x10, x0, 0x5a
        BigInt(0x1010) -> BigInt("3a2561d7", 16), // vslide1up.vx v3, v2, x10
        BigInt(0x1014) -> BigInt("04008293", 16), // addi x5, x1, 64
        BigInt(0x1018) -> BigInt("0202e1a7", 16), // vse32.v v3, (x5)
        BigInt(0x101c) -> BigInt("3e256257", 16), // vslide1down.vx v4, v2, x10
        BigInt(0x1020) -> BigInt("08008293", 16), // addi x5, x1, 128
        BigInt(0x1024) -> BigInt("0202e227", 16), // vse32.v v4, (x5)
        BigInt(0x1028) -> BigInt("30500073", 16)) // cease
      program.foreach { case (addr, word) => mem.putWord(addr, word) }
      Seq(11, 22, 33).zipWithIndex.foreach { case (value, lane) =>
        mem.putWord(BigInt(0x8010) + lane * 4, BigInt(value))
      }

      val result = runShader(dut, mem, limit = 4000)
      assert(result.traps.isEmpty, show(result.traps))
      val up = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8040) + lane * 4))
      assert(up == Seq(BigInt(0x5a), BigInt(11), BigInt(22)),
        s"vslide1up stored ${up.map(_.toString(16))}")
      val down = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8080) + lane * 4))
      assert(down == Seq(BigInt(22), BigInt(33), BigInt(0x5a)),
        s"vslide1down stored ${down.map(_.toString(16))}")
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  // The .vv forms take a vector of per-element offsets, so each lane shifts by
  // its own amount instead of one shared scalar. VL=3 and a four-element
  // offset vector exercise both the offset-per-element path and the lanes
  // outside VL that keep the old destination.
  it should "slide by a per-element vector offset on the shader CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      val program = Seq(
        BigInt(0x1000) -> BigInt("c101f057", 16), // vsetivli x0,3,e32,m1,ta,ma
        BigInt(0x1004) -> BigInt("01008293", 16), // addi x5, x1, 16
        BigInt(0x1008) -> BigInt("0202e107", 16), // vle32.v v2, (x5)
        BigInt(0x100c) -> BigInt("02008313", 16), // addi x6, x1, 32
        BigInt(0x1010) -> BigInt("02036207", 16), // vle32.v v4, (x6)
        BigInt(0x1014) -> BigInt("03008393", 16), // addi x7, x1, 48
        BigInt(0x1018) -> BigInt("0203e287", 16), // vle32.v v5, (x7)
        BigInt(0x101c) -> BigInt("022031d7", 16), // vadd.vi v3, v2, 0
        BigInt(0x1020) -> BigInt("3a2201d7", 16), // vslideup.vv v3, v2, v4
        BigInt(0x1024) -> BigInt("04008293", 16), // addi x5, x1, 64
        BigInt(0x1028) -> BigInt("0202e1a7", 16), // vse32.v v3, (x5)
        BigInt(0x102c) -> BigInt("02203357", 16), // vadd.vi v6, v2, 0
        BigInt(0x1030) -> BigInt("3e228357", 16), // vslidedown.vv v6, v2, v5
        BigInt(0x1034) -> BigInt("08008293", 16), // addi x5, x1, 128
        BigInt(0x1038) -> BigInt("0202e327", 16), // vse32.v v6, (x5)
        BigInt(0x103c) -> BigInt("30500073", 16)) // cease
      program.foreach { case (addr, word) => mem.putWord(addr, word) }
      Seq(11, 22, 33).zipWithIndex.foreach { case (value, lane) =>
        mem.putWord(BigInt(0x8010) + lane * 4, BigInt(value))
      }
      // vslideup offsets 3,2,1,0: only element 2 and 3 have room to slide.
      Seq(3, 2, 1, 0).zipWithIndex.foreach { case (value, lane) =>
        mem.putWord(BigInt(0x8020) + lane * 4, BigInt(value))
      }
      // vslidedown offsets 0,1,2,3: element 2 runs off the top and reads zero.
      Seq(0, 1, 2, 3).zipWithIndex.foreach { case (value, lane) =>
        mem.putWord(BigInt(0x8030) + lane * 4, BigInt(value))
      }

      val result = runShader(dut, mem, limit = 4000)
      assert(result.traps.isEmpty, show(result.traps))
      // Elements 0 and 1 keep the old v3 the vadd.vi above wrote.
      val up = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8040) + lane * 4))
      assert(up == Seq(BigInt(11), BigInt(22), BigInt(22)),
        s"vslideup.vv stored ${up.map(_.toString(16))}")
      val down = (0 until 3).map(lane =>
        storedWord(result.writes, BigInt(0x8080) + lane * 4))
      assert(down == Seq(BigInt(11), BigInt(33), BigInt(0)),
        s"vslidedown.vv stored ${down.map(_.toString(16))}")
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  it should "dot a clip-x row from kernarg floats" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      idle(dut)
      val mem = new Mem
      val header = scala.io.Source.fromFile(
        "userspace/gallium/opengpu_shaders.h").mkString
      val start = header.indexOf("opengpu_vertex_mvp[]")
      val body = header.slice(start, header.indexOf("#define", start))
      val words = "0x([0-9a-fA-F]+)u".r.findAllMatchIn(body).map(_.group(1)).toSeq
      words.zipWithIndex.foreach { case (word, i) =>
        mem.putWord(BigInt(0x1000 + i * 4), BigInt(word, 16))
      }
      val negOne = BigInt("bf800000", 16)
      val one = BigInt("3f800000", 16)
      val half = BigInt("3f000000", 16)
      // Lane 0 is vertex (-1,-1,0,1). Stride between attributes is 32.
      // Three vertices, attribute-major, 32-byte stride. Lane 0 alone hides a
      // cross-lane store/FMA fault the guest hits on this triangle.
      val px = Seq(negOne, one, negOne)
      val py = Seq(negOne, negOne, one)
      val pz = Seq(BigInt(0), BigInt(0), BigInt(0))
      val pw = Seq(one, one, one)
      val color = Seq(BigInt("ffff00ff", 16), BigInt("ffff00ff", 16),
        BigInt("ffff00ff", 16))
      val depth = Seq(BigInt(0x10), BigInt(0x10), BigInt(0x10))
      val tu = Seq(BigInt(0), BigInt("10000", 16), BigInt("8000", 16))
      val tv = Seq(BigInt(0), BigInt(0), BigInt("10000", 16))
      Seq(px, py, pz, pw, color, depth, tu, tv).zipWithIndex.foreach {
        case (attr, attrIndex) =>
          attr.zipWithIndex.foreach { case (value, lane) =>
            mem.putWord(BigInt(0x8000) + attrIndex * 32 + lane * 4, value)
          }
      }
      val matrix = Seq(
        one, BigInt(0), BigInt(0), BigInt(0),
        BigInt(0), one, BigInt(0), BigInt(0),
        BigInt(0), BigInt(0), one, BigInt(0),
        half, BigInt(0), BigInt(0), one
      )
      matrix.zipWithIndex.foreach { case (value, i) =>
        mem.putWord(BigInt(0x8200) + i * 4, value)
      }
      mem.putWord(BigInt(0x8240), BigInt("47800000", 16))

      val result = runShader(dut, mem, limit = 20000)
      assert(result.traps.isEmpty, show(result.traps))
      def laneWord(base: Int, lane: Int) =
        storedWord(result.writes, BigInt(base) + lane * 4)
      val expectClip = Seq(
        Seq(BigInt("ffff8000", 16), BigInt("ffff0000", 16), BigInt(0),
          BigInt("10000", 16)),
        Seq(BigInt("18000", 16), BigInt("ffff0000", 16), BigInt(0),
          BigInt("10000", 16)),
        Seq(BigInt("ffff8000", 16), BigInt("10000", 16), BigInt(0),
          BigInt("10000", 16)))
      val clipBases = Seq(0x8100, 0x8120, 0x8140, 0x8160)
      (0 until 3).foreach { lane =>
        val got = clipBases.map(base => laneWord(base, lane))
        assert(got == expectClip(lane),
          s"lane $lane clip ${got.map(v => f"0x$v%x")}")
        assert(laneWord(0x8180, lane) == color(lane),
          s"lane $lane color 0x${laneWord(0x8180, lane).toString(16)}")
        assert(laneWord(0x81a0, lane) == BigInt(0),
          s"lane $lane depth 0x${laneWord(0x81a0, lane).toString(16)}")
        assert(laneWord(0x81c0, lane) == tu(lane) &&
          laneWord(0x81e0, lane) == tv(lane),
          s"lane $lane uv 0x${laneWord(0x81c0, lane).toString(16)} " +
            s"0x${laneWord(0x81e0, lane).toString(16)}")
      }
      dut.io.completion.bits.success.expect(true.B)
    }
  }
}
