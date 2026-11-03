# RAW viewfinder color rings: precision audit (2026-11-02)

The live viewfinder reduced black-subtracted RAW to an 8-bit **linear** R/Gr/Gb/B quad before applying the four-channel HAL lens-shading map. In a smooth flat field, shading compensation then multiplies different rounded steps in each channel. White balance and the color matrix amplify their disagreement. This can create colored contours even when the brightness falloff is corrected. The previous green-row precision fix addressed channel selection, but left this earlier quantization in place.

## Paths checked and changed

| Path | Precision before lens correction |
| --- | --- |
| Vulkan buffer, image, bridge, plane upload, CPU-copy upload | Direct Q6-calibrated sensor normalization to RGBA16F; new `vf_superpixel_linear` and `vf_superpixel_img_linear` shaders |
| EGL R16UI import | Direct float normalization of sensor codes in the fragment shader |
| CPU packed and generic stride samplers on ES3 | Normalized IEEE half samples, uploaded as RGBA16F |
| BGU GPU guide | RGBA16F export using the same Vulkan variants |
| BGU CPU guide | Half samples and RGBA16F upload |
| BGU low-resolution operator target | Float sensor samples through the Kotlin sampler, JNI and regenerated Halide `bgu_look` filter |

GL image/lens samplers and color arithmetic now explicitly request high precision. RAW display conversion receives one-LSB display-space dither, as the JPEG and BGU display paths already did. Lens-map channel ordering, separate green gains, CFA phase, black/white calibration and the HAL-pre-applied-shading check are preserved. Legacy byte APIs and byte shaders remain available for existing proofs and explicitly byte-based callers. The GLES2 compatibility path still uses its existing byte texture and disables lens correction; full precision requires ES3.

Recording, offline stabilization and photo RCD initializers load the same linear variant because the Vulkan device is shared and initialization is idempotent. Opening any of them before the viewfinder must not select a byte-output main pipeline. Recorder-specific compute pipelines remain separate.

The texture allocation cache also tracks the CPU frame format: the first frame can be sampled before the GL context is initialized, and must not leave later half-float frames uploading into an existing byte texture.

## Validation

- 115 focused JVM tests passed, including all BGU tests, VF shader generation and CPU sampler contract tests. The new low-frame regression proves that adjacent 12-bit sensor levels survive even when all would round to the same byte. CFA/crop/stride parity checks exercise the float sampler too.
- `tools/vf-precision/check.cpp` exhaustively compares the production portable half conversion against host IEEE half over 65,536 normalized sensor codes. Its synthetic inverse-colored-shading flat field reports RGB RMS error **0.00170587 -> 0.00000515** relative to the sensor reference (331.3x lower), and maximum added chroma **0.00742024 -> 0.00001974**. This isolates preview quantization from sensor quantization.
- The regenerated Halide look passes 12 host cases covering RAW, JPEG, Punchy and colored lens maps. Fixtures include fractional 12-bit input levels. All four ABI archives were regenerated.
- Both new Vulkan shaders compiled with the NDK shader compiler. Generated production ESSL1 and ESSL3 fragment shaders passed glslang validation; the ESSL3 program passed vertex/fragment linkage validation.
- Android native builds passed for arm64-v8a and armeabi-v7a. Device-test Kotlin compilation passed, including a new packed/generic CPU half-output test across all CFA patterns.

No ADB device was connected. The device tests were compiled, not executed. There is no before/after camera-scene comparison or measured GPU timing. This change fixes a demonstrated precision defect; optical chromatic aberration, incorrect vendor maps or other causes of scene fringing need separate evidence if symptoms persist.

Intermediate quad storage increases from four to eight bytes per pixel; BGU low targets increase from byte to float storage in the existing bounded pool. Full-frame dimensions, slot counts and reuse remain the same. Device memory and frame timing should be checked when hardware is available.

## Reproduce

```sh
c++ -std=c++17 -O2 tools/vf-precision/check.cpp -o /tmp/rawlens-vf-precision
/tmp/rawlens-vf-precision
tools/halide/build_aot.sh bgu_look
./gradlew :app:testGithubDebugUnitTest --tests 'com.matthew.rawlens.VfGpuImportTest' --tests 'com.matthew.rawlens.VfCpuNeonTest' --tests 'com.matthew.rawlens.Bgu*Test'
./gradlew :app:compileGithubDeviceTestAndroidTestKotlin :app:externalNativeBuildGithubDebug
```
