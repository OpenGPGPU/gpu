package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{
  CacheLineInvalidate,
  ComputeMemoryRequest,
  ComputeMemoryResponse,
  SharedAtomicRequest,
  SharedAtomicResponse
}

/** Core-backed fragment shader stage (Phase D), batched dispatch.
  *
  * Fragments are accumulated into a batch of up to `warps*lanes` and shaded by
  * ONE kernel launch on the compute unit's SIMT warps (lane = fragment):
  *   1. accumulate: buffer the fragment's x/y/depth and write its x, y, depth
  *      and packed colour into the per-fragment input arrays (through the
  *      word->line bridge);
  *   2. launch the shader kernel (entry `shaderPc`) with
  *      `localSize = (count,1,1)` — the dispatcher splits it into warps and the
  *      tail warp gets a partial active mask, so vector loads/stores touch
  *      exactly the batched lanes;
  *   3. wait for kernel completion; the kernel writes per-fragment output
  *      colours into the output array and may clear output-valid words to
  *      discard individual fragments;
  *   4. read the outputs and output-valid words back through the bridge and
  *      emit only live fragments in batch order.
  *
  * kernarg ABI (byte offsets from the draw's kernarg base, which must be
  * 64-byte aligned; `stride = 4 * warps * lanes` is the per-array byte size):
  *   [0*stride, 1*stride)  per-fragment x (sign-extended i32)
  *   [1*stride, 2*stride)  per-fragment y (sign-extended i32)
  *   [2*stride, 3*stride)  per-fragment depth (u32 bits)
  *   [3*stride, 4*stride)  per-fragment packed-colour inputs (RGBA8888)
  *   [4*stride, 5*stride)  perspective-correct u (unsigned Q16.16)
  *   [5*stride, 6*stride)  perspective-correct v (unsigned Q16.16)
  *   [6*stride, 7*stride)  per-fragment packed-colour outputs
  *   [7*stride, 8*stride)  per-fragment depth outputs
  *   [8*stride, 9*stride)  output-valid words (1 = emit, 0 = discard)
  *   [9*stride, ...)       per-draw uniforms
  * The layout is structure-of-arrays so a lane-aware shader (fragment i = lane
  * i) can fetch each attribute with one unit-stride vector load at
  * `kernarg + k*stride + 4*localLinearBase` (scalar base = x1 + (x8 << 2));
  * an AoS record would need strided/gather loads the vector memory unit does
  * not implement.
  *
  * A batch is launched when it fills or when `flush` is asserted with a
  * non-empty batch. `flush` marks the rasterizer-idle boundary between draws;
  * a following draw's fragments cannot arrive without an intervening flush,
  * so a batch never mixes draws. The shader descriptor and sampler state are
  * registered at the first fragment of a batch so a following draw cannot
  * change an in-flight batch. `drawRetired` pulses once for every rising
  * `flush`, including an empty draw, after its output has drained.
  *
  * The shader program, kernarg, and output all sit in the line-based memory
  * behind the two memory ports: `memReq/memResp` serve the compute unit and
  * `wordMemReq/wordMemResp` serve the word->line bridge.  A shared L2 (or the
  * harness memory model) arbitrates the two clients onto one physical memory.
  */
class KernelFragStage(
  config: GpuConfig = GpuConfig(),
  gfxConfig: GraphicsConfig = GraphicsConfig()
) extends Module {
  private val batchCap = config.warps * config.lanes
  private val countWidth = math.max(1, log2Ceil(batchCap + 1))
  // Per-array byte stride of the SoA kernarg layout (see the class doc).
  private val arrayStride = 4 * batchCap

  val io = IO(new Bundle {
    val fragIn = Flipped(Decoupled(new RasterFragment(gfxConfig)))
    val fragUv = Input(new TexUV)
    val out = Decoupled(new RasterFragment(gfxConfig))
    val shaderPc = Input(UInt(32.W))
    val kernargBase = Input(UInt(32.W))
    /** Byte stride between two complete, identically laid-out kernarg banks. */
    val kernargBankStride = Input(UInt(32.W))
    /** Texture sampling config for the tex.sample instruction. */
    val texBase = Input(UInt(32.W))
    val texWidth = Input(UInt(14.W))
    val texHeight = Input(UInt(14.W))
    val texWrapClamp = Input(Bool())
    val texMaxLevel = Input(UInt(4.W))
    val texLodBias = Input(SInt(5.W))
    val texMinLevel = Input(UInt(4.W))
    val flush = Input(Bool())
    val drained = Output(Bool())
    val drawRetired = Output(Bool())
    val memReq = Decoupled(new ComputeMemoryRequest(config))
    val memResp = Flipped(Decoupled(new ComputeMemoryResponse()))
    val wordMemReq = Decoupled(new ComputeMemoryRequest(config))
    val wordMemResp = Flipped(Decoupled(new ComputeMemoryResponse()))
    val l1Invalidate = Flipped(Decoupled(new CacheLineInvalidate(config)))
    val l1InvalidateDone = Decoupled(new CacheLineInvalidate(config))
    val globalAtomicRequest = Decoupled(new SharedAtomicRequest(config))
    val globalAtomicResponse = Flipped(Decoupled(new SharedAtomicResponse(config)))
  })

  // Word-client request/response interface to the bridge (driven by the FSM).
  private val wordValid = Wire(Bool())
  private val wordBits = Wire(new OmMemoryRequest)

  private val kernel = Module(new KernelShaderStage(config))
  private val bridge = Module(new OmWordToLinePort(config))
  private val texBridge = Module(new OmWordToLinePort(config))
  private val texUnit = Module(new TexSampleUnit(config, gfxConfig))

  private val curTexBase = Reg(UInt(32.W))
  private val curTexWidth = Reg(UInt(14.W))
  private val curTexHeight = Reg(UInt(14.W))
  private val curTexWrapClamp = Reg(Bool())
  private val curTexMaxLevel = Reg(UInt(4.W))
  private val curTexLodBias = Reg(SInt(5.W))
  private val curTexMinLevel = Reg(UInt(4.W))
  texUnit.io.texBase := curTexBase
  texUnit.io.texWidth := curTexWidth
  texUnit.io.texHeight := curTexHeight
  texUnit.io.wrapClamp := curTexWrapClamp
  texUnit.io.texMaxLevel := curTexMaxLevel
  texUnit.io.lodBias := curTexLodBias
  texUnit.io.minLevel := curTexMinLevel
  kernel.io.texSample <> texUnit.io.in
  texUnit.io.commit <> kernel.io.texWriteback
  kernel.io.vectorTexSample <> texUnit.io.vectorIn
  texUnit.io.vectorCommit <> kernel.io.vectorTexWriteback
  texBridge.io.in <> texUnit.io.mem.req
  texUnit.io.mem.resp <> texBridge.io.out


  kernel.io.launch.kernelPc := 0.U
  kernel.io.launch.kernargAddress := 0.U
  kernel.io.launch.gridX := 1.U
  kernel.io.launch.gridY := 1.U
  kernel.io.launch.gridZ := 1.U
  kernel.io.launch.localX := 1.U
  kernel.io.launch.localY := 1.U
  kernel.io.launch.localZ := 1.U
  kernel.io.completion.ready := true.B
  io.memReq <> kernel.io.memoryRequest
  kernel.io.memoryResponse <> io.memResp
  kernel.io.trap.ready := true.B
  kernel.io.simtBranch.valid := false.B
  kernel.io.simtBranch.bits := 0.U.asTypeOf(kernel.io.simtBranch.bits)
  kernel.io.l1Invalidate <> io.l1Invalidate
  io.l1InvalidateDone <> kernel.io.l1InvalidateDone
  io.globalAtomicRequest <> kernel.io.globalAtomicRequest
  kernel.io.globalAtomicResponse <> io.globalAtomicResponse

  bridge.io.in.valid := wordValid
  bridge.io.in.bits := wordBits
  bridge.io.out.ready := true.B

  private val sAccum :: sWrite :: sLaunch :: sRun :: sRead :: sEmit :: Nil =
    Enum(6)
  private val state = RegInit(sAccum)

  private val fragX = Reg(Vec(batchCap, SInt(gfxConfig.coordWidth.W)))
  private val fragY = Reg(Vec(batchCap, SInt(gfxConfig.coordWidth.W)))
  private val fragDepth = Reg(Vec(batchCap, SInt(32.W)))
  private val packedColor = Reg(Vec(batchCap, UInt(32.W)))
  private val fragU = Reg(Vec(batchCap, UInt(32.W)))
  private val fragV = Reg(Vec(batchCap, UInt(32.W)))
  private val fragCovered = Reg(Vec(batchCap, Bool()))
  private val outWords = Reg(Vec(batchCap, UInt(32.W)))
  private val outDepth = Reg(Vec(batchCap, SInt(32.W)))
  private val outValid = Reg(Vec(batchCap, Bool()))
  private val fragE0 = Reg(Vec(batchCap, SInt(64.W)))
  private val fragE1 = Reg(Vec(batchCap, SInt(64.W)))
  private val fragE2 = Reg(Vec(batchCap, SInt(64.W)))
  private val count = RegInit(0.U(countWidth.W))
  private val index = RegInit(0.U(countWidth.W))
  // Vec indexing only ever touches [0, batchCap); narrowing avoids an over-wide
  // dynamic index.  `count` reaches `batchCap` for the fullness comparison, so
  // it keeps the wider width and is narrowed when used to index a Vec.
  private val countIdx = count(countWidth - 2, 0)
  private val indexIdx = index(countWidth - 2, 0)
  // One bridge transaction at a time keeps the staging FSM simple; the bridge
  // itself supports more outstanding transactions for other clients.
  private val wordPending = RegInit(false.B)
  // Staging field: writes 0=x, 1=y, 2=depth, 3=packed colour, 4=u, 5=v,
  // 6=depth-output initialization, 7=output-valid initialization; reads
  // 0=colour, 1=depth, 2=output-valid.
  private val field = RegInit(0.U(4.W))

  private val curShaderPc = Reg(UInt(32.W))
  private val curKernarg = Reg(UInt(32.W))
  private val bankSelect = RegInit(false.B)
  private val selectedKernarg = Mux(
    bankSelect && io.kernargBankStride.orR,
    io.kernargBase + io.kernargBankStride,
    io.kernargBase)

  io.fragIn.ready := state === sAccum && count < batchCap.U
  io.drained := state === sAccum && count === 0.U

  io.out.valid := state === sEmit && outValid(indexIdx)
  io.out.bits.x := fragX(indexIdx)
  io.out.bits.y := fragY(indexIdx)
  io.out.bits.depth := outDepth(indexIdx)
  io.out.bits.e0 := fragE0(indexIdx)
  io.out.bits.e1 := fragE1(indexIdx)
  io.out.bits.e2 := fragE2(indexIdx)
  io.out.bits.covered := fragCovered(indexIdx)
  io.out.bits.color.r := outWords(indexIdx)(31, 24)
  io.out.bits.color.g := outWords(indexIdx)(23, 16)
  io.out.bits.color.b := outWords(indexIdx)(15, 8)

  wordValid := (state === sWrite || state === sRead) && !wordPending
  wordBits.write := state === sWrite
  private val writeSlice = MuxLookup(field, 8.U)(Seq(
    0.U -> 0.U, 1.U -> 1.U, 2.U -> 2.U, 3.U -> 3.U,
    4.U -> 4.U, 5.U -> 5.U, 6.U -> 7.U))
  wordBits.addr := Mux(
    state === sWrite,
    curKernarg + writeSlice * arrayStride.U + (index << 2),
    curKernarg + ((6.U + field) * arrayStride.U) + (index << 2)
  )
  wordBits.data := MuxLookup(field, fragCovered(indexIdx).asUInt)(
    Seq(
      0.U -> fragX(indexIdx).pad(32).asUInt,
      1.U -> fragY(indexIdx).pad(32).asUInt,
      2.U -> fragDepth(indexIdx).asUInt,
      3.U -> packedColor(indexIdx),
      4.U -> fragU(indexIdx),
      5.U -> fragV(indexIdx),
      6.U -> fragDepth(indexIdx).asUInt
    )
  )

  kernel.io.launch.valid := state === sLaunch

  switch(state) {
    is(sAccum) {
      when(io.fragIn.fire) {
        fragX(countIdx) := io.fragIn.bits.x
        fragY(countIdx) := io.fragIn.bits.y
        fragDepth(countIdx) := io.fragIn.bits.depth
        packedColor(countIdx) := Cat(
          io.fragIn.bits.color.r,
          io.fragIn.bits.color.g,
          io.fragIn.bits.color.b,
          0xff.U(8.W))
        fragU(countIdx) := io.fragUv.u
        fragV(countIdx) := io.fragUv.v
        fragCovered(countIdx) := io.fragIn.bits.covered
        fragE0(countIdx) := io.fragIn.bits.e0
        fragE1(countIdx) := io.fragIn.bits.e1
        fragE2(countIdx) := io.fragIn.bits.e2
        when(count === 0.U) {
          curShaderPc := io.shaderPc
          curKernarg := selectedKernarg
          curTexBase := io.texBase
          curTexWidth := io.texWidth
          curTexHeight := io.texHeight
          curTexWrapClamp := io.texWrapClamp
          curTexMaxLevel := io.texMaxLevel
          curTexLodBias := io.texLodBias
          curTexMinLevel := io.texMinLevel
        }.otherwise {
          assert(
            io.shaderPc === curShaderPc && selectedKernarg === curKernarg,
            "a fragment batch must not mix draw descriptors")
        }
        count := count + 1.U
        when(count === (batchCap - 1).U) {
          index := 0.U
          field := 0.U
          wordPending := false.B
          state := sWrite
        }
      }.elsewhen(io.flush && count =/= 0.U) {
        index := 0.U
        field := 0.U
        wordPending := false.B
        state := sWrite
      }
    }
    is(sWrite) {
      when(!wordPending && bridge.io.in.fire) { wordPending := true.B }
      when(wordPending && bridge.io.out.fire) {
        wordPending := false.B
        when(field =/= 7.U) {
          field := field + 1.U
        }.otherwise {
          field := 0.U
          when(index === count - 1.U) {
            index := 0.U
            state := sLaunch
          }.otherwise {
            index := index + 1.U
          }
        }
      }
    }
    is(sLaunch) {
      kernel.io.launch.kernelPc := curShaderPc
      kernel.io.launch.kernargAddress := curKernarg
      kernel.io.launch.localX := count
      when(kernel.io.launch.ready) { state := sRun }
    }
    is(sRun) {
      when(kernel.io.completion.valid) {
        index := 0.U
        wordPending := false.B
        state := sRead
      }
    }
    is(sRead) {
      when(!wordPending && bridge.io.in.fire) { wordPending := true.B }
      when(wordPending && bridge.io.out.fire) {
        wordPending := false.B
        when(field === 0.U) {
          outWords(indexIdx) := bridge.io.out.bits.data
          field := 1.U
        }.elsewhen(field === 1.U) {
          outDepth(indexIdx) := bridge.io.out.bits.data.asSInt
          field := 2.U
        }.otherwise {
          // Helper lanes execute the shader and may participate in quad
          // derivatives, but no shader store can promote one into an OM write.
          outValid(indexIdx) := bridge.io.out.bits.data =/= 0.U &&
            fragCovered(indexIdx)
          field := 0.U
          when(index === count - 1.U) {
            index := 0.U
            state := sEmit
          }.otherwise {
            index := index + 1.U
          }
        }
      }
    }
    is(sEmit) {
      when(!outValid(indexIdx) || io.out.fire) {
        when(index === count - 1.U) {
          count := 0.U
          index := 0.U
          when(io.kernargBankStride.orR) { bankSelect := ~bankSelect }
          state := sAccum
        }.otherwise {
          index := index + 1.U
        }
      }
    }
  }
  private val prevFlush = RegInit(true.B)
  prevFlush := io.flush
  private val flushRise = io.flush && !prevFlush
  private val finalPending = RegInit(false.B)
  when(flushRise) {
    assert(!finalPending, "a draw boundary arrived before the previous draw retired")
    finalPending := true.B
  }
  io.drawRetired := finalPending && state === sAccum && count === 0.U
  when(io.drawRetired) { finalPending := false.B }

  // The staging FSM (kernarg write/read phases) and the sampler never have
  // requests in flight at the same time (staging completes before the kernel
  // launches; sampling happens while the kernel runs), so a phase-selected
  // mux over the single line-memory port is race-free.
  private val stagingActive = state === sWrite || state === sRead
  io.wordMemReq.valid := Mux(stagingActive, bridge.io.memoryRequest.valid,
    texBridge.io.memoryRequest.valid)
  io.wordMemReq.bits := Mux(stagingActive, bridge.io.memoryRequest.bits,
    texBridge.io.memoryRequest.bits)
  bridge.io.memoryRequest.ready := io.wordMemReq.ready && stagingActive
  texBridge.io.memoryRequest.ready := io.wordMemReq.ready && !stagingActive
  io.wordMemResp.ready := Mux(stagingActive, bridge.io.memoryResponse.ready,
    texBridge.io.memoryResponse.ready)
  bridge.io.memoryResponse.valid := io.wordMemResp.valid && stagingActive
  bridge.io.memoryResponse.bits := io.wordMemResp.bits
  texBridge.io.memoryResponse.valid := io.wordMemResp.valid && !stagingActive
  texBridge.io.memoryResponse.bits := io.wordMemResp.bits
}
