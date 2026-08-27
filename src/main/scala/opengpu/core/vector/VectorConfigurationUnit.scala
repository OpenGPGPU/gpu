package opengpu.core.vector

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

class VectorConfigurationRequest(config: GpuConfig) extends Bundle {
  val instruction = UInt(32.W)
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val warpActiveMask = UInt(config.lanes.W)
  val rs1Data = UInt(config.xLen.W)
  val rs2Data = UInt(config.xLen.W)
}

class VectorConfigurationResult(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val warpActiveMask = UInt(config.lanes.W)
  val rd = UInt(5.W)
  val writeRd = Bool()
  val data = UInt(config.xLen.W)
  val vill = Bool()
}

class VectorCsrState(config: GpuConfig) extends Bundle {
  val vl = UInt(config.xLen.W)
  val vtype = UInt(config.xLen.W)
  val vstart = UInt(config.xLen.W)
  val vxrm = UInt(2.W)
  val vxsat = Bool()
  val frm = UInt(3.W)
  val fflags = UInt(5.W)
}

class VectorCsrWrite(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val address = UInt(12.W)
  val data = UInt(config.xLen.W)
}

class VectorFlagsWrite(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val flags = UInt(5.W)
}

/** Per-warp RVV configuration state for the fixed GPU vector profile.
  *
  * The implemented profile is SEW=32, LMUL=1, tail-undisturbed and
  * mask-undisturbed, with VLMAX equal to the hardware lane count. Unsupported
  * vtype values set vill and vl=0 rather than silently selecting a different
  * datapath. Configuration results use an elastic one-entry output.
  */
class VectorConfigurationUnit(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VectorConfigurationRequest(config)))
    val out = Decoupled(new VectorConfigurationResult(config))
    val queryWarpId = Input(UInt(config.warpIdWidth.W))
    val state = Output(new VectorCsrState(config))
    val frmByWarp = Output(Vec(config.warps, UInt(3.W)))
    val csrWrite = Flipped(Valid(new VectorCsrWrite(config)))
    val flagsWrite = Flipped(Valid(new VectorFlagsWrite(config)))
    val scalarFlagsWrite = Flipped(Valid(new VectorFlagsWrite(config)))
  })

  private val vl = RegInit(VecInit(Seq.fill(config.warps)(0.U(config.xLen.W))))
  private val vtype = RegInit(
    VecInit(Seq.fill(config.warps)((BigInt(1) << 31).U(config.xLen.W)))
  )
  private val vstart =
    RegInit(VecInit(Seq.fill(config.warps)(0.U(config.xLen.W))))
  private val vxrm = RegInit(VecInit(Seq.fill(config.warps)(0.U(2.W))))
  private val vxsat = RegInit(VecInit(Seq.fill(config.warps)(false.B)))
  private val frm = RegInit(VecInit(Seq.fill(config.warps)(0.U(3.W))))
  private val fflags = RegInit(VecInit(Seq.fill(config.warps)(0.U(5.W))))

  private val outputValid = RegInit(false.B)
  private val outputBits = Reg(new VectorConfigurationResult(config))
  private val outputReady = !outputValid || io.out.ready

  private val instruction = io.in.bits.instruction
  private val rd = instruction(11, 7)
  private val rs1 = instruction(19, 15)
  private val isVsetivli = instruction(31, 30) === "b11".U
  private val isVsetvl = instruction(31, 25) === "b1000000".U
  private val isVsetvli = !instruction(31)
  private val recognized = isVsetivli || isVsetvl || isVsetvli

  private val immediateVtype = Mux(
    isVsetivli,
    Cat(0.U(22.W), instruction(29, 20)),
    Cat(0.U(21.W), instruction(30, 20))
  )
  private val requestedVtype =
    Mux(isVsetvl, io.in.bits.rs2Data, immediateVtype)

  // vta/vma may be either value. RVV permits an agnostic element to retain its
  // previous value, which is the deterministic policy used by this datapath.
  private val supportedVtype =
    !requestedVtype(31) &&
      requestedVtype(30, 8) === 0.U &&
      requestedVtype(5, 3) === "b010".U &&
      requestedVtype(2, 0) === "b000".U

  private val currentVl = vl(io.in.bits.warpId)
  private val avl = Mux(
    isVsetivli,
    rs1,
    Mux(rs1 =/= 0.U, io.in.bits.rs1Data, Mux(rd =/= 0.U, config.lanes.U, currentVl))
  )
  private val configuredVl =
    Mux(avl > config.lanes.U, config.lanes.U, avl)
  private val vill = !recognized || !supportedVtype
  private val nextVl = Mux(vill, 0.U, configuredVl)
  private val nextVtype = Mux(
    vill,
    (BigInt(1) << 31).U(config.xLen.W),
    requestedVtype
  )

  io.in.ready := outputReady
  io.out.valid := outputValid
  io.out.bits := outputBits

  io.state.vl := vl(io.queryWarpId)
  io.state.vtype := vtype(io.queryWarpId)
  io.state.vstart := vstart(io.queryWarpId)
  io.state.vxrm := vxrm(io.queryWarpId)
  io.state.vxsat := vxsat(io.queryWarpId)
  io.state.frm := frm(io.queryWarpId)
  io.state.fflags := fflags(io.queryWarpId)
  io.frmByWarp := frm

  when(io.csrWrite.valid) {
    val warp = io.csrWrite.bits.warpId
    switch(io.csrWrite.bits.address) {
      is("h008".U) { vstart(warp) := io.csrWrite.bits.data }
      is("h009".U) { vxsat(warp) := io.csrWrite.bits.data(0) }
      is("h00a".U) { vxrm(warp) := io.csrWrite.bits.data(1, 0) }
      is("h002".U) { frm(warp) := io.csrWrite.bits.data(2, 0) }
      is("h003".U) {
        frm(warp) := io.csrWrite.bits.data(7, 5)
        fflags(warp) := io.csrWrite.bits.data(4, 0)
      }
      is("h00f".U) {
        vxrm(warp) := io.csrWrite.bits.data(2, 1)
        vxsat(warp) := io.csrWrite.bits.data(0)
      }
    }
  }

  when(io.flagsWrite.valid) {
    fflags(io.flagsWrite.bits.warpId) :=
      fflags(io.flagsWrite.bits.warpId) | io.flagsWrite.bits.flags
  }

  when(io.scalarFlagsWrite.valid) {
    fflags(io.scalarFlagsWrite.bits.warpId) :=
      fflags(io.scalarFlagsWrite.bits.warpId) | io.scalarFlagsWrite.bits.flags
  }

  when(outputReady) {
    outputValid := io.in.valid
    when(io.in.valid) {
      val warp = io.in.bits.warpId
      outputBits.warpId := warp
      outputBits.pc := io.in.bits.pc
      outputBits.warpActiveMask := io.in.bits.warpActiveMask
      outputBits.rd := rd
      outputBits.writeRd := rd =/= 0.U
      outputBits.data := nextVl
      outputBits.vill := vill
      vl(warp) := nextVl
      vtype(warp) := nextVtype
      vstart(warp) := 0.U
    }
  }

  when(io.in.valid) {
    assert(
      instruction(14, 12) === "b111".U &&
        instruction(6, 0) === "b1010111".U,
      "VectorConfigurationUnit received a non-configuration instruction"
    )
  }

}
