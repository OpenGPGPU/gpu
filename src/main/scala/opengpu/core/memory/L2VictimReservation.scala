package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

class L2VictimReservationRequest(
  config: GpuConfig,
  val entries: Int,
  val sets: Int,
  val ways: Int
) extends Bundle {
  val entryId = UInt(math.max(1, log2Ceil(entries)).W)
  val set = UInt(math.max(1, log2Ceil(sets)).W)
  val way = UInt(math.max(1, log2Ceil(ways)).W)
  val lineAddress = UInt(config.xLen.W)
}

class L2VictimReservationQuery(
  val sets: Int,
  val ways: Int
) extends Bundle {
  val set = UInt(math.max(1, log2Ceil(sets)).W)
  val way = UInt(math.max(1, log2Ceil(ways)).W)
}

/** Prevents independent MSHRs from selecting the same physical cache slot.
  * Reservations are indexed by MSHR entry so refill completion can release
  * the exact slot without another associative lookup.
  */
class L2VictimReservationTable(
  config: GpuConfig = GpuConfig(),
  entries: Int = 4,
  sets: Int = 64,
  ways: Int = 4
) extends Module {
  require(entries > 0 && isPow2(entries))
  require(sets > 1 && isPow2(sets))
  require(ways > 0 && isPow2(ways))
  private val entryWidth = math.max(1, log2Ceil(entries))
  private val setWidth = math.max(1, log2Ceil(sets))
  private val wayWidth = math.max(1, log2Ceil(ways))

  val io = IO(new Bundle {
    val reserve = Flipped(Decoupled(new L2VictimReservationRequest(
      config, entries, sets, ways)))
    val release = Flipped(Valid(UInt(entryWidth.W)))
    val query = Input(new L2VictimReservationQuery(sets, ways))
    val queryConflict = Output(Bool())
    val queryOwner = Output(UInt(entryWidth.W))
    val validEntries = Output(UInt(entries.W))
  })

  private val valid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val reservedSet = Reg(Vec(entries, UInt(setWidth.W)))
  private val reservedWay = Reg(Vec(entries, UInt(wayWidth.W)))
  private val reservedLine = Reg(Vec(entries, UInt(config.xLen.W)))

  private val reserveConflictByEntry = VecInit((0 until entries).map { entry =>
    valid(entry) && reservedSet(entry) === io.reserve.bits.set &&
      reservedWay(entry) === io.reserve.bits.way
  })
  private val reserveEntryFree = !valid(io.reserve.bits.entryId)
  io.reserve.ready := reserveEntryFree &&
    !reserveConflictByEntry.asUInt.orR

  when(io.reserve.fire) {
    valid(io.reserve.bits.entryId) := true.B
    reservedSet(io.reserve.bits.entryId) := io.reserve.bits.set
    reservedWay(io.reserve.bits.entryId) := io.reserve.bits.way
    reservedLine(io.reserve.bits.entryId) := io.reserve.bits.lineAddress
  }

  when(io.release.valid) {
    assert(valid(io.release.bits),
      "victim reservation release must identify a valid entry")
    valid(io.release.bits) := false.B
  }

  private val queryMatch = VecInit((0 until entries).map { entry =>
    valid(entry) && reservedSet(entry) === io.query.set &&
      reservedWay(entry) === io.query.way
  })
  io.queryConflict := queryMatch.asUInt.orR
  io.queryOwner := PriorityEncoder(queryMatch)
  io.validEntries := valid.asUInt

  when(io.reserve.valid && valid(io.reserve.bits.entryId)) {
    assert(reservedLine(io.reserve.bits.entryId) ===
      io.reserve.bits.lineAddress,
      "one MSHR entry cannot reserve two different cache lines")
  }
}
