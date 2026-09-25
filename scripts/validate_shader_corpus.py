#!/usr/bin/env python3
"""Build symbolic and GCC-generated RV32 shader examples; check driver profiles."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SHADERS = ROOT / "userspace/shaders"
GCC = os.environ.get("RISCV_GCC", "riscv64-unknown-elf-gcc")
OBJCOPY = os.environ.get("RISCV_OBJCOPY", "riscv64-unknown-elf-objcopy")
HOST_CC = os.environ.get("CC", "cc")
FLAGS = ["-march=rv32im_zicsr", "-mabi=ilp32", "-O2", "-ffreestanding",
         "-fno-pic", "-fno-asynchronous-unwind-tables"]

ASSEMBLY = ("compute_copy", "round_modes", "masked_ops", "fixed_width",
            "widen_alu", "fragment_texture", "fp_unary")
COMPILED = ("compute_increment", "fragment_tint", "vertex_offset")
ALL = ASSEMBLY + COMPILED
# validate.c profiles: 0=compute, 1=fragment, 2=vertex, 3=fragment+texture,
# 4=compute with local_items=4 (FP VFUNARY1 needs all CU lanes defined)
PROFILES = (0, 0, 0, 0, 0, 3, 4, 0, 1, 2)


def run(args):
    subprocess.run(args, check=True)


def binary(assembly, destination):
    obj = destination.with_suffix(".o")
    run([GCC, "-march=rv32imv_zicsr", "-mabi=ilp32", "-c", "-x", "assembler",
         str(assembly), "-o", str(obj)])
    run([OBJCOPY, "-O", "binary", "--only-section=.text", str(obj),
         str(destination)])


def compiler_assembly(source, destination):
    raw = destination.with_suffix(".gcc.s")
    run([GCC, *FLAGS, "-S", str(source), "-o", str(raw)])
    lines = raw.read_text().splitlines()
    start = lines.index("shader:") + 1
    end = next(i for i in range(start, len(lines))
               if lines[i].strip().startswith(".size\tshader,"))
    body = [line.strip() for line in lines[start:end] if line.strip()]
    if body[-1] != "ret" or any(line.startswith(".") or line.endswith(":")
                                     for line in body):
        raise RuntimeError(f"{source}: compiler emitted unsupported control flow")
    # The shader validator requires direct x1-relative memory operands.
    # Rewrite GCC's C argument base only in those operands; no other ABI
    # register or stack use is admitted by the corpus.
    instructions = []
    for line in body[:-1]:
        if "(" in line and "(a0)" not in line:
            raise RuntimeError(f"{source}: unsupported memory operand {line}")
        instructions.append(line.replace("(a0)", "(x1)"))
    destination.write_text(".option norvc\n.text\n.globl shader\nshader:\n" +
                           "".join(f"    {line}\n" for line in instructions) +
                           "    .insn i 0x73, 0, zero, zero, 0x305\n")


def emit_shader(name, destination, work):
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    if name in ASSEMBLY:
        binary(SHADERS / f"{name}.S", destination)
    elif name in COMPILED:
        assembly = work / f"{name}.s"
        compiler_assembly(SHADERS / f"{name}.c", assembly)
        binary(assembly, destination)
    else:
        raise SystemExit(f"unknown shader {name!r}; choose from {', '.join(ALL)}")


def validate_all(out):
    paths = [out / f"{name}.bin" for name in ALL]
    for name, path in zip(ASSEMBLY, paths[:len(ASSEMBLY)]):
        binary(SHADERS / f"{name}.S", path)
    for name, path in zip(COMPILED, paths[len(ASSEMBLY):]):
        assembly = out / f"{name}.s"
        compiler_assembly(SHADERS / f"{name}.c", assembly)
        binary(assembly, path)
    validator = out / "validate"
    run([HOST_CC, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
         str(SHADERS / "validate.c"), "-o", str(validator)])
    args = [str(validator)]
    for path, profile in zip(paths, PROFILES):
        args.extend([str(path), str(profile)])
    run(args)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--emit", nargs=2, metavar=("NAME", "DEST"),
                        help="write one corpus shader binary and exit")
    args = parser.parse_args()
    if args.emit:
        with tempfile.TemporaryDirectory(prefix="opengpu-shader-emit-") as temp:
            emit_shader(args.emit[0], args.emit[1], Path(temp))
        return
    with tempfile.TemporaryDirectory(prefix="opengpu-shader-corpus-") as temp:
        validate_all(Path(temp))


if __name__ == "__main__":
    main()
