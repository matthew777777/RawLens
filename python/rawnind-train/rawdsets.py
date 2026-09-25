# SPDX-FileCopyrightText: 2026 RawLens contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Streaming RAW patch dataset: torch-free AND mlx-free (numpy only).

Kept import-light on purpose: under macOS spawn, DataLoader workers
re-execute the main script's top-level imports, so every heavy import
(torch, mlx) in a worker process costs ~1GB on an 8GB Air. With this
module holding the dataset, workers import numpy + dng_loader only
(~100MB); framework conversion happens in the main process per batch:

  * train.py:    torch.from_numpy(numpy_batch)  (zero-copy, shared memory)
  * train_mlx.py: mx.array(numpy_batch)

Memory model per epoch: frames cached as uint16 codes (~24MB per 12MP
frame) + a tiny slot index (coords + aug/noise params). Pixel work
(crop, flip, pack, noise) happens per sample in workers. Deterministic
per slot: shuffling/workers change order only, never values.

Use numpy_collate as DataLoader's collate_fn so batches stay numpy
(the default collate would build torch tensors inside workers).
"""

import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dng_loader
import synthetic

_PATCH_MODES = ("none", "h", "v", "rot180")
_MODE_SHIFT = {"none": (0, 0), "h": (1, 0), "v": (0, 1), "rot180": (1, 1)}


def _load_codes(path):
    """Frame codes + scalar meta; stays uint16 until patch time."""
    d = dng_loader.read_frame(path)
    codes = np.asarray(d["codes"])
    assert codes.ndim == 2, codes.shape
    return {
        "codes": codes,
        "black4": np.asarray(d["black4"], dtype=np.float64),
        "white": float(d["white"]),
        "pattern": str(d.get("pattern", "RGGB")),
    }


def _flip_codes(codes, mode):
    """Bayer flip variants (views; cropped to contiguous at patch time)."""
    if mode == "none":
        return codes
    if mode == "h":
        return codes[:, ::-1]
    if mode == "v":
        return codes[::-1, :]
    if mode == "rot180":
        return codes[::-1, ::-1]
    raise ValueError("unknown flip mode: %r" % mode)


def _patch_coords(codes, black4, white, patch, stride, sat_level, max_sat_frac):
    """Even-origin RGGB patch origins passing the saturation filter.

    Saturation is tested on raw codes (per-parity thresholds) via one
    vectorized integral-image pass -- no full-frame normalize needed.
    """
    h, w = codes.shape
    ys = (np.arange(h) & 1)[:, None]
    xs = (np.arange(w) & 1)[None, :]
    thr = np.asarray(black4) + sat_level * (white - np.asarray(black4))
    sat = codes >= thr[(ys << 1) | xs]
    ii = np.zeros((h + 1, w + 1), dtype=np.int32)
    ii[1:, 1:] = np.cumsum(np.cumsum(sat, axis=0), axis=1)
    area = patch * patch
    out = []
    for y in range(0, h - patch + 1, stride):
        row = ii[y + patch] - ii[y]
        for x in range(0, w - patch + 1, stride):
            if (x & 1) or (y & 1):
                continue  # RGGB parity (stride is even, so 0-start stays even)
            if float(row[x + patch] - row[x]) / area <= max_sat_frac:
                out.append((x, y))
    return out


def _pack_canonical(flipped, eff_pattern):
    """(P,P) Bayer with pattern `eff_pattern` -> (P/2,P/2,4) [R,G1,G2,B].

    dng_loader.pack_rggb packs positionally ([TL,TR,BL,BR] quads), so it is
    fed straight into packed_to_rggb. The flip mode that produced
    `eff_pattern` needs no border fixups: packing reads the pattern as-is
    instead of shifting pixels back to RGGB.
    """
    q = dng_loader.pack_rggb(np.ascontiguousarray(flipped))
    return dng_loader.packed_to_rggb(q, eff_pattern)


def numpy_collate(batch):
    """DataLoader collate keeping batches as numpy (see module docstring)."""
    return (np.stack([b[0] for b in batch]), np.stack([b[1] for b in batch]))


def build_items(data_root, cfg, avg_scale=None, avg_offset=None):
    """Scan data_root -> item list (torch-free; shared by both trainers).

    Noise-profile defaults fall back to TrainConfig shot/read mapped to
    scale/offset (synthetic.py SHOT/READ); per-DNG shot/read tags override
    when present.
    """
    pairs, singles = dng_loader.scan_dataset(
        data_root, cfg.burst_dirname, cfg.singles_dirname, cfg.gt_filename)
    s0 = avg_scale if avg_scale is not None else cfg.shot
    o0 = avg_offset if avg_offset is not None else cfg.read ** 2
    items = [{"kind": "real", "noisy_paths": f, "clean_path": g,
              "avg_scale": s0, "avg_offset": o0} for f, g in pairs]
    items += [{"kind": "synth", "clean_path": s} for s in singles]
    return items


class RawStreamDataset:
    """Streaming (noisy, clean, sigma) patches; numpy in, numpy out.

    Duck-typed map-style dataset (no framework base class, so workers stay
    import-light). __getitem__ returns ((5,H2,W2), (4,H2,W2)) float32 CHW.
    """

    def __init__(self, items, cfg, rng, for_val=False):
        self.cfg = cfg
        self.for_val = for_val
        self.frames = []
        self.slots = []  # (frame_idx, x, y, mode, eff_pattern, shot, read, seed)
        for it in items:
            self._index_item(it, rng)

    def _index_item(self, it, rng):
        cfg = self.cfg
        if it["kind"] == "real":
            noisy_pool = it["noisy_paths"]
            np_ = noisy_pool[0] if self.for_val else str(rng.choice(noisy_pool))
            nb = _load_codes(np_)
            cb = _load_codes(it["clean_path"])
            h = min(nb["codes"].shape[0], cb["codes"].shape[0])
            w = min(nb["codes"].shape[1], cb["codes"].shape[1])
            mode = "none" if self.for_val else str(rng.choice(list(_PATCH_MODES)))
            fidx = len(self.frames)
            self.frames.append({
                "noisy": nb, "clean": cb, "h": h, "w": w,
                "pattern": nb["pattern"], "scale": it["avg_scale"], "off": it["avg_offset"],
            })
            eff = dng_loader.shifted_pattern(nb["pattern"], *_MODE_SHIFT[mode])
            # Saturation filter on the clean frame (clipped content has no GT).
            for x, y in _patch_coords(cb["codes"][:h, :w], cb["black4"], cb["white"],
                                      cfg.bayer_patch, cfg.stride,
                                      cfg.saturation_level, cfg.max_saturated_frac):
                self.slots.append((fidx, x, y, mode, eff, 0.0, 0.0,
                                   int(rng.integers(0, 2 ** 63))))
        else:  # synthetic from a clean single
            cb = _load_codes(it["clean_path"])
            mode = "none" if self.for_val else str(rng.choice(list(_PATCH_MODES)))
            fidx = len(self.frames)
            self.frames.append({"noisy": None, "clean": cb,
                                "h": cb["codes"].shape[0], "w": cb["codes"].shape[1],
                                "pattern": cb["pattern"], "scale": 0.0, "off": 0.0})
            eff = dng_loader.shifted_pattern(cb["pattern"], *_MODE_SHIFT[mode])
            for x, y in _patch_coords(cb["codes"], cb["black4"], cb["white"],
                                      cfg.bayer_patch, cfg.stride,
                                      cfg.saturation_level, cfg.max_saturated_frac):
                shot, read = synthetic.draw_shot_read(rng)
                self.slots.append((fidx, x, y, mode, eff, shot, read,
                                   int(rng.integers(0, 2 ** 63))))

    def __len__(self):
        return len(self.slots)

    def __getitem__(self, i):
        fidx, x, y, mode, eff, shot, read, seed = self.slots[i]
        f = self.frames[fidx]
        p = self.cfg.bayer_patch
        cb = f["clean"]
        clean = dng_loader.normalize_bayer(
            _flip_codes(cb["codes"][y:y + p, x:x + p], mode),
            cb["black4"], cb["white"])
        if f["noisy"] is not None:  # real pair: identical crop+flip of noisy
            nb = f["noisy"]
            noisy = dng_loader.normalize_bayer(
                _flip_codes(nb["codes"][y:y + p, x:x + p], mode),
                nb["black4"], nb["white"])
            sig = dng_loader.estimate_sigma_scalar(noisy, f["scale"], f["off"])
        else:  # synthetic: regenerate noise from the slot seed
            nrng = np.random.default_rng(seed)
            noisy = synthetic.synthesize_noisy(clean, shot, read, nrng)
            sig = math.sqrt(max(shot * float(clean.mean()) + read * read, 1e-12))
        n4 = _pack_canonical(noisy, eff)
        c4 = _pack_canonical(clean, eff)
        h2, w2, _ = n4.shape
        xin = np.concatenate([n4, dng_loader.make_sigma_plane(h2, w2, float(sig))], axis=-1)
        # (5,H2,W2) in, (4,H2,W2) target, CHW float32.
        return (np.ascontiguousarray(np.transpose(xin, (2, 0, 1))),
                np.ascontiguousarray(np.transpose(c4, (2, 0, 1))))
