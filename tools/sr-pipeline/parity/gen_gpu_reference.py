"""Per-stage CPU-vs-GPU reference dump on IDENTICAL fixture inputs.

READ-ONLY with respect to both reference sources: this script only
*imports* from /Users/monikamalinowska/Handheld-Multi-Frame-Super-Resolution
(CPU) and /Users/monikamalinowska/Handheld-Multi-Frame-Super-Resolution-Vulkan
(GPU) and never writes outside this parity/gpu_reference/ directory.
Bytecode writing is disabled so even __pycache__ is left untouched.

Run (from this parity/ directory):
    /Users/monikamalinowska/Handheld-Multi-Frame-Super-Resolution/.venv/bin/python gen_gpu_reference.py

Inputs: parity/fixtures/<stage>_in*.npy (dumped by gen_fixtures.py).
Outputs (all under parity/gpu_reference/):
  - cpu_<stage>_out*.npy : Python-CPU recompute on the fixture inputs
  - gpu_<stage>_out*.npy : Vulkan-GPU drop-in on the SAME inputs
  - manifest2.json (shapes, cpu-vs-fixture check, cpu-vs-gpu maxabs/maxrel,
    device names, per-output status; a GPU failure records {"status":
    "unavailable"/"error"} and never invents bytes).

Determinism: inputs are the fixed SEED=0 fixtures; no RNG here.
"""

import sys

sys.dont_write_bytecode = True
sys.path.insert(0, "/Users/monikamalinowska/Handheld-Multi-Frame-Super-Resolution")
sys.path.insert(0, "/Users/monikamalinowska/Handheld-Multi-Frame-Super-Resolution-Vulkan")
sys.path.insert(0, ".")

import json
import time
import traceback
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F

import gen_fixtures as gf

HERE = Path(__file__).resolve().parent
FIX = HERE / "fixtures"
OUT = HERE / "gpu_reference"

CPU_REPO = "/Users/monikamalinowska/Handheld-Multi-Frame-Super-Resolution"
VK_REPO = "/Users/monikamalinowska/Handheld-Multi-Frame-Super-Resolution-Vulkan"

MANIFEST = {
    "seed": gf.SEED,
    "cpu_repo": CPU_REPO,
    "vk_repo": VK_REPO,
    "inputs_from": "parity/fixtures (gen_fixtures.py, SEED=0)",
    "stages": {},
}


def load(name):
    return np.load(FIX / f"{name}.npy")


def save(arr, name):
    arr = np.ascontiguousarray(arr, dtype=np.float32)
    np.save(OUT / f"{name}.npy", arr)
    return {"file": f"{name}.npy", "shape": list(arr.shape)}


def stats(a, b):
    a = np.ascontiguousarray(a, dtype=np.float32).astype(np.float64)
    b = np.ascontiguousarray(b, dtype=np.float32).astype(np.float64)
    assert a.shape == b.shape, f"shape {a.shape} vs {b.shape}"
    d = np.abs(a - b)
    denom = np.maximum(np.abs(b), 1e-12)
    with np.errstate(invalid="ignore"):
        r = d / denom
    return {
        "max_abs": float(np.nanmax(d)) if d.size else 0.0,
        "max_rel": float(np.nanmax(r)) if r.size else 0.0,
    }


def record(stage, inputs, outputs, cpu_res, gpu_res, note=""):
    """cpu_res/gpu_res: {out_name: array} or {"__error__": msg} for gpu."""
    entry = {"inputs": inputs, "outputs": outputs, "note": note}
    entry["cpu"] = {}
    for out in outputs:
        meta = save(cpu_res[out], f"cpu_{out}")
        meta["cpu_vs_fixture"] = stats(cpu_res[out], load(out))
        entry["cpu"][out] = meta
    entry["gpu"] = {}
    entry["cpu_vs_gpu"] = {}
    for out in outputs:
        if "__error__" in gpu_res:
            entry["gpu"][out] = {"status": "error", "error": gpu_res["__error__"][:300]}
            entry["cpu_vs_gpu"][out] = {"status": "no-gpu-output"}
        else:
            entry["gpu"][out] = save(gpu_res[out], f"gpu_{out}")
            if "device" in gpu_res:
                entry["gpu"][out]["device"] = gpu_res["device"]
            entry["cpu_vs_gpu"][out] = stats(cpu_res[out], gpu_res[out])
    MANIFEST["stages"][stage] = entry


def gpu_call(fn, *args, **kwargs):
    try:
        return fn(*args, **kwargs)
    except Exception as e:  # noqa: BLE001 - recorded, never raised
        return {"__error__": f"{type(e).__name__}: {e}\n{traceback.format_exc(limit=3)}"}


def split(r):
    """Normalise a GPU result to (array_or_None, device, error_or_None)."""
    if isinstance(r, dict) and "__error__" in r:
        return None, None, r["__error__"]
    if isinstance(r, tuple) and len(r) == 2 and isinstance(r[0], np.ndarray):
        return r[0], str(r[1]), None
    if isinstance(r, np.ndarray):
        return r, "unknown-device", None
    return None, None, f"unexpected GPU return {type(r)}"


def stage_gat():
    from handheld_super_resolution.utils_image import gat
    from host.gpu_pipeline import _device_gat

    raw = np.ascontiguousarray(load("gat_in_raw"))
    cpu = {"gat_out_vst": gat(raw, gf.ALPHA, gf.BETA)}
    arr, dev, err = split(gpu_call(_device_gat, raw, gf.ALPHA, gf.BETA))
    gpu = {"gat_out_vst": arr, "device": dev} if err is None else {"__error__": err}
    record("gat", ["gat_in_raw"], ["gat_out_vst"], cpu, gpu,
           "gat(raw[32,32]) identical input")


def stage_decimate():
    from handheld_super_resolution.utils_image import compute_grey_images
    from host.gpu_pipeline import _device_decimate

    raw = np.ascontiguousarray(load("decimate_in_raw"))
    cpu = {"decimate_out_grey": compute_grey_images(raw, "decimating")}
    arr, dev, err = split(gpu_call(_device_decimate, raw))
    gpu = {"decimate_out_grey": arr, "device": dev} if err is None else {"__error__": err}
    record("decimate", ["decimate_in_raw"], ["decimate_out_grey"], cpu, gpu,
           "decimating raw[32,32] identical input")


def stage_grey_fft():
    from handheld_super_resolution.utils_image import compute_grey_images
    from host.gpu_fft import gpu_grey_fft_lowpass

    raw = np.ascontiguousarray(load("grey_fft_in_raw"))
    cpu = {"grey_fft_out_lowpass": np.asarray(
        compute_grey_images(raw, "FFT"), dtype=np.float32)}
    arr, dev, err = split(gpu_call(gpu_grey_fft_lowpass, raw))
    gpu = {"grey_fft_out_lowpass": arr, "device": dev} if err is None else {"__error__": err}
    record("grey_fft", ["grey_fft_in_raw"], ["grey_fft_out_lowpass"], cpu, gpu,
           "FFT lowpass raw[32,32] identical input")


def stage_demosaic():
    from handheld_super_resolution.utils_image import demosaic_to_grey
    from host.gpu_demosaic import gpu_demosaic_grey

    cfa = np.ascontiguousarray(load("demosaic_in_cfa"))
    cpu = {"demosaic_out_grey": np.asarray(demosaic_to_grey(cfa), dtype=np.float32)}
    arr, dev, err = split(gpu_call(gpu_demosaic_grey, cfa))
    gpu = {"demosaic_out_grey": arr, "device": dev} if err is None else {"__error__": err}
    record("demosaic", ["demosaic_in_cfa"], ["demosaic_out_grey"], cpu, gpu,
           "Menon2007 RGGB cfa[32,32] identical input")


def stage_divide():
    from handheld_super_resolution.utils import divide
    from host.gpu_pipeline import gpu_divide

    num = np.ascontiguousarray(load("divide_in_num"))
    den = np.ascontiguousarray(load("divide_in_den"))
    n1, d1 = num.copy(), den.copy()
    divide(n1, d1)
    cpu = {"divide_out_ratio": n1}
    n2, d2 = num.copy(), den.copy()
    r = gpu_call(gpu_divide, n2, d2)
    gpu = {"divide_out_ratio": n2, "device": str(r)} if "__error__" not in r else r
    record("divide", ["divide_in_num", "divide_in_den"], ["divide_out_ratio"],
           cpu, gpu, "num/den [16,16,3] identical inputs, in place")


def stage_smap():
    from handheld_super_resolution.robustness import compute_s
    from host.gpu_pipeline import gpu_compute_s

    flow = np.ascontiguousarray(load("smap_in_flow"))
    cpu = {"smap_out_s": compute_s(flow, 0.8, 2.0, 12.0)}
    arr, dev, err = split(gpu_call(gpu_compute_s, flow, 0.8, 2.0, 12.0))
    gpu = {"smap_out_s": arr, "device": dev} if err is None else {"__error__": err}
    record("smap", ["smap_in_flow"], ["smap_out_s"], cpu, gpu,
           "compute_s(flow[4,4,2]) identical input")


def stage_hessian():
    from handheld_super_resolution.ICA import init_ica
    from host.gpu_grad import device_sobel
    from host.gpu_pipeline import _device_ica_hessian

    cfg = gf.small_config(64, [1, 2])
    grey = np.ascontiguousarray(load("hessian_in_grey"))
    gx, gy, h = init_ica(torch.as_tensor(grey, dtype=torch.float32), 16, cfg)
    cpu = {
        "hessian_out_gradx": gx,
        "hessian_out_grady": gy,
        "hessian_out_hessian": h,
    }
    r1 = gpu_call(device_sobel, grey)
    if isinstance(r1, dict) and "__error__" in r1:
        gpu = r1
    else:
        g_gx, g_gy, dev1 = r1
        r2 = gpu_call(_device_ica_hessian, g_gx, g_gy, 16)
        if isinstance(r2, dict) and "__error__" in r2:
            gpu = r2
        else:
            vec4, dev2 = r2
            # Shader vec4 layout is (h00, h01, h11, unused); unpack to [..,2,2].
            h2211 = np.zeros(vec4.shape[:2] + (2, 2), np.float32)
            h2211[..., 0, 0] = vec4[..., 0]
            h2211[..., 0, 1] = vec4[..., 1]
            h2211[..., 1, 0] = vec4[..., 1]
            h2211[..., 1, 1] = vec4[..., 2]
            gpu = {
                "hessian_out_gradx": g_gx,
                "hessian_out_grady": g_gy,
                "hessian_out_hessian": h2211,
                "device": f"sobel@{dev1} hessian@{dev2}",
            }
    record("hessian", ["hessian_in_grey"],
           ["hessian_out_gradx", "hessian_out_grady", "hessian_out_hessian"],
           cpu, gpu, "init_ica(grey[64,64],T16) identical input; "
           "GPU sobel feeds the on-device Hessian (chained, same root input)")


def stage_grad():
    import torch.nn.functional as F
    from handheld_super_resolution.ICA import init_ica
    from handheld_super_resolution.utils_image import preprocess
    from host.gpu_grad import (
        device_kernel_grads, device_preprocess, device_sobel,
    )

    cfg = gf.small_config(64, [1, 2])
    grey = np.ascontiguousarray(load("grad_in_grey"))
    th_grey = torch.as_tensor(grey, dtype=torch.float32)[None, None]
    k1 = torch.as_tensor(
        np.array([[[[-0.5, 0.5]]], [[[0.5, 0.5]]]]), dtype=torch.float32
    )
    k2 = torch.as_tensor(
        np.array([[[[0.5], [0.5]]], [[[-0.5], [0.5]]]]), dtype=torch.float32
    )
    full_grads = np.ascontiguousarray(
        F.conv2d(F.conv2d(th_grey, k1), k2, groups=2)
        .squeeze().transpose(0, 1).transpose(1, 2).numpy()
    )
    gx, gy, _ = init_ica(torch.as_tensor(grey, dtype=torch.float32), 16, cfg)
    th = torch.as_tensor(grey, dtype=torch.float32)
    cpu = {
        "grad_out_kerngrad": full_grads,
        "grad_out_sobel_gx": gx,
        "grad_out_sobel_gy": gy,
        "grad_out_sqrt": preprocess(th, "sqrt").numpy(),
        "grad_out_gamma": preprocess(th, "gamma").numpy(),
    }
    outs = ["grad_out_kerngrad", "grad_out_sobel_gx", "grad_out_sobel_gy",
            "grad_out_sqrt", "grad_out_gamma"]
    r1 = gpu_call(device_kernel_grads, grey)
    r2 = gpu_call(device_sobel, grey)
    r3 = gpu_call(device_preprocess, grey, "sqrt")
    r4 = gpu_call(device_preprocess, grey, "gamma")
    bad = next((r for r in (r1, r2, r3, r4)
                if isinstance(r, dict) and "__error__" in r), None)
    if bad is not None:
        gpu = bad
    else:
        (kg, d1), (sgx, sgy, d2), (sq, d3), (ga, d4) = r1, r2, r3, r4
        gpu = {
            "grad_out_kerngrad": kg,
            "grad_out_sobel_gx": sgx,
            "grad_out_sobel_gy": sgy,
            "grad_out_sqrt": sq,
            "grad_out_gamma": ga,
            "device": f"kerngrad@{d1} sobel@{d2} sqrt@{d3} gamma@{d4}",
        }
    record("grad", ["grad_in_grey"], outs, cpu, gpu,
           "kerngrad + sobel + sqrt/gamma of grey[64,64] identical input")


def stage_resample():
    from handheld_super_resolution.alignment import (
        build_gaussian_pyramid, upscale_lvl,
    )
    from handheld_super_resolution.utils_image import downsample
    from host.gpu_resample import gpu_build_pyramid, gpu_upscale_lvl

    cfg = gf.small_config(64, [1, 2])
    grey = np.ascontiguousarray(load("resample_in_grey"))
    th_img = torch.as_tensor(grey, dtype=torch.float32)[None, None]
    cpu_down2 = np.asarray(downsample(th_img, "gaussian", 2).squeeze().numpy(),
                           dtype=np.float32)
    pyr = build_gaussian_pyramid(th_img, [1, 2, 2])
    pyr = [np.asarray(lvl.squeeze().numpy(), dtype=np.float32) for lvl in pyr]
    flow = torch.zeros((1, 1, 2), dtype=torch.float32)
    up = upscale_lvl(flow, [2, 2], 0, cfg)
    cpu = {
        "resample_out_down2": cpu_down2,
        "resample_out_pyr0": pyr[0],
        "resample_out_pyr1": pyr[1],
        "resample_out_pyr2": pyr[2],
        "resample_out_upscaled_flow": up.numpy(),
    }
    outs = ["resample_out_down2", "resample_out_pyr0", "resample_out_pyr1",
            "resample_out_pyr2", "resample_out_upscaled_flow"]
    r1 = gpu_call(gpu_build_pyramid, th_img, [1, 2, 2])
    r2 = gpu_call(gpu_upscale_lvl, flow, [2, 2], 0, cfg)
    bad = next((r for r in (r1, r2)
                if isinstance(r, dict) and "__error__" in r), None)
    if bad is not None:
        gpu = bad
    else:
        (levels, labels), gup = r1, r2
        # levels are coarse-to-fine; the single device downsample of the
        # full frame is levels[1] (same op, same input as out_down2).
        gpu = {
            "resample_out_down2": np.asarray(levels[1].numpy(), dtype=np.float32),
            "resample_out_pyr0": np.asarray(levels[0].numpy(), dtype=np.float32),
            "resample_out_pyr1": np.asarray(levels[1].numpy(), dtype=np.float32),
            "resample_out_pyr2": np.asarray(levels[2].numpy(), dtype=np.float32),
            "resample_out_upscaled_flow": np.asarray(gup.detach().numpy(),
                                                     dtype=np.float32),
            "device": ";".join(labels),
        }
    record("resample", ["resample_in_grey"], outs, cpu, gpu,
           "gauss down2 + pyramid[1,2,2] + bilinup flow identical inputs")


def stage_kernels():
    from handheld_super_resolution.kernels import estimate_kernels
    from host.gpu_pipeline import gpu_estimate_kernels

    cfg = gf.small_config(64, [1, 2])
    raw = np.ascontiguousarray(load("kernels_in_raw"))
    cpu = {"kernels_out_covs": estimate_kernels(raw, cfg)}
    r = gpu_call(gpu_estimate_kernels, raw, cfg)
    if isinstance(r, dict) and "__error__" in r:
        gpu = r
    else:
        covs, dev, extra = r
        gpu = {"kernels_out_covs": covs, "device": f"{dev} {extra}"}
    record("kernels", ["kernels_in_raw"], ["kernels_out_covs"], cpu, gpu,
           "estimate_kernels(raw[64,64]) identical input")


def _tile_level(ref_lvl_np):
    """Exact host replica of the init_alignment tiling block (torch ops)."""
    lvl = torch.as_tensor(np.ascontiguousarray(ref_lvl_np, dtype=np.float32))
    tiled = lvl.unfold(0, 16, 16).unfold(1, 16, 16)
    tiled = F.pad(tiled, (4, 4, 4, 4), mode="constant", value=0)
    ref_fft = torch.fft.rfft2(tiled, dim=(-2, -1))
    return lvl, tiled, ref_fft


def stage_align():
    from handheld_super_resolution.alignment import align, init_alignment
    from host.gpu_pipeline import gpu_align, gpu_init_alignment

    cfg = gf.small_config(128, [1, 2, 2])
    ref = np.ascontiguousarray(load("align_in_ref_grey"))
    mov = np.ascontiguousarray(load("align_in_mov_grey"))
    cpu = {"align_out_flow": align(*init_alignment(ref, cfg), mov, cfg)}
    r = gpu_call(gpu_init_alignment, ref, cfg)
    if isinstance(r, dict) and "__error__" in r:
        gpu = r
    else:
        ref_pyr, tiled, tiled_fft, gx, gy, hess, devs = r
        r2 = gpu_call(gpu_align, ref_pyr, tiled, tiled_fft, gx, gy, hess,
                      mov, cfg)
        if isinstance(r2, dict) and "__error__" in r2:
            gpu = r2
        else:
            flow, labels = r2
            gpu = {"align_out_flow": flow, "device": ";".join(labels)}
    record("align", ["align_in_ref_grey", "align_in_mov_grey"],
           ["align_out_flow"], cpu, gpu,
           "align() 128px FFT-grey pair identical inputs")


def stage_l2():
    from handheld_super_resolution.block_matching import (
        align_lvl_block_matching_L2,
    )
    from host.gpu_l2 import gpu_align_lvl_l2

    cfg = gf.small_config(128, [1, 2, 2])
    ci = len(cfg.alignment.factors) - 1  # 2: coarsest level config index
    ref_lvl = np.ascontiguousarray(load("l2_in_ref_lvl"))
    mov_np = np.ascontiguousarray(load("l2_in_moving_lvl"))
    flow_np = np.ascontiguousarray(load("l2_in_flow_init"))
    lvl, tiled, ref_fft = _tile_level(ref_lvl)
    moving = torch.as_tensor(mov_np, dtype=torch.float32)
    alignment = torch.as_tensor(flow_np.copy(), dtype=torch.float32)
    align_lvl_block_matching_L2(tiled, ref_fft, moving, alignment, ci, cfg)
    cpu = {"l2_out_flow": alignment.numpy()}
    alignment_g = torch.as_tensor(flow_np.copy(), dtype=torch.float32)
    r = gpu_call(gpu_align_lvl_l2, tiled, moving, alignment_g, ci, cfg)
    if isinstance(r, dict) and "__error__" in r:
        gpu = r
    else:
        dev, _info = r
        gpu = {"l2_out_flow": np.asarray(alignment_g.numpy(), dtype=np.float32),
               "device": str(dev)}
    record("l2", ["l2_in_ref_lvl", "l2_in_moving_lvl", "l2_in_flow_init"],
           ["l2_out_flow"], cpu, gpu,
           "L2 corr on coarsest lvl identical inputs (tile tensors rebuilt "
           "from the l2_in_ref_lvl fixture bytes with the same torch ops)")


def stage_ica():
    from handheld_super_resolution.ICA import align_lvl_ica, init_ica
    from host.gpu_pipeline import gpu_align_lvl_ica

    cfg = gf.small_config(128, [1, 2, 2])
    ci = len(cfg.alignment.factors) - 1
    ref_np = np.ascontiguousarray(load("ica_in_ref_lvl"))
    mov_np = np.ascontiguousarray(load("ica_in_mov_lvl"))
    flow_np = np.ascontiguousarray(load("ica_in_flow_init"))
    ref_t = torch.as_tensor(ref_np, dtype=torch.float32)
    gx, gy, h = init_ica(ref_t, 16, cfg)
    moving = torch.as_tensor(mov_np, dtype=torch.float32)
    alignment = torch.as_tensor(flow_np.copy(), dtype=torch.float32)
    align_lvl_ica(ref_t, gx, gy, h, moving, alignment, ci, cfg)
    cpu = {"ica_out_flow": alignment.numpy()}
    alignment_g = torch.as_tensor(flow_np.copy(), dtype=torch.float32)
    r = gpu_call(gpu_align_lvl_ica, ref_t, gx, gy, h, moving, alignment_g,
                 ci, cfg)
    gpu = ({"ica_out_flow": np.asarray(alignment_g.numpy(), dtype=np.float32),
            "device": str(r)} if "__error__" not in r else r)
    record("ica", ["ica_in_ref_lvl", "ica_in_mov_lvl", "ica_in_flow_init"],
           ["ica_out_flow"], cpu, gpu,
           "align_lvl_ica on coarsest lvl identical inputs (CPU grads feed "
           "the on-device refine, isolating that op)")


def stage_robustness():
    from handheld_super_resolution.robustness import (
        compute_robustness, init_robustness,
    )
    from host.gpu_pipeline import (
        gpu_compute_robustness, gpu_init_robustness,
    )

    cfg = gf.small_config(128, [1, 2, 2])
    ref = np.ascontiguousarray(load("robustness_in_ref_raw"))
    comp = np.ascontiguousarray(load("robustness_in_comp_raw"))
    flow = np.ascontiguousarray(load("robustness_in_flow"))
    means, var = init_robustness(ref, cfg)
    nm = (np.asarray(cfg.noise_model.sigma_sq_curve, dtype=np.float32),
          np.asarray(cfg.noise_model.d_sq_curve, dtype=np.float32))
    cpu = {"robustness_out_r": compute_robustness(comp, means, var, flow, nm, cfg)}
    r = gpu_call(gpu_init_robustness, ref, cfg)
    if isinstance(r, dict) and "__error__" in r:
        gpu = r
    else:
        g_means, g_stds, devs = r
        r2 = gpu_call(gpu_compute_robustness, comp, g_means, g_stds, flow,
                      nm, cfg)
        if isinstance(r2, dict) and "__error__" in r2:
            gpu = r2
        else:
            rr, labels = r2
            gpu = {"robustness_out_r": rr,
                   "device": ";".join(devs) + "|" + ";".join(labels)}
    record("robustness",
           ["robustness_in_ref_raw", "robustness_in_comp_raw", "robustness_in_flow"],
           ["robustness_out_r"], cpu, gpu,
           "compute_robustness(128px pair + fixture flow) identical inputs")


def _crop_parity_inputs():
    """Real-burst 512-crop inputs shared by the crop GPU stages (mirrors
    gen_fixtures.burst_crop + parity_config(512); read-only)."""
    import sys
    sys.path.insert(0, "/Users/monikamalinowska/Handheld-Multi-Frame-Super-Resolution-Vulkan")
    from parity.parity_check import parity_config
    stack, ref_c, mov_c = gf.burst_crop(512)
    return parity_config(512), ref_c, mov_c


def stage_robustness_crop():
    from handheld_super_resolution.robustness import (
        compute_robustness, init_robustness,
    )
    from handheld_super_resolution.utils_image import compute_grey_images
    from host.gpu_pipeline import (
        gpu_compute_robustness, gpu_init_robustness,
    )

    config, ref_c, mov_c = _crop_parity_inputs()
    rg = compute_grey_images(ref_c, config.alignment.grey_method)
    mg = compute_grey_images(mov_c, config.alignment.grey_method)
    from handheld_super_resolution.alignment import align, init_alignment
    rp, tiled, tfft, gx, gy, hess = init_alignment(rg, config, None)
    flow = align(rp, tiled, tfft, gx, gy, hess, mg, config, None)
    mc, sc = init_robustness(ref_c, config)
    nm = (np.asarray(config.noise_model.sigma_sq_curve, dtype=np.float32),
          np.asarray(config.noise_model.d_sq_curve, dtype=np.float32))
    cpu = {"robustness_crop_out_r":
           compute_robustness(mov_c, mc, sc, flow, nm, config, None)}
    r = gpu_call(gpu_init_robustness, ref_c, config)
    if isinstance(r, dict) and "__error__" in r:
        gpu = r
    else:
        g_means, g_stds, devs = r
        r2 = gpu_call(gpu_compute_robustness, mov_c, g_means, g_stds, flow,
                      nm, config)
        if isinstance(r2, dict) and "__error__" in r2:
            gpu = r2
        else:
            rr, labels = r2
            gpu = {"robustness_crop_out_r": rr,
                   "device": ";".join(devs) + "|" + ";".join(labels)}
    record("robustness_crop",
           ["robustness_crop_in_comp", "robustness_crop_in_means",
            "robustness_crop_in_var", "robustness_crop_in_flow",
            "robustness_crop_in_sig", "robustness_crop_in_d"],
           ["robustness_crop_out_r"], cpu, gpu,
           "compute_robustness(burst512-crop) identical inputs")


def stage_grey_fft_crop():
    from handheld_super_resolution.utils_image import compute_grey_images
    from host.gpu_fft import gpu_grey_fft_lowpass

    _, _, mov_c = _crop_parity_inputs()
    cpu = {"grey_fft_crop_out_lowpass": np.asarray(
        compute_grey_images(mov_c, "FFT"), dtype=np.float32)}
    arr, dev, err = split(gpu_call(gpu_grey_fft_lowpass, mov_c))
    gpu = ({"grey_fft_crop_out_lowpass": arr, "device": dev}
           if err is None else {"__error__": err})
    record("grey_fft_crop", ["grey_fft_crop_in_raw"],
           ["grey_fft_crop_out_lowpass"], cpu, gpu,
           "FFT lowpass burst512-crop identical input")


def stage_align_crop():
    from handheld_super_resolution.alignment import align, init_alignment
    from handheld_super_resolution.utils_image import compute_grey_images
    from host.gpu_pipeline import gpu_align, gpu_init_alignment

    config, ref_c, mov_c = _crop_parity_inputs()
    rg = compute_grey_images(ref_c, config.alignment.grey_method)
    mg = compute_grey_images(mov_c, config.alignment.grey_method)
    cpu_flow = align(*init_alignment(rg, config, None), mg, config, None)
    cpu = {"align_crop_out_flow": np.asarray(cpu_flow, dtype=np.float32)}
    r = gpu_call(gpu_init_alignment, rg, config)
    if isinstance(r, dict) and "__error__" in r:
        gpu = r
    else:
        ref_pyr, tiled, tiled_fft, gx, gy, hess, devs = r
        r2 = gpu_call(gpu_align, ref_pyr, tiled, tiled_fft, gx, gy, hess,
                      mg, config)
        if isinstance(r2, dict) and "__error__" in r2:
            gpu = r2
        else:
            flow, labels = r2
            gpu = {"align_crop_out_flow": np.asarray(flow, dtype=np.float32),
                   "device": ";".join(labels)}
    record("align_crop",
           ["align_crop_in_ref_grey", "align_crop_in_mov_grey"],
           ["align_crop_out_flow"], cpu, gpu,
           "align() burst512-crop identical inputs (final flow only; "
           "per-level flows stay GPU-SKIP)")


def stage_merge():
    from handheld_super_resolution.merge import merge
    from host.gpu_pipeline import gpu_merge

    cfg = gf.small_config(128, [1, 2, 2], scale=2)
    comp = np.ascontiguousarray(load("merge_in_comp_raw"))
    flow = np.ascontiguousarray(load("merge_in_flow"))
    covs = np.ascontiguousarray(load("merge_in_covs"))
    r_ = np.ascontiguousarray(load("merge_in_r"))
    num = np.zeros((256, 256, 3), np.float32)
    den = np.zeros((256, 256, 3), np.float32)
    merge(comp, flow, covs, r_, num, den, cfg)
    cpu = {"merge_out_num": num, "merge_out_den": den}
    num_g = np.zeros((256, 256, 3), np.float32)
    den_g = np.zeros((256, 256, 3), np.float32)
    r = gpu_call(gpu_merge, comp, flow, covs, r_, num_g, den_g, cfg)
    gpu = ({"merge_out_num": num_g, "merge_out_den": den_g, "device": str(r)}
           if "__error__" not in r else r)
    record("merge",
           ["merge_in_comp_raw", "merge_in_flow", "merge_in_covs", "merge_in_r"],
           ["merge_out_num", "merge_out_den"], cpu, gpu,
           "merge(comp[128,128]) identical inputs -> num/den[256,256,3]")


def stage_endtoend():
    from handheld_super_resolution.super_resolution import main
    from host.gpu_pipeline import gpu_main

    cfg = gf.e2e_config()
    burst = np.ascontiguousarray(load("endtoend_in_burst"))
    out, _ = main(burst[0], burst[1:], cfg)
    cpu = {"endtoend_out_image": out}
    r = gpu_call(gpu_main, burst[0], burst[1:], cfg)
    if isinstance(r, dict) and "__error__" in r:
        gpu = r
    else:
        num, _debug, _report = r
        gpu = {"endtoend_out_image": num, "device": "Apple M1 (see report)"}
    record("endtoend", ["endtoend_in_burst"], ["endtoend_out_image"], cpu, gpu,
           "main(burst[3,256,256],s2) identical inputs -> img[512,512,3]")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    stages = [
        stage_gat, stage_decimate, stage_grey_fft, stage_grey_fft_crop,
        stage_demosaic, stage_divide, stage_smap, stage_hessian, stage_grad,
        stage_resample, stage_kernels, stage_align, stage_align_crop,
        stage_l2, stage_ica, stage_robustness, stage_robustness_crop,
        stage_merge, stage_endtoend,
    ]
    for fn in stages:
        t0 = time.perf_counter()
        try:
            fn()
            dt = time.perf_counter() - t0
            st = MANIFEST["stages"][fn.__name__.replace("stage_", "")]
            gkeys = list(st.get("gpu", {}).keys())
            gerr = [k for k in gkeys
                    if isinstance(st["gpu"][k], dict)
                    and st["gpu"][k].get("status") == "error"]
            print(f"[{'GPU-FAIL' if gerr else 'ok'}] {fn.__name__} "
                  f"({dt:.1f}s)" + (f" gpu errors: {gerr}" if gerr else ""),
                  flush=True)
        except Exception as e:  # noqa: BLE001 - one bad stage must not kill the dump
            print(f"[STAGE-FAIL] {fn.__name__}: {type(e).__name__}: {e}",
                  flush=True)
            traceback.print_exc(limit=5)
            MANIFEST["stages"].setdefault(
                fn.__name__.replace("stage_", {}),
                {"status": "stage-error", "error": f"{type(e).__name__}: {e}"[:300]})
    (OUT / "manifest2.json").write_text(json.dumps(MANIFEST, indent=2) + "\n")
    n = len(list(OUT.glob("*.npy")))
    print(f"wrote {n} reference arrays + manifest2.json")


if __name__ == "__main__":
    main()
