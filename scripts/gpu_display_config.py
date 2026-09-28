#!/usr/bin/env python3
"""Read the GPU display geometry out of an ARTI integration profile.

The profile is the single source of truth for the resolution. ARTI already
derives its QEMU scanout constants from it, and the elaboration, the driver
default mode, the work-tree names and the PPM check all have to agree, so the
scripts read it from here instead of each carrying their own GPU_WIDTH
default. Choosing a different size means using a different profile, not
setting a variable that silently disagrees with it.

`framebuffer_size` is checked against width*height*4 rather than trusted: a
mismatch would give ARTI a scanout window larger than its vram allocation.
"""
import argparse
from pathlib import Path
import re
import sys

BPP = 4


def display_block(text):
    """Return the lines of the top-level `display:` mapping."""
    lines = text.splitlines(keepends=True)
    start = next((i for i, line in enumerate(lines)
                  if line.rstrip() == "display:"), None)
    if start is None:
        raise SystemExit("integration profile has no top-level display: block")
    end = start + 1
    while end < len(lines) and (not lines[end].strip() or lines[end][:1].isspace()):
        end += 1
    return "".join(lines[start:end])


def scalars(block):
    """Map key -> int for the numeric keys of a YAML block.

    Deliberately not a YAML parser: the profile is a flat scalar mapping and
    the keys must match exactly, so a real parser would only add a dependency
    and hide typos behind implicit string coercion.
    """
    out = {}
    for match in re.finditer(r"^(\s*)([a-z_]+):[ \t]*([^#\n]*)", block, re.M):
        key, value = match.group(2), match.group(3).strip().replace("_", "")
        if not value:
            continue
        try:
            out[key] = int(value, 0)
        except ValueError:
            continue
    return out


def read(path):
    block = display_block(Path(path).read_text())
    fields = scalars(block)
    missing = [key for key in ("width", "height", "framebuffer_size")
               if key not in fields]
    if missing:
        raise SystemExit(f"{path}: display block is missing {', '.join(missing)}")
    want = fields["width"] * fields["height"] * BPP
    if fields["framebuffer_size"] != want:
        raise SystemExit(
            f"{path}: display.framebuffer_size is 0x{fields['framebuffer_size']:x} "
            f"but {fields['width']}x{fields['height']} needs 0x{want:x}")
    if fields["width"] < 16 or fields["height"] < 16:
        raise SystemExit(f"{path}: resolution must be at least 16x16")
    return fields


def shell(path):
    fields = read(path)
    # Quoted so a value can never be word-split or glob-expanded by eval.
    print(f"GPU_WIDTH={fields['width']}; "
          f"GPU_HEIGHT={fields['height']}; "
          f"GPU_STRIDE={fields['width'] * BPP}; "
          f"GPU_FRAMEBUFFER_SIZE=0x{fields['framebuffer_size']:x}; "
          f"GPU_MODE={fields['width']}x{fields['height']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", help="integration profile (YAML)")
    parser.add_argument("--shell", action="store_true",
                        help="emit eval-able shell assignments")
    parser.add_argument("--field", help="print one numeric field instead")
    args = parser.parse_args()
    if args.field:
        fields = read(args.profile)
        if args.field not in fields:
            raise SystemExit(f"no display.{args.field} in {args.profile}")
        print(fields[args.field])
        return
    if args.shell:
        shell(args.profile)
        return
    fields = read(args.profile)
    for key in sorted(fields):
        print(f"{key}={fields[key]}")


if __name__ == "__main__":
    main()
