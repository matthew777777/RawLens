import sys, numpy as np, json, torch
repo, d = sys.argv[1], sys.argv[2]; sys.path.insert(0, repo)
import handheld_super_resolution.alignment as AL
import handheld_super_resolution.block_matching as BM
from handheld_super_resolution.config import Config
from handheld_super_resolution.params import sanitize_config, update_snr_config
from handheld_super_resolution.utils_image import compute_grey_images
cj = json.load(open(f"{d}/cfg.json")); n, h, w = cj["n"], cj["h"], cj["w"]
burst = np.fromfile(f"{d}/burst.f32", '<f4').reshape(n, h, w)
c = Config(); c.verbose = 0; c.scale = 1; update_snr_config(c, cj["snr"]); sanitize_config(c, (h, w))
TARGET = {1: (30, 17), 6: (29, 17)}; cur = [None]
_l2 = AL.align_lvl_block_matching_L2
def l2(tyled, ref_fft, mov, al, l, cfg):
    if l == 1:
        ty, tx = TARGET[cur[0]]; ts = cfg.alignment.tile_sizes[l]; R = cfg.alignment.search_radii[l]
        fx, fy = [int(v) for v in al[ty, tx].round().tolist()]
        ref = tyled[ty, tx].double().numpy()[R:R+ts, R:R+ts]
        sa = BM.extract_flow_patches(mov, al, ts, R)[ty, tx].double().numpy()
        m = mov.double().numpy(); H, W = m.shape
        ssd = np.full((2*R+1, 2*R+1), np.nan)
        for dy in range(-R, R+1):
            for dx in range(-R, R+1):
                ssd[dy+R, dx+R] = ((sa[dy+R:dy+R+ts, dx+R:dx+R+ts] - ref)**2).sum()
        o = np.argsort(np.nan_to_num(ssd, nan=np.inf).ravel())[:3]
        print(f"f{cur[0]} tile{(ty,tx)} start flow {(fx,fy)} lvl {H}x{W}, ref tile shape {None if ref is None else ref.shape}")
        for k in o: print(f"   shift dx={k%(2*R+1)-R:+d} dy={k//(2*R+1)-R:+d}  float64 SSD={ssd.ravel()[k]:.6f}")
        print(f"   tile rows {ty*ts+fy-R}..{ty*ts+fy+R+ts} vs level height {H}")
    return _l2(tyled, ref_fft, mov, al, l, cfg)
AL.align_lvl_block_matching_L2 = l2
init = AL.init_alignment(compute_grey_images(burst[0], "FFT"), c)
for fi in (1, 6):
    cur[0] = fi; AL.align(*init, compute_grey_images(burst[fi], "FFT"), c)
