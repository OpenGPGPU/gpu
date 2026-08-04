package gpu.core.memory

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig

class VectorLowerMemoryRequest(
  config: GpuConfig,
  val lineBytes: Int = 64
) extends Bundle {
  val lineAddress = UInt(config.xLen.W)
  val writeData = UInt((lineBytes * 8).W)
  val byteMask = UInt(lineBytes.W)
  val isWrite = Bool()
}

class VectorLowerMemoryResponse(val lineBytes: Int = 64) extends Bundle {
  val readData = UInt((lineBytes * 8).W)
  val fault = Bool()
}

/** Blocking set-associative vector data cache.
  *
  * Loads allocate on a miss. Stores are write-through and update an existing
  * cached line only after the lower-level write acknowledgment succeeds.
  * Keeping one request outstanding gives precise fault and backpressure
  * semantics while the later MSHR implementation is still absent.
  */
class VectorDataCache(
  config: GpuConfig = GpuConfig(),
  sets: Int = 64,
  ways: Int = 2,
  lineBytes: Int = 64
) extends Module {
  require(isPow2(sets), "DCache set count must be a power of two")
  require(isPow2(ways), "DCache way count must be a power of two")
  require(isPow2(lineBytes), "DCache line size must be a power of two")

  private val offsetWidth = log2Ceil(lineBytes)
  private val indexWidth = log2Ceil(sets)
  private val tagWidth = config.xLen - offsetWidth - indexWidth
  private val lineWidth = lineBytes * 8
  private val wayWidth = math.max(1, log2Ceil(ways))
  require(tagWidth > 0, "address must contain at least one tag bit")

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VectorCacheLineRequest(config, lineBytes)))
    val out = Decoupled(new VectorCacheLineResponse(lineBytes))
    val lowerRequest =
      Decoupled(new VectorLowerMemoryRequest(config, lineBytes))
    val lowerResponse =
      Flipped(Decoupled(new VectorLowerMemoryResponse(lineBytes)))
  })

  private val tags = Seq.fill(ways)(Mem(sets, UInt(tagWidth.W)))
  private val data = Seq.fill(ways)(Mem(sets, UInt(lineWidth.W)))
  private val valid = Seq.fill(ways)(
    RegInit(VecInit(Seq.fill(sets)(false.B)))
  )
  private val nextVictim =
    RegInit(VecInit(Seq.fill(sets)(0.U(wayWidth.W))))
  private val request = Reg(new VectorCacheLineRequest(config, lineBytes))
  private val output = Reg(new VectorCacheLineResponse(lineBytes))

  private object State extends ChiselEnum {
    val idle, lookup, lower, respond = Value
  }
  private val state = RegInit(State.idle)

  private val requestIndex =
    request.lineAddress(offsetWidth + indexWidth - 1, offsetWidth)
  private val requestTag =
    request.lineAddress(config.xLen - 1, offsetWidth + indexWidth)
  private val hitByWay = VecInit((0 until ways).map { way =>
    valid(way)(requestIndex) && tags(way)(requestIndex) === requestTag
  })
  private val cacheHit = hitByWay.asUInt.orR
  private val hitWay = PriorityEncoder(hitByWay)
  private val cachedData = Mux1H(
    hitByWay,
    VecInit(data.map(_(requestIndex)))
  )
  private val invalidByWay = VecInit((0 until ways).map { way =>
    !valid(way)(requestIndex)
  })
  private val hasInvalidWay = invalidByWay.asUInt.orR
  private val invalidWay = PriorityEncoder(invalidByWay)
  private val victimWay = Mux(
    hasInvalidWay,
    invalidWay,
    nextVictim(requestIndex)
  )
  private def followingWay(way: UInt): UInt =
    if (ways == 1) 0.U else (way + 1.U)(wayWidth - 1, 0)

  private val mergedStoreBytes = Wire(Vec(lineBytes, UInt(8.W)))
  private val cachedBytes = cachedData.asTypeOf(Vec(lineBytes, UInt(8.W)))
  private val writeBytes =
    request.writeData.asTypeOf(Vec(lineBytes, UInt(8.W)))
  for (byte <- 0 until lineBytes) {
    mergedStoreBytes(byte) :=
      Mux(request.byteMask(byte), writeBytes(byte), cachedBytes(byte))
  }
  private val mergedStoreData = mergedStoreBytes.asUInt

  io.in.ready := state === State.idle
  io.out.valid := state === State.respond
  io.out.bits := output
  io.lowerRequest.valid := state === State.lower
  io.lowerRequest.bits.lineAddress := request.lineAddress
  io.lowerRequest.bits.writeData := request.writeData
  io.lowerRequest.bits.byteMask := request.byteMask
  io.lowerRequest.bits.isWrite := request.isStore
  io.lowerResponse.ready := state === State.lower

  when(io.in.fire) {
    request := io.in.bits
    state := State.lookup
    assert(
      io.in.bits.lineAddress(offsetWidth - 1, 0) === 0.U,
      "VectorDataCache requires cache-line-aligned requests"
    )
  }

  when(state === State.lookup) {
    when(!request.isStore && cacheHit) {
      output.readData := cachedData
      output.fault := false.B
      output.pageFault := false.B
      nextVictim(requestIndex) := followingWay(hitWay)
      state := State.respond
    }.otherwise {
      state := State.lower
    }
  }

  when(io.lowerResponse.fire) {
    output.fault := io.lowerResponse.bits.fault
    output.pageFault := false.B
    output.readData :=
      Mux(request.isStore, 0.U, io.lowerResponse.bits.readData)
    when(!io.lowerResponse.bits.fault) {
      when(!request.isStore) {
        for (way <- 0 until ways) {
          when(victimWay === way.U) {
            tags(way)(requestIndex) := requestTag
            data(way)(requestIndex) := io.lowerResponse.bits.readData
            valid(way)(requestIndex) := true.B
          }
        }
        nextVictim(requestIndex) := followingWay(victimWay)
      }.elsewhen(cacheHit) {
        for (way <- 0 until ways) {
          when(hitWay === way.U) {
            data(way)(requestIndex) := mergedStoreData
          }
        }
        nextVictim(requestIndex) := followingWay(hitWay)
      }
    }
    state := State.respond
  }

  when(io.out.fire) {
    state := State.idle
  }
}
