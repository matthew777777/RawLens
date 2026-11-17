# Shader optimization catalog (2026-11-17)

Every GPU shader in the repo, grouped into stages A→Z in the order we will
optimize them (per-frame viewfinder/record work first, then the photo/JPEG
path, then burst, then test-only). Work through one stage at a time:
before report → change → rebuild with the shader's own recipe → after report
→ image parity → device A/B timing. Record outcome in the **Progress** table.

## How the numbers were produced

Tools: malioc 2026.5.0 (Mali-G615, Mali-G720), AOC 7.0.26 / E17.52.18.00 (A750, Colima `rawlens-shaders`).

| Source set | Command (repo root) | Report dir (`build/shader-analysis/…`) |
|---|---|---|
| Packaged SPIR-V (77) | `python3 tools/shader-analysis/analyze.py --backend both --adreno-core a750 --core Mali-G615 --core Mali-G720 --output build/shader-analysis/before-2026-11-17` | `before-2026-11-17` |
| `vf_rcd*` per stage (app sets spec const 0 = stage; analyze.py does not) | `spirv-opt --set-spec-const-default-value "0:K" --freeze-spec-const X.spv -o build/shader-analysis/specialized/before/X_sK.spv`, then analyze.py on that dir | `before-2026-11-17-specialized` |
| Build-time / native SPIR-V: capture sharpening (`glslc --target-env=vulkan1.1 -O -I…`), rawdn, mon/scopes (`glslangValidator -V --target-env vulkan1.1`), `app/build/generated/{photo-rcd-assets,srpspv}` copies | into `build/shader-analysis/catalog-spv/`, then analyze.py | `catalog-nonpackaged-before` |
| Runtime GLES compute (AMaZE, quad, rcd_final, preprocess, AgX JPEG/JXL, merged develop, RapidRAW modes) | `python3 tools/shader-analysis/gles.py --backend both --core Mali-G615 --core Mali-G720 --adreno-core a750 --output build/shader-analysis/gles-before` | `gles-before` |
| Table below | `python3 tools/shader-analysis/catalog.py before-2026-11-17 before-2026-11-17-specialized catalog-nonpackaged-before gles-before` | — |

Mali "cyc" = the largest pipeline's total cycles per invocation (the bound).
Compare a shader only against its own baseline; per-invocation cycles are not
weighted by dispatch size or frequency, which is why stage order (not table rank) sets priority.

## Not yet analyzable offline (add when the stage is reached)

- `BguSlice.kt` (BGU slice, GLES 3.0 vertex+fragment), `VfGpuImport.kt` (VF import fragment), `EncodedVideoMonitorGl.kt` (monitor blit): Kotlin string shaders — need extraction → `malioc --fragment` / AOC `-fs`. Belong to stage B.
- `shaders/record_cube.glsl`: include for the `_cube` variants (covered through those).
- FlowNet `flownetcorrfused` / `flownetupsample`: need the ncnn shader preprocessor.
- `tools/lkr-lite`, `tools/sr-pipeline` sources outside `sr_spv`: standalone tools, not in the app.
- `mon/` and `scopes_*` have no host references in `app/src/main` (possibly dead); scopes `*_measure` need `shared/common.glsl`, which is not in the repo.

## Field testing (Adreno or any phone without our dev setup)

Every Encoded Video recording now logs the GPU cost of the record kernels,
once per 120 frames, under the log tag `RawLensVfVk`:

```
vf-vk: fused record pipelines ready gpu=Mali-G615 MC2 paths=1/1 fusedpath=-1 timing=on
vf-vk: fused gpu 3840x2160->1920x1080 path=2 green=5.66/6.20 main=8.19/8.71 total=13.85 ms (avg/max, n=120)
```

- `gpu`: GPU name. `paths=1/1`: both per-shape pipelines compiled on this driver.
- `path`: pipeline used: 1 = 4K (crop = output), 2 = 1080p/1440p downscale, 0 = old all-path pipeline.
- `green` / `main`: GPU milliseconds for the green prepass / main record kernel (average/max over 120 frames). `total` is their sum; at 30 fps the frame budget is 33.3 ms.
- `timing=unsupported`: the driver has no timestamp queries. No numbers will be logged; please report the GPU name.

**Tester steps (needs `adb` and USB debugging):**

1. Install the build and grant camera permissions as usual. Let the phone cool down, keep it unplugged, close other camera apps.
2. Clear the log: `adb logcat -c`
3. Record about 20 s each at 4K, 1440p and 1080p (Encoded Video, 30 fps, default profile).
4. Save: `adb logcat -d -s RawLensVfVk > rawlens-gpu-default.txt`
5. Switch to the old pipeline: `adb shell setprop debug.rawlens.fusedpath 0`, then force-stop the app (`adb shell am force-stop com.matthew.rawlens`) and reopen it. The setting is read when the record pipeline is built.
6. `adb logcat -c`, repeat step 3, then `adb logcat -d -s RawLensVfVk > rawlens-gpu-fusedpath0.txt`
7. Reset: `adb shell setprop debug.rawlens.fusedpath ""` and force-stop the app again.
8. Send both files plus the phone model.

Reading the result: compare `main` for `path=2` (default file) against `path=0` (fusedpath0 file) at the same `WxH->WxH`. If path 2 is not faster on that GPU, the host can bind the all-path pipeline for that GPU family. The `green` column should match between the two files (same shader in both).

## Stage order

| Stage | Scope | Why here |
|---|---|---|
| A | `vf_rcd` (4 stages), `vf_rcdyuv*`, `vf_mhcyuv*`, `vf_mhc*` | Per-frame 4K record + VF RCD (also used by the JPEG RCD path); all 64 regs / 50 % occupancy on Mali |
| B | superpixel, gradeyuv, videograde, stabwarp, P010, BGU/VfGpuImport fragments | Per-frame VF / record |
| C | monitor overlays & scopes | Per-frame when enabled (verify they are live first) |
| D | `vf_rawdct`, `vf_rawdct_align` | Per-frame RAW video denoise; align 274 cyc, A750 31 % occupancy |
| E | dual-RAW, hexa | Capture modes |
| F | preprocess, `hlrecon`, `opposed` | JPEG highlights; `opposed` is arithmetic-bound (convert 92, SFU 35) |
| G | AMaZE 13 passes | JPEG demosaic; `rbpm` 110, `gcorr` 84, `final` 80 |
| H | quad green/color, `rcd_final` | JPEG demosaic alternatives |
| I | AgX / sigmoid output, `final_display`, `to_acescg` | Tone mapping |
| J | merged develop, RapidRAW modes | Develop |
| K–Q | HDR+, wavelet, RTCS, capture sharpening, MFSR, rawdn, FlowNet | Burst / offline |
| Z | photo_rcd candidates | Validation-only (`validatePhotoRcd*` flags default false) |

## Progress

| Stage | Status | Before → after (Mali G615 / A750) | Parity | Device timing |
|---|---|---|---|---|
| A1 `vf_mhcyuv` + `_cube` + `vf_mhc_green` | **done (b29cf687)**; 1080p cool re-run 5x600: wall p95 16.9 vs 20.3 ms, 0 over budget | Per-shape pipelines (FUSED_PATH): 1 = 4K interior register window, 3 = 4K border cells (second tiny dispatch), 2 = downscale reading exact colour-difference planes written by the coalesced green prepass. All pipelines spill-free on G615 + G720 (plain + cube). A750: path 1 occ 100 %, path 2 occ 75 % (= HEAD), path 3 75 % | 108/108 bit-identical end to end (prepass + main, plain + cube, specialized + all-path); green plane 108/108; negative test (swapped planes) fails 38/108 as it must; odd strides now correct | Phone (Mali-G615, 3x600 interleaved, `A1-device-ab7.log`, 41-43 C): GPU main p50 1080p 4.5 vs 8.8 ms, 1440p 7.4 vs 14.2, 4K 10.7 vs 20.2; GPU total p95 16.5 vs 18.9 / 24.9 vs 28.4 / 24.7 vs 32.9. Over-budget frames 1440p 54 vs 66, 4K 16 vs 61; 1080p 29 vs 14 from one noisy round (p95 range 16.8-29.4) -> re-run cool. |
| A2 `vf_rcd` (JPEG RCD) | next | — | — | — |

### Stage A notes (2026-11-17)

A1 design: interior 2x2 cells load raw CFA words for rows y-2..y+3 (3–4 words each) and packed green rows y-1..y+2 (3 words) once into registers, then decode every tap with constant indices; edge cells and the 2:1 / 3:2 downscale keep the per-tap path. Found on the way: for odd output widths the shipped kernel races (two invocations write one Y word), which is why even HEAD-vs-HEAD differs there; record sizes are even. Device A/B: `-PmhcHeadless` install, then `am instrument -e class com.matthew.rawlens.MhcHighlightDeviceTest#sustainedHeadlessFrameBudgets -e rawlensMhcPerf true -e abSpv /data/local/tmp/X.spv -e abRounds 3` (the bundled asset is the working-tree shader, abSpv is the alternate).

Live in production (host call sites checked):
- `vf_rcd.spv` (fp32, 4 specialized stages): only the JPEG RCD path (`Gles31AmazeProcessor.ensureRcdVulkan`). G615: 64 regs / 50 % occupancy in every stage, LS 55/41/29/52.
- `vf_mhcyuv.spv` + `_cube`: Encoded Video record, fp32 (`EncodedVideoRecorder` hardcodes `fusedAsset(f16Math = false)`).
- `vf_mhc_green.spv`: embedded at build time by CMake (`rawLensMhcGreenSpirv`).

No production caller (only selector functions in `VfRcd.kt` / `VfVideoGrade.kt`, or tests): `vf_rcd_f16`, `vf_rcdyuv*`, `vf_mhcyuv_f16*`, `vf_mhc.spv`. These are candidates for deletion, not optimization.

Tried on `vf_rcd`, no gain: cheap single-reflection mirror in `loadTile` (unchanged, or LS 41→66 on stage 1 without the modulo fallback); `[[dont_unroll]]` tile loop (identical). Register bisection: replacing the CFA unpack or the whole tile load leaves 64 regs, so the pressure is the stencil body. The f16 twin reaches 32 regs on stages 1/2 because its rgba16f image loads land packed.

`vf_mhcyuv` 4K path: about 23 scalar loads per output pixel pair, already close to the per-tap union (14 CFA + 9 green). Sharing words between the two pixels saves at most about 3 loads (only when the crop origin is even), at register-cliff risk. A real cut needs vector (uvec2/uvec4) CFA loads or a packed tile, which is a redesign that has to be timed on the phone.

## Baseline table

| Stage | Shader | G615 cyc | G615 regs/occ | G615 bound | G720 cyc | A750 instr | A750 regs/occ | A750 exposed long-lat |
|---|---|---:|---|---|---:|---:|---|---:|
| A Viewfinder / record demosaic | `shaders/vf/vf_mhcyuv` | 170.0 | 64/50% | load_store | 170.0 | 3272 | 16/75% | 20.3 % |
| A Viewfinder / record demosaic | `shaders/vf/vf_mhcyuv_cube` | 170.0 | 64/50% | load_store | 170.0 | 3371 | 16/75% | 24.6 % |
| A Viewfinder / record demosaic | `shaders/vf/vf_mhcyuv_f16` | 170.0 | 64/50% | load_store | 170.0 | 3365 | 16/75% | 20.6 % |
| A Viewfinder / record demosaic | `shaders/vf/vf_mhcyuv_f16_cube` | 170.0 | 64/50% | load_store | 170.0 | 3475 | 16/75% | 23.0 % |
| A Viewfinder / record demosaic | `shaders/vf/vf_rcdyuv` | 106.0 | 64/50% | load_store | 106.0 | 9473 | 17/62% | 15.9 % |
| A Viewfinder / record demosaic | `shaders/vf/vf_rcdyuv_cube` | 106.0 | 64/50% | load_store | 106.0 | 9556 | 17/62% | 17.4 % |
| A Viewfinder / record demosaic | `shaders/vf/vf_rcdyuv_f16` | 106.0 | 64/50% | load_store | 106.0 | 9649 | 13/75% | 10.9 % |
| A Viewfinder / record demosaic | `shaders/vf/vf_rcdyuv_f16_cube` | 106.0 | 64/50% | load_store | 106.0 | 9768 | 13/75% | 12.9 % |
| A Viewfinder / record demosaic | `shaders/vf/vf_mhc` | 58.0 | 32/100% | load_store | 58.0 | 1044 | 10/100% | 33.8 % |
| A Viewfinder / record demosaic | `shaders/vf/vf_mhc_green` | 55.0 | 32/100% | load_store | 55.0 | 707 | 11/100% | 39.2 % |
| A Viewfinder / record demosaic | `vf_rcd_f16_s0` | 55.0 | 64/50% | load_store | 55.0 | 793 | 6/100% | 16.7 % |
| A Viewfinder / record demosaic | `vf_rcd_s0` | 55.0 | 64/50% | load_store | 55.0 | 766 | 8/100% | 17.3 % |
| A Viewfinder / record demosaic | `vf_rcd_f16_s3` | 53.0 | 64/50% | load_store | 53.0 | 1716 | 10/100% | 40.4 % |
| A Viewfinder / record demosaic | `vf_rcd_s3` | 52.0 | 64/50% | load_store | 52.0 | 1662 | 12/100% | 38.1 % |
| A Viewfinder / record demosaic | `vf_rcd_f16_s1` | 41.0 | 32/100% | load_store | 41.0 | 931 | 7/100% | 19.3 % |
| A Viewfinder / record demosaic | `vf_rcd_s1` | 41.0 | 64/50% | load_store | 41.0 | 897 | 9/100% | 18.5 % |
| A Viewfinder / record demosaic | `vf_rcd_f16_s2` | 30.0 | 32/100% | load_store | 30.0 | 797 | 9/100% | 19.9 % |
| A Viewfinder / record demosaic | `vf_rcd_s2` | 29.0 | 64/50% | load_store | 29.0 | 778 | 8/100% | 22.2 % |
| B Viewfinder superpixel / grade / stab / P010 | `shaders/vf/vf_superpixel_f16` | 16.0 | 26/100% | load_store | 16.0 | 921 | 12/100% | 16.6 % |
| B Viewfinder superpixel / grade / stab / P010 | `shaders/vf/vf_stabwarp` | 14.0 | 32/100% | load_store | 14.0 | 507 | 7/150% | 21.8 % |
| B Viewfinder superpixel / grade / stab / P010 | `shaders/vf/vf_superpixel` | 10.0 | 18/100% | load_store | 10.0 | 182 | 4/100% | 32.1 % |
| B Viewfinder superpixel / grade / stab / P010 | `shaders/vf/vf_gradeyuv` | 7.1 | 32/100% | arith_total | 7.1 | 1659 | 5/100% | 14.7 % |
| B Viewfinder superpixel / grade / stab / P010 | `shaders/vf/vf_gradeyuv_cube` | 7.1 | 32/100% | arith_total | 7.1 | 1814 | 6/100% | 16.6 % |
| B Viewfinder superpixel / grade / stab / P010 | `shaders/vf/vf_p010preview` | 6.0 | 22/100% | load_store | 6.0 | 771 | 6/200% | 33.0 % |
| B Viewfinder superpixel / grade / stab / P010 | `shaders/vf/vf_superpixel_img` | 6.0 | 16/100% | load_store | 6.0 | 136 | 4/100% | 37.1 % |
| B Viewfinder superpixel / grade / stab / P010 | `shaders/vf/vf_superpixel_linear` | 6.0 | 17/100% | load_store | 6.0 | 203 | 6/100% | 22.4 % |
| B Viewfinder superpixel / grade / stab / P010 | `shaders/vf/vf_videograde` | 5.0 | 12/100% | load_store | 5.0 | 105 | 2/100% | 64.5 % |
| B Viewfinder superpixel / grade / stab / P010 | `shaders/vf/vf_p010torgb8` | 4.0 | 9/100% | load_store | 4.0 | 100 | 2/200% | 33.3 % |
| B Viewfinder superpixel / grade / stab / P010 | `shaders/vf/vf_superpixel_img_linear` | 2.0 | 16/100% | load_store | 2.0 | 186 | 4/100% | 28.7 % |
| C Monitor overlays & scopes | `mon/scopes_render_waveform` | 107.0 | 64/50% | load_store | 107.0 | 1402 | 27/37% | 24.9 % |
| C Monitor overlays & scopes | `mon/scopes_render_vectorscope` | 70.0 | 32/100% | load_store | 70.0 | 1415 | 14/75% | 19.2 % |
| C Monitor overlays & scopes | `mon/scopes_vectorscope_reduce` | 42.1 | 32/100% | load_store | 42.1 | 219 | 5/200% | 40.4 % |
| C Monitor overlays & scopes | `mon/scopes_exposure_stats_reduce` | 40.3 | 27/100% | load_store | 40.3 | 388 | 5/200% | 18.3 % |
| C Monitor overlays & scopes | `mon/mon_combined_overlay` | 37.0 | 32/100% | load_store | 37.0 | 899 | 6/100% | 20.7 % |
| C Monitor overlays & scopes | `mon/mon_focus_peaking` | 32.0 | 32/100% | load_store | 32.0 | 534 | 6/100% | 28.3 % |
| C Monitor overlays & scopes | `mon/mon_raw_state_overlay` | 7.0 | 11/100% | load_store | 7.0 | 183 | 4/200% | 68.1 % |
| C Monitor overlays & scopes | `mon/mon_false_color` | 6.0 | 12/100% | load_store | 6.0 | 137 | 3/200% | 67.8 % |
| C Monitor overlays & scopes | `mon/scopes_render_raw_waveform` | 6.0 | 13/100% | load_store | 6.0 | 504 | 3/200% | 20.8 % |
| C Monitor overlays & scopes | `mon/mon_tonemap_shadow` | 4.0 | 14/100% | load_store | 4.0 | 111 | 3/200% | 67.6 % |
| D RAW video DCT denoise | `shaders/vf/vf_rawdct_align` | 274.0 | 64/50% | load_store | 274.0 | 4088 | 7/31% | 3.2 % |
| D RAW video DCT denoise | `shaders/vf/vf_rawdct` | 45.1 | 64/50% | load_store | 45.1 | 3847 | 14/25% | 10.9 % |
| E Dual-RAW / hexa binning | `shaders/dualraw/seqavg` | 15.0 | 32/100% | load_store | 15.0 | 422 | 7/150% | 16.9 % |
| E Dual-RAW / hexa binning | `shaders/hexa/hexa_box` | 14.0 | 32/100% | load_store | 14.0 | 530 | 14/75% | 62.8 % |
| E Dual-RAW / hexa binning | `shaders/dualraw/merge` | 9.0 | 13/100% | load_store | 9.0 | 282 | 3/200% | 26.0 % |
| E Dual-RAW / hexa binning | `shaders/hexa/hexa_filter` | 7.0 | 26/100% | load_store | 7.0 | 257 | 4/125% | 59.3 % |
| E Dual-RAW / hexa binning | `shaders/hexa/hexa_point` | 4.0 | 17/100% | load_store | 4.0 | 142 | 3/200% | 56.9 % |
| F JPEG: preprocess + highlight recovery | `spirv/opposed` | 92.5 | 64/50% | arith_total | 92.5 | 8033 | 14/75% | 19.0 % |
| F JPEG: preprocess + highlight recovery | `spirv/hlrecon` | 61.0 | 32/100% | load_store | 61.0 | 1092 | 6/100% | 32.8 % |
| F JPEG: preprocess + highlight recovery | `spirv/preprocess` | 3.1 | 31/100% | load_store | 3.1 | 253 | 6/100% | 46.0 % |
| F JPEG: preprocess + highlight recovery | `raw__preprocess` | 2.0 | 32/100% | load_store | 2.0 | 250 | 6/100% | 40.8 % |
| G JPEG: AMaZE demosaic | `amaze__rbpm` | 110.0 | 52/50% | load_store | 110.0 | 701 | 7/50% | 7.5 % |
| G JPEG: AMaZE demosaic | `amaze__gcorr` | 84.0 | 44/50% | load_store | 84.0 | 1138 | 9/25% | 7.1 % |
| G JPEG: AMaZE demosaic | `amaze__final` | 80.0 | 64/50% | load_store | 80.0 | 1258 | 8/37% | 3.7 % |
| G JPEG: AMaZE demosaic | `amaze__gradcd` | 73.0 | 32/100% | load_store | 73.0 | 500 | 6/50% | 6.9 % |
| G JPEG: AMaZE demosaic | `amaze__hvwt` | 71.0 | 64/50% | load_store | 71.1 | 555 | 8/25% | 13.3 % |
| G JPEG: AMaZE demosaic | `amaze__area` | 51.0 | 62/50% | load_store | 51.0 | 3285 | 10/37% | 1.6 % |
| G JPEG: AMaZE demosaic | `amaze__nyqref` | 50.0 | 60/50% | load_store | 50.0 | 603 | 7/31% | 30.0 % |
| G JPEG: AMaZE demosaic | `amaze__green` | 42.0 | 32/100% | load_store | 42.0 | 523 | 6/62% | 24.0 % |
| G JPEG: AMaZE demosaic | `amaze__bound` | 35.0 | 32/100% | load_store | 35.0 | 702 | 5/81% | 6.0 % |
| G JPEG: AMaZE demosaic | `amaze__chroma` | 25.0 | 32/100% | load_store | 25.0 | 381 | 7/62% | 14.1 % |
| G JPEG: AMaZE demosaic | `amaze__nyq2` | 17.0 | 27/100% | load_store | 17.0 | 369 | 4/100% | 15.8 % |
| G JPEG: AMaZE demosaic | `amaze__pmrbint` | 17.0 | 30/100% | load_store | 17.0 | 326 | 5/81% | 26.1 % |
| G JPEG: AMaZE demosaic | `amaze__pad` | 2.1 | 15/100% | arith_total | 2.1 | 338 | 4/100% | 7.7 % |
| H JPEG: quad / RCD-final demosaic | `spirv/green` | 40.0 | 32/100% | load_store | 40.0 | 1916 | 16/75% | 12.2 % |
| H JPEG: quad / RCD-final demosaic | `spirv/color` | 23.0 | 32/100% | load_store | 23.0 | 1702 | 8/100% | 5.8 % |
| H JPEG: quad / RCD-final demosaic | `quad__green` | 9.9 | 32/100% | arith_total | 9.8 | 1568 | 16/75% | 14.5 % |
| H JPEG: quad / RCD-final demosaic | `quad__color` | 9.9 | 32/100% | arith_total | 9.6 | 1473 | 8/100% | 6.8 % |
| H JPEG: quad / RCD-final demosaic | `rcd__rcd_final` | 4.3 | 64/50% | arith_total | 4.3 | 1054 | 14/75% | 26.1 % |
| I JPEG: tone mapping / output | `amaze__final_display` | 80.0 | 64/50% | load_store | 80.0 | 2324 | 8/37% | 2.1 % |
| I JPEG: tone mapping / output | `display__agx_srgb8` | 6.9 | 32/100% | arith_total | 6.9 | 1491 | 8/100% | 2.3 % |
| I JPEG: tone mapping / output | `display__agx_srgb8_jxl` | 6.9 | 32/100% | arith_total | 6.9 | 1491 | 8/100% | 2.3 % |
| I JPEG: tone mapping / output | `amaze__to_acescg` | 3.0 | 10/100% | load_store | 3.0 | 38 | 2/100% | 35.7 % |
| J Develop: merged + RapidRAW | `merged__develop` | 48.0 | 64/50% | load_store | 48.0 | 1203 | 15/75% | 25.8 % |
| J Develop: merged + RapidRAW | `rapidraw__md_pick` | 30.3 | 13/100% | load_store | 30.3 | 293 | 6/200% | 66.1 % |
| J Develop: merged + RapidRAW | `rapidraw__md_hist` | 26.1 | 15/100% | load_store | 26.1 | 142 | 2/125% | 73.4 % |
| J Develop: merged + RapidRAW | `rapidraw__fused-agx-hdr` | 10.2 | 64/50% | load_store | 10.2 | 1714 | 9/100% | 3.4 % |
| J Develop: merged + RapidRAW | `rapidraw__fused-basic-hdr` | 10.2 | 64/50% | load_store | 10.2 | 1504 | 9/100% | 4.0 % |
| J Develop: merged + RapidRAW | `rapidraw__out_band` | 8.2 | 59/50% | load_store | 8.2 | 712 | 9/100% | 16.4 % |
| J Develop: merged + RapidRAW | `rapidraw__fused-agx-sdr` | 8.2 | 58/50% | load_store | 8.2 | 1475 | 9/100% | 4.3 % |
| J Develop: merged + RapidRAW | `rapidraw__fused-basic-sdr` | 8.2 | 58/50% | load_store | 8.2 | 918 | 9/100% | 10.4 % |
| J Develop: merged + RapidRAW | `rapidraw__md_sample` | 4.3 | 32/100% | arith_total | 4.3 | 1327 | 9/125% | 34.9 % |
| J Develop: merged + RapidRAW | `rapidraw__nr_filter` | 3.0 | 64/50% | texture | 3.0 | 1022 | 13/87% | 32.1 % |
| J Develop: merged + RapidRAW | `rapidraw__nr_relwiden` | 2.1 | 10/100% | load_store | 2.1 | 118 | 3/100% | 10.3 % |
| J Develop: merged + RapidRAW | `rapidraw__nr_upsample` | 2.1 | 32/100% | load_store | 2.1 | 182 | 6/100% | 35.8 % |
| J Develop: merged + RapidRAW | `rapidraw__nr_core` | 2.1 | 24/100% | load_store | 2.1 | 135 | 5/100% | 33.0 % |
| J Develop: merged + RapidRAW | `rapidraw__nr_half` | 2.0 | 28/100% | load_store | 2.0 | 162 | 5/100% | 34.4 % |
| J Develop: merged + RapidRAW | `rapidraw__nr_quarter` | 2.0 | 22/100% | load_store | 2.0 | 94 | 4/100% | 56.5 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrq_merge` | 105.0 | 64/50% | load_store | 105.0 | 1342 | 16/50% | 38.8 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrp_blur` | 95.0 | 60/50% | load_store | 95.0 | 1264 | 6/75% | 18.5 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrq_rms` | 66.0 | 32/100% | load_store | 66.0 | 515 | 23/50% | 84.9 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrq_mismatch` | 57.0 | 28/100% | load_store | 57.0 | 232 | 4/200% | 62.5 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrp_correct_upsampling` | 39.0 | 31/100% | load_store | 39.0 | 764 | 7/31% | 18.8 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrq_backward_dft` | 39.0 | 32/100% | load_store | 40.8 | 457 | 19/62% | 45.0 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrq_warp_rgba` | 34.0 | 51/50% | load_store | 34.0 | 1279 | 8/150% | 46.0 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrq_forward_dft` | 30.0 | 32/100% | load_store | 31.0 | 430 | 13/87% | 36.6 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrp_tile_diff` | 17.0 | 32/100% | load_store | 17.0 | 643 | 7/75% | 38.4 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrp_hot_pixel` | 15.8 | 19/100% | load_store | 15.8 | 394 | 4/100% | 35.1 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrp_accumulate` | 14.0 | 15/100% | load_store | 14.0 | 288 | 4/200% | 62.6 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrq_deconvolute` | 11.0 | 30/100% | load_store | 11.0 | 184 | 7/100% | 55.3 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrp_color_diff` | 10.0 | 13/100% | load_store | 10.0 | 87 | 4/200% | 72.9 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrp_warp` | 10.0 | 25/100% | load_store | 10.0 | 316 | 6/200% | 40.3 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrp_best_tile` | 9.0 | 29/100% | load_store | 9.0 | 260 | 8/150% | 32.4 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrp_avg_pool` | 6.0 | 9/100% | load_store | 6.0 | 64 | 3/200% | 80.6 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrq_mismatch_norm` | 6.0 | 10/100% | load_store | 6.0 | 85 | 2/200% | 50.5 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrq_to_rgba` | 6.0 | 12/100% | load_store | 6.0 | 163 | 3/200% | 59.1 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrp_mean` | 4.1 | 8/100% | load_store | 4.1 | 88 | 3/100% | 34.3 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrp_finalize` | 4.0 | 9/100% | load_store | 4.0 | 66 | 2/200% | 73.0 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrp_merge_weight` | 4.0 | 9/100% | load_store | 4.0 | 102 | 2/200% | 42.5 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrp_prepare` | 4.0 | 9/100% | load_store | 4.0 | 76 | 2/200% | 72.0 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrq_accumulate` | 4.0 | 9/100% | load_store | 4.0 | 78 | 2/200% | 84.3 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrq_border` | 4.0 | 25/100% | load_store | 4.0 | 74 | 3/200% | 73.2 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrq_region_mean` | 3.2 | 9/100% | load_store | 3.2 | 242 | 4/200% | 54.4 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrp_column_sum` | 2.0 | 7/100% | load_store | 2.0 | 78 | 2/100% | 76.8 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrp_upsample_align` | 2.0 | 7/100% | load_store | 2.0 | 92 | 2/200% | 49.7 % |
| K Burst: HDR+ align/merge | `spirv/hdrplus/hdrq_shift_table` | 1.0 | 10/100% | load_store | 1.0 | 67 | 2/100% | 0.0 % |
| L Burst: wavelet denoise | `spirv/wavelet/denoise_img_atrous` | 99.0 | 30/100% | load_store | 99.0 | 1768 | 22/50% | 42.3 % |
| L Burst: wavelet denoise | `spirv/wavelet/denoise_img_backtransform_buffer_rl` | 65.0 | 64/50% | load_store | 65.0 | 1709 | 12/100% | 34.3 % |
| L Burst: wavelet denoise | `spirv/wavelet/denoise_img_wiener_apply` | 46.0 | 50/50% | load_store | 46.0 | 675 | 9/75% | 56.6 % |
| L Burst: wavelet denoise | `spirv/wavelet/denoise_img_lowres_chroma` | 40.0 | 20/100% | load_store | 40.0 | 987 | 4/200% | 65.1 % |
| L Burst: wavelet denoise | `spirv/wavelet/denoise_img_threshold` | 27.1 | 12/100% | load_store | 27.1 | 234 | 3/200% | 34.9 % |
| L Burst: wavelet denoise | `spirv/wavelet/denoise_img_precondition_rl` | 11.0 | 64/50% | load_store | 11.0 | 266 | 9/125% | 64.2 % |
| L Burst: wavelet denoise | `spirv/wavelet/denoise_img_backtransform` | 4.0 | 19/100% | load_store | 4.0 | 102 | 2/200% | 56.9 % |
| L Burst: wavelet denoise | `spirv/wavelet/denoise_img_backtransform_buffer` | 3.0 | 23/100% | load_store | 3.0 | 139 | 2/200% | 49.9 % |
| L Burst: wavelet denoise | `spirv/wavelet/denoise_img_precondition` | 3.0 | 15/100% | load_store | 3.0 | 99 | 2/200% | 61.8 % |
| M Burst: RTCS | `spirv/rtcs_r3` | 229.0 | 64/50% | load_store | 229.0 | 2300 | 12/50% | 59.0 % |
| M Burst: RTCS | `spirv/rtcs_r6` | 221.0 | 64/50% SPILL | load_store | 221.0 | 2470 | 11/50% | 57.6 % |
| M Burst: RTCS | `spirv/rtcs_r2` | 213.0 | 64/50% | load_store | 213.0 | 2283 | 12/50% | 61.1 % |
| M Burst: RTCS | `spirv/rtcs_r1` | 197.0 | 64/50% | load_store | 197.0 | 2206 | 12/50% | 61.3 % |
| M Burst: RTCS | `spirv/rtcs_r4` | 188.0 | 64/50% SPILL | load_store | 188.0 | 1906 | 20/50% | 50.9 % |
| N Capture sharpening | `capture_sharpening/cs_deconv` | 80.0 | 64/50% | load_store | 80.0 | 1759 | 9/50% | 54.0 % |
| N Capture sharpening | `capture_sharpening/cs_radius` | 65.0 | 27/100% | load_store | 65.0 | 1904 | 5/200% | 38.1 % |
| N Capture sharpening | `capture_sharpening/cs_select` | 63.0 | 17/100% | load_store | 63.0 | 856 | 4/200% | 49.8 % |
| N Capture sharpening | `capture_sharpening/cs_tile_stats` | 61.0 | 27/100% | load_store | 61.0 | 1276 | 5/200% | 58.5 % |
| N Capture sharpening | `capture_sharpening/cs_contrast` | 51.3 | 27/100% | load_store | 51.3 | 868 | 5/50% | 58.8 % |
| N Capture sharpening | `capture_sharpening/cs_luma_blocks` | 48.0 | 15/100% | load_store | 48.0 | 361 | 2/200% | 65.3 % |
| N Capture sharpening | `capture_sharpening/cs_blend_raw` | 36.0 | 32/100% | load_store | 36.0 | 557 | 4/200% | 52.8 % |
| N Capture sharpening | `capture_sharpening/cs_iir` | 26.0 | 24/100% | load_store | 26.0 | 871 | 6/100% | 49.8 % |
| N Capture sharpening | `capture_sharpening/cs_apron` | 5.0 | 9/100% | load_store | 5.0 | 37 | 2/200% | 85.5 % |
| N Capture sharpening | `capture_sharpening/cs_transpose` | 5.0 | 13/100% | load_store | 5.0 | 80 | 2/200% | 79.8 % |
| N Capture sharpening | `capture_sharpening/cs_yold` | 4.0 | 8/100% | load_store | 4.0 | 77 | 2/200% | 68.2 % |
| O MFSR | `sr_spv/demosaic` | 208.0 | 48/50% | load_store | 208.0 | 2640 | 8/150% | 48.3 % |
| O MFSR | `sr_spv/robustness` | 167.0 | 64/50% | load_store | 167.0 | 3267 | 7/150% | 36.4 % |
| O MFSR | `sr_spv/ica` | 79.1 | 64/50% | load_store | 79.1 | 1833 | 40/25% | 70.6 % |
| O MFSR | `sr_spv/kernelnet` | 52.1 | 64/50% | load_store | 52.1 | 1228 | 10/100% | 25.3 % |
| O MFSR | `sr_spv/merge_splat` | 44.5 | 32/100% | load_store | 44.5 | 1280 | 9/125% | 49.3 % |
| O MFSR | `sr_spv/ica_wg` | 39.1 | 46/50% | load_store | 39.1 | 621 | 8/150% | 49.6 % |
| O MFSR | `sr_spv/align_wg` | 30.1 | 32/100% | load_store | 30.1 | 575 | 5/200% | 38.9 % |
| O MFSR | `sr_spv/fft` | 23.0 | 23/100% | load_store | 23.0 | 1750 | 3/200% | 23.3 % |
| O MFSR | `sr_spv/corr_l2_fused` | 20.1 | 48/50% | load_store | 20.1 | 1439 | 6/75% | 21.6 % |
| O MFSR | `sr_spv/grey_fft` | 15.1 | 28/100% | load_store | 15.1 | 3409 | 32/37% | 50.2 % |
| O MFSR | `sr_spv/grad` | 14.0 | 16/100% | load_store | 14.0 | 377 | 2/200% | 26.7 % |
| O MFSR | `sr_spv/corr` | 12.0 | 29/100% | load_store | 12.0 | 877 | 7/150% | 43.1 % |
| O MFSR | `sr_spv/corr_argmin_wg` | 8.1 | 27/100% | load_store | 8.1 | 395 | 3/50% | 36.9 % |
| O MFSR | `sr_spv/grey_fir` | 8.1 | 25/100% | load_store | 8.1 | 354 | 4/200% | 23.0 % |
| O MFSR | `sr_spv/gat` | 7.0 | 18/100% | load_store | 7.0 | 331 | 2/200% | 24.8 % |
| O MFSR | `sr_spv/pyr_down` | 6.1 | 17/100% | load_store | 6.1 | 744 | 4/200% | 27.2 % |
| O MFSR | `sr_spv/kernels` | 6.0 | 16/100% | load_store | 6.0 | 469 | 3/200% | 31.5 % |
| O MFSR | `sr_spv/corr_direct` | 5.1 | 31/100% | load_store | 5.1 | 361 | 5/200% | 38.1 % |
| O MFSR | `sr_spv/align` | 4.0 | 27/100% | load_store | 4.0 | 251 | 7/150% | 51.5 % |
| O MFSR | `sr_spv/divide` | 3.0 | 8/100% | load_store | 3.0 | 34 | 1/200% | 37.2 % |
| O MFSR | `sr_spv/merge_accumulate` | 3.0 | 7/100% | load_store | 3.0 | 33 | 1/100% | 48.7 % |
| O MFSR | `sr_spv/passthrough` | 1.0 | 5/100% | load_store | 1.0 | 10 | 1/100% | 0.0 % |
| P rawdn CFA denoiser | `rawdn/rawdn_dct` | 155.0 | 64/50% SPILL | load_store | 154.0 | 3260 | 40/25% | 41.1 % |
| P rawdn CFA denoiser | `rawdn/rawdn_fin` | 21.0 | 64/50% | load_store | 21.0 | 266 | 11/100% | 38.0 % |
| P rawdn CFA denoiser | `rawdn/rawdn_down` | 15.0 | 30/100% | load_store | 15.0 | 272 | 6/100% | 63.2 % |
| P rawdn CFA denoiser | `rawdn/rawdn_unpack` | 13.0 | 32/100% | load_store | 13.0 | 844 | 7/100% | 3.2 % |
| P rawdn CFA denoiser | `rawdn/rawdn_pack` | 12.0 | 32/100% | load_store | 12.0 | 369 | 6/100% | 4.2 % |
| P rawdn CFA denoiser | `rawdn/rawdn_resid` | 10.0 | 30/100% | load_store | 10.0 | 127 | 7/100% | 53.2 % |
| Q FlowNet ncnn layers | `shader/flownetcorrlookup` | 8.0 | 26/100% | load_store | 8.0 | 286 | 5/100% | 54.9 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/requirements_tile_image_rcd` | 934.0 | 64/50% SPILL | load_store | 934.0 | 22010 | 20/50% | 35.0 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/requirements_tile_local_rcd` | 932.0 | 64/50% SPILL | load_store | 932.0 | 22070 | 20/50% | 34.9 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/tiled` | 450.0 | 64/50% SPILL | load_store | 450.0 | 3741 | 13/75% | 37.8 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/requirements_normalized_shared_rcd` | 400.0 | 64/50% SPILL | load_store | 400.0 | 4498 | 20/50% | 44.1 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/recording_candidate_direct_raw` | 375.0 | 64/50% SPILL | arith_total | 375.0 | 88492 | 14/75% | 6.3 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/requirements_rcd` | 338.0 | 64/50% SPILL | load_store | 338.0 | 6166 | 22/50% | 49.2 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/recording_candidate_packed` | 296.0 | 64/50% SPILL | load_store | 296.0 | 14451 | 12/100% | 11.1 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/recording_candidate_images` | 280.0 | 64/50% | load_store | 280.0 | 16930 | 16/75% | 27.7 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/recording_candidate_cached` | 266.0 | 64/50% SPILL | load_store | 266.0 | 12676 | 11/100% | 9.7 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/recording_candidate` | 265.0 | 64/50% SPILL | load_store | 265.0 | 11393 | 13/87% | 43.3 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/images` | 260.0 | 64/50% SPILL | load_store | 260.0 | 3177 | 14/75% | 44.2 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/requirements_frame_global_rcd` | 245.0 | 64/50% SPILL | load_store | 245.0 | 43519 | 29/37% | 49.2 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/baseline` | 221.0 | 64/50% SPILL | load_store | 221.0 | 14570 | 15/75% | 7.3 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/requirements_normalized_rcd` | 214.0 | 64/50% | load_store | 214.0 | 3206 | 13/75% | 34.0 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/optimized` | 211.0 | 64/50% | load_store | 211.0 | 3175 | 13/87% | 34.1 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/requirements_normalized_cached_rcd` | 186.0 | 64/50% | load_store | 186.0 | 3148 | 14/75% | 38.3 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/responsecache` | 183.0 | 64/50% | load_store | 183.0 | 3382 | 12/100% | 34.0 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/requirements_frame_global_image_rcd` | 109.0 | 64/50% SPILL | load_store | 109.0 | 16715 | 27/37% | 46.1 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/recording_candidate_stage0` | 85.6 | 64/50% SPILL | arith_total | 85.6 | 9711 | 7/100% | 3.7 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/recording_candidate_stage3` | 57.0 | 64/50% | load_store | 57.0 | 2887 | 10/100% | 8.1 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/recording_candidate_stage4` | 48.4 | 23/100% | load_store | 48.4 | 566 | 5/100% | 26.9 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/recording_candidate_stage2` | 44.1 | 64/50% | load_store | 44.1 | 2161 | 10/100% | 17.6 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/recording_candidate_stage1` | 43.0 | 32/100% | load_store | 43.0 | 2120 | 7/100% | 8.4 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/recording_candidate_stage7` | 38.0 | 32/100% | load_store | 38.0 | 2033 | 6/100% | 5.8 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/requirements_normalize` | 23.0 | 26/100% | load_store | 23.0 | 547 | 8/100% | 26.2 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/requirements_pack` | 17.0 | 25/100% | load_store | 17.0 | 416 | 8/100% | 33.9 % |
| Z Test/validation-only photo RCD candidates | `photo_rcd/recording_candidate_stage6` | 7.3 | 32/100% | load_store | 7.3 | 264 | 6/100% | 51.0 % |
