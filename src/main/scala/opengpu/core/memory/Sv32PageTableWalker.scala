package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

class PageTableMemoryRequest(config: GpuConfig) extends Bundle {
  val address = UInt(config.xLen.W)
}

class PageTableMemoryResponse extends Bundle {
  val pte = UInt(32.W)
  val fault = Bool()
}

/** Two-level Sv32 page-table walker for the vector data TLB.
  *
  * Accessed/dirty bits must already be set; hardware PTE updates are not yet
  * attempted. Sv32 permits wider physical addresses, while this RV32 core
  * intentionally keeps a 32-bit physical address and uses PTE PPN[19:0].
  */
class Sv32PageTableWalker(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val rootPpn = Input(UInt(20.W))
    val request = Flipped(Decoupled(new VectorPageWalkRequest(config)))
    val response = Decoupled(new VectorPageWalkResponse(config))
    val memoryRequest = Decoupled(new PageTableMemoryRequest(config))
    val memoryResponse = Flipped(Decoupled(new PageTableMemoryResponse))
  })

  private object State extends ChiselEnum {
    val idle, memoryRequest, memoryResponse, respond = Value
  }
  private val state = RegInit(State.idle)
  private val request = Reg(new VectorPageWalkRequest(config))
  private val level = RegInit(1.U(1.W))
  private val tablePpn = Reg(UInt(20.W))
  private val result = Reg(new VectorPageWalkResponse(config))

  private val vpn1 = request.virtualPageNumber(19, 10)
  private val vpn0 = request.virtualPageNumber(9, 0)
  private val selectedVpn = Mux(level === 1.U, vpn1, vpn0)
  private val pteAddress = Cat(tablePpn, 0.U(12.W)) + (selectedVpn << 2)

  io.request.ready := state === State.idle
  io.response.valid := state === State.respond
  io.response.bits := result
  io.memoryRequest.valid := state === State.memoryRequest
  io.memoryRequest.bits.address := pteAddress
  io.memoryResponse.ready := state === State.memoryResponse

  when(io.request.fire) {
    request := io.request.bits
    tablePpn := io.rootPpn
    level := 1.U
    state := State.memoryRequest
  }
  when(io.memoryRequest.fire) {
    state := State.memoryResponse
  }

  private val pte = io.memoryResponse.bits.pte
  private val pteValid = pte(0)
  private val pteReadable = pte(1)
  private val pteWritable = pte(2)
  private val pteExecutable = pte(3)
  private val pteAccessed = pte(6)
  private val pteDirty = pte(7)
  private val pteGlobal = pte(5)
  private val pteLeaf = pteReadable || pteExecutable
  private val invalidEncoding = !pteValid || (!pteReadable && pteWritable)
  private val ptePpn = pte(29, 10)
  private val misalignedSuperpage = level === 1.U && ptePpn(9, 0) =/= 0.U
  private val permissionFault =
    !pteAccessed || Mux(
      request.isInstruction,
      !pteExecutable,
      Mux(request.isStore, !pteWritable || !pteDirty, !pteReadable)
    )

  when(io.memoryResponse.fire) {
    when(io.memoryResponse.bits.fault || invalidEncoding) {
      result.physicalPageNumber := 0.U
      result.readable := false.B
      result.writable := false.B
      result.executable := false.B
      result.global := false.B
      result.fault := true.B
      state := State.respond
    }.elsewhen(pteLeaf) {
      result.physicalPageNumber := Mux(
        level === 1.U,
        Cat(ptePpn(19, 10), vpn0),
        ptePpn
      )
      result.readable := pteReadable && pteAccessed
      result.writable := pteWritable && pteAccessed && pteDirty
      result.executable := pteExecutable && pteAccessed
      result.global := pteGlobal
      result.fault := misalignedSuperpage || permissionFault
      state := State.respond
    }.elsewhen(level === 1.U) {
      tablePpn := ptePpn
      level := 0.U
      state := State.memoryRequest
    }.otherwise {
      result.physicalPageNumber := 0.U
      result.readable := false.B
      result.writable := false.B
      result.executable := false.B
      result.global := false.B
      result.fault := true.B
      state := State.respond
    }
  }

  when(io.response.fire) {
    state := State.idle
  }
}
