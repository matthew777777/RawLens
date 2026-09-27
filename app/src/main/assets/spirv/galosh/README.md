# GALOSH raw-pipeline SPIR-V (vendored)

Upstream: https://github.com/luxgrain/GALOSH (Apache-2.0, see NOTICE.md),
commit 11de0593cc8091933ad76d1d6d873f7dee537f15 (`standalone/vk/shaders/`).

43 raw-path kernels (the `ACTIVE` list in upstream `build_vk.sh`, minus the
`yuv_*` engine — raw-only per `docs/galosh-phase0-spec.md`).

Rebuild (upstream recipe, NDK r27 glslc):

```
glslc -O --target-env=vulkan1.2 -I shaders shaders/<k>.comp -o <k>.spv
```

(`-I` resolves the `galosh_f16_rne.glsl` include. `-O` output verified
bit-identical in parity to unoptimized builds; an early Mali pipeline crash
was a missing `VkPipeline.layout` in our host, not the optimizer.)

`.comp` sources are kept next to the `.spv` for provenance; the app loads
only the `.spv` files in `nativeInit`.

RawLens-built (see `docs/galosh-phase2-parity.md`): `o32_pass2_only.spv`
(NEW split-Phase-5 kernel) and `o32_build_inv_lut.spv` (multiplicative
recurrence rewrite). Built with
`glslangValidator -V -I. <k>.comp -o <k>.spv && spirv-opt -O <k>.spv -o
<k>.spv` (+ `spirv-val`). All other `.spv` are pristine upstream.
