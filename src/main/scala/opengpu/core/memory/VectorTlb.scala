package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

class VectorPageWalkRequest(config: GpuConfig) extends Bundle {
  val virtualPageNumber = UInt((config.xLen - 12).W)
  val isStore = Bool()
  val isInstruction = Bool()
}

class VectorPageWalkResponse(config: GpuConfig) extends Bundle {
  val physicalPageNumber = UInt((config.xLen - 12).W)
  val readable = Bool()
  val writable = Bool()
  val executable = Bool()
  val global = Bool()
  val fault = Bool()
}

class VectorTlbFlush(config: GpuConfig) extends Bundle {
  val virtualPageNumberValid = Bool()
  val virtualPageNumber = UInt((config.xLen - 12).W)
  val asidValid = Bool()
  val asid = UInt(9.W)
}

/** Fully-associative blocking data TLB and PTW bridge.
  *
  * A cache-line request cannot cross a 4 KiB page, so one translation covers
  * the complete coalesced transaction. Permission or PTW faults return through
  * the ordinary cache-response channel without touching DCache.
  */
class VectorTlb(
  config: GpuConfig = GpuConfig(),
  entries: Int = 16,
  lineBytes: Int = 64
) extends Module {
  require(entries > 0, "TLB must contain at least one entry")
  require(isPow2(lineBytes), "cache line size must be a power of two")
  private val vpnWidth = config.xLen - 12
  private val entryWidth = math.max(1, log2Ceil(entries))

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VectorCacheLineRequest(config, lineBytes)))
    val translationEnabled = Input(Bool())
    val asid = Input(UInt(9.W))
    val flush = Flipped(Valid(new VectorTlbFlush(config)))
    val out = Decoupled(new VectorCacheLineResponse(lineBytes))
    val physicalRequest =
      Decoupled(new VectorCacheLineRequest(config, lineBytes))
    val physicalResponse =
      Flipped(Decoupled(new VectorCacheLineResponse(lineBytes)))
    val pageWalkRequest = Decoupled(new VectorPageWalkRequest(config))
    val pageWalkResponse =
      Flipped(Decoupled(new VectorPageWalkResponse(config)))
  })

  private val valid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val vpn = Reg(Vec(entries, UInt(vpnWidth.W)))
  private val ppn = Reg(Vec(entries, UInt(vpnWidth.W)))
  private val readable = Reg(Vec(entries, Bool()))
  private val writable = Reg(Vec(entries, Bool()))
  private val entryAsid = Reg(Vec(entries, UInt(9.W)))
  private val global = Reg(Vec(entries, Bool()))
  private val replacement = RegInit(0.U(entryWidth.W))
  private val request = Reg(new VectorCacheLineRequest(config, lineBytes))
  private val translatedAddress = Reg(UInt(config.xLen.W))
  private val response = Reg(new VectorCacheLineResponse(lineBytes))

  private object State extends ChiselEnum {
    val idle, lookup, walkRequest, walkResponse, forward, cacheResponse,
      faultResponse = Value
  }
  private val state = RegInit(State.idle)
  private val requestVpn = request.lineAddress(config.xLen - 1, 12)
  private val hitByEntry = VecInit((0 until entries).map { entry =>
    valid(entry) && vpn(entry) === requestVpn &&
      (global(entry) || entryAsid(entry) === io.asid)
  })
  private val hit = hitByEntry.asUInt.orR
  private val hitEntry = PriorityEncoder(hitByEntry)
  private val hitReadable = Mux1H(hitByEntry, readable)
  private val hitWritable = Mux1H(hitByEntry, writable)
  private val hitPpn = Mux1H(hitByEntry, ppn)
  private val permissionOkay =
    Mux(request.isStore, hitWritable, hitReadable)

  io.in.ready := state === State.idle
  io.out.valid := state === State.faultResponse ||
    (state === State.cacheResponse && io.physicalResponse.valid)
  io.out.bits := Mux(
    state === State.faultResponse,
    response,
    io.physicalResponse.bits
  )
  io.physicalRequest.valid := state === State.forward
  io.physicalRequest.bits := request
  io.physicalRequest.bits.lineAddress := translatedAddress
  io.physicalResponse.ready :=
    state === State.cacheResponse && io.out.ready
  io.pageWalkRequest.valid := state === State.walkRequest
  io.pageWalkRequest.bits.virtualPageNumber := requestVpn
  io.pageWalkRequest.bits.isStore := request.isStore
  io.pageWalkRequest.bits.isInstruction := false.B
  io.pageWalkResponse.ready := state === State.walkResponse

  when(io.in.fire) {
    request := io.in.bits
    state := State.lookup
  }

  when(state === State.lookup) {
    when(!io.translationEnabled) {
      translatedAddress := request.lineAddress
      state := State.forward
    }.elsewhen(hit) {
      when(permissionOkay) {
        translatedAddress := Cat(hitPpn, request.lineAddress(11, 0))
        state := State.forward
      }.otherwise {
        response.readData := 0.U
        response.fault := true.B
        response.pageFault := true.B
        state := State.faultResponse
      }
    }.otherwise {
      state := State.walkRequest
    }
  }

  when(io.pageWalkRequest.fire) {
    state := State.walkResponse
  }

  when(io.pageWalkResponse.fire) {
    val walkPermission = Mux(
      request.isStore,
      io.pageWalkResponse.bits.writable,
      io.pageWalkResponse.bits.readable
    )
    when(io.pageWalkResponse.bits.fault || !walkPermission) {
      response.readData := 0.U
      response.fault := true.B
      response.pageFault := true.B
      state := State.faultResponse
    }.otherwise {
      valid(replacement) := true.B
      vpn(replacement) := requestVpn
      ppn(replacement) := io.pageWalkResponse.bits.physicalPageNumber
      readable(replacement) := io.pageWalkResponse.bits.readable
      writable(replacement) := io.pageWalkResponse.bits.writable
      entryAsid(replacement) := io.asid
      global(replacement) := io.pageWalkResponse.bits.global
      replacement := Mux(
        replacement === (entries - 1).U,
        0.U,
        replacement + 1.U
      )
      translatedAddress := Cat(
        io.pageWalkResponse.bits.physicalPageNumber,
        request.lineAddress(11, 0)
      )
      state := State.forward
    }
  }

  when(io.physicalRequest.fire) {
    state := State.cacheResponse
  }
  when(state === State.cacheResponse && io.physicalResponse.fire) {
    state := State.idle
  }
  when(state === State.faultResponse && io.out.fire) {
    state := State.idle
  }

  // Flush is accepted only while no lookup/walk can refill an old mapping.
  when(io.flush.valid) {
    assert(state === State.idle, "TLB flush requires an idle translation port")
    for (entry <- 0 until entries) {
      val vpnMatches =
        !io.flush.bits.virtualPageNumberValid ||
          vpn(entry) === io.flush.bits.virtualPageNumber
      val asidMatches =
        !io.flush.bits.asidValid ||
          (!global(entry) && entryAsid(entry) === io.flush.bits.asid)
      when(vpnMatches && asidMatches) {
        valid(entry) := false.B
      }
    }
  }
}
