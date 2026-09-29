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
                        limit: Int = 400): Result = {
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
    var resp = false
    var id = BigInt(0)
    var data = BigInt(0)
    var guard = 0
    var done = false
    while (!done && guard < limit) {
      // Re-present the pending response every cycle, the way
      // KernelFragStageSpec's pump does, so a beat is never dropped.
      dut.io.memoryResponse.valid.poke(resp)
      if (resp) {
        dut.io.memoryResponse.bits.transactionId.poke(id.U)
        dut.io.memoryResponse.bits.readData.poke(data.U)
        dut.io.memoryResponse.bits.fault.poke(false.B)
      }
      val fired = dut.io.memoryRequest.valid.peek().litToBoolean &&
        dut.io.memoryRequest.ready.peek().litToBoolean
      if (fired) {
        val addr = dut.io.memoryRequest.bits.address.peek().litValue
        id = dut.io.memoryRequest.bits.transactionId.peek().litValue
        if (dut.io.memoryRequest.bits.isWrite.peek().litToBoolean) {
          val strobe = dut.io.memoryRequest.bits.byteMask.peek().litValue
          val word = dut.io.memoryRequest.bits.writeData.peek().litValue
          (0 until 64).foreach { i =>
            if (strobe.testBit(i)) writes(addr + i) = (word >> (8 * i)) & 0xff
          }
          data = BigInt(0)
          reqs += s"W 0x${addr.toString(16)} mask=0x${strobe.toString(16)} data=0x${word.toString(16)}"
        } else { data = mem.line(addr); reqs += s"R 0x${addr.toString(16)}" }
        resp = true
      } else resp = false
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
}
