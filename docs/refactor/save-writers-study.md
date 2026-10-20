# Save writers — study before refactor (2026-10-20)

Scope: `RawCameraController` `writer` (serial) and `dngWriter` (parallel)
executors, `saveRawFrame` (280 lines, one inline job lambda) and its routing.
Algorithm stages it calls (Galosh, AI denoise, development, HDR+/MFSR merges)
are out of scope.

## Structure today

- `writer`: 1 thread, bounded queue. JPEG/JXL development, HDR, AI denoise,
  sidecars. Comment: one at a time "so full-resolution CPU/GPU allocation
  peaks never overlap".
- `dngWriter`: `DNG_WRITER_THREADS` threads for DNG-only saves.
- `saveRawFrame` snapshots the paired result into `RawFrameMetadata` on the
  camera thread ("Freeze the exact paired result before crossing to the
  writer thread"), then queues one `OwnedCaptureJob` that writes the DNG
  (original / Galosh / AI variant) and optionally develops JPEG or JXL.

## Findings

| # | Finding | Proposed |
|---|---|---|
| W1 | Two comments disagree on the parallel DNG speedup ("~2x" at the executor, "~3x" at the routing); neither number has a measurement in the repo | Remove the unmeasured multipliers; describe the mechanism |
| W2 | Routing comment says everything touching the shared GPU developer stays on the serial `writer`, but DNG-only saves with **Galosh** enabled go to the parallel workers and call `developer.galoshCaptureCfa`. Not a race: `GaloshDenoiser.denoise` is `@Synchronized` ("one in-flight run at a time") | Correct the comment; behaviour unchanged |
| W3 | `saveRawFrame` is 280 lines with the whole job inline | Move the job body to its own class with an explicit input object |
| **W4** | Not everything is frozen at shutter time. Read **when the job runs** on the writer thread: `activeDevelopedFormat`, `activeJxlOutputSettings`, ETTR headroom (`ettrCaptureActive()`, `ettrSettings`), `developFocusView`, and `selectedCameraId` (DNG metadata overrides). With saves queued, a format/lens/ETTR change right after the shutter can apply to the earlier photo | **Owner decision**: snapshot them with the rest (behaviour differs only in that race) |

## Proposed move

1. W1/W2 comments (no behaviour change).
2. W3 extraction. If W4 is approved, the extracted job takes every input as
   a shutter-time snapshot; if not, it keeps reading those five values at
   run time through the host so behaviour stays identical.
