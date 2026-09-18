package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy, GpuConfig}

/** A graphics-client translation stage: a small TLB plus the shared Sv32 walker.
  *
  * The attached graphics client (texture sampling, and graphics command or
  * framebuffer traffic when wired through it) issues cache-line requests at
  * virtual addresses; this block translates them through the same page tables
  * as the CU MMUs and attaches the page's cache policy, so a driver can map a
  * texture or vertex buffer uncached.  Page-table words are fetched as narrow
  * uncached reads on `pageWalk`; the caller routes their responses back on
  * `pageWalkResp` and keeps them clear of client traffic by using the reserved
  * transaction IDs.
  *
  * TLB entries are ASID-tagged: a global (G) page hits any address space, and a
  * private page only hits under the ASID that filled it, so a coarse VM switch
  * needs no flush here and contexts cannot resolve one another's mappings.
  */
class GraphicsAddressTranslator(
  config: GpuConfig = GpuConfig(),
  entries: Int = 16,
  val lineBytes: Int = 64,
  val maxOutstanding: Int = 8,
  /** When true, keep the client's requested cache policy instead of the
    * translated page's policy.  The command port sets an uncached policy for
    * coherence and must not have it replaced by the page tables. */
  preserveCachePolicy: Boolean = false
) extends Module {
  require(entries > 0 && isPow2(entries))
  require(isPow2(lineBytes))
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
    val flush = Input(Bool())
    /** Addresses presented to `pageWalk`/`pageWalkResp`. */
    val pageWalkTransactionId = Input(
      UInt(math.max(1, log2Ceil(maxOutstanding)).W))
  })

  private object State extends ChiselEnum {
    val idle, lookup, walkRequest, walkResponse, respond, fault = Value
  }

  private val walker = Module(new Sv32PageTableWalker(config))
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
  private val translated = Reg(new ComputeMemoryRequest(config, lineBytes,
                                                        maxOutstanding))

  private val requestVpn = request.address(config.xLen - 1, 12)
  // Tag each entry with the ASID under which it was filled (Sv32 satp bits
  // [30:22]).  An entry filled from a global (G) PTE hits any address space;
  // a private entry only hits its own ASID, so a VM switch needs no graphics
  // flush and one context can never resolve another's private mapping.
  private val currentAsid = io.satp(30, 22)
  private val hitByEntry = VecInit((0 until entries).map { entry =>
    valid(entry) && (global(entry) || asid(entry) === currentAsid) &&
      vpn(entry) === requestVpn
  })
  private val hit = hitByEntry.asUInt.orR
  private val hitPpn = Mux1H(hitByEntry, ppn)
  private val hitPolicy = Mux1H(hitByEntry, policy)
  private val hitPermission = Mux(request.isWrite,
    Mux1H(hitByEntry, writable), Mux1H(hitByEntry, readable))
  private val translationEnabled = io.satp(31)

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

  io.in.ready := state === State.idle
  io.out.valid := state === State.respond
  io.out.bits := translated
  io.faultResponse.valid := state === State.fault
  io.faultResponse.bits := 0.U.asTypeOf(io.faultResponse.bits)
  io.faultResponse.bits.fault := true.B
  io.faultResponse.bits.transactionId := request.transactionId

  when(io.in.fire) {
    request := io.in.bits
    state := State.lookup
  }

  when(state === State.lookup) {
    when(!translationEnabled) {
      translated := request
      translated.cachePolicy :=
        (if (preserveCachePolicy) request.cachePolicy
         else CachePolicy.cached)
      state := State.respond
    }.elsewhen(hit) {
      translated := request
      translated.address := Cat(hitPpn, request.address(11, 0))
      translated.cachePolicy :=
        (if (preserveCachePolicy) request.cachePolicy else hitPolicy)
      state := Mux(hitPermission, State.respond, State.fault)
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
      translated := request
      translated.address := Cat(walker.io.response.bits.physicalPageNumber,
                                request.address(11, 0))
      translated.cachePolicy :=
        (if (preserveCachePolicy) request.cachePolicy
         else walker.io.response.bits.cachePolicy)
      state := State.respond
    }
  }

  when(state === State.respond && io.out.fire) {
    state := State.idle
  }
  when(io.faultResponse.fire) { state := State.idle }

  when(io.flush) {
    assert(state === State.idle, "graphics TLB flush requires an idle port")
    for (entry <- 0 until entries)
      valid(entry) := false.B
  }
}
