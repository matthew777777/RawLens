# Remove unused HDR+ RGBA output

The app consumes `hdrp_cfa` only. `hdrp_output` was an RGBA16F twin
written by finalize and never read. Remove its Fast/HQ scratch allocations,
finalize descriptor binding 1, and GLSL image store. Preserve binding 2,
the push constants, workgroup geometry, normalization expression, and R32F
CFA store. Regenerate the shipped SPIR-V with:

```
glslangValidator -V --target-env vulkan1.1 app/src/main/assets/spirv/hdrplus/hdrp_finalize.comp -o app/src/main/assets/spirv/hdrplus/hdrp_finalize.spv
spirv-val --target-env vulkan1.1 app/src/main/assets/spirv/hdrplus/hdrp_finalize.spv
```

At 4208×3120, RGBA16F pixel storage is 105,031,680 bytes (100.17 MiB),
plus driver allocation overhead. This allocation and its per-pixel write
are removed in both Fast and HQ, independent of burst count.
The preceding upload-memory optimization remains in place.

## Parity

Same Apple M1/MoltenVK GPU, assets snapshotted before editing, exact baseline
executables saved before rebuild. Compare Git HEAD
`cb1f496b41cca3cf1ecb3474a9884510dbab1911`, the preceding upload fix,
and this change with the identical parity runner.

- Standard suite: all 18 checks pass. All 3,604,480 output bytes match,
  SHA-256 `99d620ddeb7474c099b0c2a621faab43102c505881acf5f34654a5860e433617`.
- Eight distinct 4208×3120 frames: Fast/HQ finite and nonempty; all
  105,031,680 output bytes match,
  SHA-256 `91a5a51c5b14fddecc27c6034055790e92790259d4dc3660acb19d4ca7934ebc`.
- SPIR-V validation passes; disassembly confirms bindings 0/2 and one
  `OpImageWrite` (CFA), with no RGBA descriptor or store.

## Timing

Six alternating before/after process runs, eight 4208×3120 synthetic
frames, both paths, no output-file export during timing. Before = upload
fix with RGBA retained; after = upload fix plus this removal.

| Median | Before | After |
|---|---:|---:|
| Fast GPU | 484.4 ms | 477.1 ms |
| HQ GPU | 1083.9 ms | 1072.05 ms |
| Whole process, both paths | 2.2394 s | 2.1655 s |

GPU improvements are 1.5% / 1.1%; ranges overlap, so these are small
observed changes, not evidence of a reliable device-independent speedup.
Whole-process time includes startup, shader creation, frame generation,
upload, compute, checking and cleanup. The memory saving is structural.
Raw samples and comparison hashes are in `results.json`.
Scripts, binaries, baseline assets and logs: `/tmp/rawlens-hdr-cfa-only`.
The affected Mali-G52 phone was not available for validation.

## Android build

`:app:assembleGithubRelease` passed in 54 seconds, compiling both ARM ABIs
and passing release lint. Version auto-bumped to 2.0.13. APKs are under
`app/build/outputs/apk/github/release/`. The build includes pre-existing
workspace changes; the affected Mali-G52 has not been tested on-device.
