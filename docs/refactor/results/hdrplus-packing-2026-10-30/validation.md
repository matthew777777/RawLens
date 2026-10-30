# HDR+ alignment storage and frame packing

HQ alignment storage now allocates N slots rather than 64. Each slot retains
its original 256-byte-aligned stride and original frame index, including an
unused reference slot. Frame limits and compute operations are unchanged.
At 4208×3120, default geometry is 143×111 tiles, stride 127,232 bytes:
64 slots = 8,142,848 bytes; eight slots = 1,017,856 bytes.
Saving: 7,124,992 bytes (6.80 MiB).

Packing uses ByteBuffer.put(ByteBuffer) rather than a Java byte array per
row. Tight rows copy the crop in one operation; padded rows copy one row
at a time. Pixel strides other than two retain the existing sample loop.
Source position/limit remain unchanged because packing uses a duplicate.
Normalization, CFA phase, crop selection and output byte order are unchanged.

## Merge parity

All existing desktop parity checks pass, including Fast/HQ identity,
denoising, determinism, shift recovery, and HQ per-pass checks. Eight distinct
4208×3120 frames also pass both paths. Output bytes match Git HEAD
`cb1f496b41cca3cf1ecb3474a9884510dbab1911`:

- Standard suite: 3,604,480 bytes; SHA-256
  `99d620ddeb7474c099b0c2a621faab43102c505881acf5f34654a5860e433617`.
- Full sensor: 105,031,680 bytes; SHA-256
  `91a5a51c5b14fddecc27c6034055790e92790259d4dc3660acb19d4ca7934ebc`.

Apple M1 / MoltenVK; saved HEAD executable and assets predate these changes.
The desktop test bypasses Android packing, so its packing equivalence is
checked separately by HdrPlusPackingDeviceTest, which invokes production
packFrame and compares its bytes against HEAD's row-array implementation.

## On-device benchmark method

Opt-in instrumented test: `-e class
com.matthew.rawlens.HdrPlusPackingDeviceTest -e rawlensHdrPacking true`.
Uses 4208×3120 frames with zero or 32 bytes of row padding, read-only views,
and native malloc buffers matching production output allocation. Three warmup
pairs, then eight alternating pairs; each sample packs/frees eight frames
sequentially, reusing the same synthetic source to limit test memory. Includes allocation/free/tracking overhead, excludes source
creation and parity comparisons. Reports medians and all samples. The HEAD
reference omits production argument validation and the new method is invoked
through reflection; these small overhead differences favor the baseline.
Also checks crop offsets, nonzero buffer origin, final-row mapping, heap and
direct inputs, read-only buffers, pixel stride four, and source position/limit.

Connected phone model: 25080RABDG, not the affected Mali-G52 Xiaomi.

## Android validation status

The ordinary app + instrumented-test build passed (both native ARM ABIs).
The phone's installed RawLens is signed with a different key, so updating
it with the benchmark build was refused. No uninstall was performed.
An isolated package `com.matthew.rawlens.hdrpackingprobe` was built via a
temporary Gradle init script, preserving the normal application ID in source.
Its app APK installed successfully. Installation of its test APK was refused
with `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`.
The user allowed the test APK installation; the retry succeeded. The
instrumented test passed (`OK (1 test)`, 9.017 seconds). All byte comparisons
and source/output buffer-state checks passed. The installed original RawLens
app was neither replaced nor uninstalled. Both temporary benchmark packages
were uninstalled after validation.

| Median of eight paired samples | HEAD row array | New direct copy | Reduction |
|---|---:|---:|---:|
| Eight tight 4208×3120 packs | 175.898 ms | 159.762 ms | 9.17% |
| Eight packs with 32-byte row padding | 172.443 ms | 166.447 ms | 3.48% |

These measure repeated packing of a synthetic source, with native allocation,
freeing, and tracking included; they do not measure complete capture or merge
time. The model 25080RABDG is not the affected Mali-G52 device. All raw samples
and instrumentation output are in `packing-log.txt` / `device-test.txt`.

Build and benchmark scripts/logs: `/tmp/rawlens-hdr-pack-*`.

## Production release

`:app:assembleGithubRelease -PfreezeVersion` passed in three minutes,
including both native ARM ABIs and release lint. Application ID remains
`com.matthew.rawlens`, version 2.0.18. ARM64 APK:
`app/build/outputs/apk/github/release/RawLens-2.0.18-release-arm64-v8a.apk`.
The local signing key differs from the phone's installed original app;
this APK was not installed over it. The workspace includes other existing
changes in addition to this HDR+ optimization.
