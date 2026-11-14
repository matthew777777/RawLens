# Standalone LK-Reloaded repository exporter

> See [current repository state](../../docs/CURRENT_STATE.md) for app integration, current merge names, build requirements and the scope of historical evidence.

Create a separate checkout (the destination must be outside RawLens):

```sh
python3 tools/lkr-standalone/sync.py /tmp/LK-Reloaded-Super-Resolution
python3 tools/lkr-standalone/sync.py /tmp/LK-Reloaded-Super-Resolution --check
git -C /tmp/LK-Reloaded-Super-Resolution init -b main
```

Edit the canonical pipeline in RawLens, then run the same sync command again.
Copied files keep their paths and bytes, including source headers. Templates here
supply only the standalone README, build/run scripts, root CMake, and packaging.
The SHA-256 manifest records the exact working-tree snapshot, including local edits.
New source files must be added to RawLens Git before export. Generated builds,
datasets, app code, and unrelated dependencies are not exported.

The exporter refuses to overwrite edits made in the standalone checkout. Copy
intentional changes back to the same paths in RawLens first, then sync again.
It removes only previously managed files removed from the export selection.
RawLens stays the source of truth; no automatic two-way conflict resolution occurs.

The copyright owner authorized Apache-2.0 relicensing of the pipeline on
2026-11-11. RawLens itself keeps its existing license; exported dependencies retain
their own notices.
