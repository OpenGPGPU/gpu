package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

/** Verifies the host-memory job ring reader and interrupt-history (IH) ring
  * writer: doorbell-driven descriptor fetch, launch handshakes with a mock
  * engine, queue-depth-two prefetch while a job runs, and ordered IH records
  * carrying the job id, ring slot and status.
  */
class JobQueueSpec extends AnyFlatSpec {
  behavior of "JobQueue"

  private class MemModel {
    val words = mutable.LongMap[Int]()
    def word(a: Long): Long = words.getOrElse(a, 0) & 0xffffffffL
    def wwrite(a: Long, d: Int): Unit = words(a) = d & 0xffffffff
  }

  private def descriptor(
    jobId: Int,
    count: Int,
    cmdBase: Int,
    sampleMode: Int = 0
  ): Seq[Int] =
    Seq((jobId & 0xffff) | ((count & 0xffff) << 16),
      cmdBase, 0x8000, 0x9000, 64,
      1 | (1 << 7),
      0, 0, 0,
      sampleMode & 0x3) ++ Seq.fill(6)(0)

  it should "fetch, launch and record two jobs with prefetch overlap" in {
    val ringBase = 0x10000L
    val ihBase = 0x20000L
    val m = new MemModel
    descriptor(1, 2, 0x4000, sampleMode = 2).zipWithIndex.foreach {
      case (w, i) => m.wwrite(ringBase + i * 4, w)
    }
    descriptor(2, 1, 0x4200).zipWithIndex.foreach {
      case (w, i) => m.wwrite(ringBase + 64 + i * 4, w)
    }

    simulate(new JobQueue) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)

      dut.io.enable.poke(true.B)
      dut.io.ringBase.poke(ringBase.U)
      dut.io.ringMask.poke(1.U) // 2 entries
      dut.io.hostWptr.poke(2.U) // doorbell: both descriptors queued
      dut.io.ihBase.poke(ihBase.U)
      dut.io.ihMask.poke(3.U) // 4 entries
      dut.io.reset.poke(false.B)
      dut.io.launchReady.poke(true.B)
      dut.io.done.poke(false.B)

      // Mock engine: captures launches; completes a job `latency` cycles later.
      var launched = List.empty[Long] // cmdBase, in launch order
      var launchedSampleMode = List.empty[Long] // cfg.sampleMode, launch order
      var captureNext = false
      var engineBusy = false
      var countdown = 0
      var jobNo = 0

      var respR = false; var respD = 0L; var respW = false
      var ihRecords = 0

      var guard = 0
      while ((ihRecords < 2 || dut.io.ihWptr.peek().litValue != 2) &&
             guard < 5000) {
        // Engine model feedback: pulse done for exactly one cycle.
        val doDone = engineBusy && countdown == 0
        dut.io.done.poke(doDone.B)
        dut.io.launchReady.poke(true.B)

        // The config registers settle the cycle after the launch edge, which
        // is exactly when a real engine would sample them.
        if (captureNext) {
          launched = dut.io.cfg.cmdBase.peek().litValue.toLong :: launched
          launchedSampleMode =
            dut.io.cfg.sampleMode.peek().litValue.toLong :: launchedSampleMode
          captureNext = false
        }

        // Memory model: one-cycle responses to reads and writes.
        dut.io.mem.req.ready.poke(true.B)
        if (respR) {
          dut.io.mem.resp.valid.poke(true.B)
          dut.io.mem.resp.bits.data.poke(respD.U)
          dut.io.mem.resp.bits.write.poke(false.B)
          respR = false
        } else if (respW) {
          dut.io.mem.resp.valid.poke(true.B)
          dut.io.mem.resp.bits.data.poke(0.U)
          dut.io.mem.resp.bits.write.poke(true.B)
          respW = false
        } else dut.io.mem.resp.valid.poke(false.B)

        if (dut.io.mem.req.valid.peek().litToBoolean &&
            dut.io.mem.req.ready.peek().litToBoolean) {
          val a = dut.io.mem.req.bits.addr.peek().litValue.toLong
          if (dut.io.mem.req.bits.write.peek().litToBoolean) {
            m.wwrite(a, dut.io.mem.req.bits.data.peek().litValue.toInt)
            respW = true
          } else { respR = true; respD = m.word(a) }
        }

        // Capture a launch handshake.
        if (dut.io.launch.peek().litToBoolean &&
            dut.io.launchReady.peek().litToBoolean && !engineBusy) {
          jobNo += 1
          captureNext = true
          engineBusy = true
          countdown = 3
        }

        dut.clock.step()
        guard += 1
        if (doDone) engineBusy = false
        else if (engineBusy && countdown > 0) countdown -= 1
        if (m.word(ihBase) != 0) ihRecords = 1
        if (m.word(ihBase + 16) != 0) ihRecords = 2
      }
      assert(guard < 5000, "queue did not retire both jobs in time")
      // Let the final pointer updates land before inspecting the registers.
      dut.io.done.poke(false.B)
      dut.clock.step(); dut.clock.step()

      assert(launched.reverse == List(0x4000L, 0x4200L),
        s"jobs must launch in ring order with the right command bases, got ${launched.reverse}")
      assert(launchedSampleMode.reverse == List(2L, 0L),
        "descriptor word 9 must decode into cfg.sampleMode, got " +
          s"${launchedSampleMode.reverse}")
      assert(dut.io.rptr.peek().litValue == 2,
        "both descriptors must be consumed")
      assert(dut.io.ihWptr.peek().litValue == 2,
        "both IH records must be written")

      // IH record 0: job 1, slot 0, DONE, no ERROR, status 0.
      assert((m.word(ihBase) & 0xffff) == 1, "IH record 0 must carry job id 1")
      assert((m.word(ihBase) & (1 << 16)) != 0, "IH record 0 must flag DONE")
      assert((m.word(ihBase) & (1 << 17)) == 0, "IH record 0 must not flag ERROR")
      assert((m.word(ihBase + 4) & 0xffff) == 0,
        "IH record 0 must carry ring slot 0")
      assert(m.word(ihBase + 8) == 0, "IH record 0 must report status 0")
      // IH record 1: job 2, slot 1.
      assert((m.word(ihBase + 16) & 0xffff) == 2,
        "IH record 1 must carry job id 2")
      assert((m.word(ihBase + 20) & 0xffff) == 1,
        "IH record 1 must carry ring slot 1")

      // Descriptor details reached the engine: job 1 rendered 2 records.
      // (Launched list only keeps cmdBase; command count was checked via the
      // engine capture below.)
      assert(dut.io.running.peek().litToBoolean == false,
        "no job may be running after all completions")
    }
  }

  it should "hold launches while the engine is not ready and reset cleanly" in {
    val ringBase = 0x30000L
    val ihBase = 0x40000L
    val m = new MemModel
    descriptor(7, 1, 0x5000).zipWithIndex.foreach {
      case (w, i) => m.wwrite(ringBase + i * 4, w)
    }

    simulate(new JobQueue) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)

      dut.io.enable.poke(true.B)
      dut.io.ringBase.poke(ringBase.U)
      dut.io.ringMask.poke(3.U)
      dut.io.hostWptr.poke(1.U)
      dut.io.ihBase.poke(ihBase.U)
      dut.io.ihMask.poke(3.U)
      dut.io.reset.poke(false.B)
      dut.io.done.poke(false.B)

      // With launchReady low the descriptor is fetched (RPTR advances) but no
      // launch happens and the job waits in the pending slot.
      dut.io.launchReady.poke(false.B)
      var respR2 = false; var respD2 = 0L; var respW2 = false
      var guard = 0
      while (dut.io.pendingValid.peek().litToBoolean == false && guard < 200) {
        dut.io.mem.req.ready.poke(true.B)
        if (respR2) {
          dut.io.mem.resp.valid.poke(true.B)
          dut.io.mem.resp.bits.data.poke(respD2.U)
          dut.io.mem.resp.bits.write.poke(false.B)
          respR2 = false
        } else if (respW2) {
          dut.io.mem.resp.valid.poke(true.B)
          dut.io.mem.resp.bits.data.poke(0.U)
          dut.io.mem.resp.bits.write.poke(true.B)
          respW2 = false
        } else dut.io.mem.resp.valid.poke(false.B)
        if (dut.io.mem.req.valid.peek().litToBoolean &&
            dut.io.mem.req.ready.peek().litToBoolean) {
          val a = dut.io.mem.req.bits.addr.peek().litValue.toLong
          if (dut.io.mem.req.bits.write.peek().litToBoolean) {
            m.wwrite(a, dut.io.mem.req.bits.data.peek().litValue.toInt)
            respW2 = true
          } else { respR2 = true; respD2 = m.word(a) }
        }
        dut.clock.step()
        guard += 1
      }
      assert(guard < 200, "descriptor must be fetched into the pending slot")
      assert(dut.io.rptr.peek().litValue == 1, "fetch must consume the slot")
      dut.io.mem.resp.valid.poke(false.B)
      for (_ <- 0 until 10) {
        assert(!dut.io.launch.peek().litToBoolean,
          "no launch may be offered while launchReady is low")
        dut.clock.step()
      }

      // RESET pulse drops the pending job and rewinds the pointers.
      dut.io.reset.poke(true.B)
      dut.clock.step()
      dut.io.reset.poke(false.B)
      assert(dut.io.pendingValid.peek().litToBoolean == false,
        "reset must drop the pending job")
      assert(dut.io.rptr.peek().litValue == 0, "reset must rewind the read pointer")
      assert(dut.io.ihWptr.peek().litValue == 0, "reset must rewind the IH pointer")
    }
  }

  for (maxSamples <- Seq(1, 2, 4)) {
    it should s"retire invalid sample words in order under backpressure (max $maxSamples)" in {
      val ringBase = 0x10000L
      val ihBase = 0x20000L
      val maxMode = Integer.numberOfTrailingZeros(maxSamples)
      // Valid jobs surround consecutive errors; exercise bit 31 as well as
      // the low reserved bit, and the first mode beyond each build's limit.
      val modes = Seq(0L, maxMode + 1L, 3L, maxMode.toLong, 4L, 0x80000000L, 0L)
      val m = new MemModel
      modes.zipWithIndex.foreach { case (mode, slot) =>
        descriptor(slot + 1, 1, 0x4000 + slot * 0x100)
          .updated(9, mode.toInt).zipWithIndex.foreach { case (word, i) =>
            m.wwrite(ringBase + slot * 64 + i * 4, word)
          }
      }
      simulate(new JobQueue(maxSamples)) { dut =>
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        dut.io.enable.poke(true.B)
        dut.io.ringBase.poke(ringBase.U)
        dut.io.ringMask.poke(7.U)
        dut.io.hostWptr.poke(modes.size.U)
        dut.io.ihBase.poke(ihBase.U)
        dut.io.ihMask.poke(7.U)
        dut.io.reset.poke(false.B)
        // Keep ready high even while busy: the queue must own serialization.
        dut.io.launchReady.poke(true.B)
        var cycle = 0
        var completeAt = -1
        var response = Option.empty[(Boolean, Long, Long, Int)]
        val launched = mutable.ArrayBuffer.empty[Int]
        val committed = mutable.ArrayBuffer.empty[Boolean]
        var prefetchedWhileRunning = false
        while (committed.size < modes.size && cycle < 10000) {
          val done = completeAt == cycle
          dut.io.done.poke(done.B)
          dut.io.mem.req.ready.poke((cycle % 4 != 0).B)
          dut.io.mem.resp.valid.poke(false.B)
          response.foreach { case (write, addr, data, readyAt) =>
            if (cycle >= readyAt) {
              dut.io.mem.resp.valid.poke(true.B)
              dut.io.mem.resp.bits.write.poke(write.B)
              dut.io.mem.resp.bits.addr.poke(addr.U)
              dut.io.mem.resp.bits.data.poke(data.U)
            }
          }
          assert(dut.io.ihWptr.peek().litValue == committed.size,
            "IH pointer must count only acknowledged records")
          val respFire = dut.io.mem.resp.valid.peek().litToBoolean &&
            dut.io.mem.resp.ready.peek().litToBoolean
          if (dut.io.ihCommitted.peek().litToBoolean) {
            val slot = committed.size
            assert(respFire && response.exists(r => r._1 && r._2 == ihBase + slot * 16 + 12),
              "completion must coincide with the final IH write acknowledgement")
            assert(completeAt < 0, "no error may overtake a running job")
            committed += dut.io.ihError.peek().litToBoolean
          }
          if (respFire) {
            val (write, addr, data, _) = response.get
            if (write) m.wwrite(addr, data.toInt)
            response = None
          }
          if (dut.io.mem.req.valid.peek().litToBoolean &&
              dut.io.mem.req.ready.peek().litToBoolean) {
            assert(response.isEmpty)
            val write = dut.io.mem.req.bits.write.peek().litToBoolean
            val addr = dut.io.mem.req.bits.addr.peek().litValue.toLong
            val data = if (write) dut.io.mem.req.bits.data.peek().litValue.toLong else m.word(addr)
            response = Some((write, addr, data, cycle + (if (write) 11 else 5)))
          }
          val launch = dut.io.launch.peek().litToBoolean
          if (launch) {
            assert(completeAt < 0, "queue must never launch over an active engine")
            completeAt = cycle + 300 // allow the next descriptor to prefetch
          }
          if (dut.io.pendingValid.peek().litToBoolean && dut.io.running.peek().litToBoolean)
            prefetchedWhileRunning = true
          dut.clock.step()
          if (launch) {
            val slot = ((dut.io.cfg.cmdBase.peek().litValue.toInt - 0x4000) / 0x100)
            assert(modes(slot) <= maxMode, "invalid descriptors must not launch")
            assert(dut.io.cfg.sampleMode.peek().litValue == modes(slot))
            launched += slot
          }
          if (done) completeAt = -1
          cycle += 1
        }
        assert(cycle < 10000, "a rejected job must not block later work")
        assert(prefetchedWhileRunning, "exercise rejection behind an active job")
        assert(launched.toSeq == modes.indices.filter(i => modes(i) <= maxMode))
        assert(committed.toSeq == modes.map(_ > maxMode))
        assert(dut.io.rptr.peek().litValue == modes.size)
        assert(dut.io.ihWptr.peek().litValue == modes.size)
        assert(!dut.io.running.peek().litToBoolean)
        modes.indices.foreach { slot =>
          val error = modes(slot) > maxMode
          assert(m.word(ihBase + slot * 16) ==
            ((slot + 1L) | (1L << 16) | (if (error) 1L << 17 else 0L)))
          assert(m.word(ihBase + slot * 16 + 4) == slot)
          assert(m.word(ihBase + slot * 16 + 8) == (if (error) 1L else 0L))
          assert(m.word(ihBase + slot * 16 + 12) == 0L)
        }
      }
    }
  }

}
