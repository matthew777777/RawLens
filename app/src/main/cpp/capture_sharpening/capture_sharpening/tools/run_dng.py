"""Run capsharp_reference.py on a Bayer DNG at several iteration counts and render comparison crops.

uv run --with numpy --with rawpy --with pillow python native/capture_sharpening/tools/run_dng.py <dng> [out_dir]
    [--mhc] [--iters 3 5 10] [--crop 320] [--scale 2]
AMaZE needs tools/rt_amaze/build.sh first.

Pre-processing mirrors what RT feeds capture sharpening: black-subtracted, DNG GainMap
(OpcodeList2) applied, white balance applied, scaled so sensor white = 65535, unclipped
float. Demosaic is RT's AMaZE (amaze/build/amaze_ref, verbatim RT source) or, with --mhc,
Malvar-He-Cutler.
Rendering (crops only): ForwardMatrix1 -> XYZ D50 -> linear sRGB -> sRGB OETF, one
global exposure for every variant.
"""
from __future__ import annotations

import json
import struct
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
import rawpy
from PIL import Image, ImageDraw, ImageFont

import capsharp_reference as cs

f32 = np.float32
ITERS = [1, 3, 5, 10, 20]
LEVELS = {3: "Low (3)", 5: "Sharp (5)", 10: "Sharpest (10)"}


def exif(path, *tags, binary=False):
    args = ["exiftool", "-b" if binary else "-s3"] + [f"-{t}" for t in tags] + [str(path)]
    return subprocess.run(args, check=True, capture_output=True).stdout


def nums(path, tag):
    return [float(x) for x in exif(path, tag).decode().split()]


def parse_gain_maps(blob: bytes):
    """DNG OpcodeList GainMap entries (DNG spec 1.6, opcode 9)."""
    n, = struct.unpack_from(">I", blob, 0)
    off = 4
    maps = []
    for _ in range(n):
        op, ver, flags, size = struct.unpack_from(">IIII", blob, off)
        off += 16
        p = blob[off:off + size]
        off += size
        if op != 9:
            continue
        top, left, bottom, right, plane, planes, rpitch, cpitch, nv, nh = struct.unpack_from(">10I", p, 0)
        sv, sh, ov, oh = struct.unpack_from(">4d", p, 40)
        mplanes, = struct.unpack_from(">I", p, 72)
        g = np.frombuffer(p, ">f4", nv * nh * mplanes, 76).reshape(nv, nh, mplanes)[..., 0].astype(f32)
        maps.append(dict(top=top, left=left, bottom=bottom, right=right, rpitch=rpitch, cpitch=cpitch,
                         sv=sv, sh=sh, ov=ov, oh=oh, gain=g))
    return maps


def apply_gain_map(img, m):
    """Multiply the selected sub-lattice by the bilinearly interpolated gain (coords relative to image)."""
    H, W = img.shape
    rows = np.arange(m["top"], m["bottom"], m["rpitch"])
    cols = np.arange(m["left"], m["right"], m["cpitch"])
    g = m["gain"]
    nv, nh = g.shape

    def coords(idx, size, origin, spacing, n):
        t = ((idx + 0.5) / size - origin) / spacing
        t = np.clip(t, 0, n - 1)
        i0 = np.minimum(np.floor(t).astype(int), n - 2) if n > 1 else np.zeros_like(t, int)
        return i0, (t - i0).astype(f32)

    r0, fr = coords(rows, H, m["ov"], m["sv"], nv)
    c0, fc = coords(cols, W, m["oh"], m["sh"], nh)
    r1 = np.minimum(r0 + 1, nv - 1)
    c1 = np.minimum(c0 + 1, nh - 1)
    top = g[r0][:, c0] * (1 - fc) + g[r0][:, c1] * fc
    bot = g[r1][:, c0] * (1 - fc) + g[r1][:, c1] * fc
    gain = top * (1 - fr[:, None]) + bot * fr[:, None]
    img[np.ix_(rows, cols)] *= gain


def conv5(img, k):
    H, W = img.shape
    p = np.pad(img, 2, mode="reflect")
    out = np.zeros((H, W), f32)
    for i in range(5):
        for j in range(5):
            if k[i][j]:
                out += f32(k[i][j]) * p[i:i + H, j:j + W]
    return out


def demosaic_mhc_rggb(raw):
    """Malvar-He-Cutler 2004 gradient-corrected bilinear, RGGB."""
    G_RB = np.array([[0, 0, -1, 0, 0], [0, 0, 2, 0, 0], [-1, 2, 4, 2, -1], [0, 0, 2, 0, 0], [0, 0, -1, 0, 0]]) / 8
    ROW = np.array([[0, 0, .5, 0, 0], [0, -1, 0, -1, 0], [-1, 4, 5, 4, -1], [0, -1, 0, -1, 0], [0, 0, .5, 0, 0]]) / 8
    COL = ROW.T
    DIAG = np.array([[0, 0, -1.5, 0, 0], [0, 2, 0, 2, 0], [-1.5, 0, 6, 0, -1.5], [0, 2, 0, 2, 0], [0, 0, -1.5, 0, 0]]) / 8
    H, W = raw.shape
    r, g, b = (np.empty((H, W), f32) for _ in range(3))
    kg = conv5(raw, G_RB)
    g[:] = raw
    g[0::2, 0::2] = kg[0::2, 0::2]
    g[1::2, 1::2] = kg[1::2, 1::2]
    del kg
    krow = conv5(raw, ROW)
    kcol = conv5(raw, COL)
    kdiag = conv5(raw, DIAG)
    r[0::2, 0::2] = raw[0::2, 0::2]
    r[0::2, 1::2] = krow[0::2, 1::2]
    r[1::2, 0::2] = kcol[1::2, 0::2]
    r[1::2, 1::2] = kdiag[1::2, 1::2]
    b[1::2, 1::2] = raw[1::2, 1::2]
    b[1::2, 0::2] = krow[1::2, 0::2]
    b[0::2, 1::2] = kcol[0::2, 1::2]
    b[0::2, 0::2] = kdiag[0::2, 0::2]
    return r, g, b


XYZD50_TO_SRGB = np.array([[3.1338561, -1.6168667, -0.4906146],
                           [-0.9787684, 1.9161415, 0.0334540],
                           [0.0719453, -0.2289914, 1.4052427]], f32)


def render(r, g, b, fm, exposure):
    cam = np.stack([r, g, b], -1) / f32(65535.0)
    lin = cam @ (XYZD50_TO_SRGB @ fm).T.astype(f32) * f32(exposure)
    lin = np.clip(lin, 0, 1)
    srgb = np.where(lin <= 0.0031308, 12.92 * lin, 1.055 * np.power(lin, 1 / 2.4) - 0.055)
    return (srgb * 255 + 0.5).astype(np.uint8)


def pick_crops(y, n=4, size=320):
    """Pick n non-overlapping, highly textured, unclipped regions."""
    H, W = y.shape
    bs = size
    gy = np.abs(np.diff(y, axis=0))[:, :-1] + np.abs(np.diff(y, axis=1))[:-1, :]
    nb_y, nb_x = (H - 1) // bs, (W - 1) // bs
    score = gy[:nb_y * bs, :nb_x * bs].reshape(nb_y, bs, nb_x, bs).mean(axis=(1, 3))
    bright = y[:nb_y * bs, :nb_x * bs].reshape(nb_y, bs, nb_x, bs).max(axis=(1, 3))
    score = np.where(bright > 60000, 0, score / np.maximum(y[:nb_y * bs, :nb_x * bs].reshape(nb_y, bs, nb_x, bs).mean(axis=(1, 3)), 500) ** 0.5)
    picks = []
    for k in np.argsort(score.ravel())[::-1]:
        i, j = divmod(int(k), nb_x)
        if all(abs(i - a) > 1 or abs(j - b) > 1 for a, b in picks):
            picks.append((i, j))
        if len(picks) == n:
            break
    picks.append(((H // 2) // bs, (W // 2) // bs))  # centre crop
    return [(i * bs, j * bs, bs) for i, j in picks]


def demosaic_amaze(raw, initial_gain, work: Path):
    """RT AMaZE via the standalone reference tool."""
    tool = Path(__file__).resolve().parents[3] / "tmp" / "capture_sharpening" / "rt_amaze" / "amaze_ref"
    if not tool.exists():
        sys.exit("build AMaZE first: native/capture_sharpening/tools/rt_amaze/build.sh")
    work.mkdir(parents=True, exist_ok=True)
    H, W = raw.shape
    raw.astype(f32).tofile(work / "raw.f32")
    (work / "amaze_params.txt").write_text(f"{W} {H} 0 1 1 2 {initial_gain!r}\n")
    subprocess.run([str(tool), str(work)], check=True)
    out = tuple(np.fromfile(work / f"amaze_{c}.f32", f32).reshape(H, W) for c in "rgb")
    for c in ("raw", "amaze_r", "amaze_g", "amaze_b"):
        (work / f"{c}.f32").unlink()
    return out


def main():
    import argparse

    ap = argparse.ArgumentParser()
    ap.add_argument("dng")
    ap.add_argument("out", nargs="?")
    ap.add_argument("--mhc", action="store_true", help="Malvar-He-Cutler instead of RT AMaZE")
    ap.add_argument("--iters", type=int, nargs="+", default=ITERS)
    ap.add_argument("--crop", type=int, default=320, help="crop size in image pixels")
    ap.add_argument("--scale", type=int, default=2, help="nearest-neighbour zoom in the grid")
    a = ap.parse_args()
    argv = [a.dng] + ([a.out] if a.out else [])
    use_mhc = a.mhc
    dng = Path(argv[0])
    out = Path(argv[1]) if len(argv) > 1 else (Path(__file__).resolve().parents[3] / "tmp" / "capture_sharpening" / "out" /
                                               (dng.stem + ("_mhc" if use_mhc else "_amaze")))
    out.mkdir(parents=True, exist_ok=True)
    t0 = time.time()

    with rawpy.imread(str(dng)) as rp:
        raw = rp.raw_image_visible.astype(f32)
        pattern = rp.raw_pattern.tolist()
    if pattern != [[0, 1], [3, 2]]:
        sys.exit(f"only RGGB handled, got {pattern}")
    black = nums(dng, "BlackLevel")
    white = nums(dng, "WhiteLevel")[0]
    neutral = nums(dng, "AsShotNeutral")
    fm = np.array(nums(dng, "ForwardMatrix1"), f32).reshape(3, 3)
    blk = np.empty((2, 2), f32)
    blk.flat[:] = black[:4]
    H, W = raw.shape
    raw -= np.tile(blk, (H // 2, W // 2))
    raw /= f32(white - np.mean(black))
    maps = parse_gain_maps(exif(dng, "OpcodeList2", binary=True))
    for m in maps:
        apply_gain_map(raw, m)
    pre_mul = np.array([1 / neutral[0], 1 / neutral[1], 1 / neutral[2]])
    pre_mul /= pre_mul.min()
    cfa_mul = np.array([[pre_mul[0], pre_mul[1]], [pre_mul[1], pre_mul[2]]], f32)
    raw *= np.tile(cfa_mul * f32(65535.0), (H // 2, W // 2))
    whites = (f32(65535.0) * cfa_mul * f32(0.95)).astype(f32)
    clip_val = f32(65535.0 * pre_mul[1])
    print(f"decoded {W}x{H}, {len(maps)} gain maps, wb {pre_mul.round(4).tolist()} in {time.time() - t0:.1f}s", flush=True)

    t = time.time()
    if use_mhc:
        r, g, b = demosaic_mhc_rggb(raw)
    else:
        r, g, b = demosaic_amaze(raw, float(pre_mul.max() / pre_mul.min()), out / "work")
    print(f"demosaic {'MHC' if use_mhc else 'AMaZE'} {time.time() - t:.1f}s", flush=True)

    p = cs.Params()
    t = time.time()
    clip = cs.build_clip_mask_bayer(raw, whites)
    radius = float(min(cs.calc_radius_bayer(raw, 1000.0, clip_val, (0, 1)), f32(2.0)))
    t_radius = time.time() - t
    del raw
    t = time.time()
    L = cs.rgb2L(r, g, b)
    y_old = cs.rgb2Y(r, g, b)
    blend, contrast = cs.build_blend_mask(L, clip, f32(p.contrast / 100.0), p.auto_contrast)
    del L, clip
    t_mask = time.time() - t
    print(f"auto radius {radius:.4f} ({t_radius:.1f}s), auto contrast {float(contrast) * 100:.1f} ({t_mask:.1f}s), "
          f"blend>0.5 on {(blend > 0.5).mean() * 100:.1f}% of pixels", flush=True)

    y_ref = y_old
    exposure = float(1.0 / max(np.percentile((y_old[::8, ::8] / 65535.0), 99.0), 1e-3))
    exposure = min(max(exposure, 1.0), 8.0)
    crops = pick_crops(y_old, size=a.crop)
    variants = {"off": (r, g, b)}
    stats = {"file": dng.name, "demosaic": "MHC" if use_mhc else "AMaZE", "size": [W, H], "radius": radius, "contrast": float(contrast) * 100,
             "exposure": exposure, "crops": crops, "runs": {}}
    for it in a.iters:
        cs.ACTIVE_LOG = []
        t = time.time()
        y_new = y_old.copy()
        cs.capture_deconv_sharpening(y_new, y_old, blend, f32(radius), f32(0.0), it, p.iter_check)
        factor = y_new / np.maximum(y_old, f32(0.00001))
        dt = time.time() - t
        diff = np.abs(y_new - y_ref)
        stats["runs"][it] = {"seconds": round(dt, 2), "mean_abs_dY": float(diff.mean()),
                             "active_tiles_per_iter": cs.ACTIVE_LOG}
        print(f"iterations {it:2d}: {dt:6.1f}s  mean|dY| {diff.mean():.1f}  active tiles/iter {cs.ACTIVE_LOG}", flush=True)
        variants[LEVELS.get(it, f"{it} it")] = tuple(c * factor for c in (r, g, b))
        del y_new, factor

    # crops
    names = list(variants)
    tiles = []
    for ci, (y0, x0, s) in enumerate(crops):
        row = []
        for name in names:
            vr, vg, vb = (c[y0:y0 + s, x0:x0 + s] for c in variants[name])
            img = render(vr, vg, vb, fm, exposure)
            Image.fromarray(img).save(out / f"crop{ci}_{name.split(' ')[0].lower()}.png")
            row.append(img)
        tiles.append(row)
    scale = a.scale
    s = crops[0][2] * scale
    pad = 8
    font = ImageFont.load_default(size=max(14, s // 24))
    label_h = font.size + 16
    grid = Image.new("RGB", (len(names) * (s + pad) + pad, len(crops) * (s + pad) + pad + label_h), (24, 24, 24))
    draw = ImageDraw.Draw(grid)
    for j, name in enumerate(names):
        draw.text((pad + j * (s + pad) + 6, 8), name, fill=(230, 230, 230), font=font)
    for i, row in enumerate(tiles):
        for j, img in enumerate(row):
            im = Image.fromarray(img).resize((s, s), Image.NEAREST)
            grid.paste(im, (pad + j * (s + pad), label_h + pad + i * (s + pad)))
    grid.save(out / f"grid_{scale}x.png")
    # full preview of the 20-iteration result and the blend mask
    Image.fromarray((np.clip(blend[::4, ::4], 0, 1) * 255).astype(np.uint8)).save(out / "blend_mask_quarter.png")
    (out / "stats.json").write_text(json.dumps(stats, indent=1))
    print(f"wrote {out} in {time.time() - t0:.0f}s total")


if __name__ == "__main__":
    main()
