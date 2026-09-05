package opengpu.core

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.ScalarBackend
import opengpu.core.backend.{VectorBackend, VectorCommitRequest, VectorTextureRequest}
import opengpu.core.backend.{FpuBackend, FpuFlags}
import opengpu.core.backend.issue.{FpuIssuedInstruction, ScalarIssuedInstruction, VectorIssuedInstruction}
import opengpu.core.backend.register.{FpuRegisterWrite, ScalarRegisterWrite}
import opengpu.core.backend.writeback.ScalarCommitRequest
import opengpu.core.backend.scoreboard.RegisterReservation
import opengpu.core.execute.control.{SimtBranchRequest, SimtPath}
import opengpu.core.frontend._
import opengpu.core.frontend.decode.{FpuDecodeResponse, VectorDecodeResponse}
import opengpu.core.frontend.warp.WarpLaunch
import opengpu.core.trap.{CoreTrapArbiter, CoreTrapEvent}
import opengpu.core.memory.{
  BankedSharedMemory,
  VectorMemorySpaceRouter,
  VectorDataCache,
  VectorLowerMemoryRequest,
  VectorLowerMemoryResponse,
  VectorMemoryCoalescer,
  PageTableMemoryRequest,
  PageTableMemoryResponse,
  Sv32PageTableWalker,
  SharedCacheLinePort,
  VectorTlb,
  VectorTlbFlush,
  CacheLineInvalidate,
  SharedAtomicRequest,
  SharedAtomicResponse
}

/** Current synthesizable GPU core integration boundary.
  *
  * The RV32I scalar loop is connected from fetch through commit and back to
  * warp scheduling. Unimplemented execution families remain explicit ports.
  */
class GpuCore(
  config: GpuConfig = GpuConfig(),
  useBlackBoxes: Boolean = false,
  enableFpuBackend: Boolean = false,
  vectorCacheSets: Int = 64,
  vectorCacheWays: Int = 2
) extends Module {
  val io = IO(new Bundle {
    val launch = Flipped(Decoupled(new WarpLaunch(config)))
    val scalarInitialize =
      Flipped(Decoupled(new ScalarRegisterWrite(config)))
    val fetchRequest = Decoupled(new InstructionFetchRequest(config))
    val fetchResponse = Flipped(Decoupled(new InstructionFetchResponse(config)))

    val fpu = Decoupled(new FpuDecodeResponse(config))
    val fpuInitialize = Flipped(Decoupled(new FpuRegisterWrite(config)))
    val committedFpuWriteback = Valid(new FpuRegisterWrite(config))
    val committedFpuFlags = Valid(new FpuFlags(config))
    val committedFpuIntegerWriteback = Valid(new ScalarRegisterWrite(config))
    val vector = Decoupled(new VectorIssuedInstruction(config))
    val vectorInitialize =
      Flipped(Decoupled(new opengpu.core.backend.register.VectorRegisterWrite(config)))
    val committedVectorWriteback =
      Valid(new opengpu.core.backend.register.VectorRegisterWrite(config))
    val vectorMemoryRequest =
      Decoupled(new VectorLowerMemoryRequest(config))
    val vectorMemoryResponse =
      Flipped(Decoupled(new VectorLowerMemoryResponse()))
    val vectorSatp = Input(UInt(32.W))
    val vectorTlbFlush = Flipped(Valid(new VectorTlbFlush(config)))
    val vectorPageTableRequest =
      Decoupled(new PageTableMemoryRequest(config))
    val vectorPageTableResponse =
      Flipped(Decoupled(new PageTableMemoryResponse))
    val memory = Decoupled(new ScalarIssuedInstruction(config))
    val system = Decoupled(new ScalarIssuedInstruction(config))
    val trap = Decoupled(new CoreTrapEvent(config))
    val faultResume = Flipped(Decoupled(new SimtPath(config)))

    val simtBranch = Flipped(Decoupled(new SimtBranchRequest(config)))
    val restore = Flipped(Decoupled(UInt(config.warpIdWidth.W)))
    val finish = Flipped(Valid(UInt(config.warpIdWidth.W)))
    val committedWriteback = Valid(new ScalarRegisterWrite(config))
    /** tex.sample commit sideband (see WarpSystemControl / TexSampleUnit). */
    val texCommit = Flipped(Decoupled(new ScalarCommitRequest(config)))
    /** vtex.sample execute-side handoff and vector commit sideband. */
    val vectorTexSample = Decoupled(new VectorTextureRequest(config))
    val vectorTexCommit = Flipped(Decoupled(new VectorCommitRequest(config)))
    val active = Output(UInt(config.warps.W))
    val blocked = Output(UInt(config.warps.W))
    val sharedMemoryIdle = Output(Bool())
    val l1Invalidate = Flipped(Decoupled(new CacheLineInvalidate(config)))
    val l1InvalidateDone = Decoupled(new CacheLineInvalidate(config))
    val globalAtomicRequest = Decoupled(new SharedAtomicRequest(config))
    val globalAtomicResponse = Flipped(Decoupled(new SharedAtomicResponse(config)))
  })

  private val frontend = Module(new GpuFrontend(config))
  private val scalar = Module(new ScalarBackend(config, useBlackBoxes))
  private val vector = Module(new VectorBackend(config, useBlackBoxes))
  private val scalarVectorRead =
    Module(new opengpu.core.backend.register.ScalarRegisterFile(config))
  private val scalarFpuRead =
    Module(new opengpu.core.backend.register.ScalarRegisterFile(config))
  private val vectorCoalescer = Module(new VectorMemoryCoalescer(config))
  private val vectorMemoryRouter = Module(new VectorMemorySpaceRouter(config))
  private val sharedMemory = Module(new BankedSharedMemory(config))
  private val vectorDataCache = Module(
    new VectorDataCache(config, sets = vectorCacheSets, ways = vectorCacheWays))
  private val vectorTlb = Module(new VectorTlb(config))
  private val vectorPageTableWalker = Module(new Sv32PageTableWalker(config))
  private val trapArbiter = Module(new CoreTrapArbiter(config))
  private val sharedCachePort = Module(new SharedCacheLinePort(config))

  frontend.io.launch <> io.launch
  io.fetchRequest <> frontend.io.fetchRequest
  frontend.io.fetchResponse <> io.fetchResponse
  scalar.io.in <> frontend.io.scalarOut
  scalar.io.texCommit <> io.texCommit
  scalar.io.initialize <> io.scalarInitialize
  private val completionArbiter = Module(new RRArbiter(
    new SimtPath(config), if (enableFpuBackend && config.enableFpu) 4 else 3))
  completionArbiter.io.in(0) <> scalar.io.redirect
  completionArbiter.io.in(1) <> vector.io.redirect
  completionArbiter.io.in(2) <> io.faultResume
  io.fpuInitialize.ready := false.B
  io.committedFpuWriteback.valid := false.B
  io.committedFpuWriteback.bits := 0.U.asTypeOf(io.committedFpuWriteback.bits)
  io.committedFpuFlags.valid := false.B
  io.committedFpuFlags.bits := 0.U.asTypeOf(io.committedFpuFlags.bits)
  io.committedFpuIntegerWriteback.valid := false.B
  io.committedFpuIntegerWriteback.bits :=
    0.U.asTypeOf(io.committedFpuIntegerWriteback.bits)

  if (enableFpuBackend && config.enableFpu) {
    val fpu = Module(new FpuBackend(config))
    fpu.io.in <> frontend.io.fpuOut
    fpu.io.unimplemented.ready := io.fpu.ready
    io.fpu.valid := fpu.io.unimplemented.valid
    io.fpu.bits := fpu.io.unimplemented.bits.decode
    fpu.io.initialize <> io.fpuInitialize
    io.committedFpuWriteback := fpu.io.committedWriteback
    io.committedFpuFlags.valid :=
      fpu.io.committedFlags.valid || vector.io.committedVectorFlags.valid
    io.committedFpuFlags.bits := Mux(
      fpu.io.committedFlags.valid,
      fpu.io.committedFlags.bits,
      vector.io.committedVectorFlags.bits
    )
    io.committedFpuIntegerWriteback.valid := fpu.io.scalarWriteback.valid
    io.committedFpuIntegerWriteback.bits := fpu.io.scalarWriteback.bits
    fpu.io.flush := false.B
    fpu.io.fvfRead := vector.io.scalarFpRead
    fpu.io.frm := vector.io.frm
    vector.io.scalarFpData := fpu.io.fvfData
    vector.io.scalarFpBusy := fpu.io.fpuBusyByWarp
    vector.io.scalarFlagsWrite.valid := fpu.io.committedFlags.valid
    vector.io.scalarFlagsWrite.bits.warpId :=
      fpu.io.committedFlags.bits.warpId
    vector.io.scalarFlagsWrite.bits.flags :=
      fpu.io.committedFlags.bits.flags
    completionArbiter.io.in(3) <> fpu.io.redirect
    scalarFpuRead.io.read := fpu.io.scalarRead
    fpu.io.scalarRs1Data := scalarFpuRead.io.rs1Data
    sharedCachePort.io.fpuRequest <> fpu.io.memoryRequest
    fpu.io.memoryResponse <> sharedCachePort.io.fpuResponse
    trapArbiter.io.fpuMemory <> fpu.io.memoryFault
    val fpuScalarReserveArbiter = Module(new RRArbiter(
      new RegisterReservation(config), 2))
    fpuScalarReserveArbiter.io.in(0) <> fpu.io.scalarReserve
    fpuScalarReserveArbiter.io.in(1) <> vector.io.scalarReserve
    scalar.io.externalReserve <> fpuScalarReserveArbiter.io.out
    val fpuScalarWritebackArbiter = Module(new RRArbiter(
      new ScalarRegisterWrite(config), 2))
    fpuScalarWritebackArbiter.io.in(0) <> fpu.io.scalarWriteback
    fpuScalarWritebackArbiter.io.in(1) <> vector.io.scalarWriteback
    scalar.io.externalWriteback <> fpuScalarWritebackArbiter.io.out
  } else {
    io.fpu <> frontend.io.fpuOut
    io.committedFpuFlags := vector.io.committedVectorFlags
    scalar.io.externalWriteback <> vector.io.scalarWriteback
    scalar.io.externalReserve <> vector.io.scalarReserve
    vector.io.scalarFpData := 0.U
    vector.io.scalarFpBusy := 0.U.asTypeOf(vector.io.scalarFpBusy)
    vector.io.scalarFlagsWrite.valid := false.B
    vector.io.scalarFlagsWrite.bits.warpId := 0.U
    vector.io.scalarFlagsWrite.bits.flags := 0.U
    scalarFpuRead.io.read := 0.U.asTypeOf(scalarFpuRead.io.read)
    sharedCachePort.io.fpuRequest.valid := false.B
    sharedCachePort.io.fpuRequest.bits :=
      0.U.asTypeOf(sharedCachePort.io.fpuRequest.bits)
    sharedCachePort.io.fpuResponse.ready := true.B
    trapArbiter.io.fpuMemory.valid := false.B
    trapArbiter.io.fpuMemory.bits :=
      0.U.asTypeOf(trapArbiter.io.fpuMemory.bits)
  }
  scalarFpuRead.io.write.valid := scalar.io.appliedWriteback.valid
  scalarFpuRead.io.write.bits := scalar.io.appliedWriteback.bits
  frontend.io.scalarRedirect <> completionArbiter.io.out
  vector.io.in <> frontend.io.vectorOut
  io.vectorTexSample <> vector.io.texSample
  vector.io.texCommit <> io.vectorTexCommit
  io.vector <> vector.io.unimplemented
  vector.io.initialize <> io.vectorInitialize
  io.committedVectorWriteback := vector.io.committedVectorWriteback
  vectorMemoryRouter.io.in <> vector.io.memoryRequest
  vectorCoalescer.io.in <> vectorMemoryRouter.io.globalRequest
  vectorMemoryRouter.io.globalResponse <> vectorCoalescer.io.out
  sharedMemory.io.in <> vectorMemoryRouter.io.localRequest
  vectorMemoryRouter.io.localResponse <> sharedMemory.io.out
  sharedMemory.io.atomicIn <> scalar.io.sharedAtomicRequest
  scalar.io.sharedAtomicResponse <> sharedMemory.io.atomicOut
  io.globalAtomicRequest <> scalar.io.globalAtomicRequest
  scalar.io.globalAtomicResponse <> io.globalAtomicResponse
  vector.io.memoryResponse <> vectorMemoryRouter.io.out
  sharedCachePort.io.vectorRequest <> vectorCoalescer.io.cacheRequest
  vectorCoalescer.io.cacheResponse <> sharedCachePort.io.vectorResponse
  sharedCachePort.io.scalarRequest <> scalar.io.cacheRequest
  scalar.io.cacheResponse <> sharedCachePort.io.scalarResponse
  vectorTlb.io.in <> sharedCachePort.io.sharedRequest
  sharedCachePort.io.sharedResponse <> vectorTlb.io.out
  vectorDataCache.io.in <> vectorTlb.io.physicalRequest
  vectorTlb.io.physicalResponse <> vectorDataCache.io.out
  vectorTlb.io.translationEnabled := io.vectorSatp(31)
  vectorTlb.io.asid := io.vectorSatp(30, 22)
  vectorTlb.io.flush := io.vectorTlbFlush
  vectorPageTableWalker.io.rootPpn := io.vectorSatp(19, 0)
  vectorPageTableWalker.io.request <> vectorTlb.io.pageWalkRequest
  vectorTlb.io.pageWalkResponse <> vectorPageTableWalker.io.response
  io.vectorPageTableRequest <> vectorPageTableWalker.io.memoryRequest
  vectorPageTableWalker.io.memoryResponse <> io.vectorPageTableResponse
  io.vectorMemoryRequest <> vectorDataCache.io.lowerRequest
  vectorDataCache.io.lowerResponse <> io.vectorMemoryResponse
  vectorDataCache.io.invalidate <> io.l1Invalidate
  io.l1InvalidateDone <> vectorDataCache.io.invalidateDone
  trapArbiter.io.vector <> vector.io.memoryFault

  scalarVectorRead.io.read := vector.io.scalarRead
  vector.io.scalarRs1Data := scalarVectorRead.io.rs1Data
  vector.io.scalarRs2Data := scalarVectorRead.io.rs2Data
  scalarVectorRead.io.write.valid := scalar.io.appliedWriteback.valid
  scalarVectorRead.io.write.bits := scalar.io.appliedWriteback.bits
  io.memory <> scalar.io.memory
  io.system <> scalar.io.system
  trapArbiter.io.scalar <> scalar.io.trap
  trapArbiter.io.scalarMemory <> scalar.io.memoryFault
  io.trap <> trapArbiter.io.out

  frontend.io.branch <> io.simtBranch
  frontend.io.restore <> io.restore
  frontend.io.finish <> io.finish
  io.committedWriteback := scalar.io.committedWriteback
  io.active := frontend.io.active
  io.blocked := frontend.io.blocked
  io.sharedMemoryIdle := sharedMemory.io.idle
}
