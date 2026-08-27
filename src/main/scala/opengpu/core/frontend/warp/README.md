# Warp frontend protocol

`WarpScheduler` owns the active, blocked, PC, lane-mask, and round-robin state
for every hardware warp.

- `launch.fire` allocates the hardware warp slot named by
  `WarpLaunch.warpId`. The dispatcher snapshots the slot before initializing
  its registers, so the scheduler must honor the named slot rather than
  re-deriving the lowest free one — a concurrent `finish` can free a lower
  slot during the initialization window. `launch.ready` backpressures unless
  the named slot is free.
- A runnable warp is selected fairly and placed in a one-entry registered
  `issue` output.
- Selection blocks that warp immediately, so backpressure cannot create a
  duplicate frontend request.
- `resume` supplies the next PC and active-lane mask, then makes a blocked warp
  runnable again.
- `finish` releases a blocked warp after cease has been accepted and all of
  that warp's pipeline work has drained.

The registered issue boundary is intentional. ASAP7 WC post-route analysis of
the combinational version found a failing register-to-output path through the
round-robin selector and PC mux. The registered version meets the 1 GHz target
and can still consume one grant and load the next grant every cycle.

The next frontend block should consume `issue` as a fetch request. Scoreboard
hazards must prevent `resume` until the decoded instruction is safe to issue;
branch resolution uses the same resume path with its redirected PC and
lane mask.

Completed SIMT branch paths, scalar redirects, and stack restores are
arbitrated in that priority order before updating scheduler state. Lower
priority requests remain backpressured rather than being dropped.
