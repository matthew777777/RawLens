"""Synthetic RAW burst synthesis (DBSR Sec 5.1 + NEBI Sec 3.1, numpy reference).

Pipeline per burst, given one sRGB HR image (H, W, 3) float32 in [0, 1]:
  1. Inverse ISP-lite: gamma expansion -> linear RGB (the "scene radiance").
  2. Motion: per-frame rigid warp (translation px + rotation deg, bilinear).
  3. Downsample by scale s (box average, the LR sampling).
  4. Heteroscedastic noise: Var = shot * y + read^2  (Poisson-Gaussian model,
     Brooks et al. "Unprocessing", DBSR Sec 5.1; shot/read scale with exposure
     for NEBI sweeps: longer exposure -> less noise, more motion blur proxy).
  5. Bayer mosaic (RGGB) -> one RAW plane per frame.
  6. NEBI sweep: exposures e_0..e_{N-1} ramp; motion amplitude grows with
     exposure (blur proxy via larger warp), noise falls as 1/sqrt(e).

Units: linear-light floats in [0, 1] throughout (matches the Kotlin
normalized exposure domain; Radiometry.kt). Warps are (dx, dy) pixel shifts
plus rotation degrees about the frame centre, applied in HR coordinates.
"""
import numpy as np

# Poisson-Gaussian defaults (DBSR/SyntheticBurst magnitude, linear domain).
SHOT = 2.5e-3
READ = 4.0e-3


def srgb_to_linear(img):
    """Inverse gamma (BT.709-ish piecewise); img float in [0, 1]."""
    img = np.asarray(img, dtype=np.float64)
    lo = img <= 0.04045
    out = np.empty_like(img)
    out[lo] = img[lo] / 12.92
    out[~lo] = ((img[~lo] + 0.055) / 1.055) ** 2.4
    return out.astype(np.float32)


def linear_to_srgb(img):
    img = np.asarray(img, dtype=np.float64)
    lo = img <= 0.0031308
    out = np.empty_like(img)
    out[lo] = img[lo] * 12.92
    out[~lo] = 1.055 * np.clip(img[~lo], 0, None) ** (1.0 / 2.4) - 0.055
    return np.clip(out, 0, 1).astype(np.float32)


def warp_rigid(img, dx, dy, deg):
    """Translate by (dx, dy) px then rotate deg about centre (bilinear).

    Positive dx shifts content right. Out-of-frame samples replicate edges
    (matches QuadSplit clamped borders on the Kotlin side).
    """
    h, w = img.shape[:2]
    th = np.deg2rad(deg)
    c, s = np.cos(th), np.sin(th)
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float64)
    xc, yc = (w - 1) / 2.0, (h - 1) / 2.0
    # Inverse map: destination -> source.
    xd = xs - xc - dx
    yd = ys - yc - dy
    x0 = c * xd + s * yd + xc
    y0 = -s * xd + c * yd + yc
    x0 = np.clip(x0, 0, w - 1)
    y0 = np.clip(y0, 0, h - 1)
    x_lo = np.floor(x0).astype(int)
    y_lo = np.floor(y0).astype(int)
    x_hi = np.minimum(x_lo + 1, w - 1)
    y_hi = np.minimum(y_lo + 1, h - 1)
    fx = (x0 - x_lo)[..., None]
    fy = (y0 - y_lo)[..., None]
    img = np.asarray(img, dtype=np.float64)
    top = img[y_lo, x_lo] * (1 - fx) + img[y_lo, x_hi] * fx
    bot = img[y_hi, x_lo] * (1 - fx) + img[y_hi, x_hi] * fx
    return (top * (1 - fy) + bot * fy).astype(np.float32)


def box_downsample(img, s):
    """Mean-pool by integer factor s (LR sampling). Crops remainder."""
    h, w = img.shape[:2]
    h2, w2 = (h // s) * s, (w // s) * s
    img = img[:h2, :w2]
    return img.reshape(h2 // s, s, w2 // s, s, -1).mean(axis=(1, 3))


def add_shot_read_noise(img, shot, read, rng):
    """Heteroscedastic Gaussian: n ~ N(0, shot*y + read^2)."""
    var = shot * np.clip(img, 0, None) + read * read
    return (img + rng.normal(0, 1, img.shape) * np.sqrt(var)).astype(np.float32)


def mosaic_rggb(rgb):
    """Bayer-mosaic an (h, w, 3) RGB frame to one RAW plane (RGGB)."""
    h, w, _ = rgb.shape
    h2, w2 = (h // 2) * 2, (w // 2) * 2
    rgb = rgb[:h2, :w2]
    raw = np.empty((h2, w2), dtype=np.float32)
    raw[0::2, 0::2] = rgb[0::2, 0::2, 0]  # R
    raw[0::2, 1::2] = rgb[0::2, 1::2, 1]  # G1
    raw[1::2, 0::2] = rgb[1::2, 0::2, 1]  # G2
    raw[1::2, 1::2] = rgb[1::2, 1::2, 2]  # B
    return raw


def pack_rggb(raw):
    """Split one RAW plane into 4 quad channels (R, G1, B, G2)."""
    return np.stack([raw[0::2, 0::2], raw[0::2, 1::2],
                     raw[1::2, 1::2], raw[1::2, 0::2]], axis=-1)


def make_burst(hr_srgb, n=8, scale=4, max_shift=24.0, max_rot=1.0,
               shot=SHOT, read=READ, seed=0):
    """Uniform-exposure synthetic burst (DBSR Sec 5.1).

    Returns dict with keys: raw (n, h, w) float32, packed (n, h/2, w/2, 4),
    shifts (n, 2), rots (n,), linear_hr (H, W, 3).
    """
    rng = np.random.default_rng(seed)
    lin = srgb_to_linear(hr_srgb)
    shifts = rng.uniform(-max_shift, max_shift, size=(n, 2))
    rots = rng.uniform(-max_rot, max_rot, size=n)
    shifts[0] = (0.0, 0.0)
    rots[0] = 0.0
    raws = []
    for i in range(n):
        w = warp_rigid(lin, shifts[i, 0], shifts[i, 1], rots[i])
        lr = box_downsample(w, scale)
        noisy = add_shot_read_noise(lr, shot, read, rng)
        raws.append(mosaic_rggb(np.clip(noisy, 0, 1)))
    raw = np.stack(raws)
    packed = np.stack([pack_rggb(f) for f in raw])
    return {"raw": raw, "packed": packed, "shifts": shifts, "rots": rots,
            "linear_hr": lin}


def make_nebi_sweep(hr_srgb, n=14, scale=4, seed=0,
                    t_min=0.01, t_max=0.14):
    """Non-uniform exposure sweep (NEBI Sec 3.1 lite).

    Exposure ramps geometrically t_min -> t_max. Noise falls as 1/sqrt(t)
    (more photons) while motion amplitude grows linearly with t (blur proxy).
    Frame 0 is the short/noisy end, frame n-1 the long/smeared end; the
    optimal anchor is interior (never frame 0 by construction).
    Returns make_burst-style dict plus 'exposures' (n,) in seconds.
    """
    rng = np.random.default_rng(seed)
    lin = srgb_to_linear(hr_srgb)
    exposures = np.geomspace(t_min, t_max, n).astype(np.float64)
    e0 = exposures[n // 2]
    raws = []
    shifts = np.zeros((n, 2))
    rots = np.zeros(n)
    for i, t in enumerate(exposures):
        amp = float(t / t_min)  # motion grows with exposure
        dx, dy = rng.uniform(-2.0, 2.0, size=2) * amp
        rot = float(rng.uniform(-0.1, 0.1) * amp)
        shifts[i] = (dx, dy)
        rots[i] = rot
        w = warp_rigid(lin, dx, dy, rot)
        lr = box_downsample(w, scale)
        k = float(np.sqrt(e0 / t))  # noise relative to mid exposure
        noisy = add_shot_read_noise(lr, SHOT * k * k, READ * k, rng)
        raws.append(mosaic_rggb(np.clip(noisy, 0, 1)))
    raw = np.stack(raws)
    packed = np.stack([pack_rggb(f) for f in raw])
    return {"raw": raw, "packed": packed, "shifts": shifts, "rots": rots,
            "linear_hr": lin, "exposures": exposures}


def _self_test():
    rng = np.random.default_rng(7)
    hr = rng.random((64, 64, 3)).astype(np.float32)
    b = make_burst(hr, n=4, scale=4, seed=11)
    assert b["raw"].shape == (4, 16, 16), b["raw"].shape
    assert b["packed"].shape == (4, 8, 8, 4)
    assert np.all(b["shifts"][0] == 0) and b["rots"][0] == 0
    # Identity warp is exact.
    w = warp_rigid(hr, 0.0, 0.0, 0.0)
    assert np.abs(w - hr).max() < 1e-5, np.abs(w - hr).max()
    # Integer shift moves content exactly (edge-replicated borders excluded).
    w = warp_rigid(hr, 2.0, 0.0, 0.0)
    assert np.abs(w[:, 2:-1] - hr[:, :-3]).max() < 1e-5
    # Mosaic/pack round-trip positions: R quad holds red samples.
    raw = mosaic_rggb(hr)
    assert raw[0, 0] == hr[0, 0, 0] and raw[1, 1] == hr[1, 1, 2]
    p = pack_rggb(raw)
    assert p.shape == (32, 32, 4) and p[0, 0, 0] == hr[0, 0, 0]
    # NEBI sweep: exposures ramp, noise falls along the ramp.
    nb = make_nebi_sweep(hr, n=6, scale=4, seed=3)
    assert nb["exposures"][0] < nb["exposures"][-1]
    flat = np.full((64, 64, 3), 0.18, dtype=np.float32)
    vars = []
    for i in range(6):
        f = make_nebi_sweep(flat, n=6, scale=4, seed=3)["raw"][i]
        vars.append(f.var())
    assert vars[0] > vars[-1], vars  # short end noisier than long end
    print("synth.py self-test ok")


if __name__ == "__main__":
    _self_test()
