package opengpu.config

import chisel3._
import chisel3.util._

/** Per-page GPU cache policy, carried from the MMU (Sv32 PTE bits [9:8]) into
  * the memory system.
  *
  * `cached` uses the L1/L2 normally; `writeThrough` keeps the line resident
  * but relies on write-through stores; `uncached` bypasses both caches so a
  * non-coherent writer (the CPU) is always observed and no stale line can
  * shadow it.
  */
object CachePolicy {
  val width = 2
  val cached = 0.U(width.W)
  val writeThrough = 1.U(width.W)
  val uncached = 2.U(width.W)

  /** True for policies that may use the caches. */
  def isCacheable(policy: UInt): Bool = policy =/= uncached
}
