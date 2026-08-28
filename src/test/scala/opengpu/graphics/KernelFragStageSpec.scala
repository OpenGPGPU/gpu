package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class KernelFragStageSpec extends AnyFlatSpec {
  behavior of "KernelFragStage"

  // Batched SoA ABI (byte offsets from the draw's kernarg base; stride =
  // 4 * warps * lanes = 32 for the test config):
  //   [0,   32)   per-fragment x (sign-extended i32)
  //   [32,  64)   per-fragment y
  //   [64,  96)   per-fragment depth
  //   [96,  128)  per-fragment packed-colour inputs
  //   [128, 160)  per-fragment colour outputs
  //   [192, ...)  per-draw uniforms

  /** Byte-addressed 64-byte line memory model shared by the core and the
    * word->line bridge.  Lines are keyed by their aligned byte address.
    */
  private class MemModel {
    val lines = scala.collection.mutable.LongMap[BigInt]()
    def putWord(lineAddr: Long, wordIdx: Int, value: BigInt): Unit = {
      val base = lines.getOrElse(lineAddr, BigInt(0))
      val mask = (BigInt(0xffffffffL) << (wordIdx * 32))
      lines(lineAddr) = (base & ~mask) | (value << (wordIdx * 32))
    }
    def applyWrite(addr: Long, writeData: BigInt, byteMask: BigInt): Unit = {
      var line = lines.getOrElse(addr, BigInt(0))
      for (b <- 0 until 64) {
        if (((byteMask >> b) & 1) != 0) {
          line = (line & ~(BigInt(0xff) << (b * 8))) |
            (((writeData >> (b * 8)) & 0xff) << (b * 8))
        }
      }
      lines(addr) = line
    }
    def readLine(addr: Long): BigInt = lines.getOrElse(addr, BigInt(0))
  }

  /** Shader program snippet helpers (RV32I + the RVV subset). */
  private def lw(rd: Int, rs1: Int, imm: Int): BigInt =
    ((BigInt(imm & 0xfff)) << 20) | ((BigInt(rs1 & 0x1f)) << 15) |
      ((BigInt(2)) << 12) | ((BigInt(rd & 0x1f)) << 7) | 0x03
  private def sw(rs2: Int, rs1: Int, imm: Int): BigInt = {
    val i = imm & 0xfff
    ((BigInt(i >> 5)) << 25) | ((BigInt(rs2 & 0x1f)) << 20) |
      ((BigInt(rs1 & 0x1f)) << 15) | ((BigInt(2)) << 12) |
      ((BigInt(i & 0x1f)) << 7) | 0x23
  }
  private def add(rd: Int, rs1: Int, rs2: Int): BigInt =
    ((BigInt(rs2 & 0x1f)) << 20) | ((BigInt(rs1 & 0x1f)) << 15) |
      ((BigInt(rd & 0x1f)) << 7) | 0x33
  /** opengpu.texsample rd, rs1, rs2 (custom-0, funct7=1, funct3=0). */
  private def texsample(rd: Int, rs1: Int, rs2: Int): BigInt =
    (BigInt(1) << 25) | (BigInt(rs2 & 0x1f) << 20) |
      (BigInt(rs1 & 0x1f) << 15) | (BigInt(rd & 0x1f) << 7) | 0x0b
  /** opengpu.vtex.sample vd, vs1, vs2 (custom-1, funct6=1, vm=1). */
  private def vtexsample(vd: Int, vs1: Int, vs2: Int): BigInt =
    (BigInt(1) << 26) | (BigInt(1) << 25) |
      (BigInt(vs2 & 0x1f) << 20) | (BigInt(vs1 & 0x1f) << 15) |
      (BigInt(vd & 0x1f) << 7) | 0x2b

  private def slli(rd: Int, rs1: Int, shamt: Int): BigInt =
    ((BigInt(shamt & 0x1f)) << 20) | ((BigInt(rs1 & 0x1f)) << 15) |
      (BigInt(1) << 12) | ((BigInt(rd & 0x1f)) << 7) | 0x13
  private def addi(rd: Int, rs1: Int, imm: Int): BigInt =
    ((BigInt(imm & 0xfff)) << 20) | ((BigInt(rs1 & 0x1f)) << 15) |
      ((BigInt(rd & 0x1f)) << 7) | 0x13
  private def vsetivli(uimm: Int): BigInt =
    (BigInt(0x3) << 30) | (BigInt(0x10) << 20) | (BigInt(uimm & 0x1f) << 15) |
      (BigInt(0x7) << 12) | 0x57
  // Vector loads/stores are encoded unmasked (vm=1), matching the assembler
  // default `vle32.v vd, (rs1)`: the mask operand v0 is not read.
  private def vle32(rs1: Int, vd: Int): BigInt =
    (BigInt(1) << 25) | (BigInt(0x6) << 12) | (BigInt(vd & 0x1f) << 7) |
      (BigInt(rs1 & 0x1f) << 15) | 0x07
  private def vse32(rs1: Int, vs3: Int): BigInt =
    (BigInt(1) << 25) | (BigInt(0x6) << 12) | (BigInt(vs3 & 0x1f) << 7) |
      (BigInt(rs1 & 0x1f) << 15) | 0x27
  private def vaddVv(vd: Int, vs2: Int, vs1: Int): BigInt =
    (BigInt(1) << 25) | (BigInt(vs2 & 0x1f) << 20) |
      (BigInt(vs1 & 0x1f) << 15) | (BigInt(vd & 0x1f) << 7) | 0x57
  private val cease = BigInt("30500073", 16)

  /** Services KernelFragStage's two memory ports against `mem` until `pred`
    * becomes true or a guard timeout expires.  Returns the final predicate.
    */
  private val LineMask = (BigInt(1) << 512) - 1

  private def pump(
    dut: KernelFragStage,
    mem: MemModel,
    pred: () => Boolean,
    guard: Int = 400
  ): Boolean = {
    var kResp = false; var kId = BigInt(0); var kData = BigInt(0)
    var wResp = false; var wId = BigInt(0); var wData = BigInt(0)
    var g = 0
    var hit = pred()
    while (!hit && g < guard) {
      dut.io.memResp.valid.poke(kResp)
      if (kResp) {
        dut.io.memResp.bits.transactionId.poke(kId.U)
        dut.io.memResp.bits.readData.poke((kData & LineMask).U)
        dut.io.memResp.bits.fault.poke(false.B)
      }
      dut.io.wordMemResp.valid.poke(wResp)
      if (wResp) {
        dut.io.wordMemResp.bits.transactionId.poke(wId.U)
        dut.io.wordMemResp.bits.readData.poke((wData & LineMask).U)
        dut.io.wordMemResp.bits.fault.poke(false.B)
      }
      val kFired = dut.io.memReq.valid.peek().litToBoolean &&
        dut.io.memReq.ready.peek().litToBoolean
      if (kFired) {
        val addr = dut.io.memReq.bits.address.peek().litValue.toLong
        val id = dut.io.memReq.bits.transactionId.peek().litValue
        if (dut.io.memReq.bits.isWrite.peek().litToBoolean) {
          val wd = dut.io.memReq.bits.writeData.peek().litValue
          val bm = dut.io.memReq.bits.byteMask.peek().litValue
          mem.applyWrite(addr, wd, bm); kData = 0
        } else kData = mem.readLine(addr)
        kId = id; kResp = true
      } else kResp = false
      val wFired = dut.io.wordMemReq.valid.peek().litToBoolean &&
        dut.io.wordMemReq.ready.peek().litToBoolean
      if (wFired) {
        val addr = dut.io.wordMemReq.bits.address.peek().litValue.toLong
        val id = dut.io.wordMemReq.bits.transactionId.peek().litValue
        if (dut.io.wordMemReq.bits.isWrite.peek().litToBoolean) {
          val wd = dut.io.wordMemReq.bits.writeData.peek().litValue
          val bm = dut.io.wordMemReq.bits.byteMask.peek().litValue
          mem.applyWrite(addr, wd, bm); wData = 0
        } else wData = mem.readLine(addr)
        wId = id; wResp = true
      } else wResp = false
      dut.clock.step()
      g += 1
      hit = pred()
    }
    // Do not leak the final response-valid pulse to a caller that continues
    // stepping after the predicate becomes true.
    dut.io.memResp.valid.poke(false.B)
    dut.io.wordMemResp.valid.poke(false.B)
    hit
  }

  private def pokeDefaults(dut: KernelFragStage): Unit = {
    dut.io.fragIn.valid.poke(false.B)
    dut.io.out.ready.poke(true.B)
    dut.io.flush.poke(false.B)
    dut.io.memReq.ready.poke(true.B)
    dut.io.memResp.valid.poke(false.B)
    dut.io.memResp.bits.readData.poke(0.U)
    dut.io.memResp.bits.fault.poke(false.B)
    dut.io.memResp.bits.transactionId.poke(0.U)
    dut.io.wordMemReq.ready.poke(true.B)
    dut.io.wordMemResp.valid.poke(false.B)
    dut.io.wordMemResp.bits.readData.poke(0.U)
    dut.io.wordMemResp.bits.fault.poke(false.B)
    dut.io.wordMemResp.bits.transactionId.poke(0.U)
  }

  it should "shade a single fragment via a batched pass-through kernel" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelFragStage(config)) { dut =>
      val mem = new MemModel
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      pokeDefaults(dut)
      dut.io.shaderPc.poke(0x1000.U)
      dut.io.kernargBase.poke(0x8000.U)

      // Program: read the packed-colour input array (kernarg+96), write the
      // output array (kernarg+128), halt.
      mem.putWord(0x1000L, 0, lw(10, 1, 96))
      mem.putWord(0x1000L, 1, sw(10, 1, 128))
      mem.putWord(0x1000L, 2, cease)

      dut.io.fragIn.bits.x.poke(3.S)
      dut.io.fragIn.bits.y.poke(4.S)
      dut.io.fragIn.bits.depth.poke(0x20.S)
      dut.io.fragIn.bits.color.r.poke(0xab.U)
      dut.io.fragIn.bits.color.g.poke(0xcd.U)
      dut.io.fragIn.bits.color.b.poke(0xef.U)
      dut.io.fragIn.valid.poke(true.B)
      dut.clock.step()
      dut.io.fragIn.valid.poke(false.B)

      // Flush the (non-empty) batch: draw boundary.
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)

      val done = pump(dut, mem, () => dut.io.out.valid.peek().litToBoolean)
      assert(done, "fragment did not finish within a bounded time")
      dut.io.out.bits.x.expect(3.S)
      dut.io.out.bits.y.expect(4.S)
      dut.io.out.bits.depth.expect(0x20.S)
      dut.io.out.bits.color.r.expect(0xab.U)
      dut.io.out.bits.color.g.expect(0xcd.U)
      dut.io.out.bits.color.b.expect(0xef.U)
    }
  }

  it should "shade a single fragment through a kernel adding a uniform" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelFragStage(config)) { dut =>
      val mem = new MemModel
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      pokeDefaults(dut)
      dut.io.shaderPc.poke(0x1000.U)
      dut.io.kernargBase.poke(0x8000.U)

      // Program: colour = input[0] (kernarg+96) + uniform (kernarg+192);
      //          output[0] (kernarg+128); cease.
      mem.putWord(0x1000L, 0, lw(10, 1, 96))
      mem.putWord(0x1000L, 1, lw(11, 1, 192))
      mem.putWord(0x1000L, 2, add(10, 10, 11))
      mem.putWord(0x1000L, 3, sw(10, 1, 128))
      mem.putWord(0x1000L, 4, cease)

      // Uniform at kernarg+192 (line 0x80c0, word 0): +1 blue.
      mem.putWord(0x80c0L, 0, BigInt("00000100", 16))

      dut.io.fragIn.bits.x.poke(3.S)
      dut.io.fragIn.bits.y.poke(4.S)
      dut.io.fragIn.bits.depth.poke(0x20.S)
      dut.io.fragIn.bits.color.r.poke(0xab.U)
      dut.io.fragIn.bits.color.g.poke(0xcd.U)
      dut.io.fragIn.bits.color.b.poke(0xef.U)
      dut.io.fragIn.valid.poke(true.B)
      dut.clock.step()
      dut.io.fragIn.valid.poke(false.B)

      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)

      val done = pump(dut, mem, () => dut.io.out.valid.peek().litToBoolean)
      assert(done, "fragment did not finish within a bounded time")
      // Reference: (0xab,0xcd,0xef,0xff) + (blue+1) -> (0xab,0xcd,0xf0).
      dut.io.out.bits.x.expect(3.S)
      dut.io.out.bits.y.expect(4.S)
      dut.io.out.bits.color.r.expect(0xab.U)
      dut.io.out.bits.color.g.expect(0xcd.U)
      dut.io.out.bits.color.b.expect(0xf0.U)
    }
  }

  it should "accumulate multiple fragments into one flushed batch, emitted in order" in {
    // Batched dispatch: several fragments are accumulated and flushed as ONE
    // kernel launch (localSize = count) rather than one launch per fragment.
    // The FSM buffers each fragment's x/y/depth locally and re-emits the batch
    // in submission order after the kernel's output words are read back.  Here
    // a scalar pass-through kernel is used (scalar registers are per-warp
    // broadcast, so only fragment 0's colour slot holds a value); the geometry
    // and ordering of every batched fragment are the contract under test.
    val config = GpuConfig(lanes = 4, warps = 2)
    val count = 3
    simulate(new KernelFragStage(config)) { dut =>
      val mem = new MemModel
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      pokeDefaults(dut)
      dut.io.shaderPc.poke(0x1000.U)
      dut.io.kernargBase.poke(0x8000.U)

      // Pass-through scalar kernel: read the colour input word, write the
      // output word, cease.
      mem.putWord(0x1000L, 0, lw(10, 1, 96))
      mem.putWord(0x1000L, 1, sw(10, 1, 128))
      mem.putWord(0x1000L, 2, cease)

      // Feed a batch of fragments with distinct x/y/depth.
      val inputs = Seq(
        (0, 10, 0x20),
        (1, 11, 0x30),
        (2, 12, 0x40)
      )
      for ((x, y, d) <- inputs) {
        dut.io.fragIn.bits.x.poke(x.S)
        dut.io.fragIn.bits.y.poke(y.S)
        dut.io.fragIn.bits.depth.poke(d.S)
        dut.io.fragIn.bits.color.r.poke(0xab.U)
        dut.io.fragIn.bits.color.g.poke(0xcd.U)
        dut.io.fragIn.bits.color.b.poke(0xef.U)
        dut.io.fragIn.valid.poke(true.B)
        dut.clock.step()
        dut.io.fragIn.valid.poke(false.B)
      }

      // The batch is not yet launched: no output is pending until flush.
      assert(!dut.io.out.valid.peek().litToBoolean,
        "an unflushed batch must not emit fragments")

      // Flush the (non-empty) batch: draw boundary launches one kernel.
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)

      // Collect every emitted fragment in order, serving memory with deferred
      // responses: a request is captured on the cycle it fires and its response
      // is presented on a later cycle once the port re-asserts validity.
      val emitted = scala.collection.mutable.ArrayBuffer.empty[(Long, Long, Long)]
      val kQueue = scala.collection.mutable.Queue.empty[(BigInt, BigInt)]
      val wQueue = scala.collection.mutable.Queue.empty[(BigInt, BigInt)]
      var guard = 0
      var drained = false
      while (!drained && guard < 4000) {
        if (kQueue.nonEmpty) {
          dut.io.memResp.valid.poke(true.B)
          dut.io.memResp.bits.transactionId.poke(kQueue.head._1.U)
          dut.io.memResp.bits.readData.poke(kQueue.head._2.U)
          dut.io.memResp.bits.fault.poke(false.B)
        } else dut.io.memResp.valid.poke(false.B)
        if (kQueue.nonEmpty && dut.io.memResp.ready.peek().litToBoolean) kQueue.dequeue()
        if (dut.io.memReq.valid.peek().litToBoolean && dut.io.memReq.ready.peek().litToBoolean) {
          val addr = dut.io.memReq.bits.address.peek().litValue.toLong
          val id = dut.io.memReq.bits.transactionId.peek().litValue
          if (dut.io.memReq.bits.isWrite.peek().litToBoolean) {
            mem.applyWrite(addr, dut.io.memReq.bits.writeData.peek().litValue,
              dut.io.memReq.bits.byteMask.peek().litValue)
            kQueue.enqueue((id, BigInt(0)))
          } else kQueue.enqueue((id, mem.readLine(addr)))
        }
        if (wQueue.nonEmpty) {
          dut.io.wordMemResp.valid.poke(true.B)
          dut.io.wordMemResp.bits.transactionId.poke(wQueue.head._1.U)
          dut.io.wordMemResp.bits.readData.poke(wQueue.head._2.U)
          dut.io.wordMemResp.bits.fault.poke(false.B)
        } else dut.io.wordMemResp.valid.poke(false.B)
        if (wQueue.nonEmpty && dut.io.wordMemResp.ready.peek().litToBoolean) wQueue.dequeue()
        if (dut.io.wordMemReq.valid.peek().litToBoolean &&
          dut.io.wordMemReq.ready.peek().litToBoolean) {
          val addr = dut.io.wordMemReq.bits.address.peek().litValue.toLong
          val id = dut.io.wordMemReq.bits.transactionId.peek().litValue
          if (dut.io.wordMemReq.bits.isWrite.peek().litToBoolean) {
            mem.applyWrite(addr, dut.io.wordMemReq.bits.writeData.peek().litValue,
              dut.io.wordMemReq.bits.byteMask.peek().litValue)
            wQueue.enqueue((id, BigInt(0)))
          } else wQueue.enqueue((id, mem.readLine(addr)))
        }
        if (dut.io.out.valid.peek().litToBoolean) {
          emitted += ((dut.io.out.bits.x.peek().litValue.toLong,
            dut.io.out.bits.y.peek().litValue.toLong,
            dut.io.out.bits.depth.peek().litValue.toLong))
          dut.io.out.ready.poke(true.B)
        } else dut.io.out.ready.poke(false.B)
        dut.clock.step()
        guard += 1
        if (emitted.size == count && dut.io.drained.peek().litToBoolean)
          drained = true
      }
      assert(emitted.size == count, s"expected $count fragments, got ${emitted.size}")

      // Fragments are emitted in submission order with geometry/depth preserved.
      for ((expected, got) <- inputs zip emitted) {
        assert(got._1 == expected._1 &&
          got._2 == expected._2 && got._3 == expected._3,
          s"fragment geometry mismatch: expected $expected got $got")
      }
    }
  }

  it should "shade a full-warp batch per lane through the vector memory path" in {
    // Lane-aware kernel: fragment i is lane i.  Each warp computes its batch
    // slice from the launch ABI (x1 = kernarg, x8 = localLinearBase) and moves
    // all of its lanes' colours with one vector load/store pair.  This is the
    // end-to-end check of the core's vle32/vse32 round-trip: the output store
    // must cover all four lanes (byteMask 0xffff) and every fragment must get
    // its own colour back, not lane 0's.
    val config = GpuConfig(lanes = 4, warps = 2)
    val count = 4
    simulate(new KernelFragStage(config)) { dut =>
      val mem = new MemModel
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      pokeDefaults(dut)
      dut.io.shaderPc.poke(0x1000.U)
      dut.io.kernargBase.poke(0x8000.U)

      // Program (lane = fragment; x1 = kernarg, x8 = localLinearBase):
      //   slli x5, x8, 2      // x5 = 4 * localLinearBase (this warp's slice)
      //   add  x5, x1, x5     // x5 = kernarg base of this warp's slice
      //   vsetivli x0, 4, e32
      //   vle32.v v2, (x5)    // per-lane x array
      //   addi x6, x5, 96
      //   vle32.v v3, (x6)    // per-lane packed-colour inputs
      //   vadd.vv v4, v3, v2  // colour + x, per lane
      //   addi x6, x5, 128
      //   vse32.v v4, (x6)    // per-lane colour outputs
      //   cease
      mem.putWord(0x1000L, 0, slli(5, 8, 2))
      mem.putWord(0x1000L, 1, add(5, 1, 5))
      mem.putWord(0x1000L, 2, vsetivli(4))
      mem.putWord(0x1000L, 3, vle32(5, 2))
      mem.putWord(0x1000L, 4, addi(6, 5, 96))
      mem.putWord(0x1000L, 5, vle32(6, 3))
      mem.putWord(0x1000L, 6, vaddVv(4, 3, 2))
      mem.putWord(0x1000L, 7, addi(6, 5, 128))
      mem.putWord(0x1000L, 8, vse32(6, 4))
      mem.putWord(0x1000L, 9, cease)

      // Feed a full-warp batch of fragments with distinct colours.
      val colors = Seq(
        (0x10, 0x20, 0x30),
        (0x40, 0x50, 0x60),
        (0x70, 0x80, 0x90),
        (0xa0, 0xb0, 0xc0)
      )
      for (i <- 0 until count) {
        dut.io.fragIn.bits.x.poke(i.S)
        dut.io.fragIn.bits.y.poke((10 + i).S)
        dut.io.fragIn.bits.depth.poke(0x20.S)
        dut.io.fragIn.bits.color.r.poke(colors(i)._1.U)
        dut.io.fragIn.bits.color.g.poke(colors(i)._2.U)
        dut.io.fragIn.bits.color.b.poke(colors(i)._3.U)
        dut.io.fragIn.valid.poke(true.B)
        dut.clock.step()
        dut.io.fragIn.valid.poke(false.B)
      }

      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)

      val emitted =
        scala.collection.mutable.ArrayBuffer.empty[(Long, Long, Long)]
      val kernelStores =
        scala.collection.mutable.ArrayBuffer.empty[(Long, BigInt)]
      val kQueue = scala.collection.mutable.Queue.empty[(BigInt, BigInt)]
      val wQueue = scala.collection.mutable.Queue.empty[(BigInt, BigInt)]
      var guard = 0
      var drained = false
      while (!drained && guard < 4000) {
        if (kQueue.nonEmpty) {
          dut.io.memResp.valid.poke(true.B)
          dut.io.memResp.bits.transactionId.poke(kQueue.head._1.U)
          dut.io.memResp.bits.readData.poke(kQueue.head._2.U)
          dut.io.memResp.bits.fault.poke(false.B)
        } else dut.io.memResp.valid.poke(false.B)
        if (kQueue.nonEmpty && dut.io.memResp.ready.peek().litToBoolean) kQueue.dequeue()
        if (dut.io.memReq.valid.peek().litToBoolean && dut.io.memReq.ready.peek().litToBoolean) {
          val addr = dut.io.memReq.bits.address.peek().litValue.toLong
          val id = dut.io.memReq.bits.transactionId.peek().litValue
          if (dut.io.memReq.bits.isWrite.peek().litToBoolean) {
            val bm = dut.io.memReq.bits.byteMask.peek().litValue
            mem.applyWrite(addr, dut.io.memReq.bits.writeData.peek().litValue, bm)
            kernelStores += ((addr, bm))
            kQueue.enqueue((id, BigInt(0)))
          } else kQueue.enqueue((id, mem.readLine(addr)))
        }
        if (wQueue.nonEmpty) {
          dut.io.wordMemResp.valid.poke(true.B)
          dut.io.wordMemResp.bits.transactionId.poke(wQueue.head._1.U)
          dut.io.wordMemResp.bits.readData.poke(wQueue.head._2.U)
          dut.io.wordMemResp.bits.fault.poke(false.B)
        } else dut.io.wordMemResp.valid.poke(false.B)
        if (wQueue.nonEmpty && dut.io.wordMemResp.ready.peek().litToBoolean) wQueue.dequeue()
        if (dut.io.wordMemReq.valid.peek().litToBoolean &&
          dut.io.wordMemReq.ready.peek().litToBoolean) {
          val addr = dut.io.wordMemReq.bits.address.peek().litValue.toLong
          val id = dut.io.wordMemReq.bits.transactionId.peek().litValue
          if (dut.io.wordMemReq.bits.isWrite.peek().litToBoolean) {
            mem.applyWrite(addr, dut.io.wordMemReq.bits.writeData.peek().litValue,
              dut.io.wordMemReq.bits.byteMask.peek().litValue)
            wQueue.enqueue((id, BigInt(0)))
          } else wQueue.enqueue((id, mem.readLine(addr)))
        }
        if (dut.io.out.valid.peek().litToBoolean) {
          emitted += ((dut.io.out.bits.color.r.peek().litValue.toLong,
            dut.io.out.bits.color.g.peek().litValue.toLong,
            dut.io.out.bits.color.b.peek().litValue.toLong))
          dut.io.out.ready.poke(true.B)
        } else dut.io.out.ready.poke(false.B)
        dut.clock.step()
        guard += 1
        if (emitted.size == count && dut.io.drained.peek().litToBoolean)
          drained = true
      }
      assert(emitted.size == count, s"expected $count fragments, got ${emitted.size}")

      // The vector output store covers all four lanes of the warp in one
      // 16-byte mask at kernarg+128.
      assert(kernelStores.exists { case (addr, bm) =>
        addr == 0x8080L && bm == BigInt(0xffff)
      }, s"expected a full 4-lane store at 0x8080, got $kernelStores")

      // Every fragment gets colour + its own x back, in submission order.
      for (i <- 0 until count) {
        val packed = (BigInt(colors(i)._1) << 24) | (BigInt(colors(i)._2) << 16) |
          (BigInt(colors(i)._3) << 8) | 0xff
        val reference = (packed + i) & 0xffffffffL
        val expected = ((reference >> 24) & 0xff, (reference >> 16) & 0xff,
          (reference >> 8) & 0xff)
        val got = (emitted(i)._1, emitted(i)._2, emitted(i)._3)
        assert(got == expected,
          s"per-lane shade mismatch at fragment $i: expected $expected got $got")
      }
    }
  }

  it should "sample a texture through the tex.sample instruction" in {
    // Kernel-side texture path: decode (system path) -> TexSampleUnit ->
    // TextureUnit fetches over the shared word bridge -> scalar commit
    // writeback of the packed texel word.
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelFragStage(config)) { dut =>
      val mem = new MemModel
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      pokeDefaults(dut)
      dut.io.shaderPc.poke(0x1000.U)
      dut.io.kernargBase.poke(0x8000.U)
      dut.io.texBase.poke(0x2000.U)
      dut.io.texWidth.poke(2.U)
      dut.io.texHeight.poke(2.U)
      dut.io.texWrapClamp.poke(false.B)

      // Program: u = uniform(192), v = uniform(196); sample; output[0] = rd.
      mem.putWord(0x1000L, 0, lw(10, 1, 192))
      mem.putWord(0x1000L, 1, lw(11, 1, 196))
      mem.putWord(0x1000L, 2, texsample(12, 10, 11))
      mem.putWord(0x1000L, 3, sw(12, 1, 128))
      mem.putWord(0x1000L, 4, cease)

      // Uniforms: u = v = 0.75 (Q16.16) -- the centre of texel (1,1),
      // so the sample must return the pure yellow texel word.
      mem.putWord(0x80c0L, 0, 0xc000)
      mem.putWord(0x80c0L, 1, 0xc000)

      // 2x2 gradient: black / red / green / yellow.  Texel words use the
      // pipeline's packed-colour byte order (colour channels in the top
      // three bytes) so the kernel's raw sw round-trips r directly.
      // Pipeline colour packing: r<<24 | g<<16 | b<<8 | alpha.
      mem.putWord(0x2000L, 0, 0x000000ff) // black
      mem.putWord(0x2000L, 1, 0xff0000ff) // red
      mem.putWord(0x2000L, 2, 0x00ff00ff) // green
      mem.putWord(0x2000L, 3, 0xffff00ff) // yellow

      dut.io.fragIn.bits.x.poke(3.S)
      dut.io.fragIn.bits.y.poke(4.S)
      dut.io.fragIn.bits.depth.poke(0x20.S)
      dut.io.fragIn.bits.color.r.poke(0xab.U)
      dut.io.fragIn.bits.color.g.poke(0xcd.U)
      dut.io.fragIn.bits.color.b.poke(0xef.U)
      dut.io.fragIn.valid.poke(true.B)
      dut.clock.step()
      dut.io.fragIn.valid.poke(false.B)

      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)

      val done = pump(dut, mem, () => dut.io.out.valid.peek().litToBoolean,
        guard = 2000)
      assert(done, "textured fragment did not finish within a bounded time")
      dut.io.out.bits.color.r.expect(0xff.U)
      dut.io.out.bits.color.g.expect(0xff.U)
      dut.io.out.bits.color.b.expect(0x00.U)
    }
  }

  it should "sample distinct texture coordinates per vector lane" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelFragStage(config)) { dut =>
      val mem = new MemModel
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      pokeDefaults(dut)
      dut.io.shaderPc.poke(0x1000.U)
      dut.io.kernargBase.poke(0x8000.U)
      dut.io.texBase.poke(0x2000.U)
      dut.io.texWidth.poke(2.U)
      dut.io.texHeight.poke(2.U)
      dut.io.texWrapClamp.poke(false.B)

      // Each lane loads its own u/v from the uniform tail, samples into v3,
      // and stores the four packed results to the ordinary output array.
      mem.putWord(0x1000L, 0, vsetivli(4))
      mem.putWord(0x1000L, 1, addi(5, 1, 192))
      mem.putWord(0x1000L, 2, vle32(5, 1))
      mem.putWord(0x1000L, 3, addi(5, 1, 208))
      mem.putWord(0x1000L, 4, vle32(5, 2))
      mem.putWord(0x1000L, 5, vtexsample(3, 1, 2))
      mem.putWord(0x1000L, 6, addi(5, 1, 128))
      mem.putWord(0x1000L, 7, vse32(5, 3))
      mem.putWord(0x1000L, 8, cease)

      // Texel-centre coordinates for black, red, green and yellow.
      val u = Seq(0x4000, 0xc000, 0x4000, 0xc000)
      val v = Seq(0x4000, 0x4000, 0xc000, 0xc000)
      for (lane <- 0 until 4) {
        mem.putWord(0x80c0L, lane, u(lane))
        mem.putWord(0x80c0L, 4 + lane, v(lane))
      }
      mem.putWord(0x2000L, 0, 0x000000ff)
      mem.putWord(0x2000L, 1, 0xff0000ff)
      mem.putWord(0x2000L, 2, 0x00ff00ff)
      mem.putWord(0x2000L, 3, 0xffff00ff)

      for (lane <- 0 until 4) {
        dut.io.fragIn.bits.x.poke(lane.S)
        dut.io.fragIn.bits.y.poke((8 + lane).S)
        dut.io.fragIn.bits.depth.poke(0x20.S)
        dut.io.fragIn.bits.color.r.poke(0.U)
        dut.io.fragIn.bits.color.g.poke(0.U)
        dut.io.fragIn.bits.color.b.poke(0.U)
        dut.io.fragIn.valid.poke(true.B)
        dut.clock.step()
        dut.io.fragIn.valid.poke(false.B)
      }
      dut.io.flush.poke(true.B); dut.clock.step()
      dut.io.flush.poke(false.B)

      val expected = Seq(
        (0x00, 0x00, 0x00),
        (0xff, 0x00, 0x00),
        (0x00, 0xff, 0x00),
        (0xff, 0xff, 0x00)
      )
      val emitted = scala.collection.mutable.ArrayBuffer.empty[(Long, Long, Long)]
      val done = pump(dut, mem, () => dut.io.out.valid.peek().litToBoolean,
        guard = 6000)
      assert(done, "per-lane texture kernel did not complete")
      var guard = 0
      while (emitted.size < 4 && guard < 16) {
        if (dut.io.out.valid.peek().litToBoolean) {
          emitted += ((dut.io.out.bits.color.r.peek().litValue.toLong,
            dut.io.out.bits.color.g.peek().litValue.toLong,
            dut.io.out.bits.color.b.peek().litValue.toLong))
          dut.io.out.ready.poke(true.B)
          dut.clock.step()
          dut.io.out.ready.poke(false.B)
        }
        guard += 1
      }
      assert(emitted == expected,
        s"per-lane texture result mismatch: expected=$expected got=$emitted")
    }
  }
}
