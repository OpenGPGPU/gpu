package opengpu.core.backend

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.register.{ScalarRegisterWrite, VectorRegisterWrite}
import opengpu.core.frontend.decode.{DecodePipe, DecodeRequest, VectorUnit}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** DecodePipe plus VectorBackend, with the core's unused ports tied off. */
private class DecodedVectorBackend(config: GpuConfig) extends Module {
  val decode = Module(new DecodePipe(config))
  val backend = Module(new VectorBackend(config))
  val io = IO(new Bundle {
    val fetch = Flipped(Decoupled(new DecodeRequest(config)))
    val scalarRs1Data = Input(UInt(config.xLen.W))
    val scalarRs2Data = Input(UInt(config.xLen.W))
    val scalarFpData = Input(UInt(32.W))
    val scalarFpReadRs1 = Output(UInt(5.W))
    val scalarFpBusy = Input(Vec(config.warps, UInt(32.W)))
    val initialize = Flipped(Decoupled(new VectorRegisterWrite(config)))
    val scalarWriteback = Decoupled(new ScalarRegisterWrite(config))
    val committedVectorWriteback = Output(Valid(new VectorRegisterWrite(config)))
  })

  decode.io.in <> io.fetch
  decode.io.scalarOut.ready := true.B
  decode.io.fpuOut.ready := true.B
  backend.io.in <> decode.io.vectorOut
  backend.io.scalarRs1Data := io.scalarRs1Data
  backend.io.scalarRs2Data := io.scalarRs2Data
  backend.io.scalarFpData := io.scalarFpData
  io.scalarFpReadRs1 := backend.io.scalarFpRead.rs1
  backend.io.scalarFpBusy := io.scalarFpBusy
  backend.io.initialize.valid := io.initialize.valid
  backend.io.initialize.bits := io.initialize.bits
  io.initialize.ready := backend.io.initialize.ready
  io.scalarWriteback <> backend.io.scalarWriteback
  io.committedVectorWriteback := backend.io.committedVectorWriteback

  backend.io.scalarFlagsWrite.valid := false.B
  backend.io.scalarFlagsWrite.bits := 0.U.asTypeOf(backend.io.scalarFlagsWrite.bits)
  backend.io.shaderCsrWrite.valid := false.B
  backend.io.shaderCsrWrite.bits := 0.U.asTypeOf(backend.io.shaderCsrWrite.bits)
  backend.io.clearWarp.valid := false.B
  backend.io.clearWarp.bits := 0.U
  backend.io.scalarReserve.ready := true.B
  backend.io.fpReserve.ready := true.B
  backend.io.fpWriteback.ready := true.B
  backend.io.redirect.ready := true.B
  backend.io.memoryRequest.ready := true.B
  backend.io.memoryResponse.valid := false.B
  backend.io.memoryResponse.bits := 0.U.asTypeOf(backend.io.memoryResponse.bits)
  backend.io.memoryFault.ready := true.B
  backend.io.unimplemented.ready := true.B
  backend.io.texSample.ready := true.B
  backend.io.texCommit.valid := false.B
  backend.io.texCommit.bits := 0.U.asTypeOf(backend.io.texCommit.bits)
}

class VectorBackendSpec extends AnyFlatSpec {
  behavior of "VectorBackend"

  // Isolate the initialize path from the whole dispatch chain: publish v1
  // through io.initialize, then store v1 and read the per-lane write data. If
  // this passes, the loss is upstream in the controller or the initializer's
  // handshake rather than inside VectorBackend.
  it should "land a vector initialize write in the register file" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new VectorBackend(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.scalarRs1Data.poke(0x400.U)
      dut.io.scalarRs2Data.poke(0.U)
      dut.io.initialize.valid.poke(false.B)
      dut.io.initialize.bits.warpId.poke(0.U)
      dut.io.initialize.bits.vd.poke(0.U)
      for (lane <- 0 until config.lanes) {
        dut.io.initialize.bits.data(lane).poke(0.U)
      }
      dut.io.scalarWriteback.ready.poke(false.B)
      dut.io.redirect.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryFault.ready.poke(true.B)
      dut.io.memoryResponse.bits.faultMask.poke(0.U)
      dut.io.memoryResponse.bits.pageFault.poke(false.B)
      for (lane <- 0 until config.lanes) {
        dut.io.memoryResponse.bits.readData(lane).poke(0.U)
      }
      dut.io.scalarReserve.ready.poke(true.B)
      dut.io.unimplemented.ready.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      // v1 = lane index, exactly what WarpContextInitializer publishes.
      dut.io.initialize.valid.poke(true.B)
      dut.io.initialize.bits.warpId.poke(0.U)
      dut.io.initialize.bits.vd.poke(1.U)
      for (lane <- 0 until config.lanes) {
        dut.io.initialize.bits.data(lane).poke(lane.U)
      }
      var waited = 0
      while (!dut.io.initialize.ready.peek().litToBoolean && waited < 8) {
        dut.clock.step(); waited += 1
      }
      assert(dut.io.initialize.ready.peek().litToBoolean,
        "initialize must be accepted")
      dut.clock.step()
      dut.io.initialize.valid.poke(false.B)
      dut.clock.step(2)

      // vse32.v v1, (x1), unmasked
      val storeInstruction =
        (BigInt(1) << 25) | (BigInt(1) << 15) |
          (BigInt(6) << 12) | (BigInt(1) << 7) | 0x27
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke(storeInstruction.U)
      dut.io.in.bits.pc.poke(0x1000.U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.activeMask.poke("b1111".U)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.loadStore)
      dut.io.in.bits.decoded.vm.poke(true.B)
      dut.io.in.bits.decoded.mop.poke(0.U)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(false.B)
      dut.io.in.bits.decoded.readsVs2Pair.poke(false.B)
      dut.io.in.bits.decoded.readsScalar.poke(true.B)
      dut.io.in.bits.decoded.writesVd.poke(false.B)
      dut.io.in.bits.decoded.memoryRead.poke(false.B)
      dut.io.in.bits.decoded.memoryWrite.poke(true.B)
      var cycles = 0
      while (!dut.io.in.ready.peek().litToBoolean && cycles < 12) {
        dut.clock.step(); cycles += 1
      }
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 12) {
        dut.clock.step(); cycles += 1
      }
      assert(dut.io.memoryRequest.valid.peek().litToBoolean,
        "store must reach the memory port")
      assert(dut.io.memoryRequest.bits.isStore.peek().litToBoolean)
      for (lane <- 0 until config.lanes) {
        val got = dut.io.memoryRequest.bits.writeData(lane).peek().litValue
        assert(got == BigInt(lane),
          s"v1 lane$lane stored 0x${
            got.toString(16)}, expected ${lane}: the initialize write was lost")
      }
    }
  }

  it should "commit a vmsltu.vx mask into v0 and predicate the next store" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new VectorBackend(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.scalarRs1Data.poke(3.U)
      dut.io.scalarRs2Data.poke(0.U)
      dut.io.initialize.valid.poke(false.B)
      dut.io.scalarWriteback.ready.poke(true.B)
      dut.io.redirect.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.faultMask.poke(0.U)
      dut.io.memoryResponse.bits.pageFault.poke(false.B)
      for (lane <- 0 until config.lanes)
        dut.io.memoryResponse.bits.readData(lane).poke(0.U)
      dut.io.memoryFault.ready.poke(true.B)
      dut.io.scalarReserve.ready.poke(true.B)
      dut.io.unimplemented.ready.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      val commits = scala.collection.mutable.ArrayBuffer.empty[(Int, BigInt)]
      val stores =
        scala.collection.mutable.ArrayBuffer.empty[(BigInt, Seq[BigInt])]
      var respond = false
      def step(): Unit = {
        dut.io.memoryResponse.valid.poke(respond.B)
        if (dut.io.committedVectorWriteback.valid.peek().litToBoolean) {
          commits += (
            dut.io.committedVectorWriteback.bits.vd.peek().litValue.toInt ->
              dut.io.committedVectorWriteback.bits.data(0).peek().litValue)
        }
        val request = dut.io.memoryRequest.valid.peek().litToBoolean
        if (request) {
          stores += (dut.io.memoryRequest.bits.laneMask.peek().litValue ->
            (0 until config.lanes).map(lane =>
              dut.io.memoryRequest.bits.writeData(lane).peek().litValue))
        }
        if (respond && dut.io.memoryResponse.ready.peek().litToBoolean)
          respond = false
        dut.clock.step()
        if (request) respond = true
      }

      def issue(instruction: BigInt, unit: VectorUnit.Type,
                configure: Boolean = false, readsVs2: Boolean = false,
                readsScalar: Boolean = false, writesVd: Boolean = false,
                memoryWrite: Boolean = false): Unit = {
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.instruction.poke(instruction.U)
        dut.io.in.bits.pc.poke(0x1000.U)
        dut.io.in.bits.warpId.poke(0.U)
        dut.io.in.bits.activeMask.poke("b1111".U)
        dut.io.in.bits.decoded.recognized.poke(true.B)
        dut.io.in.bits.decoded.valid.poke(true.B)
        dut.io.in.bits.decoded.unit.poke(unit)
        dut.io.in.bits.decoded.funct6.poke((instruction >> 26).U)
        dut.io.in.bits.decoded.operandType.poke(((instruction >> 12) & 7).U)
        dut.io.in.bits.decoded.vm.poke(instruction.testBit(25).B)
        dut.io.in.bits.decoded.nf.poke((instruction >> 29).U)
        dut.io.in.bits.decoded.mop.poke(((instruction >> 26) & 3).U)
        dut.io.in.bits.decoded.elementWidth.poke(((instruction >> 12) & 7).U)
        dut.io.in.bits.decoded.readsVs1.poke(false.B)
        dut.io.in.bits.decoded.readsVs2.poke(readsVs2.B)
        dut.io.in.bits.decoded.readsVs2Pair.poke(false.B)
        dut.io.in.bits.decoded.readsScalar.poke(readsScalar.B)
        dut.io.in.bits.decoded.readsFloat.poke(false.B)
        dut.io.in.bits.decoded.writesVd.poke(writesVd.B)
        dut.io.in.bits.decoded.memoryRead.poke(false.B)
        dut.io.in.bits.decoded.memoryWrite.poke(memoryWrite.B)
        dut.io.in.bits.decoded.configure.poke(configure.B)
        var waited = 0
        while (!dut.io.in.ready.peek().litToBoolean && waited < 64) {
          step(); waited += 1
        }
        assert(dut.io.in.ready.peek().litToBoolean, "issue never accepted")
        step()
        dut.io.in.valid.poke(false.B)
      }

      // vsetvli x0, x9(=3), e32,m1
      issue((BigInt(0x010) << 20) | (BigInt(9) << 15) | (BigInt(7) << 12) |
        0x57, VectorUnit.configuration, configure = true)

      def initialize(register: Int, values: Seq[BigInt]): Unit = {
        dut.io.initialize.valid.poke(true.B)
        dut.io.initialize.bits.warpId.poke(0.U)
        dut.io.initialize.bits.vd.poke(register.U)
        values.zipWithIndex.foreach { case (value, lane) =>
          dut.io.initialize.bits.data(lane).poke(value.U)
        }
        var waited = 0
        while (!dut.io.initialize.ready.peek().litToBoolean && waited < 16) {
          step(); waited += 1
        }
        step()
        dut.io.initialize.valid.poke(false.B)
      }
      initialize(1, Seq(0, 1, 2, 3))
      initialize(0, Seq(BigInt("b3865eb6", 16), BigInt("549f802d", 16),
        BigInt("89d37fe6", 16), BigInt("02b6780a", 16)))

      // vmsltu.vx v0, v1, x9; vse32.v v1, (x1), v0.t; vse32.v v0, (x1)
      issue((BigInt(0x1a) << 26) | (BigInt(1) << 25) | (BigInt(1) << 20) |
        (BigInt(9) << 15) | (BigInt(4) << 12) | 0x57, VectorUnit.mask,
        readsVs2 = true, readsScalar = true, writesVd = true)
      dut.io.scalarRs1Data.poke(0x400.U)
      issue((BigInt(1) << 15) | (BigInt(6) << 12) | (BigInt(1) << 7) | 0x27,
        VectorUnit.loadStore, readsScalar = true, memoryWrite = true)
      issue((BigInt(1) << 25) | (BigInt(1) << 15) | (BigInt(6) << 12) | 0x27,
        VectorUnit.loadStore, readsScalar = true, memoryWrite = true)
      for (_ <- 0 until 64) step()

      val trace = s"commits=${commits.map { case (vd, word) =>
        s"v$vd:0x${word.toString(16)}" }} stores=${stores.map {
        case (mask, data) => s"${mask.toString(2)}:${data.map(_.toString(16))}" }}"
      assert(commits.headOption.exists { case (vd, word) =>
        vd == 0 && (word & 0xf) == 0x7 }, trace)
      assert(stores.size == 2, trace)
      assert(stores(0)._1 == 0x7 && stores(0)._2.take(3) == Seq(0, 1, 2), trace)
      assert((stores(1)._2.head & 0xf) == 0x7, trace)
    }
  }

  it should "carry vsetvli through issue and commit its scalar vl result" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new VectorBackend(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.scalarRs1Data.poke(4.U)
      dut.io.scalarRs2Data.poke(0.U)
      dut.io.initialize.valid.poke(false.B)
      dut.io.initialize.bits.warpId.poke(0.U)
      dut.io.initialize.bits.vd.poke(0.U)
      for (lane <- 0 until config.lanes) {
        dut.io.initialize.bits.data(lane).poke(0.U)
      }
      dut.io.scalarWriteback.ready.poke(false.B)
      dut.io.redirect.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryFault.ready.poke(true.B)
      dut.io.memoryResponse.bits.faultMask.poke(0.U)
      dut.io.memoryResponse.bits.pageFault.poke(false.B)
      for (lane <- 0 until config.lanes) {
        dut.io.memoryResponse.bits.readData(lane).poke(0.U)
      }
      dut.io.scalarReserve.ready.poke(true.B)
      dut.io.unimplemented.ready.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      // vsetvli x1, x2, e32,m1
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke("h010170d7".U)
      dut.io.in.bits.pc.poke("h1000".U)
      dut.io.in.bits.warpId.poke(1.U)
      dut.io.in.bits.activeMask.poke("b1111".U)
      dut.io.in.bits.decoded.recognized.poke(true.B)
      dut.io.in.bits.decoded.valid.poke(true.B)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.configuration)
      dut.io.in.bits.decoded.funct6.poke(0.U)
      dut.io.in.bits.decoded.operandType.poke(7.U)
      dut.io.in.bits.decoded.vm.poke(false.B)
      dut.io.in.bits.decoded.nf.poke(0.U)
      dut.io.in.bits.decoded.mop.poke(0.U)
      dut.io.in.bits.decoded.elementWidth.poke(7.U)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(false.B)
      dut.io.in.bits.decoded.readsVs2Pair.poke(false.B)
      dut.io.in.bits.decoded.readsScalar.poke(false.B)
      dut.io.in.bits.decoded.readsFloat.poke(false.B)
      dut.io.in.bits.decoded.writesVd.poke(false.B)
      dut.io.in.bits.decoded.memoryRead.poke(false.B)
      dut.io.in.bits.decoded.memoryWrite.poke(false.B)
      dut.io.in.bits.decoded.configure.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.scalarWriteback.valid.peek().litToBoolean && cycles < 6) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.scalarWriteback.valid.peek().litToBoolean)
      dut.io.scalarWriteback.bits.warpId.expect(1.U)
      dut.io.scalarWriteback.bits.rd.expect(1.U)
      dut.io.scalarWriteback.bits.data.expect(4.U)
      dut.clock.step(2)
      dut.io.scalarWriteback.valid.expect(true.B)
      dut.io.scalarWriteback.bits.data.expect(4.U)
      dut.io.scalarWriteback.ready.poke(true.B)
      dut.clock.step()
      dut.io.scalarWriteback.valid.expect(false.B)

      def initialize(register: Int, base: Int): Unit = {
        dut.io.initialize.valid.poke(true.B)
        dut.io.initialize.bits.warpId.poke(1.U)
        dut.io.initialize.bits.vd.poke(register.U)
        for (lane <- 0 until config.lanes) {
          dut.io.initialize.bits.data(lane).poke((base + lane).U)
        }
        dut.io.initialize.ready.expect(true.B)
        dut.clock.step()
        dut.io.initialize.valid.poke(false.B)
      }
      initialize(2, 0x20)
      initialize(3, 0x30)

      // vsext.vf2 v6, v2: the backend executes fixed-profile integer widening.
      val widenInstruction =
        (BigInt(0x12) << 26) | (BigInt(1) << 25) |
          (BigInt(2) << 20) | (BigInt(7) << 15) |
          (BigInt(2) << 12) | (BigInt(6) << 7) | 0x57
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke(widenInstruction.U)
      dut.io.in.bits.pc.poke(0x1000.U)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.alu)
      dut.io.in.bits.decoded.funct6.poke("h12".U)
      dut.io.in.bits.decoded.operandType.poke(2.U)
      dut.io.in.bits.decoded.vm.poke(true.B)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(true.B)
      dut.io.in.bits.decoded.readsScalar.poke(false.B)
      dut.io.in.bits.decoded.writesVd.poke(true.B)
      dut.io.in.bits.decoded.configure.poke(false.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      cycles = 0
      while (
        !dut.io.committedVectorWriteback.valid.peek().litToBoolean &&
        cycles < 24
      ) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.committedVectorWriteback.valid.peek().litToBoolean)
      dut.io.committedVectorWriteback.bits.vd.expect(6.U)
      for (lane <- 0 until config.lanes)
        dut.io.committedVectorWriteback.bits.data(lane)
          .expect((0x20 + lane).U)

      dut.clock.step() // retire the unmasked extension before initialization
      initialize(0, 5) // packed v0 bits enable lanes 0 and 2
      initialize(2, 0x8000)
      initialize(6, 100)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke((widenInstruction & ~(BigInt(1) << 25)).U)
      dut.io.in.bits.decoded.vm.poke(false.B)
      dut.io.in.bits.activeMask.poke(3.U) // lane 2 is inactive despite v0
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      cycles = 0
      while (!dut.io.committedVectorWriteback.valid.peek().litToBoolean &&
        cycles < 24) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.committedVectorWriteback.valid.peek().litToBoolean)
      dut.io.committedVectorWriteback.bits.warpId.expect(1.U)
      dut.io.committedVectorWriteback.bits.vd.expect(6.U)
      dut.io.committedVectorWriteback.bits.data(0).expect("hffff8000".U)
      for (lane <- 1 until config.lanes)
        dut.io.committedVectorWriteback.bits.data(lane).expect((100 + lane).U)
      dut.clock.step()
      initialize(2, 0x20)
      dut.io.in.bits.activeMask.poke(15.U)

      // Exercise paired RF reads all the way through ALU and writeback.
      for (funct6 <- Seq(0x2c, 0x2e, 0x2f); masked <- Seq(false, true);
           shift <- Seq(0, 32)) {
        initialize(6, 100)
        dut.io.scalarRs1Data.poke(shift.U)
        val narrowInstruction = (BigInt(funct6) << 26) |
          (if (masked) BigInt(0) else BigInt(1) << 25) |
          (BigInt(2) << 20) | (BigInt(1) << 15) |
          (BigInt(4) << 12) | (BigInt(6) << 7) | 0x57
        dut.io.in.bits.instruction.poke(narrowInstruction.U)
        dut.io.in.bits.decoded.funct6.poke(funct6.U)
        dut.io.in.bits.decoded.operandType.poke(4.U)
        dut.io.in.bits.decoded.vm.poke((!masked).B)
        dut.io.in.bits.decoded.readsVs2Pair.poke(true.B)
        dut.io.in.bits.decoded.readsScalar.poke(true.B)
        dut.io.in.bits.activeMask.poke((if (masked) 3 else 15).U)
        dut.io.in.valid.poke(true.B)
        dut.io.in.ready.expect(true.B)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
        cycles = 0
        while (!dut.io.committedVectorWriteback.valid.peek().litToBoolean &&
          cycles < 24) {
          dut.clock.step()
          cycles += 1
        }
        dut.io.committedVectorWriteback.valid.expect(true.B)
        dut.io.committedVectorWriteback.bits.warpId.expect(1.U)
        dut.io.committedVectorWriteback.bits.vd.expect(6.U)
        for (lane <- 0 until config.lanes) {
          val value = if (shift == 32) BigInt(0x30 + lane)
            else if (funct6 == 0x2c) BigInt(0x20 + lane)
            else if (funct6 == 0x2e) BigInt("ffffffff", 16)
            else BigInt("7fffffff", 16)
          dut.io.committedVectorWriteback.bits.data(lane).expect(
            (if (!masked || lane == 0) value else BigInt(100 + lane)).U)
        }
        dut.clock.step()
      }
      dut.io.in.bits.decoded.readsVs2Pair.poke(false.B)

      // Scaling uses a single odd source and permits in-place writeback.
      val scalingValues = Seq("ffffffff", "80000001", "00000005", "7fffffff")
        .map(BigInt(_, 16))
      initialize(3, 1) // vv shift amounts 1, 2, 3, 4
      for (funct6 <- Seq(0x2a, 0x2b); form <- Seq(0, 3, 4);
           masked <- Seq(false, true)) {
        dut.io.initialize.valid.poke(true.B)
        dut.io.initialize.bits.warpId.poke(1.U)
        dut.io.initialize.bits.vd.poke(31.U)
        scalingValues.zipWithIndex.foreach { case (value, lane) =>
          dut.io.initialize.bits.data(lane).poke(value.U)
        }
        dut.io.initialize.ready.expect(true.B)
        dut.clock.step()
        dut.io.initialize.valid.poke(false.B)
        dut.io.scalarRs1Data.poke(33.U) // Only the low five bits apply.
        val operand = if (form == 0) 3 else 1
        val instruction = (BigInt(funct6) << 26) |
          (if (masked) BigInt(0) else BigInt(1) << 25) |
          (BigInt(31) << 20) | (BigInt(operand) << 15) |
          (BigInt(form) << 12) | (BigInt(31) << 7) | 0x57
        dut.io.in.bits.instruction.poke(instruction.U)
        dut.io.in.bits.decoded.funct6.poke(funct6.U)
        dut.io.in.bits.decoded.operandType.poke(form.U)
        dut.io.in.bits.decoded.vm.poke((!masked).B)
        dut.io.in.bits.decoded.readsVs1.poke((form == 0).B)
        dut.io.in.bits.decoded.readsScalar.poke((form == 4).B)
        dut.io.in.bits.activeMask.poke((if (masked) 3 else 15).U)
        dut.io.in.valid.poke(true.B)
        dut.io.in.ready.expect(true.B)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
        cycles = 0
        while (!dut.io.committedVectorWriteback.valid.peek().litToBoolean &&
          cycles < 24) {
          dut.clock.step()
          cycles += 1
        }
        dut.io.committedVectorWriteback.valid.expect(true.B)
        dut.io.committedVectorWriteback.bits.warpId.expect(1.U)
        dut.io.committedVectorWriteback.bits.vd.expect(31.U)
        scalingValues.zipWithIndex.foreach { case (raw, lane) =>
          val shift = if (form == 0) lane + 1 else 1
          val value = if (funct6 == 0x2b && raw.testBit(31))
            raw - (BigInt(1) << 32) else raw
          val rounded = ((value + (BigInt(1) << (shift - 1))) >> shift) &
            BigInt("ffffffff", 16) // reset vxrm is RNU
          dut.io.committedVectorWriteback.bits.data(lane).expect(
            (if (!masked || lane == 0) rounded else raw).U)
        }
        dut.clock.step()
      }
      initialize(3, 0x30)

      // Predicated arithmetic uses the same v0 and old-vd register paths
      // across integer, multiply and divide execution units.
      val maskedArithmetic = Seq(
        (0x00, 0, VectorUnit.alu, 0x50),
        (0x02, 4, VectorUnit.alu, 0x1d),
        (0x0b, 3, VectorUnit.alu, 0x23),
        (0x25, 2, VectorUnit.multiply, 0x600),
        (0x20, 6, VectorUnit.divide, 10)
      )
      for ((funct6, form, unit, expected) <- maskedArithmetic) {
        initialize(6, 100)
        val instruction = (BigInt(funct6) << 26) | (BigInt(2) << 20) |
          (BigInt(3) << 15) | (BigInt(form) << 12) |
          (BigInt(6) << 7) | 0x57
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.instruction.poke(instruction.U)
        dut.io.in.bits.activeMask.poke(3.U)
        dut.io.in.bits.decoded.unit.poke(unit)
        dut.io.in.bits.decoded.funct6.poke(funct6.U)
        dut.io.in.bits.decoded.operandType.poke(form.U)
        dut.io.in.bits.decoded.vm.poke(false.B)
        dut.io.in.bits.decoded.readsVs1.poke((form == 0 || form == 2).B)
        dut.io.in.bits.decoded.readsScalar.poke((form == 4 || form == 6).B)
        dut.io.scalarRs1Data.poke(3.U)
        dut.io.in.ready.expect(true.B)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
        cycles = 0
        while (!dut.io.committedVectorWriteback.valid.peek().litToBoolean &&
          cycles < 100) {
          dut.clock.step()
          cycles += 1
        }
        assert(dut.io.committedVectorWriteback.valid.peek().litToBoolean)
        dut.io.committedVectorWriteback.bits.vd.expect(6.U)
        dut.io.committedVectorWriteback.bits.data(0).expect(expected.U)
        for (lane <- 1 until config.lanes)
          dut.io.committedVectorWriteback.bits.data(lane).expect((100 + lane).U)
        dut.clock.step()
      }
      dut.io.in.bits.activeMask.poke(15.U)

      // vadd.vx v3, v2, x1
      val addInstruction =
        (BigInt(1) << 25) | (BigInt(2) << 20) | (BigInt(1) << 15) |
          (BigInt(4) << 12) | (BigInt(3) << 7) | 0x57
      dut.io.scalarRs1Data.poke(5.U)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke(addInstruction.U)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.alu)
      dut.io.in.bits.decoded.funct6.poke(0.U)
      dut.io.in.bits.decoded.operandType.poke(4.U)
      dut.io.in.bits.decoded.vm.poke(true.B)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(true.B)
      dut.io.in.bits.decoded.readsScalar.poke(true.B)
      dut.io.in.bits.decoded.writesVd.poke(true.B)
      dut.io.in.bits.decoded.configure.poke(false.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      cycles = 0
      while (
        !dut.io.committedVectorWriteback.valid.peek().litToBoolean &&
        cycles < 24
      ) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.committedVectorWriteback.valid.peek().litToBoolean)
      dut.io.committedVectorWriteback.bits.warpId.expect(1.U)
      dut.io.committedVectorWriteback.bits.vd.expect(3.U)
      for (lane <- 0 until config.lanes) {
        dut.io.committedVectorWriteback.bits.data(lane)
          .expect((0x25 + lane).U)
      }

      // vdiv.vx v3, v2, x1 with x1 = 5.
      val divideInstruction =
        (BigInt(0x21) << 26) | (BigInt(1) << 25) |
          (BigInt(2) << 20) | (BigInt(1) << 15) |
          (BigInt(6) << 12) | (BigInt(3) << 7) | 0x57
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke(divideInstruction.U)
      dut.io.in.bits.pc.poke("h1004".U)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.divide)
      dut.io.in.bits.decoded.funct6.poke("h21".U)
      dut.io.in.bits.decoded.operandType.poke("b110".U)
      dut.io.in.bits.decoded.vm.poke(true.B)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(true.B)
      dut.io.in.bits.decoded.readsScalar.poke(true.B)
      dut.io.in.bits.decoded.writesVd.poke(true.B)
      dut.io.in.bits.decoded.configure.poke(false.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      cycles = 0
      while (
        !dut.io.committedVectorWriteback.valid.peek().litToBoolean &&
        cycles < 40
      ) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.committedVectorWriteback.valid.peek().litToBoolean)
      dut.io.committedVectorWriteback.bits.vd.expect(3.U)
      dut.io.committedVectorWriteback.bits.data(0).expect(6.U)
      dut.io.committedVectorWriteback.bits.data(1).expect(6.U)
      dut.io.committedVectorWriteback.bits.data(2).expect(6.U)
      dut.io.committedVectorWriteback.bits.data(3).expect(7.U)

      // vle32.v v4, (x1): the backend must wait for the external response.
      dut.io.memoryRequest.ready.poke(false.B)
      val loadInstruction =
        (BigInt(1) << 25) | (BigInt(1) << 15) |
          (BigInt(6) << 12) | (BigInt(4) << 7) | 0x07
      dut.io.scalarRs1Data.poke(0x400.U)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke(loadInstruction.U)
      dut.io.in.bits.pc.poke(0x1004.U)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.loadStore)
      dut.io.in.bits.decoded.vm.poke(true.B)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(false.B)
      dut.io.in.bits.decoded.readsVs2Pair.poke(false.B)
      dut.io.in.bits.decoded.readsScalar.poke(true.B)
      dut.io.in.bits.decoded.writesVd.poke(true.B)
      dut.io.in.bits.decoded.memoryRead.poke(true.B)
      dut.io.in.bits.decoded.memoryWrite.poke(false.B)
      while (!dut.io.in.ready.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 12) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.memoryRequest.valid.peek().litToBoolean)
      dut.io.memoryRequest.bits.laneMask.expect("b1111".U)
      for (lane <- 0 until config.lanes) {
        dut.io.memoryRequest.bits.addresses(lane)
          .expect((0x400 + lane * 4).U)
      }
      dut.io.memoryRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.committedVectorWriteback.valid.expect(false.B)

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.faultMask.poke(0.U)
      dut.io.memoryResponse.bits.pageFault.poke(false.B)
      for (lane <- 0 until config.lanes) {
        dut.io.memoryResponse.bits.readData(lane).poke((0x80 + lane).U)
      }
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      cycles = 0
      while (
        !dut.io.committedVectorWriteback.valid.peek().litToBoolean &&
        cycles < 8
      ) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.committedVectorWriteback.valid.peek().litToBoolean)
      dut.io.committedVectorWriteback.bits.vd.expect(4.U)
      for (lane <- 0 until config.lanes) {
        dut.io.committedVectorWriteback.bits.data(lane)
          .expect((0x80 + lane).U)
      }
      dut.clock.step()

      // vluxei32.v v6,(x1),v5: vs2 supplies per-lane byte indices.
      initialize(5, 0x20)
      val indexedLoadInstruction =
        (BigInt(1) << 26) | (BigInt(1) << 25) |
          (BigInt(5) << 20) | (BigInt(1) << 15) |
          (BigInt(6) << 12) | (BigInt(6) << 7) | 0x07
      dut.io.scalarRs1Data.poke(0x500.U)
      dut.io.memoryRequest.ready.poke(false.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke(indexedLoadInstruction.U)
      dut.io.in.bits.pc.poke(0x1008.U)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.loadStore)
      dut.io.in.bits.decoded.vm.poke(true.B)
      dut.io.in.bits.decoded.mop.poke("b01".U)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(true.B)
      dut.io.in.bits.decoded.readsScalar.poke(true.B)
      dut.io.in.bits.decoded.writesVd.poke(true.B)
      dut.io.in.bits.decoded.memoryRead.poke(true.B)
      dut.io.in.bits.decoded.memoryWrite.poke(false.B)
      while (!dut.io.in.ready.peek().litToBoolean)
        dut.clock.step()
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 12) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.memoryRequest.valid.peek().litToBoolean)
      for (lane <- 0 until config.lanes)
        dut.io.memoryRequest.bits.addresses(lane)
          .expect((0x520 + lane).U)
    }
  }

  it should "execute FVF FP add and reverse-divide from a scalar FP operand" in {
    val config = GpuConfig(lanes = 2, warps = 1)
    simulate(new VectorBackend(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.scalarRs1Data.poke(4.U)
      dut.io.scalarRs2Data.poke(0.U)
      dut.io.scalarFpData.poke("h40000000".U) // 2.0
      for (warp <- 0 until config.warps) {
        dut.io.scalarFpBusy(warp).poke(0.U)
      }
      dut.io.initialize.valid.poke(false.B)
      dut.io.initialize.bits.warpId.poke(0.U)
      dut.io.initialize.bits.vd.poke(0.U)
      for (lane <- 0 until config.lanes) {
        dut.io.initialize.bits.data(lane).poke(0.U)
      }
      dut.io.scalarWriteback.ready.poke(true.B)
      dut.io.redirect.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryFault.ready.poke(true.B)
      dut.io.memoryResponse.bits.faultMask.poke(0.U)
      dut.io.memoryResponse.bits.pageFault.poke(false.B)
      for (lane <- 0 until config.lanes) {
        dut.io.memoryResponse.bits.readData(lane).poke(0.U)
      }
      dut.io.scalarReserve.ready.poke(true.B)
      dut.io.unimplemented.ready.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      // vsetvli x1, x2, e32,m1
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke("h010170d7".U)
      dut.io.in.bits.pc.poke("h1000".U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.activeMask.poke("b11".U)
      dut.io.in.bits.decoded.recognized.poke(true.B)
      dut.io.in.bits.decoded.valid.poke(true.B)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.configuration)
      dut.io.in.bits.decoded.funct6.poke(0.U)
      dut.io.in.bits.decoded.operandType.poke(7.U)
      dut.io.in.bits.decoded.vm.poke(false.B)
      dut.io.in.bits.decoded.nf.poke(0.U)
      dut.io.in.bits.decoded.mop.poke(0.U)
      dut.io.in.bits.decoded.elementWidth.poke(7.U)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(false.B)
      dut.io.in.bits.decoded.readsVs2Pair.poke(false.B)
      dut.io.in.bits.decoded.readsScalar.poke(false.B)
      dut.io.in.bits.decoded.readsFloat.poke(false.B)
      dut.io.in.bits.decoded.writesVd.poke(false.B)
      dut.io.in.bits.decoded.memoryRead.poke(false.B)
      dut.io.in.bits.decoded.memoryWrite.poke(false.B)
      dut.io.in.bits.decoded.configure.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      var configCycles = 0
      while (
        !dut.io.scalarWriteback.valid.peek().litToBoolean &&
        configCycles < 6
      ) {
        dut.clock.step()
        configCycles += 1
      }
      assert(dut.io.scalarWriteback.valid.peek().litToBoolean)
      dut.io.scalarWriteback.ready.poke(true.B)
      dut.clock.step()

      def initialize(register: Int, values: Seq[Int]): Unit = {
        dut.io.initialize.valid.poke(true.B)
        dut.io.initialize.bits.warpId.poke(0.U)
        dut.io.initialize.bits.vd.poke(register.U)
        for (lane <- 0 until config.lanes) {
          dut.io.initialize.bits.data(lane).poke(values(lane).U)
        }
        var initCycles = 0
        while (
          !dut.io.initialize.ready.peek().litToBoolean &&
          initCycles < 4
        ) {
          dut.clock.step()
          initCycles += 1
        }
        dut.io.initialize.ready.expect(true.B)
        dut.clock.step()
        dut.io.initialize.valid.poke(false.B)
      }
      initialize(2, Seq(0x3f800000, 0x40400000)) // 1.0, 3.0
      initialize(3, Seq(0x11111111, 0x22222222))

      // vfadd.vf v3, v2, f1 while a pending flw still owns f1.
      dut.io.scalarFpBusy(0).poke((BigInt(1) << 1).U)
      dut.io.scalarFpData.poke("h3f800000".U) // stale 1.0
      val addInstruction =
        (BigInt(1) << 25) | (BigInt(2) << 20) | (BigInt(1) << 15) |
          (BigInt(5) << 12) | (BigInt(3) << 7) | 0x57
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke(addInstruction.U)
      dut.io.in.bits.pc.poke("h1008".U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.activeMask.poke("b11".U)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.floatingPoint)
      dut.io.in.bits.decoded.funct6.poke("h00".U)
      dut.io.in.bits.decoded.operandType.poke("b101".U)
      dut.io.in.bits.decoded.vm.poke(true.B)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(true.B)
      dut.io.in.bits.decoded.readsScalar.poke(false.B)
      dut.io.in.bits.decoded.readsFloat.poke(true.B)
      dut.io.in.bits.decoded.writesVd.poke(true.B)
      dut.io.in.bits.decoded.configure.poke(false.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      for (_ <- 0 until 6) {
        dut.io.committedVectorWriteback.valid.expect(false.B)
        dut.clock.step()
      }
      // The flw writeback clears busy and presents the new f1 together.
      dut.io.scalarFpData.poke("h40000000".U) // 2.0
      dut.io.scalarFpBusy(0).poke(0.U)

      var cycles = 0
      while (
        !dut.io.committedVectorWriteback.valid.peek().litToBoolean &&
        cycles < 16
      ) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.committedVectorWriteback.valid.peek().litToBoolean)
      dut.io.committedVectorWriteback.bits.warpId.expect(0.U)
      dut.io.committedVectorWriteback.bits.vd.expect(3.U)
      dut.io.committedVectorWriteback.bits.data(0)
        .expect(BigInt("40400000", 16).U) // 3.0
      dut.io.committedVectorWriteback.bits.data(1)
        .expect(BigInt("40a00000", 16).U) // 5.0
      dut.io.committedVectorFlags.valid.expect(true.B)
      dut.io.committedVectorFlags.bits.flags.expect(0.U)

      // vfrdiv.vf v3, v2, f1 with f1 still 2.0 and v2 = {1.0, 3.0}.
      val rdivInstruction =
        (BigInt(0x21) << 26) | (BigInt(1) << 25) |
          (BigInt(2) << 20) | (BigInt(1) << 15) |
          (BigInt(5) << 12) | (BigInt(3) << 7) | 0x57
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke(rdivInstruction.U)
      dut.io.in.bits.pc.poke("h100c".U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.activeMask.poke("b11".U)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.floatingPoint)
      dut.io.in.bits.decoded.funct6.poke("h21".U)
      dut.io.in.bits.decoded.operandType.poke("b101".U)
      dut.io.in.bits.decoded.vm.poke(true.B)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(true.B)
      dut.io.in.bits.decoded.readsScalar.poke(false.B)
      dut.io.in.bits.decoded.readsFloat.poke(true.B)
      dut.io.in.bits.decoded.writesVd.poke(true.B)
      dut.io.in.bits.decoded.configure.poke(false.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      cycles = 0
      while (
        !dut.io.committedVectorWriteback.valid.peek().litToBoolean &&
        cycles < 160
      ) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.committedVectorWriteback.valid.peek().litToBoolean)
      dut.io.committedVectorWriteback.bits.vd.expect(3.U)
      dut.io.committedVectorWriteback.bits.data(0)
        .expect(BigInt("40000000", 16).U) // 2.0 / 1.0
      dut.io.committedVectorWriteback.bits.data(1)
        .expect(BigInt("3f2aaaab", 16).U) // 2.0 / 3.0
      dut.io.committedVectorFlags.valid.expect(true.B)
      assert((dut.io.committedVectorFlags.bits.flags.peek().litValue & 1) != 0)
    }
  }

  it should "execute vfcvt, vfsqrt, vfclass, and vfrec7 through vector writeback" in {
    val config = GpuConfig(lanes = 2, warps = 1)
    simulate(new VectorBackend(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.scalarRs1Data.poke(4.U)
      dut.io.scalarRs2Data.poke(0.U)
      dut.io.scalarFpData.poke(0.U)
      for (warp <- 0 until config.warps) {
        dut.io.scalarFpBusy(warp).poke(0.U)
      }
      dut.io.initialize.valid.poke(false.B)
      dut.io.initialize.bits.warpId.poke(0.U)
      dut.io.initialize.bits.vd.poke(0.U)
      for (lane <- 0 until config.lanes) {
        dut.io.initialize.bits.data(lane).poke(0.U)
      }
      dut.io.scalarWriteback.ready.poke(true.B)
      dut.io.redirect.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryFault.ready.poke(true.B)
      dut.io.memoryResponse.bits.faultMask.poke(0.U)
      dut.io.memoryResponse.bits.pageFault.poke(false.B)
      for (lane <- 0 until config.lanes) {
        dut.io.memoryResponse.bits.readData(lane).poke(0.U)
      }
      dut.io.scalarReserve.ready.poke(true.B)
      dut.io.unimplemented.ready.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      // vsetvli x1, x2, e32,m1
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke("h010170d7".U)
      dut.io.in.bits.pc.poke("h1000".U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.activeMask.poke("b11".U)
      dut.io.in.bits.decoded.recognized.poke(true.B)
      dut.io.in.bits.decoded.valid.poke(true.B)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.configuration)
      dut.io.in.bits.decoded.funct6.poke(0.U)
      dut.io.in.bits.decoded.operandType.poke(7.U)
      dut.io.in.bits.decoded.vm.poke(false.B)
      dut.io.in.bits.decoded.nf.poke(0.U)
      dut.io.in.bits.decoded.mop.poke(0.U)
      dut.io.in.bits.decoded.elementWidth.poke(7.U)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(false.B)
      dut.io.in.bits.decoded.readsVs2Pair.poke(false.B)
      dut.io.in.bits.decoded.readsScalar.poke(false.B)
      dut.io.in.bits.decoded.readsFloat.poke(false.B)
      dut.io.in.bits.decoded.writesVd.poke(false.B)
      dut.io.in.bits.decoded.memoryRead.poke(false.B)
      dut.io.in.bits.decoded.memoryWrite.poke(false.B)
      dut.io.in.bits.decoded.configure.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      var cycles = 0
      while (
        !dut.io.scalarWriteback.valid.peek().litToBoolean &&
        cycles < 6
      ) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.scalarWriteback.valid.peek().litToBoolean)
      dut.io.scalarWriteback.ready.poke(true.B)
      dut.clock.step()

      def initialize(register: Int, values: Seq[BigInt]): Unit = {
        dut.io.initialize.valid.poke(true.B)
        dut.io.initialize.bits.warpId.poke(0.U)
        dut.io.initialize.bits.vd.poke(register.U)
        for (lane <- 0 until config.lanes) {
          dut.io.initialize.bits.data(lane).poke(values(lane).U)
        }
        var initCycles = 0
        while (
          !dut.io.initialize.ready.peek().litToBoolean &&
          initCycles < 4
        ) {
          dut.clock.step()
          initCycles += 1
        }
        dut.io.initialize.ready.expect(true.B)
        dut.clock.step()
        dut.io.initialize.valid.poke(false.B)
      }
      initialize(2, Seq(BigInt(5), BigInt("fffffffb", 16)))
      initialize(3, Seq(BigInt("11111111", 16), BigInt("22222222", 16)))

      // vfcvt.f.x.v v3, v2
      val cvtInstruction =
        (BigInt(0x12) << 26) | (BigInt(1) << 25) |
          (BigInt(2) << 20) | (BigInt(3) << 15) |
          (BigInt(1) << 12) | (BigInt(3) << 7) | 0x57
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke(cvtInstruction.U)
      dut.io.in.bits.pc.poke("h1004".U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.activeMask.poke("b11".U)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.floatingPoint)
      dut.io.in.bits.decoded.funct6.poke("h12".U)
      dut.io.in.bits.decoded.operandType.poke("b001".U)
      dut.io.in.bits.decoded.vm.poke(true.B)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(true.B)
      dut.io.in.bits.decoded.readsScalar.poke(false.B)
      dut.io.in.bits.decoded.readsFloat.poke(false.B)
      dut.io.in.bits.decoded.writesVd.poke(true.B)
      dut.io.in.bits.decoded.configure.poke(false.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      cycles = 0
      while (
        !dut.io.committedVectorWriteback.valid.peek().litToBoolean &&
        cycles < 12
      ) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.committedVectorWriteback.valid.peek().litToBoolean)
      dut.io.committedVectorWriteback.bits.vd.expect(3.U)
      dut.io.committedVectorWriteback.bits.data(0)
        .expect(BigInt("40a00000", 16).U) // 5.0
      dut.io.committedVectorWriteback.bits.data(1)
        .expect(BigInt("c0a00000", 16).U) // -5.0
      dut.io.committedVectorFlags.valid.expect(true.B)
      dut.io.committedVectorFlags.bits.flags.expect(0.U)

      // vfsqrt.v v3, v2 with v2 = {4.0, 1.0}.
      initialize(2, Seq(BigInt("40800000", 16), BigInt("3f800000", 16)))
      val sqrtInstruction =
        (BigInt(0x13) << 26) | (BigInt(1) << 25) |
          (BigInt(2) << 20) | (BigInt(0) << 15) |
          (BigInt(1) << 12) | (BigInt(3) << 7) | 0x57
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke(sqrtInstruction.U)
      dut.io.in.bits.pc.poke("h1008".U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.activeMask.poke("b11".U)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.floatingPoint)
      dut.io.in.bits.decoded.funct6.poke("h13".U)
      dut.io.in.bits.decoded.operandType.poke("b001".U)
      dut.io.in.bits.decoded.vm.poke(true.B)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(true.B)
      dut.io.in.bits.decoded.readsScalar.poke(false.B)
      dut.io.in.bits.decoded.readsFloat.poke(false.B)
      dut.io.in.bits.decoded.writesVd.poke(true.B)
      dut.io.in.bits.decoded.configure.poke(false.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      cycles = 0
      while (
        !dut.io.committedVectorWriteback.valid.peek().litToBoolean &&
        cycles < 80
      ) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.committedVectorWriteback.valid.peek().litToBoolean)
      dut.io.committedVectorWriteback.bits.vd.expect(3.U)
      dut.io.committedVectorWriteback.bits.data(0)
        .expect(BigInt("40000000", 16).U) // sqrt(4)
      dut.io.committedVectorWriteback.bits.data(1)
        .expect(BigInt("3f800000", 16).U) // sqrt(1)
      dut.io.committedVectorFlags.valid.expect(true.B)
      dut.io.committedVectorFlags.bits.flags.expect(0.U)

      // vfclass.v v3, v2 with v2 = {-0.0, +inf}.
      initialize(2, Seq(BigInt("80000000", 16), BigInt("7f800000", 16)))
      val classInstruction =
        (BigInt(0x13) << 26) | (BigInt(1) << 25) |
          (BigInt(2) << 20) | (BigInt(16) << 15) |
          (BigInt(1) << 12) | (BigInt(3) << 7) | 0x57
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke(classInstruction.U)
      dut.io.in.bits.pc.poke("h100c".U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.activeMask.poke("b11".U)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.floatingPoint)
      dut.io.in.bits.decoded.funct6.poke("h13".U)
      dut.io.in.bits.decoded.operandType.poke("b001".U)
      dut.io.in.bits.decoded.vm.poke(true.B)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(true.B)
      dut.io.in.bits.decoded.readsScalar.poke(false.B)
      dut.io.in.bits.decoded.readsFloat.poke(false.B)
      dut.io.in.bits.decoded.writesVd.poke(true.B)
      dut.io.in.bits.decoded.configure.poke(false.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      cycles = 0
      while (
        !dut.io.committedVectorWriteback.valid.peek().litToBoolean &&
        cycles < 12
      ) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.committedVectorWriteback.valid.peek().litToBoolean)
      dut.io.committedVectorWriteback.bits.vd.expect(3.U)
      dut.io.committedVectorWriteback.bits.data(0)
        .expect(BigInt("8", 16).U) // -0.0 -> bit 3
      dut.io.committedVectorWriteback.bits.data(1)
        .expect(BigInt("80", 16).U) // +inf -> bit 7
      dut.io.committedVectorFlags.valid.expect(true.B)
      dut.io.committedVectorFlags.bits.flags.expect(0.U)

      // vfrec7.v v3, v2 with v2 = {1.0, 3.0}.
      initialize(2, Seq(BigInt("3f800000", 16), BigInt("40400000", 16)))
      val recInstruction =
        (BigInt(0x13) << 26) | (BigInt(1) << 25) |
          (BigInt(2) << 20) | (BigInt(4) << 15) |
          (BigInt(1) << 12) | (BigInt(3) << 7) | 0x57
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke(recInstruction.U)
      dut.io.in.bits.pc.poke("h1010".U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.activeMask.poke("b11".U)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.floatingPoint)
      dut.io.in.bits.decoded.funct6.poke("h13".U)
      dut.io.in.bits.decoded.operandType.poke("b001".U)
      dut.io.in.bits.decoded.vm.poke(true.B)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(true.B)
      dut.io.in.bits.decoded.readsScalar.poke(false.B)
      dut.io.in.bits.decoded.readsFloat.poke(false.B)
      dut.io.in.bits.decoded.writesVd.poke(true.B)
      dut.io.in.bits.decoded.configure.poke(false.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      cycles = 0
      while (
        !dut.io.committedVectorWriteback.valid.peek().litToBoolean &&
        cycles < 12
      ) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.committedVectorWriteback.valid.peek().litToBoolean)
      dut.io.committedVectorWriteback.bits.vd.expect(3.U)
      dut.io.committedVectorWriteback.bits.data(0)
        .expect(BigInt("3f7f0000", 16).U) // recip7(1.0)
      dut.io.committedVectorWriteback.bits.data(1)
        .expect(BigInt("3eaa0000", 16).U) // recip7(3.0)
      dut.io.committedVectorFlags.valid.expect(true.B)
      dut.io.committedVectorFlags.bits.flags.expect(0.U)
    }
  }

  // vfredusum.vs folds vs1[0] and the active vs2 elements into element 0 of
  // vd. Every other element keeps the old destination, so the writeback is a
  // whole register whose upper elements must be the value read before.
  it should "reduce FP32 elements into element zero through vector writeback" in {
    val config = GpuConfig(lanes = 4, warps = 1)
    simulate(new VectorBackend(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      // The vsetvli AVL comes from x1, so the scalar read supplies vl = 4.
      dut.io.scalarRs1Data.poke(4.U)
      dut.io.scalarRs2Data.poke(0.U)
      dut.io.scalarFpData.poke(0.U)
      for (warp <- 0 until config.warps) {
        dut.io.scalarFpBusy(warp).poke(0.U)
      }
      dut.io.initialize.valid.poke(false.B)
      dut.io.initialize.bits.warpId.poke(0.U)
      dut.io.initialize.bits.vd.poke(0.U)
      for (lane <- 0 until config.lanes) {
        dut.io.initialize.bits.data(lane).poke(0.U)
      }
      dut.io.scalarWriteback.ready.poke(false.B)
      dut.io.redirect.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryFault.ready.poke(true.B)
      dut.io.memoryResponse.bits.faultMask.poke(0.U)
      dut.io.memoryResponse.bits.pageFault.poke(false.B)
      for (lane <- 0 until config.lanes) {
        dut.io.memoryResponse.bits.readData(lane).poke(0.U)
      }
      dut.io.scalarReserve.ready.poke(true.B)
      dut.io.unimplemented.ready.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      def initialize(register: Int, values: Seq[BigInt]): Unit = {
        dut.io.initialize.valid.poke(true.B)
        dut.io.initialize.bits.warpId.poke(0.U)
        dut.io.initialize.bits.vd.poke(register.U)
        for (lane <- 0 until config.lanes) {
          dut.io.initialize.bits.data(lane).poke(values(lane).U)
        }
        var initCycles = 0
        while (!dut.io.initialize.ready.peek().litToBoolean && initCycles < 4) {
          dut.clock.step()
          initCycles += 1
        }
        dut.io.initialize.ready.expect(true.B)
        dut.clock.step()
        dut.io.initialize.valid.poke(false.B)
      }

      // vsetvli x1, x2, e32,m1 with all four lanes active.
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke("h010170d7".U)
      dut.io.in.bits.pc.poke("h1000".U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.activeMask.poke("b1111".U)
      dut.io.in.bits.decoded.recognized.poke(true.B)
      dut.io.in.bits.decoded.valid.poke(true.B)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.configuration)
      dut.io.in.bits.decoded.funct6.poke(0.U)
      dut.io.in.bits.decoded.operandType.poke(7.U)
      dut.io.in.bits.decoded.vm.poke(false.B)
      dut.io.in.bits.decoded.nf.poke(0.U)
      dut.io.in.bits.decoded.mop.poke(0.U)
      dut.io.in.bits.decoded.elementWidth.poke(7.U)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(false.B)
      dut.io.in.bits.decoded.readsVs2Pair.poke(false.B)
      dut.io.in.bits.decoded.readsScalar.poke(false.B)
      dut.io.in.bits.decoded.readsFloat.poke(false.B)
      dut.io.in.bits.decoded.writesVd.poke(false.B)
      dut.io.in.bits.decoded.memoryRead.poke(false.B)
      dut.io.in.bits.decoded.memoryWrite.poke(false.B)
      dut.io.in.bits.decoded.configure.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      var cycles = 0
      while (!dut.io.scalarWriteback.valid.peek().litToBoolean && cycles < 6) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.scalarWriteback.valid.peek().litToBoolean)
      dut.io.scalarWriteback.ready.poke(true.B)
      dut.clock.step()

      // Seed 1.0, elements 2.0/3.0/4.0/0.5, destination starts at 7.0.
      initialize(2, Seq(BigInt("40000000", 16), BigInt("40400000", 16),
        BigInt("40800000", 16), BigInt("3f000000", 16)))
      initialize(4, Seq(BigInt("3f800000", 16), BigInt(0), BigInt(0), BigInt(0)))
      initialize(3, Seq(BigInt("40e00000", 16), BigInt("40e00000", 16),
        BigInt("40e00000", 16), BigInt("40e00000", 16)))

      // vfredusum.vs v3, v2, v4
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke("h062211d7".U)
      dut.io.in.bits.pc.poke("h1004".U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.activeMask.poke("b1111".U)
      dut.io.in.bits.decoded.recognized.poke(true.B)
      dut.io.in.bits.decoded.valid.poke(true.B)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.floatingPoint)
      dut.io.in.bits.decoded.funct6.poke("h01".U)
      dut.io.in.bits.decoded.operandType.poke("b001".U)
      dut.io.in.bits.decoded.vm.poke(true.B)
      dut.io.in.bits.decoded.readsVs1.poke(true.B)
      dut.io.in.bits.decoded.readsVs2.poke(true.B)
      dut.io.in.bits.decoded.readsScalar.poke(false.B)
      dut.io.in.bits.decoded.readsFloat.poke(false.B)
      dut.io.in.bits.decoded.writesVd.poke(true.B)
      dut.io.in.bits.decoded.configure.poke(false.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      cycles = 0
      while (!dut.io.committedVectorWriteback.valid.peek().litToBoolean &&
        cycles < 120) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.committedVectorWriteback.valid.peek().litToBoolean)
      dut.io.committedVectorWriteback.bits.vd.expect(3.U)
      // 1 + 2 + 3 + 4 + 0.5
      dut.io.committedVectorWriteback.bits.data(0)
        .expect(BigInt("41280000", 16).U)
      // Elements past 0 keep the 7.0 the initialize wrote.
      for (lane <- 1 until config.lanes) {
        dut.io.committedVectorWriteback.bits.data(lane)
          .expect(BigInt("40e00000", 16).U)
      }
      dut.io.committedVectorFlags.valid.expect(true.B)
    }
  }

  it should "round-trip a dependent sum through vfcvt even if it reuses a source" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new VectorBackend(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.scalarRs1Data.poke(4.U)
      dut.io.scalarRs2Data.poke(0.U)
      dut.io.scalarFpData.poke(0.U)
      for (warp <- 0 until config.warps) {
        dut.io.scalarFpBusy(warp).poke(0.U)
      }
      dut.io.initialize.valid.poke(false.B)
      dut.io.scalarWriteback.ready.poke(false.B)
      dut.io.redirect.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryFault.ready.poke(true.B)
      dut.io.memoryResponse.bits.faultMask.poke(0.U)
      dut.io.memoryResponse.bits.pageFault.poke(false.B)
      for (lane <- 0 until config.lanes) {
        dut.io.memoryResponse.bits.readData(lane).poke(0.U)
      }
      dut.io.scalarReserve.ready.poke(true.B)
      dut.io.unimplemented.ready.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      def initialize(register: Int, values: Seq[BigInt]): Unit = {
        dut.io.initialize.valid.poke(true.B)
        dut.io.initialize.bits.warpId.poke(0.U)
        dut.io.initialize.bits.vd.poke(register.U)
        for (lane <- 0 until config.lanes) {
          dut.io.initialize.bits.data(lane).poke(values(lane).U)
        }
        var initCycles = 0
        while (!dut.io.initialize.ready.peek().litToBoolean && initCycles < 8) {
          dut.clock.step()
          initCycles += 1
        }
        dut.io.initialize.ready.expect(true.B)
        dut.clock.step()
        dut.io.initialize.valid.poke(false.B)
      }

      def issue(instruction: BigInt, funct6: Int, vs1: Boolean): Unit = {
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.instruction.poke(instruction.U)
        dut.io.in.bits.pc.poke("h1004".U)
        dut.io.in.bits.warpId.poke(0.U)
        dut.io.in.bits.activeMask.poke("b1111".U)
        dut.io.in.bits.decoded.recognized.poke(true.B)
        dut.io.in.bits.decoded.valid.poke(true.B)
        dut.io.in.bits.decoded.unit.poke(VectorUnit.floatingPoint)
        dut.io.in.bits.decoded.funct6.poke(funct6.U)
        dut.io.in.bits.decoded.operandType.poke("b001".U)
        dut.io.in.bits.decoded.vm.poke(instruction.testBit(25).B)
        dut.io.in.bits.decoded.readsVs1.poke(vs1.B)
        dut.io.in.bits.decoded.readsVs2.poke(true.B)
        dut.io.in.bits.decoded.readsVs2Pair.poke(false.B)
        dut.io.in.bits.decoded.readsScalar.poke(false.B)
        dut.io.in.bits.decoded.readsFloat.poke(false.B)
        dut.io.in.bits.decoded.writesVd.poke(true.B)
        dut.io.in.bits.decoded.memoryRead.poke(false.B)
        dut.io.in.bits.decoded.memoryWrite.poke(false.B)
        dut.io.in.bits.decoded.configure.poke(false.B)
        var waited = 0
        while (!dut.io.in.ready.peek().litToBoolean && waited < 80) {
          dut.clock.step()
          waited += 1
        }
        assert(dut.io.in.ready.peek().litToBoolean, "issue never accepted")
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
      }

      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke("h010170d7".U)
      dut.io.in.bits.pc.poke("h1000".U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.activeMask.poke("b1111".U)
      dut.io.in.bits.decoded.recognized.poke(true.B)
      dut.io.in.bits.decoded.valid.poke(true.B)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.configuration)
      dut.io.in.bits.decoded.funct6.poke(0.U)
      dut.io.in.bits.decoded.operandType.poke(7.U)
      dut.io.in.bits.decoded.vm.poke(false.B)
      dut.io.in.bits.decoded.configure.poke(true.B)
      dut.io.in.bits.decoded.writesVd.poke(false.B)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(false.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      var cycles = 0
      while (!dut.io.scalarWriteback.valid.peek().litToBoolean && cycles < 8) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.scalarWriteback.valid.peek().litToBoolean)
      dut.io.scalarWriteback.ready.poke(true.B)
      dut.clock.step()

      // 0, 4, -4, 20
      initialize(5, Seq(BigInt(0), BigInt("40800000", 16),
        BigInt("c0800000", 16), BigInt("41a00000", 16)))

      def opv(f6: Int, vs2: Int, vs1: Int, vd: Int): BigInt =
        (BigInt(f6) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) |
          (BigInt(vs1) << 15) | (BigInt(1) << 12) | (BigInt(vd) << 7) | 0x57

      def cvt(vs1Field: Int, vs2: Int, vd: Int): BigInt =
        (BigInt(0x12) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) |
          (BigInt(vs1Field) << 15) | (BigInt(1) << 12) | (BigInt(vd) << 7) | 0x57

      // vfadd.vv v6, v5, v5, then convert that sum into v5 (the add's source)
      // and back. This is the guest probe's write-after-read on v5.
      issue(opv(0x00, 5, 5, 6), 0x00, vs1 = true)
      issue(cvt(1, 6, 5), 0x12, vs1 = false)
      issue(cvt(3, 5, 8), 0x12, vs1 = false)

      val seen = scala.collection.mutable.Map.empty[Int, Seq[BigInt]]
      cycles = 0
      while (seen.size < 3 && cycles < 120) {
        if (dut.io.committedVectorWriteback.valid.peek().litToBoolean) {
          val vd = dut.io.committedVectorWriteback.bits.vd.peek().litValue.toInt
          seen(vd) = (0 until config.lanes).map { lane =>
            dut.io.committedVectorWriteback.bits.data(lane).peek().litValue
          }
        }
        dut.clock.step()
        cycles += 1
      }
      assert(seen.contains(6), s"vfadd never wrote v6, saw $seen")
      assert(seen.contains(5), "vfcvt.x.f never wrote v5")
      assert(seen.contains(8), "vfcvt.f.x never wrote v8")
      val sum = Seq(BigInt(0), BigInt("41000000", 16),
        BigInt("c1000000", 16), BigInt("42200000", 16))
      val sumInt = Seq(BigInt(0), BigInt(8),
        (BigInt(1) << 32) - 8, BigInt(40))
      for (lane <- 0 until config.lanes) {
        assert(seen(6)(lane) == sum(lane),
          s"add lane $lane got ${seen(6)(lane).toString(16)}")
        assert(seen(5)(lane) == sumInt(lane),
          s"cvt.x lane $lane got ${seen(5)(lane).toString(16)}")
        assert(seen(8)(lane) == sum(lane),
          s"cvt.f lane $lane got ${seen(8)(lane).toString(16)}")
      }
    }
  }

  it should "keep two warps' dependent sums on their own results" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new VectorBackend(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.scalarRs1Data.poke(4.U)
      dut.io.scalarRs2Data.poke(0.U)
      dut.io.scalarFpData.poke(0.U)
      for (warp <- 0 until config.warps)
        dut.io.scalarFpBusy(warp).poke(0.U)
      dut.io.initialize.valid.poke(false.B)
      dut.io.scalarWriteback.ready.poke(false.B)
      dut.io.redirect.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryFault.ready.poke(true.B)
      dut.io.memoryResponse.bits.faultMask.poke(0.U)
      dut.io.memoryResponse.bits.pageFault.poke(false.B)
      for (lane <- 0 until config.lanes)
        dut.io.memoryResponse.bits.readData(lane).poke(0.U)
      dut.io.scalarReserve.ready.poke(true.B)
      dut.io.unimplemented.ready.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      def initialize(warp: Int, register: Int, values: Seq[BigInt]): Unit = {
        dut.io.initialize.valid.poke(true.B)
        dut.io.initialize.bits.warpId.poke(warp.U)
        dut.io.initialize.bits.vd.poke(register.U)
        for (lane <- 0 until config.lanes)
          dut.io.initialize.bits.data(lane).poke(values(lane).U)
        var waited = 0
        while (!dut.io.initialize.ready.peek().litToBoolean && waited < 16) {
          dut.clock.step(); waited += 1
        }
        dut.io.initialize.ready.expect(true.B)
        dut.clock.step()
        dut.io.initialize.valid.poke(false.B)
      }

      def configure(warp: Int): Unit = {
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.instruction.poke("h010170d7".U)
        dut.io.in.bits.pc.poke("h1000".U)
        dut.io.in.bits.warpId.poke(warp.U)
        dut.io.in.bits.activeMask.poke("b1111".U)
        dut.io.in.bits.decoded.recognized.poke(true.B)
        dut.io.in.bits.decoded.valid.poke(true.B)
        dut.io.in.bits.decoded.unit.poke(VectorUnit.configuration)
        dut.io.in.bits.decoded.funct6.poke(0.U)
        dut.io.in.bits.decoded.operandType.poke(7.U)
        dut.io.in.bits.decoded.vm.poke(false.B)
        dut.io.in.bits.decoded.nf.poke(0.U)
        dut.io.in.bits.decoded.mop.poke(0.U)
        dut.io.in.bits.decoded.elementWidth.poke(7.U)
        dut.io.in.bits.decoded.configure.poke(true.B)
        dut.io.in.bits.decoded.writesVd.poke(false.B)
        dut.io.in.bits.decoded.readsVs1.poke(false.B)
        dut.io.in.bits.decoded.readsVs2.poke(false.B)
        var waited = 0
        while (!dut.io.in.ready.peek().litToBoolean && waited < 40) {
          dut.clock.step(); waited += 1
        }
        assert(dut.io.in.ready.peek().litToBoolean)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
        waited = 0
        while (!dut.io.scalarWriteback.valid.peek().litToBoolean && waited < 12) {
          dut.clock.step(); waited += 1
        }
        assert(dut.io.scalarWriteback.valid.peek().litToBoolean)
        dut.io.scalarWriteback.ready.poke(true.B)
        dut.clock.step()
      }

      def opv(f6: Int, vs2: Int, vs1: Int, vd: Int): BigInt =
        (BigInt(f6) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) |
          (BigInt(vs1) << 15) | (BigInt(1) << 12) | (BigInt(vd) << 7) | 0x57
      def cvt(vs1Field: Int, vs2: Int, vd: Int): BigInt =
        (BigInt(0x12) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) |
          (BigInt(vs1Field) << 15) | (BigInt(1) << 12) | (BigInt(vd) << 7) | 0x57

      def issue(warp: Int, instruction: BigInt, funct6: Int, vs1: Boolean): Unit = {
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.instruction.poke(instruction.U)
        dut.io.in.bits.pc.poke("h1004".U)
        dut.io.in.bits.warpId.poke(warp.U)
        dut.io.in.bits.activeMask.poke("b1111".U)
        dut.io.in.bits.decoded.recognized.poke(true.B)
        dut.io.in.bits.decoded.valid.poke(true.B)
        dut.io.in.bits.decoded.unit.poke(VectorUnit.floatingPoint)
        dut.io.in.bits.decoded.funct6.poke(funct6.U)
        dut.io.in.bits.decoded.operandType.poke("b001".U)
        dut.io.in.bits.decoded.vm.poke(true.B)
        dut.io.in.bits.decoded.readsVs1.poke(vs1.B)
        dut.io.in.bits.decoded.readsVs2.poke(true.B)
        dut.io.in.bits.decoded.readsVs2Pair.poke(false.B)
        dut.io.in.bits.decoded.readsScalar.poke(false.B)
        dut.io.in.bits.decoded.readsFloat.poke(false.B)
        dut.io.in.bits.decoded.writesVd.poke(true.B)
        dut.io.in.bits.decoded.memoryRead.poke(false.B)
        dut.io.in.bits.decoded.memoryWrite.poke(false.B)
        dut.io.in.bits.decoded.configure.poke(false.B)
        var waited = 0
        while (!dut.io.in.ready.peek().litToBoolean && waited < 80) {
          dut.clock.step(); waited += 1
        }
        assert(dut.io.in.ready.peek().litToBoolean, s"warp $warp never accepted")
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
      }

      configure(0)
      configure(1)
      // Warp 0 squares to 0, 8, -8, 40. Warp 1 squares to 2, 4, 6, 10.
      initialize(0, 5, Seq(BigInt(0), BigInt("40800000", 16),
        BigInt("c0800000", 16), BigInt("41a00000", 16)))
      initialize(1, 5, Seq(BigInt("3f800000", 16), BigInt("40000000", 16),
        BigInt("40400000", 16), BigInt("40a00000", 16)))

      issue(0, opv(0x00, 5, 5, 6), 0x00, vs1 = true)
      issue(1, opv(0x00, 5, 5, 6), 0x00, vs1 = true)
      issue(0, cvt(1, 6, 5), 0x12, vs1 = false)
      issue(1, cvt(1, 6, 5), 0x12, vs1 = false)
      issue(0, cvt(3, 5, 8), 0x12, vs1 = false)
      issue(1, cvt(3, 5, 8), 0x12, vs1 = false)

      val seen = scala.collection.mutable.Map.empty[(Int, Int), Seq[BigInt]]
      var cycles = 0
      while (seen.size < 6 && cycles < 200) {
        if (dut.io.committedVectorWriteback.valid.peek().litToBoolean) {
          val warp = dut.io.committedVectorWriteback.bits.warpId.peek().litValue.toInt
          val vd = dut.io.committedVectorWriteback.bits.vd.peek().litValue.toInt
          seen((warp, vd)) = (0 until config.lanes).map { lane =>
            dut.io.committedVectorWriteback.bits.data(lane).peek().litValue
          }
        }
        dut.clock.step()
        cycles += 1
      }
      assert(seen.size == 6, s"missing writebacks: $seen")
      val sum0 = Seq(BigInt(0), BigInt("41000000", 16),
        BigInt("c1000000", 16), BigInt("42200000", 16))
      val sum1 = Seq(BigInt("40000000", 16), BigInt("40800000", 16),
        BigInt("40c00000", 16), BigInt("41200000", 16))
      val int0 = Seq(BigInt(0), BigInt(8), (BigInt(1) << 32) - 8, BigInt(40))
      val int1 = Seq(BigInt(2), BigInt(4), BigInt(6), BigInt(10))
      for (lane <- 0 until config.lanes) {
        assert(seen((0, 6))(lane) == sum0(lane), s"warp0 add $lane")
        assert(seen((1, 6))(lane) == sum1(lane), s"warp1 add $lane")
        assert(seen((0, 5))(lane) == int0(lane),
          s"warp0 cvt ${seen((0, 5))(lane).toString(16)}")
        assert(seen((1, 5))(lane) == int1(lane),
          s"warp1 cvt ${seen((1, 5))(lane).toString(16)}")
        assert(seen((0, 8))(lane) == sum0(lane), s"warp0 back $lane")
        assert(seen((1, 8))(lane) == sum1(lane), s"warp1 back $lane")
      }
    }
  }

  it should "cover the ellipse center and interior in red and exterior in black" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new DecodedVectorBackend(config)) { dut =>
      val fReg = Map(
        1 -> BigInt("3f800000", 16), // color.r
        7 -> BigInt("41800000", 16), // radius.x = 16
        8 -> BigInt("41800000", 16), // radius.y = 16
        13 -> BigInt(0),             // 0
        14 -> BigInt("40000000", 16), // 2
        15 -> BigInt("bf800000", 16), // -1
        16 -> BigInt("3f000000", 16), // 0.5
        17 -> BigInt("3f800000", 16)  // 1
      )
      val seen = scala.collection.mutable.Map.empty[Int, Seq[BigInt]]
      val trace = scala.collection.mutable.ListBuffer.empty[(Int, String)]
      def stepFp(): Unit = {
        if (dut.io.committedVectorWriteback.valid.peek().litToBoolean) {
          val vd = dut.io.committedVectorWriteback.bits.vd.peek().litValue.toInt
          val data = (0 until config.lanes).map { lane =>
            dut.io.committedVectorWriteback.bits.data(lane).peek().litValue
          }
          seen(vd) = data
          trace += ((vd, data.map(_.toString(16)).mkString(",")))
        }
        val rs = dut.io.scalarFpReadRs1.peek().litValue.toInt
        dut.io.scalarFpData.poke(fReg.getOrElse(rs, BigInt(0)).U)
        dut.clock.step()
      }

      dut.reset.poke(true.B)
      dut.io.fetch.valid.poke(false.B)
      dut.io.scalarRs1Data.poke(0.U)
      dut.io.scalarRs2Data.poke(0.U)
      dut.io.scalarFpData.poke(0.U)
      for (warp <- 0 until config.warps)
        dut.io.scalarFpBusy(warp).poke(0.U)
      dut.io.initialize.valid.poke(false.B)
      dut.io.scalarWriteback.ready.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      // vsetivli from the fragment prologue: vl = 4, e32, m1.
      dut.io.fetch.valid.poke(true.B)
      dut.io.fetch.bits.instruction.poke("hc1027057".U)
      dut.io.fetch.bits.pc.poke(0.U)
      dut.io.fetch.bits.warpId.poke(0.U)
      dut.io.fetch.bits.activeMask.poke("b1111".U)
      dut.io.fetch.bits.instructionAccessFault.poke(false.B)
      var waited = 0
      while (!dut.io.fetch.ready.peek().litToBoolean && waited < 20) {
        stepFp(); waited += 1
      }
      assert(dut.io.fetch.ready.peek().litToBoolean, "vsetivli not accepted")
      stepFp()
      dut.io.fetch.valid.poke(false.B)
      waited = 0
      while (!dut.io.scalarWriteback.valid.peek().litToBoolean && waited < 30) {
        stepFp(); waited += 1
      }
      assert(dut.io.scalarWriteback.valid.peek().litToBoolean, "vsetivli did not retire")

      def initialize(register: Int, values: Seq[BigInt]): Unit = {
        dut.io.initialize.valid.poke(true.B)
        dut.io.initialize.bits.warpId.poke(0.U)
        dut.io.initialize.bits.vd.poke(register.U)
        for (lane <- 0 until config.lanes)
          dut.io.initialize.bits.data(lane).poke(values(lane).U)
        var spins = 0
        while (!dut.io.initialize.ready.peek().litToBoolean && spins < 40) {
          stepFp(); spins += 1
        }
        assert(dut.io.initialize.ready.peek().litToBoolean, s"init v$register")
        stepFp()
        dut.io.initialize.valid.poke(false.B)
      }

      val dx = Seq(
        BigInt("40800000", 16), // 4, inside
        BigInt("41a00000", 16), // 20, outside
        BigInt("41000000", 16), // 8, inside
        BigInt("00000000", 16)  // 0, ellipse center
      )
      initialize(0, Seq.fill(4)(BigInt(0)))
      initialize(4, Seq.fill(4)(BigInt(0))) // dy
      initialize(5, dx)
      initialize(6, Seq.fill(4)(BigInt(0)))
      initialize(7, Seq.fill(4)(BigInt(0)))
      initialize(8, Seq.fill(4)(BigInt(0)))
      initialize(30, Seq.fill(4)(BigInt(0)))
      initialize(31, Seq.fill(4)(BigInt("3f800000", 16)))

      // Ellipse body from the reciprocal of the radius through the coverage move.
      val program = Seq(
        "h5e03d0d7", "h8618d3d7", "h92539357", "h5e0450d7", "h8618d4d7",
        "h924493d7", "h5e075157", "h92231257", "h5e075157", "h922394d7",
        "h5e03d0d7", "h8618d5d7", "h92459557", "h5e0450d7", "h8618d5d7",
        "h92959257", "h92631157", "h927390d7", "h02209157", "h5e0104d7",
        "h5e07d0d7", "h02909357", "h92a51157", "h924210d7", "h02209157",
        "h5e0103d7", "h4e7294d7", "h8698d257", "h8648d4d7", "h926493d7",
        "h5e085157", "h267390d7", "h02209357", "h1a66d357", "h1268d357",
        "h7686d057", "h5e06d3d7", "h5c6380d7", "h5e008257"
      )
      program.zipWithIndex.foreach { case (word, pc) =>
        dut.io.fetch.valid.poke(true.B)
        dut.io.fetch.bits.instruction.poke(s"$word".U)
        dut.io.fetch.bits.pc.poke((pc + 1).U)
        dut.io.fetch.bits.warpId.poke(0.U)
        dut.io.fetch.bits.activeMask.poke("b1111".U)
        dut.io.fetch.bits.instructionAccessFault.poke(false.B)
        var spins = 0
        while (!dut.io.fetch.ready.peek().litToBoolean && spins < 500) {
          stepFp(); spins += 1
        }
        assert(dut.io.fetch.ready.peek().litToBoolean,
          s"instruction $pc ($word) was not accepted; trace ${trace.takeRight(16)}")
        stepFp()
        dut.io.fetch.valid.poke(false.B)
      }

      var quiet = 0
      var cycles = 0
      while (quiet < 400 && cycles < 8000) {
        if (dut.io.committedVectorWriteback.valid.peek().litToBoolean) {
          val vd = dut.io.committedVectorWriteback.bits.vd.peek().litValue.toInt
          seen(vd) = (0 until config.lanes).map { lane =>
            dut.io.committedVectorWriteback.bits.data(lane).peek().litValue
          }
          quiet = 0
        } else quiet += 1
        stepFp()
        cycles += 1
      }
      assert(seen.contains(4),
        s"coverage never wrote v4, trace ${trace.takeRight(8)}")
      val coverage = seen(4)
      val one = BigInt("3f800000", 16)
      assert(coverage(0) == one,
        s"inside dx=4 ${coverage(0).toString(16)} trace ${trace.takeRight(12)}")
      assert(coverage(1) == 0,
        s"outside dx=20 ${coverage(1).toString(16)} trace ${trace.takeRight(12)}")
      assert(coverage(2) == one, s"inside dx=8 ${coverage(2).toString(16)}")
      assert(coverage(3) == one, s"center dx=0 ${coverage(3).toString(16)}")
    }
  }
}
