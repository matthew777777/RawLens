"""Deterministic parity fixtures for the RawLens sr-pipeline Kotlin port.

READ-ONLY with respect to the Python reference source: this script only
*imports* from /Users/monikamalinowska/Handheld-Multi-Frame-Super-Resolution
(via sys.path) and never writes outside this parity/ directory. Bytecode
writing is disabled so even __pycache__ is left untouched.

Run:
    /Users/monikamalinowska/Handheld-Multi-Frame-Super-Resolution/.venv/bin/python gen_fixtures.py

Outputs (all under parity/fixtures/):
  - <stage>_in*.npy / <stage>_out*.npy for EVERY cartography stage, plus one
    256px end-to-end pair (burst in, merged image out).
  - manifest.json (shapes, dtypes, seeds, source function citations).
  - ../envelopes.json (numeric bounds copied verbatim from the Vulkan parity
    report, with a source note).

Determinism: fixed SEED = 0 everywhere (np.random.default_rng(0) plus closed
form sin/cos patterns). No unseeded randomness.
"""

import sys

sys.dont_write_bytecode = True
sys.path.insert(0, "/Users/monikamalinowska/Handheld-Multi-Frame-Super-Resolution")

import json
from pathlib import Path

import numpy as np
import torch

HERE = Path(__file__).resolve().parent
FIX = HERE / "fixtures"
REPORT_PATH = Path(
    "/Users/monikamalinowska/Handheld-Multi-Frame-Super-Resolution-Vulkan"
    "/parity/parity_gpu_report.json"
)
SEED = 0
ALPHA = (1e-4, 1e-4, 1e-4, 1e-4)
BETA = (1e-6, 1e-6, 1e-6, 1e-6)

MANIFEST = {"seed": SEED, "stages": {}}


def save(stage, role, arr, note=""):
    arr = np.ascontiguousarray(arr)
    name = f"{stage}_{role}.npy"
    np.save(FIX / name, arr)
    MANIFEST["stages"].setdefault(stage, {"files": {}, "note": note})
    MANIFEST["stages"][stage]["files"][role] = {
        "file": name,
        "shape": list(arr.shape),
        "dtype": str(arr.dtype),
    }
    return arr


def bayer_pattern(h, w):
    """Smooth deterministic Bayer-like image in [0, 1], float32."""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    base = (np.sin(xx / 7.0) * np.cos(yy / 9.0) * 0.5 + 0.5).astype(np.float32)
    base[0::2, 0::2] *= 0.8  # R
    base[1::2, 1::2] *= 0.6  # B
    return np.clip(base, 0, 1)


def burst(h, w, n=3):
    rng = np.random.default_rng(SEED)
    base = bayer_pattern(h, w)
    frames = []
    for i in range(n):
        shifted = np.roll(base, shift=(i, -i), axis=(0, 1))
        noisy = np.clip(
            shifted + rng.normal(0, 0.005, size=shifted.shape), 0, 1
        ).astype(np.float32)
        frames.append(noisy)
    return np.stack(frames)


def small_config(size, factors, tile=16, scale=2, grey_method="FFT"):
    """Config with explicit pyramid factors valid for `size` (no SNR lookup)."""
    from handheld_super_resolution.config import Config
    from handheld_super_resolution.params import sanitize_config

    c = Config()
    c.verbose = 0
    c.scale = scale
    c.alignment.grey_method = grey_method
    c.alignment.tile_size = tile
    c.alignment.factors = list(factors)
    n = len(factors)
    c.alignment.tile_sizes = [tile] * n
    c.alignment.search_radii = ([1] + [4] * max(0, n - 1))[:n]
    c.alignment.block_matching.metrics = (["L1"] + ["L2"] * (n - 1))[:n]
    c.robustness.noise_correction = False
    c.noise_model.alpha = ALPHA
    c.noise_model.beta = BETA
    c.noise_model.sigma_sq_curve = [0.0, 0.0]
    c.noise_model.d_sq_curve = [0.0, 0.0]
    c.merging.kernel.k_detail = 0.3
    c.merging.kernel.k_denoise = 4.0
    c.merging.kernel.D_th = 0.75
    c.merging.kernel.D_tr = 1.1
    sanitize_config(c, (size, size))
    return c


def e2e_config():
    """256px end-to-end config; explicit pyramid valid for 256px FFT grey."""
    return small_config(256, [1, 2, 4], scale=2)


def stage_gat():
    # handheld_super_resolution/utils_image.py:143 gat
    from handheld_super_resolution.utils_image import gat

    img = bayer_pattern(32, 32)
    save("gat", "in_raw", img)
    out = gat(img, ALPHA, BETA)
    save("gat", "out_vst", out)
    MANIFEST["stages"]["gat"]["note"] = "gat(raw[32,32]) -> vst[32,32]"


def stage_decimate():
    # handheld_super_resolution/utils_image.py:61 compute_grey_images
    from handheld_super_resolution.utils_image import compute_grey_images

    img = bayer_pattern(32, 32)
    save("decimate", "in_raw", img)
    out = compute_grey_images(img, "decimating")
    save("decimate", "out_grey", out)
    MANIFEST["stages"]["decimate"]["note"] = "decimating raw[32,32] -> grey[16,16]"


def stage_grey_fft():
    # handheld_super_resolution/utils_image.py:61 compute_grey_images (FFT)
    from handheld_super_resolution.utils_image import compute_grey_images

    img = bayer_pattern(32, 32)
    save("grey_fft", "in_raw", img)
    out = compute_grey_images(img, "FFT")
    save("grey_fft", "out_lowpass", np.asarray(out, dtype=np.float32))
    MANIFEST["stages"]["grey_fft"]["note"] = "FFT lowpass raw[32,32] -> grey[32,32]"


BURST_DIR = "/Users/monikamalinowska/Downloads/Photos/Photos/IMG_20260927_134544_522"


def burst_crop(crop=512, frame=0):
    """Real-burst center crop (the parity burst, same recipe as the Vulkan
    parity scripts): ((h-crop)//2 & ~1, (w-crop)//2 & ~1)."""
    from handheld_super_resolution.utils_dng import load_dng_burst
    stack = load_dng_burst(BURST_DIR)
    ref_raw, raw_comp = stack.get_raw_arrays()
    h, w = ref_raw.shape
    y0, x0 = (h - crop) // 2 & ~1, (w - crop) // 2 & ~1
    ref_c = np.ascontiguousarray(ref_raw[y0:y0 + crop, x0:x0 + crop]).astype(np.float32)
    mov_c = np.ascontiguousarray(raw_comp[frame, y0:y0 + crop, x0:x0 + crop]).astype(np.float32)
    return stack, ref_c, mov_c


def stage_grey_fft_crop():
    # Crop-parity twin of stage_grey_fft on the real burst (512 center
    # crop): the synthetic 32px fixture sits below the float32 FFT noise
    # floor that the real-data envelope was calibrated on (the Vulkan GPU
    # port itself measures maxabs 1.79e-07 there vs envelope 2.6e-08).
    from handheld_super_resolution.utils_image import compute_grey_images
    _, _, mov_c = burst_crop(512)
    save("grey_fft_crop", "in_raw", mov_c)
    out = compute_grey_images(mov_c, "FFT")
    save("grey_fft_crop", "out_lowpass", np.asarray(out, dtype=np.float32))
    MANIFEST["stages"]["grey_fft_crop"]["note"] = (
        "FFT lowpass burst512-crop -> grey[512,512] (crop parity)"
    )


def stage_demosaic():
    # handheld_super_resolution/utils_image.py:121 demosaic_to_grey
    from handheld_super_resolution.utils_image import demosaic_to_grey

    img = bayer_pattern(32, 32)
    save("demosaic", "in_cfa", img)
    out = demosaic_to_grey(img)
    save("demosaic", "out_grey", np.asarray(out, dtype=np.float32))
    MANIFEST["stages"]["demosaic"]["note"] = "Menon2007 RGGB cfa[32,32] -> grey[32,32]"


def stage_divide():
    # handheld_super_resolution/utils.py:66 divide
    from handheld_super_resolution.utils import divide

    rng = np.random.default_rng(SEED)
    num = (rng.random((16, 16, 3), dtype=np.float32) + 0.5).astype(np.float32)
    den = (rng.random((16, 16, 3), dtype=np.float32) + 0.5).astype(np.float32)
    save("divide", "in_num", num)
    save("divide", "in_den", den)
    divide(num, den)
    save("divide", "out_ratio", num)
    MANIFEST["stages"]["divide"]["note"] = "num/den [16,16,3] in place"


def stage_smap():
    # handheld_super_resolution/robustness.py:402 compute_s
    from handheld_super_resolution.robustness import compute_s

    rng = np.random.default_rng(SEED)
    flows = (rng.random((4, 4, 2), dtype=np.float32) * 2 - 1).astype(np.float32)
    flows[2, 2] = (5.0, -5.0)  # one deliberate discontinuity
    save("smap", "in_flow", flows)
    out = compute_s(flows, 0.8, 2.0, 12.0)
    save("smap", "out_s", out)
    MANIFEST["stages"]["smap"]["note"] = "compute_s(flow[4,4,2]) -> S[4,4]"


def stage_hessian():
    # handheld_super_resolution/ICA.py:18 init_ica
    from handheld_super_resolution.ICA import init_ica

    cfg = small_config(64, [1, 2])
    grey = torch.as_tensor(bayer_pattern(64, 64), dtype=torch.float32)
    save("hessian", "in_grey", grey.numpy())
    gradx, grady, hessian = init_ica(grey, 16, cfg)
    save("hessian", "out_gradx", gradx)
    save("hessian", "out_grady", grady)
    save("hessian", "out_hessian", hessian)
    MANIFEST["stages"]["hessian"]["note"] = "init_ica(grey[64,64],T16) -> grads + hessian[4,4,2,2]"


def stage_grad():
    # handheld_super_resolution/kernels.py:100-123 kerngrad convs (inlined,
    # same ops), ICA.py Sobel grads via init_ica, utils_image.py:135
    # preprocess sqrt/gamma.
    import torch.nn.functional as F
    from handheld_super_resolution.ICA import init_ica
    from handheld_super_resolution.utils_image import preprocess

    cfg = small_config(64, [1, 2])
    grey = bayer_pattern(64, 64)
    save("grad", "in_grey", grey)
    th_grey = torch.as_tensor(grey, dtype=torch.float32)[None, None]
    k1 = torch.as_tensor(
        np.array([[[[-0.5, 0.5]]], [[[0.5, 0.5]]]]), dtype=torch.float32
    )
    k2 = torch.as_tensor(
        np.array([[[[0.5], [0.5]]], [[[-0.5], [0.5]]]]), dtype=torch.float32
    )
    full_grads = np.ascontiguousarray(
        F.conv2d(F.conv2d(th_grey, k1), k2, groups=2)
        .squeeze()
        .transpose(0, 1)
        .transpose(1, 2)
        .numpy()
    )
    save("grad", "out_kerngrad", full_grads)
    gx, gy, _ = init_ica(torch.as_tensor(grey, dtype=torch.float32), 16, cfg)
    save("grad", "out_sobel_gx", gx)
    save("grad", "out_sobel_gy", gy)
    th = torch.as_tensor(grey, dtype=torch.float32)
    save("grad", "out_sqrt", preprocess(th, "sqrt").numpy())
    save("grad", "out_gamma", preprocess(th, "gamma").numpy())
    MANIFEST["stages"]["grad"]["note"] = "kerngrad + sobel + sqrt/gamma of grey[64,64]"


def stage_resample():
    # handheld_super_resolution/utils_image.py:220 downsample,
    # alignment.py:89 build_gaussian_pyramid, alignment.py:185 upscale_lvl
    from handheld_super_resolution.alignment import (
        build_gaussian_pyramid,
        upscale_lvl,
    )
    from handheld_super_resolution.utils_image import downsample

    cfg = small_config(64, [1, 2])
    grey = bayer_pattern(64, 64)
    save("resample", "in_grey", grey)
    th_img = torch.as_tensor(grey, dtype=torch.float32)[None, None]
    d2 = downsample(th_img, "gaussian", 2).squeeze().numpy()
    save("resample", "out_down2", np.asarray(d2, dtype=np.float32))
    pyr = build_gaussian_pyramid(th_img, [1, 2, 2])
    pyr = [np.asarray(l.squeeze().numpy(), dtype=np.float32) for l in pyr]
    for i, lvl in enumerate(pyr):
        save("resample", f"out_pyr{i}", lvl)
    flow = torch.zeros((1, 1, 2), dtype=torch.float32)
    up = upscale_lvl(flow, [2, 2], 0, cfg)
    save("resample", "out_upscaled_flow", up.numpy())
    MANIFEST["stages"]["resample"]["note"] = "gauss down2 + pyramid[1,2,2] + bilinup flow"


def stage_kernels():
    # handheld_super_resolution/kernels.py:30 estimate_kernels
    from handheld_super_resolution.kernels import estimate_kernels

    cfg = small_config(64, [1, 2])
    img = bayer_pattern(64, 64)
    save("kernels", "in_raw", img)
    covs = estimate_kernels(img, cfg)
    save("kernels", "out_covs", covs)
    MANIFEST["stages"]["kernels"]["note"] = "estimate_kernels(raw[64,64]) -> covs[32,32,2,2]"


def _align_pair():
    """128px reference/moving grey pair + config (shared by align/l2/ica).

    FFT grey keeps full 128px; pyramid [1,2,2] -> coarse-to-fine [26,60,128].
    """
    from handheld_super_resolution.utils_image import compute_grey_images

    cfg = small_config(128, [1, 2, 2])
    ref_raw = bayer_pattern(128, 128)
    mov_raw = np.roll(ref_raw, shift=(3, -2), axis=(0, 1))
    ref_grey = compute_grey_images(np.ascontiguousarray(ref_raw), "FFT")
    mov_grey = compute_grey_images(np.ascontiguousarray(mov_raw), "FFT")
    return cfg, ref_grey, mov_grey


def stage_align():
    # handheld_super_resolution/alignment.py:114 align (L1+L2+ICA loop)
    from handheld_super_resolution.alignment import align, init_alignment

    cfg, ref_grey, mov_grey = _align_pair()
    save("align", "in_ref_grey", ref_grey)
    save("align", "in_mov_grey", mov_grey)
    ref_struct = init_alignment(ref_grey, cfg)
    flow = align(*ref_struct, np.ascontiguousarray(mov_grey), cfg)
    save("align", "out_flow", flow)
    MANIFEST["stages"]["align"]["note"] = "align() 128px raw pair (FFT grey) -> flow[8,8,2]"


def stage_l2():
    # handheld_super_resolution/block_matching.py:19 align_lvl_block_matching_L2
    from handheld_super_resolution.alignment import init_alignment
    from handheld_super_resolution.block_matching import (
        align_lvl_block_matching_L2,
    )

    cfg, ref_grey, mov_grey = _align_pair()
    ref_pyr, tiled_pyr, ref_fft, _, _, _ = init_alignment(ref_grey, cfg)
    lvl = 0  # coarsest (list index 0); fine-to-coarse config index below
    ci = len(cfg.alignment.factors) - 1
    moving_lvl = torch.as_tensor(
        np.ascontiguousarray(
            np.roll(ref_pyr[lvl].numpy(), shift=(2, -1), axis=(0, 1))
        ),
        dtype=torch.float32,
    )
    alignment = torch.zeros(
        (*ref_fft[lvl].shape[:2], 2), dtype=torch.float32
    )
    save("l2", "in_ref_lvl", ref_pyr[lvl].numpy())
    save("l2", "in_moving_lvl", moving_lvl.numpy())
    save("l2", "in_flow_init", alignment.numpy())
    align_lvl_block_matching_L2(
        tiled_pyr[lvl], ref_fft[lvl], moving_lvl, alignment, ci, cfg
    )
    save("l2", "out_flow", alignment.numpy())
    MANIFEST["stages"]["l2"]["note"] = "L2 corr on coarsest lvl -> flow tiles"


def stage_ica():
    # handheld_super_resolution/ICA.py:67 align_lvl_ica
    from handheld_super_resolution.alignment import init_alignment
    from handheld_super_resolution.ICA import align_lvl_ica

    cfg, ref_grey, mov_grey = _align_pair()
    ref_pyr, _, _, ref_gx, ref_gy, ref_h = init_alignment(ref_grey, cfg)
    lvl = 0
    moving_lvl = torch.as_tensor(
        np.ascontiguousarray(
            np.roll(ref_pyr[lvl].numpy(), shift=(1, 1), axis=(0, 1))
        ),
        dtype=torch.float32,
    )
    alignment = torch.zeros((1, 1, 2), dtype=torch.float32)
    save("ica", "in_ref_lvl", ref_pyr[lvl].numpy())
    save("ica", "in_mov_lvl", moving_lvl.numpy())
    save("ica", "in_flow_init", alignment.numpy())
    align_lvl_ica(
        ref_pyr[lvl], ref_gx[lvl], ref_gy[lvl], ref_h[lvl],
        moving_lvl, alignment, len(cfg.alignment.factors) - 1, cfg,
    )
    save("ica", "out_flow", alignment.numpy())
    MANIFEST["stages"]["ica"]["note"] = "align_lvl_ica on coarsest lvl -> flow[1,1,2]"


def stage_robustness():
    # handheld_super_resolution/robustness.py:27 init_robustness,
    # :69 compute_robustness
    from handheld_super_resolution.alignment import align, init_alignment
    from handheld_super_resolution.robustness import (
        compute_robustness,
        init_robustness,
    )
    from handheld_super_resolution.utils_image import compute_grey_images

    cfg = small_config(128, [1, 2, 2])
    frames = burst(128, 128, 2)
    save("robustness", "in_ref_raw", frames[0])
    save("robustness", "in_comp_raw", frames[1])
    means, var = init_robustness(np.ascontiguousarray(frames[0]), cfg)
    mov_grey = compute_grey_images(np.ascontiguousarray(frames[1]), "FFT")
    ref_grey = compute_grey_images(np.ascontiguousarray(frames[0]), "FFT")
    flow = align(*init_alignment(ref_grey, cfg), mov_grey, cfg)
    save("robustness", "in_flow", flow)
    r = compute_robustness(
        np.ascontiguousarray(frames[1]), means, var, flow,
        (np.asarray(cfg.noise_model.sigma_sq_curve, dtype=np.float32),
         np.asarray(cfg.noise_model.d_sq_curve, dtype=np.float32)),
        cfg,
    )
    save("robustness", "out_r", r)
    MANIFEST["stages"]["robustness"]["note"] = "compute_robustness(128px pair) -> r[64,64]"
    return cfg, frames, flow, r


def stage_robustness_crop():
    # Crop-parity twin on the real burst (512 center crop, parity_config):
    # the synthetic 128px fixture drives R into near-zero pixels where no
    # float32 implementation holds the real-data rel envelope (Vulkan GPU
    # measures maxrel 0.0113 there vs envelope 2.1e-04; Kotlin 0.0093).
    from handheld_super_resolution.alignment import align, init_alignment
    from handheld_super_resolution.robustness import (
        compute_robustness,
        init_robustness,
    )
    from handheld_super_resolution.utils_image import compute_grey_images
    from parity.parity_check import parity_config
    import sys
    sys.path.insert(0, "/Users/monikamalinowska/Handheld-Multi-Frame-Super-Resolution-Vulkan")
    stack, ref_c, mov_c = burst_crop(512)
    config = parity_config(512)
    rg = compute_grey_images(ref_c, config.alignment.grey_method)
    mg = compute_grey_images(mov_c, config.alignment.grey_method)
    rp, tiled, tfft, gx, gy, hess = init_alignment(rg, config, None)
    flow = align(rp, tiled, tfft, gx, gy, hess, mg, config, None)
    mc, sc = init_robustness(ref_c, config)
    sig = (np.asarray(config.noise_model.sigma_sq_curve, np.float32),
           np.asarray(config.noise_model.d_sq_curve, np.float32))
    save("robustness_crop", "in_comp", mov_c)
    save("robustness_crop", "in_means", mc)
    save("robustness_crop", "in_var", sc)
    save("robustness_crop", "in_flow", np.asarray(flow, dtype=np.float32))
    save("robustness_crop", "in_sig", sig[0])
    save("robustness_crop", "in_d", sig[1])
    r = compute_robustness(mov_c, mc, sc, flow, sig, config, None)
    save("robustness_crop", "out_r", r)
    MANIFEST["stages"]["robustness_crop"]["note"] = (
        "compute_robustness(burst512-crop) -> r (crop parity)"
    )


def stage_align_crop():
    # Crop-parity twin of stage_align on the real burst (512 center crop,
    # parity_config): gates the full coarse-to-fine align() loop (moving
    # pyramid, per-level L1/L2 + ICA, flow upscaling) at real-data tile
    # counts. The per-level loop below replays align() orchestration
    # (coarse-to-fine, list_id = len - l - 1, zero-init then upscale_lvl)
    # through the real align_lvl; its finest level must equal align()
    # bit-exactly (asserted).
    import sys
    import torch
    from handheld_super_resolution.alignment import (
        align,
        align_lvl,
        build_gaussian_pyramid,
        init_alignment,
        upscale_lvl,
    )
    from handheld_super_resolution.utils_image import compute_grey_images
    sys.path.insert(0, "/Users/monikamalinowska/Handheld-Multi-Frame-Super-Resolution-Vulkan")
    from parity.parity_check import parity_config
    stack, ref_c, mov_c = burst_crop(512)
    config = parity_config(512)
    rg = compute_grey_images(ref_c, config.alignment.grey_method)
    mg = compute_grey_images(mov_c, config.alignment.grey_method)
    save("align_crop", "in_ref_grey", rg)
    save("align_crop", "in_mov_grey", mg)
    ref_struct = init_alignment(rg, config, None)
    ref_pyr, tiled_pyr, ref_fft, ref_gx, ref_gy, ref_h = ref_struct
    th_mov = torch.as_tensor(np.ascontiguousarray(mg), dtype=torch.float32)
    moving_pyr = build_gaussian_pyramid(th_mov[None, None], config.alignment.factors)
    levels = []
    alignments = None
    with torch.no_grad():
        for l, (rl, tp, rf, gx, gy, h, ml) in enumerate(
            zip(ref_pyr, tiled_pyr, ref_fft, ref_gx, ref_gy, ref_h, moving_pyr)
        ):
            list_id = len(ref_pyr) - l - 1
            if alignments is None:
                alignments = torch.zeros(
                    (*rf.shape[:2], 2), dtype=torch.float32
                )
            else:
                alignments = upscale_lvl(alignments, rf.shape[:2], list_id, config)
            align_lvl(rl, tp, rf, gx, gy, h, ml, alignments, l=list_id, config=config)
            levels.append(np.ascontiguousarray(alignments.detach().numpy()))
    flow = align(*ref_struct, np.ascontiguousarray(mg), config, None)
    assert np.array_equal(levels[-1], flow), "inline loop diverged from align()"
    save("align_crop", "out_flow", flow)
    for i, lvl in enumerate(levels):
        save("align_crop", f"out_lvl{i}", lvl)
    a = config.alignment
    MANIFEST["stages"]["align_crop"]["note"] = (
        "align() burst512-crop -> flow[32,32,2] + per-level flows "
        f"(factors={list(a.factors)} tile_sizes={list(a.tile_sizes)} "
        f"radii={list(a.search_radii)} metrics={list(a.block_matching.metrics)} "
        f"grey={a.grey_method} ica_n={a.ica.n_iter} clip={a.ica.clip} "
        f"upscale={a.flow_upscale_mode} preprocessing={a.preprocessing})"
    )


def stage_merge():
    # handheld_super_resolution/merge.py:23 merge
    from handheld_super_resolution.alignment import align, init_alignment
    from handheld_super_resolution.kernels import estimate_kernels
    from handheld_super_resolution.merge import merge
    from handheld_super_resolution.robustness import (
        compute_robustness,
        init_robustness,
    )
    from handheld_super_resolution.utils_image import compute_grey_images

    cfg = small_config(128, [1, 2, 2], scale=2)
    frames = burst(128, 128, 2)
    save("merge", "in_comp_raw", frames[1])
    means, var = init_robustness(np.ascontiguousarray(frames[0]), cfg)
    ref_grey = compute_grey_images(np.ascontiguousarray(frames[0]), "FFT")
    mov_grey = compute_grey_images(np.ascontiguousarray(frames[1]), "FFT")
    flow = align(*init_alignment(ref_grey, cfg), mov_grey, cfg)
    save("merge", "in_flow", flow)
    covs = estimate_kernels(np.ascontiguousarray(frames[1]), cfg)
    save("merge", "in_covs", covs)
    r = compute_robustness(
        np.ascontiguousarray(frames[1]), means, var, flow,
        (np.asarray(cfg.noise_model.sigma_sq_curve, dtype=np.float32),
         np.asarray(cfg.noise_model.d_sq_curve, dtype=np.float32)),
        cfg,
    )
    save("merge", "in_r", r)
    num = np.zeros((256, 256, 3), np.float32)
    den = np.zeros((256, 256, 3), np.float32)
    merge(np.ascontiguousarray(frames[1]), flow, covs, r, num, den, cfg)
    save("merge", "out_num", num)
    save("merge", "out_den", den)
    MANIFEST["stages"]["merge"]["note"] = "merge(comp[128,128]) -> num/den[256,256,3]"


def stage_endtoend():
    # handheld_super_resolution/super_resolution.py:38 main
    from handheld_super_resolution.super_resolution import main

    cfg = e2e_config()
    frames = burst(256, 256, 3)
    save("endtoend", "in_burst", frames)
    out, _ = main(frames[0], frames[1:], cfg)
    save("endtoend", "out_image", out)
    MANIFEST["stages"]["endtoend"]["note"] = "main(burst[3,256,256],s2) -> img[512,512,3]"


def write_envelopes():
    """Copy numeric bounds from the Vulkan parity report verbatim."""
    rep = json.loads(REPORT_PATH.read_text())

    def num(x):
        return float(x) if isinstance(x, (int, float)) else None

    ica_lvls = rep["gpu_ica_levels"]["levels"]
    hess_lvls = rep["gpu_hessian_wired"]["levels"]
    res_lvls = rep["gpu_resample"]["levels"]
    grey_rows = rep["gpu_grey_fft"]["rows"]
    grad_rows = rep["gpu_grad"]["rows"]
    demo_rows = rep["gpu_demosaic"]["rows"]
    e2e = rep["gpu_endtoend_crop"]
    merge = rep["gpu_merge_crop"]

    stages = {
        "gat": {
            "max_abs_diff": num(rep["gpu_gat_f00"]["max_abs_diff"]),
            "max_rel_diff": num(rep["gpu_gat_f00"]["max_rel_diff"]),
            "report_key": "gpu_gat_f00",
        },
        "decimate": {
            "max_abs_diff": num(rep["gpu_decimate_f00"]["max_abs_diff"]),
            "max_rel_diff": num(rep["gpu_decimate_f00"]["max_rel_diff"]),
            "report_key": "gpu_decimate_f00",
        },
        "align": {
            "max_abs_diff": num(rep["gpu_align_crop"]["max_abs_diff"]),
            "max_rel_diff": None,
            "report_key": "gpu_align_crop",
        },
        "ica": {
            "max_abs_diff": max(l["refine_max_abs_diff"] for l in ica_lvls),
            "max_rel_diff": None,
            "levels": ica_lvls,
            "report_key": "gpu_ica_levels",
        },
        "kernels": {
            "max_abs_diff": num(rep["gpu_kernels_crop"]["finite_max_abs_diff"]),
            "max_rel_diff": num(rep["gpu_kernels_crop"]["finite_max_rel_diff"]),
            "report_key": "gpu_kernels_crop",
        },
        "robustness": {
            "max_abs_diff": num(rep["gpu_robustness_crop"]["finite_max_abs_diff"]),
            "max_rel_diff": num(rep["gpu_robustness_crop"]["finite_max_rel_diff"]),
            "report_key": "gpu_robustness_crop",
        },
        "merge": {
            "max_abs_diff": max(
                merge["num_max_abs_diff"], merge["den_max_abs_diff"]
            ),
            "max_rel_diff": max(
                merge["num_max_rel_diff"], merge["den_max_rel_diff"]
            ),
            "num": {
                "max_abs_diff": num(merge["num_max_abs_diff"]),
                "max_rel_diff": num(merge["num_max_rel_diff"]),
            },
            "den": {
                "max_abs_diff": num(merge["den_max_abs_diff"]),
                "max_rel_diff": num(merge["den_max_rel_diff"]),
            },
            "report_key": "gpu_merge_crop",
        },
        "grey_fft": {
            "max_abs_diff": max(
                grey_rows["pot"]["max_abs_diff"],
                grey_rows["bluestein"]["max_abs_diff"],
            ),
            "max_rel_diff": max(
                grey_rows["pot"]["max_rel_diff"],
                grey_rows["bluestein"]["max_rel_diff"],
            ),
            "rows": grey_rows,
            "report_key": "gpu_grey_fft",
        },
        "l2": {
            "max_abs_diff": max(
                l["max_abs_diff"] for l in rep["gpu_l2_levels"]["levels"]
            ),
            "max_rel_diff": None,
            "levels": rep["gpu_l2_levels"]["levels"],
            "report_key": "gpu_l2_levels",
        },
        "smap": {
            "max_abs_diff": num(rep["gpu_smap"]["max_abs_diff"]),
            "max_rel_diff": None,
            "report_key": "gpu_smap",
        },
        "divide": {
            "max_abs_diff": num(rep["gpu_divide"]["finite_max_abs_diff"]),
            "max_rel_diff": num(rep["gpu_divide"]["finite_max_rel_diff"]),
            "mask_note": {
                "masks_agree": rep["gpu_divide"]["masks_agree"],
                "mask_mismatches": rep["gpu_divide"]["mask_mismatches"],
                "ftz_subnormal_pixels": rep["gpu_divide"][
                    "ftz_subnormal_pixels"
                ],
                "unexplained_mask_mismatches": rep["gpu_divide"][
                    "unexplained_mask_mismatches"
                ],
                "ftz_model_exact": rep["gpu_divide"]["ftz_model_exact"],
            },
            "report_key": "gpu_divide",
        },
        "hessian": {
            "max_abs_diff": max(l["max_abs_diff"] for l in hess_lvls),
            "max_rel_diff": None,
            "levels": hess_lvls,
            "report_key": "gpu_hessian_wired",
        },
        "grad": {
            "max_abs_diff": max(
                grad_rows["kerngrad"]["max_abs_diff"],
                grad_rows["sqrt"]["max_diff"],
                grad_rows["gamma"]["max_diff"],
            ),
            "max_rel_diff": None,
            "rows": grad_rows,
            "report_key": "gpu_grad",
        },
        "resample": {
            "max_abs_diff": max(
                [l["max_abs_diff"] for l in res_lvls]
                + [rep["gpu_resample"]["upscale_max_abs"]]
            ),
            "max_rel_diff": None,
            "levels": res_lvls,
            "upscale_max_abs": num(rep["gpu_resample"]["upscale_max_abs"]),
            "report_key": "gpu_resample",
        },
        "demosaic": {
            "max_abs_diff": max(
                demo_rows["100"]["max_abs_diff"],
                demo_rows["512"]["max_abs_diff"],
            ),
            "max_rel_diff": None,
            "rows": demo_rows,
            "report_key": "gpu_demosaic",
        },
        "endtoend": {
            "max_abs_diff": num(e2e["gpu_vs_cpu"]["finite_max_abs_diff"]),
            "max_rel_diff": num(e2e["gpu_vs_cpu"]["finite_max_rel_diff"]),
            "interior_quantiles_gpu": e2e["interior"]["quantiles_gpu"],
            "interior_quantiles_control": e2e["interior"][
                "quantiles_control"
            ],
            "report_key": "gpu_endtoend_crop",
        },
    }
    env = {
        "_source": {
            "report": str(REPORT_PATH),
            "note": (
                "All numeric bounds below are copied verbatim from the "
                "Vulkan parity report parity_gpu_report.json; per-stage "
                "envelope summaries are exact maxima over the copied rows."
            ),
        },
        "stages": stages,
    }
    (HERE / "envelopes.json").write_text(json.dumps(env, indent=2) + "\n")


def main():
    FIX.mkdir(parents=True, exist_ok=True)
    stage_gat()
    stage_decimate()
    stage_grey_fft()
    stage_grey_fft_crop()
    stage_demosaic()
    stage_divide()
    stage_smap()
    stage_hessian()
    stage_grad()
    stage_resample()
    stage_kernels()
    stage_align()
    stage_align_crop()
    stage_l2()
    stage_ica()
    stage_robustness()
    stage_robustness_crop()
    stage_merge()
    stage_endtoend()
    write_envelopes()
    (FIX / "manifest.json").write_text(json.dumps(MANIFEST, indent=2) + "\n")
    n = len(list(FIX.glob("*.npy")))
    print(f"wrote {n} fixtures + manifest.json + envelopes.json")


if __name__ == "__main__":
    main()

