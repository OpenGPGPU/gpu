package gpu.graphics

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

/** Core-backed fragment shader stage (Phase D), drop-in for `ShaderFragStage`.
  *
  * A fused fragment is shaded by a kernel launched on the compute unit's SIMT
  * warps rather than the private shader core.  For one fragment at a time:
  *   1. pack its varyings into the kernarg buffer (word 0 = packed RGBA colour,
  *      written through the word->line bridge) at `kernargBase`;
  *   2. launch the shader kernel (entry `shaderPc`, kernarg `kernargBase`) on
  *      `KernelShaderStage`;
  *   3. wait for kernel completion; the kernel writes its output colour to the
  *      kernarg buffer word 1 (kernargBase+4);
  *   4. read word 1 back through the bridge and emit the shaded fragment.
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
  val io = IO(new Bundle {
    val fragIn = Flipped(Decoupled(new RasterFragment(gfxConfig)))
    val out = Decoupled(new RasterFragment(gfxConfig))
    val shaderPc = Input(UInt(32.W))
    val kernargBase = Input(UInt(32.W))
    val memReq = Decoupled(new ComputeMemoryRequest(config))
    val memResp = Flipped(Decoupled(new ComputeMemoryResponse()))
    val wordMemReq = Decoupled(new ComputeMemoryRequest(config))
    val wordMemResp = Flipped(Decoupled(new ComputeMemoryResponse()))
  })

  // Word-client request/response interface to the bridge (driven by the FSM).
  private val wordValid = Wire(Bool())
  private val wordBits = Wire(new OmMemoryRequest)
  private val wordOutReady = Wire(Bool())
  private val wordReady = Wire(Bool())

  private val kernel = Module(new KernelShaderStage(config))
  private val bridge = Module(new OmWordToLinePort(config))

  kernel.io.launch.kernelPc := io.shaderPc
  kernel.io.launch.kernargAddress := io.kernargBase
  kernel.io.launch.gridX := 1.U
  kernel.io.launch.gridY := 1.U
  kernel.io.launch.gridZ := 1.U
  kernel.io.launch.localX := 3.U
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
  wordReady := bridge.io.in.ready
  bridge.io.out.ready := wordOutReady
  io.wordMemReq <> bridge.io.memoryRequest
  bridge.io.memoryResponse <> io.wordMemResp

  private val idle :: sWriteColor :: sLaunch :: sRun :: sReadOut :: sEmit :: Nil =
    Enum(6)
  private val state = RegInit(idle)

  private val fragX = Reg(SInt(gfxConfig.coordWidth.W))
  private val fragY = Reg(SInt(gfxConfig.coordWidth.W))
  private val fragDepth = Reg(SInt(32.W))
  private val packedColor = Reg(UInt(32.W))
  private val shadedColor = Reg(new Varyings)
  private val outWord = Reg(UInt(32.W))

  io.fragIn.ready := state === idle
  io.out.valid := state === sEmit
  io.out.bits.x := fragX
  io.out.bits.y := fragY
  io.out.bits.depth := fragDepth
  io.out.bits.color := shadedColor

  private val packColorAddr = io.kernargBase
  private val readOutAddr = io.kernargBase + 4.U

  wordOutReady := true.B
  when(state === sWriteColor) {
    wordValid := true.B
    wordBits.write := true.B
    wordBits.addr := packColorAddr
    wordBits.data := packedColor
  }.elsewhen(state === sReadOut) {
    wordValid := true.B
    wordBits.write := false.B
    wordBits.addr := readOutAddr
    wordBits.data := 0.U
  }.otherwise {
    wordValid := false.B
    wordBits.write := false.B
    wordBits.addr := io.kernargBase
    wordBits.data := 0.U
  }

  kernel.io.launch.valid := state === sLaunch

  switch(state) {
    is(idle) {
      when(io.fragIn.fire) {
        fragX := io.fragIn.bits.x
        fragY := io.fragIn.bits.y
        fragDepth := io.fragIn.bits.depth
        packedColor := Cat(
          io.fragIn.bits.color.r,
          io.fragIn.bits.color.g,
          io.fragIn.bits.color.b,
          0xff.U(8.W))
        state := sWriteColor
      }
    }
    is(sWriteColor) {
      when(bridge.io.out.valid) { state := sLaunch }
    }
    is(sLaunch) {
      when(kernel.io.launch.ready) { state := sRun }
    }
    is(sRun) {
      when(kernel.io.completion.valid) { state := sReadOut }
    }
    is(sReadOut) {
      when(bridge.io.out.valid) {
        shadedColor.r := bridge.io.out.bits.data(31, 24)
        shadedColor.g := bridge.io.out.bits.data(23, 16)
        shadedColor.b := bridge.io.out.bits.data(15, 8)
        state := sEmit
      }
    }
    is(sEmit) {
      when(io.out.fire) { state := idle }
    }
  }
}
