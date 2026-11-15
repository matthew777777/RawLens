#!/usr/bin/env python3
"""Export the diagnostic converter's tightly packed GBRG output using captured metadata.

Requires numpy/tifffile. Factory camera matrices are retained, not recalibrated.
No noise profile, lens-shading opcode or distortion model is invented.
"""
import argparse
from fractions import Fraction
import json
from pathlib import Path

import numpy as np
import tifffile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("raw", type=Path)
parser.add_argument("metadata", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
m = json.loads(args.metadata.read_text())
w, h = m["width"] // 2, m["height"] // 2
if m["width"] % 8 or m["height"] % 8 or args.raw.stat().st_size != w * h * 2:
    raise SystemExit("Geometry does not match half-size conversion")
if args.output.exists():
    raise SystemExit("Output exists; choose a new path")
if not all(k in m for k in ("colorMatrix1", "illuminant1", "asShotNeutral", "blackRggb", "white")):
    raise SystemExit("Matching colour/black metadata is missing; refusing fabricated calibration")
tags = []

def tag(code, dtype, count, value):
    tags.append((code, dtype, count, value, False))

def rationals(code, values, signed=False):
    values = [Fraction(float(v)).limit_denominator(1000000) for v in values]
    tag(code, "2i" if signed else "2I", len(values), tuple(n for v in values for n in (v.numerator, v.denominator)))

tag(254, "I", 1, 0)
tag(271, "s", 0, "Xiaomi")
tag(272, "s", 0, "Redmi Note 15 Pro 5G")
tag(274, "H", 1, 1)  # No orientation was captured; preserve sensor orientation.
tag(50706, "B", 4, (1, 4, 0, 0))
tag(50707, "B", 4, (1, 3, 0, 0))
tag(50708, "s", 0, "RawLens lapis Hexadeca to Bayer diagnostic")
tag(33421, "H", 2, (2, 2))
tag(33422, "B", 4, (1, 2, 0, 1))  # GBRG, conventional 1-pixel Bayer
tag(50710, "B", 3, (0, 1, 2))
tag(50711, "H", 1, 1)
tag(50713, "H", 2, (2, 2))
rationals(50714, [m["blackRggb"][i] for i in (1, 3, 0, 2)])
tag(50717, "I", 1, int(m["white"]))
tag(50829, "I", 4, (0, 0, h, w))
tag(50719, "I", 2, (0, 0))
tag(50720, "I", 2, (w, h))
rationals(50728, m["asShotNeutral"])
for key, code in (("colorMatrix1", 50721), ("colorMatrix2", 50722),
                  ("cameraCalibration1", 50723), ("cameraCalibration2", 50724),
                  ("forwardMatrix1", 50964), ("forwardMatrix2", 50965)):
    if key in m:
        rationals(code, m[key], signed=True)
for key, code in (("illuminant1", 50778), ("illuminant2", 50779)):
    if key in m:
        tag(code, "H", 1, int(m[key]))
if "iso" in m:
    tag(34855, "H", 1, int(m["iso"]))
if "exposureNs" in m:
    rationals(33434, [m["exposureNs"] / 1e9])
if "focalLength" in m:
    rationals(37386, [m["focalLength"]])
if "aperture" in m:
    rationals(33437, [m["aperture"]])
raw = np.memmap(args.raw, mode="r", dtype="<u2", shape=(h, w))
tifffile.imwrite(args.output, raw, photometric=32803, metadata=None,
                  compression=None, byteorder="<", extratags=tags,
                  software="RawLens/rawr CFA-aware 200 to 50 MP prototype",
                  description="Derived Bayer, not original sensor samples. Factory matrices retained; mode-specific colour, shading and noise calibration unverified.")
with tifffile.TiffFile(args.output) as t:
    page = t.pages[0]
    assert page.shape == (h, w)
    assert page.tags[33422].value == bytes((1, 2, 0, 1))
    assert page.tags[50717].value == m["white"]
print(args.output)
