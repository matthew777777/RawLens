# Upstream provenance: RAWR raw_denoise

## Integration update (2026-11-04)

Both RAWR and RawLens Wiener now share RawLens learned noise selection and
frame/profile fallbacks. RAWR retains its shrinkage shaders and inverse; full
capture output no longer claims upstream bit parity because noise inputs differ.

## Integration update (2026-11-02)

At the user’s explicit request, RawLens now mirrors RAWR’s Camera2 noise
adapter: average green S/O, then a=S/range and b=(S*black+O)/range², with
the same acceptance rules. Denoise WB uses actual COLOR_CORRECTION_GAINS,
averaging both greens without normalizing green to 1. The normalized-pass-through
adapter described later in this historical note has been superseded.


Source: https://github.com/adityawarmanfw/rawr

Originally vendored 2026-10-11 at 87c2209. Re-audited and restored to exact
source parity 2026-11-01 against revision
`537852ec0e109f78fb4ed54bc6a692d9c7cfca92`.

License: GPL-3.0-only (RawLens is GPL-3.0-or-later). The module itself ports
[darktable's](https://github.com/darktable-org/darktable) GPL denoiseprofile
wavelet path; see `app/src/main/cpp/wavelet/upstream/NOTICE.md`.

Byte-identical RAWR files:

- `native/raw_denoise/include/raw_denoise/DenoisePipeline.hpp` →
  `app/src/main/cpp/wavelet/upstream/include/raw_denoise/DenoisePipeline.hpp`
- `native/raw_denoise/src/DenoisePipeline.cpp` →
  `app/src/main/cpp/wavelet/upstream/src/DenoisePipeline.cpp`
- `native/raw_denoise/NOTICE.md` → the vendored `NOTICE.md`
- `native/raw_denoise/shaders/denoise_img_*.comp` → the four shaders here.
  SPIR-V is built with `glslangValidator -V --target-env vulkan1.1`.

The local atrous apply shortcut, partial-buffer allocation optimization, and
per-pass query extension were removed to satisfy exact source identity.

RawLens-specific integration remains: `wavelet_host.*`, JNI, Kotlin facade,
metadata/settings adapters and camera-linear readback/upload. Photo settings
mirror RAWR: strength 0–8 step 0.1, detail 0–1.8 step 0.05 (VST shadows),
luma 0–1 step 0.05 (forceY, independent of detail), scales 1–7; chroma 0.75.
Noise selection averages the CFA green sites. Camera2 SENSOR_NOISE_PROFILE
coefficients are already normalized and pass directly to the core; the
raw-DN/black-level conversion in RAWR's helper must not be applied again.
This Camera2 adapter deliberately differs from the pinned RAWR integration.
As of 2026-11-04, invalid/missing profiles fall back to learned/frame noise selection.

Reproduce source and same-GPU byte parity:

```sh
python3 tools/wavelet-parity/prove_rawr_parity.py \
  --reference /path/to/rawr --out /tmp/rawlens-wavelet-proof
```

This builds RAWR and RawLens independently, compiles reference shaders, checks
source/SPIR-V identity, and compares complete output bytes over ten cases.
The validation harness adapts include paths and probes the host portability
extension before enabling it; no algorithm changes are made in the harness.
`run_parity.sh` separately checks the frozen Python oracle with upstream
float tolerances. Neither test claims bit identity between different GPU
vendors, or between the complete RAWR and RawLens JPEG pipelines.

See `docs/refactor/wavelet-rawr-parity.md` and its saved JSON evidence.
