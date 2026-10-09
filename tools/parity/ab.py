#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 RawLens contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Step-5 parity A/B v1: saved-JPEG vs VF-screenshot color parity.

Compares a clean sub-window of a VF screenshot against the matching window
of the saved JPEG (EXIF-transposed to portrait). Both are downsampled to a
small common grid so the numbers measure color/tone parity (BGU's job), not
demosaic detail or screenshot compression.

Limitations (v1): linear window mapping (ignores active-array inset and the
~4% VF aspect squeeze), no sub-pixel registration (handheld drift between
capture and screenshot penalizes SSIM slightly). Future corpus captures
should be tripod/static with the VF shot simultaneous to capture.

Usage:
    tools/parity/ab.py saved.jpg vf_screenshot.jpg [--match]
With --match, the VF patch is scaled to the saved patch's mean luma first,
separating exposure strategy (preview lift) from color/structure parity.
"""
import sys

import numpy as np
from PIL import Image, ImageOps

# Clean door/wall window in the 1280x2772 screenshot (avoids overlay pill,
# histogram, lens buttons, focus brackets): x[500,1000] y[850,1546].
VF_WINDOW = (500, 850, 1000, 1546)
# VF region in the screenshot (SurfaceView bounds).
VF_REGION_TOP = 240
VF_REGION_HEIGHT = 1783
SCREEN_W = 1280
OUT_SIZE = (256, 356)


def ssim_gray(a: np.ndarray, b: np.ndarray) -> float:
    """Wang et al. SSIM, 11x11 Gaussian window, L=255."""
    x = a.astype(np.float64)
    y = b.astype(np.float64)
    ax = np.arange(-5, 6)
    w = np.exp(-(ax ** 2) / (2 * 1.5 ** 2))
    w /= w.sum()
    mu_x = _sep(x, w)
    mu_y = _sep(y, w)
    sig_xx = _sep(x * x, w) - mu_x * mu_x
    sig_yy = _sep(y * y, w) - mu_y * mu_y
    sig_xy = _sep(x * y, w) - mu_x * mu_y
    c1 = (0.01 * 255) ** 2
    c2 = (0.03 * 255) ** 2
    num = (2 * mu_x * mu_y + c1) * (2 * sig_xy + c2)
    den = (mu_x ** 2 + mu_y ** 2 + c1) * (sig_xx + sig_yy + c2)
    return float(np.mean(num / den))


def _sep(img: np.ndarray, w: np.ndarray) -> np.ndarray:
    tmp = np.apply_along_axis(lambda r: np.convolve(r, w, mode="same"), 1, img)
    return np.apply_along_axis(lambda r: np.convolve(r, w, mode="same"), 0, tmp)


def luma(rgb: np.ndarray) -> np.ndarray:
    return 0.2126 * rgb[..., 0] + 0.7152 * rgb[..., 1] + 0.0722 * rgb[..., 2]


def main(saved_path: str, vf_path: str, match: bool) -> None:
    saved = ImageOps.exif_transpose(Image.open(saved_path).convert("RGB"))
    shot = Image.open(vf_path).convert("RGB")
    assert shot.size == (SCREEN_W, 2772), shot.size
    sw, sh = saved.size
    assert sw < sh, "expected portrait saved JPEG after transpose"

    # Map the VF window linearly onto the saved frame.
    x0, y0, x1, y1 = VF_WINDOW
    jx0, jx1 = x0 / SCREEN_W * sw, x1 / SCREEN_W * sw
    jy0 = (y0 - VF_REGION_TOP) / VF_REGION_HEIGHT * sh
    jy1 = (y1 - VF_REGION_TOP) / VF_REGION_HEIGHT * sh
    a = np.asarray(saved.crop((jx0, jy0, jx1, jy1)).resize(OUT_SIZE, Image.BICUBIC)).astype(float)
    b = np.asarray(shot.crop(VF_WINDOW).resize(OUT_SIZE, Image.BICUBIC)).astype(float)
    if match:
        b *= luma(a).mean() / max(luma(b).mean(), 1e-6)

    mse = np.mean((a - b) ** 2)
    psnr = 10 * np.log10(255 * 255 / mse) if mse > 0 else float("inf")
    ssim = ssim_gray(luma(a), luma(b))
    ma = a.reshape(-1, 3).mean(axis=0)
    mb = b.reshape(-1, 3).mean(axis=0)
    print(f"window vf={VF_WINDOW} jpg={jx0:.0f},{jy0:.0f},{jx1:.0f},{jy1:.0f} size={OUT_SIZE}")
    print(f"PSNR {psnr:.2f} dB  SSIM {ssim:.4f}")
    print(f"mean saved R/G/B {ma[0]:.1f}/{ma[1]:.1f}/{ma[2]:.1f}  "
          f"vf R/G/B {mb[0]:.1f}/{mb[1]:.1f}/{mb[2]:.1f}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], "--match" in sys.argv)
