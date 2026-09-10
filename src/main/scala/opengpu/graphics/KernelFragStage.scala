package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.execute.control.SimtBranchRequest
import opengpu.core.backend.issue.ScalarIssuedInstruction
import opengpu.core.backend.writeback.ScalarCommitRequest
import opengpu.core.backend.{VectorCommitRequest, VectorTextureRequest}
import opengpu.core.memory.{
  CacheLineInvalidate,
  ComputeMemoryRequest,
  ComputeMemoryResponse,
  SharedAtomicRequest,
  SharedAtomicResponse
}
import opengpu.core.trap.CoreTrapEvent
import opengpu.dispatch.KernelCompletion

/** Packed producer-side quad record. Keeping the four lanes together reduces
  * the number of independent dynamic array reads in the consumer path. */
private class KernelFragQuadRecord(gfxConfig: GraphicsConfig) extends Bundle {
  val lanes = Vec(4, new RasterFragment(gfxConfig))
  val u = Vec(4, UInt(32.W))
  val v = Vec(4, UInt(32.W))
}

/** Core-backed fragment shader stage (Phase D), batched quad dispatch with
  * per-draw overlap.
  *
  * Fragments arrive as whole 2x2 quads (`fragIn`, one TL/TR/BL/BR group per
  * beat with per-lane coverage as the helper-lane mask) and are accumulated
  * four lanes per beat into a batch of up to `warps*lanes` and shaded by
  * ONE kernel launch on the compute unit's SIMT warps (lane = fragment):
  *   1. accumulate: buffer the quad lanes' x/y/depth and write their x, y,
  *      depth and packed colour into the per-fragment input arrays (through
  *      the word->line bridge);
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
  * Batch counts are always multiples of four (every beat appends exactly one
  * quad at the next 4-aligned index), so the lane%4 quad mapping the
  * `vquad.dfdx/dfdy` derivative ops rely on is preserved end to end.
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
  * Overlap: the stage keeps TWO staging slots (ping-pong).  While the
  * consumer FSM streams/launches/runs/reads/emits one slot's batch, the
  * producer keeps accumulating the next draw's fragments into the other slot,
  * so rasterization of draw N+1 overlaps SIMT execution of batch N.  Batches
  * never mix draws: `flush` marks the rasterizer-idle draw boundary and
  * commits the accumulated batch; a full batch also commits.  The committed
  * slot stalls the producer until the consumer drains and swaps.  Each slot
  * snapshots its draw's shader descriptor and sampler state at its first
  * snapshot at first fragment, and executes against the kernarg bank selected
  * by slot parity (`kernargBase + slot * kernargBankStride` when a bank
  * stride is programmed), so consecutive batches alternate banks exactly as
  * the dual kernarg bank ABI requires (zero stride retains legacy single-bank
  * execution).
  *
  * Coherence note (software contract): a kernel's input loads hit the CU's
  * L1 while batch staging writes reach memory through the word bridge, so
  * two consecutively executed batches must not read addresses a previous
  * batch's kernel also loaded from the same lines.  Software avoids this by
  * programming the dual kernarg bank stride (the Linux driver always does)
  * or by giving draws disjoint kernarg buffers; with a shared L2 the
  * external-write invalidation covers it as well.  Zero-stride legacy
  * single-bank streams are only safe when consecutive batches do not reuse
  * loaded lines (uniform per-fragment inputs or single-batch draws).
  *
  * `drawRetire` is an ordered valid/ready handshake carrying one completion
  * event per rising `flush` (i.e. per draw, including empty draws).  An event
  * is presented once that draw's last batch has been emitted, and is accepted
  * by the owner only when the output merger is idle, so the draw's context
  * can be retired without reordering.
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
  require(batchCap % 4 == 0, "fragment batches accumulate whole 2x2 quads")
  private val countWidth = math.max(1, log2Ceil(batchCap + 1))
  // Per-array byte stride of the SoA kernarg layout (see the class doc).
  private val arrayStride = 4 * batchCap

  val io = IO(new Bundle {
    val fragIn = Flipped(Decoupled(new FragmentQuad(gfxConfig)))
    /** Per-lane perspective-correct UVs of the presented quad. */
    val fragUv = Input(Vec(4, new TexUV))
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
    /** Ordered per-draw completion event (one per rising flush). */
    val drawRetire = Decoupled(Bool())
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
    val kernelVectorTexSample =
      Flipped(Decoupled(new VectorTextureRequest(config)))
    val kernelVectorTexWriteback =
      Decoupled(new VectorCommitRequest(config))
  })

  // Word-client request/response interface to the bridge (driven by the FSM).
  private val wordValid = Wire(Bool())
  private val wordBits = Wire(new OmMemoryRequest)

  private val bridge = Module(new OmWordToLinePort(config))
  private val texBridge = Module(new OmWordToLinePort(config))
  private val texUnit = Module(new TexSampleUnit(config, gfxConfig))

  texUnit.io.in.valid := io.kernelTexSample.valid
  texUnit.io.in.bits := io.kernelTexSample.bits
  io.kernelTexSample.ready := texUnit.io.in.ready
  io.kernelTexWriteback.valid := texUnit.io.commit.valid
  io.kernelTexWriteback.bits := texUnit.io.commit.bits
  texUnit.io.commit.ready := io.kernelTexWriteback.ready
  texUnit.io.vectorIn.valid := io.kernelVectorTexSample.valid
  texUnit.io.vectorIn.bits := io.kernelVectorTexSample.bits
  io.kernelVectorTexSample.ready := texUnit.io.vectorIn.ready
  io.kernelVectorTexWriteback.valid := texUnit.io.vectorCommit.valid
  io.kernelVectorTexWriteback.bits := texUnit.io.vectorCommit.bits
  texUnit.io.vectorCommit.ready := io.kernelVectorTexWriteback.ready
  texBridge.io.in <> texUnit.io.mem.req
  texUnit.io.mem.resp <> texBridge.io.out

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

  io.memReq.valid := false.B
  io.memReq.bits := 0.U.asTypeOf(io.memReq.bits)
  io.memResp.ready := false.B
  io.l1Invalidate.ready := false.B
  io.l1InvalidateDone.valid := false.B
  io.l1InvalidateDone.bits := 0.U.asTypeOf(io.l1InvalidateDone.bits)
  io.globalAtomicRequest.valid := false.B
  io.globalAtomicRequest.bits := 0.U.asTypeOf(io.globalAtomicRequest.bits)
  io.globalAtomicResponse.ready := false.B

  // Only one word transaction is issued at a time. A dedicated request
  // register is sufficient and avoids the large index/field -> Queue RAM
  // write path created by a one-entry Queue.
  private val wordReqReg = Reg(new OmMemoryRequest)
  private val wordReqValid = RegInit(false.B)
  bridge.io.in.valid := wordReqValid
  bridge.io.in.bits := wordReqReg
  bridge.io.out.ready := true.B

  // ---------------------------------------------------------------------
  // Ping-pong staging slots. Producer writes use registered slot/quad
  // strobes; consumer reads are indexed dynamically by execSlot and index.
  // ---------------------------------------------------------------------
  private val fragRecords = Reg(Vec(2, Vec(batchCap / 4, new KernelFragQuadRecord(gfxConfig))))
  // Output staging is consumer-only (one batch reads/emit at a time).
  private val outWords = Reg(Vec(batchCap, UInt(32.W)))
  private val outDepth = Reg(Vec(batchCap, SInt(32.W)))
  private val outValid = Reg(Vec(batchCap, Bool()))

  private val slotCount = RegInit(VecInit(0.U(countWidth.W), 0.U(countWidth.W)))
  private val prodSlot = RegInit(0.U(1.W))
  private val execSlot = RegInit(0.U(1.W))

  // Per-slot draw descriptor snapshot (registered at the slot's first
  // fragment; bank parity = slot index).
  private val slotShaderPc = Reg(Vec(2, UInt(32.W)))
  private val slotKernarg = Reg(Vec(2, UInt(32.W)))
  private val slotTexBase = Reg(Vec(2, UInt(32.W)))
  private val slotTexWidth = Reg(Vec(2, UInt(14.W)))
  private val slotTexHeight = Reg(Vec(2, UInt(14.W)))
  private val slotTexWrapClamp = Reg(Vec(2, Bool()))
  private val slotTexMaxLevel = Reg(Vec(2, UInt(4.W)))
  private val slotTexLodBias = Reg(Vec(2, SInt(5.W)))
  private val slotTexMinLevel = Reg(Vec(2, UInt(4.W)))

  texUnit.io.texBase := slotTexBase(execSlot)
  texUnit.io.texWidth := slotTexWidth(execSlot)
  texUnit.io.texHeight := slotTexHeight(execSlot)
  texUnit.io.wrapClamp := slotTexWrapClamp(execSlot)
  texUnit.io.texMaxLevel := slotTexMaxLevel(execSlot)
  texUnit.io.lodBias := slotTexLodBias(execSlot)
  texUnit.io.minLevel := slotTexMinLevel(execSlot)

  private def bankedKernarg(slot: UInt): UInt = Mux(
    slot === 1.U && io.kernargBankStride.orR,
    io.kernargBase + io.kernargBankStride,
    io.kernargBase)

  private val sIdle :: sWrite :: sLaunch :: sRun :: sRead :: sEmit :: Nil =
    Enum(6)
  private val state = RegInit(sIdle)

  private val execCount = RegInit(0.U(countWidth.W))
  private val index = RegInit(0.U(countWidth.W))
  // Vec indexing only ever touches [0, batchCap); narrowing avoids an
  // over-wide dynamic index.  Counts reach `batchCap` for the fullness
  // comparison, so they keep the wider width and are narrowed when used to
  // index a Vec.
  private val indexIdx = index(countWidth - 2, 0)
  private val quadIdxWidth = math.max(1, log2Ceil(batchCap / 4))
  // These selectors drive the staging request data path. They advance with
  // the transaction response, decoupling the request muxes from `index`.
  private val requestQuadIdx = RegInit(0.U(quadIdxWidth.W))
  private val requestLaneIdx = RegInit(0.U(2.W))
  // Select the lane inside each statically addressed slot before muxing the
  // scalar values.  Muxing the outer Vec first creates a packed-array mux
  // that firtool cannot lower with ARTI's disallowPackedArrays setting.
  private val quadIdx = indexIdx >> 2
  private val laneIdx = index(1, 0)
  // Select fields individually; muxing a Bundle/Vec as a packed aggregate is
  // rejected by the target FIRRTL flow and also creates a needlessly wide
  // packed-array mux.
  private def slotField[T <: Data](quad: UInt, lane: UInt)(f: KernelFragQuadRecord => T): T =
    Mux(execSlot.asBool, f(fragRecords(1)(quad)), f(fragRecords(0)(quad)))
  private val execX = slotField(quadIdx, laneIdx)(r => r.lanes(laneIdx).x)
  private val execY = slotField(quadIdx, laneIdx)(r => r.lanes(laneIdx).y)
  private val execDepth = slotField(quadIdx, laneIdx)(r => r.lanes(laneIdx).depth)
  private val execR = slotField(quadIdx, laneIdx)(r => r.lanes(laneIdx).color.r)
  private val execG = slotField(quadIdx, laneIdx)(r => r.lanes(laneIdx).color.g)
  private val execB = slotField(quadIdx, laneIdx)(r => r.lanes(laneIdx).color.b)
  private val execAlpha = slotField(quadIdx, laneIdx)(r => r.lanes(laneIdx).alpha)
  private val execE0 = slotField(quadIdx, laneIdx)(r => r.lanes(laneIdx).e0)
  private val execE1 = slotField(quadIdx, laneIdx)(r => r.lanes(laneIdx).e1)
  private val execE2 = slotField(quadIdx, laneIdx)(r => r.lanes(laneIdx).e2)
  private val execCovered = slotField(quadIdx, laneIdx)(r => r.lanes(laneIdx).covered)
  private val execU = slotField(quadIdx, laneIdx)(r => r.u(laneIdx))
  private val execV = slotField(quadIdx, laneIdx)(r => r.v(laneIdx))
  private val requestX = slotField(requestQuadIdx, requestLaneIdx)(r => r.lanes(requestLaneIdx).x)
  private val requestY = slotField(requestQuadIdx, requestLaneIdx)(r => r.lanes(requestLaneIdx).y)
  private val requestDepth = slotField(requestQuadIdx, requestLaneIdx)(r => r.lanes(requestLaneIdx).depth)
  private val requestR = slotField(requestQuadIdx, requestLaneIdx)(r => r.lanes(requestLaneIdx).color.r)
  private val requestG = slotField(requestQuadIdx, requestLaneIdx)(r => r.lanes(requestLaneIdx).color.g)
  private val requestB = slotField(requestQuadIdx, requestLaneIdx)(r => r.lanes(requestLaneIdx).color.b)
  private val requestAlpha = slotField(requestQuadIdx, requestLaneIdx)(r => r.lanes(requestLaneIdx).alpha)
  private val requestCovered = slotField(requestQuadIdx, requestLaneIdx)(r => r.lanes(requestLaneIdx).covered)
  private val requestU = slotField(requestQuadIdx, requestLaneIdx)(r => r.u(requestLaneIdx))
  private val requestV = slotField(requestQuadIdx, requestLaneIdx)(r => r.v(requestLaneIdx))
  // One bridge transaction at a time keeps the staging FSM simple; the bridge
  // itself supports more outstanding transactions for other clients.
  private val wordPending = RegInit(false.B)
  // Staging field: writes 0=x, 1=y, 2=depth, 3=packed colour, 4=u, 5=v,
  // 6=depth-output initialization, 7=output-valid initialization; reads
  // 0=colour, 1=depth, 2=output-valid.
  private val field = RegInit(0.U(4.W))

  // ---------------------------------------------------------------------
  // Producer: accumulate the next draw's quads into slot prodSlot, four
  // lanes per beat, so batch counts are always multiples of four and every
  // fragment lands at the 4-aligned index the quad derivative ops expect.
  // ---------------------------------------------------------------------
  private val prodCount = slotCount(prodSlot)
  // Count including a quad accepted this cycle (commit-by-fullness takes
  // the batch with the firing quad; a flush commit has no fire).
  private val prodCountNext = prodCount + Mux(io.fragIn.fire, 4.U, 0.U)

  private val pendingValid = RegInit(false.B)
  private val pendingSlot = Reg(UInt(1.W))
  private val pendingCarries = RegInit(false.B)
  private val pendingEntryIdx = Reg(UInt(1.W))

  // ---------------------------------------------------------------------
  // Producer write pipe. Capture a one-hot slot/quad write strobe alongside
  // the quad, then write statically indexed entries one cycle later. Quad
  // alignment fixes each lane's destination within its group, eliminating
  // the binary lane-index decode and dynamic Vec write muxes at the drain.
  // ---------------------------------------------------------------------
  private val wpValid = RegInit(false.B)
  private val wpSlot = Reg(UInt(1.W))
  private val wpFirst = Reg(Bool())
  private val wpWrite = RegInit(VecInit(Seq.fill(2)(
    VecInit(Seq.fill(batchCap / 4)(false.B)))))
  private val wpRecord = Reg(new KernelFragQuadRecord(gfxConfig))
  private val wpShaderPc = Reg(UInt(32.W))
  private val wpKernarg = Reg(UInt(32.W))
  private val wpTexBase = Reg(UInt(32.W))
  private val wpTexWidth = Reg(UInt(14.W))
  private val wpTexHeight = Reg(UInt(14.W))
  private val wpTexWrapClamp = Reg(Bool())
  private val wpTexMaxLevel = Reg(UInt(4.W))
  private val wpTexLodBias = Reg(SInt(5.W))
  private val wpTexMinLevel = Reg(UInt(4.W))

  io.fragIn.ready := !pendingValid && prodCount < batchCap.U
  io.drained := state === sIdle && !pendingValid

  // ---------------------------------------------------------------------
  // Draw-boundary commit and the ordered retire queue.
  //
  // Every rising `flush` pushes exactly one retire entry (depth two: at most
  // two draws can be outstanding because the draw-context FIFO above also
  // holds two).  An entry is "done" when the draw produced no batch (empty
  // entry, done at enqueue) or when the batch bound to it drains.  Batches
  // bind to entries when committed at a flush (accumulated fragments = the
  // draw's last batch) or when a later empty-accumulation flush promotes the
  // most recent unbound batch.
  // ---------------------------------------------------------------------
  private val qDone = RegInit(VecInit(true.B, true.B))
  private val qHead = RegInit(0.U(1.W))
  private val qCount = RegInit(0.U(2.W))
  private val qTail = qHead ^ qCount(0)
  private val execCarries = RegInit(false.B)
  private val execEntryIdx = Reg(UInt(1.W))

  private val prevFlush = RegInit(true.B)
  prevFlush := io.flush
  private val flushRise = io.flush && !prevFlush

  private val prodNonEmpty = prodCount =/= 0.U
  private val commitFull = io.fragIn.fire && prodCount === (batchCap - 4).U
  private val commitFlush = flushRise && prodNonEmpty
  private val commitAny = (commitFull || commitFlush) && !pendingValid

  when(flushRise) { assert(qCount =/= 2.U, "retire queue overflow") }

  // Producer accumulation (data always; count frozen on a take-commit).
  // Each fire captures all four lanes of the presented quad plus the
  // resolved slot and quad write strobe into the write pipe; the staging
  // arrays are written when the pipe drains one cycle later.
  wpValid := io.fragIn.fire
  for (slot <- 0 until 2; group <- 0 until batchCap / 4) {
    wpWrite(slot)(group) := io.fragIn.fire && prodSlot === slot.U &&
      prodCount === (4 * group).U
  }
  when(io.fragIn.fire) {
    wpSlot := prodSlot
    wpFirst := prodCount === 0.U
    for (k <- 0 until 4) {
      wpRecord.lanes(k) := io.fragIn.bits.lanes(k)
      // The graphics ABI uses opaque RGBA8888 fragment inputs; the raster
      // quad source carries RGB varyings and leaves alpha unspecified.
      wpRecord.lanes(k).alpha := 0xff.U
      wpRecord.u(k) := io.fragUv(k).u
      wpRecord.v(k) := io.fragUv(k).v
    }
    wpShaderPc := io.shaderPc
    wpKernarg := bankedKernarg(prodSlot)
    wpTexBase := io.texBase
    wpTexWidth := io.texWidth
    wpTexHeight := io.texHeight
    wpTexWrapClamp := io.texWrapClamp
    wpTexMaxLevel := io.texMaxLevel
    wpTexLodBias := io.texLodBias
    wpTexMinLevel := io.texMinLevel
    when(prodCount =/= 0.U) {
      // The first quad's descriptor snapshot may still be in the pipe on
      // back-to-back fires, so compare against whichever copy is live.
      val snapLive = wpValid && wpFirst && wpSlot === prodSlot
      assert(
        io.shaderPc === Mux(snapLive, wpShaderPc, slotShaderPc(prodSlot)) &&
          bankedKernarg(prodSlot) === Mux(snapLive, wpKernarg, slotKernarg(prodSlot)),
        "a fragment batch must not mix draw descriptors")
    }
    when(!(commitAny && state === sIdle)) {
      slotCount(prodSlot) := prodCountNext
    }
  }

  // Write-pipe drain: stage the captured quad into the resolved slot.  The
  // drain always fires, so the pipe never back-pressures `fragIn`.
  for (slot <- 0 until 2; group <- 0 until batchCap / 4) {
    when(wpWrite(slot)(group)) {
      fragRecords(slot)(group) := wpRecord
    }
  }
  when(wpValid) {
    when(wpFirst) {
      slotShaderPc(wpSlot) := wpShaderPc
      slotKernarg(wpSlot) := wpKernarg
      slotTexBase(wpSlot) := wpTexBase
      slotTexWidth(wpSlot) := wpTexWidth
      slotTexHeight(wpSlot) := wpTexHeight
      slotTexWrapClamp(wpSlot) := wpTexWrapClamp
      slotTexMaxLevel(wpSlot) := wpTexMaxLevel
      slotTexLodBias(wpSlot) := wpTexLodBias
      slotTexMinLevel(wpSlot) := wpTexMinLevel
    }
  }

  private def pushEntry(done: Bool): Unit = {
    qDone(qTail) := done
  }

  when(commitAny) {
    when(state === sIdle) {
      // Take immediately: the consumer is idle, so the committed batch
      // starts streaming straight away.
      execSlot := prodSlot
      execCount := prodCountNext
      execCarries := commitFlush
      when(commitFlush) { execEntryIdx := qTail }
      slotCount(prodSlot) := 0.U
      prodSlot := ~prodSlot
      index := 0.U
      requestQuadIdx := 0.U
      requestLaneIdx := 0.U
      field := 0.U
      wordPending := false.B
      state := sWrite
    }.otherwise {
      // Consumer busy: park the batch; the drain swaps it in.
      pendingValid := true.B
      pendingSlot := prodSlot
      pendingCarries := commitFlush
      when(commitFlush) { pendingEntryIdx := qTail }
    }
  }
  when(commitAny && commitFlush) { pushEntry(false.B) }

  // Empty-accumulation flush: the flushing draw produced no new batch.  Bind
  // the most recent unbound batch (pending, else executing) as the draw's
  // last; otherwise the draw retires as an empty entry.
  private val bindPending = pendingValid && !pendingCarries
  private val bindExec = !pendingValid && state =/= sIdle && !execCarries
  when(flushRise && !commitFlush) {
    when(prodNonEmpty && pendingValid) {
      assert(false.B, "producer must be stalled while a batch is parked")
    }
    when(bindPending) {
      pendingCarries := true.B
      pendingEntryIdx := qTail
      pushEntry(false.B)
    }.elsewhen(bindExec) {
      execCarries := true.B
      execEntryIdx := qTail
      pushEntry(false.B)
    }.otherwise {
      pushEntry(true.B)
    }
  }

  // Retire handshake: present completed entries in order.
  io.drawRetire.valid := qCount =/= 0.U && qDone(qHead)
  io.drawRetire.bits := true.B
  when(io.drawRetire.fire) {
    qHead := ~qHead
    qCount := qCount - 1.U
  }
  // A flush pushes exactly one entry; composed with a same-cycle retire.
  when(flushRise) {
    qCount := Mux(io.drawRetire.fire, qCount, qCount + 1.U)
  }

  // ---------------------------------------------------------------------
  // Consumer FSM: stream the executing slot's batch into its kernarg bank,
  // launch, wait, read back, emit — then swap in the parked batch.
  // ---------------------------------------------------------------------
  wordValid := (state === sWrite || state === sRead) && !wordPending && !wordReqValid
  wordBits.write := state === sWrite
  private val writeSlice = MuxLookup(field, 8.U)(Seq(
    0.U -> 0.U, 1.U -> 1.U, 2.U -> 2.U, 3.U -> 3.U,
    4.U -> 4.U, 5.U -> 5.U, 6.U -> 7.U))
  wordBits.addr := Mux(
    state === sWrite,
    slotKernarg(execSlot) + writeSlice * arrayStride.U + (index << 2),
    slotKernarg(execSlot) + ((6.U + field) * arrayStride.U) + (index << 2)
  )
  wordBits.data := MuxLookup(field, requestCovered.asUInt)(
    Seq(
      0.U -> requestX.pad(32).asUInt,
      1.U -> requestY.pad(32).asUInt,
      2.U -> requestDepth.asUInt,
      3.U -> Cat(requestR, requestG,
        requestB, requestAlpha),
      4.U -> requestU,
      5.U -> requestV,
      6.U -> requestDepth.asUInt
    )
  )

  io.kernelLaunch.valid := state === sLaunch

  io.out.valid := state === sEmit && outValid(indexIdx)
  io.out.bits.x := execX
  io.out.bits.y := execY
  io.out.bits.depth := outDepth(indexIdx)
  io.out.bits.e0 := execE0
  io.out.bits.e1 := execE1
  io.out.bits.e2 := execE2
  io.out.bits.covered := execCovered
  io.out.bits.color.r := outWords(indexIdx)(31, 24)
  io.out.bits.color.g := outWords(indexIdx)(23, 16)
  io.out.bits.color.b := outWords(indexIdx)(15, 8)
  io.out.bits.alpha := outWords(indexIdx)(7, 0)

  switch(state) {
    is(sIdle) {
      // Nothing executing; the producer fills prodSlot (equal to execSlot
      // until the first commit flips it).
    }
    is(sWrite) {
      when(!wordPending && !wordReqValid && bridge.io.in.ready) {
        wordReqReg := wordBits
        wordReqValid := true.B
      }
      when(wordReqValid && bridge.io.in.fire) { wordReqValid := false.B; wordPending := true.B }
      when(wordPending && bridge.io.out.fire) {
        wordPending := false.B
        when(field =/= 7.U) {
          field := field + 1.U
        }.otherwise {
          field := 0.U
          when(index === execCount - 1.U) {
            index := 0.U
            requestQuadIdx := 0.U
            requestLaneIdx := 0.U
            state := sLaunch
          }.otherwise {
            index := index + 1.U
            when(requestLaneIdx === 3.U) {
              requestLaneIdx := 0.U
              requestQuadIdx := requestQuadIdx + 1.U
            }.otherwise {
              requestLaneIdx := requestLaneIdx + 1.U
            }
          }
        }
      }
    }
    is(sLaunch) {
      io.kernelLaunch.kernelPc := slotShaderPc(execSlot)
      io.kernelLaunch.kernargAddress := slotKernarg(execSlot)
      io.kernelLaunch.localX := execCount
      when(io.kernelLaunch.ready) { state := sRun }
    }
    is(sRun) {
      when(io.kernelCompletion.valid) {
        index := 0.U
        requestQuadIdx := 0.U
        requestLaneIdx := 0.U
        wordPending := false.B
        state := sRead
      }
    }
    is(sRead) {
      when(!wordPending && !wordReqValid && bridge.io.in.ready) {
        wordReqReg := wordBits
        wordReqValid := true.B
      }
      when(wordReqValid && bridge.io.in.fire) { wordReqValid := false.B; wordPending := true.B }
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
            execCovered
          field := 0.U
          when(index === execCount - 1.U) {
            index := 0.U
            requestQuadIdx := 0.U
            requestLaneIdx := 0.U
            state := sEmit
          }.otherwise {
            index := index + 1.U
            when(requestLaneIdx === 3.U) {
              requestLaneIdx := 0.U
              requestQuadIdx := requestQuadIdx + 1.U
            }.otherwise {
              requestLaneIdx := requestLaneIdx + 1.U
            }
          }
        }
      }
    }
    is(sEmit) {
      when(!outValid(indexIdx) || io.out.fire) {
        when(index === execCount - 1.U) {
          when(execCarries) { qDone(execEntryIdx) := true.B }
          execCarries := false.B
          slotCount(execSlot) := 0.U
          when(pendingValid) {
            execSlot := pendingSlot
            execCount := slotCount(pendingSlot)
            execCarries := pendingCarries
            execEntryIdx := pendingEntryIdx
            slotCount(pendingSlot) := 0.U
            pendingValid := false.B
            prodSlot := execSlot
            index := 0.U
            requestQuadIdx := 0.U
            requestLaneIdx := 0.U
            field := 0.U
            wordPending := false.B
            state := sWrite
          }.otherwise {
            state := sIdle
          }
        }.otherwise {
          index := index + 1.U
        }
      }
    }
  }

  // The staging FSM (kernarg write/read phases) and the sampler never have
  // requests in flight at the same time (staging completes before the kernel
  // launches; sampling happens while the kernel runs), so a phase-selected
  // mux over the single line-memory port is race-free.  The producer never
  // touches the word port: its fragments stage into registers and stream
  // through sWrite only after the slot is swapped in.
  private val stagingActive = state === sWrite || state === sRead
  // Register the wide line request at the block boundary.  Besides providing
  // one entry of elastic backpressure, this prevents the staging/texture mux
  // and its 512-bit write-data cone from becoming a top-level output path.
  private val wordMemReqPipe = Module(
    new Queue(new ComputeMemoryRequest(config), 1, pipe = false, flow = false))
  wordMemReqPipe.io.enq.valid := Mux(stagingActive, bridge.io.memoryRequest.valid,
    texBridge.io.memoryRequest.valid)
  wordMemReqPipe.io.enq.bits := Mux(stagingActive, bridge.io.memoryRequest.bits,
    texBridge.io.memoryRequest.bits)
  bridge.io.memoryRequest.ready := wordMemReqPipe.io.enq.ready && stagingActive
  texBridge.io.memoryRequest.ready := wordMemReqPipe.io.enq.ready && !stagingActive
  io.wordMemReq <> wordMemReqPipe.io.deq
  io.wordMemResp.ready := Mux(stagingActive, bridge.io.memoryResponse.ready,
    texBridge.io.memoryResponse.ready)
  bridge.io.memoryResponse.valid := io.wordMemResp.valid && stagingActive
  bridge.io.memoryResponse.bits := io.wordMemResp.bits
  texBridge.io.memoryResponse.valid := io.wordMemResp.valid && !stagingActive
  texBridge.io.memoryResponse.bits := io.wordMemResp.bits
}
