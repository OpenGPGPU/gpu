# GPU package layout

- `config`: elaboration-time architectural configuration.
- `core/frontend/decode`: instruction classification, decode contracts, and decode pipelines.
- `core/frontend/warp`: hardware-warp allocation, runnable state, and round-robin scheduling.
- `core/backend/register`: scalar register storage, bypass, and atomic issue/writeback boundary.
- `core/backend/scoreboard`: per-warp dependency reservation and release.
- `core/execute`: execution-unit control types and datapaths.
- `core/simt`: lane replication and warp-level datapaths.
- `top`: synthesizable integration tops.
- `elaboration`: RTL generation entry points; not instantiated as hardware.

Dependencies should point inward from integration to leaf blocks:

`top -> core -> config`

ISA encodings may be added under `opengpu/isa`; leaf decoders may depend on those
definitions, but ISA packages must not depend on pipeline or execution modules.
