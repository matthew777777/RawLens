# SPDX-FileCopyrightText: 2026 RawLens contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Torch losses for RawNIND-tiny (packed Bayer domain).

  * bayer_l1: L1 on packed [R,G1,G2,B] -- the primary loss. Equivalent to L1
    on the Bayer plane (packing is a reshape), so it matches the sensor.
  * demosaic_l1: L1 after a fixed bilinear green-aware demosaic lite. Keeps
    the denoiser honest cross-channel (a per-channel-only optimum can leave
    maze artifacts AMaZE will amplify). Weight 0.2 by default.
  * combined(...): weighted sum used by train.py.

Plus a numpy psnr() helper for validation logging (no torch needed).
"""

import numpy as np

try:
    import torch
    import torch.nn.functional as F
except ImportError as _e:  # pragma: no cover
    raise ImportError("losses.py needs torch: pip install -r requirements.txt (%s)" % _e)


def bayer_l1(pred, target):
    """Mean L1 over packed 4ch. Shapes (B,4,H,W)."""
    return F.l1_loss(pred, target)


def _bilinear_demosaic_lite(packed):
    """Packed (B,4,H,W) -> RGB (B,3,2H,2W) via bilinear interpolation.

    R/B quads are upsampled x2; green averages the G1/G2 quads then
    upsamples. Fixed (no params), differentiable, NCNN-irrelevant
    (train-time only).
    """
    r = F.interpolate(packed[:, 0:1], scale_factor=2, mode="bilinear",
                      align_corners=False)
    b = F.interpolate(packed[:, 3:4], scale_factor=2, mode="bilinear",
                      align_corners=False)
    g = F.interpolate(0.5 * (packed[:, 1:2] + packed[:, 2:3]), scale_factor=2,
                      mode="bilinear", align_corners=False)
    return torch.cat([r, g, b], dim=1)


def demosaic_l1(pred, target):
    """L1 in demosaiced-linear RGB (cross-channel consistency)."""
    return F.l1_loss(_bilinear_demosaic_lite(pred), _bilinear_demosaic_lite(target))


def combined(pred, target, w_demosaic=0.2):
    """train.py loss: Bayer L1 + w * demosaic L1."""
    return bayer_l1(pred, target) + w_demosaic * demosaic_l1(pred, target)


def psnr(pred_np, target_np, peak=1.0):
    """Numpy PSNR on normalized packed or Bayer arrays (validation log)."""
    mse = float(np.mean((np.asarray(pred_np, dtype=np.float64)
                         - np.asarray(target_np, dtype=np.float64)) ** 2))
    if mse <= 0:
        return float("inf")
    return 10.0 * np.log10(peak * peak / mse)
