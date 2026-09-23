#!/usr/bin/env python3
"""Check that boundary edits cannot silently omit system regressions."""
from pathlib import Path
import json
import subprocess

root = Path(__file__).resolve().parents[1]


def select(path):
    return subprocess.check_output(
        ["bash", "scripts/select_tests.sh", path], cwd=root, text=True
    ).split()


def shard_cmd(*args):
    return subprocess.check_output(
        ["bash", "scripts/ci_shard_tests.sh", *args], cwd=root, text=True
    ).strip()


for path in (
    "graphics/RenderHost.scala",
    "command/GpuCommandRouter.scala",
    "dma/CopyEngine.scala",
    "core/memory/TranslatedWordClient.scala",
    "system/GpuHostAxi.scala",
):
    selected = select("src/main/scala/opengpu/" + path)
    assert "opengpu.system.*" in selected, (path, selected)
    assert "opengpu.graphics.*" in selected, (path, selected)
assert select("src/main/scala/opengpu/config/GpuConfig.scala") == ["ALL"]
assert select("src/main/scala/yunsuan/fpu/FloatFMA.scala") == ["ALL"]
assert select("README.md") == ["NONE"]

# CI shard partition: every Spec belongs to exactly one shard; selection
# intersects so a graphics-only change only schedules the graphics runner.
all_specs = {
    str(p.relative_to(root / "src/test/scala").with_suffix("")).replace("/", ".")
    for p in (root / "src/test/scala").rglob("*Spec.scala")
}
assigned = {}
for shard in ("graphics", "system", "core-a", "core-b"):
    suites = shard_cmd("suites", shard, "ALL").split()
    for suite in suites:
        assert suite not in assigned, (suite, assigned[suite], shard)
        assigned[suite] = shard
assert set(assigned) == all_specs, (
    sorted(all_specs - set(assigned)),
    sorted(set(assigned) - all_specs),
)
assert json.loads(shard_cmd("shards-json", "ALL")) == [
    "graphics",
    "system",
    "core-a",
    "core-b",
]
assert json.loads(shard_cmd("shards-json", "NONE")) == []
assert json.loads(shard_cmd("shards-json", "opengpu.graphics.*")) == ["graphics"]
graphics_fanout = " ".join(select("src/main/scala/opengpu/graphics/RenderHost.scala"))
assert "graphics" in json.loads(shard_cmd("shards-json", graphics_fanout))
assert "system" in json.loads(shard_cmd("shards-json", graphics_fanout))

print("Integration test selection: PASS")
