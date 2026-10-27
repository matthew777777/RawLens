# Preserved RawLens RCD baseline

Do not replace or overwrite this baseline during replacement experiments.

Measured on Xiaomi 25080RABDG / Mali-G615 MC2 at 4080×2294: 200.7 ms total for split RCD stages (55.1 / 41.9 / 53.2 / 50.4 ms). This is a **four-stage demosaic benchmark**, not a 200 ms complete recording/encode guarantee. GPU-to-CPU reference error: mean 0.00010791645, maximum 0.0056773424, identical to the original fixture result.

`vf_rcd_measured_200ms.comp` and `.spv` are the exact measured packed-site shader. `measured-baseline-source.tar.gz` preserves its native layout/dispatch, associated source shaders, recorder lifetime/timestamp fixes, and bridge. The archived native dispatch deliberately includes inactive groups in packed stages; the shader guards those groups. The latest packed dispatch reduction and fused-grade packing are separate subsequent work.

Rebuild the archived auxiliary `.comp` shaders with glslangValidator targeting Vulkan 1.1; validate with spirv-val. SHA256.json records the archived source bytes. No restore is performed automatically.
