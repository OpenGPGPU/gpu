#!/usr/bin/env python3
"""Write the Mesa cross file with this CI run's AArch64 dependency prefix."""

from pathlib import Path
import sys

if len(sys.argv) != 4:
    raise SystemExit("usage: aarch64_cross_prefix.py TEMPLATE OUTPUT PREFIX")

lines = Path(sys.argv[1]).read_text().splitlines()
matches = [i for i, line in enumerate(lines) if line.strip().startswith("pkg_config_libdir")]
if len(matches) != 1:
    raise SystemExit("expected one pkg_config_libdir entry in cross file")
lines[matches[0]] = f"pkg_config_libdir = ['{Path(sys.argv[3]) / 'lib/pkgconfig'}']"
Path(sys.argv[2]).write_text("\n".join(lines) + "\n")
