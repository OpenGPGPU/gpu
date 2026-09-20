#!/usr/bin/env python3
"""Check that boundary edits cannot silently omit system regressions."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
def select(path):
    return subprocess.check_output(["bash", "scripts/select_tests.sh", path], cwd=root, text=True).split()

for path in ("graphics/RenderHost.scala", "command/GpuCommandRouter.scala",
             "dma/CopyEngine.scala", "core/memory/TranslatedWordClient.scala",
             "system/GpuHostAxi.scala"):
    selected = select("src/main/scala/opengpu/" + path)
    assert "opengpu.system.*" in selected, (path, selected)
    assert "opengpu.graphics.*" in selected, (path, selected)
assert select("src/main/scala/opengpu/config/GpuConfig.scala") == ["ALL"]
assert select("src/main/scala/yunsuan/fpu/FloatFMA.scala") == ["ALL"]
assert select("README.md") == ["NONE"]
print("Integration test selection: PASS")
