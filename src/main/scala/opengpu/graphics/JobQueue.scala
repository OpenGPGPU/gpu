package opengpu.graphics

import chisel3._
import chisel3.util._

/** Per-job engine configuration carried by one job-ring descriptor.
  *
  * The descriptor snapshot mirrors the legacy register file fields the engine
  * consumes at START, so a queued job renders exactly what the equivalent
  * register-programmed submission would have rendered.
  */
class JobConfig extends Bundle {
  val cmdBase = UInt(32.W)
  val cmdCount = UInt(16.W)
  val colorBase = UInt(32.W)
  val depthBase = UInt(32.W)
  val stride = UInt(32.W)
  val depthTestEnable = Bool()
  val depthFunc = UInt(3.W)
  val depthWriteEnable = Bool()
  val cullMode = UInt(2.W)
  val texEnable = Bool()
  val texBase = UInt(32.W)
  val texWidth = UInt(14.W)
  val texHeight = UInt(14.W)
  val texWrapClamp = Bool()
  val texMaxLevel = UInt(4.W)
}

/** Host-memory job submission ring and interrupt-history (IH) ring reader.
  *
  * AMDGPU-style submission for the renderer: the host writes fixed-size job
  * descriptors into a ring buffer in shared physical memory and rings a
  * doorbell (JOB_WPTR); this unit reads the next descriptor over an admin
  * memory port, launches the engine, and — when a job completes — first
  * records the interrupt *details* (job id, ring slot, status) into a second
  * host-memory ring (the IH ring) and only then raises the completion
  * interrupt.  The driver's IRQ handler consumes the IH ring and retires the
  * fence identified by the job id, so interrupt context never has to guess
  * which submission completed.
  *
  * One descriptor may be prefetched (queue depth two) while the previous job
  * is still running, so submission and execution overlap without reordering:
  * every job still waits for the previous job's fence, and IH records are
  * written strictly in job order.
  *
  * Descriptor layout (16 words, little-endian; see docs/HOST_INTERFACE.md):
  *   [0]  bits 15:0 job id, bits 31:16 command record count
  *   [1]  command buffer base
  *   [2]  colour buffer base
  *   [3]  depth buffer base
  *   [4]  framebuffer stride (bytes)
  *   [5]  bit0 depth-test enable, bits 6:4 depth func, bit7 depth-write
  *        enable, bits 9:8 cull mode
  *   [6]  texture base
  *   [7]  bits 13:0 texture width, bits 29:16 texture height
  *   [8]  TEX_CONFIG (bit0 CLAMP, bits 5:2 max mip level, bit8 enable)
  *   [9..15] reserved
  *
  * IH record layout (4 words):
  *   [0] bits 15:0 job id, bit16 DONE, bit17 ERROR
  *   [1] bits 15:0 job-ring slot index (queue position)
  *   [2] status code (0 = completed)
  *   [3] reserved
  */
class JobQueue extends Module {
  override def desiredName: String = "JobQueue"

  private val wordsPerJob = 16
  private val wordsPerIh = 4

  val io = IO(new Bundle {
    /** Queue enable (JOB_CONTROL bit0 qualified by the programmed rings). */
    val enable = Input(Bool())
    /** Job-ring base byte address and entry count minus one (power of two). */
    val ringBase = Input(UInt(32.W))
    val ringMask = Input(UInt(16.W))
    /** Host doorbell: next free ring slot index. */
    val hostWptr = Input(UInt(16.W))
    val ihBase = Input(UInt(32.W))
    val ihMask = Input(UInt(16.W))
    /** Write-1 pulse: drop all state (only safe while the engine is idle). */
    val reset = Input(Bool())

    val rptr = Output(UInt(16.W))
    val ihWptr = Output(UInt(16.W))
    val running = Output(Bool())
    val pendingValid = Output(Bool())

    /** Launch handshake into the engine; accepted when `launchReady`. */
    val launch = Output(Bool())
    val launchReady = Input(Bool())
    /** Completion pulse for the job this unit launched. */
    val done = Input(Bool())
    /** Pulses only after the completed job's IH record and WPTR are visible. */
    val ihCommitted = Output(Bool())

    /** Configuration of the running job; stable between launch accept cycles. */
    val cfg = Output(new JobConfig)

    /** Admin memory port (descriptor fetches + IH record writes). */
    val mem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
  })

  private val sIdle :: sFetchReq :: sFetchResp :: sIhReq :: sIhResp :: Nil =
    Enum(5)
  private val state = RegInit(sIdle)

  // Currently running job (its descriptor stays stable until the next launch).
  private val runCfg = RegInit(0.U.asTypeOf(new JobConfig))
  private val runJobId = RegInit(0.U(16.W))
  private val runSlot = RegInit(0.U(16.W))
  private val running = RegInit(false.B)

  // Prefetched job (queue depth two).
  private val pendingCfg = RegInit(0.U.asTypeOf(new JobConfig))
  private val pendingJobId = RegInit(0.U(16.W))
  private val pendingSlot = RegInit(0.U(16.W))
  private val pendingValid = RegInit(false.B)

  // In-flight descriptor fetch.
  private val fetchCfg = RegInit(0.U.asTypeOf(new JobConfig))
  private val fetchJobId = RegInit(0.U(16.W))
  private val fetchSlot = RegInit(0.U(16.W))
  private val fetchWord = RegInit(0.U(log2Ceil(wordsPerJob).W))

  // Completion awaiting its IH record.
  private val ihValid = RegInit(false.B)
  private val ihJobId = RegInit(0.U(16.W))
  private val ihSlot = RegInit(0.U(16.W))
  private val ihWord = RegInit(0.U(log2Ceil(wordsPerIh).W))

  // Ring positions. These are free-running (mod 2^16) so the doorbell
  // comparison never wraps as long as the host keeps fewer jobs in flight
  // than ring entries; the entry index is the pointer masked by ring size.
  private val rptr = RegInit(0.U(16.W))
  private val ihWptr = RegInit(0.U(16.W))

  io.rptr := rptr
  io.ihWptr := ihWptr
  io.running := running
  io.pendingValid := pendingValid
  io.ihCommitted := false.B
  io.cfg := runCfg
  io.launch := false.B

  // Ring entry byte addresses (slot index = pointer & (entries-1)).
  private val fetchAddr =
    io.ringBase + (fetchSlot & io.ringMask) * (wordsPerJob * 4).U +
      fetchWord * 4.U
  private val ihAddr =
    io.ihBase + (ihWptr & io.ihMask) * (wordsPerIh * 4).U + ihWord * 4.U

  // Admin memory port: one outstanding access at a time.
  io.mem.req.valid := false.B
  io.mem.req.bits.write := false.B
  io.mem.req.bits.addr := 0.U
  io.mem.req.bits.data := 0.U
  io.mem.resp.ready := false.B

  // Job completion: latch the IH details immediately, wherever the FSM is.
  when(io.done) {
    running := false.B
    ihValid := true.B
    ihJobId := runJobId
    ihSlot := runSlot
  }

  // Host-driven reset pulse (JOB_CONTROL bit1).
  when(io.reset) {
    state := sIdle
    running := false.B
    pendingValid := false.B
    ihValid := false.B
    rptr := 0.U
    ihWptr := 0.U
  }

  private val wantFetch =
    io.enable && !pendingValid && !ihValid && rptr =/= io.hostWptr

  switch(state) {
    is(sIdle) {
      when(io.reset) {
        // handled above
      }.elsewhen(ihValid) {
        // Record the completed job first: while the IH record is being
        // written no job is running, so a completion can never be overwritten
        // by a newer one.
        ihWord := 0.U
        state := sIhReq
      }.elsewhen(pendingValid && io.launchReady && !io.done) {
        // Launch the prefetched job.
        pendingValid := false.B
        running := true.B
        runCfg := pendingCfg
        runJobId := pendingJobId
        runSlot := pendingSlot
        io.launch := true.B
      }.elsewhen(wantFetch) {
        fetchWord := 0.U
        fetchSlot := rptr
        state := sFetchReq
      }
    }

    is(sFetchReq) {
      io.mem.req.valid := true.B
      io.mem.req.bits.addr := fetchAddr
      when(io.mem.req.fire) { state := sFetchResp }
    }

    is(sFetchResp) {
      io.mem.resp.ready := true.B
      when(io.mem.resp.fire) {
        val data = io.mem.resp.bits.data
        switch(fetchWord) {
          is(0.U) {
            fetchJobId := data(15, 0)
            fetchCfg.cmdCount := data(31, 16)
          }
          is(1.U) { fetchCfg.cmdBase := data }
          is(2.U) { fetchCfg.colorBase := data }
          is(3.U) { fetchCfg.depthBase := data }
          is(4.U) { fetchCfg.stride := data }
          is(5.U) {
            fetchCfg.depthTestEnable := data(0)
            fetchCfg.depthFunc := data(6, 4)
            fetchCfg.depthWriteEnable := data(7)
            fetchCfg.cullMode := data(9, 8)
          }
          is(6.U) { fetchCfg.texBase := data }
          is(7.U) {
            fetchCfg.texWidth := data(13, 0)
            fetchCfg.texHeight := data(29, 16)
          }
          is(8.U) {
            fetchCfg.texWrapClamp := data(0)
            fetchCfg.texMaxLevel := data(5, 2)
            fetchCfg.texEnable := data(8)
          }
        }
        when(fetchWord === (wordsPerJob - 1).U) {
          // Descriptor consumed: advance the read pointer and stage the job.
          pendingCfg := fetchCfg
          pendingJobId := fetchJobId
          pendingSlot := fetchSlot & io.ringMask
          pendingValid := true.B
          rptr := rptr + 1.U
          state := sIdle
        }.otherwise {
          fetchWord := fetchWord + 1.U
          state := sFetchReq
        }
      }
    }

    is(sIhReq) {
      io.mem.req.valid := true.B
      io.mem.req.bits.write := true.B
      io.mem.req.bits.addr := ihAddr
      io.mem.req.bits.data :=
        MuxLookup(ihWord, 0.U(32.W))(Seq(
          0.U -> (ihJobId | (1 << 16).U), // DONE, no ERROR
          1.U -> ihSlot,
          2.U -> 0.U // status: completed
        ))
      when(io.mem.req.fire) { state := sIhResp }
    }

    is(sIhResp) {
      io.mem.resp.ready := true.B
      when(io.mem.resp.fire) {
        when(ihWord === (wordsPerIh - 1).U) {
          ihWptr := ihWptr + 1.U
          ihValid := false.B
          io.ihCommitted := true.B
          state := sIdle
        }.otherwise {
          ihWord := ihWord + 1.U
          state := sIhReq
        }
      }
    }
  }
}
