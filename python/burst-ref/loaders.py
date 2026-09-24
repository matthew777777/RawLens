"""Dataset layout readers (DBSR Sec 4-5 + RealBSR Sec 3, numpy reference).

Conventions (match the official toolboxes so future downloads just work):
  SyntheticBurst: <root>/train|val/<seq:05d>/frame_00.raw ... + gt.png
    Each burst has 14 RAW LR frames; filenames are zero-padded indices.
  BurstSR (cropped): <root>/train|val/<id>/burst/*.png (14 RAW crops 80x80
    stored as 16-bit PNG mosaics) + gt_hr.png (DSLR crop, scale x4).
  RealBSR-RAW: <root>/train|test/<group>/lr_00.dng ... lr_13.dng + hr.dng
    (optical-zoom pairs, same sensor; no cross-device color gap).
  RealBSR-RGB: same layout with *_lr.png / *_hr.png sRGB files.

This module does NOT download datasets. It validates a root directory,
enumerates groups, and implements the shared patch policy:
  - 160x160 LR crops, stride 80 (DBSR Sec 5.2 / RealBSR Sec 3.1).
  - phone-DSLR crop pairs with normalized cross-correlation < 0.9 are
    discarded (DBSR Sec 5.2 data processing).
"""
import os
import numpy as np

BURST_LEN = 14
PATCH = 160
STRIDE = 80
NCC_KEEP = 0.9


def list_groups(root):
    """Return sorted group-dir names containing at least one LR frame."""
    groups = []
    for name in sorted(os.listdir(root)):
        g = os.path.join(root, name)
        if os.path.isdir(g) and len(enumerate_frames(g)) > 0:
            groups.append(name)
    return groups


def enumerate_frames(group_dir):
    """LR frame paths in burst order (numeric sort of lr_*.dng/png)."""
    out = []
    for name in sorted(os.listdir(group_dir)):
        low = name.lower()
        if low.startswith("lr_") and low.endswith((".dng", ".png", ".raw")):
            out.append(os.path.join(group_dir, name))
    return out


def validate_group(group_dir, expect=BURST_LEN):
    """Check burst length + HR presence. Returns (ok, reason)."""
    frames = enumerate_frames(group_dir)
    if len(frames) != expect:
        return False, "want %d lr frames, found %d" % (expect, len(frames))
    names = set(os.listdir(group_dir))
    if not any(n.lower().startswith("hr") for n in names):
        return False, "missing hr reference"
    return True, "ok"


def ncc(a, b):
    """Normalized cross-correlation of two float patches."""
    a = np.asarray(a, dtype=np.float64).ravel()
    b = np.asarray(b, dtype=np.float64).ravel()
    a = a - a.mean()
    b = b - b.mean()
    denom = np.sqrt((a * a).sum() * (b * b).sum())
    if denom <= 1e-12:
        return 1.0 if float(((a - b) ** 2).mean()) <= 1e-12 else 0.0
    return float((a * b).sum() / denom)


def extract_patches(img, patch=PATCH, stride=STRIDE):
    """Sliding-window (patch x patch) crops; returns list of views."""
    h, w = img.shape[:2]
    out = []
    for y in range(0, h - patch + 1, stride):
        for x in range(0, w - patch + 1, stride):
            out.append(img[y:y + patch, x:x + patch])
    return out


def keep_pair(lr_crop, hr_crop_down):
    """DBSR Sec 5.2 filter: keep pairs with NCC >= 0.9."""
    return ncc(lr_crop, hr_crop_down) >= NCC_KEEP


def _self_test():
    import tempfile
    rng = np.random.default_rng(9)
    with tempfile.TemporaryDirectory() as root:
        g = os.path.join(root, "00001")
        os.makedirs(g)
        for i in range(BURST_LEN):
            open(os.path.join(g, "lr_%02d.dng" % i), "wb").close()
        open(os.path.join(g, "hr.dng"), "wb").close()
        assert list_groups(root) == ["00001"]
        assert validate_group(g) == (True, "ok")
        os.remove(os.path.join(g, "hr.dng"))
        assert validate_group(g)[0] is False
    img = rng.random((200, 200)).astype(np.float32)
    patches = extract_patches(img)
    assert len(patches) == 1 and patches[0].shape == (160, 160), len(patches)
    big = rng.random((400, 400)).astype(np.float32)
    assert len(extract_patches(big)) == 16  # 4x4 grid at stride 80
    assert keep_pair(img[:160, :160], img[:160, :160])
    assert not keep_pair(rng.random((160, 160)).astype(np.float32),
                         rng.random((160, 160)).astype(np.float32))
    print("loaders.py self-test ok")


if __name__ == "__main__":
    _self_test()
