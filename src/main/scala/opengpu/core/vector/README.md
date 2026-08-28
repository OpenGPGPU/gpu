# Vector execution

`VectorIntegerAlu` is the first execution unit migrated from OpenGPU's vector
subsystem. It is intentionally rewritten around the fixed GPU profile:

- ELEN=32, SEW=32, LMUL=1
- VLEN=`lanes * 32`
- one element per lane with no element crossing lanes
- exact RVV funct6 and vv/vx/vi operand forms
- masked and inactive lanes preserve the previous destination value
- elastic ready/valid output with no result loss under backpressure

`VectorIntegerAlu` implements the lane-local integer ALU, comparison/mask,
saturating add/subtract, and shift instructions accepted by `VectorDecoder`.

`VectorMultiplyAlu` implements `vmul`, `vmulh`, `vmulhu`, and `vmulhsu` in
`vv` and `vx` forms with one elastic radix-4 Booth pipeline per lane. It also
implements `vsmul` with all four `vxrm` rounding modes and reports saturation
for the per-warp `vxsat` state.

`VectorConfigurationUnit` owns independent `vl`, `vtype`, `vstart`, `vxrm`,
and `vxsat` state for every warp. It implements `vsetvli`, `vsetivli`, and
`vsetvl` for the fixed SEW=32, LMUL=1 profile and sets `vill` for unsupported
types.

`VectorFcvtAlu` implements lane-local `vfcvt.xu.f.v`, `vfcvt.x.f.v`,
`vfcvt.f.xu.v`, `vfcvt.f.x.v`, the two `vfcvt.rtz` integer forms, and
`vfclass.v` using the scalar exact-conversion lanes. Non-rtz conversions honor
the per-warp `frm`; the rtz forms force truncation.

`VectorFsqrtAlu` implements `vfsqrt.v` with one iterative restoring-square-root
lane per vector lane. It honors the per-warp `frm` and reports NX/NV through
the normal vector flag path.

`VectorFEstimateAlu` implements the RVV 7-bit `vfrec7.v` and `vfrsqrt7.v`
estimates with the standard mantissa lookup tables. It handles infinities,
zeros, NaNs, and subnormal normalization, reports DZ/NV, and raises OF/NX when
a `vfrec7` subnormal reciprocal overflows.

Remaining RVV families (reductions, widening/narrowing, slide/gather, and the
remaining VFUNARY1 forms) remain separate migration steps.

The backend now contains a behavioral per-warp vector register file and issue
boundary. Each warp owns 32 VLEN-wide registers with `vs1`, `vs2`, old-`vd`,
and dedicated v0 predicate reads plus one write port. The accompanying vector
scoreboard tracks all registers including v0 and supports same-cycle release
and re-issue. This behavioral storage is the architectural reference; a
multi-port macro wrapper is now available through
`VectorRegisterFile(useBlackBox = true)`: each warp bank mirrors ASAP7 1RW
SRAM macros for the three read ports and the write port, keeping writes
visible to same-cycle reads through a write-through bypass. `GpuCore` selects
this physical file when `useBlackBoxes = true`.

`VectorBackend` connects vector issue, per-warp configuration, integer ALU,
Booth multiply, FP conversion, and round-robin vector writeback. It applies
`vl` to the lane mask, packs comparison bits into the architectural low bits of
the destination register, updates `vxsat`, serializes `vset*` behind older
operations from the same warp, and exposes launch-time VGPR initialization
plus scalar RF bridge ports.

Texture sampling is routed as a separate elastic vector path. `vtex.sample`
uses the warp active mask, `vl`, `vm`, and v0 predicate to select lanes; the
sampler serializes those lane requests and commits one vector destination while
preserving inactive lanes. This keeps sampler latency independent from the
integer/FP writeback arbiters while retaining in-order vector issue semantics.
