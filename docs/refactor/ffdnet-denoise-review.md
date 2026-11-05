# FFDNet denoiser review — 2026-11-05

Reviewed the standalone path end to end, including the shared LK-SR FFDNet prior. The implementation is usable as an opt-in experimental denoiser, but its noise and colour assumptions do not justify calling it a calibrated RAW denoiser.

## Corrections made

1. The colour guard previously smoothed absolute candidate chroma. Even identical first-pass and phase predictions could therefore lose genuine fine colour detail. It now filters the candidate-minus-reference correction, removes its low-frequency component, and adds it to the first-pass chroma. Identical predictions now remain bit-exact, including a fine chroma checkerboard. This deliberately also preserves residual detail/noise already present in the first pass.
2. The inverse GAT could turn negative infinity from an accelerator into a finite shadow value through its lower clamp. All network outputs are now checked before inverse transformation; failed predictions cannot commit to the photograph. Added prior argument/noise validation and regression coverage for NaN and both infinities.
3. The noise estimator's relative-error least-squares weights divided by measured variance, which can be zero in constant or quantized patches. The fit now uses its existing 1e-10 variance floor for these weights as well as its returned intercept.
4. Standalone input validation now rejects non-Bayer channel arrangements, invalid backend/stride selections, and white-balance ratios that overflow or underflow during normalization.

## Whole-path assessment

| Component | Assessment |
|---|---|
| Input/order | Single-frame pre-WB, pre-sharpen camera RGB. FFDNet precedes capture sharpening and replaces the post-demosaic wavelet stage. Existing pre-demosaic AI can still stack with it. Merged paths and sensor DNGs do not use this standalone stage. |
| Noise estimate | Re-samples native CFA sites and fits a shared green-derived Poisson-Gaussian model. Valid Bayer geometry is now enforced. This is an approximation after demosaic or earlier AI processing; it is not a per-channel sensor calibration. |
| GAT/model | Forward transform, packed channel ordering and inverse formula remain consistent with the existing LK-SR adapter. A fixed full-image normalization range prevents per-tile changes. Backend invalid values now fail before inverse clamping. |
| Luma | The first RGB FFDNet pass denoises brightness as well as colour. The phase pass defaults to zero additional luma transfer. Its invariant is weighted WB camera RGB using 0.2126/0.7152/0.0722, not calibrated XYZ luminance. No additional luma strength was enabled during this review. |
| Chroma | Stride-separated phases remain a heuristic for demosaic-correlated noise. The corrected guard preserves broad first-pass colour and shared fine detail. It cannot correct an existing first-pass cast or remove every broad chroma blotch. |
| Tiles | 512 pixels with 32-pixel halos and even 448-pixel core stride. Real-model full/tiled agreement, partial tiles, odd small dimensions, and phase coverage tested. Border extension uses clamping. |
| Strength/alpha | Final source/result blend; zero and intermediate strengths tested. Alpha remains unchanged. |
| Failures | Output is staged until the entire operation succeeds. Missing model, invalid settings, invalid network output, and tile failures tested. NPU opening can fall back; a failure during inference preserves the source rather than retrying the entire image on GPU. |
| Cost | Still expensive: multiple full-size half-float copies and two tiled inference passes. This review does not establish a new phone speed or memory benchmark. |

## Verification

- `lkr_denoise_check`: actual FFDNet synthetic MSE 0.00032815427 -> 0.0000019339354; tiled/full maximum difference 0.00048828. Strength, luma/chroma invariants, alpha, rollback, invalid backend tensors, and odd image boundaries pass.
- `lkr_colour_guard_check`: identical fine-colour predictions preserved bit-exactly; band/full agreement zero; weighted-brightness maximum error 0.00010818; synthetic opponent-colour MSE 0.00049990707 -> 0.0000061569303. These are controlled test results, not measured photographic ground-truth quality.
- `lkr_photo_check`: constant-image noise fit finite; synthetic shot/read noise fit a=0.000939724, b=0.0000187902 for true a=0.001, b=0.00001. Predicted variance within 30% at three tested levels. Invalid configuration and missing-model rollback pass.
- Colour-guard regression also passes AddressSanitizer and UndefinedBehaviorSanitizer.
- Android `DenoiseSettingsTest`: 4 tests, no failures. Android native build succeeds for arm64-v8a and armeabi-v7a. Build used `-PqualcommNpu=false`; it does not validate Qualcomm packaging or execution.
- Reprocessed the existing architecture, indoor, sunset and flower DNG crops locally with the final native implementation. All outputs finite; alpha unchanged. See `artifacts/ffdnet-study/review-metrics.json`, each scene's `review.log`, and `review-comparison.jpg`. Rendering uses the existing desktop AHD/reference pipeline, not the exact app renderer.

## Remaining quality limitations

The network is being adapted to demosaiced sensor data with a shared noise model. Demosaic correlations, unequal channel noise and clipped/negative shadows are not fully represented. The positive shot-noise slope floor (1e-5) also adds a minimum signal-dependent denoising assumption for very low-noise images. The approximate inverse GAT and clipping deserve dedicated low-light calibration before changing their behaviour in the shared LK-SR solver. A camera-matrix-derived luminance definition and per-channel RAW noise calibration would require a separately validated change, rather than simply increasing the luma or chroma sliders.

The DNG results show no large newly added mean red bias relative to the first pass. They do not establish absolute colour accuracy: there are no matched clean captures, and retaining the first pass's broad colour deliberately retains any bias already present there.
