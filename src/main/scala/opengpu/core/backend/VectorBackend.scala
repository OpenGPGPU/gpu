package opengpu.core.backend

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.issue.{
  VectorExecutionDispatch,
  VectorIssueStage,
  VectorIssuedInstruction
}
import opengpu.core.backend.register.{
  FpuRegisterRead,
  ScalarRegisterRead,
  ScalarRegisterWrite,
  VectorRegisterWrite
}
import opengpu.core.backend.scoreboard.RegisterReservation
import opengpu.core.execute.control.SimtPath
import opengpu.core.frontend.decode.VectorDecodeResponse
import opengpu.core.memory.{
  VectorMemoryFault,
  VectorMemoryResponse,
  VectorMemoryResult,
  VectorMemoryTransaction,
  VectorMemoryUnit
}
import opengpu.core.vector.{
  VectorConfigurationUnit,
  VectorFcvtAlu,
  VectorFEstimateAlu,
  VectorFsqrtAlu,
  VectorDivideAlu,
  VectorFdivAlu,
  VectorIntegerAlu,
  VectorFmaAlu,
  VectorMultiplyAlu,
  VectorFpuAlu,
  VectorFlagsWrite
}

private class VectorCommitRequest(config: GpuConfig) extends Bundle {
  val writeback = new VectorRegisterWrite(config)
  val saturated = Bool()
  val writesVd = Bool()
  val flags = UInt(5.W)
  val writesFlags = Bool()
  val pc = UInt(config.xLen.W)
  val warpActiveMask = UInt(config.lanes.W)
}

/** Connected RVV backend with per-warp configuration and writeback. */
class VectorBackend(
  config: GpuConfig = GpuConfig(),
  useBlackBox: Boolean = false
) extends Module {
  private val vectorWidth = config.lanes * config.xLen
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VectorDecodeResponse(config)))
    val scalarRead = Output(new ScalarRegisterRead(config))
    val scalarRs1Data = Input(UInt(config.xLen.W))
    val scalarRs2Data = Input(UInt(config.xLen.W))
    val scalarFpRead = Output(new FpuRegisterRead(config))
    val scalarFpData = Input(UInt(32.W))
    val scalarFpBusy = Input(Vec(config.warps, UInt(32.W)))
    val scalarFlagsWrite = Flipped(Valid(new VectorFlagsWrite(config)))
    val frm = Output(Vec(config.warps, UInt(3.W)))
    val scalarReserve = Decoupled(new RegisterReservation(config))
    val initialize = Flipped(Decoupled(new VectorRegisterWrite(config)))
    val scalarWriteback = Decoupled(new ScalarRegisterWrite(config))
    val redirect = Decoupled(new SimtPath(config))
    val memoryRequest = Decoupled(new VectorMemoryTransaction(config))
    val memoryResponse = Flipped(Decoupled(new VectorMemoryResponse(config)))
    val memoryFault = Decoupled(new VectorMemoryFault(config))
    val committedVectorWriteback = Valid(new VectorRegisterWrite(config))
    val committedVectorFlags = Valid(new FpuFlags(config))
    val unimplemented = Decoupled(new VectorIssuedInstruction(config))
    val rawHazard = Output(Bool())
    val wawHazard = Output(Bool())
  })

  private val issue = Module(new VectorIssueStage(config, useBlackBox))
  private val dispatch = Module(new VectorExecutionDispatch(config))
  private val integerAlu = Module(new VectorIntegerAlu(config))
  private val multiplyAlu = Module(new VectorMultiplyAlu(config))
  private val divideAlu = Module(new VectorDivideAlu(config))
  private val fpuAlu = Module(new VectorFpuAlu(config))
  private val cvtAlu = Module(new VectorFcvtAlu(config))
  private val estimateAlu = Module(new VectorFEstimateAlu(config))
  private val fsqrtAlu = Module(new VectorFsqrtAlu(config))
  private val fmaAlu = Module(new VectorFmaAlu(config))
  private val divAlu = Module(new VectorFdivAlu(config))
  private val configuration = Module(new VectorConfigurationUnit(config))
  private val memory = Module(new VectorMemoryUnit(config))
  private val writebackArbiter =
    Module(new RRArbiter(new VectorCommitRequest(config), 10))
  private val completionQueue =
    Module(new Queue(new SimtPath(config), 1, pipe = false, flow = false))
  private val inflight =
    RegInit(VecInit(Seq.fill(config.warps)(0.U(6.W))))
  private val commitValid = RegInit(false.B)
  private val commitBits = Reg(new VectorCommitRequest(config))
  private val commitFire = commitValid && completionQueue.io.enq.ready

  when(writebackArbiter.io.out.fire) {
    commitValid := true.B
    commitBits := writebackArbiter.io.out.bits
  }.elsewhen(commitFire) {
    commitValid := false.B
  }

  issue.io.in <> io.in
  dispatch.io.in <> issue.io.out
  io.scalarRead := issue.io.scalarRead
  issue.io.scalarRs1Data := io.scalarRs1Data
  issue.io.scalarRs2Data := io.scalarRs2Data
  io.scalarFpRead := issue.io.scalarFpRead
  issue.io.scalarFpData := io.scalarFpData
  issue.io.scalarFpBusy := io.scalarFpBusy
  io.memoryRequest <> memory.io.memoryRequest
  memory.io.memoryResponse <> io.memoryResponse

  configuration.io.queryWarpId :=
    Mux(
      dispatch.io.multiply.valid,
      dispatch.io.multiply.bits.decode.warpId,
      Mux(
        dispatch.io.alu.valid,
        dispatch.io.alu.bits.decode.warpId,
        dispatch.io.fpu.bits.decode.warpId
      )
    )
  private val vectorState = configuration.io.state
  io.frm := configuration.io.frmByWarp
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

  divideAlu.io.in.valid := dispatch.io.divide.valid
  dispatch.io.divide.ready := divideAlu.io.in.ready
  divideAlu.io.in.bits.warpId := dispatch.io.divide.bits.decode.warpId
  divideAlu.io.in.bits.pc := dispatch.io.divide.bits.decode.pc
  divideAlu.io.in.bits.warpActiveMask :=
    dispatch.io.divide.bits.decode.activeMask
  divideAlu.io.in.bits.vd :=
    dispatch.io.divide.bits.decode.instruction(11, 7)
  divideAlu.io.in.bits.activeMask :=
    dispatch.io.divide.bits.decode.activeMask & vlMask
  divideAlu.io.in.bits.predicateMask :=
    dispatch.io.divide.bits.predicateMask
  divideAlu.io.in.bits.oldVd := dispatch.io.divide.bits.oldVdData
  divideAlu.io.in.bits.vs1 := dispatch.io.divide.bits.vs1Data
  divideAlu.io.in.bits.vs2 := dispatch.io.divide.bits.vs2Data
  divideAlu.io.in.bits.scalar := dispatch.io.divide.bits.scalarRs1Data
  divideAlu.io.in.bits.immediate := 0.U
  divideAlu.io.in.bits.funct6 :=
    dispatch.io.divide.bits.decode.decoded.funct6
  divideAlu.io.in.bits.operandType :=
    dispatch.io.divide.bits.decode.decoded.operandType
  divideAlu.io.in.bits.vm :=
    dispatch.io.divide.bits.decode.decoded.vm

  private val fpuIsArithmetic =
    dispatch.io.fpu.bits.decode.decoded.funct6 === "h00".U ||
      dispatch.io.fpu.bits.decode.decoded.funct6 === "h02".U ||
      dispatch.io.fpu.bits.decode.decoded.funct6 === "h24".U ||
      dispatch.io.fpu.bits.decode.decoded.funct6 === "h27".U ||
      (dispatch.io.fpu.bits.decode.decoded.funct6 >= "h28".U &&
        dispatch.io.fpu.bits.decode.decoded.funct6 <= "h2f".U)
  private val fpuIsExact = MuxLookup(
    dispatch.io.fpu.bits.decode.decoded.funct6, false.B
  )(Seq(
    "h04".U -> true.B,
    "h06".U -> true.B,
    "h08".U -> true.B,
    "h09".U -> true.B,
    "h0a".U -> true.B,
    "h17".U -> true.B,
    "h18".U -> true.B,
    "h19".U -> true.B,
    "h1b".U -> true.B,
    "h1c".U -> true.B,
    "h1d".U -> true.B,
    "h1f".U -> true.B
  ))
  private val fpuIsDiv =
    dispatch.io.fpu.bits.decode.decoded.funct6 === "h20".U ||
      dispatch.io.fpu.bits.decode.decoded.funct6 === "h21".U
  private val fpuIsCvt =
    dispatch.io.fpu.bits.decode.decoded.funct6 === "h12".U
  private val fpuIsUnary1 =
    dispatch.io.fpu.bits.decode.decoded.funct6 === "h13".U
  private val fpuVs1 =
    dispatch.io.fpu.bits.decode.instruction(19, 15)
  private val fpuIsSqrt = fpuIsUnary1 && fpuVs1 === "b00000".U
  private val fpuIsRecip = fpuIsUnary1 && fpuVs1 === "b00100".U
  private val fpuIsRsqrt = fpuIsUnary1 && fpuVs1 === "b00101".U
  private val fpuIsClassify = fpuIsUnary1 && fpuVs1 === "b10000".U
  private val fpuUnsupported =
    dispatch.io.fpu.valid && !fpuIsArithmetic && !fpuIsExact &&
      !fpuIsDiv && !fpuIsCvt && !fpuIsSqrt && !fpuIsClassify &&
      !fpuIsRecip && !fpuIsRsqrt
  fpuAlu.io.in.valid := dispatch.io.fpu.valid && fpuIsExact
  cvtAlu.io.in.valid := dispatch.io.fpu.valid && (fpuIsCvt || fpuIsClassify)
  estimateAlu.io.in.valid :=
    dispatch.io.fpu.valid && (fpuIsRecip || fpuIsRsqrt)
  fsqrtAlu.io.in.valid := dispatch.io.fpu.valid && fpuIsSqrt
  fmaAlu.io.in.valid := dispatch.io.fpu.valid && fpuIsArithmetic
  divAlu.io.in.valid := dispatch.io.fpu.valid && fpuIsDiv
  dispatch.io.fpu.ready := Mux(
    fpuUnsupported,
    io.unimplemented.ready,
    Mux(
      fpuIsArithmetic,
      fmaAlu.io.in.ready,
      Mux(
        fpuIsSqrt,
        fsqrtAlu.io.in.ready,
        Mux(
          fpuIsRecip || fpuIsRsqrt,
          estimateAlu.io.in.ready,
          Mux(
            fpuIsCvt || fpuIsClassify,
            cvtAlu.io.in.ready,
            Mux(fpuIsExact, fpuAlu.io.in.ready, divAlu.io.in.ready)
          )
        )
      )
    )
  )
  io.unimplemented.valid :=
    dispatch.io.unimplemented.valid || fpuUnsupported
  io.unimplemented.bits := Mux(
    dispatch.io.unimplemented.valid,
    dispatch.io.unimplemented.bits,
    dispatch.io.fpu.bits
  )
  dispatch.io.unimplemented.ready :=
    io.unimplemented.ready && !fpuUnsupported
  fpuAlu.io.in.bits.warpId := dispatch.io.fpu.bits.decode.warpId
  fpuAlu.io.in.bits.pc := dispatch.io.fpu.bits.decode.pc
  fpuAlu.io.in.bits.warpActiveMask :=
    dispatch.io.fpu.bits.decode.activeMask
  fpuAlu.io.in.bits.vd :=
    dispatch.io.fpu.bits.decode.instruction(11, 7)
  fpuAlu.io.in.bits.activeMask :=
    dispatch.io.fpu.bits.decode.activeMask & vlMask
  fpuAlu.io.in.bits.rawActiveMask :=
    dispatch.io.fpu.bits.decode.activeMask & vlMask
  fpuAlu.io.in.bits.predicateMask :=
    dispatch.io.fpu.bits.predicateMask
  fpuAlu.io.in.bits.oldVd := dispatch.io.fpu.bits.oldVdData
  fpuAlu.io.in.bits.vs1 := dispatch.io.fpu.bits.vs1Data
  fpuAlu.io.in.bits.vs2 := dispatch.io.fpu.bits.vs2Data
  fpuAlu.io.in.bits.vs1Field := fpuVs1
  fpuAlu.io.in.bits.scalarFpData := dispatch.io.fpu.bits.scalarFpData
  fpuAlu.io.in.bits.roundingMode := vectorState.frm
  fpuAlu.io.in.bits.funct6 :=
    dispatch.io.fpu.bits.decode.decoded.funct6
  fpuAlu.io.in.bits.operandType :=
    dispatch.io.fpu.bits.decode.decoded.operandType
  fpuAlu.io.in.bits.vm :=
    dispatch.io.fpu.bits.decode.decoded.vm

  cvtAlu.io.in.bits.warpId := dispatch.io.fpu.bits.decode.warpId
  cvtAlu.io.in.bits.pc := dispatch.io.fpu.bits.decode.pc
  cvtAlu.io.in.bits.warpActiveMask :=
    dispatch.io.fpu.bits.decode.activeMask
  cvtAlu.io.in.bits.vd :=
    dispatch.io.fpu.bits.decode.instruction(11, 7)
  cvtAlu.io.in.bits.activeMask :=
    dispatch.io.fpu.bits.decode.activeMask & vlMask
  cvtAlu.io.in.bits.rawActiveMask :=
    dispatch.io.fpu.bits.decode.activeMask & vlMask
  cvtAlu.io.in.bits.predicateMask :=
    dispatch.io.fpu.bits.predicateMask
  cvtAlu.io.in.bits.oldVd := dispatch.io.fpu.bits.oldVdData
  cvtAlu.io.in.bits.vs1 := dispatch.io.fpu.bits.vs1Data
  cvtAlu.io.in.bits.vs2 := dispatch.io.fpu.bits.vs2Data
  cvtAlu.io.in.bits.vs1Field := fpuVs1
  cvtAlu.io.in.bits.scalarFpData := dispatch.io.fpu.bits.scalarFpData
  cvtAlu.io.in.bits.roundingMode := vectorState.frm
  cvtAlu.io.in.bits.funct6 :=
    dispatch.io.fpu.bits.decode.decoded.funct6
  cvtAlu.io.in.bits.operandType :=
    dispatch.io.fpu.bits.decode.decoded.operandType
  cvtAlu.io.in.bits.vm :=
    dispatch.io.fpu.bits.decode.decoded.vm

  estimateAlu.io.in.bits.warpId := dispatch.io.fpu.bits.decode.warpId
  estimateAlu.io.in.bits.pc := dispatch.io.fpu.bits.decode.pc
  estimateAlu.io.in.bits.warpActiveMask :=
    dispatch.io.fpu.bits.decode.activeMask
  estimateAlu.io.in.bits.vd :=
    dispatch.io.fpu.bits.decode.instruction(11, 7)
  estimateAlu.io.in.bits.activeMask :=
    dispatch.io.fpu.bits.decode.activeMask & vlMask
  estimateAlu.io.in.bits.rawActiveMask :=
    dispatch.io.fpu.bits.decode.activeMask & vlMask
  estimateAlu.io.in.bits.predicateMask :=
    dispatch.io.fpu.bits.predicateMask
  estimateAlu.io.in.bits.oldVd := dispatch.io.fpu.bits.oldVdData
  estimateAlu.io.in.bits.vs1 := dispatch.io.fpu.bits.vs1Data
  estimateAlu.io.in.bits.vs2 := dispatch.io.fpu.bits.vs2Data
  estimateAlu.io.in.bits.vs1Field := fpuVs1
  estimateAlu.io.in.bits.scalarFpData := dispatch.io.fpu.bits.scalarFpData
  estimateAlu.io.in.bits.roundingMode := vectorState.frm
  estimateAlu.io.in.bits.funct6 :=
    dispatch.io.fpu.bits.decode.decoded.funct6
  estimateAlu.io.in.bits.operandType :=
    dispatch.io.fpu.bits.decode.decoded.operandType
  estimateAlu.io.in.bits.vm :=
    dispatch.io.fpu.bits.decode.decoded.vm

  fsqrtAlu.io.in.bits.warpId := dispatch.io.fpu.bits.decode.warpId
  fsqrtAlu.io.in.bits.pc := dispatch.io.fpu.bits.decode.pc
  fsqrtAlu.io.in.bits.warpActiveMask :=
    dispatch.io.fpu.bits.decode.activeMask
  fsqrtAlu.io.in.bits.vd :=
    dispatch.io.fpu.bits.decode.instruction(11, 7)
  fsqrtAlu.io.in.bits.activeMask :=
    dispatch.io.fpu.bits.decode.activeMask & vlMask
  fsqrtAlu.io.in.bits.rawActiveMask :=
    dispatch.io.fpu.bits.decode.activeMask & vlMask
  fsqrtAlu.io.in.bits.predicateMask :=
    dispatch.io.fpu.bits.predicateMask
  fsqrtAlu.io.in.bits.oldVd := dispatch.io.fpu.bits.oldVdData
  fsqrtAlu.io.in.bits.vs1 := dispatch.io.fpu.bits.vs1Data
  fsqrtAlu.io.in.bits.vs2 := dispatch.io.fpu.bits.vs2Data
  fsqrtAlu.io.in.bits.vs1Field := fpuVs1
  fsqrtAlu.io.in.bits.scalarFpData := dispatch.io.fpu.bits.scalarFpData
  fsqrtAlu.io.in.bits.roundingMode := vectorState.frm
  fsqrtAlu.io.in.bits.funct6 :=
    dispatch.io.fpu.bits.decode.decoded.funct6
  fsqrtAlu.io.in.bits.operandType :=
    dispatch.io.fpu.bits.decode.decoded.operandType
  fsqrtAlu.io.in.bits.vm :=
    dispatch.io.fpu.bits.decode.decoded.vm

  fmaAlu.io.in.bits.warpId := dispatch.io.fpu.bits.decode.warpId
  fmaAlu.io.in.bits.pc := dispatch.io.fpu.bits.decode.pc
  fmaAlu.io.in.bits.warpActiveMask :=
    dispatch.io.fpu.bits.decode.activeMask
  fmaAlu.io.in.bits.vd :=
    dispatch.io.fpu.bits.decode.instruction(11, 7)
  fmaAlu.io.in.bits.activeMask :=
    dispatch.io.fpu.bits.decode.activeMask & vlMask
  fmaAlu.io.in.bits.rawActiveMask :=
    dispatch.io.fpu.bits.decode.activeMask & vlMask
  fmaAlu.io.in.bits.predicateMask :=
    dispatch.io.fpu.bits.predicateMask
  fmaAlu.io.in.bits.oldVd := dispatch.io.fpu.bits.oldVdData
  fmaAlu.io.in.bits.vs1 := dispatch.io.fpu.bits.vs1Data
  fmaAlu.io.in.bits.vs2 := dispatch.io.fpu.bits.vs2Data
  fmaAlu.io.in.bits.vs1Field := fpuVs1
  fmaAlu.io.in.bits.scalarFpData := dispatch.io.fpu.bits.scalarFpData
  fmaAlu.io.in.bits.roundingMode := vectorState.frm
  fmaAlu.io.in.bits.funct6 :=
    dispatch.io.fpu.bits.decode.decoded.funct6
  fmaAlu.io.in.bits.operandType :=
    dispatch.io.fpu.bits.decode.decoded.operandType
  fmaAlu.io.in.bits.vm :=
    dispatch.io.fpu.bits.decode.decoded.vm

  divAlu.io.in.bits.warpId := dispatch.io.fpu.bits.decode.warpId
  divAlu.io.in.bits.pc := dispatch.io.fpu.bits.decode.pc
  divAlu.io.in.bits.warpActiveMask :=
    dispatch.io.fpu.bits.decode.activeMask
  divAlu.io.in.bits.vd :=
    dispatch.io.fpu.bits.decode.instruction(11, 7)
  divAlu.io.in.bits.activeMask :=
    dispatch.io.fpu.bits.decode.activeMask & vlMask
  divAlu.io.in.bits.rawActiveMask :=
    dispatch.io.fpu.bits.decode.activeMask & vlMask
  divAlu.io.in.bits.predicateMask :=
    dispatch.io.fpu.bits.predicateMask
  divAlu.io.in.bits.oldVd := dispatch.io.fpu.bits.oldVdData
  divAlu.io.in.bits.vs1 := dispatch.io.fpu.bits.vs1Data
  divAlu.io.in.bits.vs2 := dispatch.io.fpu.bits.vs2Data
  divAlu.io.in.bits.vs1Field := fpuVs1
  divAlu.io.in.bits.scalarFpData := dispatch.io.fpu.bits.scalarFpData
  divAlu.io.in.bits.roundingMode := vectorState.frm
  divAlu.io.in.bits.funct6 :=
    dispatch.io.fpu.bits.decode.decoded.funct6
  divAlu.io.in.bits.operandType :=
    dispatch.io.fpu.bits.decode.decoded.operandType
  divAlu.io.in.bits.vm :=
    dispatch.io.fpu.bits.decode.decoded.vm

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
  private val completingVector = commitFire
  private val completingFault = io.memoryFault.fire
  private val completingConfigWarp =
    completingVector &&
      commitBits.writeback.warpId === configWarp
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
  writebackArbiter.io.in(0).bits.flags := 0.U
  writebackArbiter.io.in(0).bits.writesFlags := false.B
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
  writebackArbiter.io.in(1).bits.flags := 0.U
  writebackArbiter.io.in(1).bits.writesFlags := false.B
  writebackArbiter.io.in(1).bits.pc := multiplyAlu.io.out.bits.pc
  writebackArbiter.io.in(1).bits.warpActiveMask :=
    multiplyAlu.io.out.bits.warpActiveMask

  private val fpuFlat = fpuAlu.io.out.bits.data.asUInt
  private val fpuPackedMask = Cat(
    fpuFlat(vectorWidth - 1, config.lanes),
    fpuAlu.io.out.bits.mask
  ).asTypeOf(Vec(config.lanes, UInt(config.xLen.W)))
  writebackArbiter.io.in(2).valid := fpuAlu.io.out.valid
  fpuAlu.io.out.ready := writebackArbiter.io.in(2).ready
  writebackArbiter.io.in(2).bits.writeback.warpId :=
    fpuAlu.io.out.bits.warpId
  writebackArbiter.io.in(2).bits.writeback.vd :=
    fpuAlu.io.out.bits.vd
  writebackArbiter.io.in(2).bits.writeback.data :=
    Mux(
      fpuAlu.io.out.bits.writesMask,
      fpuPackedMask.asUInt,
      fpuAlu.io.out.bits.data.asUInt
    ).asTypeOf(Vec(config.lanes, UInt(config.xLen.W)))
  writebackArbiter.io.in(2).bits.saturated :=
    fpuAlu.io.out.bits.saturated
  writebackArbiter.io.in(2).bits.writesVd := true.B
  writebackArbiter.io.in(2).bits.flags := fpuAlu.io.out.bits.flags
  writebackArbiter.io.in(2).bits.writesFlags :=
    fpuAlu.io.out.bits.writesFlags
  writebackArbiter.io.in(2).bits.pc := fpuAlu.io.out.bits.pc
  writebackArbiter.io.in(2).bits.warpActiveMask :=
    fpuAlu.io.out.bits.warpActiveMask

  writebackArbiter.io.in(3).valid := fmaAlu.io.out.valid
  fmaAlu.io.out.ready := writebackArbiter.io.in(3).ready
  writebackArbiter.io.in(3).bits.writeback.warpId :=
    fmaAlu.io.out.bits.warpId
  writebackArbiter.io.in(3).bits.writeback.vd :=
    fmaAlu.io.out.bits.vd
  writebackArbiter.io.in(3).bits.writeback.data :=
    fmaAlu.io.out.bits.data
  writebackArbiter.io.in(3).bits.saturated := false.B
  writebackArbiter.io.in(3).bits.writesVd := true.B
  writebackArbiter.io.in(3).bits.flags := fmaAlu.io.out.bits.flags
  writebackArbiter.io.in(3).bits.writesFlags :=
    fmaAlu.io.out.bits.writesFlags
  writebackArbiter.io.in(3).bits.pc := fmaAlu.io.out.bits.pc
  writebackArbiter.io.in(3).bits.warpActiveMask :=
    fmaAlu.io.out.bits.warpActiveMask

  private val memPipeValid = RegInit(false.B)
  private val memPipeBits = Reg(new VectorMemoryResult(config))
  memory.io.out.ready := !memPipeValid
  when(memory.io.out.fire) {
    memPipeValid := true.B
    memPipeBits := memory.io.out.bits
  }
  private val memPipeSucceeded =
    memPipeBits.faultMask === 0.U
  private val memPipeOutReady = Mux(
    memPipeSucceeded,
    writebackArbiter.io.in(4).ready,
    io.memoryFault.ready
  )
  when(memPipeValid && memPipeOutReady) {
    memPipeValid := false.B
  }
  writebackArbiter.io.in(4).valid :=
    memPipeValid && memPipeSucceeded
  io.memoryFault.valid := memPipeValid && !memPipeSucceeded
  io.memoryFault.bits.warpId := memPipeBits.warpId
  io.memoryFault.bits.pc := memPipeBits.pc
  io.memoryFault.bits.warpActiveMask :=
    memPipeBits.warpActiveMask
  io.memoryFault.bits.faultMask := memPipeBits.faultMask
  io.memoryFault.bits.pageFault := memPipeBits.pageFault
  io.memoryFault.bits.isStore := memPipeBits.isStore
  io.memoryFault.bits.addresses := memPipeBits.addresses
  io.memoryFault.bits.vd := memPipeBits.vd
  io.memoryFault.bits.writeVd := memPipeBits.writesVd
  writebackArbiter.io.in(4).bits.writeback.warpId :=
    memPipeBits.warpId
  writebackArbiter.io.in(4).bits.writeback.vd := memPipeBits.vd
  writebackArbiter.io.in(4).bits.writeback.data := memPipeBits.data
  writebackArbiter.io.in(4).bits.saturated := false.B
  writebackArbiter.io.in(4).bits.writesVd :=
    memPipeBits.writesVd
  writebackArbiter.io.in(4).bits.flags := 0.U
  writebackArbiter.io.in(4).bits.writesFlags := false.B
  writebackArbiter.io.in(4).bits.pc := memPipeBits.pc
  writebackArbiter.io.in(4).bits.warpActiveMask :=
    memPipeBits.warpActiveMask

  writebackArbiter.io.in(5).valid := divAlu.io.out.valid
  divAlu.io.out.ready := writebackArbiter.io.in(5).ready
  writebackArbiter.io.in(5).bits.writeback.warpId :=
    divAlu.io.out.bits.warpId
  writebackArbiter.io.in(5).bits.writeback.vd :=
    divAlu.io.out.bits.vd
  writebackArbiter.io.in(5).bits.writeback.data :=
    divAlu.io.out.bits.data
  writebackArbiter.io.in(5).bits.saturated := false.B
  writebackArbiter.io.in(5).bits.writesVd := true.B
  writebackArbiter.io.in(5).bits.flags := divAlu.io.out.bits.flags
  writebackArbiter.io.in(5).bits.writesFlags :=
    divAlu.io.out.bits.writesFlags
  writebackArbiter.io.in(5).bits.pc := divAlu.io.out.bits.pc
  writebackArbiter.io.in(5).bits.warpActiveMask :=
    divAlu.io.out.bits.warpActiveMask

  writebackArbiter.io.in(6).valid := cvtAlu.io.out.valid
  cvtAlu.io.out.ready := writebackArbiter.io.in(6).ready
  writebackArbiter.io.in(6).bits.writeback.warpId :=
    cvtAlu.io.out.bits.warpId
  writebackArbiter.io.in(6).bits.writeback.vd :=
    cvtAlu.io.out.bits.vd
  writebackArbiter.io.in(6).bits.writeback.data :=
    cvtAlu.io.out.bits.data
  writebackArbiter.io.in(6).bits.saturated := false.B
  writebackArbiter.io.in(6).bits.writesVd := true.B
  writebackArbiter.io.in(6).bits.flags := cvtAlu.io.out.bits.flags
  writebackArbiter.io.in(6).bits.writesFlags :=
    cvtAlu.io.out.bits.writesFlags
  writebackArbiter.io.in(6).bits.pc := cvtAlu.io.out.bits.pc
  writebackArbiter.io.in(6).bits.warpActiveMask :=
    cvtAlu.io.out.bits.warpActiveMask

  writebackArbiter.io.in(7).valid := divideAlu.io.out.valid
  divideAlu.io.out.ready := writebackArbiter.io.in(7).ready
  writebackArbiter.io.in(7).bits.writeback.warpId :=
    divideAlu.io.out.bits.warpId
  writebackArbiter.io.in(7).bits.writeback.vd :=
    divideAlu.io.out.bits.vd
  writebackArbiter.io.in(7).bits.writeback.data :=
    divideAlu.io.out.bits.data
  writebackArbiter.io.in(7).bits.saturated := false.B
  writebackArbiter.io.in(7).bits.writesVd := true.B
  writebackArbiter.io.in(7).bits.flags := 0.U
  writebackArbiter.io.in(7).bits.writesFlags := false.B
  writebackArbiter.io.in(7).bits.pc := divideAlu.io.out.bits.pc
  writebackArbiter.io.in(7).bits.warpActiveMask :=
    divideAlu.io.out.bits.warpActiveMask

  writebackArbiter.io.in(8).valid := fsqrtAlu.io.out.valid
  fsqrtAlu.io.out.ready := writebackArbiter.io.in(8).ready
  writebackArbiter.io.in(8).bits.writeback.warpId :=
    fsqrtAlu.io.out.bits.warpId
  writebackArbiter.io.in(8).bits.writeback.vd :=
    fsqrtAlu.io.out.bits.vd
  writebackArbiter.io.in(8).bits.writeback.data :=
    fsqrtAlu.io.out.bits.data
  writebackArbiter.io.in(8).bits.saturated := false.B
  writebackArbiter.io.in(8).bits.writesVd := true.B
  writebackArbiter.io.in(8).bits.flags := fsqrtAlu.io.out.bits.flags
  writebackArbiter.io.in(8).bits.writesFlags :=
    fsqrtAlu.io.out.bits.writesFlags
  writebackArbiter.io.in(8).bits.pc := fsqrtAlu.io.out.bits.pc
  writebackArbiter.io.in(8).bits.warpActiveMask :=
    fsqrtAlu.io.out.bits.warpActiveMask

  writebackArbiter.io.in(9).valid := estimateAlu.io.out.valid
  estimateAlu.io.out.ready := writebackArbiter.io.in(9).ready
  writebackArbiter.io.in(9).bits.writeback.warpId :=
    estimateAlu.io.out.bits.warpId
  writebackArbiter.io.in(9).bits.writeback.vd :=
    estimateAlu.io.out.bits.vd
  writebackArbiter.io.in(9).bits.writeback.data :=
    estimateAlu.io.out.bits.data
  writebackArbiter.io.in(9).bits.saturated := false.B
  writebackArbiter.io.in(9).bits.writesVd := true.B
  writebackArbiter.io.in(9).bits.flags := estimateAlu.io.out.bits.flags
  writebackArbiter.io.in(9).bits.writesFlags :=
    estimateAlu.io.out.bits.writesFlags
  writebackArbiter.io.in(9).bits.pc := estimateAlu.io.out.bits.pc
  writebackArbiter.io.in(9).bits.warpActiveMask :=
    estimateAlu.io.out.bits.warpActiveMask

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
    !commitValid &&
      initializeWarpValid && initializeWarpIdle
  // The queue makes completion a registered scheduler event and cuts the
  // completion-arbiter ready path. A vset* scalar write and its queued
  // completion are accepted atomically.
  private val selectVector = commitValid
  private val selectConfiguration =
    !selectVector && configuration.io.out.valid
  completionQueue.io.enq.valid :=
    commitValid ||
      (selectConfiguration && io.scalarWriteback.ready)
  completionQueue.io.enq.bits.warpId := Mux(
    selectVector,
    commitBits.writeback.warpId,
    configuration.io.out.bits.warpId
  )
  completionQueue.io.enq.bits.pc := Mux(
    selectVector,
    commitBits.pc,
    configuration.io.out.bits.pc
  ) + 4.U
  completionQueue.io.enq.bits.activeMask := Mux(
    selectVector,
    commitBits.warpActiveMask,
    configuration.io.out.bits.warpActiveMask
  )
  io.redirect <> completionQueue.io.deq
  writebackArbiter.io.out.ready := !commitValid || commitFire
  issue.io.vectorWriteback.valid :=
    (commitFire && commitBits.writesVd) || io.initialize.fire
  issue.io.vectorWriteback.bits := Mux(
    commitFire,
    commitBits.writeback,
    io.initialize.bits
  )
  issue.io.cancel.valid := io.memoryFault.fire && io.memoryFault.bits.writeVd
  issue.io.cancel.bits.warpId := io.memoryFault.bits.warpId
  issue.io.cancel.bits.vd := io.memoryFault.bits.vd
  io.committedVectorWriteback.valid :=
    commitFire && commitBits.writesVd
  io.committedVectorWriteback.bits := commitBits.writeback
  io.committedVectorFlags.valid :=
    commitFire && commitBits.writesFlags
  io.committedVectorFlags.bits.warpId :=
    commitBits.writeback.warpId
  io.committedVectorFlags.bits.flags :=
    commitBits.flags
  configuration.io.flagsWrite.valid := io.committedVectorFlags.valid
  configuration.io.flagsWrite.bits.warpId :=
    io.committedVectorFlags.bits.warpId
  configuration.io.flagsWrite.bits.flags :=
    io.committedVectorFlags.bits.flags
  configuration.io.scalarFlagsWrite := io.scalarFlagsWrite

  configuration.io.csrWrite.valid :=
    commitFire && commitBits.saturated
  configuration.io.csrWrite.bits.warpId :=
    commitBits.writeback.warpId
  configuration.io.csrWrite.bits.address := "h009".U
  configuration.io.csrWrite.bits.data := 1.U

  private val startingVector =
    dispatch.io.alu.fire || dispatch.io.multiply.fire ||
      dispatch.io.divide.fire || dispatch.io.fpu.fire ||
      dispatch.io.memory.fire
  private val startingWarp = Mux(
    dispatch.io.memory.fire,
    dispatch.io.memory.bits.decode.warpId,
    Mux(
      dispatch.io.fpu.fire,
      dispatch.io.fpu.bits.decode.warpId,
      Mux(
        dispatch.io.divide.fire,
        dispatch.io.divide.bits.decode.warpId,
        Mux(
          dispatch.io.multiply.fire,
          dispatch.io.multiply.bits.decode.warpId,
          dispatch.io.alu.bits.decode.warpId
        )
      )
    )
  )
  for (warp <- 0 until config.warps) {
    val increment = startingVector && startingWarp === warp.U
    val decrement =
      (completingVector &&
        commitBits.writeback.warpId === warp.U) ||
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

  io.rawHazard := RegNext(issue.io.rawHazard)
  io.wawHazard := RegNext(issue.io.wawHazard)
}
