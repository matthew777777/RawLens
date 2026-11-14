> See [current repository state](../../../../../docs/CURRENT_STATE.md) for app integration, current merge names, build requirements and the scope of historical evidence.

These RGBA16F inputs and expected outputs come from independently compiled
RAWR commit 537852ec0e109f78fb4ed54bc6a692d9c7cfca92, not from the Android JNI
implementation under test. Dimensions: 193 × 187; WB: [1.9, 1, 1.45].
Each case pairs one RAWR preset (2/3/4/6 iterations) with RGGB/GRBG/GBRG/BGGR.
The complete host-output hashes and reproduction command are documented in
`docs/refactor/capture-sharpening-rawr-parity.md`.
Cross-GPU comparisons use tolerances; same-GPU independent RAWR/JNI comparisons
must be byte-identical after both RGB results are represented as float32.
