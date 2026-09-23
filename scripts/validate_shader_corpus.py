#!/usr/bin/env python3
"""Build symbolic and GCC-generated RV32 shader examples; check driver profiles."""
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


def main():
    with tempfile.TemporaryDirectory(prefix="opengpu-shader-corpus-") as temp:
        out = Path(temp)
        paths = [out / f"{name}.bin" for name in
                 ("compute_copy", "round_modes", "compute_increment", "fragment_tint", "vertex_offset")]
        binary(SHADERS / "compute_copy.S", paths[0])
        binary(SHADERS / "round_modes.S", paths[1])
        for name, path in zip(("compute_increment", "fragment_tint", "vertex_offset"),
                              paths[2:]):
            assembly = out / f"{name}.s"
            compiler_assembly(SHADERS / f"{name}.c", assembly)
            binary(assembly, path)
        validator = out / "validate"
        run([HOST_CC, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
             str(SHADERS / "validate.c"), "-o", str(validator)])
        run([str(validator), *map(str, paths)])


if __name__ == "__main__":
    main()
