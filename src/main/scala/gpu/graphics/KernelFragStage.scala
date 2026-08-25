package gpu.graphics

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

/** Core-backed fragment shader stage (Phase D), batched dispatch.
  *
  * Fragments are accumulated into a batch of up to `warps*lanes` and shaded by
  * ONE kernel launch on the compute unit's SIMT warps (lane = fragment):
  *   1. accumulate: buffer the fragment's x/y/depth and write its packed
  *      colour to the per-fragment input word at `kernarg + 4*i` (through the
  *      word->line bridge);
  *   2. launch the shader kernel (entry `shaderPc`) with
  *      `localSize = (count,1,1)` — the dispatcher splits it into warps and the
  *      tail warp gets a partial active mask, so vector loads/stores touch
  *      exactly the batched lanes;
  *   3. wait for kernel completion; the kernel writes per-fragment output
  *      colours at `kernarg + 64 + 4*i`;
  *   4. read the outputs back through the bridge and emit the fragments in
  *      batch order.
  *
  * kernarg ABI (byte offsets from the draw's kernarg base, which must be
  * 64-byte aligned):
  *   [0, 4*count)     per-fragment packed-colour inputs
  *   [64, 64+4*count) per-fragment colour outputs
  *   [128, ...)       per-draw uniforms
  *
  * A batch is launched when it fills or when `flush` is asserted with a
  * non-empty batch. `flush` marks the rasterizer-idle boundary between draws;
  * a following draw's fragments cannot arrive without an intervening flush,
  * so a batch never mixes draws. The shader descriptor is registered at the
  * first fragment of a batch so a following draw cannot change the descriptor
  * of an in-flight batch.
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

  val io = IO(new Bundle {
    val fragIn = Flipped(Decoupled(new RasterFragment(gfxConfig)))
    val out = Decoupled(new RasterFragment(gfxConfig))
    val shaderPc = Input(UInt(32.W))
    val kernargBase = Input(UInt(32.W))
    val flush = Input(Bool())
    val drained = Output(Bool())
    val memReq = Decoupled(new ComputeMemoryRequest(config))
    val memResp = Flipped(Decoupled(new ComputeMemoryResponse()))
    val wordMemReq = Decoupled(new ComputeMemoryRequest(config))
    val wordMemResp = Flipped(Decoupled(new ComputeMemoryResponse()))
  })

  // Word-client request/response interface to the bridge (driven by the FSM).
  private val wordValid = Wire(Bool())
  private val wordBits = Wire(new OmMemoryRequest)

  private val kernel = Module(new KernelShaderStage(config))
  private val bridge = Module(new OmWordToLinePort(config))

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

  bridge.io.in.valid := wordValid
  bridge.io.in.bits := wordBits
  bridge.io.out.ready := true.B
  io.wordMemReq <> bridge.io.memoryRequest
  bridge.io.memoryResponse <> io.wordMemResp

  private val sAccum :: sWrite :: sLaunch :: sRun :: sRead :: sEmit :: Nil =
    Enum(6)
  private val state = RegInit(sAccum)

  private val fragX = Reg(Vec(batchCap, SInt(gfxConfig.coordWidth.W)))
  private val fragY = Reg(Vec(batchCap, SInt(gfxConfig.coordWidth.W)))
  private val fragDepth = Reg(Vec(batchCap, SInt(32.W)))
  private val packedColor = Reg(Vec(batchCap, UInt(32.W)))
  private val outWords = Reg(Vec(batchCap, UInt(32.W)))
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

  private val curShaderPc = Reg(UInt(32.W))
  private val curKernarg = Reg(UInt(32.W))

  io.fragIn.ready := state === sAccum && count < batchCap.U
  io.drained := state === sAccum && count === 0.U

  io.out.valid := state === sEmit
  io.out.bits.x := fragX(indexIdx)
  io.out.bits.y := fragY(indexIdx)
  io.out.bits.depth := fragDepth(indexIdx)
  io.out.bits.color.r := outWords(indexIdx)(31, 24)
  io.out.bits.color.g := outWords(indexIdx)(23, 16)
  io.out.bits.color.b := outWords(indexIdx)(15, 8)

  wordValid := (state === sWrite || state === sRead) && !wordPending
  wordBits.write := state === sWrite
  wordBits.addr := Mux(
    state === sWrite,
    curKernarg + (index << 2),
    curKernarg + 64.U + (index << 2)
  )
  wordBits.data := packedColor(indexIdx)

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
        when(count === 0.U) {
          curShaderPc := io.shaderPc
          curKernarg := io.kernargBase
        }.otherwise {
          assert(
            io.shaderPc === curShaderPc && io.kernargBase === curKernarg,
            "a fragment batch must not mix draw descriptors")
        }
        count := count + 1.U
        when(count === (batchCap - 1).U) {
          index := 0.U
          wordPending := false.B
          state := sWrite
        }
      }.elsewhen(io.flush && count =/= 0.U) {
        index := 0.U
        wordPending := false.B
        state := sWrite
      }
    }
    is(sWrite) {
      when(!wordPending && bridge.io.in.fire) { wordPending := true.B }
      when(wordPending && bridge.io.out.fire) {
        wordPending := false.B
        when(index === count - 1.U) {
          index := 0.U
          state := sLaunch
        }.otherwise {
          index := index + 1.U
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
        outWords(indexIdx) := bridge.io.out.bits.data
        wordPending := false.B
        when(index === count - 1.U) {
          index := 0.U
          state := sEmit
        }.otherwise {
          index := index + 1.U
        }
      }
    }
    is(sEmit) {
      when(io.out.fire) {
        when(index === count - 1.U) {
          count := 0.U
          index := 0.U
          state := sAccum
        }.otherwise {
          index := index + 1.U
        }
      }
    }
  }
}
