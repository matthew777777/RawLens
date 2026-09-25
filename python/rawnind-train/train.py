# SPDX-FileCopyrightText: 2026 RawLens contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Train RawNIND-tiny on Mac (PyTorch MPS/CUDA/CPU).

Pipeline per patch (deterministic given --seed):
  1. Sample source: with prob synthetic_frac a clean `singles` frame with
     synthetic Poisson-Gaussian noise (synthetic.py, shot/read drawn
     log-uniform ~0.5x..3x nominal for multi-ISO coverage); else a real
     noisy/clean pair from bursts/<id>/ (frame_*.dng vs merged.dng).
  2. Full-frame flip aug (dng_loader.flip_bayer_full), RGGB-parity patch
     sampling, saturation filter, pack to [R,G1,G2,B] + sigma plane.
  3. Loss: losses.combined (Bayer L1 + 0.2 demosaic L1). AdamW + cosine.

Mac notes:
  * Device auto: mps (Apple Silicon) > cuda > cpu. Override with --device.
  * Streaming dataset: frames are cached as uint16 codes (~24MB per 12MP
    frame) with a tiny per-epoch slot index; pixel work (crop, flip, pack,
    noise) happens per sample inside DataLoader --workers. 8GB unified
    memory stays comfortable; --workers 2-4 recommended.
  * --amp enables fp16 autocast (default on for mps/cuda): ~1.3-1.5x on
    Apple Silicon, no metric change.
  * Start small: --epochs 5 smoke test on .npy pre-extracts before the full
    DNG run. Full 12MP DNGs are patched on the fly; nothing full-res is
    moved to the GPU.

Usage:
  python3 train.py --data data --out checkpoints --epochs 200 --batch 16
  tensorboard not required; metrics print + checkpoints/<best>.pt saved.
"""

import argparse
import os
import random
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import TrainConfig
import dng_loader
import losses as loss_fn
from rawdsets import RawStreamDataset, numpy_collate, build_items

try:
    import torch
    import torch.nn as nn
    from torch.utils.data import DataLoader
    from model import RawNindTiny, count_parameters
except ImportError as e:  # pragma: no cover
    raise SystemExit("train.py needs torch: pip install -r requirements.txt (%s)" % e)


def pick_device(name):
    """mps > cuda > cpu auto-selection for Mac training."""
    if name != "auto":
        return torch.device(name)
    if hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
        return torch.device("mps")
    if torch.cuda.is_available():
        return torch.device("cuda")
    return torch.device("cpu")


def main():
    ap = argparse.ArgumentParser(description="Train RawNIND-tiny (Mac MPS)")
    ap.add_argument("--data", default="data")
    ap.add_argument("--out", default="checkpoints")
    ap.add_argument("--epochs", type=int, default=TrainConfig.epochs)
    ap.add_argument("--batch", type=int, default=TrainConfig.batch)
    ap.add_argument("--lr", type=float, default=TrainConfig.lr)
    ap.add_argument("--seed", type=int, default=TrainConfig.seed)
    ap.add_argument("--device", default="auto")
    ap.add_argument("--workers", type=int, default=2,
                    help="DataLoader workers (streaming dataset: pixel work happens here)")
    ap.add_argument("--val-every", type=int, default=5)
    ap.add_argument("--amp", action=argparse.BooleanOptionalAction, default=None,
                    help="fp16 autocast (default: on for mps/cuda, off for cpu)")
    ap.add_argument("--compile", action=argparse.BooleanOptionalAction, default=False,
                    help="torch.compile the model (MPS support varies by torch "
                         "version; falls back to eager with a warning)")
    ap.add_argument("--compile-backend", default=None,
                    help="compile backend override: aot_eager (MPS-safe default), "
                         "inductor, or inductor-noautotune (inductor with GEMM/coordinate "
                         "autotuning off -- the autotuner hangs on MPS, so test this "
                         "with --epochs 2 first and kill it if epoch 0 stalls >10 min)")
    args = ap.parse_args()

    cfg = TrainConfig(epochs=args.epochs, batch=args.batch, lr=args.lr, seed=args.seed)
    random.seed(cfg.seed)
    np.random.seed(cfg.seed)
    torch.manual_seed(cfg.seed)
    device = pick_device(args.device)
    print("device=%s" % device)

    items = build_items(args.data, cfg)
    if not items:
        raise SystemExit(
            "no training data under %s/ (need bursts/<id>/{frame_*.dng,merged.dng} "
            "and/or singles/*.dng; see README)" % args.data)
    n_real = sum(1 for i in items if i["kind"] == "real")
    print("items=%d (real=%d synth=%d)" % (len(items), n_real, len(items) - n_real))

    # 90/10 item split (val uses fixed frame + no aug).
    rng_split = np.random.default_rng(cfg.seed)
    order = rng_split.permutation(len(items))
    n_val = max(1, len(items) // 10)
    val_items = [items[i] for i in order[:n_val]]
    train_items = [items[i] for i in order[n_val:]] or items

    model = RawNindTiny(with_sigma=cfg.with_sigma).to(device)
    print("params=%d" % count_parameters(model))
    if args.compile:
        backend = args.compile_backend or ("aot_eager" if device.type == "mps" else "inductor")
        try:
            if backend == "inductor-noautotune":
                # Full inductor fusion without the GEMM/coordinate-descent
                # autotuner that hangs on MPS ("Not enough SMs...").
                model = torch.compile(
                    model,
                    options={"max_autotune": False, "coordinate_descent_tuning": False})
                print("torch.compile: on (inductor, autotune OFF)")
            else:
                model = torch.compile(model, backend=backend)
                print("torch.compile: on (%s)" % backend)
        except Exception as e:
            print("torch.compile unavailable, continuing eager (%s)" % e)
    opt = torch.optim.AdamW(model.parameters(), lr=cfg.lr, weight_decay=cfg.weight_decay)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=cfg.epochs)
    use_amp = args.amp if args.amp is not None else device.type in ("mps", "cuda")
    scaler = torch.amp.GradScaler(device.type) if use_amp else None
    print("amp=%s workers=%d" % (use_amp, args.workers))
    os.makedirs(args.out, exist_ok=True)

    best = float("inf")
    for ep in range(cfg.epochs):
        rng = np.random.default_rng([cfg.seed, ep])
        t_build = time.perf_counter()
        train_ds = RawStreamDataset(train_items, cfg, rng)
        build_s = time.perf_counter() - t_build
        if len(train_ds) == 0:
            print("epoch %d: no patches (check saturation filter / data)" % ep)
            continue
        dl = DataLoader(train_ds, batch_size=cfg.batch, shuffle=True,
                        num_workers=args.workers, collate_fn=numpy_collate,
                        # Pinned memory is CUDA-only (MPS warns); persistent
                        # workers avoid respawning 3 processes every epoch.
                        pin_memory=device.type == "cuda",
                        persistent_workers=args.workers > 0)
        model.train()
        tot, n = 0.0, 0
        data_s, compute_s = 0.0, 0.0
        t_loop = time.perf_counter()
        need = iter(dl)
        while True:
            t0 = time.perf_counter()
            try:
                xin, tgt = next(need)
            except StopIteration:
                break
            data_s += time.perf_counter() - t0
            t0 = time.perf_counter()
            # Batches arrive as shared-memory numpy (worker torch-free);
            # from_numpy is zero-copy in the main process.
            xin = torch.from_numpy(xin).to(device, non_blocking=True)
            tgt = torch.from_numpy(tgt).to(device, non_blocking=True)
            opt.zero_grad()
            with torch.amp.autocast(device.type, enabled=use_amp):
                loss = loss_fn.combined(model(xin), tgt)
            if scaler is not None:
                scaler.scale(loss).backward()
                scaler.unscale_(opt)
                nn.utils.clip_grad_norm_(model.parameters(), 1.0)
                scaler.step(opt)
                scaler.update()
            else:
                loss.backward()
                nn.utils.clip_grad_norm_(model.parameters(), 1.0)
                opt.step()
            tot += float(loss.detach()) * len(xin)
            n += len(xin)
            compute_s += time.perf_counter() - t0
        sched.step()
        loop_s = time.perf_counter() - t_loop
        msg = "epoch %3d train_l1=%.5f lr=%.1e patches=%d build=%.0fs loop=%.0fs (data=%.0fs compute=%.0fs)" % (
            ep, tot / max(n, 1), sched.get_last_lr()[0], len(train_ds),
            build_s, loop_s, data_s, compute_s)
        if ep % args.val_every == 0:
            model.eval()
            with torch.no_grad():
                # SeedSequence takes unsigned ints only; offset the val stream
                # clear of the train stream ([seed, epoch]).
                vds = RawStreamDataset(val_items, cfg, np.random.default_rng([cfg.seed, 1_000_000 + ep]))
                if len(vds):
                    vdl = DataLoader(vds, batch_size=cfg.batch, num_workers=0,
                                     collate_fn=numpy_collate)
                    vt, vn, ps = 0.0, 0, []
                    for xin, tgt in vdl:
                        pred = model(torch.from_numpy(xin).to(device)).cpu().numpy()
                        tgt_np = np.asarray(tgt)
                        vt += float((np.abs(pred - tgt_np)).mean()) * len(xin)
                        vn += len(xin)
                        ps.append(loss_fn.psnr(pred[0], tgt_np[0]))
                    msg += " val_l1=%.5f val_psnr=%.2f" % (vt / vn, float(np.mean(ps)))
                    if vt / vn < best:
                        best = vt / vn
                        torch.save(model.state_dict(), os.path.join(args.out, "best.pt"))
        print(msg, flush=True)
        torch.save(model.state_dict(), os.path.join(args.out, "last.pt"))
    print("done. best val_l1=%.5f -> %s/best.pt" % (best, args.out))


if __name__ == "__main__":
    main()
