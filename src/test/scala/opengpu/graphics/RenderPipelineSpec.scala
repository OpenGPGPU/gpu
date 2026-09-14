package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

import java.nio.file.{Files, Paths}

class RenderPipelineSpec extends AnyFlatSpec {
  behavior of "RenderPipeline"

  private def q(v: Double): Int = (v * (1 << 16)).toInt

  it should "elaborate one shared CU for the vertex and fragment paths" in {
    simulate(new RenderPipeline(
      GraphicsConfig(screenWidth = 16, screenHeight = 16),
      GpuConfig(lanes = 4, warps = 2), fragCore = true, vertCore = true)) { dut =>
      dut.io.sampleMode.poke(0.U)
      dut.reset.poke(true.B)
      dut.clock.step()
    }
  }

  it should "render a triangle to a framebuffer and export a PPM image" in {
    val config = GraphicsConfig(screenWidth = 16, screenHeight = 16, subPixelBits = 8)
    val stride = 16 * 4
    val colorBase = 0x1000
    val depthBase = 0x2000

    simulate(new RenderPipeline(config)) { dut =>
      val mem = Array.fill(1 << 15)(0x00000000)
      // depth buffer starts "far" so the near triangle passes the LESS test.
      for (i <- 0 until (16 * 16)) mem(depthBase / 4 + i) = 0xffffffff

      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.colorBase.poke(colorBase.U)
      dut.io.depthBase.poke(depthBase.U)
      dut.io.stride.poke(stride.U)
      dut.io.depthTestEnable.poke(true.B)
      dut.io.depthFunc.poke(0.U) // less
      dut.io.depthWriteEnable.poke(true.B)
      dut.io.cullMode.poke(0.U)
      dut.io.sampleMode.poke(0.U)
      dut.io.texEnable.poke(false.B)
      dut.io.texBase.poke(0.U)
      dut.io.texWidth.poke(0.U)
      dut.io.texHeight.poke(0.U)
      dut.io.texWrapClamp.poke(false.B)
      dut.io.texMaxLevel.poke(0.U)
      dut.io.texMem.req.ready.poke(true.B)
      dut.io.texMem.resp.valid.poke(false.B)
      dut.io.mem.req.ready.poke(true.B)
      dut.io.mem.resp.valid.poke(false.B)

      // One red triangle covering the lower-left half (clip x+y maps to sx+sy<=16).
      val red: (Int, Int, Int) = (255, 0, 0)
      val clip = Seq(
        (q(-1.0), q(-1.0), q(1.0)),
        (q(1.0), q(-1.0), q(1.0)),
        (q(-1.0), q(1.0), q(1.0))
      )
      dut.io.draw.valid.poke(true.B)
      val drawBits = dut.io.draw.bits.asInstanceOf[SceneTriangle]
      drawBits.stateOverride.poke(false.B)
      for (i <- 0 until 3) {
        drawBits.clip(i).x.poke(clip(i)._1.S)
        drawBits.clip(i).y.poke(clip(i)._2.S)
        drawBits.clip(i).z.poke(0.S)
        drawBits.clip(i).w.poke(clip(i)._3.S)
        drawBits.color(i).r.poke(red._1.U)
        drawBits.color(i).g.poke(red._2.U)
        drawBits.color(i).b.poke(red._3.U)
        drawBits.depth(i).poke(0x10.S)
      }
      dut.clock.step()
      dut.io.draw.valid.poke(false.B)

      // Mutate every externally supplied state field after acceptance.  The
      // in-flight draw must continue with its boundary snapshot.
      dut.io.colorBase.poke(0x3000.U)
      dut.io.depthBase.poke(0x4000.U)
      dut.io.stride.poke((stride * 2).U)
      dut.io.depthTestEnable.poke(false.B)
      dut.io.depthFunc.poke(3.U)
      dut.io.depthWriteEnable.poke(false.B)
      dut.io.cullMode.poke(1.U)
      dut.io.texEnable.poke(true.B)
      dut.io.texBase.poke(0x5000.U)
      dut.io.texWidth.poke(1.U)
      dut.io.texHeight.poke(1.U)
      dut.io.texWrapClamp.poke(true.B)
      dut.io.texMaxLevel.poke(2.U)

      // Drain: service the OM memory port until done.  Multi-outstanding
      // model with one cycle of read latency: requests captured on fire
      // become presentable the next cycle (the parallel output merger keeps
      // several reads in flight, attributed by the echoed address).
      val respQ = scala.collection.mutable.Queue.empty[(Boolean, Int, Long)]
      val captured = scala.collection.mutable.Queue.empty[(Boolean, Int, Long)]
      var guard = 0
      while (!dut.io.done.peek().litToBoolean && guard < 4000) {
        dut.io.mem.req.ready.poke(true.B)
        while (captured.nonEmpty) respQ.enqueue(captured.dequeue())
        if (dut.io.mem.req.valid.peek().litToBoolean) {
          val addr = dut.io.mem.req.bits.addr.peek().litValue.toInt
          val write = dut.io.mem.req.bits.write.peek().litToBoolean
          val data = dut.io.mem.req.bits.data.peek().litValue.toInt
          if (write) mem(addr / 4) = data
          captured.enqueue((write, addr, mem(addr / 4) & 0xffffffffL))
        }
        if (respQ.nonEmpty) {
          val (isWrite, addr, data) = respQ.head
          dut.io.mem.resp.valid.poke(true.B)
          dut.io.mem.resp.bits.write.poke(isWrite.B)
          dut.io.mem.resp.bits.data.poke(data.U)
          dut.io.mem.resp.bits.addr.poke(addr.U)
          if (dut.io.mem.resp.ready.peek().litToBoolean) respQ.dequeue()
        } else dut.io.mem.resp.valid.poke(false.B)
        dut.clock.step()
        guard += 1
      }
      assert(guard < 4000, "pipeline did not drain")

      // Read back the colour buffer and export a P6 PPM.
      val px = new Array[Int](16 * 16 * 3)
      var i = 0
      for (y <- 0 until 16; x <- 0 until 16) {
        val c = mem(colorBase / 4 + (y * 16 + x))
        px(i) = (c >> 24) & 0xff; px(i + 1) = (c >> 16) & 0xff; px(i + 2) = (c >> 8) & 0xff
        i += 3
      }
      val out = new StringBuilder
      out.append("P6\n16 16\n255\n")
      val bytes = out.toString.getBytes("US-ASCII") ++ px.map(_.toByte)
      val path = Paths.get("generated/render.ppm")
      Files.createDirectories(path.getParent)
      Files.write(path, bytes)
      System.err.println(s"wrote ${path.toAbsolutePath}")

      // Verify the framebuffer: the triangle's interior pixel (5,5) is red.
      def rgb(x: Int, y: Int): (Int, Int, Int) = {
        val c = mem(colorBase / 4 + (y * 16 + x))
        (((c >> 24) & 0xff), ((c >> 16) & 0xff), ((c >> 8) & 0xff))
      }
      assert(rgb(5, 5) == (255, 0, 0), s"interior (5,5) should be red, got ${rgb(5, 5)}")
      // A pixel in the non-covered upper-right half stays background (black).
      assert(rgb(13, 13) == (0, 0, 0), s"outside (13,13) should be black, got ${rgb(13, 13)}")
    }
  }

  it should "discard a triangle wholly outside a side clip plane" in {
    val config = GraphicsConfig(screenWidth = 16, screenHeight = 16)

    simulate(new RenderPipeline(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.colorBase.poke(0x1000.U)
      dut.io.depthBase.poke(0x2000.U)
      dut.io.stride.poke(64.U)
      dut.io.depthTestEnable.poke(false.B)
      dut.io.depthFunc.poke(0.U)
      dut.io.depthWriteEnable.poke(false.B)
      dut.io.cullMode.poke(0.U)
      dut.io.sampleMode.poke(0.U)
      dut.io.texEnable.poke(false.B)
      dut.io.texBase.poke(0.U)
      dut.io.texWidth.poke(0.U)
      dut.io.texHeight.poke(0.U)
      dut.io.texWrapClamp.poke(false.B)
      dut.io.texMaxLevel.poke(0.U)
      dut.io.texMem.req.ready.poke(true.B)
      dut.io.texMem.resp.valid.poke(false.B)
      dut.io.mem.req.ready.poke(true.B)
      dut.io.mem.resp.valid.poke(false.B)

      val draw = dut.io.draw.bits.asInstanceOf[SceneTriangle]
      draw.poke(0.U.asTypeOf(new SceneTriangle(config)))
      draw.stateOverride.poke(false.B)
      for (i <- 0 until 3) {
        draw.clip(i).x.poke(q(1.5 + i * 0.25).S)
        draw.clip(i).y.poke(q(-0.5 + i * 0.5).S)
        draw.clip(i).z.poke(0.S)
        draw.clip(i).w.poke(q(1.0).S)
        draw.color(i).r.poke(255.U)
        draw.depth(i).poke(0x10.S)
      }
      assert(dut.io.draw.ready.peek().litToBoolean)
      dut.io.draw.valid.poke(true.B)
      dut.clock.step()
      dut.io.draw.valid.poke(false.B)

      var requests = 0
      var cycles = 0
      while (!dut.io.done.peek().litToBoolean && cycles < 200) {
        if (dut.io.mem.req.valid.peek().litToBoolean) requests += 1
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 200, "pipeline did not retire a clipped-away draw")
      assert(requests == 0, s"clipped-away draw issued $requests memory requests")
    }
  }

  it should "rasterize both triangles produced by a side-plane clip" in {
    val config = GraphicsConfig(screenWidth = 16, screenHeight = 16)
    val colorBase = 0x1000
    val mem = Array.fill(1 << 14)(0)

    simulate(new RenderPipeline(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.colorBase.poke(colorBase.U)
      dut.io.depthBase.poke(0x2000.U)
      dut.io.stride.poke(64.U)
      dut.io.depthTestEnable.poke(false.B)
      dut.io.depthFunc.poke(0.U)
      dut.io.depthWriteEnable.poke(false.B)
      dut.io.cullMode.poke(0.U)
      dut.io.sampleMode.poke(0.U)
      dut.io.texEnable.poke(false.B)
      dut.io.texBase.poke(0.U)
      dut.io.texWidth.poke(0.U)
      dut.io.texHeight.poke(0.U)
      dut.io.texWrapClamp.poke(false.B)
      dut.io.texMaxLevel.poke(0.U)
      dut.io.texMem.req.ready.poke(true.B)
      dut.io.texMem.resp.valid.poke(false.B)
      dut.io.mem.req.ready.poke(true.B)
      dut.io.mem.resp.valid.poke(false.B)

      val draw = dut.io.draw.bits.asInstanceOf[SceneTriangle]
      draw.poke(0.U.asTypeOf(new SceneTriangle(config)))
      val vertices = Seq((-1.0, -1.0), (2.0, -1.0), (-1.0, 1.0))
      for (i <- 0 until 3) {
        draw.clip(i).x.poke(q(vertices(i)._1).S)
        draw.clip(i).y.poke(q(vertices(i)._2).S)
        draw.clip(i).z.poke(0.S)
        draw.clip(i).w.poke(q(1.0).S)
        draw.color(i).r.poke(255.U)
        draw.depth(i).poke(0x10.S)
      }
      dut.io.draw.valid.poke(true.B)
      dut.clock.step()
      dut.io.draw.valid.poke(false.B)

      val responses = scala.collection.mutable.Queue.empty[(Boolean, Int, Long)]
      val captured = scala.collection.mutable.Queue.empty[(Boolean, Int, Long)]
      var cycles = 0
      while (!dut.io.done.peek().litToBoolean && cycles < 4000) {
        while (captured.nonEmpty) responses.enqueue(captured.dequeue())
        if (dut.io.mem.req.valid.peek().litToBoolean) {
          val address = dut.io.mem.req.bits.addr.peek().litValue.toInt
          if (dut.io.mem.req.bits.write.peek().litToBoolean) {
            mem(address / 4) = dut.io.mem.req.bits.data.peek().litValue.toInt
            captured.enqueue((true, address, 0L))
          } else {
            captured.enqueue((false, address, mem(address / 4) & 0xffffffffL))
          }
        }
        if (responses.nonEmpty) {
          val (write, address, data) = responses.head
          dut.io.mem.resp.valid.poke(true.B)
          dut.io.mem.resp.bits.write.poke(write.B)
          dut.io.mem.resp.bits.addr.poke(address.U)
          dut.io.mem.resp.bits.data.poke(data.U)
          if (dut.io.mem.resp.ready.peek().litToBoolean) responses.dequeue()
        } else {
          dut.io.mem.resp.valid.poke(false.B)
        }
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 4000, "side-clipped fan did not drain")
      def pixel(x: Int, y: Int): Int = mem(colorBase / 4 + y * 16 + x)
      assert(pixel(14, 2) == 0xff0000ff,
        f"first fan region was not rendered: 0x${pixel(14, 2)}%08x")
      assert(pixel(2, 10) == 0xff0000ff,
        f"second fan region was not rendered: 0x${pixel(2, 10)}%08x")
      assert(pixel(14, 12) == 0,
        f"pixel outside the clipped polygon changed: 0x${pixel(14, 12)}%08x")
    }
  }

  it should "write per-sample depths that follow the triangle depth plane" in {
    val config = GraphicsConfig(screenWidth = 16, screenHeight = 16, subPixelBits = 8)
    val wordsPerRow = 16 * 4 // four samples per pixel in 4x mode
    val stride = wordsPerRow * 4
    val colorBase = 0x4000
    val depthBase = 0x8000

    // Render the lower-left-half triangle and return the finished memory.
    // Vertex depths (0, 4096, 2048) over the 16x16 viewport make depth ramp
    // as X + Y/2 in fixed-point screen units, so a quarter-pixel step is
    // exactly +64 in x and +32 in y.
    def drain(sampleMode: Int): Array[Int] = {
      var result: Array[Int] = null
      simulate(new RenderPipeline(config)) { dut =>
        val mem = Array.fill(1 << 16)(0)

        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.io.colorBase.poke(colorBase.U)
        dut.io.depthBase.poke(depthBase.U)
        dut.io.stride.poke(stride.U)
        dut.io.depthTestEnable.poke(false.B)
        dut.io.depthFunc.poke(0.U)
        dut.io.depthWriteEnable.poke(true.B)
        dut.io.cullMode.poke(0.U)
        dut.io.sampleMode.poke(sampleMode.U)
        dut.io.texEnable.poke(false.B)
        dut.io.texBase.poke(0.U)
        dut.io.texWidth.poke(0.U)
        dut.io.texHeight.poke(0.U)
        dut.io.texWrapClamp.poke(false.B)
        dut.io.texMaxLevel.poke(0.U)
        dut.io.texMem.req.ready.poke(true.B)
        dut.io.texMem.resp.valid.poke(false.B)
        dut.io.mem.req.ready.poke(true.B)
        dut.io.mem.resp.valid.poke(false.B)

        val clip = Seq((-1.0, -1.0), (1.0, -1.0), (-1.0, 1.0))
        val depth = Seq(0, 4096, 2048)
        val drawBits = dut.io.draw.bits.asInstanceOf[SceneTriangle]
        drawBits.stateOverride.poke(false.B)
        for (i <- 0 until 3) {
          drawBits.clip(i).x.poke(q(clip(i)._1).S)
          drawBits.clip(i).y.poke(q(clip(i)._2).S)
          drawBits.clip(i).z.poke(0.S)
          drawBits.clip(i).w.poke(q(1.0).S)
          drawBits.color(i).r.poke(255.U)
          drawBits.color(i).g.poke(0.U)
          drawBits.color(i).b.poke(0.U)
          drawBits.depth(i).poke(depth(i).S)
        }
        dut.io.draw.valid.poke(true.B)
        dut.clock.step()
        dut.io.draw.valid.poke(false.B)

        val respQ = scala.collection.mutable.Queue.empty[(Boolean, Int, Long)]
        val captured = scala.collection.mutable.Queue.empty[(Boolean, Int, Long)]
        var guard = 0
        while (!dut.io.done.peek().litToBoolean && guard < 8000) {
          dut.io.mem.req.ready.poke(true.B)
          while (captured.nonEmpty) respQ.enqueue(captured.dequeue())
          if (dut.io.mem.req.valid.peek().litToBoolean) {
            val addr = dut.io.mem.req.bits.addr.peek().litValue.toInt
            val write = dut.io.mem.req.bits.write.peek().litToBoolean
            val data = dut.io.mem.req.bits.data.peek().litValue.toInt
            if (write) mem(addr / 4) = data
            captured.enqueue((write, addr, mem(addr / 4) & 0xffffffffL))
          }
          if (respQ.nonEmpty) {
            val (isWrite, addr, data) = respQ.head
            dut.io.mem.resp.valid.poke(true.B)
            dut.io.mem.resp.bits.write.poke(isWrite.B)
            dut.io.mem.resp.bits.data.poke(data.U)
            dut.io.mem.resp.bits.addr.poke(addr.U)
            if (dut.io.mem.resp.ready.peek().litToBoolean) respQ.dequeue()
          } else dut.io.mem.resp.valid.poke(false.B)
          dut.clock.step()
          guard += 1
        }
        assert(guard < 8000, "per-sample depth draw did not drain")
        result = mem
      }
      result
    }

    def depthWord(mem: Array[Int], mode: Int, x: Int, y: Int, sample: Int): Int = {
      val sampleOffset = (x << mode) + (if (mode == 0) 0 else sample)
      mem(depthBase / 4 + y * wordsPerRow + sampleOffset)
    }

    // Mode 0 keeps the legacy pixel-centre depth; 4x mode places one sample at
    // each quarter-pixel offset around that same base point.
    val centre = depthWord(drain(0), 0, 5, 5, 0)
    assert(centre > 0, s"mode-0 centre depth was not written (got $centre)")
    val mode2 = drain(2)
    val expected = Seq(centre - 96, centre + 32, centre - 32, centre + 96)
    for (s <- 0 until 4) {
      val got = depthWord(mode2, 2, 5, 5, s)
      assert(got == expected(s),
        s"sample $s of pixel (5,5): got $got expected ${expected(s)}")
    }
    // The four samples are an affine function of the sample offset: the
    // parallelogram identity holds and the mean is the interpolated centre.
    val d = (0 until 4).map(s => depthWord(mode2, 2, 5, 5, s))
    assert(d(0) + d(3) == d(1) + d(2), s"sample depths not affine: $d")
    assert(d.sum == 4 * centre, s"sample mean ${d.sum / 4.0} != centre $centre")
  }
}
