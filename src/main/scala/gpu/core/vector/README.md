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

Vector divide, floating point, memory, and a physical vector-register macro
remain separate migration steps.

The backend now contains a behavioral per-warp vector register file and issue
boundary. Each warp owns 32 VLEN-wide registers with `vs1`, `vs2`, old-`vd`,
and dedicated v0 predicate reads plus one write port. The accompanying vector
scoreboard tracks all registers including v0 and supports same-cycle release
and re-issue. This behavioral storage is the architectural reference; a
multi-port macro wrapper remains the physical implementation step.

`VectorBackend` connects vector issue, per-warp configuration, integer ALU,
Booth multiply, and round-robin vector writeback. It applies `vl` to the lane
mask, packs comparison bits into the architectural low bits of the destination
register, updates `vxsat`, serializes `vset*` behind older operations from the
same warp, and exposes launch-time VGPR initialization plus scalar RF bridge
ports.
