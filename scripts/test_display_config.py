#!/usr/bin/env python3
"""Check that the ARTI display geometry stays consistent across the tree.

The integration profile owns the resolution. Everything else either follows it
at build time or is a hand-written copy that can silently drift:

  driver/gpu.dtsi            documents the driver binding with literal values
  driver/gpu_integration.yaml the reference profile the dtsi values describe

`run_arti_gpu.sh` and friends already read the profile, so this only guards
the literals. Run from the repository root.
"""
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / "driver" / "gpu_integration.yaml"
DTSI = ROOT / "driver" / "gpu.dtsi"
RESOLVER = ROOT / "scripts" / "gpu_display_config.py"


def profile_field(name):
    out = subprocess.run(
        [sys.executable, str(RESOLVER), "--field", name, str(PROFILE)],
        text=True, capture_output=True)
    if out.returncode:
        raise SystemExit(f"FAIL: {out.stderr.strip()}")
    return int(out.stdout.strip())


def dtsi_property(name):
    match = re.search(rf"{name}\s*=\s*<([^>]+)>", DTSI.read_text())
    if not match:
        raise SystemExit(f"FAIL: gpu.dtsi has no {name} property")
    return int(match.group(1), 0)


def main():
    width = profile_field("width")
    height = profile_field("height")
    framebuffer = profile_field("framebuffer_size")
    stride = dtsi_property("opengpu,stride")
    checks = (
        ("gpu.dtsi opengpu,width", dtsi_property("opengpu,width"), width),
        ("gpu.dtsi opengpu,height", dtsi_property("opengpu,height"), height),
        # The dtsi describes the reference profile, whose format is 32bpp.
        ("gpu.dtsi opengpu,stride", stride, width * 4),
    )
    failed = False
    for label, got, want in checks:
        if got != want:
            print(f"FAIL: {label} is {got} but {PROFILE.name} says {want}",
                  flush=True)
            failed = True
        else:
            print(f"{label}: {got} (matches {PROFILE.name})", flush=True)
    if framebuffer != width * height * 4:
        print(f"FAIL: {PROFILE.name} framebuffer_size does not match "
              f"{width}x{height}", flush=True)
        failed = True
    else:
        print(f"framebuffer_size: 0x{framebuffer:x}", flush=True)
    if failed:
        raise SystemExit(1)
    print("PASS: display geometry is consistent", flush=True)


if __name__ == "__main__":
    main()
