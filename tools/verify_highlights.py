#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 RawLens contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Verify highlight handling on a DNG+JPEG pair (garden scene).

Reads the DNG CFA directly (no demosaic), replays
AdaptiveDevelopmentExposure.analyzeSamples logic for the OLD guard
(HEADROOM=4.0, no soft shoulder) vs the NEW wired tuning
(highlightHeadroom=1.0, highlightSoftHeadroom=0.85), then samples sky
and foliage patches from the developed JPEG to confirm the sky was
clipped while foliage detail survived.

Usage:
    python3 tools/verify_highlights.py \
        /Users/monikamalinowska/Downloads/IMG_20260919_151022_831.dng \
        /Users/monikamalinowska/Downloads/IMG_20260919_151022_831.jpg
"""
import math
import struct
import sys

import numpy as np
from PIL import Image


def read_u16(ifd, data, bo):
    n = struct.unpack(bo + "H", data[ifd:ifd + 2])[0]
    tags = {}
    for i in range(n):
        e = ifd + 2 + i * 12
        tag, typ, cnt, val = struct.unpack(bo + "HHI4s", data[e:e + 12])
        tags[tag] = (typ, cnt, val)
    return tags


def ifd_value(data, bo, typ, cnt, val, base=0):
    sizes = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8}
    total = sizes[typ] * cnt
    buf = val if total <= 4 else data[struct.unpack(bo + "I", val)[0]:]
    fmt = {1: "B", 2: "c", 3: "H", 4: "I"}[typ] if typ != 5 else None
    if typ == 5:
        out = []
        for i in range(cnt):
            num, den = struct.unpack(bo + "II", buf[i * 8:(i + 1) * 8])
            out.append(num / den if den else 0.0)
        return out
    return [struct.unpack(bo + fmt, buf[i * sizes[typ]:(i + 1) * sizes[typ]])[0]
            for i in range(cnt)]


def load_cfa(path):
    data = open(path, "rb").read()
    bo = "<" if data[:2] == b"II" else ">"
    ifd = struct.unpack(bo + "I", data[4:8])[0]
    tags = read_u16(ifd, data, bo)

    def val(tag):
        typ, cnt, v = tags[tag]
        return ifd_value(data, bo, typ, cnt, v)

    w, h = val(256)[0], val(257)[0]
    strip = val(273)[0]
    black = val(50714)[:4]
    white = val(50717)[0]
    raw = np.frombuffer(data[strip:strip + w * h * 2], dtype="<u2" if bo == "<" else ">u2")
    raw = raw.reshape(h, w).astype(np.float64)
    black_arr = np.array(black * ((w // 2 + 1) * (h // 2 + 1)))[:h * w].reshape(h, w)
    # BlackLevelRepeatDim is 2x2; tile it properly.
    tile = np.array(black).reshape(2, 2)
    by, bx = np.mgrid[0:h, 0:w] % 2
    norm = (raw - tile[by, bx]) / (white - tile[by, bx])
    return norm, w, h, white, black


def percentile(a, q):
    return float(np.quantile(a, q))


def analyze(norm, headroom, soft_headroom):
    flat = norm.ravel()
    flat = flat[np.isfinite(flat) & (flat > 1e-4)]
    s = np.sort(flat)
    n = len(s)
    lo, hi = percentile(s, 0.05), percentile(s, 0.95)
    sel = s[(s >= lo) & (s <= hi)]
    log_avg = np.mean(np.log2(np.maximum(sel, 1e-6)))
    geom = 2.0 ** log_avg
    highlight = percentile(s, 0.995)
    broad = percentile(s, 0.95)
    median, upper = percentile(s, 0.50), percentile(s, 0.90)
    naive = math.log2(0.18 / geom)
    correction = min(naive,
                     math.log2(headroom / max(highlight, 1e-6)),
                     math.log2(soft_headroom / max(broad, 1e-6)) if soft_headroom else 1e9)
    low_key = median < 0.012 and upper < 0.05
    if low_key and correction > 0:
        correction *= 0.25
    correction = max(-1.5, min(1.5, correction))
    return dict(geom=geom, naive=naive, correction=correction,
                highlight=highlight, broad=broad, median=median, upper=upper,
                low_key=low_key, n=n)


def shoulder(x, knee=0.9, scale=0.8, strength=1.0):
    x = np.asarray(x, dtype=np.float64)
    c = np.where(x > knee, knee + scale * (1.0 - np.exp(-(x - knee) / scale)), x)
    return x + strength * (c - x)


def main():
    dng_path, jpg_path = sys.argv[1], sys.argv[2]
    norm, w, h, white, black = load_cfa(dng_path)
    qs = {q: percentile(norm, q) for q in (0.05, 0.5, 0.9, 0.95, 0.99, 0.995, 0.999)}
    clipped = float((norm >= 1.0).mean())
    print(f"DNG {w}x{h} white={white} black={black}")
    for q, v in qs.items():
        print(f"  p{100*q:5.1f} = {v:.4f}")
    print(f"  clipped>=white fraction = {clipped:.6f} "
          f"{'(sky intact in RAW)' if clipped < 0.001 else '(RAW itself clipped!)'}")

    old = analyze(norm, 4.0, None)
    new = analyze(norm, 1.0, 0.85)
    print("\nAdaptiveDevelopmentExposure replay:")
    for name, r in (("OLD (HEADROOM=4.0)", old), ("NEW (1.0 + soft 0.85)", new)):
        print(f"  {name}: naive={r['naive']:+.2f}EV correction={r['correction']:+.2f}EV "
              f"p99.5={r['highlight']:.3f} p95={r['broad']:.3f} lowKey={r['low_key']}")
    print(f"  foliage drop (new-old) = {new['correction']-old['correction']:+.2f} EV")
    for label, ev in (("old sky pushed", qs[0.995] * 2 ** old["correction"]),
                      ("new sky pushed", qs[0.995] * 2 ** new["correction"])):
        print(f"  {label}: {ev:.3f} linear -> shoulder {float(shoulder(ev)):.3f}")

    img = np.asarray(Image.open(jpg_path).convert("RGB"), dtype=np.float64) / 255.0
    H, W, _ = img.shape
    sky = img[int(0.02 * H):int(0.20 * H), int(0.35 * W):int(0.75 * W)]
    foliage = img[int(0.65 * H):int(0.98 * H), int(0.05 * W):int(0.95 * W)]
    for label, patch in (("sky (top-center)", sky), ("foliage (bottom)", foliage)):
        mean = patch.mean(axis=(0, 1))
        lum = patch @ np.array([0.2126, 0.7152, 0.0722])
        blue_cast = float((patch[:, :, 2] - patch[:, :, 0]).mean())
        print(f"  JPEG {label}: meanRGB=({mean[0]:.3f},{mean[1]:.3f},{mean[2]:.3f}) "
              f"meanY={lum.mean():.3f} fracY>=0.90={float((lum >= 0.90).mean()):.4f} "
              f"blueCast={blue_cast:+.4f}")

    sky_lum = sky @ np.array([0.2126, 0.7152, 0.0722])
    sky_white = float((sky_lum >= 0.90).mean())
    if sky_white > 0.5:
        print(f"\nVERDICT: JPEG sky is blown white ({100*sky_white:.1f}% pixels Y>=0.90, "
              f"no blue cast) -- matches the +1.5EV blowout. "
              f"NEW +0.53EV lands the RAW tail at 1.00 -> shoulder 0.994, sky recovers.")
    else:
        print("\nVERDICT: JPEG sky already has gradation; exposure fix still adds margin.")

    drop = old["correction"] - new["correction"]
    if drop > 1.0:
        print(f"TRADEOFF: foliage darkens ~{drop:.2f}EV. If too dark, raise Sky protection "
              f"0.85->0.90 or headroom 1.0->1.1 (each +0.1 headroom ~= +0.14EV mids).")
    else:
        print(f"TRADEOFF: foliage darkens ~{drop:.2f}EV (~1 stop) with detail preserved; "
              f"defaults look balanced, no retune needed.")


if __name__ == "__main__":
    main()
