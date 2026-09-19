package opengpu.graphics

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import opengpu.command.GpuCommandOpcode
import org.scalatest.flatspec.AnyFlatSpec

/** Synchronization checks between the hardware/Scala definitions and the
  * user-visible ABI in `driver/gpu_abi.h`.
  *
  * The header is the contract the Linux driver and userspace compile against;
  * the register map, capability word, record layouts and encoding shifts are
  * duplicated in RTL.  This spec parses the header and compares every
  * duplicated constant, so a change to either side fails the build instead of
  * silently desynchronizing the UAPI from the hardware.
  */
class GpuAbiLayoutSpec extends AnyFlatSpec {
  behavior of "gpu_abi.h"

  private val headerPath = Paths.get("driver", "gpu_abi.h")

  private val defines: Map[String, Long] = {
    assert(Files.exists(headerPath), s"ABI header not found at $headerPath")
    val text = new String(Files.readAllBytes(headerPath), StandardCharsets.UTF_8)
    CHeader.parse(text)
  }

  private def check(name: String, expected: Long): Unit = {
    assert(defines.contains(name), s"ABI header does not define $name")
    assert(defines(name) == expected,
      s"$name=${defines(name)} does not match the hardware value $expected")
  }

  private def checkAll(pairs: Seq[(String, Long)]): Unit =
    for ((name, expected) <- pairs) check(name, expected)

  it should "match the render-host register map" in {
    checkAll(Seq(
      "GPU_REG_ID" -> RenderHostRegs.ID,
      "GPU_REG_CONTROL" -> RenderHostRegs.CONTROL,
      "GPU_REG_STATUS" -> RenderHostRegs.STATUS,
      "GPU_REG_IRQ" -> RenderHostRegs.IRQ,
      "GPU_REG_CMD_BASE" -> RenderHostRegs.CMD_BASE,
      "GPU_REG_CMD_COUNT" -> RenderHostRegs.CMD_COUNT,
      "GPU_REG_COLOR_BASE" -> RenderHostRegs.COLOR_BASE,
      "GPU_REG_DEPTH_BASE" -> RenderHostRegs.DEPTH_BASE,
      "GPU_REG_STRIDE" -> RenderHostRegs.STRIDE,
      "GPU_REG_DEPTH_TEST" -> RenderHostRegs.DEPTH_TEST_ENABLE,
      "GPU_REG_DEPTH_FUNC" -> RenderHostRegs.DEPTH_FUNC,
      "GPU_REG_DEPTH_WRITE" -> RenderHostRegs.DEPTH_WRITE_ENABLE,
      "GPU_REG_CULL_MODE" -> RenderHostRegs.CULL_MODE,
      "GPU_REG_TEX_BASE" -> RenderHostRegs.TEX_BASE,
      "GPU_REG_TEX_WIDTH" -> RenderHostRegs.TEX_WIDTH,
      "GPU_REG_TEX_HEIGHT" -> RenderHostRegs.TEX_HEIGHT,
      "GPU_REG_TEX_CONFIG" -> RenderHostRegs.TEX_CONFIG,
      "GPU_REG_SCANOUT_BASE" -> RenderHostRegs.SCANOUT_BASE,
      "GPU_REG_SCANOUT_STRIDE" -> RenderHostRegs.SCANOUT_STRIDE,
      "GPU_REG_SCANOUT_WIDTH" -> RenderHostRegs.SCANOUT_WIDTH,
      "GPU_REG_SCANOUT_HEIGHT" -> RenderHostRegs.SCANOUT_HEIGHT,
      "GPU_REG_SCANOUT_FORMAT" -> RenderHostRegs.SCANOUT_FORMAT,
      "GPU_REG_SCANOUT_CONTROL" -> RenderHostRegs.SCANOUT_CONTROL,
      "GPU_REG_SCANOUT_STATUS" -> RenderHostRegs.SCANOUT_STATUS,
      "GPU_REG_CAPABILITIES" -> RenderHostRegs.CAPABILITIES,
      "GPU_REG_CLEAR_BASE" -> RenderHostRegs.CLEAR_BASE,
      "GPU_REG_CLEAR_BYTES" -> RenderHostRegs.CLEAR_BYTES,
      "GPU_REG_CLEAR_PATTERN" -> RenderHostRegs.CLEAR_PATTERN,
      "GPU_REG_CLEAR_START" -> RenderHostRegs.CLEAR_START,
      "GPU_REG_BLIT_SRC_BASE" -> RenderHostRegs.BLIT_SRC_BASE,
      "GPU_REG_BLIT_DST_BASE" -> RenderHostRegs.BLIT_DST_BASE,
      "GPU_REG_BLIT_BYTES" -> RenderHostRegs.BLIT_BYTES,
      "GPU_REG_BLIT_START" -> RenderHostRegs.BLIT_START,
      "GPU_REG_STRIDED_SRC_BASE" -> RenderHostRegs.STRIDED_SRC_BASE,
      "GPU_REG_STRIDED_DST_BASE" -> RenderHostRegs.STRIDED_DST_BASE,
      "GPU_REG_STRIDED_WIDTH" -> RenderHostRegs.STRIDED_WIDTH,
      "GPU_REG_STRIDED_HEIGHT" -> RenderHostRegs.STRIDED_HEIGHT,
      "GPU_REG_STRIDED_SRC_STRIDE" -> RenderHostRegs.STRIDED_SRC_STRIDE,
      "GPU_REG_STRIDED_DST_STRIDE" -> RenderHostRegs.STRIDED_DST_STRIDE,
      "GPU_REG_STRIDED_START" -> RenderHostRegs.STRIDED_START,
      "GPU_REG_MSAA_CONFIG" -> RenderHostRegs.MSAA_CONFIG,
      "GPU_REG_STENCIL_CONFIG" -> RenderHostRegs.STENCIL_CONFIG,
      "GPU_REG_STENCIL_REF_MASKS" -> RenderHostRegs.STENCIL_REF_MASKS,
      "GPU_REG_BLEND_CONFIG" -> RenderHostRegs.BLEND_CONFIG
    ).map { case (n, v) => (n, v.toLong) })
  }

  it should "match the unified-command register bank" in {
    checkAll(Seq(
      "GPU_REG_UCMD_ID" -> GpuCommandMmioRegs.COMMAND_ID,
      "GPU_REG_UCMD_OPCODE" -> GpuCommandMmioRegs.OPCODE,
      "GPU_REG_UCMD_KERNEL_PC" -> GpuCommandMmioRegs.KERNEL_PC,
      "GPU_REG_UCMD_KERNARG" -> GpuCommandMmioRegs.KERNARG,
      "GPU_REG_UCMD_GRID_X" -> GpuCommandMmioRegs.GRID_X,
      "GPU_REG_UCMD_GRID_Y" -> GpuCommandMmioRegs.GRID_Y,
      "GPU_REG_UCMD_GRID_Z" -> GpuCommandMmioRegs.GRID_Z,
      "GPU_REG_UCMD_LOCAL_X" -> GpuCommandMmioRegs.LOCAL_X,
      "GPU_REG_UCMD_LOCAL_Y" -> GpuCommandMmioRegs.LOCAL_Y,
      "GPU_REG_UCMD_LOCAL_Z" -> GpuCommandMmioRegs.LOCAL_Z,
      "GPU_REG_UCMD_FLAGS" -> GpuCommandMmioRegs.FLAGS,
      "GPU_REG_UCMD_DMA_DEPENDENCY" -> GpuCommandMmioRegs.DMA_DEPENDENCY,
      "GPU_REG_UCMD_SOURCE" -> GpuCommandMmioRegs.SOURCE,
      "GPU_REG_UCMD_DESTINATION" -> GpuCommandMmioRegs.DESTINATION,
      "GPU_REG_UCMD_BYTES" -> GpuCommandMmioRegs.BYTES,
      "GPU_REG_UCMD_PATTERN" -> GpuCommandMmioRegs.PATTERN,
      "GPU_REG_UCMD_WIDTH" -> GpuCommandMmioRegs.WIDTH,
      "GPU_REG_UCMD_HEIGHT" -> GpuCommandMmioRegs.HEIGHT,
      "GPU_REG_UCMD_SOURCE_STRIDE" -> GpuCommandMmioRegs.SOURCE_STRIDE,
      "GPU_REG_UCMD_DEST_STRIDE" -> GpuCommandMmioRegs.DESTINATION_STRIDE,
      "GPU_REG_UCMD_WAIT_EVENT" -> GpuCommandMmioRegs.WAIT_EVENT,
      "GPU_REG_UCMD_SIGNAL_EVENT" -> GpuCommandMmioRegs.SIGNAL_EVENT,
      "GPU_REG_UCMD_SUBMIT" -> GpuCommandMmioRegs.SUBMIT,
      "GPU_REG_UCMD_STATUS" -> GpuCommandMmioRegs.STATUS,
      "GPU_REG_UCMD_COMPLETION" -> GpuCommandMmioRegs.COMPLETION,
      "GPU_REG_UCMD_COMPLETION_BYTES_LO" -> GpuCommandMmioRegs.COMPLETION_BYTES_LO,
      "GPU_REG_UCMD_COMPLETION_BYTES_HI" -> GpuCommandMmioRegs.COMPLETION_BYTES_HI,
      "GPU_REG_UCMD_COMPLETION_POP" -> GpuCommandMmioRegs.COMPLETION_POP,
      "GPU_REG_UCMD_RESET" -> GpuCommandMmioRegs.RESET,
      "GPU_REG_UCMD_SAMPLE_MODE" -> GpuCommandMmioRegs.SAMPLE_MODE,
      "GPU_REG_UCMD_VECTOR_SATP" -> GpuCommandMmioRegs.VECTOR_SATP,
      "GPU_REG_UCMD_INSTRUCTION_SATP" -> GpuCommandMmioRegs.INSTRUCTION_SATP,
      "GPU_REG_UCMD_TLB_FLUSH" -> GpuCommandMmioRegs.TLB_FLUSH
    ).map { case (n, v) => (n, v.toLong) })
  }

  it should "match the scoped TLB-flush and satp field encoding" in {
    checkAll(Seq[(String, Long)](
      "GPU_TLB_FLUSH_FULL" -> GpuCommandMmioRegs.TLB_FLUSH_FULL.toLong,
      "GPU_TLB_FLUSH_ASID" -> GpuCommandMmioRegs.TLB_FLUSH_ASID.toLong,
      "GPU_TLB_FLUSH_ASID_SHIFT" ->
        GpuCommandMmioRegs.TLB_FLUSH_ASID_SHIFT.toLong,
      "GPU_TLB_FLUSH_ASID_MASK" ->
        GpuCommandMmioRegs.TLB_FLUSH_ASID_MASK.toLong,
      "GPU_TLB_FLUSH_VPN" -> GpuCommandMmioRegs.TLB_FLUSH_VPN.toLong,
      "GPU_TLB_FLUSH_VPN_SHIFT" ->
        GpuCommandMmioRegs.TLB_FLUSH_VPN_SHIFT.toLong,
      "GPU_TLB_FLUSH_VPN_MASK" ->
        GpuCommandMmioRegs.TLB_FLUSH_VPN_MASK.toLong,
      "GPU_SATP_ENABLE" -> GpuCommandMmioRegs.SATP_ENABLE.toLong,
      "GPU_SATP_ASID_SHIFT" -> GpuCommandMmioRegs.SATP_ASID_SHIFT.toLong,
      "GPU_SATP_ASID_MASK" -> GpuCommandMmioRegs.SATP_ASID_MASK.toLong
    ))
  }

  it should "match the capability-word bit assignments" in {
    checkAll(Seq(
      "GPU_CAP_FRAGMENT_CORE" -> (1L << GpuCapabilities.FragmentCore),
      "GPU_CAP_VERTEX_CORE" -> (1L << GpuCapabilities.VertexCore),
      "GPU_CAP_CLEAR_ENGINE" -> (1L << GpuCapabilities.ClearEngine),
      "GPU_CAP_BLIT_ENGINE" -> (1L << GpuCapabilities.BlitEngine),
      "GPU_CAP_STRIDED_ENGINE" -> (1L << GpuCapabilities.StridedEngine),
      "GPU_CAP_UNIFIED_COMMANDS" -> (1L << GpuCapabilities.UnifiedCommands),
      "GPU_CAP_UNIFIED_RESET" -> (1L << GpuCapabilities.UnifiedReset),
      "GPU_CAP_PERSISTENT_DEPTH" -> (1L << GpuCapabilities.PersistentDepth),
      "GPU_CAP_UNIFIED_RENDER" -> (1L << GpuCapabilities.UnifiedRender),
      "GPU_CAP_MSAA" -> (1L << GpuCapabilities.Msaa),
      "GPU_CAP_MSAA_MAX_MODE_SHIFT" -> GpuCapabilities.MsaaMaxModeShift.toLong,
      "GPU_CAP_MSAA_MAX_MODE_MASK" ->
        (0x3L << GpuCapabilities.MsaaMaxModeShift),
      "GPU_CAP_FRAGMENT_BATCH_SHIFT" ->
        GpuCapabilities.FragmentBatchShift.toLong,
      "GPU_CAP_FRAGMENT_BATCH_MASK" ->
        (0xffL << GpuCapabilities.FragmentBatchShift)
    ))
  }

  it should "match the fragment output-control ABI" in {
    checkAll(Seq(
      "GPU_FRAGMENT_ABI_LEGACY" -> FragmentShaderAbi.Legacy.toLong,
      "GPU_FRAGMENT_ABI_MULTISAMPLE" -> FragmentShaderAbi.Multisample.toLong,
      "GPU_FRAGMENT_CONTROL_EMIT" -> (1L << FragmentShaderAbi.EmitBit),
      "GPU_FRAGMENT_CONTROL_DEPTH_OVERRIDE" ->
        (1L << FragmentShaderAbi.DepthOverrideBit),
      "GPU_FRAGMENT_CONTROL_VALID_MASK" -> 0x3L
    ))
  }

  it should "match the unified-command opcodes" in {
    checkAll(Seq(
      "GPU_UCMD_OP_KERNEL" -> GpuCommandOpcode.kernel.litValue.toLong,
      "GPU_UCMD_OP_COPY" -> GpuCommandOpcode.copy.litValue.toLong,
      "GPU_UCMD_OP_FILL" -> GpuCommandOpcode.fill.litValue.toLong,
      "GPU_UCMD_OP_STRIDED_COPY" -> GpuCommandOpcode.stridedCopy.litValue.toLong,
      "GPU_UCMD_OP_RESOLVE" -> GpuCommandOpcode.resolve.litValue.toLong,
      "GPU_UCMD_OP_INVALIDATE" -> GpuCommandOpcode.invalidate.litValue.toLong,
      "GPU_UCMD_OP_RENDER" -> GpuCommandOpcode.render.litValue.toLong
    ))
  }

  it should "match the draw, vertex and job record sizes" in {
    checkAll(Seq(
      "GPU_DRAW_WORDS" -> 40L,
      "GPU_VERT_DRAW_WORDS" -> 40L,
      "GPU_VERTEX_STRIDE_BYTES" -> 32L,
      "GPU_JOB_WORDS" -> 16L
    ))
  }

  it should "match the blend and stencil encoding shifts" in {
    checkAll(Seq(
      "GPU_BLEND_PRESENT" -> 1L,
      "GPU_BLEND_SRC_SHIFT" -> 4L,
      "GPU_BLEND_SRC_MASK" -> (0xfL << 4),
      "GPU_BLEND_DST_SHIFT" -> 8L,
      "GPU_BLEND_DST_MASK" -> (0xfL << 8),
      "GPU_BLEND_EQ_SHIFT" -> 12L,
      "GPU_BLEND_EQ_MASK" -> (0x7L << 12),
      "GPU_STENCIL_CFG_FUNC_SHIFT" -> 0L,
      "GPU_STENCIL_CFG_FUNC_MASK" -> 0x7L,
      "GPU_STENCIL_CFG_FAIL_SHIFT" -> 3L,
      "GPU_STENCIL_CFG_FAIL_MASK" -> (0x7L << 3),
      "GPU_STENCIL_CFG_ZFAIL_SHIFT" -> 6L,
      "GPU_STENCIL_CFG_ZFAIL_MASK" -> (0x7L << 6),
      "GPU_STENCIL_CFG_ZPASS_SHIFT" -> 9L,
      "GPU_STENCIL_CFG_ZPASS_MASK" -> (0x7L << 9),
      "GPU_STENCIL_REF_SHIFT" -> 0L,
      "GPU_STENCIL_RMASK_SHIFT" -> 8L,
      "GPU_STENCIL_WMASK_SHIFT" -> 16L
    ))
  }

  it should "match the job-state stencil-test encoding" in {
    checkAll(Seq(
      "GPU_JOB_STATE_STENCIL_TEST" -> (1L << 17)
    ))
  }
}

/** Minimal object-like C-macro evaluator for `gpu_abi.h`.
  *
  * Only integer constant expressions built from hex/decimal literals,
  * parentheses, `|`, `<<`, `*`, `+`, `-` and previously defined object-like
  * macros are folded; anything else (function-like macros, string bodies,
  * forward references) is ignored.  That is sufficient for every layout
  * constant the ABI spec synchronizes.
  */
private object CHeader {
  private val Define =
    """^\s*#\s*define\s+([A-Za-z_]\w*)(\([^)]*\))?\s+(.*?)\s*$""".r
  private val Token =
    """0[xX][0-9a-fA-F]+[uUlL]*|0[bB][01]+[uUlL]*|\d+[uUlL]*|[A-Za-z_]\w*|<<|>>|\||\(|\)|\*|\+|-|&""".r

  def parse(text: String): Map[String, Long] = {
    val out = scala.collection.mutable.LinkedHashMap.empty[String, Long]
    for (line <- text.split("\n")) line match {
      case Define(name, params, body) if params == null =>
        eval(stripComments(body), out.toMap).foreach(out(name) = _)
      case _ =>
    }
    out.toMap
  }

  private def stripComments(s: String): String = {
    val noBlock = s.replaceAll("""/\*.*?\*/""", " ")
    val slash = noBlock.indexOf("//")
    (if (slash >= 0) noBlock.substring(0, slash) else noBlock).trim
  }

  private def eval(expr: String, defs: Map[String, Long]): Option[Long] =
    try {
      val tokens = Token.findAllIn(expr).toVector
      if (tokens.isEmpty) None else Some(new ExprParser(tokens, defs).parse())
    } catch { case _: Throwable => None }

  private class ExprParser(tokens: Vector[String], defs: Map[String, Long]) {
    private var pos = 0
    private def peek: Option[String] =
      if (pos < tokens.length) Some(tokens(pos)) else None
    private def eat(op: String): Boolean =
      if (peek.contains(op)) { pos += 1; true } else false

    def parse(): Long = {
      val v = parseOr()
      if (pos != tokens.length) throw new RuntimeException("trailing tokens")
      v
    }

    private def parseOr(): Long = {
      var v = parseShift()
      while (eat("|")) v |= parseShift()
      v
    }

    private def parseShift(): Long = {
      var v = parseAdd()
      while (eat("<<")) v <<= parseAdd().toInt
      v
    }

    private def parseAdd(): Long = {
      var v = parseMul()
      var go = true
      while (go) {
        if (eat("+")) v += parseMul()
        else if (eat("-")) v -= parseMul()
        else go = false
      }
      v
    }

    private def parseMul(): Long = {
      var v = parsePrimary()
      while (eat("*")) v *= parsePrimary()
      v
    }

    private def parsePrimary(): Long = {
      val t = tokens(pos); pos += 1
      if (t == "(") {
        val v = parseOr()
        if (!eat(")")) throw new RuntimeException("missing )")
        v
      } else if (t.head.isDigit) parseNumber(t)
      else defs.getOrElse(t, throw new RuntimeException(s"unknown $t"))
    }

    private def parseNumber(t: String): Long = {
      val body = t.replaceAll("""[uUlL]+$""", "")
      if (body.startsWith("0x") || body.startsWith("0X"))
        java.lang.Long.parseLong(body.substring(2), 16)
      else if (body.startsWith("0b") || body.startsWith("0B"))
        java.lang.Long.parseLong(body.substring(2), 2)
      else body.toLong
    }
  }
}
