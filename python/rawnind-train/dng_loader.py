# SPDX-FileCopyrightText: 2026 RawLens contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""DNG dataset loader for RawNIND-tiny single-frame Bayer denoising.

What this is:
  numpy-only (stdlib + numpy) ingestion for training on Mac. Reads DNG
  bursts/pairs (e.g. HDR+ payloads: N noisy frames + one merged clean frame)
  plus lone clean DNGs for synthetic-noise training, and emits normalized
  RGGB-canonical patches the torch trainer consumes.

Contract (must match Android inference exactly):
  1. Normalize per pixel:  y = (code - black_ij) / (white - black_ij),
     where black_ij is selected by pixel parity ((y&1)<<1)|(x&1) -- the same
     convention as RawSensorUnpacker.unpackNormalized + RawNormalization.
  2. Canonicalize to RGGB, packed as H/2 x W/2 x 4 channels [R, G1, G2, B]
     (CFA order R,Gr,Gb,B, matching CfaNoiseModel in DenoiseSettings.kt).
     NOTE: python/burst-ref/synth.py pack_rggb() uses [R,G1,B,G2]; this
     loader deliberately uses [R,G1,G2,B]. Do not mix the two without
     permuting channels.
  3. Noise conditioning scalar per patch:
       sigma = sqrt(avg_scale * mean(y) + avg_offset)
     with (avg_scale, avg_offset) from the DNG noise profile
     (CfaNoiseModel.averageScale/averageOffset on Android).

Data layout under <root> (see scan_dataset):
  bursts/<id>/frame_*.dng + merged.dng   noisy burst frames + clean target
  singles/*.dng                          clean frames for synthetic training

DNG backends (lazy, first available wins):
  1. rawpy (+ numpy)   -- real DNGs, honors black/white/CFA/noise tags
  2. tiffile/imageio   -- fallback for LinearDNG-style files
  3. .npy files        -- pre-extracted dicts {codes, black4, white, pattern,
     shot, read}; used by self-test and headless CI without a DNG stack.

Augmentation policy: flip/rot180 are applied to the FULL frame BEFORE patch
extraction, then only even origins whose parity yields RGGB are sampled, so
no border wrap artifacts are introduced. rot90/transpose is intentionally
unsupported in v1 (it permutes the CFA in a way that needs care; hflip +
vflip + rot180 already give 4 orientations).
"""

import os

import numpy as np

# ---------------------------------------------------------------------------
# Bayer patterns
# ---------------------------------------------------------------------------

# 2x2 cells in [TL, TR, BL, BR] order, matching Camera2 reading order.
PATTERNS = {
    "RGGB": ("R", "G", "G", "B"),
    "GRBG": ("G", "R", "B", "G"),
    "GBRG": ("G", "B", "R", "G"),
    "BGGR": ("B", "G", "G", "R"),
}

# Packed [TL,TR,BL,BR] index permute -> canonical [R,G1,G2,B], where
# G1 = green on EVEN rows, G2 = green on ODD rows. (TL/TR sit on row 0,
# BL/BR on row 1, so e.g. BGGR's TR green is G1 and its BL green is G2.)
_TO_RGGB_PERM = {
    "RGGB": (0, 1, 2, 3),
    "GRBG": (1, 0, 3, 2),
    "GBRG": (2, 0, 3, 1),
    "BGGR": (3, 1, 2, 0),
}
_FROM_RGGB_PERM = {k: tuple(np.argsort(v)) for k, v in _TO_RGGB_PERM.items()}


def _shift_cells(cells, sx, sy):
    """Cells of the pattern seen from crop origin shifted by (sx, sy)."""
    out = []
    for dy in (0, 1):
        for dx in (0, 1):
            out.append(cells[(((dy + sy) & 1) << 1) | ((dx + sx) & 1)])
    return tuple(out)


def _pattern_name_of(cells):
    for name, ref in PATTERNS.items():
        if tuple(ref) == tuple(cells):
            return name
    raise ValueError("not a 2x2 Bayer pattern: %r" % (cells,))


def rggb_origin_parity(pattern):
    """(px, py) origin parity s.t. a crop of `pattern` at that parity is RGGB.

    Mirrors BayerPattern.shifted() in RawSensorUnpacker.kt.
    """
    cells = PATTERNS[pattern]
    for sy in (0, 1):
        for sx in (0, 1):
            if _pattern_name_of(_shift_cells(cells, sx, sy)) == "RGGB":
                return (sx, sy)
    raise AssertionError("unreachable")  # every Bayer shift hits RGGB


def shifted_pattern(pattern, sx, sy):
    """Pattern name observed after shifting the origin by (sx, sy)."""
    return _pattern_name_of(_shift_cells(PATTERNS[pattern], sx & 1, sy & 1))


# ---------------------------------------------------------------------------
# Normalize / pack
# ---------------------------------------------------------------------------

def normalize_bayer(codes, black4, white):
    """Per-parity black/white normalization to float32 [~0, ~1].

    codes: (H, W) uint16 raw codes. black4: 4 black levels indexed by
    ((y&1)<<1)|(x&1) (Camera2 SENSOR_BLACK_LEVEL_PATTERN order).
    white: scalar white level. Values are NOT clipped (matches
    RawSensorUnpacker, which leaves highlight headroom unclipped).
    """
    codes = np.asarray(codes)
    black4 = np.asarray(black4, dtype=np.float64)
    assert black4.shape == (4,), black4.shape
    assert np.all(np.asarray(white) > black4), (white, black4)
    h, w = codes.shape
    ys = (np.arange(h) & 1)[:, None]
    xs = (np.arange(w) & 1)[None, :]
    idx = (ys << 1) | xs
    black = black4[idx]
    return ((codes.astype(np.float64) - black) / (white - black)).astype(np.float32)


def pack_rggb(bayer):
    """(H, W) Bayer RGGB -> (H/2, W/2, 4) [R, G1, G2, B] float32."""
    bayer = np.asarray(bayer)
    h, w = bayer.shape
    assert h % 2 == 0 and w % 2 == 0, (h, w)
    return np.stack([bayer[0::2, 0::2], bayer[0::2, 1::2],
                     bayer[1::2, 0::2], bayer[1::2, 1::2]], axis=-1)


def unpack_rggb(packed):
    """Inverse of pack_rggb: (H/2, W/2, 4) -> (H, W) Bayer."""
    packed = np.asarray(packed)
    assert packed.shape[2] == 4, packed.shape
    h2, w2, _ = packed.shape
    out = np.empty((h2 * 2, w2 * 2), dtype=packed.dtype)
    out[0::2, 0::2] = packed[..., 0]
    out[0::2, 1::2] = packed[..., 1]
    out[1::2, 0::2] = packed[..., 2]
    out[1::2, 1::2] = packed[..., 3]
    return out


def packed_to_rggb(packed, pattern):
    """Reorder packed [TL,TR,BL,BR] of `pattern` to canonical [R,G1,G2,B]."""
    return np.asarray(packed)[..., list(_TO_RGGB_PERM[pattern])].copy()


def packed_from_rggb(canonical, pattern):
    """Inverse of packed_to_rggb (for tests / visualization)."""
    return np.asarray(canonical)[..., list(_FROM_RGGB_PERM[pattern])].copy()


# ---------------------------------------------------------------------------
# DNG reading (lazy backends)
# ---------------------------------------------------------------------------

def read_frame(path):
    """Read one frame file -> dict(codes, black4, white, pattern, shot, read).

    codes: (H, W) uint16. black4: float64 (4,). white: float.
    pattern: one of RGGB/GRBG/GBRG/BGGR. shot/read: Poisson-Gaussian
    coefficients in the NORMALIZED domain (may be None if unknown).
    Supports .dng (rawpy/tiffile) and .npy (dict with the same keys).
    """
    low = path.lower()
    if low.endswith(".npy"):
        d = np.load(path, allow_pickle=True).item()
        return {
            "codes": np.asarray(d["codes"]),
            "black4": np.asarray(d["black4"], dtype=np.float64),
            "white": float(d["white"]),
            "pattern": str(d.get("pattern", "RGGB")),
            "shot": d.get("shot"), "read": d.get("read"),
        }
    if low.endswith(".dng"):
        try:
            return _read_dng_rawpy(path)
        except ImportError:
            pass
        try:
            return _read_dng_tiffile(path)
        except ImportError:
            pass
        raise ImportError(
            "no DNG backend: pip install rawpy (preferred) or tiffile/imageio; "
            "or convert frames to .npy dicts {codes, black4, white, pattern}")
    raise ValueError("unsupported frame suffix: %s" % path)


def _read_dng_rawpy(path):
    import rawpy  # lazy: training-only dependency
    raw = rawpy.imread(path)
    codes = raw.raw_image_visible.copy()
    black = np.asarray(raw.black_level_per_channel, dtype=np.float64)
    # rawpy reports per-channel (R,G,B ...) levels; expand repeats to parity-4.
    if black.shape == (1,):
        black4 = np.repeat(black, 4)
    elif black.shape == (3,):
        black4 = np.array([black[0], black[1], black[1], black[2]])
    elif black.shape == (4,):
        black4 = black.copy()
    else:  # pragma: no cover - defensive
        black4 = np.repeat(float(np.median(black)), 4)
    white = float(raw.white_level)
    cdesc = raw.color_desc.decode() if isinstance(raw.color_desc, bytes) else str(raw.color_desc)
    pattern = _rawpy_pattern_to_name(raw.raw_pattern, cdesc)
    return {"codes": codes, "black4": black4, "white": white,
            "pattern": pattern, "shot": None, "read": None}


def _rawpy_pattern_to_name(raw_pattern, color_desc):
    idx = np.asarray(raw_pattern)
    letters = [[color_desc[int(idx[y, x])] for x in range(2)] for y in range(2)]
    cells = (letters[0][0], letters[0][1], letters[1][0], letters[1][1])
    conv = {"R": "R", "G": "G", "B": "B"}
    cells = tuple(conv.get(c, "G") for c in cells)
    return _pattern_name_of(cells)


def _read_dng_tiffile(path):
    import tiffile  # lazy fallback
    with tiffile.TiffFile(path) as tif:
        page = tif.pages[0]
        codes = page.asarray()
        tags = {t.name: t.value for t in page.tags.values()}
    black = tags.get("BlackLevel", 0)
    black4 = np.repeat(float(np.atleast_1d(black)[0]), 4)
    white = float(np.atleast_1d(tags.get("WhiteLevel", 65535))[0])
    return {"codes": np.asarray(codes), "black4": black4, "white": white,
            "pattern": "RGGB", "shot": None, "read": None}


# ---------------------------------------------------------------------------
# Dataset scan
# ---------------------------------------------------------------------------

def scan_dataset(root, burst_dirname="bursts", singles_dirname="singles",
                 gt_filename="merged.dng"):
    """Enumerate training items under root.

    Returns (pairs, singles) where pairs = [(noisy_paths, gt_path)] for
    bursts/<id>/ dirs containing frame_*.dng + merged.dng, and singles =
    [path] for clean lone DNGs/.npy used in synthetic-noise training.
    """
    pairs, singles = [], []
    bdir = os.path.join(root, burst_dirname)
    if os.path.isdir(bdir):
        for bid in sorted(os.listdir(bdir)):
            g = os.path.join(bdir, bid)
            if not os.path.isdir(g):
                continue
            frames = sorted(os.path.join(g, n) for n in os.listdir(g)
                            if n.startswith("frame_")
                            and n.lower().endswith((".dng", ".npy")))
            gt = os.path.join(g, gt_filename)
            if not os.path.isfile(gt):  # allow merged.npy pre-extracts
                alt = os.path.splitext(gt)[0] + ".npy"
                gt = alt if os.path.isfile(alt) else gt
            if frames and os.path.isfile(gt):
                pairs.append((frames, gt))
    sdir = os.path.join(root, singles_dirname)
    if os.path.isdir(sdir):
        for n in sorted(os.listdir(sdir)):
            if n.lower().endswith((".dng", ".npy")):
                singles.append(os.path.join(sdir, n))
    return pairs, singles


# ---------------------------------------------------------------------------
# Patch extraction (RGGB-canonical, saturation-filtered)
# ---------------------------------------------------------------------------

def extract_patches_rggb(bayer, pattern, patch=256, stride=128,
                         saturation_level=0.98, max_saturated_frac=0.05):
    """Tile a NORMALIZED full frame into RGGB Bayer patches.

    Only origins with the parity from rggb_origin_parity() are sampled, so
    every returned patch is natively RGGB (no shift/reorder needed).
    Saturated patches (fraction of pixels >= saturation_level above
    max_saturated_frac) are dropped -- clipped content teaches the net to
    invent highlights. Returns list of (H=patch, W=patch) float32 patches.
    """
    bayer = np.asarray(bayer, dtype=np.float32)
    h, w = bayer.shape
    px, py = rggb_origin_parity(pattern)
    out = []
    y = py
    while y + patch <= h:
        x = px
        while x + patch <= w:
            # Parity guard: (x%2, y%2) must equal required parity.
            if (x & 1) == px and (y & 1) == py:
                p = bayer[y:y + patch, x:x + patch]
                sat = float((p >= saturation_level).mean())
                if sat <= max_saturated_frac:
                    out.append(p)
            x += stride
        y += stride
    return out


def flip_bayer_full(bayer, pattern, mode, rng):
    """Flip/rot180 augment a full Bayer frame. Returns (aug, new_pattern).

    mode: 'h' | 'v' | 'rot180' | 'none'. 'random' draws from all four with
    rng (np.random.Generator). Applied BEFORE patch extraction; the new
    pattern is tracked so extract_patches_rggb() re-derives RGGB origins.
    """
    if mode == "random":
        mode = str(rng.choice(["none", "h", "v", "rot180"]))
    if mode == "none":
        return np.asarray(bayer), pattern
    if mode == "h":
        return np.asarray(bayer)[:, ::-1].copy(), shifted_pattern(pattern, 1, 0)
    if mode == "v":
        return np.asarray(bayer)[::-1, :].copy(), shifted_pattern(pattern, 0, 1)
    if mode == "rot180":
        return np.asarray(bayer)[::-1, ::-1].copy(), shifted_pattern(pattern, 1, 1)
    raise ValueError("unknown flip mode: %r" % mode)


# ---------------------------------------------------------------------------
# Noise conditioning
# ---------------------------------------------------------------------------

def estimate_sigma_scalar(patch_normalized, avg_scale, avg_offset):
    """Per-patch noise sigma from the DNG profile (CfaNoiseModel averages)."""
    m = float(np.clip(np.asarray(patch_normalized).mean(), 0.0, None))
    return float(np.sqrt(avg_scale * m + avg_offset))


def make_sigma_plane(h2, w2, sigma, dtype=np.float32):
    """Tile a scalar sigma to a packed-resolution conditioning plane."""
    return np.full((h2, w2, 1), sigma, dtype=dtype)


def _self_test():
    rng = np.random.default_rng(7)
    # 1. normalize: parity-indexed black levels.
    codes = np.array([[100, 110], [120, 130]], dtype=np.uint16)
    n = normalize_bayer(codes, [100, 10, 20, 30], 1100)
    assert abs(n[0, 0] - 0.0) < 1e-6 and n[1, 1] > 0, n
    # 2. pack/unpack round-trip.
    b = rng.random((64, 64)).astype(np.float32)
    assert np.abs(unpack_rggb(pack_rggb(b)) - b).max() < 1e-6
    # 3. channel permutes round-trip for all patterns, and the absolute
    # mapping matches the parity-derived Kotlin tables (RawNindPackTest):
    # G1 = even-row green, G2 = odd-row green.
    canon = rng.random((8, 8, 4)).astype(np.float32)
    for p in PATTERNS:
        assert np.abs(packed_from_rggb(packed_to_rggb(canon, p), p) - canon).max() < 1e-6, p
    assert _TO_RGGB_PERM["RGGB"] == (0, 1, 2, 3)
    assert _TO_RGGB_PERM["GRBG"] == (1, 0, 3, 2)
    assert _TO_RGGB_PERM["GBRG"] == (2, 0, 3, 1)
    assert _TO_RGGB_PERM["BGGR"] == (3, 1, 2, 0)
    # Absolute check on a 2x2 BGGR quad [B,G1,G2,R] -> canonical [R,G1,G2,B].
    q = np.array([[[1.0, 2.0, 3.0, 4.0]]])  # [TL,TR,BL,BR] = [B,G1,G2,R]
    assert list(packed_to_rggb(q, "BGGR")[0, 0]) == [4.0, 2.0, 3.0, 1.0]
    # 4. every pattern has an RGGB-parity origin; patches from it re-pack.
    big = rng.random((130, 130)).astype(np.float32)
    for p in PATTERNS:
        px, py = rggb_origin_parity(p)
        assert shifted_pattern(p, px, py) == "RGGB", (p, px, py)
        ps = extract_patches_rggb(big, p, patch=64, stride=64)
        assert len(ps) == 4, (p, len(ps))  # 2x2 grid on 130px frame
        for q in ps:
            assert q.shape == (64, 64) and pack_rggb(q).shape == (32, 32, 4)
    # 5. saturation filter drops white patches, keeps flat gray (needed).
    white = np.ones((64, 64), np.float32)
    assert extract_patches_rggb(white, "RGGB", patch=64, stride=64) == []
    gray = np.full((64, 64), 0.18, np.float32)
    assert len(extract_patches_rggb(gray, "RGGB", patch=64, stride=64)) == 1
    # 6. flips track the pattern; re-extraction still yields RGGB patches.
    # 130px frame so odd-parity origins still fit a 2x2 grid of 64px patches.
    frame = rng.random((130, 130)).astype(np.float32)
    for mode in ("h", "v", "rot180"):
        aug, pat2 = flip_bayer_full(frame, "RGGB", mode, rng)
        assert aug.shape == frame.shape
        px, py = rggb_origin_parity(pat2)
        assert shifted_pattern(pat2, px, py) == "RGGB"
        assert len(extract_patches_rggb(aug, pat2, patch=64, stride=64)) == 4
    # 7. GRBG frame: only odd-x origins sampled.
    ps = extract_patches_rggb(big, "GRBG", patch=64, stride=32)
    assert len(ps) > 0 and all(q.shape == (64, 64) for q in ps)
    # 8. sigma helpers.
    s = estimate_sigma_scalar(np.full((16, 16), 0.18, np.float32), 2.5e-4, 2.5e-6)
    assert 0 < s < 0.05, s
    assert make_sigma_plane(4, 4, s).shape == (4, 4, 1)
    # 9. scan_dataset finds pairs + singles (incl. .npy pre-extracts).
    import tempfile
    with tempfile.TemporaryDirectory() as root:
        os.makedirs(os.path.join(root, "bursts", "b0"))
        for i in range(2):
            np.save(os.path.join(root, "bursts", "b0", "frame_%02d.npy" % i),
                    {"codes": np.zeros((4, 4), np.uint16), "black4": np.zeros(4),
                     "white": 1023.0, "pattern": "RGGB"})
        np.save(os.path.join(root, "bursts", "b0", "merged.npy"),
                {"codes": np.zeros((4, 4), np.uint16), "black4": np.zeros(4),
                 "white": 1023.0, "pattern": "RGGB"})
        os.makedirs(os.path.join(root, "singles"))
        np.save(os.path.join(root, "singles", "c.npy"),
                {"codes": np.zeros((4, 4), np.uint16), "black4": np.zeros(4),
                 "white": 1023.0, "pattern": "RGGB"})
        pairs, singles = scan_dataset(root)
        assert len(pairs) == 1 and len(pairs[0][0]) == 2, (pairs, singles)
        assert len(singles) == 1, singles
        d = read_frame(pairs[0][1])
        assert d["codes"].shape == (4, 4) and d["pattern"] == "RGGB"
    print("dng_loader.py self-test ok")


if __name__ == "__main__":
    _self_test()
