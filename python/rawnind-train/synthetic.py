# SPDX-FileCopyrightText: 2026 RawLens contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Synthetic Poisson-Gaussian noise for RawNIND-tiny training (numpy-only).

Same heteroscedastic model as python/burst-ref/synth.py
(Var = shot*y + read^2, Brooks et al. "Unprocessing", DBSR Sec 5.1) but
operating on NORMALIZED Bayer patches in [0, 1] -- the exact domain the
Android denoiser sees after RawSensorUnpacker -- instead of linear-RGB.

Use: clean daylight / merged-HDR+ frames are ground truth; noisy inputs are
synthesized per patch with shot/read drawn from the sensor's calibrated
range (or the SHOT/READ defaults). Mix with real noisy/clean HDR+ pairs
per TrainConfig.synthetic_frac.
"""

import numpy as np

# Normalized-domain defaults; match burst-ref/synth.py magnitude.
SHOT = 2.5e-3
READ = 4.0e-3


def add_shot_read_noise(img, shot, read, rng):
    """Heteroscedastic Gaussian: n ~ N(0, shot*y + read^2)."""
    img = np.asarray(img, dtype=np.float32)
    var = shot * np.clip(img, 0, None) + read * read
    return (img + rng.normal(0, 1, img.shape).astype(np.float32)
            * np.sqrt(var).astype(np.float32)).astype(np.float32)


def synthesize_noisy(clean, shot, read, rng, clip=True):
    """Noisy Bayer patch from a clean normalized patch. Returns float32."""
    noisy = add_shot_read_noise(clean, shot, read, rng)
    if clip:
        noisy = np.clip(noisy, 0, None)  # sensor clips negatives, not highlights
    return noisy.astype(np.float32)


def draw_shot_read(rng, shot_range=(0.5 * SHOT, 3.0 * SHOT),
                   read_range=(0.5 * READ, 3.0 * READ)):
    """Log-uniform draw covering ~0.5x..3x nominal (multi-ISO training)."""
    lo_s, hi_s = np.log(shot_range[0]), np.log(shot_range[1])
    lo_r, hi_r = np.log(read_range[0]), np.log(read_range[1])
    return float(np.exp(rng.uniform(lo_s, hi_s))), float(np.exp(rng.uniform(lo_r, hi_r)))


def _self_test():
    rng = np.random.default_rng(11)
    flat = np.full((64, 64), 0.18, dtype=np.float32)
    noisy = synthesize_noisy(flat, SHOT, READ, rng)
    assert noisy.shape == flat.shape and noisy.dtype == np.float32
    # Noise variance scales with brightness (heteroscedastic).
    dark = synthesize_noisy(np.full((256, 256), 0.05, np.float32), SHOT, READ, rng)
    bright = synthesize_noisy(np.full((256, 256), 0.6, np.float32), SHOT, READ, rng)
    assert bright.var() > dark.var(), (bright.var(), dark.var())
    # Short-exposure (large shot) is noisier than long-exposure.
    assert synthesize_noisy(flat, SHOT * 4, READ * 2, rng).var() > noisy.var()
    # Determinism: same seed, same output.
    r1, r2 = np.random.default_rng(3), np.random.default_rng(3)
    a = synthesize_noisy(flat, SHOT, READ, r1)
    b = synthesize_noisy(flat, SHOT, READ, r2)
    assert np.array_equal(a, b)
    print("synthetic.py self-test ok")


if __name__ == "__main__":
    _self_test()
