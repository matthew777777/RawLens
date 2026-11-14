# Public-source photo RCD baseline tools

> See [current repository state](../../docs/CURRENT_STATE.md) for app integration, current merge names, build requirements and the scope of historical evidence.

Host-only GPL-3.0-or-later tooling. No production integration or Android device
tests. Mathematical source: pinned RawTherapee RCD; original LuisSR files are
provenance evidence only. See upstream/manifest.json and the accompanying license
texts. See ../../docs/refactor/rcd-replacement/ for contracts, failures and results.

Requirements: Python 3 stdlib, clang++; GPU tests additionally need glslangValidator,
spirv-val, Vulkan headers/loader and a working host GPU driver. The provided host
build command uses Homebrew paths on macOS; no Android SDK or adb is invoked.

```sh
python3 tools/photo-rcd/validate.py --work /tmp/rawlens-photo-rcd-m1/validation --report docs/refactor/rcd-replacement/host-validation.json
python3 tools/photo-rcd/validate_real.py /tmp/rawlens-photo-rcd-m1/real-validation docs/refactor/rcd-replacement/real-host-validation.json
python3 tools/photo-rcd/gpu_validate.py --work /tmp/rawlens-photo-rcd-m1/gpu-validation --report docs/refactor/rcd-replacement/gpu-host-validation.json
```

`build_oracle.py` validates vendored SHA-256 before extracting anything. It emits
the RCD source adaptation patch, border body, exact three arithmetic helpers,
binary and compiler/flag/hash record into the supplied directory. It replaces
application/UI includes with the shim; no equation substitutions. Do not use an
old packed shader, binary or expected-pixel set to update these tools.

`baseline.comp` is a separate FP32 SSBO public-source port. It preserves the pinned
upstream tile valid domains and odd-column diagonal-response lookup convention.
No in-place reconstruction, FP16, packing or output fusion. It is deliberately
untuned. GPU report thresholds are the predeclared diagnostic budgets, not a
claim of universal CPU/GPU bit identity. Intermediate snapshots are mapped using
upstream tile coordinates. The host runner keeps resources until completion.

## Real RAW crop reproduction

The three small `fixtures/*.f32` CFA crops come from raw.pixls.us entry 3115,
individually marked CC0. Exact original SHA-256, license entry, crop coordinates,
normalization, decoder and crop hashes are in fixtures/manifest.json. The original
photographer is not identified in that entry; no author name is invented. CC0
text accompanies the crops. Full DNG/PGM downloads stay outside the repository.

```sh
curl -L --fail https://raw.pixls.us/json/getrepository.php -o /tmp/rawlens-photo-rcd-m1/rawpixls-repository.json
curl -L --fail 'https://raw.pixls.us/getfile.php/3115/nice/Ricoh%20-%20GR%20III%20-%2014bit%20(3:2).DNG' -o /tmp/rawlens-photo-rcd-m1/ricoh-gr-iii.dng
unprocessed_raw -q /tmp/rawlens-photo-rcd-m1/ricoh-gr-iii.dng
python3 tools/photo-rcd/import_real_fixture.py /tmp/rawlens-photo-rcd-m1/ricoh-gr-iii.dng /tmp/rawlens-photo-rcd-m1/ricoh-gr-iii.dng.pgm /tmp/rawlens-photo-rcd-m1/rawpixls-repository.json
```

Decoder used: LibRaw 0.22.2. No gamma, autoscale, WB, demosaic or color conversion.
Import verifies original DNG hash, published individual license/checksum entry,
PGM dimensions, DNG BlackLevel=64 per site and WhiteLevel=16314 directly. Do not
substitute the 14-bit storage maximum 16383 for the tagged white level. Import
is specific to this verified fixture, not a general-purpose untrusted TIFF reader.

No tool here measures delivered mobile fps. Required release modes remain
1080p30, 1440p30 and 4K24 under the research report's real-cadence contract.

## Continued optimization and app artifact checks

`experiment_validate.py` compares packed/tiled/direct/specialized scheduling to
the saved validated GPU corpus; `--stages` also checks owned stage values against
the pinned scalar oracle. `benchmark.py` includes the packing dispatch in warm
host GPU timestamps. Reports show packing and shared reconstruction were slower
on this host. The app retains baseline and optional direct/specialized shaders.

`engine_validate.py` runs the actual app-native implementation on the host,
including persistent resource reuse and invalid-input recovery.
`validate_packaged_engine.py` runs both NDK-compiled shaders extracted from an
inspected APK. `inspect_packages.py` checks current APK shader/source/license
hashes, required JNI exports and retired identifier inventory without disassembly.
`input_contract_test.cc` checks the actual native input/correction/output adapters
under ASan/UBSan. JVM PhotoRcdCompletionTest checks grade-fence ownership failures.

The app's internal validation route remains synchronous and hidden. Separate
grading/LUT/scaling/P010, final FP16 storage, true Android fence ownership and
all-profile output quality still require device validation. Do not enable a
public recording choice from host checks or timing alone.

## Requirements-derived GPU input prototype

The new raw_cell_pack.comp, requirements_input_shaders.py, requirements_input_host.cc and their validation/Android drivers implement integer Bayer-cell input and compact public-baseline reconstruction. These new RawLens/Codex tools are GPL-3.0-or-later; generated baseline reconstruction retains its RawTherapee authorship/notices. See docs/refactor/rcd-replacement/gpu-input-implementation.md for commands, tested scope, stage sizing, licenses/provenance context and remaining camera/video integration. Standalone GPU correctness and pack-only timings do not establish recording fps.

Encoder-owned P010 delivery can be checked with `run_android_recordings.py
--gpu-writer --requirements-input ...`. It remains internal and capability/content
probed per take/output size. The startup synthetic pattern is never queued to
recording. `--reconstruction-scale 4` explicitly reduces reconstruction detail;
this is diagnostic approximate RCD, not quality acceptance. See
`docs/refactor/rcd-replacement/independent-encoder-delivery.md`. CPU RGB readback
and synchronous reconstruction remain in this route.
