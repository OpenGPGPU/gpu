package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy, GpuConfig}

/** A graphics-client translation stage: a small TLB plus the shared Sv32 walker.
  *
  * The attached graphics client (texture sampling, and graphics command or
  * framebuffer traffic when wired through it) issues cache-line requests at
  * virtual addresses; this block translates them through the same page tables
  * as the CU MMU and attaches the page's cache policy, so a driver can map a
  * texture or vertex buffer uncached.  Page-table words are fetched as narrow
  * uncached reads on `pageWalk`; the caller routes their responses back on
  * `pageWalkResp` and keeps them clear of client traffic by using the reserved
  * transaction IDs.
  *
  * TLB entries are ASID-tagged: a global (G) page hits any address space, and a
  * private page only hits under the ASID that filled it, so a coarse VM switch
  * needs no flush here and contexts cannot resolve one another's mappings.
  * `flush` honours the same full/ASID/VPN scope as `VectorTlb`, leaving the
  * other entries warm across a scoped shootdown.
  *
  * Hit and translation-disabled results land in a registered, non-flowing
  * pending queue of depth `maxOutstanding`.  That keeps `in.ready` free of
  * `out.ready` (no combo through the shared mem-request arbiter) while still
  * accepting further TLB hits when the downstream port backs up.  Misses still
  * serialize through the single page-table walker; a walk result pushes the
  * same queue once the PTE returns.
  */
class GraphicsAddressTranslator(
  config: GpuConfig = GpuConfig(),
  entries: Int = 16,
  val lineBytes: Int = 64,
  val maxOutstanding: Int = 8,
  /** Soft cap on the registered hit/disabled pending queue.  The effective
    * depth is `min(pendingDepth, maxOutstanding)` so unit tests with a
    * narrow ID space still elaborate, while host clients with large
    * transaction spaces do not flood the shared request arbiter. */
  pendingDepth: Int = 8,
  /** When true, keep the client's requested cache policy instead of the
    * translated page's policy.  The command port sets an uncached policy for
    * coherence and must not have it replaced by the page tables. */
  preserveCachePolicy: Boolean = false
) extends Module {
  require(entries > 0 && isPow2(entries))
  require(isPow2(lineBytes))
  require(maxOutstanding > 0)
  require(pendingDepth > 0)
  private val queueDepth = math.min(pendingDepth, maxOutstanding)
  private val vpnWidth = config.xLen - 12
  private val entryWidth = math.max(1, log2Ceil(entries))

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(
      new ComputeMemoryRequest(config, lineBytes, maxOutstanding)))
    val out = Decoupled(
      new ComputeMemoryRequest(config, lineBytes, maxOutstanding))
    /** Translation failures complete locally; never issue a physical access. */
    val faultResponse = Decoupled(
      new ComputeMemoryResponse(lineBytes, maxOutstanding))
    /** Narrow uncached PTE read; the caller returns the word on `pageWalkResp`
      * and must reserve the transaction IDs used here. */
    val pageWalk = Decoupled(
      new ComputeMemoryRequest(config, lineBytes, maxOutstanding))
    val pageWalkResp = Flipped(Decoupled(
      new ComputeMemoryResponse(lineBytes, maxOutstanding)))
    /** Sv32 satp: bit 31 enables translation, bits 19:0 are the root PPN. */
    val satp = Input(UInt(32.W))
    /** Scoped shootdown: clear a full range, one ASID or one VPN. Both scope
      * bits clear is a full flush, matching the CU TLB contract. */
    val flush = Flipped(Valid(new VectorTlbFlush(config)))
    val miss = Output(Bool())
    val walkActive = Output(Bool())
    /** Addresses presented to `pageWalk`/`pageWalkResp`. */
    val pageWalkTransactionId = Input(
      UInt(math.max(1, log2Ceil(maxOutstanding)).W))
  })

  private object State extends ChiselEnum {
    val idle, walkRequest, walkResponse, fault = Value
  }

  private val walker = Module(new Sv32PageTableWalker(config))
  private val pending = Module(new Queue(
    new ComputeMemoryRequest(config, lineBytes, maxOutstanding),
    entries = queueDepth, pipe = false, flow = false))
  private val valid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val vpn = Reg(Vec(entries, UInt(vpnWidth.W)))
  private val ppn = Reg(Vec(entries, UInt(vpnWidth.W)))
  private val policy = Reg(Vec(entries, UInt(CachePolicy.width.W)))
  private val readable = Reg(Vec(entries, Bool()))
  private val writable = Reg(Vec(entries, Bool()))
  private val global = Reg(Vec(entries, Bool()))
  private val asid = Reg(Vec(entries, UInt(9.W)))
  private val replacement = RegInit(0.U(entryWidth.W))
  private val state = RegInit(State.idle)
  private val request = Reg(new ComputeMemoryRequest(config, lineBytes,
                                                     maxOutstanding))

  // Tag each entry with the ASID under which it was filled (Sv32 satp bits
  // [30:22]).  An entry filled from a global (G) PTE hits any address space;
  // a private entry only hits its own ASID, so a VM switch needs no graphics
  // flush and one context can never resolve another's private mapping.
  private val currentAsid = io.satp(30, 22)
  private val translationEnabled = io.satp(31)

  private def hitVec(probeVpn: UInt): Vec[Bool] = VecInit((0 until entries).map {
    entry => valid(entry) && (global(entry) || asid(entry) === currentAsid) &&
      vpn(entry) === probeVpn
  })

  private val acceptVpn = io.in.bits.address(config.xLen - 1, 12)
  private val acceptHitByEntry = hitVec(acceptVpn)
  private val acceptHit = acceptHitByEntry.asUInt.orR
  private val acceptHitPpn = Mux1H(acceptHitByEntry, ppn)
  private val acceptHitPolicy = Mux1H(acceptHitByEntry, policy)
  private val acceptHitPermission = Mux(io.in.bits.isWrite,
    Mux1H(acceptHitByEntry, writable), Mux1H(acceptHitByEntry, readable))

  private val requestVpn = request.address(config.xLen - 1, 12)

  private val walking =
    state === State.walkRequest || state === State.walkResponse
  // Space in the pending queue is required before accept so a later walk
  // result can always push without a second buffer.  `in.ready` does not look
  // at `out.ready`, so the shared request arbiter stays free of this cone.
  io.in.ready := state === State.idle && pending.io.enq.ready

  private def fillTranslated(
    src: ComputeMemoryRequest,
    physical: UInt,
    cachePolicy: UInt
  ): ComputeMemoryRequest = {
    val next = Wire(new ComputeMemoryRequest(config, lineBytes, maxOutstanding))
    next := src
    next.address := physical
    next.cachePolicy := cachePolicy
    next
  }

  private val disabledPolicy =
    if (preserveCachePolicy) io.in.bits.cachePolicy else CachePolicy.cached
  private val hitPolicyOut =
    if (preserveCachePolicy) io.in.bits.cachePolicy else acceptHitPolicy

  walker.io.rootPpn := io.satp(19, 0)
  walker.io.request.valid := state === State.walkRequest
  walker.io.request.bits.virtualPageNumber := requestVpn
  walker.io.request.bits.isStore := request.isWrite
  walker.io.request.bits.isInstruction := false.B
  walker.io.response.ready := state === State.walkResponse

  // The walker's PTE reads are narrow and uncached so a CPU write to the page
  // table is always observed.
  io.pageWalk.valid := walker.io.memoryRequest.valid
  io.pageWalk.bits := 0.U.asTypeOf(io.pageWalk.bits)
  io.pageWalk.bits.address := walker.io.memoryRequest.bits.address
  io.pageWalk.bits.sizeLog2 := 2.U
  io.pageWalk.bits.cachePolicy := CachePolicy.uncached
  io.pageWalk.bits.isWrite := false.B
  io.pageWalk.bits.transactionId := io.pageWalkTransactionId
  walker.io.memoryRequest.ready := io.pageWalk.ready
  walker.io.memoryResponse.valid := io.pageWalkResp.valid
  walker.io.memoryResponse.bits.pte := io.pageWalkResp.bits.readData(31, 0)
  walker.io.memoryResponse.bits.fault := io.pageWalkResp.bits.fault
  io.pageWalkResp.ready := walker.io.memoryResponse.ready

  io.miss := io.in.fire && translationEnabled && !acceptHit
  io.walkActive := walking
  io.out <> pending.io.deq
  io.faultResponse.valid := state === State.fault
  io.faultResponse.bits := 0.U.asTypeOf(io.faultResponse.bits)
  io.faultResponse.bits.fault := true.B
  io.faultResponse.bits.transactionId := request.transactionId

  private val acceptPush = Wire(Bool())
  private val acceptBits = Wire(new ComputeMemoryRequest(config, lineBytes,
                                                         maxOutstanding))
  private val walkPush = Wire(Bool())
  private val walkBits = Wire(new ComputeMemoryRequest(config, lineBytes,
                                                       maxOutstanding))
  acceptPush := false.B
  acceptBits := 0.U.asTypeOf(acceptBits)
  walkPush := false.B
  walkBits := 0.U.asTypeOf(walkBits)
  pending.io.enq.valid := acceptPush || walkPush
  pending.io.enq.bits := Mux(walkPush, walkBits, acceptBits)

  when(state === State.fault && io.faultResponse.fire) {
    state := State.idle
  }

  when(io.in.fire) {
    request := io.in.bits
    when(!translationEnabled) {
      acceptPush := true.B
      acceptBits := fillTranslated(io.in.bits, io.in.bits.address, disabledPolicy)
    }.elsewhen(acceptHit) {
      when(acceptHitPermission) {
        acceptPush := true.B
        acceptBits := fillTranslated(io.in.bits,
          Cat(acceptHitPpn, io.in.bits.address(11, 0)), hitPolicyOut)
      }.otherwise {
        state := State.fault
      }
    }.otherwise {
      state := State.walkRequest
    }
  }

  when(walker.io.request.fire) {
    state := State.walkResponse
  }

  when(walker.io.response.fire) {
    when(walker.io.response.bits.fault) {
      state := State.fault
    }.otherwise {
      valid(replacement) := true.B
      vpn(replacement) := requestVpn
      ppn(replacement) := walker.io.response.bits.physicalPageNumber
      policy(replacement) := walker.io.response.bits.cachePolicy
      readable(replacement) := walker.io.response.bits.readable
      writable(replacement) := walker.io.response.bits.writable
      global(replacement) := walker.io.response.bits.global
      asid(replacement) := currentAsid
      replacement := Mux(replacement === (entries - 1).U, 0.U,
                         replacement + 1.U)
      val walkPolicy =
        if (preserveCachePolicy) request.cachePolicy
        else walker.io.response.bits.cachePolicy
      walkPush := true.B
      walkBits := fillTranslated(request,
        Cat(walker.io.response.bits.physicalPageNumber, request.address(11, 0)),
        walkPolicy)
      state := State.idle
    }
  }

  // Accept a scoped shootdown only while idle with no translated requests
  // still queued, so a lookup or walk cannot refill an invalidated mapping.
  // A global entry is not owned by an ASID, so an ASID-scoped flush leaves
  // globals warm; a VPN-scoped flush drops only the one mapping. Both scope
  // bits clear is a full flush.
  when(io.flush.valid) {
    assert(state === State.idle && !pending.io.deq.valid,
      "graphics TLB flush requires an idle port and empty pending queue")
    for (entry <- 0 until entries) {
      val vpnMatches =
        !io.flush.bits.virtualPageNumberValid ||
          vpn(entry) === io.flush.bits.virtualPageNumber
      val asidMatches =
        !io.flush.bits.asidValid ||
          (!global(entry) && asid(entry) === io.flush.bits.asid)
      when(vpnMatches && asidMatches) {
        valid(entry) := false.B
      }
    }
  }
}
