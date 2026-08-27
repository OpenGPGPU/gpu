# SIMT control-flow state

`SimtStack` is the per-warp divergence/reconvergence LIFO migrated from
OpenGPU. Each entry saves:

- the PC to resume;
- the active lanes for that path;
- the complete mask before divergence;
- whether the entry belongs to divergent control flow.

The migrated implementation intentionally fixes two problems in the source
design: pop addresses the actual top entry, and every successful pop removes
exactly one entry. The interface uses Decoupled push/pop handshakes, supports
simultaneous full-stack pop and push, and provides `clear` for hardware-warp
recycling.

This module stores one warp's control-flow state. `SimtBranchResolver`
calculates the taken and fall-through masks. `SimtBranchStackSequencer` then
pushes the reconvergence entry first and the alternate path second, making the
alternate path the next entry popped. It also reserves both free entries before
accepting divergence, so a partially written branch cannot deadlock its warp.

`SimtStackBank` instantiates this state independently for every hardware warp.
It routes push, pop, and recycle-clear operations only to the selected bank.
`SimtControlFlow` combines the resolver, stack sequencer, and bank, while
`SimtFrontendControl` feeds both newly selected and restored PC/mask pairs back
into `WarpScheduler`.

The old design's implicit "pop a divergent entry twice" behavior is not
retained. A completed path or join operation requests one explicit restore,
which pops exactly one entry.
