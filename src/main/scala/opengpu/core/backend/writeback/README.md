# Scalar commit and writeback

`ScalarCommitStage` treats register writeback and warp redirect as one atomic
commit. A destination-writing instruction cannot make its warp runnable until
the RF/scoreboard writeback port accepts the result. Instructions writing x0
need only the redirect.

Integer commits advance to `pc + 4`. Branch commits use the resolved target and
write `pc + 4` as the JAL/JALR link value. `ScalarBackend` fairly arbitrates
these commit requests before the shared commit stage.

`ScalarWritebackArbiter` is available for later multi-unit integration. It uses
round-robin arbitration and consumes x0 writes without occupying the RF port.
