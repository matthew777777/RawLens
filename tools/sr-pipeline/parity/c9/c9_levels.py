"""Per-level flows (after block matching, after ICA) of the reference for frames 1 and 6."""
import sys, os, numpy as np
repo, d = sys.argv[1], sys.argv[2]; sys.path.insert(0, repo)
import json, torch
import handheld_super_resolution.alignment as AL
from handheld_super_resolution.config import Config
from handheld_super_resolution.params import sanitize_config, update_snr_config
cfgj = json.load(open(f"{d}/cfg.json")); n, h, w = cfgj["n"], cfgj["h"], cfgj["w"]
burst = np.fromfile(f"{d}/burst.f32", '<f4').reshape(n, h, w)
c = Config(); c.verbose = 0; c.scale = 1
c.noise_model.alpha, c.noise_model.beta = cfgj["alpha"], cfgj["beta"]
update_snr_config(c, cfgj["snr"]); sanitize_config(c, (h, w))
from handheld_super_resolution.utils_image import compute_grey_images
CAP = []
_bm1, _bm2, _ica = AL.align_lvl_block_matching_L1, AL.align_lvl_block_matching_L2, AL.align_lvl_ica
def wrap(fn, tag):
    def f(*a, **k):
        al = a[-3] if tag != "ica" else a[-3]
        r = fn(*a, **k); CAP.append((tag, a[-2] if tag != "ica" else a[-2], al.clone().numpy())); return r
    return f
AL.align_lvl_block_matching_L1 = wrap(_bm1, "L1"); AL.align_lvl_block_matching_L2 = wrap(_bm2, "L2"); AL.align_lvl_ica = wrap(_ica, "ica")
refg = compute_grey_images(burst[0], "FFT")
init = AL.init_alignment(refg, c)
for fi in (1, 6):
    CAP.clear()
    AL.align(*init, compute_grey_images(burst[fi], "FFT"), c)
    for i, (tag, lvl, arr) in enumerate(CAP):
        arr.astype('<f4').tofile(f"{d}/lvl_f{fi}_{i}_{tag}_l{lvl}.f32")
    print(fi, [(t, l, a.shape) for t, l, a in CAP])
