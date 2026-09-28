#!/usr/bin/env python3
"""Measure ARTI model throughput without booting QEMU or a guest.

The embedded ARTI model is a plain C++ library (`arti_rtl_model.h` plus
`libarti_rtl_model.a`), so it can be linked into a host probe that drives the
control AXI-Lite port directly and times it. That turns "is the simulator the
bottleneck?" into a seconds-long A/B instead of a multi-minute Debian boot.

Two rates matter and they differ by three orders of magnitude:

  idle   a quiet register read; one settle exits after SETTLE_MIN + IDLE_GRACE
         with the design quiescent.
  active a hardware clear, which keeps the fill FSM and the memory AXI live so
         the settle runs real GPU cycles.

`active` is the rate that bounds a game frame. Measured on the 64x64 Debian
FlashSim model: idle ~2-3 MHz, active ~0.45-0.6 MHz, with a wide run-to-run
spread. The gap is per-cycle evaluation cost, not settle policy, so
ARTI_MODEL_IDLE_GRACE and ARTI_MODEL_MMIO_ADVANCE are not the knobs for it.
See docs/GRAPHICS_ROADMAP.md.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]

PROBE = r"""
#include "arti_rtl_model.h"
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>

static uint8_t *mem;
static const uint64_t MEM_BASE = 0x40000000ull;
static const uint64_t MEM_SIZE = 1ull << 26;

/* Write mask is a per-byte strobe for the `size` bytes at `addr`; a nonzero
 * return is reported to the DUT as a SLVERR. */
static int mem_read(uint64_t addr, uint8_t *data, unsigned size, uint64_t id) {
  (void)id;
  if (addr < MEM_BASE || addr + size > MEM_BASE + MEM_SIZE) return -1;
  memcpy(data, mem + (addr - MEM_BASE), size);
  return 0;
}

static int mem_write(uint64_t addr, const uint8_t *data, unsigned size,
                     uint64_t mask, uint64_t id) {
  (void)id;
  if (addr < MEM_BASE || addr + size > MEM_BASE + MEM_SIZE) return -1;
  uint8_t *p = mem + (addr - MEM_BASE);
  for (unsigned i = 0; i < size; i++)
    if (mask & (1ull << i)) p[i] = data[i];
  return 0;
}

static double now_s(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return (double)t.tv_sec + (double)t.tv_nsec * 1e-9;
}

static uint32_t reg_read(unsigned off) {
  uint64_t v = 0;
  if (arti_rtl_model_read(off, &v, 4) != 0) {
    fprintf(stderr, "read 0x%x failed\n", off);
    exit(2);
  }
  return (uint32_t)v;
}

static void reg_write(unsigned off, uint32_t v) {
  if (arti_rtl_model_write(off, v, 4) != 0) {
    fprintf(stderr, "write 0x%x failed\n", off);
    exit(2);
  }
}

int main(int argc, char **argv) {
  unsigned idle_reads = argc > 1 ? (unsigned)strtoul(argv[1], nullptr, 0) : 200;
  unsigned fill_bytes = argc > 2 ? (unsigned)strtoul(argv[2], nullptr, 0) : 32768;
  unsigned fill_reps = argc > 3 ? (unsigned)strtoul(argv[3], nullptr, 0) : 1;

  mem = (uint8_t *)calloc(1, MEM_SIZE);
  if (!mem) return 3;
  arti_rtl_model_set_memory_callbacks(mem_read, mem_write);
  arti_rtl_model_init();

  /* 0x47550001 = GPU_ID, ('GU' << 16) | GPU_VERSION. */
  if (reg_read(0x00) != 0x47550001u) {
    fprintf(stderr, "FAIL: GPU_ID readback mismatch\n");
    return 1;
  }

  double t0 = now_s();
  uint32_t sink = 0;
  for (unsigned i = 0; i < idle_reads; i++) sink ^= reg_read(0x00);
  double t1 = now_s();
  if (!sink) fprintf(stderr, "note: idle readback XOR folded to zero\n");
  double idle_us = idle_reads ? (t1 - t0) * 1e6 / idle_reads : 0.0;
  printf("idle_us %.3f\n", idle_us);

  /* Legacy hardware clear: 64-byte aligned, byte count a multiple of 64. One
   * MMIO starts it; the settle returns once the fill FSM goes quiescent, so
   * this is a single active settle rather than a poll loop. */
  double t2 = now_s();
  for (unsigned r = 0; r < fill_reps; r++) {
    reg_write(0x088, (uint32_t)MEM_BASE);   /* CLEAR_BASE */
    reg_write(0x08c, fill_bytes);           /* CLEAR_BYTES */
    reg_write(0x090, 0x00ff00ffu);          /* CLEAR_PATTERN */
    reg_write(0x094, 1u);                   /* CLEAR_START */
    if (reg_read(0x008) & 0x8u) {           /* STATUS.CLEAR_BUSY */
      fprintf(stderr, "fill still busy after the submit settle\n");
      return 4;
    }
  }
  double t3 = now_s();
  double fill_s = t3 - t2;
  if (fill_reps) fill_s /= fill_reps;
  uint32_t word;
  memcpy(&word, mem, sizeof(word));
  if (word != 0x00ff00ffu) {
    fprintf(stderr, "FAIL: clear pattern readback 0x%08x\n", word);
    return 1;
  }
  printf("fill_ms %.3f\n", fill_s * 1e3);

  /* Flush the model's own throttled [artistats] line (it prints on call 256). */
  for (unsigned i = 0; i < 300; i++) arti_rtl_model_check_irq(0);
  return 0;
}
"""

# Instrumentation spliced into a copy of arti_rtl_model.cpp so the settle can
# report the dirty-list width and how many change-detection pages actually
# re-evaluate. The width is what disproves "the -O0 commit switch is the
# bottleneck"; the page count is what shows the gating prunes nothing.
INSTR = [
    ("static uint64_t g_stat_ticks;",
     "static uint64_t g_stat_ticks;\n"
     "static uint64_t g_stat_nw_sum, g_stat_nw_max, g_stat_nw_ticks;\n"
     "static uint64_t g_stat_pg_sum, g_stat_pg_max, g_stat_pg_n;"),
    ("  g_stat_ticks++;\n}",
     "  g_stat_ticks++;\n"
     "  g_stat_nw_sum += g_rtl->_nw;\n"
     "  if (g_rtl->_nw > g_stat_nw_max) g_stat_nw_max = g_rtl->_nw;\n"
     "  g_stat_nw_ticks++;\n}"),
    ("  g_rtl->tick_nba();",
     "  {\n"
     "    unsigned dirty = 0;\n"
     "    for (unsigned i = 0; i < %d; i++)\n"
     "      if (g_rtl->_seq_seen[i] != g_rtl->_pg[i]) dirty++;\n"
     "    g_stat_pg_sum += dirty;\n"
     "    if (dirty > g_stat_pg_max) g_stat_pg_max = dirty;\n"
     "    g_stat_pg_n++;\n"
     "  }\n"
     "  g_rtl->tick_nba();"),
    ("  g_stat_settles++;",
     "  g_stat_settles++;\n"
     "  g_stat_nw_sum = 0; g_stat_nw_max = 0; g_stat_nw_ticks = 0;\n"
     "  g_stat_pg_sum = 0; g_stat_pg_max = 0; g_stat_pg_n = 0;"),
    ('  if (g_arti_debug)\n    fprintf(stderr,\n            "[artidbg] settle ticks=%u',
     '  if (g_arti_debug)\n'
     '    fprintf(stderr,\n'
     '            "[artidbg-nw] ticks=%llu nw_avg=%.2f nw_max=%llu '
     'pages_dirty_avg=%.1f pages_dirty_max=%llu\\n",\n'
     '            (unsigned long long)g_stat_nw_ticks,\n'
     '            g_stat_nw_ticks ? (double)g_stat_nw_sum / g_stat_nw_ticks : 0.0,\n'
     '            (unsigned long long)g_stat_nw_max,\n'
     '            g_stat_pg_n ? (double)g_stat_pg_sum / g_stat_pg_n : 0.0,\n'
     '            (unsigned long long)g_stat_pg_max);\n'
     '  if (g_arti_debug)\n    fprintf(stderr,\n            "[artidbg] settle ticks=%u'),
]


def find_model_dir(explicit):
    if explicit:
        return Path(explicit)
    work = Path(os.environ.get("ARTI_WORK", ROOT.parent / "arti-work"))
    found = sorted(
        p for p in work.glob("*/arti-embedded-gen/generated/embedded")
        if (p / "libarti_rtl_model.a").is_file())
    if not found:
        raise SystemExit(
            f"no embedded ARTI model under {work}; build one with "
            "scripts/build_arti_debian_display.sh or pass --model-dir")
    return found[0]


def instrument(model_dir, source, dest):
    """Copy arti_rtl_model.cpp with dirty-width/page instrumentation spliced in."""
    pages = re.search(r"uint32_t _seq_seen\[(\d+)\]", (model_dir / "dut.h").read_text())
    if not pages:
        raise SystemExit("cannot read _seq_seen size from dut.h")
    count = int(pages.group(1))
    for old, new in INSTR:
        if old.count("g_rtl->tick_nba();") and "%d" in new:
            new = new % count
        if source.count(old) != 1:
            raise SystemExit(f"unexpected model shape for {old!r}")
        source = source.replace(old, new)
    dest.write_text(source)


def build(model_dir, build_dir, compiler, instrumented):
    archive = model_dir / "libarti_rtl_model.a"
    (build_dir / "probe.cpp").write_text(PROBE)
    if not list(model_dir.glob("dut_*.o")):
        raise SystemExit(f"no dut_*.o in {model_dir}; run build_embedded.sh first")
    link = [str(archive)]
    if instrumented:
        # The archive also carries arti_rtl_model.o; listing the instrumented
        # copy first satisfies the same symbols so the member is never pulled.
        instrument(model_dir, (model_dir / "arti_rtl_model.cpp").read_text(),
                   build_dir / "model_instr.cpp")
        obj = build_dir / "model_instr.o"
        subprocess.run([compiler, "-std=gnu++17", "-fPIC", "-fPIE", "-w",
                        "-Wno-parentheses-equality", "-fbracket-depth=4096",
                        f"-I{model_dir}", "-O1", "-c",
                        str(build_dir / "model_instr.cpp"), "-o", str(obj)],
                       check=True)
        link.insert(0, str(obj))
    binary = build_dir / ("probe_instr" if instrumented else "probe")
    subprocess.run([compiler, "-std=gnu++17", "-O2", "-w",
                    "-fbracket-depth=4096", f"-I{model_dir}",
                    str(build_dir / "probe.cpp"), *link, "-o", str(binary)],
                   check=True)
    return binary


def run(binary, idle_reads, fill_bytes, fill_reps, debug):
    env = dict(os.environ)
    if debug:
        env["ARTI_MODEL_DEBUG"] = "1"
    proc = subprocess.run(
        [str(binary), str(idle_reads), str(fill_bytes), str(fill_reps)],
        text=True, capture_output=True, env=env)
    sys.stdout.write(proc.stdout)
    sys.stderr.write(proc.stderr)
    if proc.returncode:
        raise SystemExit(f"probe failed (rc={proc.returncode})")
    fields = dict(line.split()[:2] for line in proc.stdout.split("\n")
                  if line and not line.startswith("["))
    return fields


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", help="embedded ARTI model directory")
    parser.add_argument("--build-dir", help="where to place the probe "
                                            "(default: a temporary directory)")
    parser.add_argument("--compiler", default=os.environ.get("CXX", "c++"))
    parser.add_argument("--idle-reads", type=int, default=200)
    parser.add_argument("--fill-bytes", type=int, default=32768)
    parser.add_argument("--fill-reps", type=int, default=1)
    parser.add_argument("--instrument", action="store_true",
                        help="also report dirty-list width and dirty pages. "
                             "This scans 707 pages per tick, so it inflates "
                             "the timings; use it for the counters, not for "
                             "wall-clock numbers")
    parser.add_argument("--keep", action="store_true",
                        help="keep the build directory for reuse")
    args = parser.parse_args()

    model_dir = find_model_dir(args.model_dir)
    build_dir = Path(args.build_dir) if args.build_dir else Path(
        tempfile.mkdtemp(prefix="opengpu-arti-probe."))
    build_dir.mkdir(parents=True, exist_ok=True)
    binary = build(model_dir, build_dir, args.compiler, args.instrument)

    print(f"model  : {model_dir}")
    print(f"probe  : {binary}")
    fields = run(binary, args.idle_reads, args.fill_bytes, args.fill_reps,
                 args.instrument)
    idle_us = float(fields["idle_us"])
    fill_ms = float(fields["fill_ms"])
    print()
    print(f"idle settle   {idle_us:8.1f} us/MMIO")
    print(f"active settle {fill_ms / 1e3:8.3f} s for a {args.fill_bytes} B "
          "hardware clear")
    print("A frame rate is cycles x (cycles/s). The active rate is the one that "
          "bounds it;\nsee scripts/benchmark_gpu.py for per-workload cycle counts.")
    if not args.keep and not args.build_dir:
        for path in sorted(build_dir.iterdir()):
            path.unlink()
        build_dir.rmdir()


if __name__ == "__main__":
    main()
