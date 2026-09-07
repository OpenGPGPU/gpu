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
`vssrl.vv/vx/vi` and `vssra.vv/vx/vi` implement single-width scaling shifts:
the unsigned or signed 32-bit source is shifted by the low five bits of the
shift amount, then rounded according to `vxrm`. They share the fixed-point
rounding pipeline with narrowing clips, but read only `vs2`, permit source
and destination overlap, and never raise `vxsat`. Masked-off and inactive
lanes preserve old `vd`. The driver admits all three forms, requiring defined
register operands and, for masked forms, defined v0 and old `vd` with `vd != 0`.
Scaling results discard trusted byte-index provenance. The current shader
interface uses reset RNU, as for the other fixed-point operations.

The eight single-width integer reductions combine the active `vs2` lanes with
the scalar seed in `vs1[0]` and write the result to `vd[0]`. It also implements
`vrgather.vv/vx/vi`; each destination lane selects a source element
independently, and indices outside VLMAX produce zero.
`vslideup.vx/vi` and `vslidedown.vx/vi` move elements across lanes; slide-up
preserves destination elements below the offset, while slide-down returns zero
when its source index is outside VLMAX. The custom fragment-quad
`vquad.dfdx`/`vquad.dfdy`
cross-lane primitives over four-lane groups ordered TL, TR, BL, BR. These
encodings remain outside the driver shader profile until graphics dispatch
guarantees that ordering and supplies helper lanes at primitive edges.

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

Remaining RVV families (most widening/narrowing and the remaining VFUNARY1
forms) remain separate migration steps. The fixed SEW=32 profile implements
`vsext.vf2/vf4/vf8` and `vzext.vf2/vf4/vf8` as lane-local integer widening
operations: the low 16, 8, or 4 bits of each source lane are sign- or
zero-extended to 32 bits. Both masked and unmasked forms are supported;
masked-off and inactive lanes preserve the old destination. The decoder and
driver reject source/destination overlap and masked writes to v0. The driver
also requires defined source, predicate and preserved destination registers
for masked extensions. Full RVV
register-group and variable-SEW widening semantics remain outside this
profile.

The fixed profile also implements masked and unmasked `vnsrl.wv/wx/wi`
and `vnsra.wv/wx/wi`. Each lane forms a 64-bit source as
`{v[vs2+1][lane], v[vs2][lane]}` and keeps the low 32 bits after the shift.
Vector and scalar shift amounts use their low six bits; the five-bit
immediate is zero-extended. The source base must be even and the destination
must be disjoint from both source registers. Disabled lanes preserve old
`vd`; masked writes to v0 are rejected. Both source halves participate in
scoreboard hazards and must be defined for driver validation. This is a
lane-local pair layout, not the general RVV double-width register-group
layout; software must explicitly prepare the low and high word vectors.

`vnclipu.wv/wx/wi` and `vnclip.wv/wx/wi` reuse this source-pair layout and
its decode, scoreboard and driver restrictions. They shift the unsigned or
signed 64-bit value, round using `vxrm` (RNU, RNE, RDN or ROD), then saturate
to the unsigned or signed 32-bit range. Rounding occurs before the overflow
check, so rounding across a limit also saturates. Only enabled lanes can
raise the result's saturation flag; the backend sets the warp's sticky
`vxsat` on commit. The ALU captures `vxrm` with each request and preserves
results and flags under backpressure. The current shader interface uses the
reset RNU mode; it does not expose software writes to `vxrm`.

The backend now contains a behavioral per-warp vector register file and issue
boundary. Each warp owns 32 VLEN-wide registers with `vs1`, `vs2`, `vs2+1`, old-`vd`,
and dedicated v0 predicate reads plus one write port. The accompanying vector
scoreboard tracks all registers including v0 and supports same-cycle release
and re-issue. This behavioral storage is the architectural reference; a
multi-port macro wrapper is now available through
`VectorRegisterFile(useBlackBox = true)`: each warp bank mirrors ASAP7 1RW
SRAM macros for four operand reads plus the dedicated predicate read,
replicating writes across all copies and keeping them
visible to same-cycle reads through a write-through bypass. `GpuCore` selects
this physical file when `useBlackBoxes = true`.

`VectorBackend` connects vector issue, per-warp configuration, integer ALU,
Booth multiply, FP conversion, and round-robin vector writeback. It applies
`vl` to the lane mask, packs comparison bits into the architectural low bits of
the destination register, updates `vxsat`, serializes `vset*` behind older
operations from the same warp, and exposes launch-time VGPR initialization
plus scalar RF bridge ports.

The driver admits masked lane-local integer ALU, saturation, multiply,
divide and remainder operations in the same operand forms as their unmasked
counterparts. Predicate v0, the old destination and register sources must be
defined, and the destination cannot be v0. Masked comparisons, reductions,
gathers and slides remain outside the driver profile. Masked arithmetic
invalidates trusted local-index provenance; only an unmasked `vsll.vi ...,2`
can establish a complete-batch byte-index proof for indexed memory access.

The vector memory path applies the same `vl` and `v0` lane mask to unit-stride
loads and stores. Inactive load lanes preserve the old destination value; the
driver validator therefore requires a defined destination before admitting a
masked load.

`VectorMemoryUnit` also generates `vlse8/16/32.v` and `vsse8/16/32.v` lane
addresses from the signed two's-complement scalar byte stride. The coalescer
deduplicates every cache line touched by active lanes, serializes those line
requests, and reassembles elements that cross a line boundary. The driver
exposes the 32-bit forms when `rs2` is a directly materialized, aligned signed
constant and its abstract interpreter proves every lane address inside the
bound kernarg and writable output range.

For the fixed SEW=32 profile, the LSU also implements ordered and unordered
`vluxei32/vloxei32/vsuxei32/vsoxei32` using unsigned per-lane byte offsets from
`vs2`. Indexed accesses reuse the sparse cache-line coalescer. The driver
admits indices derived from the trusted launch-time local IDs by an exact
two-bit left shift and proves the complete batch span; other index provenance
is rejected.

Texture sampling is routed as a separate elastic vector path. `vtex.sample`
uses the warp active mask, `vl`, `vm`, and v0 predicate to select lanes; the
sampler serializes those lane requests and commits one vector destination while
preserving inactive lanes. This keeps sampler latency independent from the
integer/FP writeback arbiters while retaining in-order vector issue semantics.
