#!/usr/bin/env python3
"""Decode local bursts for the opt-in production Kotlin selector test.
Requires numpy, rawpy and Pillow. Writes only to the explicit output directory.
Dense MAD uses the definition in tools/halide/generators/hdrplus_meter.cpp;
float summation order may differ. Strided measurements run in production Kotlin.
"""
import argparse
from pathlib import Path
import numpy as np
import rawpy
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument('source', type=Path)
parser.add_argument('output', type=Path)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
for folder in sorted(args.source.iterdir()):
    frame_dir = folder/'frames' if (folder/'frames').is_dir() else folder
    files = sorted(frame_dir.glob('*.dng')) if folder.is_dir() else []
    if len(files) < 2:
        continue
    dest = args.output / folder.name
    dest.mkdir(exist_ok=True)
    rows, prev = [], None
    if (folder/'google/merged.dng').is_file():
        (dest/'google-source.txt').write_text(str(folder/'google/merged.dng'))
        (dest/'google-reference.txt').write_text((folder/'google/reference_frame.txt').read_text())
    for i, file in enumerate(files):
        with rawpy.imread(str(file)) as raw:
            pixels = raw.raw_image.copy().astype('<u2')
            pattern = raw.raw_pattern
            black = [raw.black_level_per_channel[int(c)] for c in pattern.flat]
            white = raw.white_level
            if i == 0:
                rgb = raw.postprocess(use_camera_wb=True, half_size=True, no_auto_bright=False)
                image = Image.fromarray(rgb)
                image.thumbnail((1000, 750))
                image.save(dest / 'reference.jpg')
        h, w = pixels.shape
        pixels.tofile(dest / f'{i}.raw')
        rows.append('\t'.join(map(str, [file, w, h, white, *black])))
        if prev is not None:
            delta = np.abs(pixels.astype(np.float64) - prev) / (white - np.mean(black))
            sums = np.add.reduceat(np.add.reduceat(delta, np.arange(0,h,32), axis=0), np.arange(0,w,32), axis=1)
            counts = np.minimum(32, h-np.arange(0,h,32))[:,None] * np.minimum(32,w-np.arange(0,w,32))[None,:]
            (sums/counts).astype('<f8').tofile(dest / f'{i-1}.mad')
        prev = pixels.astype(np.float64)
    (dest/'frames.tsv').write_text('\n'.join(rows)+'\n')
    print(folder.name, len(files), f'{w}x{h}', flush=True)
