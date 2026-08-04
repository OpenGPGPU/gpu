package yunsuan

import chisel3._

/** Minimal definitions required by YunSuan's standalone Chisel FloatFMA. */
object FmaOpCode {
  val fmul = "b0000".U(4.W)
  val fmacc = "b0001".U(4.W)
  val fnmacc = "b0010".U(4.W)
  val fmsac = "b0011".U(4.W)
  val fnmsac = "b0100".U(4.W)
}

package util {
  import chisel3._
  import chisel3.util._

  object GatedValidRegNext {
    def apply(next: Bool, init: Bool = false.B): Bool = {
      val last = Wire(next.cloneType)
      last := RegEnable(next, init, next || last)
      last
    }
  }
}

package vector {
  import chisel3._
  import chisel3.util._

  object LZD {
    def apply(in: UInt): UInt = PriorityEncoder(Reverse(Cat(in, 1.U)))
  }
}
