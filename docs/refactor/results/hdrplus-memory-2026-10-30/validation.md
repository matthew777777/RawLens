# HDR+ upload memory reduction

Baseline: Git HEAD `cb1f496b41cca3cf1ecb3474a9884510dbab1911`.

Reported device: Xiaomi M2004J19C, Android 11/API 30, Mali-G52,
RAW 4208×3120. The supplied 1,147-line log shows RUNNING_CRITICAL memory
pressure and logcat EOF/restarts, but no HDR+ capture/merge/crash stack.
Memory pressure is an investigation lead, not a confirmed crash diagnosis.

## Change

Upload N inputs sequentially using one coherent, mapped staging buffer,
waiting on a fence before overwriting it. Destroy upload staging before
allocating scratch. Input images have a separate arena, preventing scratch
UNDEFINED-to-GENERAL transitions from discarding already uploaded contents.
Keep the original shaders, frame count/order, normalization, strength,
alignment, compute dispatches, and output format.

At 4208×3120, an eight-frame upload previously retained 210,063,360 bytes
(200.3 MiB) of staging throughout compute. Compute now retains none of this
upload staging; upload itself needs one 26,257,920-byte frame (25.0 MiB).
GPU inputs, CPU packed frames, and merge scratch still consume memory.

On GPU wait failure, drain outstanding work before destroying resources;
a timeout alone does not cancel GPU use of descriptors/images/buffers.

## Validation

Desktop Apple M1 / MoltenVK, identical assets and runner for baseline/fix:

- Existing suite: all 18 checks pass (Fast/HQ identity, denoise,
  determinism, shift recovery, and HQ per-pass identity/shift).
- All existing suite float output bytes match HEAD, SHA-256:
  `99d620ddeb7474c099b0c2a621faab43102c505881acf5f34654a5860e433617`.
- New `sensor` case: eight distinct synthetic 4208×3120 frames, Fast + HQ;
  finite/nonempty outputs pass. Combined 105,031,680 output bytes match HEAD,
  SHA-256:
  `91a5a51c5b14fddecc27c6034055790e92790259d4dc3660acb19d4ca7934ebc`.

Android validation: `:app:assembleGithubRelease` passed, including both
arm64-v8a and armeabi-v7a native builds and release lint. APKs are under
`app/build/outputs/apk/github/release/`, version 2.0.12 (existing workspace
version). This build includes the user's pre-existing workspace changes.

Comparison builds and logs: `/tmp/rawlens-hdr-memory-compare`.
See `tools/hdrplus-parity/README.md` for the reusable sensor test and
`HDRPLUS_PARITY_OUTPUT` export mechanism.

The affected Xiaomi was not connected. The connected device reports model
25080RABDG. Desktop parity establishes unchanged output on the tested GPU;
it does not establish driver compatibility or successful capture on Mali-G52.
