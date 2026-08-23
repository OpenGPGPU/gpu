package gpu.core.trap

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.backend.issue.ScalarIssuedInstruction
import gpu.core.memory.{ScalarMemoryFault, VectorMemoryFault}

object TrapCause {
  val instructionAccessFault = 1
  val illegalInstruction = 2
  val loadAddressMisaligned = 4
  val loadAccessFault = 5
  val storeAddressMisaligned = 6
  val storeAccessFault = 7
  val loadPageFault = 13
  val storePageFault = 15
}

class CoreTrapEvent(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val activeMask = UInt(config.lanes.W)
  val cause = UInt(5.W)
  val tval = UInt(config.xLen.W)
  val laneFaultMask = UInt(config.lanes.W)
}

/** Fairly arbitrates scalar decode faults and vector memory faults. */
class CoreTrapArbiter(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val scalar = Flipped(Decoupled(new ScalarIssuedInstruction(config)))
    val vector = Flipped(Decoupled(new VectorMemoryFault(config)))
    val scalarMemory = Flipped(Decoupled(new ScalarMemoryFault(config)))
    val fpuMemory = Flipped(Decoupled(new ScalarMemoryFault(config)))
    val out = Decoupled(new CoreTrapEvent(config))
  })

  private val scalarEvent = Wire(new CoreTrapEvent(config))
  scalarEvent.warpId := io.scalar.bits.decode.warpId
  scalarEvent.pc := io.scalar.bits.decode.pc
  scalarEvent.activeMask := io.scalar.bits.decode.activeMask
  scalarEvent.cause := Mux(
    io.scalar.bits.decode.instructionAccessFault,
    TrapCause.instructionAccessFault.U,
    TrapCause.illegalInstruction.U
  )
  scalarEvent.tval := Mux(
    io.scalar.bits.decode.instructionAccessFault,
    io.scalar.bits.decode.pc,
    io.scalar.bits.decode.instruction
  )
  scalarEvent.laneFaultMask := io.scalar.bits.decode.activeMask

  private val firstFaultLane = PriorityEncoder(io.vector.bits.faultMask)
  private val vectorEvent = Wire(new CoreTrapEvent(config))
  vectorEvent.warpId := io.vector.bits.warpId
  vectorEvent.pc := io.vector.bits.pc
  vectorEvent.activeMask := io.vector.bits.warpActiveMask
  vectorEvent.cause := Mux(
    io.vector.bits.pageFault,
    Mux(
      io.vector.bits.isStore,
      TrapCause.storePageFault.U,
      TrapCause.loadPageFault.U
    ),
    Mux(
      io.vector.bits.isStore,
      TrapCause.storeAccessFault.U,
      TrapCause.loadAccessFault.U
    )
  )
  vectorEvent.tval := io.vector.bits.addresses(firstFaultLane)
  vectorEvent.laneFaultMask := io.vector.bits.faultMask

  private val memoryEvent = Wire(new CoreTrapEvent(config))
  memoryEvent.warpId := io.scalarMemory.bits.warpId
  memoryEvent.pc := io.scalarMemory.bits.pc
  memoryEvent.activeMask := io.scalarMemory.bits.activeMask
  memoryEvent.cause := Mux(
    io.scalarMemory.bits.misaligned,
    Mux(io.scalarMemory.bits.isStore, TrapCause.storeAddressMisaligned.U, TrapCause.loadAddressMisaligned.U),
    Mux(
      io.scalarMemory.bits.pageFault,
      Mux(io.scalarMemory.bits.isStore, TrapCause.storePageFault.U, TrapCause.loadPageFault.U),
      Mux(io.scalarMemory.bits.isStore, TrapCause.storeAccessFault.U, TrapCause.loadAccessFault.U)
    )
  )
  memoryEvent.tval := io.scalarMemory.bits.address
  memoryEvent.laneFaultMask := io.scalarMemory.bits.activeMask

  private val fpuMemoryEvent = Wire(new CoreTrapEvent(config))
  fpuMemoryEvent.warpId := io.fpuMemory.bits.warpId
  fpuMemoryEvent.pc := io.fpuMemory.bits.pc
  fpuMemoryEvent.activeMask := io.fpuMemory.bits.activeMask
  fpuMemoryEvent.cause := Mux(
    io.fpuMemory.bits.misaligned,
    Mux(io.fpuMemory.bits.isStore,
      TrapCause.storeAddressMisaligned.U,
      TrapCause.loadAddressMisaligned.U),
    Mux(
      io.fpuMemory.bits.pageFault,
      Mux(io.fpuMemory.bits.isStore,
        TrapCause.storePageFault.U,
        TrapCause.loadPageFault.U),
      Mux(io.fpuMemory.bits.isStore,
        TrapCause.storeAccessFault.U,
        TrapCause.loadAccessFault.U)
    )
  )
  fpuMemoryEvent.tval := io.fpuMemory.bits.address
  fpuMemoryEvent.laneFaultMask := io.fpuMemory.bits.activeMask

  private val arbiter = Module(new RRArbiter(new CoreTrapEvent(config), 4))
  arbiter.io.in(0).valid := io.scalar.valid
  arbiter.io.in(0).bits := scalarEvent
  io.scalar.ready := arbiter.io.in(0).ready
  arbiter.io.in(1).valid := io.vector.valid
  arbiter.io.in(1).bits := vectorEvent
  io.vector.ready := arbiter.io.in(1).ready
  arbiter.io.in(2).valid := io.scalarMemory.valid
  arbiter.io.in(2).bits := memoryEvent
  io.scalarMemory.ready := arbiter.io.in(2).ready
  arbiter.io.in(3).valid := io.fpuMemory.valid
  arbiter.io.in(3).bits := fpuMemoryEvent
  io.fpuMemory.ready := arbiter.io.in(3).ready
  io.out <> arbiter.io.out
}
