"""C9: export a dim real-burst crop + the reference's effective config + its output.
python c9_export.py <repo> <burst_dir> <out_dir> x y size"""
import sys, json, os
repo, burst, out, x0, y0, S = sys.argv[1], sys.argv[2], sys.argv[3], *map(int, sys.argv[4:7])
sys.path.insert(0, repo)
if os.environ.get("NUMBA_ENABLE_CUDASIM") == "1":
    import simshim  # noqa
import numpy as np
from handheld_super_resolution.config import Config
from handheld_super_resolution.params import sanitize_config, update_snr_config
from handheld_super_resolution.super_resolution import main
from handheld_super_resolution.utils_dng import load_dng_burst
from handheld_super_resolution.utils_image import estimate_image_snr
st = load_dng_burst(burst)
ref, comp = st.get_raw_arrays()
ref = np.ascontiguousarray(ref[y0:y0+S, x0:x0+S]).astype(np.float32)
comp = np.ascontiguousarray(comp[:, y0:y0+S, x0:x0+S]).astype(np.float32)
c = Config(); c.verbose = 0; c.scale = 1
c.noise_model.alpha, c.noise_model.beta = st.alpha, st.beta
c.robustness.noise_correction = False
c.noise_model.sigma_sq_curve = [0.0, 0.0]; c.noise_model.d_sq_curve = [0.0, 0.0]
snr = estimate_image_snr(ref, c.noise_model.alpha, c.noise_model.beta)
update_snr_config(c, snr); sanitize_config(c, ref.shape)
import handheld_super_resolution.super_resolution as SR
CAP = {"flow": [], "rob": [], "cov": []}
_al, _rb = SR.align, SR.compute_robustness
def _align(*a, **k):
    f = _al(*a, **k); CAP["flow"].append(np.array(f, np.float32)); return f
def _rob(*a, **k):
    r = _rb(*a, **k); CAP["rob"].append(np.array(r, np.float32)); return r
_ek = SR.estimate_kernels
def _ek2(*a, **k):
    r = _ek(*a, **k); CAP["cov"].append(np.array(r, np.float32)); return r
SR.align, SR.compute_robustness, SR.estimate_kernels = _align, _rob, _ek2
img, dbg = main(ref, comp, c)
os.makedirs(out, exist_ok=True)
np.stack([ref, *comp]).astype('<f4').tofile(f"{out}/burst.f32")
np.asarray(img, '<f4').tofile(f"{out}/out_ref.f32")
np.stack(CAP["flow"]).astype('<f4').tofile(f"{out}/flows_ref.f32"); np.stack(CAP["rob"]).astype('<f4').tofile(f"{out}/rob_ref.f32"); np.stack(CAP["cov"]).astype('<f4').tofile(f"{out}/cov_ref.f32")
print("flows", np.stack(CAP["flow"]).shape, "rob", np.stack(CAP["rob"]).shape)
a, m, r, k = c.alignment, c.merging, c.robustness, c.merging.kernel
cfg = dict(n=1+len(comp), h=S, w=S, scale=c.scale, snr=float(snr), mean=float(ref.mean()),
  alpha=[float(v) for v in c.noise_model.alpha], beta=[float(v) for v in c.noise_model.beta],
  factors=list(a.factors), tile_sizes=list(a.tile_sizes), search_radii=list(a.search_radii),
  metrics=list(a.block_matching.metrics), grey_method=a.grey_method, preproc_align=a.preprocessing,
  ica_iter=a.ica.n_iter, ica_clip=a.ica.clip, flow_up=a.flow_upscale_mode,
  merge_pre=m.preprocessing, k_detail=k.k_detail, k_denoise=k.k_denoise, D_th=k.D_th, D_tr=k.D_tr,
  k_stretch=k.k_stretch, k_shrink=k.k_shrink, law=m.selection_law, iso=(m.kernel_type == "iso"),
  t=r.t, s1=r.s1, s2=r.s2, Mt=r.Mt, rob=r.enabled)
json.dump(cfg, open(f"{out}/cfg.json", "w"), indent=1, default=str); print(json.dumps(cfg, default=str))
