# SPDX-FileCopyrightText: 2026 RawLens contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Profile the RawNIND-tiny training pipeline: CPU data vs GPU compute.

Answers the one question that decides every speedup that follows: is an
epoch bottlenecked by the per-epoch NumPy patch rebuild (flip + pack +
noise on CPU) or by MPS forward/backward? Rule of thumb from the verdict:

  CPU-bound  -> --workers, patch caching (MLX won't help: same CPU pipe)
  GPU-bound  -> autocast fp16, larger batches, MLX port worth it
  balanced   -> tune both; start with the cheap flags

Stages (each timed; torch only required for loader/gpu stages):
  1. scan      dataset scan + counts
  2. frame     load + normalize one frame (median of 3)
  3. epoch     per-epoch slot-index build (frame load as uint16 codes +
               flip draw + coord sampling; pixels stream in workers)
  4. loader    DataLoader batch-fetch throughput, workers=0 vs --workers
  5. gpu       forward + combined loss + backward per batch, device-synced

Usage:
  python3 profile_train.py --data data --batch 32 --workers 4
  python3 profile_train.py --data data --max-items 4   # quick subset estimate
"""

import argparse
import os
import statistics
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import TrainConfig
import dng_loader
import synthetic
from rawdsets import RawStreamDataset, numpy_collate, build_items

try:
    import train
    HAVE_TRAIN = True
except ImportError as e:  # torch (or deps) missing: data stages still run
    train = None
    HAVE_TRAIN = False
    _TRAIN_IMPORT_ERROR = e


def _sync(device, torch):
    """Device synchronize for honest GPU timing (no-op on CPU/MPS-less)."""
    try:
        if str(device) == "mps" and hasattr(torch, "mps"):
            torch.mps.synchronize()
        elif str(device).startswith("cuda"):
            torch.cuda.synchronize()
    except Exception:
        pass


def stage_scan(data_root, cfg):
    """Stage 1: dataset scan."""
    if not HAVE_TRAIN:
        raise SystemExit("stage scan needs train.py imports (torch): %s" % _TRAIN_IMPORT_ERROR)
    t0 = time.perf_counter()
    items = build_items(data_root, cfg)
    dt = time.perf_counter() - t0
    n_real = sum(1 for i in items if i["kind"] == "real")
    print("stage scan: items=%d (real=%d synth=%d) in %.2fs"
          % (len(items), n_real, len(items) - n_real, dt))
    return items


def stage_frame(items):
    """Stage 2: single-frame load + normalize (median of 3, torch-free)."""
    if not HAVE_TRAIN:
        raise SystemExit("stage frame needs train.py imports (torch): %s" % _TRAIN_IMPORT_ERROR)
    path = items[0]["clean_path"] if items[0]["kind"] == "synth" else items[0]["noisy_paths"][0]
    dts = []
    for _ in range(3):
        t0 = time.perf_counter()
        d = dng_loader.read_frame(path)
        bayer = dng_loader.normalize_bayer(d["codes"], d["black4"], d["white"])
        dts.append(time.perf_counter() - t0)
    med = statistics.median(dts)
    mp = d["codes"].size / 1e6
    print("stage frame: load+normalize %s %.1fMP median %.2fs (%.1f MP/s)"
          % (os.path.basename(path), mp, med, mp / max(med, 1e-9)))
    return med


def stage_epoch(items, cfg, seed, max_items):
    """Stage 3: per-epoch index build (torch-free slot planning).

    Times the RawStreamDataset construction train.py runs every epoch: frame
    load (uint16 codes) + flip draw + saturation-filtered coord sampling.
    Pixel work itself happens per sample in DataLoader workers (see the
    stage-4 loader throughput), so this measures main-process overhead only.
    Returns (seconds, patch_count).
    """
    if not HAVE_TRAIN:
        raise SystemExit("stage epoch needs train.py imports (torch): %s" % _TRAIN_IMPORT_ERROR)
    import numpy as np
    subset = items if not max_items else items[:max_items]
    t0 = time.perf_counter()
    ds = RawStreamDataset(subset, cfg, np.random.default_rng(seed))
    dt = time.perf_counter() - t0
    total = len(ds)
    scale = len(items) / max(len(subset), 1)
    proj = dt * scale
    print("stage epoch: %d items -> %d slots in %.1fs (%.0f slots/s); "
          "projected full %d-item index %.1fs/epoch (frames cached, pixels stream in workers)"
          % (len(subset), total, dt, total / max(dt, 1e-9), len(items), proj))
    if total == 0:
        print("stage epoch: ZERO patches -- frames are likely smaller than the "
              "%dpx patch or the saturation filter dropped everything" % cfg.bayer_patch)
    return proj, int(total * scale)


def stage_loader(items, cfg, seed, batch, workers, batches):
    """Stage 4: DataLoader batch-fetch throughput (torch)."""
    import numpy as np
    import torch
    from torch.utils.data import DataLoader
    results = {}
    for w in sorted({0, workers}):
        ds = RawStreamDataset(items, cfg, np.random.default_rng([seed, 1]))
        if len(ds) == 0:
            print("stage loader: no patches, skipping")
            return results
        dl = DataLoader(ds, batch_size=batch, shuffle=True, num_workers=w,
                        collate_fn=numpy_collate, persistent_workers=w > 0)
        t0 = time.perf_counter()
        n = 0
        for i, (xin, tgt) in enumerate(dl):
            n += len(xin)
            if i + 1 >= batches:
                break
        dt = time.perf_counter() - t0
        results[w] = (n / max(dt, 1e-9), dt)
        print("stage loader: workers=%d -> %.0f patches/s fetch (%d batches in %.1fs)"
              % (w, results[w][0], min(batches, len(dl)), dt))
    return results


def stage_gpu(cfg, device_name, batch, iters):
    """Stage 5: forward + loss + backward per batch, device-synced (torch)."""
    import numpy as np
    import torch
    import losses as loss_fn
    from model import RawNindTiny
    device = train.pick_device(device_name)
    model = RawNindTiny(with_sigma=cfg.with_sigma).to(device)
    opt = torch.optim.AdamW(model.parameters(), lr=cfg.lr)
    c = 5 if cfg.with_sigma else 4
    xin = torch.rand(batch, c, 128, 128).to(device)
    tgt = torch.rand(batch, 4, 128, 128).to(device)
    model.train()
    for _ in range(5):  # warmup (MPS shader/allocator standup)
        opt.zero_grad()
        loss_fn.combined(model(xin), tgt).backward()
        opt.step()
    _sync(device, torch)
    dts = []
    for _ in range(iters):
        _sync(device, torch)
        t0 = time.perf_counter()
        opt.zero_grad()
        loss = loss_fn.combined(model(xin), tgt)
        loss.backward()
        opt.step()
        _sync(device, torch)
        dts.append(time.perf_counter() - t0)
    med = statistics.median(dts)
    print("stage gpu: device=%s forward+loss+backward batch=%d median %.1fms "
          "(%.0f patches/s compute)" % (device, batch, med * 1e3, batch / med))
    return med


def main():
    ap = argparse.ArgumentParser(description="Profile rawnind-train CPU vs GPU split")
    ap.add_argument("--data", default="data")
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--batches", type=int, default=30,
                    help="DataLoader batches to time per worker setting")
    ap.add_argument("--gpu-iters", type=int, default=20)
    ap.add_argument("--seed", type=int, default=TrainConfig.seed)
    ap.add_argument("--device", default="auto")
    ap.add_argument("--max-items", type=int, default=0,
                    help="cap stage-3 items (0 = all, extrapolates otherwise)")
    ap.add_argument("--skip-gpu", action="store_true",
                    help="data stages only (headless / torch-free check)")
    args = ap.parse_args()

    cfg = TrainConfig(batch=args.batch, seed=args.seed)
    print("profile: batch=%d workers=%d device=%s" % (args.batch, args.workers, args.device))

    if not HAVE_TRAIN:
        print("torch unavailable (%s); running torch-free frame microbench only"
              % _TRAIN_IMPORT_ERROR)
        import numpy as np
        rng = np.random.default_rng(args.seed)
        bayer = rng.random((2048, 2048)).astype(np.float32)
        dts = []
        for _ in range(3):
            t0 = time.perf_counter()
            aug, pat = dng_loader.flip_bayer_full(bayer, "RGGB", "random", rng)
            ps = dng_loader.extract_patches_rggb(aug, pat, 256, 128)
            packed = [dng_loader.pack_rggb(p) for p in ps]
            noisy = [synthetic.synthesize_noisy(p, synthetic.SHOT, synthetic.READ, rng) for p in ps]
            dts.append(time.perf_counter() - t0)
        med = statistics.median(dts)
        print("microbench (2K frame, torch-free): flip+patch+pack+noise median %.2fs "
              "for %d patches" % (med, len(ps)))
        return

    items = stage_scan(args.data, cfg)
    if not items:
        raise SystemExit("no training data under %s/" % args.data)
    stage_frame(items)
    data_s, patches = stage_epoch(items, cfg, args.seed, args.max_items)

    compute_s, iters_per_epoch = None, None
    if not args.skip_gpu:
        stage_loader(items, cfg, args.seed, args.batch, args.workers, args.batches)
        ms = stage_gpu(cfg, args.device, args.batch, args.gpu_iters)
        iters_per_epoch = max(int(round(patches / args.batch)), 1)
        compute_s = iters_per_epoch * ms
        print("projection: %d patches / batch %d = %d iters -> compute ~%.1fs/epoch; "
              "data rebuild ~%.1fs/epoch" % (patches, args.batch, iters_per_epoch, compute_s, data_s))

        ratio = data_s / max(compute_s, 1e-9)
        print("--- verdict: data/compute ratio %.1fx ---" % ratio)
        if ratio > 2.0:
            print("CPU-BOUND: the per-epoch NumPy rebuild dominates. Do (in order): "
                  "--workers 4+, cache clean patches across epochs, validate less often. "
                  "An MLX port will NOT help (same CPU pipe).")
        elif ratio < 0.5:
            print("GPU-BOUND: MPS compute dominates. Do (in order): larger --batch, "
                  "autocast fp16, then the MLX port is worth it.")
        else:
            print("BALANCED: tune both sides; start with --batch/--workers/--val-every "
                  "(cheapest), then autocast.")
    else:
        print("projection: data rebuild ~%.1fs/epoch for ~%d patches (--skip-gpu, no compute side)"
              % (data_s, patches))


if __name__ == "__main__":
    main()
