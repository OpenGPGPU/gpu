package gpu.core.backend

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.backend.issue.{
  VectorExecutionDispatch,
  VectorIssueStage,
  VectorIssuedInstruction
}
import gpu.core.backend.register.{
  ScalarRegisterRead,
  ScalarRegisterWrite,
  VectorRegisterWrite
}
import gpu.core.backend.scoreboard.RegisterReservation
import gpu.core.execute.control.SimtPath
import gpu.core.frontend.decode.VectorDecodeResponse
import gpu.core.memory.{
  VectorMemoryFault,
  VectorMemoryResponse,
  VectorMemoryTransaction,
  VectorMemoryUnit
}
import gpu.core.vector.{
  VectorConfigurationUnit,
  VectorIntegerAlu,
  VectorMultiplyAlu
}

private class VectorCommitRequest(config: GpuConfig) extends Bundle {
  val writeback = new VectorRegisterWrite(config)
  val saturated = Bool()
  val writesVd = Bool()
  val pc = UInt(config.xLen.W)
  val warpActiveMask = UInt(config.lanes.W)
}

/** Connected RVV integer backend with per-warp configuration and writeback. */
class VectorBackend(config: GpuConfig = GpuConfig()) extends Module {
  private val vectorWidth = config.lanes * config.xLen
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VectorDecodeResponse(config)))
    val scalarRead = Output(new ScalarRegisterRead(config))
    val scalarRs1Data = Input(UInt(config.xLen.W))
    val scalarRs2Data = Input(UInt(config.xLen.W))
    val scalarReserve = Decoupled(new RegisterReservation(config))
    val initialize = Flipped(Decoupled(new VectorRegisterWrite(config)))
    val scalarWriteback = Decoupled(new ScalarRegisterWrite(config))
    val redirect = Decoupled(new SimtPath(config))
    val memoryRequest = Decoupled(new VectorMemoryTransaction(config))
    val memoryResponse = Flipped(Decoupled(new VectorMemoryResponse(config)))
    val memoryFault = Decoupled(new VectorMemoryFault(config))
    val committedVectorWriteback = Valid(new VectorRegisterWrite(config))
    val unimplemented = Decoupled(new VectorIssuedInstruction(config))
    val rawHazard = Output(Bool())
    val wawHazard = Output(Bool())
  })

  private val issue = Module(new VectorIssueStage(config))
  private val dispatch = Module(new VectorExecutionDispatch(config))
  private val integerAlu = Module(new VectorIntegerAlu(config))
  private val multiplyAlu = Module(new VectorMultiplyAlu(config))
  private val configuration = Module(new VectorConfigurationUnit(config))
  private val memory = Module(new VectorMemoryUnit(config))
  private val writebackArbiter =
    Module(new RRArbiter(new VectorCommitRequest(config), 3))
  private val completionQueue =
    Module(new Queue(new SimtPath(config), 1, pipe = false, flow = false))
  private val inflight =
    RegInit(VecInit(Seq.fill(config.warps)(0.U(6.W))))

  issue.io.in <> io.in
  dispatch.io.in <> issue.io.out
  io.scalarRead := issue.io.scalarRead
  issue.io.scalarRs1Data := io.scalarRs1Data
  issue.io.scalarRs2Data := io.scalarRs2Data
  io.unimplemented <> dispatch.io.unimplemented
  io.memoryRequest <> memory.io.memoryRequest
  memory.io.memoryResponse <> io.memoryResponse

  configuration.io.queryWarpId :=
    Mux(
      dispatch.io.multiply.valid,
      dispatch.io.multiply.bits.decode.warpId,
      dispatch.io.alu.bits.decode.warpId
    )
  private val vectorState = configuration.io.state
  private val allLanes = Fill(config.lanes, 1.U)
  private val vlShiftWidth = math.max(1, log2Ceil(config.lanes + 1))
  private val boundedVl = vectorState.vl(vlShiftWidth - 1, 0)
  private val vlMask = Mux(
    vectorState.vl >= config.lanes.U,
    allLanes,
    ((1.U(config.lanes.W) << boundedVl) - 1.U)(config.lanes - 1, 0)
  )

  integerAlu.io.in.valid := dispatch.io.alu.valid
  dispatch.io.alu.ready := integerAlu.io.in.ready
  integerAlu.io.in.bits.warpId := dispatch.io.alu.bits.decode.warpId
  integerAlu.io.in.bits.pc := dispatch.io.alu.bits.decode.pc
  integerAlu.io.in.bits.warpActiveMask :=
    dispatch.io.alu.bits.decode.activeMask
  integerAlu.io.in.bits.vd := dispatch.io.alu.bits.decode.instruction(11, 7)
  integerAlu.io.in.bits.activeMask :=
    dispatch.io.alu.bits.decode.activeMask & vlMask
  integerAlu.io.in.bits.predicateMask :=
    dispatch.io.alu.bits.predicateMask
  integerAlu.io.in.bits.oldVd := dispatch.io.alu.bits.oldVdData
  integerAlu.io.in.bits.vs1 := dispatch.io.alu.bits.vs1Data
  integerAlu.io.in.bits.vs2 := dispatch.io.alu.bits.vs2Data
  integerAlu.io.in.bits.scalar := dispatch.io.alu.bits.scalarRs1Data
  integerAlu.io.in.bits.immediate :=
    dispatch.io.alu.bits.decode.instruction(19, 15)
  integerAlu.io.in.bits.funct6 :=
    dispatch.io.alu.bits.decode.decoded.funct6
  integerAlu.io.in.bits.operandType :=
    dispatch.io.alu.bits.decode.decoded.operandType
  integerAlu.io.in.bits.vm := dispatch.io.alu.bits.decode.decoded.vm

  multiplyAlu.io.in.valid := dispatch.io.multiply.valid
  dispatch.io.multiply.ready := multiplyAlu.io.in.ready
  multiplyAlu.io.in.bits.warpId := dispatch.io.multiply.bits.decode.warpId
  multiplyAlu.io.in.bits.pc := dispatch.io.multiply.bits.decode.pc
  multiplyAlu.io.in.bits.warpActiveMask :=
    dispatch.io.multiply.bits.decode.activeMask
  multiplyAlu.io.in.bits.vd :=
    dispatch.io.multiply.bits.decode.instruction(11, 7)
  multiplyAlu.io.in.bits.activeMask :=
    dispatch.io.multiply.bits.decode.activeMask & vlMask
  multiplyAlu.io.in.bits.predicateMask :=
    dispatch.io.multiply.bits.predicateMask
  multiplyAlu.io.in.bits.oldVd := dispatch.io.multiply.bits.oldVdData
  multiplyAlu.io.in.bits.vs1 := dispatch.io.multiply.bits.vs1Data
  multiplyAlu.io.in.bits.vs2 := dispatch.io.multiply.bits.vs2Data
  multiplyAlu.io.in.bits.scalar := dispatch.io.multiply.bits.scalarRs1Data
  multiplyAlu.io.in.bits.funct6 :=
    dispatch.io.multiply.bits.decode.decoded.funct6
  multiplyAlu.io.in.bits.operandType :=
    dispatch.io.multiply.bits.decode.decoded.operandType
  multiplyAlu.io.in.bits.vm :=
    dispatch.io.multiply.bits.decode.decoded.vm
  multiplyAlu.io.in.bits.vxrm := vectorState.vxrm

  private val memoryMask =
    dispatch.io.memory.bits.decode.activeMask & vlMask &
      Mux(
        dispatch.io.memory.bits.decode.decoded.vm,
        allLanes,
        dispatch.io.memory.bits.predicateMask
      )
  memory.io.in.valid := dispatch.io.memory.valid
  dispatch.io.memory.ready := memory.io.in.ready
  memory.io.in.bits.warpId := dispatch.io.memory.bits.decode.warpId
  memory.io.in.bits.pc := dispatch.io.memory.bits.decode.pc
  memory.io.in.bits.warpActiveMask :=
    dispatch.io.memory.bits.decode.activeMask
  memory.io.in.bits.activeMask := memoryMask
  memory.io.in.bits.vd :=
    dispatch.io.memory.bits.decode.instruction(11, 7)
  memory.io.in.bits.baseAddress := dispatch.io.memory.bits.scalarRs1Data
  memory.io.in.bits.storeData := dispatch.io.memory.bits.oldVdData
  memory.io.in.bits.oldVd := dispatch.io.memory.bits.oldVdData
  memory.io.in.bits.elementSize := MuxLookup(
    dispatch.io.memory.bits.decode.instruction(14, 12),
    2.U
  )(
    Seq("b000".U -> 0.U, "b101".U -> 1.U, "b110".U -> 2.U)
  )
  memory.io.in.bits.isStore :=
    dispatch.io.memory.bits.decode.decoded.memoryWrite

  private val configWarp = dispatch.io.configuration.bits.decode.warpId
  private val completingVector = writebackArbiter.io.out.fire
  private val completingFault = io.memoryFault.fire
  private val completingConfigWarp =
    completingVector &&
      writebackArbiter.io.out.bits.writeback.warpId === configWarp
  private val selectedInflight =
    if (config.warps == 1) inflight(0) else inflight(configWarp)
  private val configCanEnter =
    selectedInflight === 0.U ||
      (selectedInflight === 1.U && completingConfigWarp)
  private val configurationReady =
    configuration.io.in.ready && io.scalarReserve.ready
  configuration.io.in.valid :=
    dispatch.io.configuration.valid && configCanEnter &&
      io.scalarReserve.ready
  dispatch.io.configuration.ready :=
    configurationReady && configCanEnter
  io.scalarReserve.valid :=
    dispatch.io.configuration.valid && configCanEnter &&
      configuration.io.in.ready
  io.scalarReserve.bits.warpId := configWarp
  io.scalarReserve.bits.rs1 := 0.U
  io.scalarReserve.bits.rs2 := 0.U
  io.scalarReserve.bits.rd :=
    dispatch.io.configuration.bits.decode.instruction(11, 7)
  io.scalarReserve.bits.useRs1 := false.B
  io.scalarReserve.bits.useRs2 := false.B
  io.scalarReserve.bits.writeRd := io.scalarReserve.bits.rd =/= 0.U
  configuration.io.in.bits.instruction :=
    dispatch.io.configuration.bits.decode.instruction
  configuration.io.in.bits.warpId :=
    dispatch.io.configuration.bits.decode.warpId
  configuration.io.in.bits.pc :=
    dispatch.io.configuration.bits.decode.pc
  configuration.io.in.bits.warpActiveMask :=
    dispatch.io.configuration.bits.decode.activeMask
  configuration.io.in.bits.rs1Data :=
    dispatch.io.configuration.bits.scalarRs1Data
  configuration.io.in.bits.rs2Data :=
    dispatch.io.configuration.bits.scalarRs2Data

  private val aluFlat = integerAlu.io.out.bits.data.asUInt
  private val packedMask = Cat(
    aluFlat(vectorWidth - 1, config.lanes),
    integerAlu.io.out.bits.mask
  ).asTypeOf(Vec(config.lanes, UInt(config.xLen.W)))
  writebackArbiter.io.in(0).valid := integerAlu.io.out.valid
  integerAlu.io.out.ready := writebackArbiter.io.in(0).ready
  writebackArbiter.io.in(0).bits.writeback.warpId :=
    integerAlu.io.out.bits.warpId
  writebackArbiter.io.in(0).bits.writeback.vd := integerAlu.io.out.bits.vd
  writebackArbiter.io.in(0).bits.writeback.data :=
    Mux(
      integerAlu.io.out.bits.writesMask,
      packedMask.asUInt,
      integerAlu.io.out.bits.data.asUInt
    ).asTypeOf(Vec(config.lanes, UInt(config.xLen.W)))
  writebackArbiter.io.in(0).bits.saturated :=
    integerAlu.io.out.bits.saturated
  writebackArbiter.io.in(0).bits.writesVd := true.B
  writebackArbiter.io.in(0).bits.pc := integerAlu.io.out.bits.pc
  writebackArbiter.io.in(0).bits.warpActiveMask :=
    integerAlu.io.out.bits.warpActiveMask

  writebackArbiter.io.in(1).valid := multiplyAlu.io.out.valid
  multiplyAlu.io.out.ready := writebackArbiter.io.in(1).ready
  writebackArbiter.io.in(1).bits.writeback.warpId :=
    multiplyAlu.io.out.bits.warpId
  writebackArbiter.io.in(1).bits.writeback.vd :=
    multiplyAlu.io.out.bits.vd
  writebackArbiter.io.in(1).bits.writeback.data :=
    multiplyAlu.io.out.bits.data
  writebackArbiter.io.in(1).bits.saturated :=
    multiplyAlu.io.out.bits.saturated
  writebackArbiter.io.in(1).bits.writesVd := true.B
  writebackArbiter.io.in(1).bits.pc := multiplyAlu.io.out.bits.pc
  writebackArbiter.io.in(1).bits.warpActiveMask :=
    multiplyAlu.io.out.bits.warpActiveMask

  private val memorySucceeded =
    memory.io.out.bits.faultMask === 0.U
  writebackArbiter.io.in(2).valid :=
    memory.io.out.valid && memorySucceeded
  io.memoryFault.valid := memory.io.out.valid && !memorySucceeded
  io.memoryFault.bits.warpId := memory.io.out.bits.warpId
  io.memoryFault.bits.pc := memory.io.out.bits.pc
  io.memoryFault.bits.warpActiveMask :=
    memory.io.out.bits.warpActiveMask
  io.memoryFault.bits.faultMask := memory.io.out.bits.faultMask
  io.memoryFault.bits.pageFault := memory.io.out.bits.pageFault
  io.memoryFault.bits.isStore := memory.io.out.bits.isStore
  io.memoryFault.bits.addresses := memory.io.out.bits.addresses
  io.memoryFault.bits.vd := memory.io.out.bits.vd
  io.memoryFault.bits.writeVd := memory.io.out.bits.writesVd
  memory.io.out.ready := Mux(
    memorySucceeded,
    writebackArbiter.io.in(2).ready,
    io.memoryFault.ready
  )
  writebackArbiter.io.in(2).bits.writeback.warpId :=
    memory.io.out.bits.warpId
  writebackArbiter.io.in(2).bits.writeback.vd := memory.io.out.bits.vd
  writebackArbiter.io.in(2).bits.writeback.data := memory.io.out.bits.data
  writebackArbiter.io.in(2).bits.saturated := false.B
  writebackArbiter.io.in(2).bits.writesVd :=
    memory.io.out.bits.writesVd
  writebackArbiter.io.in(2).bits.pc := memory.io.out.bits.pc
  writebackArbiter.io.in(2).bits.warpActiveMask :=
    memory.io.out.bits.warpActiveMask

  private val initializeWarpValid =
    io.initialize.bits.warpId < config.warps.U
  private val initializeWarpIdle =
    if (config.warps == 1) inflight(0) === 0.U
    else Mux(
      initializeWarpValid,
      inflight(io.initialize.bits.warpId) === 0.U,
      false.B
    )
  io.initialize.ready :=
    !writebackArbiter.io.out.valid &&
      initializeWarpValid && initializeWarpIdle
  // The queue makes completion a registered scheduler event and cuts the
  // completion-arbiter ready path. A vset* scalar write and its queued
  // completion are accepted atomically.
  private val selectVector = writebackArbiter.io.out.valid
  private val selectConfiguration =
    !selectVector && configuration.io.out.valid
  completionQueue.io.enq.valid :=
    selectVector ||
      (selectConfiguration && io.scalarWriteback.ready)
  completionQueue.io.enq.bits.warpId := Mux(
    selectVector,
    writebackArbiter.io.out.bits.writeback.warpId,
    configuration.io.out.bits.warpId
  )
  completionQueue.io.enq.bits.pc := Mux(
    selectVector,
    writebackArbiter.io.out.bits.pc,
    configuration.io.out.bits.pc
  ) + 4.U
  completionQueue.io.enq.bits.activeMask := Mux(
    selectVector,
    writebackArbiter.io.out.bits.warpActiveMask,
    configuration.io.out.bits.warpActiveMask
  )
  io.redirect <> completionQueue.io.deq
  writebackArbiter.io.out.ready := completionQueue.io.enq.ready
  issue.io.vectorWriteback.valid :=
    (writebackArbiter.io.out.fire &&
      writebackArbiter.io.out.bits.writesVd) || io.initialize.fire
  issue.io.vectorWriteback.bits := Mux(
    writebackArbiter.io.out.fire,
    writebackArbiter.io.out.bits.writeback,
    io.initialize.bits
  )
  issue.io.cancel.valid := io.memoryFault.fire && io.memoryFault.bits.writeVd
  issue.io.cancel.bits.warpId := io.memoryFault.bits.warpId
  issue.io.cancel.bits.vd := io.memoryFault.bits.vd
  io.committedVectorWriteback.valid :=
    writebackArbiter.io.out.fire &&
      writebackArbiter.io.out.bits.writesVd
  io.committedVectorWriteback.bits :=
    writebackArbiter.io.out.bits.writeback

  configuration.io.csrWrite.valid :=
    writebackArbiter.io.out.fire &&
      writebackArbiter.io.out.bits.saturated
  configuration.io.csrWrite.bits.warpId :=
    writebackArbiter.io.out.bits.writeback.warpId
  configuration.io.csrWrite.bits.address := "h009".U
  configuration.io.csrWrite.bits.data := 1.U

  private val startingVector =
    dispatch.io.alu.fire || dispatch.io.multiply.fire ||
      dispatch.io.memory.fire
  private val startingWarp = Mux(
    dispatch.io.memory.fire,
    dispatch.io.memory.bits.decode.warpId,
    Mux(
      dispatch.io.multiply.fire,
      dispatch.io.multiply.bits.decode.warpId,
      dispatch.io.alu.bits.decode.warpId
    )
  )
  for (warp <- 0 until config.warps) {
    val increment = startingVector && startingWarp === warp.U
    val decrement =
      (completingVector &&
        writebackArbiter.io.out.bits.writeback.warpId === warp.U) ||
        (completingFault && io.memoryFault.bits.warpId === warp.U)
    when(increment && !decrement) {
      inflight(warp) := inflight(warp) + 1.U
    }.elsewhen(decrement && !increment) {
      inflight(warp) := inflight(warp) - 1.U
    }
    when(decrement) {
      assert(inflight(warp) =/= 0.U, "vector in-flight counter underflow")
    }
  }

  io.scalarWriteback.valid :=
    selectConfiguration && completionQueue.io.enq.ready
  configuration.io.out.ready :=
    !selectVector && io.scalarWriteback.ready &&
      completionQueue.io.enq.ready
  io.scalarWriteback.bits.warpId := configuration.io.out.bits.warpId
  io.scalarWriteback.bits.rd := configuration.io.out.bits.rd
  io.scalarWriteback.bits.data := configuration.io.out.bits.data

  io.rawHazard := issue.io.rawHazard
  io.wawHazard := issue.io.wawHazard
}
