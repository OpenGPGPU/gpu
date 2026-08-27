# GPU frontend

`GpuFrontend` connects warp scheduling, SIMT control flow, instruction fetch,
and unified decode.

The instruction-memory interface is decoupled and permits one outstanding
request. Request metadata (warp ID, PC, and active-lane mask) remains registered
until the matching in-order response enters `DecodePipe`. A response and the
next request can transfer in the same cycle, allowing a one-cycle-hit memory to
sustain one instruction per cycle.

An instruction access fault is routed to the scalar trap path independently of
the returned instruction bits. It is not reported as an illegal instruction
and cannot accidentally select the FPU or vector output.

The interface deliberately stops before selecting a concrete I-cache. A later
cache or scratchpad must preserve response order and must not return a response
in the same cycle as the first request; registered one-cycle responses are
supported.
