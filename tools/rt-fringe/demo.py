#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Render a reproducible defringe comparison from an existing opaque sRGB PNG.
The input can be a RawLens DNG development. No RAW decoding or altered exposure.
Requires NumPy and Pillow; builds the standalone C++ port with clang++ by default.
"""
import argparse
import json
from pathlib import Path
import subprocess
import numpy as np
from PIL import Image, ImageDraw


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--radius', type=float, default=1)
    parser.add_argument('--threshold', type=int, default=13)
    parser.add_argument('--binary', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    image = Image.open(args.input).convert('RGBA')
    rgba = np.asarray(image)
    if not np.all(rgba[:, :, 3] == 255):
        parser.error('The demo requires opaque, straight sRGB RGBA pixels')
    binary = args.binary or args.output / 'rgba_defringe'
    if args.binary is None:
        subprocess.run(['clang++', '-std=c++17', '-O3', str(Path(__file__).with_name('rgba_cli.cpp')), '-o', str(binary)], check=True)
    source, target = args.output / 'off.rgba', args.output / 'on.rgba'
    source.write_bytes(image.tobytes())
    command = [str(binary.resolve()), str(source), str(target), str(image.width), str(image.height), str(args.radius), str(args.threshold)]
    result = subprocess.run(command, check=True, capture_output=True, text=True)
    stats = json.loads(result.stdout)
    (args.output / 'stats.json').write_text(json.dumps(stats, indent=2) + '\n')
    corrected = Image.frombytes('RGBA', image.size, target.read_bytes()).convert('RGB')
    image.convert('RGB').save(args.output / 'OFF.png')
    corrected.save(args.output / 'ON.png')
    # Entire image, matching geometry and no extra display transforms.
    width = 1000
    height = max(1, round(image.height * width / image.width))
    comparison = Image.new('RGB', (width * 2, height + 40), (24, 27, 32))
    draw = ImageDraw.Draw(comparison)
    for column, (label, picture) in enumerate([('OFF', image), ('RT-derived defringe ON', corrected)]):
        draw.text((column * width + 16, 12), label, fill='white')
        comparison.paste(picture.convert('RGB').resize((width, height), Image.Resampling.LANCZOS), (column * width, 40))
    comparison.save(args.output / 'comparison.png')
    print(json.dumps(stats, indent=2))


if __name__ == '__main__':
    main()
