# External Chisel sources

`yunsuan/` is a pinned Git submodule.  The GPU build compiles only
`src/main/scala/yunsuan/fpu/FloatFMA.scala` from it; compatibility definitions
for its opcode, valid-register, and leading-zero helpers are local to this
repository.  No generated YunSuan Verilog or SystemVerilog is checked in.

Clone with `git clone --recurse-submodules`, or initialize an existing checkout
with `git submodule update --init --recursive`.

`cvfpu/` may exist as an ignored local reference checkout, but it is not a GPU
build dependency.
