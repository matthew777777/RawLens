# HDR+ merge SPIR-V (vendored from RAWR)

Upstream: https://github.com/adityawarmanfw/rawr (GPL-3.0-only, see NOTICE.md),
`native/multiframe/merge_hdrplus/shaders/` at commit
`f41e6c2c493cb2ebfe37ca7dcb98a6e38a5d40a5` (2026-10-06).

RAWR's merge is itself a port of Burst Photo
(https://github.com/martin-marek/hdr-plus-swift, GPL-3.0) at
`69cb0572bb6712e160c448260125cb6099bdfd87` (2024-08-24) — see RAWR's
`merge_hdrplus/UPSTREAM.md`, mirrored in `UPSTREAM.md` next to this file.

28 kernels: 15 `hdrp_*` (spatial "Fast" merge: prepare, pyramid, tile
alignment, warp, robust weight, accumulate, finalize) and 13 `hdrq_*`
(frequency "Higher quality" merge: RGBA pack, DFT, RMS/mismatch
statistics, Wiener merge, deconvolution, inverse DFT, border repair,
4-pass accumulation, shift table). 18 are byte-identical to RAWR; 8 carry
small marked upstream-parity patches toward Burst Photo and 2 carry marked
RawLens extension patches (step-4 strength maps, see UPSTREAM.md).

Rebuild (matches the documented glslang recipe; no glslc in the NDK):

```
for f in *.comp; do
  glslangValidator -V --target-env vulkan1.2 "$f" -o "${f%.comp}.spv"
  spirv-opt -O "${f%.comp}.spv" -o "${f%.comp}.spv"
  spirv-val --target-env vulkan1.2 "${f%.comp}.spv"
done
```

Built with glslang 16.6.0 (Homebrew) + SPIRV-Tools optimizer/validator.
`.comp` sources are kept next to the `.spv` for provenance; the app loads
only the `.spv` files in `nativeInit`.
