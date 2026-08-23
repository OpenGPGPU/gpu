package gpu.core.backend

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.backend.issue.{
  FpuIssueStage,
  FpuIssuedInstruction,
  FpuMemoryUnit
}
import gpu.core.backend.register.{
  FpuRegisterRead,
  FpuRegisterWrite,
  ScalarRegisterRead,
  ScalarRegisterWrite
}
import gpu.core.backend.scoreboard.RegisterReservation
import gpu.core.execute.control.SimtPath
import gpu.core.execute.fpu.{
  Fp32Request,
  Fp32ExactUnit,
  Fp32FmaLane,
  Fp32Operation,
  FpuFastMapper
}
import gpu.core.frontend.decode.FpuDecodeResponse
import gpu.core.memory.{
  ScalarMemoryFault,
  VectorCacheLineRequest,
  VectorCacheLineResponse
}

class FpuFlags(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val flags = UInt(5.W)
}

/** Mapper output captured with its issue metadata before execution. */
private class FpuMappedEntry(config: GpuConfig, tagWidth: Int) extends Bundle {
  val request = new Fp32Request(tagWidth)
  val supported = Bool()
  val decode = new FpuIssuedInstruction(config)
}

/** Scalar FP32 backend. FMA, sign/min-max/compare/classify/move are native;
  * conversions and FP memory remain explicit outputs. */
class FpuBackend(config: GpuConfig = GpuConfig(), tagWidth: Int = 16)
    extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new FpuDecodeResponse(config)))
    val redirect = Decoupled(new SimtPath(config))
    val committedWriteback = Valid(new FpuRegisterWrite(config))
    val committedFlags = Valid(new FpuFlags(config))
    val committedIntegerWriteback = Valid(new ScalarRegisterWrite(config))
    val initialize = Flipped(Decoupled(new FpuRegisterWrite(config)))
    val unimplemented = Decoupled(new FpuIssuedInstruction(config))
    val scalarReserve = Decoupled(new RegisterReservation(config))
    val scalarWriteback = Decoupled(new ScalarRegisterWrite(config))
    val scalarRead = Output(new ScalarRegisterRead(config))
    val scalarRs1Data = Input(UInt(32.W))
    val frm = Input(Vec(config.warps, UInt(3.W)))
    val fvfRead = Input(new FpuRegisterRead(config))
    val fvfData = Output(UInt(32.W))
    val fpuBusyByWarp = Output(Vec(config.warps, UInt(32.W)))
    val memoryRequest = Decoupled(new VectorCacheLineRequest(config))
    val memoryResponse = Flipped(Decoupled(new VectorCacheLineResponse()))
    val memoryFault = Decoupled(new ScalarMemoryFault(config))
    val rawHazard = Output(Bool())
    val wawHazard = Output(Bool())
    val flush = Input(Bool())
  })

  private val issue = Module(new FpuIssueStage(config))
  private val mapper = Module(new FpuFastMapper(config, tagWidth))
  private val fma = Module(new Fp32FmaLane(tagWidth))
  private val exact = Module(new Fp32ExactUnit(tagWidth))
  private val memory = Module(new FpuMemoryUnit(config))
  private val fmaMetadata = Module(
    new Queue(new FpuIssuedInstruction(config), 8))
  private val exactMetadata = Module(
    new Queue(new FpuIssuedInstruction(config), 8))
  private val mappedQueue = Module(
    new Queue(new FpuMappedEntry(config, tagWidth), 2))

  issue.io.in <> io.in
  mapper.io.in := issue.io.out.bits.decode
  mapper.io.rs1Data := issue.io.out.bits.rs1Data
  mapper.io.rs2Data := issue.io.out.bits.rs2Data
  mapper.io.rs3Data := issue.io.out.bits.rs3Data
  mapper.io.scalarRs1Data := issue.io.out.bits.scalarRs1Data
  mapper.io.frm := io.frm
  io.scalarRead := issue.io.scalarRead
  issue.io.scalarRs1Data := io.scalarRs1Data
  issue.io.fvfRead := io.fvfRead
  io.fvfData := issue.io.fvfData
  io.fpuBusyByWarp := issue.io.busyByWarp

  private val fast = mapper.io.out.supported
  private val isExact =
    mapper.io.out.request.operation === Fp32Operation.sgnj ||
      mapper.io.out.request.operation === Fp32Operation.minmax ||
      mapper.io.out.request.operation === Fp32Operation.compare ||
      mapper.io.out.request.operation === Fp32Operation.classify ||
      mapper.io.out.request.operation === Fp32Operation.fmvX ||
      mapper.io.out.request.operation === Fp32Operation.fmvFromX ||
      mapper.io.out.request.operation === Fp32Operation.fpToInt ||
      mapper.io.out.request.operation === Fp32Operation.intToFp
  private val needsScalarReserve =
    issue.io.out.valid && issue.io.out.bits.decode.decoded.writesInteger &&
      fast
  private val scalarReserveReady =
    !needsScalarReserve || io.scalarReserve.ready
  private val isMemory =
    issue.io.out.bits.decode.decoded.memoryRead ||
      issue.io.out.bits.decode.decoded.memoryWrite
  issue.io.out.ready := Mux(
    isMemory,
    memory.io.in.ready,
    Mux(
      fast,
      mappedQueue.io.enq.ready && scalarReserveReady,
      io.unimplemented.ready
    )
  )
  io.scalarReserve.valid := needsScalarReserve
  io.scalarReserve.bits.warpId := issue.io.out.bits.decode.warpId
  io.scalarReserve.bits.rs1 := 0.U
  io.scalarReserve.bits.rs2 := 0.U
  io.scalarReserve.bits.rd := issue.io.out.bits.decode.instruction(11, 7)
  io.scalarReserve.bits.useRs1 := false.B
  io.scalarReserve.bits.useRs2 := false.B
  io.scalarReserve.bits.writeRd := true.B
  mappedQueue.io.enq.valid :=
    issue.io.out.valid && fast && !isMemory && scalarReserveReady
  mappedQueue.io.enq.bits.request := mapper.io.out.request
  mappedQueue.io.enq.bits.supported := mapper.io.out.supported
  mappedQueue.io.enq.bits.decode := issue.io.out.bits
  private val isExactDeq =
    mappedQueue.io.deq.bits.request.operation === Fp32Operation.sgnj ||
      mappedQueue.io.deq.bits.request.operation === Fp32Operation.minmax ||
      mappedQueue.io.deq.bits.request.operation === Fp32Operation.compare ||
      mappedQueue.io.deq.bits.request.operation === Fp32Operation.classify ||
      mappedQueue.io.deq.bits.request.operation === Fp32Operation.fmvX ||
      mappedQueue.io.deq.bits.request.operation === Fp32Operation.fmvFromX ||
      mappedQueue.io.deq.bits.request.operation === Fp32Operation.fpToInt ||
      mappedQueue.io.deq.bits.request.operation === Fp32Operation.intToFp
  private val executionReady = Mux(
    isExactDeq,
    exact.io.in.ready && exactMetadata.io.enq.ready,
    fma.io.in.ready && fmaMetadata.io.enq.ready
  )
  exact.io.in.valid :=
    mappedQueue.io.deq.valid && isExactDeq && executionReady
  exact.io.in.bits := mappedQueue.io.deq.bits.request
  fma.io.in.valid :=
    mappedQueue.io.deq.valid && fast && !isExactDeq && executionReady
  fma.io.in.bits := mappedQueue.io.deq.bits.request
  mappedQueue.io.deq.ready := executionReady
  exactMetadata.io.enq.valid :=
    mappedQueue.io.deq.valid && isExactDeq && executionReady
  exactMetadata.io.enq.bits := mappedQueue.io.deq.bits.decode
  fmaMetadata.io.enq.valid :=
    mappedQueue.io.deq.valid && fast && !isExactDeq && executionReady
  fmaMetadata.io.enq.bits := mappedQueue.io.deq.bits.decode
  memory.io.in.valid :=
    issue.io.out.valid && isMemory && memory.io.in.ready
  memory.io.in.bits := issue.io.out.bits
  io.unimplemented.valid := issue.io.out.valid && !fast && !isMemory
  io.unimplemented.bits := issue.io.out.bits
  fma.io.flush := io.flush
  io.memoryRequest <> memory.io.cacheRequest
  memory.io.cacheResponse <> io.memoryResponse
  io.memoryFault <> memory.io.fault

  private val exactResultValid =
    exact.io.out.valid && exactMetadata.io.deq.valid
  private val fmaResultValid =
    fma.io.out.valid && fmaMetadata.io.deq.valid
  private val memoryResultValid = memory.io.commit.valid
  private val selectedMemory = memoryResultValid
  private val selectedExact = !selectedMemory && exactResultValid
  private val selectedFma = !selectedMemory && !selectedExact && fmaResultValid
  private val resultValid = selectedMemory || selectedExact || selectedFma
  private val result = Wire(new FpuDecodeResponse(config))
  result := Mux(
    selectedMemory,
    memory.io.commit.bits.decode,
    Mux(
      selectedExact,
      exactMetadata.io.deq.bits.decode,
      fmaMetadata.io.deq.bits.decode
    )
  )
  private val resultData = Mux(
    selectedMemory,
    memory.io.commit.bits.loadData,
    Mux(selectedExact, exact.io.out.bits.result, fma.io.out.bits.result)
  )
  private val resultStatus = Mux(
    selectedMemory,
    0.U,
    Mux(selectedExact, exact.io.out.bits.status, fma.io.out.bits.status)
  )
  private val rd = result.instruction(11, 7)
  private val needsWrite = result.decoded.writesFp
  private val needsScalarWriteback = result.decoded.writesInteger
  private val scalarWritebackReady =
    !needsScalarWriteback || io.scalarWriteback.ready
  private val commitReady = io.redirect.ready && scalarWritebackReady
  memory.io.commit.ready := selectedMemory && commitReady
  exact.io.out.ready :=
    !selectedMemory && exactMetadata.io.deq.valid && commitReady
  fma.io.out.ready :=
    !selectedMemory && fmaMetadata.io.deq.valid &&
      commitReady && !selectedExact
  exactMetadata.io.deq.ready :=
    !selectedMemory && exact.io.out.valid && commitReady
  fmaMetadata.io.deq.ready :=
    !selectedMemory && fma.io.out.valid &&
      commitReady && !selectedExact

  io.redirect.valid := resultValid
  io.redirect.bits.warpId := result.warpId
  io.redirect.bits.pc := result.pc + 4.U
  io.redirect.bits.activeMask := result.activeMask
  io.committedWriteback.valid := resultValid && commitReady && needsWrite
  io.committedWriteback.bits.warpId := result.warpId
  io.committedWriteback.bits.rd := rd
  io.committedWriteback.bits.data := resultData
  io.committedFlags.valid :=
    resultValid && commitReady && !selectedMemory &&
      result.decoded.setsFlags
  io.committedFlags.bits.warpId := result.warpId
  io.committedFlags.bits.flags := resultStatus
  io.scalarWriteback.valid := resultValid && needsScalarWriteback
  io.scalarWriteback.bits.warpId := result.warpId
  io.scalarWriteback.bits.rd := rd
  io.scalarWriteback.bits.data := resultData
  io.committedIntegerWriteback.valid := io.scalarWriteback.valid
  io.committedIntegerWriteback.bits := io.scalarWriteback.bits

  io.initialize.ready := !io.committedWriteback.valid
  issue.io.writeback.valid := io.committedWriteback.valid || io.initialize.fire
  issue.io.writeback.bits := Mux(
    io.committedWriteback.valid,
    io.committedWriteback.bits,
    io.initialize.bits
  )
  io.rawHazard := issue.io.rawHazard
  io.wawHazard := issue.io.wawHazard

  when(resultValid && !selectedMemory) {
    val expectedTag = Cat(
      result.warpId, result.instruction(11, 7))
    when(selectedExact) {
      assert(exact.io.out.bits.tag === expectedTag)
    }.elsewhen(selectedFma) {
      assert(fma.io.out.bits.tag === expectedTag)
    }
  }
}
