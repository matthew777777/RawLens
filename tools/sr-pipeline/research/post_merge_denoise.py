#!/usr/bin/env python3
"""Experimental noise-aware luma/chroma finish for RawLens desktop linear DNGs.

Run with NumPy and ExifTool. The independent channel filters borrow the
multiscale design of the GCam denoise stages. Their tuning and noise estimates
are local to RawLens. The input is preserved, including its DNG metadata.
"""

import argparse
import json
import os
import shutil
import subprocess
from pathlib import Path
from tempfile import TemporaryDirectory

import numpy as np

LEVELS = (1, 2, 4)
TILE = 384


def read_linear_dng(path):
    fields = ("ImageWidth", "ImageHeight", "BitsPerSample", "SamplesPerPixel",
              "PlanarConfiguration", "StripOffsets", "RowsPerStrip", "Compression",
              "PhotometricInterpretation", "AsShotNeutral", "BlackLevel", "WhiteLevel")
    command = ["exiftool", "-json", "-n", *[f"-{field}" for field in fields], str(path)]
    tags = json.loads(subprocess.check_output(command))[0]
    with path.open("rb") as file:
        little_endian = file.read(2) == b"II"
    if not (little_endian and tags["BitsPerSample"] == "16 16 16"
            and tags["SamplesPerPixel"] == 3 and tags.get("PlanarConfiguration", 1) == 1
            and tags["RowsPerStrip"] == tags["ImageHeight"] and tags["Compression"] == 1
            and tags["PhotometricInterpretation"] == 34892):
        raise ValueError("Expected a little-endian uncompressed single-strip RawLens linear RGB DNG")
    shape = (tags["ImageHeight"], tags["ImageWidth"], 3)
    offset = int(tags["StripOffsets"])
    if offset < 8 or offset + np.prod(shape) * 2 > path.stat().st_size:
        raise ValueError("DNG pixel strip lies outside the file")
    neutral = np.fromstring(str(tags["AsShotNeutral"]), sep=" ", dtype=np.float32)
    black = np.fromstring(str(tags["BlackLevel"]), sep=" ", dtype=np.float32)
    white = np.fromstring(str(tags["WhiteLevel"]), sep=" ", dtype=np.float32)
    ranges = white - black
    if (neutral.size != 3 or np.any(~np.isfinite(neutral)) or np.any(neutral <= 0)
            or ranges.size not in (1, 3) or np.any(~np.isfinite(ranges)) or np.any(ranges <= 0)):
        raise ValueError("Invalid DNG neutral or levels")
    return offset, shape, neutral, np.broadcast_to(black, (3,)), np.broadcast_to(ranges, (3,))


def to_components(raw, neutral, black, ranges):
    rgb = (raw.astype(np.float32) - black) / ranges / neutral
    green = rgb[..., 1]
    return green, rgb[..., [0, 2]] - green[..., None]


def estimate_noise(raw, neutral, black, ranges, scale):
    """Fit lower-envelope high-pass noise curves from sampled low-texture patches."""
    size = 24 * scale
    samples, energies = [], []
    for y in range(0, raw.shape[0] - size + 1, 80 * scale):
        for x in range(0, raw.shape[1] - size + 1, 80 * scale):
            guide, chroma = to_components(raw[y:y + size, x:x + size], neutral, black, ranges)
            components = np.dstack((guide, chroma))
            # The diagonal residual rejects planes and reports variation at
            # the same sensor-pixel spacing at both output scales.
            hh = (components[:-scale, :-scale] - components[:-scale, scale:]
                  - components[scale:, :-scale] + components[scale:, scale:]) * 0.5
            samples.append(np.median(guide))
            energies.append(np.median(np.abs(hh), axis=(0, 1)) / 0.67449)
    if not samples:
        raise ValueError("DNG too small to estimate post-merge noise")
    samples, energies = np.asarray(samples), np.asarray(energies)
    order = np.argsort(samples)
    # Quantile bins have similar support in dark and bright images.
    groups = np.array_split(order, min(12, max(1, len(order) // 16)))
    centers = np.array([np.median(samples[g]) for g in groups])
    values = np.array([np.percentile(energies[g], 25, axis=0) for g in groups])
    values = np.maximum(values, float(np.min(1 / ranges)) * 2)
    return centers, values


def level_filter(values, guide, noise, guide_noise, step, strength, range_sigma):
    """One dilated bilateral level with outlier rejection in both domains."""
    padded_values = np.pad(values, ((step, step), (step, step), (0, 0)), mode="edge")
    padded_guide = np.pad(guide, step, mode="edge")
    height, width = guide.shape
    total = values.copy()
    weights = np.ones_like(values)
    for dy, dx in ((-step, 0), (step, 0), (0, -step), (0, step)):
        rows = slice(step + dy, step + dy + height)
        cols = slice(step + dx, step + dx + width)
        neighbor = padded_values[rows, cols]
        feature = (neighbor - values) / (noise * range_sigma)
        edge = (padded_guide[rows, cols] - guide) / (guide_noise * range_sigma)
        weight = np.exp(-0.5 * np.maximum(feature * feature, (edge * edge)[..., None]))
        weight *= strength
        total += weight * neighbor
        weights += weight
    return total / weights


def local_contrast(guide, step):
    height, width = guide.shape
    padded = np.pad(guide, step, mode="edge")
    contrast = np.zeros_like(guide)
    for dy, dx in ((-step, 0), (step, 0), (0, -step), (0, step)):
        neighbor = padded[step + dy:step + dy + height, step + dx:step + dx + width]
        contrast += np.abs(neighbor - guide)
    return contrast * 0.25


def smoothstep(value, low, high):
    t = np.clip((value - low) / (high - low), 0, 1)
    return t * t * (3 - 2 * t)


def luma_flat_weight(guide, sigma, step):
    """Fade the correction out as local contrast rises above the noise floor."""
    return 1 - smoothstep(local_contrast(guide, step) / sigma, 1.25, 3.0)


def finish_tile(raw, neutral, black, ranges, curves, scale, mode,
                luma_amount=1.0, luma_flats_only=False):
    guide, chroma = to_components(raw, neutral, black, ranges)
    centers, values = curves
    noise = np.stack([np.interp(guide, centers, sigma).astype(np.float32) for sigma in values.T], axis=-1)
    green = guide[..., None]
    filtered_green, filtered_chroma = green, chroma
    for radius, strength in zip(LEVELS, (0.80, 0.45, 0.25)):
        if mode in ("luma", "both"):
            filtered_green = level_filter(filtered_green, guide, noise[..., :1], noise[..., 0],
                                          radius * scale, strength * 0.55, 1.65)
        if mode in ("chroma", "both"):
            filtered_chroma = level_filter(filtered_chroma, guide, noise[..., 1:], noise[..., 0],
                                           radius * scale, strength, 2.35)
    # Apply in camera RGB via white-balanced differences. Chroma filtering
    # leaves green alone. Luma filtering applies a neutral delta to all planes.
    delta_chroma = filtered_chroma - chroma
    delta = np.concatenate((delta_chroma[..., :1], np.zeros_like(green), delta_chroma[..., 1:]), axis=-1)
    luma_delta = (filtered_green - green) * luma_amount
    if luma_flats_only and mode in ("luma", "both"):
        luma_delta *= luma_flat_weight(guide, noise[..., 0], scale)[..., None]
    delta += luma_delta
    result = np.rint(raw + delta * neutral * ranges)
    return np.clip(result, 0, 65535).astype("<u2")


def blend_texture(raw, texture, neutral, black, ranges, curves, scale):
    """Use the higher-retention merge's green only where texture is resolved."""
    green = (raw[..., 1].astype(np.float32) - black[1]) / ranges[1] / neutral[1]
    centers, values = curves
    sigma = np.interp(green, centers, values[:, 0]).astype(np.float32)
    # Require contrast across a neighborhood so isolated noise excursions
    # cannot draw the higher-grain result into an otherwise smooth area.
    radius = 3 * scale
    width = 2 * radius + 1
    passed = local_contrast(green, scale)
    for axis in (0, 1):
        rows = np.moveaxis(passed, axis, 0)
        padded = np.pad(rows, ((radius, radius), (0, 0)), mode="edge")
        prefix = np.pad(np.cumsum(padded, axis=0, dtype=np.float64), ((1, 0), (0, 0)))
        passed = np.moveaxis((prefix[width:] - prefix[:-width]) / width, 0, axis)
    weight = smoothstep(passed / sigma, 2.4, 4.5).astype(np.float32)
    delta = (texture[..., 1].astype(np.float32) - raw[..., 1]) * weight
    gain = neutral * ranges
    result = np.rint(raw + delta[..., None] * (gain / gain[1]))
    return np.clip(result, 0, 65535).astype("<u2")


def run(source, destination, scale, mode, luma_amount=1.0, luma_flats_only=False,
        texture_source=None):
    source, destination = source.resolve(), destination.resolve()
    if source == destination or destination.exists():
        raise ValueError("Output must be a new file distinct from the input")
    offset, shape, neutral, black, ranges = read_linear_dng(source)
    raw = np.memmap(source, dtype="<u2", mode="r", offset=offset, shape=shape)
    texture = None
    if texture_source is not None:
        t_offset, t_shape, t_neutral, t_black, t_ranges = read_linear_dng(texture_source)
        if t_shape != shape or any(not np.array_equal(a, b) for a, b in
                                   ((t_neutral, neutral), (t_black, black), (t_ranges, ranges))):
            raise ValueError("Texture source must match the base geometry, neutral and levels")
        texture = np.memmap(texture_source, dtype="<u2", mode="r", offset=t_offset, shape=shape)
    curves = estimate_noise(raw, neutral, black, ranges, scale)
    destination.parent.mkdir(parents=True, exist_ok=True)
    # Stage beside the destination so an incomplete run never publishes a DNG.
    with TemporaryDirectory(prefix=".finish-", dir=destination.parent) as folder:
        staged = Path(folder) / "merged.dng"
        shutil.copyfile(source, staged)
        output = np.memmap(staged, dtype="<u2", mode="r+", offset=offset, shape=shape)
        height, width = shape[:2]
        halo = (4 if texture is not None else sum(LEVELS)) * scale
        for y in range(0, height, TILE):
            bottom = min(y + TILE, height)
            top = max(y - halo, 0)
            for x in range(0, width, TILE):
                right = min(x + TILE, width)
                left = max(x - halo, 0)
                rows = slice(top, min(bottom + halo, height))
                cols = slice(left, min(right + halo, width))
                block = raw[rows, cols]
                if texture is None:
                    result = finish_tile(block, neutral, black, ranges, curves, scale, mode,
                                         luma_amount, luma_flats_only)
                else:
                    result = blend_texture(block, texture[rows, cols], neutral, black, ranges,
                                           curves, scale)
                output[y:bottom, x:right] = result[y - top:bottom - top, x - left:right - left]
        output.flush()
        del output
        os.link(staged, destination)
    print(f"{mode}: {width}x{height} -> {destination}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--scale", type=int, choices=(1, 2), required=True)
    parser.add_argument("--mode", choices=("chroma", "luma", "both", "texture"), required=True)
    parser.add_argument("--texture-source", type=Path,
                        help="Same burst and registration rendered with higher source-grain retention")
    parser.add_argument("--luma-amount", type=float, default=1.0,
                        help="Fraction of the luma correction applied, from 0 to 1")
    parser.add_argument("--luma-flats-only", action="store_true",
                        help="Fade the luma correction out in textured and high-contrast regions")
    args = parser.parse_args()
    if not 0.0 <= args.luma_amount <= 1.0:
        parser.error("--luma-amount must be between 0 and 1")
    if (args.mode == "texture") != (args.texture_source is not None):
        parser.error("--mode texture requires --texture-source")
    run(args.source, args.destination, args.scale, args.mode, args.luma_amount,
        args.luma_flats_only, args.texture_source)
