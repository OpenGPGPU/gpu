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
import opengpu.core.execute.control.SimtBranchRequest
import opengpu.core.backend.issue.ScalarIssuedInstruction
import opengpu.core.backend.writeback.ScalarCommitRequest
import opengpu.core.backend.{VectorCommitRequest, VectorTextureRequest}
import opengpu.core.trap.CoreTrapEvent
import opengpu.dispatch.KernelCompletion

/** Draw descriptor emitted by `CommandBufferStage` when `vertCore = true`.
  *
  * Replaces the inline clip-space vertices of the legacy 40-word draw record
  * with a vertex-buffer pointer and vertex-shader descriptor.  The fragment
  * shader descriptor and render-target state fields are passed through to the
  * downstream pipeline unchanged.
  */
class VertexDrawCommand(config: GraphicsConfig) extends Bundle {
  val vertBufferBase = UInt(32.W)
  val vertCount = UInt(16.W)
  val vertStride = UInt(16.W)
  val vertShaderPc = UInt(32.W)
  val vertKernarg = UInt(32.W)
  val vertKernargBankStride = UInt(32.W)
  val fragShaderPc = UInt(32.W)
  val fragKernarg = UInt(32.W)
  val fragKernargBankStride = UInt(32.W)
  val stateOverride = Bool()
  val depthTestEnable = Bool()
  val depthFunc = UInt(3.W)
  val depthWriteEnable = Bool()
  val blendEnable = Bool()
  val cullMode = UInt(2.W)
  val texEnable = Bool()
  val texWrapClamp = Bool()
  val texMaxLevel = UInt(4.W)
  val texLodBias = SInt(5.W)
  val texMinLevel = UInt(4.W)
}

/** Core-backed vertex shader stage.
  *
  * Processes vertices through a compiled RV32 kernel on the SIMT compute unit,
  * mirroring the `KernelFragStage` pattern for fragments:
  *   1. read vertex attributes from the vertex buffer (through the word bridge);
  *   2. stage SoA kernarg inputs (through the word bridge);
  *   3. launch the vertex shader kernel;
  *   4. wait for kernel completion;
  *   5. read back transformed vertices (through the word bridge);
  *   6. group every 3 output vertices into a `SceneTriangle` and emit.
  *
  * Batch size is the largest multiple of 3 not exceeding `warps*lanes`.  The
  * driver pads vertex counts to multiples of 3, so each batch produces whole
  * triangles with no cross-batch accumulation.
  *
  * Vertex kernarg ABI (`stride = 4*warps*lanes`):
  *   [0*stride, 1*stride)   pos_x  (i32 Q16.16)
  *   [1*stride, 2*stride)   pos_y  (i32 Q16.16)
  *   [2*stride, 3*stride)   pos_z  (i32 Q16.16)
  *   [3*stride, 4*stride)   pos_w  (i32 Q16.16)
  *   [4*stride, 5*stride)   color  (u32 RGBA8888)
  *   [5*stride, 6*stride)   depth  (i32)
  *   [6*stride, 7*stride)   tex_u  (u32 Q16.16)
  *   [7*stride, 8*stride)   tex_v  (u32 Q16.16)
  *   [8*stride, 9*stride)   clip_x (i32 Q16.16)  [output]
  *   [9*stride, 10*stride)  clip_y (i32 Q16.16)  [output]
  *   [10*stride, 11*stride) clip_z (i32 Q16.16)  [output]
  *   [11*stride, 12*stride) clip_w (i32 Q16.16)  [output]
  *   [12*stride, 13*stride) out_color (u32 RGBA8888) [output]
  *   [13*stride, 14*stride) out_depth (i32)       [output]
  *   [14*stride, 15*stride) out_u  (u32 Q16.16)  [output]
  *   [15*stride, 16*stride) out_v  (u32 Q16.16)  [output]
  *   [16*stride, ...)       uniforms
  */
class KernelVertStage(
  config: GpuConfig = GpuConfig(),
  gfxConfig: GraphicsConfig = GraphicsConfig(),
  /** Compatibility mode for direct stage tests; RenderPipeline supplies the CU. */
  standaloneKernel: Boolean = true
) extends Module {
  private val batchCap = config.warps * config.lanes
  private val batchEff = (batchCap / 3) * 3
  require(batchEff >= 3, "need at least one triangle per batch")
  private val countWidth = math.max(1, log2Ceil(batchCap + 1))
  private val arrayStride = 4 * batchCap

  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())
    val vertBufferBase = Input(UInt(32.W))
    val vertCount = Input(UInt(16.W))
    val vertStride = Input(UInt(16.W))
    val shaderPc = Input(UInt(32.W))
    val kernargBase = Input(UInt(32.W))
    val kernargBankStride = Input(UInt(32.W))
    val fragShaderPc = Input(UInt(32.W))
    val fragKernarg = Input(UInt(32.W))
    val fragKernargBankStride = Input(UInt(32.W))
    val stateOverride = Input(Bool())
    val depthTestEnable = Input(Bool())
    val depthFunc = Input(UInt(3.W))
    val depthWriteEnable = Input(Bool())
    val blendEnable = Input(Bool())
    val cullMode = Input(UInt(2.W))
    val texEnable = Input(Bool())
    val texWrapClamp = Input(Bool())
    val texMaxLevel = Input(UInt(4.W))
    val texLodBias = Input(SInt(5.W))
    val texMinLevel = Input(UInt(4.W))
    val vertOut = Decoupled(new SceneTriangle(gfxConfig))
    val memReq = Decoupled(new ComputeMemoryRequest(config))
    val memResp = Flipped(Decoupled(new ComputeMemoryResponse()))
    val wordMemReq = Decoupled(new ComputeMemoryRequest(config))
    val wordMemResp = Flipped(Decoupled(new ComputeMemoryResponse()))
    val l1Invalidate = Flipped(Decoupled(new CacheLineInvalidate(config)))
    val l1InvalidateDone = Decoupled(new CacheLineInvalidate(config))
    val globalAtomicRequest = Decoupled(new SharedAtomicRequest(config))
    val globalAtomicResponse = Flipped(Decoupled(new SharedAtomicResponse(config)))
    val kernelLaunch = new Bundle {
      val valid = Output(Bool())
      val ready = Input(Bool())
      val kernelPc = Output(UInt(config.xLen.W))
      val kernargAddress = Output(UInt(config.xLen.W))
      val gridX = Output(UInt(32.W))
      val gridY = Output(UInt(32.W))
      val gridZ = Output(UInt(32.W))
      val localX = Output(UInt(16.W))
      val localY = Output(UInt(16.W))
      val localZ = Output(UInt(16.W))
    }
    val kernelCompletion = Flipped(Decoupled(new KernelCompletion))
    val kernelTrap = Flipped(Decoupled(new CoreTrapEvent(config)))
    val kernelSimtBranch = Flipped(Decoupled(new SimtBranchRequest(config)))
    val kernelTexSample = Flipped(Decoupled(new ScalarIssuedInstruction(config)))
    val kernelTexWriteback = Decoupled(new ScalarCommitRequest(config))
    val kernelVectorTexSample = Flipped(Decoupled(new VectorTextureRequest(config)))
    val kernelVectorTexWriteback = Decoupled(new VectorCommitRequest(config))
  })

  private val bridge = Module(new OmWordToLinePort(config))
  private val internalKernel = if (standaloneKernel) Some(Module(new KernelShaderStage(config))) else None
  private val kernelLaunchReady = WireDefault(io.kernelLaunch.ready)
  private val kernelCompletionValid = WireDefault(io.kernelCompletion.valid)

  io.kernelLaunch.valid := false.B
  io.kernelLaunch.kernelPc := 0.U
  io.kernelLaunch.kernargAddress := 0.U
  io.kernelLaunch.gridX := 1.U
  io.kernelLaunch.gridY := 1.U
  io.kernelLaunch.gridZ := 1.U
  io.kernelLaunch.localX := 1.U
  io.kernelLaunch.localY := 1.U
  io.kernelLaunch.localZ := 1.U
  io.kernelCompletion.ready := true.B
  io.kernelTrap.ready := true.B
  io.kernelSimtBranch.ready := true.B
  io.kernelTexSample.ready := false.B
  io.kernelTexWriteback.valid := false.B
  io.kernelTexWriteback.bits := 0.U.asTypeOf(io.kernelTexWriteback.bits)
  io.kernelVectorTexSample.ready := false.B
  io.kernelVectorTexWriteback.valid := false.B
  io.kernelVectorTexWriteback.bits := 0.U.asTypeOf(io.kernelVectorTexWriteback.bits)
  io.l1Invalidate.ready := false.B
  io.l1InvalidateDone.valid := false.B
  io.l1InvalidateDone.bits := 0.U.asTypeOf(io.l1InvalidateDone.bits)
  io.globalAtomicRequest.valid := false.B
  io.globalAtomicRequest.bits := 0.U.asTypeOf(io.globalAtomicRequest.bits)
  io.globalAtomicResponse.ready := false.B
  io.memReq.valid := false.B
  io.memReq.bits := 0.U.asTypeOf(io.memReq.bits)
  io.memResp.ready := false.B

  internalKernel.foreach { kernel =>
    kernel.io.launch.valid := io.kernelLaunch.valid
    kernel.io.launch.kernelPc := io.kernelLaunch.kernelPc
    kernel.io.launch.kernargAddress := io.kernelLaunch.kernargAddress
    kernel.io.launch.gridX := io.kernelLaunch.gridX
    kernel.io.launch.gridY := io.kernelLaunch.gridY
    kernel.io.launch.gridZ := io.kernelLaunch.gridZ
    kernel.io.launch.localX := io.kernelLaunch.localX
    kernel.io.launch.localY := io.kernelLaunch.localY
    kernel.io.launch.localZ := io.kernelLaunch.localZ
    kernelLaunchReady := kernel.io.launch.ready
    kernelCompletionValid := kernel.io.completion.valid
    kernel.io.completion.ready := true.B
    kernel.io.trap.ready := true.B
    kernel.io.simtBranch.valid := false.B
    kernel.io.simtBranch.bits := 0.U.asTypeOf(kernel.io.simtBranch.bits)
    io.memReq <> kernel.io.memoryRequest
    kernel.io.memoryResponse <> io.memResp
    kernel.io.l1Invalidate.valid := false.B
    kernel.io.l1Invalidate.bits := 0.U.asTypeOf(kernel.io.l1Invalidate.bits)
    kernel.io.l1InvalidateDone.ready := true.B
    kernel.io.globalAtomicRequest.ready := true.B
    kernel.io.globalAtomicResponse.valid := false.B
    kernel.io.globalAtomicResponse.bits := 0.U.asTypeOf(kernel.io.globalAtomicResponse.bits)
    kernel.io.texSample.ready := false.B
    kernel.io.texWriteback.valid := false.B
    kernel.io.texWriteback.bits := 0.U.asTypeOf(kernel.io.texWriteback.bits)
    kernel.io.vectorTexSample.ready := false.B
    kernel.io.vectorTexWriteback.valid := false.B
    kernel.io.vectorTexWriteback.bits := 0.U.asTypeOf(kernel.io.vectorTexWriteback.bits)
  }

  // -- Word bridge driven by the FSM --
  private val wordValid = Wire(Bool())
  private val wordBits = Wire(new OmMemoryRequest)
  bridge.io.in.valid := wordValid
  bridge.io.in.bits := wordBits
  bridge.io.out.ready := true.B

  // ---------------------------------------------------------------------
  // On-chip staging registers.
  // ---------------------------------------------------------------------
  private val inPosX = Reg(Vec(batchEff, SInt(32.W)))
  private val inPosY = Reg(Vec(batchEff, SInt(32.W)))
  private val inPosZ = Reg(Vec(batchEff, SInt(32.W)))
  private val inPosW = Reg(Vec(batchEff, SInt(32.W)))
  private val inColor = Reg(Vec(batchEff, UInt(32.W)))
  private val inDepth = Reg(Vec(batchEff, SInt(32.W)))
  private val inTexU = Reg(Vec(batchEff, UInt(32.W)))
  private val inTexV = Reg(Vec(batchEff, UInt(32.W)))

  private val outClipX = Reg(Vec(batchEff, SInt(32.W)))
  private val outClipY = Reg(Vec(batchEff, SInt(32.W)))
  private val outClipZ = Reg(Vec(batchEff, SInt(32.W)))
  private val outClipW = Reg(Vec(batchEff, SInt(32.W)))
  private val outColor = Reg(Vec(batchEff, UInt(32.W)))
  private val outDepth = Reg(Vec(batchEff, SInt(32.W)))
  private val outTexU = Reg(Vec(batchEff, UInt(32.W)))
  private val outTexV = Reg(Vec(batchEff, UInt(32.W)))

  // ---------------------------------------------------------------------
  // FSM.
  // ---------------------------------------------------------------------
  private val sIdle :: sReadVB :: sWrite :: sLaunch :: sRun :: sReadback :: sEmitTri :: Nil =
    Enum(7)
  private val state = RegInit(sIdle)

  private val vertIdx = RegInit(0.U(countWidth.W))
  private val vertIdxIdx = vertIdx(countWidth - 2, 0)
  private val field = RegInit(0.U(4.W))
  private val wordPending = RegInit(false.B)
  private val vbWord = RegInit(0.U(4.W))
  private val batchVertCount = RegInit(0.U(countWidth.W))
  private val drawVertBase = RegInit(0.U(16.W))
  private val remainingVerts = RegInit(0.U(16.W))
  private val emitTriIdx = RegInit(0.U(countWidth.W))
  private val emitTriTotal = RegInit(0.U(16.W))
  private val emitTriBatch = RegInit(0.U(countWidth.W))

  // Draw descriptor snapshot.
  private val snapVertBase = Reg(UInt(32.W))
  private val snapVertStride = Reg(UInt(16.W))
  private val snapShaderPc = Reg(UInt(32.W))
  private val snapKernargBase = Reg(UInt(32.W))
  private val snapKernargBankStride = Reg(UInt(32.W))
  private val snapKernarg = Reg(UInt(32.W))
  private val kernargBank = RegInit(false.B)
  private val snapFragPc = Reg(UInt(32.W))
  private val snapFragKernarg = Reg(UInt(32.W))
  private val snapFragBankStride = Reg(UInt(32.W))
  private val snapStateOverride = Reg(Bool())
  private val snapDepthTest = Reg(Bool())
  private val snapDepthFunc = Reg(UInt(3.W))
  private val snapDepthWrite = Reg(Bool())
  private val snapBlend = Reg(Bool())
  private val snapCull = Reg(UInt(2.W))
  private val snapTexEnable = Reg(Bool())
  private val snapTexWrapClamp = Reg(Bool())
  private val snapTexMaxLevel = Reg(UInt(4.W))
  private val snapTexLodBias = Reg(SInt(5.W))
  private val snapTexMinLevel = Reg(UInt(4.W))

  // -- Word bridge request generation --
  wordValid := (state === sReadVB || state === sWrite || state === sReadback) && !wordPending
  wordBits.write := (state === sWrite)
  wordBits.addr := Mux(state === sReadVB,
    snapVertBase + (drawVertBase + vertIdx) * snapVertStride + vbWord * 4.U,
    Mux(state === sWrite,
      snapKernarg + field * arrayStride.U + (vertIdx << 2),
      snapKernarg + (8.U + field) * arrayStride.U + (vertIdx << 2)))
  wordBits.data := MuxLookup(field, 0.U)(Seq(
    0.U -> inPosX(vertIdxIdx).asUInt,
    1.U -> inPosY(vertIdxIdx).asUInt,
    2.U -> inPosZ(vertIdxIdx).asUInt,
    3.U -> inPosW(vertIdxIdx).asUInt,
    4.U -> inColor(vertIdxIdx),
    5.U -> inDepth(vertIdxIdx).asUInt,
    6.U -> inTexU(vertIdxIdx),
    7.U -> inTexV(vertIdxIdx)
  ))

  // -- Triangle emission --
  private val triBase = emitTriIdx * 3.U
  private val v0 = triBase(countWidth - 2, 0)
  private val v1 = (triBase + 1.U)(countWidth - 2, 0)
  private val v2 = (triBase + 2.U)(countWidth - 2, 0)

  io.vertOut.valid := state === sEmitTri && emitTriIdx =/= emitTriBatch
  io.vertOut.bits := 0.U.asTypeOf(new SceneTriangle(gfxConfig))
  for ((vi, k) <- Seq(v0, v1, v2).zipWithIndex) {
    io.vertOut.bits.clip(k).x := outClipX(vi)
    io.vertOut.bits.clip(k).y := outClipY(vi)
    io.vertOut.bits.clip(k).z := outClipZ(vi)
    io.vertOut.bits.clip(k).w := outClipW(vi)
    io.vertOut.bits.color(k).r := outColor(vi)(31, 24)
    io.vertOut.bits.color(k).g := outColor(vi)(23, 16)
    io.vertOut.bits.color(k).b := outColor(vi)(15, 8)
    io.vertOut.bits.depth(k) := outDepth(vi)
    io.vertOut.bits.uv(k).u := outTexU(vi)
    io.vertOut.bits.uv(k).v := outTexV(vi)
  }
  io.vertOut.bits.shaderPc := snapFragPc
  io.vertOut.bits.shaderKernarg := snapFragKernarg
  io.vertOut.bits.kernargBankStride := snapFragBankStride
  io.vertOut.bits.stateOverride := snapStateOverride
  io.vertOut.bits.depthTestEnable := snapDepthTest
  io.vertOut.bits.depthFunc := snapDepthFunc
  io.vertOut.bits.depthWriteEnable := snapDepthWrite
  io.vertOut.bits.blendEnable := snapBlend
  io.vertOut.bits.cullMode := snapCull
  io.vertOut.bits.texEnable := snapTexEnable
  io.vertOut.bits.texWrapClamp := snapTexWrapClamp
  io.vertOut.bits.texMaxLevel := snapTexMaxLevel
  io.vertOut.bits.texLodBias := snapTexLodBias
  io.vertOut.bits.texMinLevel := snapTexMinLevel

  io.done := state === sIdle

  // ---------------------------------------------------------------------
  // FSM transitions.
  // ---------------------------------------------------------------------
  switch(state) {
    is(sIdle) {
      when(io.start && io.vertCount >= 3.U) {
        snapVertBase := io.vertBufferBase
        snapVertStride := io.vertStride
        snapShaderPc := io.shaderPc
        snapKernargBase := io.kernargBase
        snapKernargBankStride := io.kernargBankStride
        snapKernarg := io.kernargBase
        kernargBank := false.B
        snapFragPc := io.fragShaderPc
        snapFragKernarg := io.fragKernarg
        snapFragBankStride := io.fragKernargBankStride
        snapStateOverride := io.stateOverride
        snapDepthTest := io.depthTestEnable
        snapDepthFunc := io.depthFunc
        snapDepthWrite := io.depthWriteEnable
        snapBlend := io.blendEnable
        snapCull := io.cullMode
        snapTexEnable := io.texEnable
        snapTexWrapClamp := io.texWrapClamp
        snapTexMaxLevel := io.texMaxLevel
        snapTexLodBias := io.texLodBias
        snapTexMinLevel := io.texMinLevel
        drawVertBase := 0.U
        remainingVerts := io.vertCount
        emitTriTotal := io.vertCount / 3.U
        val thisBatch = Mux(io.vertCount >= batchEff.U,
          batchEff.U, (io.vertCount / 3.U) * 3.U)
        batchVertCount := thisBatch
        emitTriBatch := thisBatch / 3.U
        emitTriIdx := 0.U
        vertIdx := 0.U
        vbWord := 0.U
        field := 0.U
        wordPending := false.B
        state := sReadVB
      }
    }
    is(sReadVB) {
      when(!wordPending && bridge.io.in.fire) { wordPending := true.B }
      when(wordPending && bridge.io.out.fire && !bridge.io.out.bits.write) {
        wordPending := false.B
        val w = bridge.io.out.bits.data
        when(vbWord === 0.U) { inPosX(vertIdxIdx) := w.asSInt }
        when(vbWord === 1.U) { inPosY(vertIdxIdx) := w.asSInt }
        when(vbWord === 2.U) { inPosZ(vertIdxIdx) := w.asSInt }
        when(vbWord === 3.U) { inPosW(vertIdxIdx) := w.asSInt }
        when(vbWord === 4.U) { inColor(vertIdxIdx) := w }
        when(vbWord === 5.U) { inDepth(vertIdxIdx) := w.asSInt }
        when(vbWord === 6.U) { inTexU(vertIdxIdx) := w }
        when(vbWord === 7.U) { inTexV(vertIdxIdx) := w }
        when(vbWord === 7.U) {
          vbWord := 0.U
          when(vertIdx === batchVertCount - 1.U) {
            vertIdx := 0.U
            field := 0.U
            state := sWrite
          }.otherwise {
            vertIdx := vertIdx + 1.U
          }
        }.otherwise {
          vbWord := vbWord + 1.U
        }
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
          when(vertIdx === batchVertCount - 1.U) {
            vertIdx := 0.U
            state := sLaunch
          }.otherwise {
            vertIdx := vertIdx + 1.U
          }
        }
      }
    }
    is(sLaunch) {
      io.kernelLaunch.valid := true.B
      io.kernelLaunch.kernelPc := snapShaderPc
      io.kernelLaunch.kernargAddress := snapKernarg
      io.kernelLaunch.localX := batchVertCount
      when(kernelLaunchReady) { state := sRun }
    }
    is(sRun) {
      when(kernelCompletionValid) {
        vertIdx := 0.U
        field := 0.U
        wordPending := false.B
        state := sReadback
      }
    }
    is(sReadback) {
      when(!wordPending && bridge.io.in.fire) { wordPending := true.B }
      when(wordPending && bridge.io.out.fire && !bridge.io.out.bits.write) {
        wordPending := false.B
        val idx = vertIdxIdx
        when(field === 0.U) { outClipX(idx) := bridge.io.out.bits.data.asSInt }
        when(field === 1.U) { outClipY(idx) := bridge.io.out.bits.data.asSInt }
        when(field === 2.U) { outClipZ(idx) := bridge.io.out.bits.data.asSInt }
        when(field === 3.U) { outClipW(idx) := bridge.io.out.bits.data.asSInt }
        when(field === 4.U) { outColor(idx) := bridge.io.out.bits.data }
        when(field === 5.U) { outDepth(idx) := bridge.io.out.bits.data.asSInt }
        when(field === 6.U) { outTexU(idx) := bridge.io.out.bits.data }
        when(field === 7.U) {
          outTexV(idx) := bridge.io.out.bits.data
          field := 0.U
          when(vertIdx === batchVertCount - 1.U) {
            vertIdx := 0.U
            emitTriIdx := 0.U
            state := sEmitTri
          }.otherwise {
            vertIdx := vertIdx + 1.U
          }
        }.otherwise {
          field := field + 1.U
        }
      }
    }
    is(sEmitTri) {
      when(io.vertOut.fire) {
        emitTriIdx := emitTriIdx + 1.U
        when(emitTriIdx === emitTriBatch - 1.U) {
          val newRemaining = remainingVerts - batchVertCount
          remainingVerts := newRemaining
          drawVertBase := drawVertBase + batchVertCount
          when(newRemaining >= 3.U) {
            // Alternate complete kernarg banks between batches.  Besides
            // separating staging writes, this prevents a following batch
            // from observing private-L1 lines left by the previous launch.
            snapKernarg := Mux(snapKernargBankStride.orR && !kernargBank,
              snapKernargBase + snapKernargBankStride, snapKernargBase)
            kernargBank := !kernargBank
            val nextBatch = Mux(newRemaining >= batchEff.U,
              batchEff.U, (newRemaining / 3.U) * 3.U)
            batchVertCount := nextBatch
            emitTriBatch := nextBatch / 3.U
            vertIdx := 0.U
            vbWord := 0.U
            field := 0.U
            wordPending := false.B
            state := sReadVB
          }.otherwise {
            state := sIdle
          }
        }
      }
    }
  }

  // -- Memory port mux: bridge is active during VB read, write, and readback --
  private val stagingActive = state === sWrite || state === sReadback ||
    state === sReadVB
  io.wordMemReq.valid := Mux(stagingActive,
    bridge.io.memoryRequest.valid, false.B)
  io.wordMemReq.bits := Mux(stagingActive,
    bridge.io.memoryRequest.bits,
    0.U.asTypeOf(bridge.io.memoryRequest.bits))
  bridge.io.memoryRequest.ready := io.wordMemReq.ready && stagingActive
  io.wordMemResp.ready := Mux(stagingActive,
    bridge.io.memoryResponse.ready, false.B)
  bridge.io.memoryResponse.valid := io.wordMemResp.valid && stagingActive
  bridge.io.memoryResponse.bits := io.wordMemResp.bits
}
