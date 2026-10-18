# Pinned-ICA build (FINAL-14)

Reproducible SPIR-V-level pinning of the ICA refine shader for
desktop/Mali eval convergence. Shader math is untouched: the recipe only
serializes *how* fused multiply-adds evaluate (explicit `FMA`
instructions), refines the `det` reciprocal (one Newton-Raphson step over
the plain divider), and wraps intermediates in identity clamps.

## Recipe

```
ica_pristine.comp
  -> glslangValidator --target-env vulkan1.2 -V        (11.16.6.0)
  -> force_fma.py PINS                                 (selective FMA pins)
  -> nr_div.py                                         (NR det reciprocal)
  -> wrap_clamp.py ALL                                 (identity clamp wraps)
  -> spirv-as / spirv-val
```

Run `./build_ica.sh [pin_csv] [out.spv]`. Default pins
`0,1,4,5,7,8,11,12,13` reproduce `host/build/ica.spv` bit-identical
(verified 2026-10-18). Pin indices are file-order mul->add pairs in the
pristine disassembly: 0,1,2 op-0 Hessian; 3 det; 4,5,6 8x8 top/bot/interp;
7,8,9,10 8x8 inner/outer; 11,12,13 generic top/bot/interp; 14,15 generic
B0/B1; 16,17 generic inner.

## Provenance notes

- `ica_pristine.comp` is `ica.comp` before the `precise` qualifiers.
  The qualifiers emit `NoContraction` (check a fresh build's disassembly),
  which both drivers ignore; the recipe input stays NC-free on purpose.
- `host/build/ica.spv` is generated (git-ignored); this directory is its
  source of truth. The app syncs SPVs from `host/build` at packaging time.
- Desktop-vs-device status (2026-10-18): deterministic both sides;
  staged 628 px / 3 LSB, resident 667 px / 12 LSB on the 8-frame 1024 crop
  (envelope parity; bit-identity blocked by input-dependent eval
  divergence — see project memory, not solvable by steering).
- Suite: 181/183 green; `gpu/pinned align loop matches recorded crop
  flow` stay red (1.15931e-05 vs 1.154e-05 envelope) from FINAL-14's
  intended pin-driven drift vs the pre-pin reference. Kept red as
  referees; widening the bound needs explicit auth.
