// SPDX-License-Identifier: Apache-2.0
#pragma once

namespace lkrl {

// Block motion seed on the half-resolution cell grey (n x n): checks every 16 LR px block's start offset
// against sensor noise, and only where the residual is not explained by noise looks for a better one:
// the neighbours' offsets, the plain affine, a coarse search, then translational Lucas-Kanade.
// p: tile affine in grey px (frame -> reference). field: nbx x nby x 2, LR px, in / out.
// Pixels that are NaN in either image (clipped HDR samples) are skipped.
struct PatchSeedStats { int blocks = 0, checked = 0, changed = 0; };
struct PatchSeedParams {
  float noise_a = 0, noise_b = 0;   // per raw sample; the grey averages four
  float trigger = 1.3f;             // mean |residual| in expected-noise units that starts a search
  float slack = 0.5f;               // grey px of motion-like residual allowed on top of the noise
  float gain = 0.85f;               // a candidate must cut the residual to this share
  float fine_unique = 3.0f;         // PatchSeedFine: its match must stand out by this many standard errors (0 = off)
  int fine_agree = 2;               // and this many of the 8 neighbours must have moved alike
  float unique = 0;                 // a jump's match must stand out by this many standard errors (0 = off)
  int search = 5;                   // coarse search radius, 8 LR px steps (0 = off)
  float max_offset = 64.0f;         // LR px
};
PatchSeedStats PatchSeed(const float* ref, const float* mov, int n, const double p[6], const PatchSeedParams& prm,
                         float* field, int nbx, int nby);

// Offsets on an 8 LR px grid on top of the 16 px field (bilinear): only blocks whose residual noise cannot
// explain, from the neighbouring 16 px blocks' own motion (object edges) and a local refinement.
// extra: nfx x nfy x 2 LR px, overwritten. Returns the blocks changed.
int PatchSeedFine(const float* ref, const float* mov, int n, const double p[6], const PatchSeedParams& prm,
                  const float* field, int nbx, int nby, float* extra, int nfx, int nfy);

}  // namespace lkrl
