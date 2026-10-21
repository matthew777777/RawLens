"""S.3 (Wronski 2019 supplement) high-frequency artefact rejection prototype.

R is set to 0 where the reference shows variance loss under the robustness
low-pass AND the alignment field varies locally (Eq. 7-8 motion test), before
the 5x5 minimum (applied here as dilate5 on the final R, which is identical).
  before = sum_c var3x3(guide_c)           (ref_local_var)
  after  = sum_c var3x3(mean3x3(guide_c))  (variance of ref_local_means)
  rho    = after / before ; hf = rho < RHO and before > K * noise_var(sqrt domain)
"""
import sys
sys.dont_write_bytecode = True
sys.path.insert(0, "/Users/monikamalinowska/Handheld-Multi-Frame-Super-Resolution")
import numpy as np
from scipy.ndimage import uniform_filter, maximum_filter
from handheld_super_resolution import super_resolution as sr
from handheld_super_resolution.config import Config
from handheld_super_resolution.params import runtime_config, sanitize_config, update_snr_config

STATE = {}

def var3(a):  # clamped 3x3 variance per channel (mode='nearest' == clamped window)
    m = uniform_filter(a, size=(1, 3, 3), mode='nearest')
    return uniform_filter(a * a, size=(1, 3, 3), mode='nearest') - m * m

def hf_map(means, var, alpha, beta, rho_th, k):
    before = var.sum(0)
    after = var3(means).sum(0)
    lin = np.maximum(means, 1e-6) ** 2                     # guide is sqrt domain
    a = np.array([alpha[0], alpha[1] / 2, alpha[2]])[:, None, None]   # G is a 2-sample mean
    b = np.array([beta[0], beta[1] / 2, beta[2]])[:, None, None]
    noise = ((a * lin + b) / (4 * lin)).sum(0)            # delta-method var of sqrt(x)
    rho = after / np.maximum(before, 1e-12)
    return (rho < rho_th) & (before > k * noise), rho, before / np.maximum(noise, 1e-12)

def install(rho_th, k, alpha, beta):
    orig_init, orig_rob = sr.init_robustness, sr.compute_robustness
    def init(ref_img, config):
        means, var = orig_init(ref_img, config)
        hf, rho, snrv = hf_map(np.asarray(means), np.asarray(var), alpha, beta, rho_th, k)
        STATE.update(hf=hf, rho=rho, snrv=snrv, rejected=[])
        return means, var
    def rob(comp_img, ref_local_means, ref_local_var, flows, noise_model, config, debug_writer=None):
        R = np.array(orig_rob(comp_img, ref_local_means, ref_local_var, flows, noise_model, config, debug_writer))
        f = np.asarray(flows)                                  # (nTy, nTx, 2) in raw px
        mx = maximum_filter(f, size=(3, 3, 1), mode='constant', cval=-np.inf)
        mn = -maximum_filter(-f, size=(3, 3, 1), mode='constant', cval=-np.inf)
        d = mx - mn
        motion = (d[..., 0] ** 2 + d[..., 1] ** 2) > config.robustness.Mt ** 2
        th = config.alignment.tile_size // 2                   # guide px per tile
        mg = np.repeat(np.repeat(motion, th, 0), th, 1)[:R.shape[0], :R.shape[1]]
        mask = STATE['hf'][:R.shape[0], :R.shape[1]] & mg
        kill = maximum_filter(mask.astype(np.uint8), size=5, mode='nearest').astype(bool)
        STATE['rejected'].append(float(kill.mean()))
        R[kill] = 0
        return R
    sr.init_robustness, sr.compute_robustness = init, rob
    return lambda: (setattr(sr, 'init_robustness', orig_init), setattr(sr, 'compute_robustness', orig_rob))

def merge(ref, comp, alpha, beta, snr, s3=None):
    cfg = Config(); cfg.robustness.noise_correction = False
    cfg = runtime_config(cfg)
    cfg.noise_model.alpha, cfg.noise_model.beta = list(alpha), list(beta)
    update_snr_config(cfg, snr)
    sanitize_config(cfg, ref.shape)
    cfg.noise_model.sigma_sq_curve = [0.0, 0.0]; cfg.noise_model.d_sq_curve = [0.0, 0.0]
    undo = install(*s3, alpha, beta) if s3 else None
    try:
        out, _ = sr.main(ref.astype(np.float32), comp.astype(np.float32), cfg)
    finally:
        if undo: undo()
    return np.nan_to_num(out)
