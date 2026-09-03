# OpenGPU — Development Opportunities

Architecture review from docs + code (2026-09-03). Captured for planning;
no implementation commitment is implied by this document.

**Verdict:** M1–M7 of the graphics/host path are landed through the vertex-core
ARTI/Linux test. The highest leverage work is now scaling compute and graphics
into one SoC-facing product surface — not stacking more isolated fixed-function
blocks.

---

## Maturity snapshot

| Layer | Status | Open edge |
|---|---|---|
| Fixed-function graphics | Strong | Full frustum clipping complete; 16×16 full-system default |
| Unified SIMT shading | Strong | Vertex + fragment Linux draws pass end to end |
| Host / DRM / KMS | Strong | Compute UAPI + hardware clear unused |
| Compute core / RVV | Solid subset | Reductions / gather / strided mem; CSR/trap polish |
| Multi-CU + DMA | RTL present | Not on the ARTI/Linux graphics attach path |
| Userspace GL/VK | Not started | M8 after ABI + resolution stabilize |

---

## Completed this iteration

### 1. Reconcile roadmap / host-interface / guest-test status

- **Area:** Docs / process · **Leverage:** Medium · **Effort:** S
- Vertex-core status is now aligned across `GRAPHICS_ROADMAP.md`,
  `HOST_INTERFACE.md`, `DRIVER_ARCHITECTURE.md`, the README and guest harness.
- README now describes the Linux/DRM surface and uses CI's JDK 11 as its minimum.

### 2. Parameterize the render resolution beyond 16×16

- **Area:** Graphics system / integration · **Leverage:** High · **Effort:** M
- `EmitGpuHostAxi --width/--height` elaborates any power-of-two target
  (>= 16x16; verified the screen-bounds constants reach TriangleRasterizer and
  GeometryStage SV while the top file stays invariant).
- The ARTI runner threads one `GPU_WIDTH`/`GPU_HEIGHT` knob through the
  emitter args, the driver's `OPENGPU_DEFAULT_WIDTH/HEIGHT` boot-mode
  fallbacks, the guest test's `TEST_WIDTH/TEST_HEIGHT` (now overridable with a
  static-assert guard), and scales `ARTI_GPU_DRAW_WAIT_MS` with the pixel
  count (60 s baseline at 16x16).
- Follow-up: measure per-resolution full-system runtimes and pick a default
  the ARTI regression can afford; hardware clear via `FillEngine` pairs with
  this (larger clears dominate CPU traffic).

---

## Do next

### Expose general compute + DMA through Linux

`GpuSystem` already has multi-CU dispatch, copy/fill/strided DMA, and tagged
commands. The host top and DRM UAPI are graphics-draw only.

### Unify `GpuSystem` and `GpuHostAxi` integration tops

Compute-first multi-CU system and graphics AXI host are parallel morphologies.
A product SoC needs one coherent attach point (shared L2, IRQ/IH, DMA engines,
graphics).

### Wire `FillEngine` as hardware clear / blit

Roadmap deferred clear to a driver fill; the driver still `memset`s depth.
`FillEngine` exists in `GpuSystem` and would cut CPU traffic at larger
resolutions.

---

## Mid horizon

| Opportunity | Why |
|---|---|
| High-value RVV families | Reductions, widening/narrowing, slide/gather, masked/strided/gather memory unlock real compute and richer shaders |
| Grow shader sandbox | Hardware runs broader RV32IMF+V; Linux admits a tiny verified subset |
| ROP features | More blend modes, stencil, MSAA before Mesa is meaningful |
| CSR / trap contracts | Unimplemented ports and trap arbitration exist; precise host-visible faults incomplete |
| Physical timing on full graphics tops | Compute and raster-quad have ASAP7 PPA history; composite host+OM+texture+CU is the next silicon risk |

---

## Long horizon (correctly deferred)

- **M8 — Mesa / Vulkan / OpenGL ES** — only after ABI, resolution, clipping, and
  compute/graphics tops stabilize.
- **Discrete form (IOMMU / PCIe / local VRAM)** — keep the MMIO/command interface
  portable; do not invest before the integrated path is product-shaped.
- TBDR and on-die display PHY remain out of GPU RTL scope for v1.

---

## Suggested sequencing

1. **Make it useful** — resolution scale with a perf budget → hardware clear via `FillEngine`.
2. **One SoC product** — merge `GpuSystem` capabilities into the host/Linux attach path; expose compute + DMA jobs beside graphics.
3. **Broaden the ISA surface** — high-value RVV + sandbox growth, then OM features, then Mesa.

---

## What not to chase yet

Discrete PCIe/IOMMU/VRAM, TBDR, on-die display PHY, and a full Mesa stack are
correctly deferred. Investing there before resolution scaling and a single host
top would fragment the project.

---

## References

- `docs/GRAPHICS_ROADMAP.md`
- `docs/HOST_INTERFACE.md`
- `docs/DRIVER_ARCHITECTURE.md`
- `README.md`
- RTL under `src/main/scala/opengpu/`
- Linux driver under `driver/`
