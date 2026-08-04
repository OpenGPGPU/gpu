package gpu.core.memory

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.frontend.{InstructionFetchRequest, InstructionFetchResponse}

class InstructionLineRequest(
  config: GpuConfig,
  val lineBytes: Int = 64,
  val missEntries: Int = 4
) extends Bundle {
  val lineAddress = UInt(config.xLen.W)
  val requestId = UInt(math.max(1, log2Ceil(missEntries)).W)
}

class InstructionLineResponse(
  val lineBytes: Int = 64,
  val missEntries: Int = 4
) extends Bundle {
  val readData = UInt((lineBytes * 8).W)
  val fault = Bool()
  val requestId = UInt(math.max(1, log2Ceil(missEntries)).W)
}

/** Non-blocking set-associative instruction cache.
  *
  * Hits can complete while up to `missEntries` independent line refills are
  * outstanding. Refill responses carry an MSHR ID and may return out of order.
  */
class InstructionCache(
  config: GpuConfig = GpuConfig(),
  sets: Int = 64,
  ways: Int = 2,
  lineBytes: Int = 64,
  missEntries: Int = 4
) extends Module {
  require(isPow2(sets) && isPow2(ways) && isPow2(lineBytes))
  require(lineBytes >= 4 && missEntries > 0)
  private val offsetWidth = log2Ceil(lineBytes)
  private val indexWidth = log2Ceil(sets)
  private val tagWidth = config.xLen - offsetWidth - indexWidth
  private val lineWidth = lineBytes * 8
  private val wayWidth = math.max(1, log2Ceil(ways))
  private val missIdWidth = math.max(1, log2Ceil(missEntries))

  val io = IO(new Bundle {
    val fetch = Flipped(Decoupled(new InstructionFetchRequest(config)))
    val response = Decoupled(new InstructionFetchResponse(config))
    val lowerRequest =
      Decoupled(new InstructionLineRequest(config, lineBytes, missEntries))
    val lowerResponse = Flipped(
      Decoupled(new InstructionLineResponse(lineBytes, missEntries)))
    val invalidate = Input(Bool())
  })

  private val tags = Seq.fill(ways)(Mem(sets, UInt(tagWidth.W)))
  private val data = Seq.fill(ways)(Mem(sets, UInt(lineWidth.W)))
  private val valid = Seq.fill(ways)(RegInit(VecInit(Seq.fill(sets)(false.B))))
  private val nextVictim =
    RegInit(VecInit(Seq.fill(sets)(0.U(wayWidth.W))))
  private val epoch = RegInit(false.B)

  private val missValid = RegInit(VecInit(Seq.fill(missEntries)(false.B)))
  private val missSent = RegInit(VecInit(Seq.fill(missEntries)(false.B)))
  private val missRequest = Reg(Vec(missEntries, new InstructionFetchRequest(config)))
  private val missVictim = Reg(Vec(missEntries, UInt(wayWidth.W)))
  private val missEpoch = Reg(Vec(missEntries, Bool()))
  private val freeMisses = ~missValid.asUInt
  private val hasFreeMiss = freeMisses.orR
  private val allocateMiss = PriorityEncoder(freeMisses)

  private def index(address: UInt): UInt =
    address(offsetWidth + indexWidth - 1, offsetWidth)
  private def tag(address: UInt): UInt =
    address(config.xLen - 1, offsetWidth + indexWidth)
  private def wordIndex(address: UInt): UInt =
    if (lineBytes == 4) 0.U else address(offsetWidth - 1, 2)
  private def instruction(line: UInt, address: UInt): UInt =
    line.asTypeOf(Vec(lineBytes / 4, UInt(32.W)))(wordIndex(address))
  private def followingWay(way: UInt): UInt =
    if (ways == 1) 0.U else (way + 1.U)(wayWidth - 1, 0)

  private val fetchIndex = index(io.fetch.bits.pc)
  private val fetchTag = tag(io.fetch.bits.pc)
  private val fetchHitByWay = VecInit((0 until ways).map { way =>
    valid(way)(fetchIndex) && tags(way)(fetchIndex) === fetchTag
  })
  private val fetchHit = fetchHitByWay.asUInt.orR
  private val fetchHitWay = PriorityEncoder(fetchHitByWay)
  private val fetchLine = Mux1H(fetchHitByWay, VecInit(data.map(_(fetchIndex))))
  private val fetchInvalidByWay = VecInit((0 until ways).map(way => !valid(way)(fetchIndex)))
  private val fetchVictim = Mux(
    fetchInvalidByWay.asUInt.orR,
    PriorityEncoder(fetchInvalidByWay),
    nextVictim(fetchIndex)
  )
  private val misaligned = io.fetch.bits.pc(1, 0) =/= 0.U
  private val immediate = misaligned || fetchHit

  private val responseArbiter = Module(
    new RRArbiter(new InstructionFetchResponse(config), 2))
  private val responseQueue = Module(
    new Queue(new InstructionFetchResponse(config), missEntries + 2))
  responseQueue.io.enq <> responseArbiter.io.out
  io.response <> responseQueue.io.deq

  responseArbiter.io.in(0).valid := io.fetch.valid && immediate
  responseArbiter.io.in(0).bits.warpId := io.fetch.bits.warpId
  responseArbiter.io.in(0).bits.instruction :=
    Mux(misaligned, 0.U, instruction(fetchLine, io.fetch.bits.pc))
  responseArbiter.io.in(0).bits.accessFault := misaligned
  io.fetch.ready := Mux(immediate, responseArbiter.io.in(0).ready, hasFreeMiss)

  private val allocate = io.fetch.fire && !immediate
  when(allocate) {
    missValid(allocateMiss) := true.B
    missSent(allocateMiss) := false.B
    missRequest(allocateMiss) := io.fetch.bits
    missVictim(allocateMiss) := fetchVictim
    missEpoch(allocateMiss) := epoch
  }
  when(io.fetch.fire && fetchHit) {
    nextVictim(fetchIndex) := followingWay(fetchHitWay)
  }

  private val unsent = VecInit((0 until missEntries).map(i => missValid(i) && !missSent(i)))
  private val hasUnsent = unsent.asUInt.orR
  private val sendMiss = PriorityEncoder(unsent)
  private val sendRequest = missRequest(sendMiss)
  io.lowerRequest.valid := hasUnsent
  io.lowerRequest.bits.lineAddress := Cat(
    sendRequest.pc(config.xLen - 1, offsetWidth), 0.U(offsetWidth.W))
  io.lowerRequest.bits.requestId := sendMiss
  when(io.lowerRequest.fire) { missSent(sendMiss) := true.B }

  private val responseMissId = io.lowerResponse.bits.requestId
  private val responseIdInRange = responseMissId < missEntries.U
  private val responseIdValid = responseIdInRange && missValid(responseMissId) &&
    missSent(responseMissId)
  private val completedRequest = missRequest(responseMissId)
  responseArbiter.io.in(1).valid := io.lowerResponse.valid && responseIdValid
  responseArbiter.io.in(1).bits.warpId := completedRequest.warpId
  responseArbiter.io.in(1).bits.instruction :=
    instruction(io.lowerResponse.bits.readData, completedRequest.pc)
  responseArbiter.io.in(1).bits.accessFault := io.lowerResponse.bits.fault
  io.lowerResponse.ready := responseIdValid && responseArbiter.io.in(1).ready

  when(io.lowerResponse.fire) {
    val completedIndex = index(completedRequest.pc)
    val completedTag = tag(completedRequest.pc)
    val completedVictim = missVictim(responseMissId)
    when(!io.lowerResponse.bits.fault && missEpoch(responseMissId) === epoch &&
      !io.invalidate) {
      for (way <- 0 until ways) {
        when(completedVictim === way.U) {
          tags(way)(completedIndex) := completedTag
          data(way)(completedIndex) := io.lowerResponse.bits.readData
          valid(way)(completedIndex) := true.B
        }
      }
      nextVictim(completedIndex) := followingWay(completedVictim)
    }
    missValid(responseMissId) := false.B
    missSent(responseMissId) := false.B
  }

  when(io.invalidate) {
    valid.foreach(_.foreach(_ := false.B))
    epoch := ~epoch
  }
  when(io.lowerResponse.valid) {
    assert(responseIdValid, "ICache refill must reference a sent MSHR")
  }
}
