package gpu.core

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.backend.ScalarBackend
import gpu.core.backend.VectorBackend
import gpu.core.backend.{FpuBackend, FpuFlags}
import gpu.core.backend.issue.{FpuIssuedInstruction, ScalarIssuedInstruction, VectorIssuedInstruction}
import gpu.core.backend.register.{FpuRegisterWrite, ScalarRegisterWrite}
import gpu.core.execute.control.{SimtBranchRequest, SimtPath}
import gpu.core.frontend._
import gpu.core.frontend.decode.{FpuDecodeResponse, VectorDecodeResponse}
import gpu.core.frontend.warp.WarpLaunch
import gpu.core.trap.{CoreTrapArbiter, CoreTrapEvent}
import gpu.core.memory.{
  VectorDataCache,
  VectorLowerMemoryRequest,
  VectorLowerMemoryResponse,
  VectorMemoryCoalescer,
  PageTableMemoryRequest,
  PageTableMemoryResponse,
  Sv32PageTableWalker,
  SharedCacheLinePort,
  VectorTlb,
  VectorTlbFlush
}

/** Current synthesizable GPU core integration boundary.
  *
  * The RV32I scalar loop is connected from fetch through commit and back to
  * warp scheduling. Unimplemented execution families remain explicit ports.
  */
class GpuCore(
  config: GpuConfig = GpuConfig(),
  useBlackBoxes: Boolean = false,
  enableFpuBackend: Boolean = false
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
    val vector = Decoupled(new VectorIssuedInstruction(config))
    val vectorInitialize =
      Flipped(Decoupled(new gpu.core.backend.register.VectorRegisterWrite(config)))
    val committedVectorWriteback =
      Valid(new gpu.core.backend.register.VectorRegisterWrite(config))
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
    val active = Output(UInt(config.warps.W))
    val blocked = Output(UInt(config.warps.W))
  })

  private val frontend = Module(new GpuFrontend(config))
  private val scalar = Module(new ScalarBackend(config, useBlackBoxes))
  private val vector = Module(new VectorBackend(config))
  private val scalarVectorRead =
    Module(new gpu.core.backend.register.ScalarRegisterFile(config))
  private val vectorCoalescer = Module(new VectorMemoryCoalescer(config))
  private val vectorDataCache = Module(new VectorDataCache(config))
  private val vectorTlb = Module(new VectorTlb(config))
  private val vectorPageTableWalker = Module(new Sv32PageTableWalker(config))
  private val trapArbiter = Module(new CoreTrapArbiter(config))
  private val sharedCachePort = Module(new SharedCacheLinePort(config))

  frontend.io.launch <> io.launch
  io.fetchRequest <> frontend.io.fetchRequest
  frontend.io.fetchResponse <> io.fetchResponse
  scalar.io.in <> frontend.io.scalarOut
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

  if (enableFpuBackend && config.enableFpu) {
    val fpu = Module(new FpuBackend(config))
    fpu.io.in <> frontend.io.fpuOut
    fpu.io.unimplemented.ready := io.fpu.ready
    io.fpu.valid := fpu.io.unimplemented.valid
    io.fpu.bits := fpu.io.unimplemented.bits.decode
    fpu.io.initialize <> io.fpuInitialize
    io.committedFpuWriteback := fpu.io.committedWriteback
    io.committedFpuFlags := fpu.io.committedFlags
    fpu.io.flush := false.B
    completionArbiter.io.in(3) <> fpu.io.redirect
  } else {
    io.fpu <> frontend.io.fpuOut
  }
  frontend.io.scalarRedirect <> completionArbiter.io.out
  vector.io.in <> frontend.io.vectorOut
  io.vector <> vector.io.unimplemented
  vector.io.initialize <> io.vectorInitialize
  io.committedVectorWriteback := vector.io.committedVectorWriteback
  vectorCoalescer.io.in <> vector.io.memoryRequest
  vector.io.memoryResponse <> vectorCoalescer.io.out
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
  trapArbiter.io.vector <> vector.io.memoryFault

  scalarVectorRead.io.read := vector.io.scalarRead
  vector.io.scalarRs1Data := scalarVectorRead.io.rs1Data
  vector.io.scalarRs2Data := scalarVectorRead.io.rs2Data
  scalarVectorRead.io.write.valid := scalar.io.appliedWriteback.valid
  scalarVectorRead.io.write.bits := scalar.io.appliedWriteback.bits
  scalar.io.externalWriteback <> vector.io.scalarWriteback
  scalar.io.externalReserve <> vector.io.scalarReserve
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
}
