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

  bridge.io.in.valid := wordValid
  bridge.io.in.bits := wordBits
  bridge.io.out.ready := true.B

  // ---------------------------------------------------------------------
  // Ping-pong staging slots.  Slot registers are indexed dynamically by
  // prodSlot (producer accumulation) and execSlot (consumer execution).
  // ---------------------------------------------------------------------
  private val fragX = Reg(Vec(2, Vec(batchCap, SInt(gfxConfig.coordWidth.W))))
  private val fragY = Reg(Vec(2, Vec(batchCap, SInt(gfxConfig.coordWidth.W))))
  private val fragDepth = Reg(Vec(2, Vec(batchCap, SInt(32.W))))
  private val packedColor = Reg(Vec(2, Vec(batchCap, UInt(32.W))))
  private val fragU = Reg(Vec(2, Vec(batchCap, UInt(32.W))))
  private val fragV = Reg(Vec(2, Vec(batchCap, UInt(32.W))))
  private val fragCovered = Reg(Vec(2, Vec(batchCap, Bool())))
  private val fragE0 = Reg(Vec(2, Vec(batchCap, SInt(64.W))))
  private val fragE1 = Reg(Vec(2, Vec(batchCap, SInt(64.W))))
  private val fragE2 = Reg(Vec(2, Vec(batchCap, SInt(64.W))))
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
  // Select the lane inside each statically addressed slot before muxing the
  // scalar values.  Muxing the outer Vec first creates a packed-array mux
  // that firtool cannot lower with ARTI's disallowPackedArrays setting.
  private def execLane[T <: Data](slots: Vec[Vec[T]]): T =
    Mux(execSlot.asBool, slots(1)(indexIdx), slots(0)(indexIdx))
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
  private val prodCountIdx = prodCount(countWidth - 2, 0)
  // Count including a quad accepted this cycle (commit-by-fullness takes
  // the batch with the firing quad; a flush commit has no fire).
  private val prodCountNext = prodCount + Mux(io.fragIn.fire, 4.U, 0.U)

  private val pendingValid = RegInit(false.B)
  private val pendingSlot = Reg(UInt(1.W))
  private val pendingCarries = RegInit(false.B)
  private val pendingEntryIdx = Reg(UInt(1.W))

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
  // Each fire stages all four lanes of the presented quad at the next
  // 4-aligned index range.
  when(io.fragIn.fire) {
    for (k <- 0 until 4) {
      // Fires only happen with prodCount <= batchCap-4, so the sum stays in
      // range; the explicit truncation keeps the dynamic index at Vec width.
      val laneIdx = (prodCountIdx + k.U)(countWidth - 2, 0)
      fragX(prodSlot)(laneIdx) := io.fragIn.bits.lanes(k).x
      fragY(prodSlot)(laneIdx) := io.fragIn.bits.lanes(k).y
      fragDepth(prodSlot)(laneIdx) := io.fragIn.bits.lanes(k).depth
      packedColor(prodSlot)(laneIdx) := Cat(
        io.fragIn.bits.lanes(k).color.r,
        io.fragIn.bits.lanes(k).color.g,
        io.fragIn.bits.lanes(k).color.b,
        0xff.U(8.W))
      fragU(prodSlot)(laneIdx) := io.fragUv(k).u
      fragV(prodSlot)(laneIdx) := io.fragUv(k).v
      fragCovered(prodSlot)(laneIdx) := io.fragIn.bits.lanes(k).covered
      fragE0(prodSlot)(laneIdx) := io.fragIn.bits.lanes(k).e0
      fragE1(prodSlot)(laneIdx) := io.fragIn.bits.lanes(k).e1
      fragE2(prodSlot)(laneIdx) := io.fragIn.bits.lanes(k).e2
    }
    when(prodCount === 0.U) {
      slotShaderPc(prodSlot) := io.shaderPc
      slotKernarg(prodSlot) := bankedKernarg(prodSlot)
      slotTexBase(prodSlot) := io.texBase
      slotTexWidth(prodSlot) := io.texWidth
      slotTexHeight(prodSlot) := io.texHeight
      slotTexWrapClamp(prodSlot) := io.texWrapClamp
      slotTexMaxLevel(prodSlot) := io.texMaxLevel
      slotTexLodBias(prodSlot) := io.texLodBias
      slotTexMinLevel(prodSlot) := io.texMinLevel
    }.otherwise {
      assert(
        io.shaderPc === slotShaderPc(prodSlot) &&
          bankedKernarg(prodSlot) === slotKernarg(prodSlot),
        "a fragment batch must not mix draw descriptors")
    }
    when(!(commitAny && state === sIdle)) {
      slotCount(prodSlot) := prodCountNext
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
  wordValid := (state === sWrite || state === sRead) && !wordPending
  wordBits.write := state === sWrite
  private val writeSlice = MuxLookup(field, 8.U)(Seq(
    0.U -> 0.U, 1.U -> 1.U, 2.U -> 2.U, 3.U -> 3.U,
    4.U -> 4.U, 5.U -> 5.U, 6.U -> 7.U))
  wordBits.addr := Mux(
    state === sWrite,
    slotKernarg(execSlot) + writeSlice * arrayStride.U + (index << 2),
    slotKernarg(execSlot) + ((6.U + field) * arrayStride.U) + (index << 2)
  )
  wordBits.data := MuxLookup(field, execLane(fragCovered).asUInt)(
    Seq(
      0.U -> execLane(fragX).pad(32).asUInt,
      1.U -> execLane(fragY).pad(32).asUInt,
      2.U -> execLane(fragDepth).asUInt,
      3.U -> execLane(packedColor),
      4.U -> execLane(fragU),
      5.U -> execLane(fragV),
      6.U -> execLane(fragDepth).asUInt
    )
  )

  io.kernelLaunch.valid := state === sLaunch

  io.out.valid := state === sEmit && outValid(indexIdx)
  io.out.bits.x := execLane(fragX)
  io.out.bits.y := execLane(fragY)
  io.out.bits.depth := outDepth(indexIdx)
  io.out.bits.e0 := execLane(fragE0)
  io.out.bits.e1 := execLane(fragE1)
  io.out.bits.e2 := execLane(fragE2)
  io.out.bits.covered := execLane(fragCovered)
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
      when(!wordPending && bridge.io.in.fire) { wordPending := true.B }
      when(wordPending && bridge.io.out.fire) {
        wordPending := false.B
        when(field =/= 7.U) {
          field := field + 1.U
        }.otherwise {
          field := 0.U
          when(index === execCount - 1.U) {
            index := 0.U
            state := sLaunch
          }.otherwise {
            index := index + 1.U
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
            execLane(fragCovered)
          field := 0.U
          when(index === execCount - 1.U) {
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
