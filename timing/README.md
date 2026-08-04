# Decoder timing experiments

All timing inputs in this directory are generated from the repository's real
Chisel modules.

Generate SystemVerilog:

```sh
sbt "runMain gpu.elaboration.EmitTimingRtl timing/generated"
```

The 1 GHz constraint is in `constraints/decode_1ghz.sdc`. ChipAgent reports and
the exact submitted RTL snapshots belong under `reports/`.

Each report records SHA-256 hashes of every submitted RTL input. Recompute them
with:

```sh
shasum -a 256 timing/generated/*.sv timing/constraints/*.sdc
```

`FullInstructionDecoder.sv` is the current combinational decoder.
`DecodePipe.sv` is the current one-stage registered wrapper. It does **not**
split routing and detailed decoding across two register-to-register stages, so
it must not be presented as a true two-level timing alternative.

`TwoStageDecodePipe.sv` is the real two-level elastic implementation. Its first
level registers the Scalar/FPU/RVV route and its second level registers the
detailed decode result.

## Integrated core baseline

Report `032_gpu_core_rv32i_closure_no_cts_wc` is the first physical baseline
for the integrated `GpuCore` RV32I fetch-to-commit loop. It uses the ASAP7 WC
corner, a 1000 ps clock, and the same FakeRAM LEF/Liberty views as the scalar
register-manager experiments.

The post-route core-clock result is 496.54 MHz with -1013.93 ps setup slack at
the 1 GHz target. The worst path starts at
`scalar.issue.metadata.deq_ptr_value[1]`, crosses the wide metadata queue
read/dispatch/ALU network, and ends at
`scalar._integerAdapter_io_out_bits_data[31]`. This is a measured need for an
elastic register after scalar issue, not an estimated result.
