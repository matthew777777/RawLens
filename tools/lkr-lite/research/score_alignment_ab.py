# SPDX-License-Identifier: Apache-2.0
"""Score alignment_ab.sh output against lkr_synth_burst.py labels."""
import argparse
import json
import re
from pathlib import Path

import numpy as np
from scipy.ndimage import binary_dilation, gaussian_filter, maximum_filter, uniform_filter


def score(labels, directory, scale):
    labels = np.load(labels)
    truth = labels["gt"]
    if scale == 1:
        h, w = truth.shape[:2]
        truth = truth.reshape(h // 2, 2, w // 2, 2, 3).mean((1, 3))
    h, w = truth.shape[:2]
    moving = labels["masks"].any(axis=0)
    if moving.shape == (h // 2, w // 2):
        moving = moving.repeat(2, 0).repeat(2, 1)
    elif moving.shape == (h * 2, w * 2):
        moving = moving.reshape(h, 2, w, 2).any((1, 3))
    interior = np.zeros((h, w), dtype=bool)
    margin = 32 * scale
    interior[margin:-margin, margin:-margin] = True
    usable = interior & (maximum_filter(truth.max(-1), size=7) < .78)
    moving = binary_dilation(moving, iterations=3 * scale)
    green = np.array([.25, .5, .25])
    gray = truth @ green
    hp = gray - gaussian_filter(gray, scale)
    detail = usable & ~moving & (np.abs(hp) > .005)
    report = {}
    for log_path in sorted(directory.glob("*.log")):
        log = log_path.read_text()
        image = np.fromfile(log_path.with_suffix(".dng.f32"), dtype=np.float32).reshape(h, w, 3)
        white = re.search(r"HDR white ([0-9.]+)", log)
        image *= float(white[1]) if white else 1
        delta = image - truth
        luma = image @ green
        avg = uniform_filter(luma, 7)
        avg_gt = uniform_filter(gray, 7)
        covariance = uniform_filter(luma * gray, 7) - avg * avg_gt
        var = uniform_filter(luma**2, 7) - avg**2
        var_gt = uniform_filter(gray**2, 7) - avg_gt**2
        ssim = ((2 * avg * avg_gt + .01**2) * (2 * covariance + .03**2)
                / ((avg**2 + avg_gt**2 + .01**2) * (var + var_gt + .03**2)))
        error = delta @ green
        hp_error = error - gaussian_filter(error, scale)
        report[log_path.stem] = {
            "psnr": float(-10 * np.log10(np.mean(delta[usable] ** 2))),
            "luma_ssim": float(ssim[usable].mean()),
            "detail_rmse": float(np.sqrt(np.mean(hp_error[detail] ** 2))) if detail.any() else None,
            "motion_rmse": float(np.sqrt(np.mean(error[usable & moving] ** 2))) if (usable & moving).any() else None,
            "merge_ms": float(re.search(r"TOTAL\(merge\) ([0-9.]+)", log)[1]),
        }
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("labels", type=Path)
    parser.add_argument("results", type=Path)
    parser.add_argument("--scale", type=int, choices=(1, 2), default=1)
    args = parser.parse_args()
    print(json.dumps(score(args.labels, args.results, args.scale), indent=2, allow_nan=False))
