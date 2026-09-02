package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import opengpu.core.memory.{
  CacheLineInvalidate,
  ComputeMemoryRequest,
  ComputeMemoryResponse,
  SharedAtomicRequest,
  SharedAtomicResponse
}
import org.scalatest.flatspec.AnyFlatSpec

/** Test wrapper that provides an external KernelShaderStage to the
  * KernelFragStage, matching the pre-refactor behavior where the kernel was
  * internal.
  */
class KernelFragStageWithKernel(
  config: GpuConfig = GpuConfig(),
  gfxConfig: GraphicsConfig = GraphicsConfig()
) extends Module {
  // Expose the pre-refactor IO (without the new kernel ports)
  val io = IO(new Bundle {
    val fragIn = Flipped(Decoupled(new FragmentQuad(gfxConfig)))
    val fragUv = Input(Vec(4, new TexUV))
    val out = Decoupled(new RasterFragment(gfxConfig))
    val shaderPc = Input(UInt(32.W))
    val kernargBase = Input(UInt(32.W))
    val kernargBankStride = Input(UInt(32.W))
    val texBase = Input(UInt(32.W))
    val texWidth = Input(UInt(14.W))
    val texHeight = Input(UInt(14.W))
    val texWrapClamp = Input(Bool())
    val texMaxLevel = Input(UInt(4.W))
    val texLodBias = Input(SInt(5.W))
    val texMinLevel = Input(UInt(4.W))
    val flush = Input(Bool())
    val drained = Output(Bool())
    val drawRetire = Decoupled(Bool())
    val memReq = Decoupled(new ComputeMemoryRequest(config))
    val memResp = Flipped(Decoupled(new ComputeMemoryResponse()))
    val wordMemReq = Decoupled(new ComputeMemoryRequest(config))
    val wordMemResp = Flipped(Decoupled(new ComputeMemoryResponse()))
    val l1Invalidate = Flipped(Decoupled(new CacheLineInvalidate(config)))
    val l1InvalidateDone = Decoupled(new CacheLineInvalidate(config))
    val globalAtomicRequest = Decoupled(new SharedAtomicRequest(config))
    val globalAtomicResponse = Flipped(Decoupled(new SharedAtomicResponse(config)))
  })

  private val frag = Module(new KernelFragStage(config, gfxConfig))
  private val kernel = Module(new KernelShaderStage(config))

  // Connect wrapper IO to frag stage's non-kernel ports
  io.fragIn <> frag.io.fragIn
  io.fragUv <> frag.io.fragUv
  frag.io.out <> io.out
  frag.io.shaderPc := io.shaderPc
  frag.io.kernargBase := io.kernargBase
  frag.io.kernargBankStride := io.kernargBankStride
  frag.io.texBase := io.texBase
  frag.io.texWidth := io.texWidth
  frag.io.texHeight := io.texHeight
  frag.io.texWrapClamp := io.texWrapClamp
  frag.io.texMaxLevel := io.texMaxLevel
  frag.io.texLodBias := io.texLodBias
  frag.io.texMinLevel := io.texMinLevel
  frag.io.flush := io.flush
  io.drained := frag.io.drained
  io.drawRetire <> frag.io.drawRetire
  io.wordMemReq <> frag.io.wordMemReq
  frag.io.wordMemResp <> io.wordMemResp

  // Connect kernel internally: frag's kernel ports <-> kernel's IO
  kernel.io.launch.valid := frag.io.kernelLaunch.valid
  kernel.io.launch.kernelPc := frag.io.kernelLaunch.kernelPc
  kernel.io.launch.kernargAddress := frag.io.kernelLaunch.kernargAddress
  kernel.io.launch.gridX := frag.io.kernelLaunch.gridX
  kernel.io.launch.gridY := frag.io.kernelLaunch.gridY
  kernel.io.launch.gridZ := frag.io.kernelLaunch.gridZ
  kernel.io.launch.localX := frag.io.kernelLaunch.localX
  kernel.io.launch.localY := frag.io.kernelLaunch.localY
  kernel.io.launch.localZ := frag.io.kernelLaunch.localZ
  frag.io.kernelLaunch.ready := kernel.io.launch.ready

  kernel.io.completion <> frag.io.kernelCompletion
  kernel.io.trap <> frag.io.kernelTrap
  kernel.io.simtBranch.valid := false.B
  kernel.io.simtBranch.bits := 0.U.asTypeOf(kernel.io.simtBranch.bits)
  frag.io.kernelSimtBranch.valid := false.B
  frag.io.kernelSimtBranch.bits := 0.U.asTypeOf(frag.io.kernelSimtBranch.bits)
  kernel.io.texSample <> frag.io.kernelTexSample
  frag.io.kernelTexWriteback <> kernel.io.texWriteback
  kernel.io.vectorTexSample <> frag.io.kernelVectorTexSample
  frag.io.kernelVectorTexWriteback <> kernel.io.vectorTexWriteback

  // Memory ports: kernel's memory requests are exposed directly to wrapper IO.
  // The frag stage's memReq/memResp are unused after externalizing the kernel.
  io.memReq <> kernel.io.memoryRequest
  kernel.io.memoryResponse <> io.memResp
  frag.io.memReq.ready := false.B
  frag.io.memResp.valid := false.B
  frag.io.memResp.bits := 0.U.asTypeOf(frag.io.memResp.bits)

  // L1 invalidate and global atomic: connect frag's ports to wrapper IO.
  // The kernel's corresponding ports are unused in this configuration.
  io.l1Invalidate <> frag.io.l1Invalidate
  io.l1InvalidateDone <> frag.io.l1InvalidateDone
  io.globalAtomicRequest <> frag.io.globalAtomicRequest
  frag.io.globalAtomicResponse <> io.globalAtomicResponse
  kernel.io.l1Invalidate.valid := false.B
  kernel.io.l1Invalidate.bits := 0.U.asTypeOf(kernel.io.l1Invalidate.bits)
  kernel.io.l1InvalidateDone.ready := false.B
  kernel.io.globalAtomicRequest.ready := false.B
  kernel.io.globalAtomicResponse.valid := false.B
  kernel.io.globalAtomicResponse.bits := 0.U.asTypeOf(kernel.io.globalAtomicResponse.bits)
}

class KernelFragStageSpec extends AnyFlatSpec {
  behavior of "KernelFragStage"

  // Batched SoA ABI (byte offsets from the draw's kernarg base; stride =
  // 4 * warps * lanes = 32 for the test config):
  //   [0,   32)   per-fragment x (sign-extended i32)
  //   [32,  64)   per-fragment y
  //   [64,  96)   per-fragment depth
  //   [96,  128)  per-fragment packed-colour inputs
  //   [128, 160)  perspective-correct u
  //   [160, 192)  perspective-correct v
  //   [192, 224)  per-fragment colour outputs
  //   [224, 256)  per-fragment depth outputs
  //   [256, 288)  per-fragment output-valid (1 = emit, 0 = discard)
  //   [288, ...)  per-draw uniforms

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
    def getWord(addr: Long): BigInt = {
      val line = readLine(addr & ~63L)
      (line >> ((addr & 63L).toInt * 8)) & BigInt("ffffffff", 16)
    }
  }

  it should "alternate complete kernarg banks at batch boundaries" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelFragStageWithKernel(config)) { dut =>
      val mem = new MemModel
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      pokeDefaults(dut)
      dut.io.shaderPc.poke(0x1000.U)
      dut.io.kernargBase.poke(0x8000.U)
      dut.io.kernargBankStride.poke(0x200.U)
      mem.putWord(0x1000L, 0, lw(10, 1, 96))
      mem.putWord(0x1000L, 1, sw(10, 1, 192))
      mem.putWord(0x1000L, 2, cease)

      def runBatch(red: Int): Unit = {
        fireQuad(dut, Seq(Frag(red, 0, 1, r = red, g = 0, b = 0)))
        dut.io.flush.poke(true.B); dut.clock.step(); dut.io.flush.poke(false.B)
        assert(pump(dut, mem, () => dut.io.out.valid.peek().litToBoolean),
          "banked fragment batch did not complete")
        dut.io.out.bits.color.r.expect(red.U)
        dut.io.out.bits.alpha.expect(0xff.U)
        dut.clock.step()
        assert(pump(dut, mem, () => dut.io.drained.peek().litToBoolean),
          "banked fragment batch did not drain")
      }

      runBatch(0x11)
      runBatch(0x22)
      assert(mem.getWord(0x8000L + 96) == BigInt("110000ff", 16))
      assert(mem.getWord(0x8200L + 96) == BigInt("220000ff", 16))
    }
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
  private def vquad(funct6: Int, vd: Int, vs2: Int): BigInt =
    (BigInt(funct6 & 0x3f) << 26) | (BigInt(1) << 25) |
      (BigInt(vs2 & 0x1f) << 20) | (BigInt(vd & 0x1f) << 7) | 0x2b

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
  private def vaddVi(vd: Int, vs2: Int, imm: Int): BigInt =
    (BigInt(1) << 25) | (BigInt(vs2 & 0x1f) << 20) |
      (BigInt(imm & 0x1f) << 15) | (BigInt(3) << 12) |
      (BigInt(vd & 0x1f) << 7) | 0x57
  private val cease = BigInt("30500073", 16)

  /** Services KernelFragStage's two memory ports against `mem` until `pred`
    * becomes true or a guard timeout expires.  Returns the final predicate.
    */
  private val LineMask = (BigInt(1) << 512) - 1

  private def pump(
    dut: KernelFragStageWithKernel,
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

  private def pokeDefaults(dut: KernelFragStageWithKernel): Unit = {
    dut.io.fragIn.valid.poke(false.B)
    for (k <- 0 until 4) {
      dut.io.fragIn.bits.lanes(k).covered.poke(false.B)
      dut.io.fragUv(k).u.poke(0.U)
      dut.io.fragUv(k).v.poke(0.U)
    }
    dut.io.out.ready.poke(true.B)
    dut.io.flush.poke(false.B)
    dut.io.drawRetire.ready.poke(true.B)
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

  /** One fragment lane of a quad under test; lanes not supplied to a poke
    * default to uncovered helper lanes, which stage and execute but never
    * reach the output stream. */
  private case class Frag(x: Int, y: Int, depth: Int,
    r: Int = 0xab, g: Int = 0xcd, b: Int = 0xef, covered: Boolean = true)

  /** Pokes all four lanes of `fragIn` (TL/TR/BL/BR) and their UVs without
    * firing; lanes beyond `frags.length` become uncovered helpers. */
  private def pokeQuadLanes(dut: KernelFragStageWithKernel, frags: Seq[Frag],
    uvs: Seq[(Int, Int)] = Seq.fill(4)((0, 0))): Unit = {
    for (k <- 0 until 4) {
      val f = if (k < frags.length) frags(k)
        else Frag(0, 0, 0, 0, 0, 0, covered = false)
      dut.io.fragIn.bits.lanes(k).x.poke(f.x.S)
      dut.io.fragIn.bits.lanes(k).y.poke(f.y.S)
      dut.io.fragIn.bits.lanes(k).depth.poke(f.depth.S)
      dut.io.fragIn.bits.lanes(k).covered.poke(f.covered.B)
      dut.io.fragIn.bits.lanes(k).color.r.poke(f.r.U)
      dut.io.fragIn.bits.lanes(k).color.g.poke(f.g.U)
      dut.io.fragIn.bits.lanes(k).color.b.poke(f.b.U)
      dut.io.fragUv(k).u.poke(uvs(k)._1.U)
      dut.io.fragUv(k).v.poke(uvs(k)._2.U)
    }
  }

  /** Fires exactly one quad beat. */
  private def fireQuad(dut: KernelFragStageWithKernel, frags: Seq[Frag],
    uvs: Seq[(Int, Int)] = Seq.fill(4)((0, 0))): Unit = {
    pokeQuadLanes(dut, frags, uvs)
    dut.io.fragIn.valid.poke(true.B)
    dut.clock.step()
    dut.io.fragIn.valid.poke(false.B)
  }

  it should "shade a single fragment via a batched pass-through kernel" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelFragStageWithKernel(config)) { dut =>
      val mem = new MemModel
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      pokeDefaults(dut)
      dut.io.shaderPc.poke(0x1000.U)
      dut.io.kernargBase.poke(0x8000.U)
      dut.io.kernargBankStride.poke(0.U)

      // Program: read the packed-colour input array (kernarg+96), write the
      // output array (kernarg+192), halt.
      mem.putWord(0x1000L, 0, lw(10, 1, 96))
      mem.putWord(0x1000L, 1, sw(10, 1, 192))
      mem.putWord(0x1000L, 2, cease)

      fireQuad(dut, Seq(Frag(3, 4, 0x20)))

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
    simulate(new KernelFragStageWithKernel(config)) { dut =>
      val mem = new MemModel
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      pokeDefaults(dut)
      dut.io.shaderPc.poke(0x1000.U)
      dut.io.kernargBase.poke(0x8000.U)
      dut.io.kernargBankStride.poke(0.U)

      // Program: colour = input[0] (kernarg+96) + uniform (kernarg+288);
      //          output[0] (kernarg+192); cease.
      mem.putWord(0x1000L, 0, lw(10, 1, 96))
      mem.putWord(0x1000L, 1, lw(11, 1, 288))
      mem.putWord(0x1000L, 2, add(10, 10, 11))
      mem.putWord(0x1000L, 3, sw(10, 1, 192))
      mem.putWord(0x1000L, 4, cease)

      // Uniform at kernarg+288 (line 0x8100, word 8): +1 blue.
      mem.putWord(0x8100L, 8, BigInt("00000100", 16))

      fireQuad(dut, Seq(Frag(3, 4, 0x20)))

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
      dut.io.out.bits.alpha.expect(0xff.U)
    }
  }

  it should "discard a fragment whose shader clears output-valid" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelFragStageWithKernel(config)) { dut =>
      val mem = new MemModel
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      pokeDefaults(dut)
      dut.io.shaderPc.poke(0x1000.U)
      dut.io.kernargBase.poke(0x8000.U)
      dut.io.kernargBankStride.poke(0.U)

      // The stage initializes valid[0] to one. The shader's zero store kills
      // the fragment before it can reach depth/colour output merging.
      mem.putWord(0x1000L, 0, sw(0, 1, 256))
      mem.putWord(0x1000L, 1, cease)

      fireQuad(dut, Seq(Frag(3, 4, 0x20)))
      dut.io.flush.poke(true.B); dut.clock.step()
      dut.io.flush.poke(false.B)

      val done = pump(dut, mem, () => dut.io.drained.peek().litToBoolean,
        guard = 2000)
      assert(done, "discarded fragment did not drain")
      assert(!dut.io.out.valid.peek().litToBoolean,
        "discarded fragment must not be emitted")
      assert((mem.readLine(0x8100L) & 0xffffffffL) == 0,
        "shader did not clear the output-valid word")
    }
  }

  it should "accumulate a batch while suppressing helper-lane output" in {
    // Batched dispatch: several fragments are accumulated and flushed as ONE
    // kernel launch (localSize = count) rather than one launch per fragment.
    // The FSM buffers each fragment's x/y/depth locally and re-emits the batch
    // in submission order after the kernel's output words are read back.  Here
    // a scalar pass-through kernel is used (scalar registers are per-warp
    // broadcast, so only fragment 0's colour slot holds a value); the geometry
    // and ordering of every batched fragment are the contract under test.
    val config = GpuConfig(lanes = 4, warps = 2)
    val count = 3
    simulate(new KernelFragStageWithKernel(config)) { dut =>
      val mem = new MemModel
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      pokeDefaults(dut)
      dut.io.shaderPc.poke(0x1000.U)
      dut.io.kernargBase.poke(0x8000.U)
      dut.io.kernargBankStride.poke(0.U)

      // Pass-through scalar kernel: read the colour input word, write the
      // output word, cease.
      mem.putWord(0x1000L, 0, lw(10, 1, 96))
      mem.putWord(0x1000L, 1, sw(10, 1, 192))
      mem.putWord(0x1000L, 2, cease)

      // Feed one quad whose four lanes carry distinct x/y/depth; lane 1 is an
      // uncovered helper.
      val inputs = Seq(
        (0, 10, 0x20, true),
        (1, 11, 0x30, false),
        (2, 12, 0x40, true)
      )
      fireQuad(dut, inputs.map { case (x, y, d, c) =>
        Frag(x, y, d, covered = c) })

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
        if (emitted.size == 2 && dut.io.drained.peek().litToBoolean)
          drained = true
      }
      assert(emitted.size == 2, s"expected 2 covered fragments, got ${emitted.size}")

      // Covered fragments retain submission order; the middle helper executed
      // the shader but cannot be promoted into an output-memory transaction.
      for ((expected, got) <- inputs.filter(_._4) zip emitted) {
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
    simulate(new KernelFragStageWithKernel(config)) { dut =>
      val mem = new MemModel
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      pokeDefaults(dut)
      dut.io.shaderPc.poke(0x1000.U)
      dut.io.kernargBase.poke(0x8000.U)
      dut.io.kernargBankStride.poke(0.U)

      // Program (lane = fragment; x1 = kernarg, x8 = localLinearBase):
      //   slli x5, x8, 2      // x5 = 4 * localLinearBase (this warp's slice)
      //   add  x5, x1, x5     // x5 = kernarg base of this warp's slice
      //   vsetivli x0, 4, e32
      //   vle32.v v2, (x5)    // per-lane x array
      //   addi x6, x5, 96
      //   vle32.v v3, (x6)    // per-lane packed-colour inputs
      //   vadd.vv v4, v3, v2  // colour + x, per lane
      //   addi x6, x5, 192
      //   vse32.v v4, (x6)    // per-lane colour outputs
      //   cease
      mem.putWord(0x1000L, 0, slli(5, 8, 2))
      mem.putWord(0x1000L, 1, add(5, 1, 5))
      mem.putWord(0x1000L, 2, vsetivli(4))
      mem.putWord(0x1000L, 3, vle32(5, 2))
      mem.putWord(0x1000L, 4, addi(6, 5, 96))
      mem.putWord(0x1000L, 5, vle32(6, 3))
      mem.putWord(0x1000L, 6, vaddVv(4, 3, 2))
      mem.putWord(0x1000L, 7, addi(6, 5, 192))
      mem.putWord(0x1000L, 8, vse32(6, 4))
      mem.putWord(0x1000L, 9, cease)

      // Feed a full-warp batch as ONE quad beat with distinct per-lane colours.
      val colors = Seq(
        (0x10, 0x20, 0x30),
        (0x40, 0x50, 0x60),
        (0x70, 0x80, 0x90),
        (0xa0, 0xb0, 0xc0)
      )
      fireQuad(dut, Seq.tabulate(count)(i =>
        Frag(i, 10 + i, 0x20, colors(i)._1, colors(i)._2, colors(i)._3)))

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
      // 16-byte mask at kernarg+192.
      assert(kernelStores.exists { case (addr, bm) =>
        addr == 0x80c0L && bm == BigInt(0xffff)
      }, s"expected a full 4-lane store at 0x80c0, got $kernelStores")

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
    simulate(new KernelFragStageWithKernel(config)) { dut =>
      val mem = new MemModel
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      pokeDefaults(dut)
      dut.io.shaderPc.poke(0x1000.U)
      dut.io.kernargBase.poke(0x8000.U)
      dut.io.kernargBankStride.poke(0.U)
      dut.io.texBase.poke(0x2000.U)
      dut.io.texWidth.poke(2.U)
      dut.io.texHeight.poke(2.U)
      dut.io.texWrapClamp.poke(false.B)
      dut.io.texMaxLevel.poke(0.U)
      dut.io.texLodBias.poke(0.S)
      dut.io.texMinLevel.poke(0.U)

      // Program: u = uniform(288), v = uniform(292); sample; output[0] = rd.
      mem.putWord(0x1000L, 0, lw(10, 1, 288))
      mem.putWord(0x1000L, 1, lw(11, 1, 292))
      mem.putWord(0x1000L, 2, texsample(12, 10, 11))
      mem.putWord(0x1000L, 3, sw(12, 1, 192))
      mem.putWord(0x1000L, 4, cease)

      // Uniforms: u = v = 0.75 (Q16.16) -- the centre of texel (1,1),
      // so the sample must return the pure yellow texel word.
      mem.putWord(0x8100L, 8, 0xc000)
      mem.putWord(0x8100L, 9, 0xc000)

      // 2x2 gradient: black / red / green / yellow.  Texel words use the
      // pipeline's packed-colour byte order (colour channels in the top
      // three bytes) so the kernel's raw sw round-trips r directly.
      // Pipeline colour packing: r<<24 | g<<16 | b<<8 | alpha.
      mem.putWord(0x2000L, 0, 0x000000ff) // black
      mem.putWord(0x2000L, 1, 0xff0000ff) // red
      mem.putWord(0x2000L, 2, 0x00ff00ff) // green
      mem.putWord(0x2000L, 3, 0xffff00ff) // yellow

      fireQuad(dut, Seq(Frag(3, 4, 0x20)))

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

  it should "consume quad UVs and write a dFdx-derived depth" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelFragStageWithKernel(config)) { dut =>
      val mem = new MemModel
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      pokeDefaults(dut)
      dut.io.shaderPc.poke(0x1000.U)
      dut.io.kernargBase.poke(0x8000.U)
      dut.io.kernargBankStride.poke(0.U)
      dut.io.texBase.poke(0x2000.U)
      dut.io.texWidth.poke(2.U)
      dut.io.texHeight.poke(2.U)
      dut.io.texWrapClamp.poke(false.B)
      dut.io.texMaxLevel.poke(0.U)
      dut.io.texLodBias.poke(0.S)
      dut.io.texMinLevel.poke(0.U)

      // Each lane loads interpolated u/v from the fragment-input slices,
      // samples into v3, and writes colour plus dFdx(u) as depth.
      mem.putWord(0x1000L, 0, vsetivli(4))
      mem.putWord(0x1000L, 1, addi(5, 1, 128))
      mem.putWord(0x1000L, 2, vle32(5, 1))
      mem.putWord(0x1000L, 3, addi(5, 1, 160))
      mem.putWord(0x1000L, 4, vle32(5, 2))
      mem.putWord(0x1000L, 5, vtexsample(3, 1, 2))
      mem.putWord(0x1000L, 6, addi(5, 1, 192))
      mem.putWord(0x1000L, 7, vse32(5, 3))
      mem.putWord(0x1000L, 8, vquad(0x0c, 4, 1))
      mem.putWord(0x1000L, 9, addi(5, 1, 224))
      mem.putWord(0x1000L, 10, vse32(5, 4))
      mem.putWord(0x1000L, 11, cease)

      // Texel-centre coordinates for black, red, green and yellow.
      val u = Seq(0x4000, 0xc000, 0x4000, 0xc000)
      val v = Seq(0x4000, 0x4000, 0xc000, 0xc000)
      mem.putWord(0x2000L, 0, 0x000000ff)
      mem.putWord(0x2000L, 1, 0xff0000ff)
      mem.putWord(0x2000L, 2, 0x00ff00ff)
      mem.putWord(0x2000L, 3, 0xffff00ff)

      fireQuad(dut, Seq.tabulate(4)(lane => Frag(lane, 8 + lane, 0x20,
        r = 0, g = 0, b = 0)), uvs = u zip v)
      dut.io.flush.poke(true.B); dut.clock.step()
      dut.io.flush.poke(false.B)

      val expected = Seq(
        (0x00, 0x00, 0x00),
        (0xff, 0x00, 0x00),
        (0x00, 0xff, 0x00),
        (0xff, 0xff, 0x00)
      )
      val emitted = scala.collection.mutable.ArrayBuffer.empty[(Long, Long, Long, Long)]
      val done = pump(dut, mem, () => dut.io.out.valid.peek().litToBoolean,
        guard = 6000)
      assert(done, "per-lane texture kernel did not complete")
      var guard = 0
      while (emitted.size < 4 && guard < 16) {
        if (dut.io.out.valid.peek().litToBoolean) {
          emitted += ((dut.io.out.bits.color.r.peek().litValue.toLong,
            dut.io.out.bits.color.g.peek().litValue.toLong,
            dut.io.out.bits.color.b.peek().litValue.toLong,
            dut.io.out.bits.depth.peek().litValue.toLong))
          dut.io.out.ready.poke(true.B)
          dut.clock.step()
          dut.io.out.ready.poke(false.B)
        }
        guard += 1
      }
      val expectedWithDepth = expected.map { case (r, g, b) =>
        (r.toLong, g.toLong, b.toLong, 0x8000L)
      }
      assert(emitted == expectedWithDepth,
        s"per-lane UV/depth result mismatch: expected=$expectedWithDepth got=$emitted")
    }
  }

  it should "accumulate the next draw while the previous batch executes" in {
    // Per-draw overlap: draw 2's fragment is accepted while batch 1 is still
    // in flight (not drained), and both draws complete in submission order
    // with one retire event each.
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelFragStageWithKernel(config)) { dut =>
      val mem = new MemModel
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      pokeDefaults(dut)
      dut.io.shaderPc.poke(0x1000.U)
      dut.io.kernargBase.poke(0x8000.U)
      // Overlapping batches alternate banks: with a zero stride the second
      // kernel's loads would hit the first kernel's stale L1 lines for the
      // same input addresses (the hazard the dual-bank ABI exists for).
      dut.io.kernargBankStride.poke(0x200.U)
      mem.putWord(0x1000L, 0, lw(10, 1, 96))
      mem.putWord(0x1000L, 1, sw(10, 1, 192))
      mem.putWord(0x1000L, 2, cease)

      def feed(x: Int, red: Int): Unit =
        fireQuad(dut, Seq(Frag(x, 0, 1, r = red, g = 0, b = 0)))

      // Draw 1: one fragment, flush at the boundary.
      feed(1, 0x11)
      dut.io.flush.poke(true.B); dut.clock.step(); dut.io.flush.poke(false.B)
      // The consumer has taken batch 1 (not drained), yet the producer must
      // already accept draw 2's fragment into the second staging slot.
      dut.io.fragIn.ready.expect(true.B, "producer stalled during execution")
      dut.io.drained.expect(false.B, "batch 1 should still be in flight")

      // Serve both memory ports with deferred responses while polling, so
      // batch 1's staging requests are captured from the first cycle.  Draw
      // 2's fragment and boundary are injected mid-flight: the fragment is
      // accepted while batch 1 executes, and its flush commits batch 2 as
      // the parked slot.
      val emitted = scala.collection.mutable.ArrayBuffer.empty[Int]
      val kQueue = scala.collection.mutable.Queue.empty[(BigInt, BigInt)]
      val wQueue = scala.collection.mutable.Queue.empty[(BigInt, BigInt)]
      var guard = 0
      var drained = false
      var inject = 0
      while ((!drained || emitted.size < 2) && guard < 4000) {
        // Mid-flight injection: draw 2's fragment is accepted while batch 1
        // executes (inject 0/1), then its boundary flush commits batch 2 as
        // the parked slot (inject 2/3).
        inject match {
          case 0 =>
            pokeQuadLanes(dut, Seq(Frag(2, 0, 1, r = 0x22, g = 0, b = 0)))
            dut.io.fragIn.valid.poke(true.B)
            inject = 1
          case 1 =>
            dut.io.fragIn.valid.poke(false.B)
            dut.io.flush.poke(true.B)
            inject = 2
          case 2 =>
            dut.io.flush.poke(false.B)
            inject = 3
          case _ =>
        }
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
          emitted += dut.io.out.bits.color.r.peek().litValue.toInt
          dut.io.out.ready.poke(true.B)
        } else dut.io.out.ready.poke(false.B)
        dut.clock.step()
        guard += 1
        if (sys.env.contains("KFS_DEBUG") && guard < 120)
          println(f"dbg g=$guard out.valid=${dut.io.out.valid.peek().litToBoolean}" +
            f" drained=${dut.io.drained.peek().litToBoolean}" +
            f" wreq=${dut.io.wordMemReq.valid.peek().litToBoolean}" +
            f" kreq=${dut.io.memReq.valid.peek().litToBoolean}" +
            f" retire=${dut.io.drawRetire.valid.peek().litToBoolean}")
        if (emitted.size == 2 && dut.io.drained.peek().litToBoolean)
          drained = true
      }
      assert(emitted == Seq(0x11, 0x22),
        s"overlapped draws emitted out of order: $emitted")

      // Drain, then exactly one further retire event (draw 1's fired during
      // the emission loop once the OM-facing output drained).
      assert(pump(dut, mem, () => dut.io.drained.peek().litToBoolean),
        "overlapped draws did not drain")
      dut.io.drawRetire.valid.expect(true.B, "draw 2 did not retire")
      dut.clock.step()
      dut.io.drawRetire.valid.expect(false.B)

      // The two batches staged into alternating kernarg banks.
      assert(mem.getWord(0x8000L + 96) == BigInt("110000ff", 16))
      assert(mem.getWord(0x8200L + 96) == BigInt("220000ff", 16))
    }
  }

  it should "retire exactly one event per draw boundary, including empty draws" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelFragStageWithKernel(config)) { dut =>
      val mem = new MemModel
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      pokeDefaults(dut)
      dut.io.shaderPc.poke(0x1000.U)
      dut.io.kernargBase.poke(0x8000.U)
      dut.io.kernargBankStride.poke(0.U)
      mem.putWord(0x1000L, 0, lw(10, 1, 96))
      mem.putWord(0x1000L, 1, sw(10, 1, 192))
      mem.putWord(0x1000L, 2, cease)
      dut.clock.step(2)

      // `flush` is a level: only its rising edge ends a draw.  An empty draw
      // must still release one context token.
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.drawRetire.valid.expect(true.B)
      dut.clock.step()
      dut.io.drawRetire.valid.expect(false.B)
      dut.io.flush.poke(false.B)
      dut.clock.step()

      fireQuad(dut, Seq(Frag(1, 2, 0x20)))
      dut.io.flush.poke(true.B)
      assert(pump(dut, mem, () => dut.io.drawRetire.valid.peek().litToBoolean),
        "flushed draw did not retire")
      dut.io.drained.expect(true.B)
      dut.clock.step(4)
      dut.io.drawRetire.valid.expect(false.B)
    }
  }
}
