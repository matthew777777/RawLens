#!/usr/bin/env python3
"""Score the desktop blend against truth from the Vulkan Wronski diagnostic.

Pass the temporary directory used for BENCH_TEXTURE_EXPORT in
WronskiVsKernelNetBenchTest. Each frequency-band pair reports transfer
(1 means full contrast) and error unrelated to the true texture in dB.
"""

import argparse
from pathlib import Path

import numpy as np

from post_merge_denoise import blend_texture, estimate_noise

BANDS = ((0.1, 0.2), (0.2, 0.3), (0.3, 0.4), (0.4, 0.5), (0.5, 0.7))
UNITY = np.ones(3, dtype=np.float32)
BLACK = np.zeros(3, dtype=np.float32)
SPAN = np.full(3, 65535, dtype=np.float32)


def quantize(image):
    return np.rint(np.clip(image, 0, 1) * SPAN).astype("<u2")


def score(directory, name, scale):
    side = 512 * scale

    def read(variant):
        return np.fromfile(directory / f"{name}-{variant}.f32", dtype="<f4").reshape(side, side, 3)

    truth, base = map(read, ("truth", "base"))
    encoded_base = quantize(base)
    variants = [("base", encoded_base)]
    fits = sorted(directory.glob(f"{name}-fit-*.f32"))
    if fits:
        for path in fits:
            label = path.stem.removeprefix(f"{name}-")
            variants.append((label, quantize(read(label))))
    elif (directory / f"{name}-candidate.f32").exists():
        variants.append(("candidate", quantize(read("candidate"))))
        for alignment in ("oracle", "jitter-0.05", "jitter-0.1", "jitter-0.2"):
            if (directory / f"{name}-{alignment}.f32").exists():
                variants.append((alignment, quantize(read(alignment))))
    else:
        encoded_donor = quantize(read("donor"))
        noise = estimate_noise(encoded_base, UNITY, BLACK, SPAN, scale)
        blend = blend_texture(encoded_base, encoded_donor, UNITY, BLACK, SPAN, noise, scale)
        variants.extend((("donor", encoded_donor), ("blend", blend)))

    patch = np.s_[40 * scale:168 * scale, 360 * scale:488 * scale, 1]
    flat = np.s_[400 * scale:460 * scale, 40 * scale:100 * scale, 1]
    window = np.hanning(128 * scale)
    frequency = np.fft.fftfreq(128 * scale) * scale
    radius = np.hypot(frequency[:, None], frequency)

    def spectrum(image):
        region = image[patch]
        return np.fft.fft2((region - region.mean()) * window[:, None] * window)

    exact = spectrum(truth)
    y, x = np.ogrid[:side, :side]
    y, x = (y + 0.5) / scale - 0.5, (x + 0.5) / scale - 0.5
    trail_distance = np.minimum.reduce([np.hypot(x - 330 - 4 * k, y - 330 + 2.5 * k)
                                        for k in range(1, 8)])
    trail = (np.hypot(x - 330, y - 330) > 38) & (trail_distance < 39)
    print(name)
    for label, encoded in variants:
        image = encoded.astype(np.float32) / SPAN
        actual = spectrum(image)
        bands = []
        for lo, hi in BANDS:
            if hi > 0.5 * scale:
                break
            indices = (radius >= lo) & (radius < hi)
            a, t = actual[indices], exact[indices]
            energy = np.vdot(t, t).real
            transfer = np.vdot(t, a).real / energy
            residual = 10 * np.log10(np.sum(np.abs(a - transfer * t) ** 2) / energy)
            bands.append(f"{lo:.1f}–{hi:.1f}: {transfer:.3f}/{residual:+.2f}")
        flat_error = np.sqrt(np.mean((image[flat] - truth[flat]) ** 2))
        trail_p99 = np.percentile(np.abs(image[trail, 1] - truth[trail, 1]), 99)
        print(f"  {label:5} {'  '.join(bands)}  flat RMS {flat_error:.6f}  trail P99 {trail_p99:.6f}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    dumps = sorted(args.directory.glob("*-truth.f32"))
    if not dumps:
        parser.error("No truth dumps found")
    for path in dumps:
        name = path.name.removesuffix("-truth.f32")
        score(args.directory, name, int(name[-2]))
