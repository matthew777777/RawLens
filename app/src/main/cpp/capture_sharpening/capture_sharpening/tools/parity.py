"""Parity test: capsharp_reference.py vs the RawTherapee reference harness (tools/rt_harness).

Build the RT harness first (tools/rt_harness/build.sh), then:
    uv run --with numpy python native/capture_sharpening/tools/parity.py
Synthetic Bayer scenes cover every kernel size, auto radius/contrast, iteration-stop
check, corner offset, clipped highlights, Y > 65535 and non-multiple-of-tile sizes.
"""
from __future__ import annotations

import subprocess
import zlib
import sys
from pathlib import Path

import numpy as np

import capsharp_reference as cs

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
TMP = REPO / "tmp" / "capture_sharpening"  # all generated files (AGENTS.md: keep temp work in ./tmp)
WORK = TMP / "parity"
REF = TMP / "rt_harness" / "cs_ref"
REF_RING = TMP / "rt_harness" / "cs_ref_ringreset"
f32 = np.float32


def gauss_blur(img, sigma):
    r = int(np.ceil(3 * sigma))
    x = np.arange(-r, r + 1)
    k = np.exp(-x * x / (2 * sigma * sigma))
    k /= k.sum()
    p = np.pad(img, r, mode="reflect")
    p = sum(k[i] * p[:, i:i + img.shape[1]] for i in range(2 * r + 1))
    return sum(k[i] * p[i:i + img.shape[0], :] for i in range(2 * r + 1))


def scene(H, W, seed, blur_sigma, highlight=True):
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[0:H, 0:W].astype(np.float64)
    rgb = np.empty((3, H, W))
    base = 0.06 + 0.5 * x / W  # gradient
    for c in range(3):
        rgb[c] = base * (0.7 + 0.3 * c)
    # flat mid-grey patch (auto-contrast needs a flat tile at moderate luminance)
    rgb[:, H // 8:H // 8 + 100, W // 8:W // 8 + 100] = 0.18
    # siemens star
    cy, cx, rad = H * 0.6, W * 0.35, min(H, W) * 0.22
    ang = np.arctan2(y - cy, x - cx)
    inside = (y - cy) ** 2 + (x - cx) ** 2 < rad ** 2
    star = (np.sin(ang * 36) > 0) * 0.6 + 0.05
    rgb[:, inside] = star[inside]
    # random coloured rectangles and fine stripes
    for _ in range(25):
        h, w = rng.integers(8, H // 4), rng.integers(8, W // 4)
        y0, x0 = rng.integers(0, H - h), rng.integers(0, W - w)
        rgb[:, y0:y0 + h, x0:x0 + w] = rng.uniform(0.02, 0.8, 3)[:, None, None]
    stripes = (np.sin(x * 2 * np.pi / rng.uniform(3, 7)) > 0) * 0.3 + 0.1
    band = slice(int(H * 0.82), int(H * 0.9))
    rgb[:, band] = stripes[band]
    if highlight:
        hl = (y - H * 0.25) ** 2 + (x - W * 0.75) ** 2 < (min(H, W) * 0.06) ** 2
        rgb[:, hl] = np.array([1.4, 1.25, 1.1])[:, None]
    rgb = np.stack([gauss_blur(c, blur_sigma) for c in rgb])
    rgb = rgb * 65535.0
    rgb += rng.normal(0, 1, rgb.shape) * np.sqrt(np.maximum(rgb, 0) * 2.0 + 25.0)
    return rgb


def mosaic_rggb(rgb):
    _, H, W = rgb.shape
    raw = np.empty((H, W))
    raw[0::2, 0::2] = rgb[0, 0::2, 0::2]
    raw[0::2, 1::2] = rgb[1, 0::2, 1::2]
    raw[1::2, 0::2] = rgb[1, 1::2, 0::2]
    raw[1::2, 1::2] = rgb[2, 1::2, 1::2]
    return raw


def bilinear_demosaic(raw):
    H, W = raw.shape
    masks = np.zeros((3, H, W))
    masks[0, 0::2, 0::2] = 1
    masks[1, 0::2, 1::2] = 1
    masks[1, 1::2, 0::2] = 1
    masks[2, 1::2, 1::2] = 1
    k = np.array([[1, 2, 1], [2, 4, 2], [1, 2, 1]], float)
    out = np.empty((3, H, W))
    for c in range(3):
        num = np.pad(raw * masks[c], 1, mode="reflect")
        den = np.pad(masks[c], 1, mode="reflect")
        n = sum(k[i, j] * num[i:i + H, j:j + W] for i in range(3) for j in range(3))
        d = sum(k[i, j] * den[i:i + H, j:j + W] for i in range(3) for j in range(3))
        out[c] = n / d
    return out


CASES = [
    # name, H, W, blur, params, ring-reset reference
    ("auto_default", 600, 800, 0.9, cs.Params(), False),
    ("r0.5_3x3_fixedcontrast", 503, 701, 0.6, cs.Params(auto_radius=False, radius=0.5, auto_contrast=False, contrast=10), False),
    ("r0.75_5x5", 480, 640, 0.8, cs.Params(auto_radius=False, radius=0.75), False),
    ("r1.0_7x7_40it", 451, 613, 1.0, cs.Params(auto_radius=False, radius=1.0, iterations=40), False),
    ("r1.3_9x9_nocheck", 400, 520, 1.3, cs.Params(auto_radius=False, radius=1.3, iter_check=False), False),
    ("r1.8_13x13", 390, 470, 1.8, cs.Params(auto_radius=False, radius=1.8), False),
    ("corner_offset", 600, 800, 0.9, cs.Params(auto_radius=False, radius=0.6, radius_offset=0.9), True),
    ("contrast0_noauto", 300, 400, 0.9, cs.Params(auto_contrast=False, contrast=0), False),
    ("tiny", 70, 90, 0.9, cs.Params(), False),
]

WHITE = 65535.0
CLIP = 0.95


def run_case(name, H, W, blur, p, ring):
    d = WORK / name
    d.mkdir(parents=True, exist_ok=True)
    rgb = scene(H, W, seed=zlib.crc32(name.encode()), blur_sigma=blur)
    raw = np.clip(mosaic_rggb(rgb), 0, WHITE)  # sensor clips at white
    dm = bilinear_demosaic(raw)
    planes = {"raw": raw, "r": dm[0], "g": dm[1], "b": dm[2]}
    for k, v in planes.items():
        v.astype(f32).tofile(d / f"{k}.f32")
    whites = [[WHITE * CLIP] * 2] * 2
    fc = (0, 1)
    (d / "params.txt").write_text(
        f"{W} {H} {whites[0][0]} {whites[0][1]} {whites[1][0]} {whites[1][1]} {WHITE} {fc[0]} {fc[1]} "
        f"{int(p.auto_radius)} {p.radius} {int(p.auto_contrast)} {p.contrast} {p.radius_offset} {p.iterations} {int(p.iter_check)}\n")
    subprocess.run([str(REF_RING if ring else REF), str(d)], check=True)
    info = dict(l.split() for l in (d / "ref_info.txt").read_text().split("\n") if l)
    ld = lambda n: np.fromfile(d / f"ref_{n}.f32", f32).reshape(H, W)

    res = cs.capture_sharpening(*(planes[k].astype(f32) for k in ("raw", "r", "g", "b")),
                                np.array(whites, f32), f32(WHITE), fc, p, debug=True)
    rows = []
    ok = True
    if info.get("radius") == "nan":
        ok = res is None
        return ok, [("radius", "nan", "None" if res is None else res.radius)]
    rr, rc = float(info["radius"]), float(info["contrast"])
    rows.append(("radius", f"{rr:.9g}", f"{f32(res.radius):.9g}", f32(rr) == f32(res.radius)))
    rows.append(("contrast", f"{rc:.9g}", f"{f32(res.contrast):.9g}", f32(rc) == f32(res.contrast)))
    ok &= rows[-1][3] and rows[-2][3]
    for n, mine in (("clipmask", res.clip_mask), ("L", res.L), ("blend", res.blend), ("ynew", res.y_new),
                    ("r", res.r), ("g", res.g), ("b", res.b)):
        ref = ld(n)
        same = np.array_equal(ref.view(np.uint32), mine.astype(f32).view(np.uint32))
        diff = np.abs(ref.astype(np.float64) - mine)
        rel = diff / np.maximum(np.abs(ref), 1.0)
        rows.append((n, f"bitexact={same}", f"maxabs={diff.max():.3g} maxrel={rel.max():.3g} ndiff={(diff > 0).sum()}", same))
        ok &= same
    # sanity: how much did sharpening change the image?
    change = np.abs(ld("ynew").astype(np.float64) - cs.rgb2Y(*(planes[k].astype(f32) for k in "rgb"))).mean()
    rows.append(("mean |Ynew-Yold|", f"{change:.2f}", "", True))
    return ok, rows


def main():
    if not REF.exists():
        sys.exit("build the harness first: native/capture_sharpening/tools/rt_harness/build.sh")
    all_ok = True
    for name, H, W, blur, p, ring in CASES:
        ok, rows = run_case(name, H, W, blur, p, ring)
        all_ok &= ok
        print(f"== {name} ({H}x{W}){' [ring-reset ref]' if ring else ''}: {'PASS' if ok else 'FAIL'}")
        for r in rows:
            print("   ", *r[:3])
    print("ALL PASS" if all_ok else "SOME FAILED")
    sys.exit(0 if all_ok else 1)


if __name__ == "__main__":
    main()
