# Functional backlog

Authoritative non-PPA work list for the GPU core, graphics path and Linux
driver. Physical timing work stays in [timing/README.md](../timing/README.md).
Detailed feature contracts remain in the other documents under `docs/`.

## In progress: GPU virtual-memory isolation

Completed foundations:

- [x] Private executable mappings for compute, fragment and vertex snapshots.
- [x] Private mappings for kernargs, vertex buffers, textures, command and
  render descriptors, framebuffer planes and fill/blit/strided DMA.
- [x] Reject VM-enabled mapping failures instead of falling back to physical
  addresses (`0dc83b4`, `48700fb`).
- [x] Revoke private resource mappings on unbind and cross-window rebind with
  ASID-scoped TLB invalidation (`25e9770`, `38d0ae1`).

Remaining work:

- [x] Add MMU tests proving that the same VA resolves to different physical
  pages, revocation is context-local and a recycled ASID gets a fresh root.
- [x] Cover DRM ioctl guest-path isolation for same-VA/different-PA fill,
  cross-window rebind and context teardown with ASID recycle
  (`opengpu_drm_test.c`).
- [x] Cover DRM ioctl guest-path bind-time private-map allocation failure,
  including cleanup and a successful follow-up submission.
- [x] Track and revoke temporary per-job mappings when their lifetime ends,
  without racing the next job's reuse of a fixed VA window.
- [x] Separate resolve and PA-tagged line-invalidate requirements from the
  context address space.
- [x] Replace full-space identity inheritance in context roots with explicit
  mappings. Keep ASID 0 only for controlled Bare bring-up.

Completion means a context root cannot address another context's resources or
unbound storage, and no validated VM job depends on VA equal to PA.

## Submission and recovery verification

- [x] Cover private-map allocation failure through render and compute ioctls,
  including failed-fence status, untouched output and a successful follow-up
  submission in the guest DRM path.
- [ ] Cover context destruction reset during page walks or delayed writes and
  completion backpressure; queued-work teardown and ASID reuse are now covered
  in the guest path.
- [ ] Add randomized legal command sequences with memory backpressure and
  injected descriptor, translation and bus faults.
- [ ] Promote the full Scala suite, host driver tests and selected guest paths
  into a documented functional qualification gate.

## Shader software and ISA

- [ ] Add a small userspace library for context, buffer, binding, submission
  and fence operations, plus compute and graphics examples.
- [ ] Add symbolic shader assembly and validate a small compiler-generated
  shader corpus instead of relying on hand-written instruction words.
- [ ] Expose software control of `vxrm` where shader ABI semantics are defined.
- [ ] Admit masked comparisons, reductions, gathers and slides after validator
  data-flow rules cover their predicate and destination dependencies.
- [ ] Add remaining useful fixed-profile RVV widening/narrowing and VFUNARY1
  operations as workload-driven steps.

## Graphics correctness and coverage

- [ ] Define helper-lane behavior and guarantee 2x2 fragment-quad ordering,
  then admit `vquad.dfdx` and `vquad.dfdy` in the shader profile.
- [ ] Add software-reference image comparisons for rasterization, depth,
  stencil, blend, texture and 1x/2x/4x MSAA combinations.
- [ ] Broaden persistent-depth and multisample continuation coverage in the
  ARTI guest path.
- [ ] Decide from workloads whether centroid/per-sample interpolation or
  framebuffer compression is needed before implementing either.

## Platform work

- [ ] Replace simulation-only virtual vblank/scanout with a target-specific
  display integration when a platform is selected.
- [ ] Evaluate a small Mesa/Gallium experiment only after the userspace ABI,
  VM isolation and functional qualification baseline are stable.

Demand paging, discrete PCIe/local VRAM, tile-based rendering and display PHY
design remain out of scope for the current implementation.
