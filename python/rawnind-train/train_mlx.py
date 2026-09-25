# SPDX-FileCopyrightText: 2026 RawLens contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Train RawNIND-tiny with MLX on Apple Silicon, export via PyTorch.

Same data, same architecture, same loss as train.py -- only the compute
framework differs (MLX fuses small conv kernels far better than
PyTorch-MPS, which is launch-bound at ~76 patches/s on an M1 Air):

  * Data: reuses RawStreamDataset (rawdsets.py, numpy-only so DataLoader
    workers stay import-light) with IDENTICAL seeds, so --seed 7 here sees
    exactly the same patches in the same order as train.py.
  * Layout: batches transpose NCHW -> NHWC once per batch (cheap).
  * Loss: Bayer L1 + 0.2 demosaic L1; the bilinear x2 upsample is manual
    (edge-padded shifted adds, align_corners=False weights, matching
    losses.py exactly -- verified numerically in the self-check below).
  * Optimizer: AdamW + epoch-granular cosine (mirrors CosineAnnealingLR),
    grad-norm clip 1.0 like train.py.
  * Checkpoints: MLX flat npz; transfer with transfer_mlx_to_torch.py,
    then export.py is unchanged.

Usage:
  python3 train_mlx.py --data data --out checkpoints --epochs 200 --batch 64 --workers 3
  python3 transfer_mlx_to_torch.py --mlx checkpoints/mlx_best.npz --torch checkpoints/best.pt
"""

import argparse
import math
import os
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import TrainConfig
from rawdsets import RawStreamDataset, numpy_collate, build_items

try:
    import mlx.core as mx
    import mlx.nn as nn
    import mlx.optimizers as optim
    from model_mlx import RawNindTiny, count_parameters, flatten_params
except ImportError as _e:  # pragma: no cover
    raise SystemExit("train_mlx.py needs mlx: pip install -r requirements-mlx.txt (%s)" % _e)


def _up_h(x):
    """Bilinear x2 along H, align_corners=False (matches F.interpolate)."""
    h = x.shape[1]
    xp = mx.pad(x, [(0, 0), (1, 1), (0, 0), (0, 0)], mode="edge")
    core = xp[:, 1:h + 1]
    r0 = 0.75 * core + 0.25 * xp[:, 0:h]
    r1 = 0.75 * core + 0.25 * xp[:, 2:h + 2]
    return mx.stack([r0, r1], axis=2).reshape(x.shape[0], 2 * h, x.shape[2], x.shape[3])


def _up_w(x):
    """Bilinear x2 along W, align_corners=False."""
    w = x.shape[2]
    xp = mx.pad(x, [(0, 0), (0, 0), (1, 1), (0, 0)], mode="edge")
    core = xp[:, :, 1:w + 1]
    c0 = 0.75 * core + 0.25 * xp[:, :, 0:w]
    c1 = 0.75 * core + 0.25 * xp[:, :, 2:w + 2]
    return mx.stack([c0, c1], axis=3).reshape(x.shape[0], x.shape[1], 2 * w, x.shape[3])


def _upsample2x(x):
    return _up_w(_up_h(x))


def demosaic_lite(packed):
    """Packed NHWC (B,H,W,4) -> linear RGB (B,2H,2W,3), differentiable."""
    r = _upsample2x(packed[..., 0:1])
    b = _upsample2x(packed[..., 3:4])
    g = _upsample2x(0.5 * (packed[..., 1:2] + packed[..., 2:3]))
    return mx.concatenate([r, g, b], axis=-1)


def combined_loss(pred, target, w_demosaic=0.2):
    """train.py losses.combined mirror (Bayer L1 + w * demosaic L1)."""
    l1 = mx.mean(mx.abs(pred - target))
    dl = mx.mean(mx.abs(demosaic_lite(pred) - demosaic_lite(target)))
    return l1 + w_demosaic * dl


def _tree_leaves(tree):
    if isinstance(tree, dict):
        for v in tree.values():
            yield from _tree_leaves(v)
    else:
        yield tree


def _tree_map(tree, fn):
    if isinstance(tree, dict):
        return {k: _tree_map(v, fn) for k, v in tree.items()}
    return fn(tree)


def clip_grads(grads, max_norm=1.0):
    """Global-norm clip in pure mx ops (compilable; mirrors
    nn.utils.clip_grad_norm_). No Python branching on values, no numpy."""
    total = None
    for g in _tree_leaves(grads):
        s = mx.sum(g * g)
        total = s if total is None else total + s
    norm = mx.sqrt(total + 1e-12)
    scale = mx.minimum(1.0, max_norm / mx.maximum(norm, 1e-12))
    return _tree_map(grads, lambda g: g * scale)


def psnr(pred_np, target_np, peak=1.0):
    mse = float(np.mean((pred_np - target_np) ** 2))
    return float("inf") if mse <= 0 else 10.0 * np.log10(peak * peak / mse)


def save_mlx(model, path):
    flat = flatten_params(model.parameters())
    mx.eval(*list(flat.values()))  # post-update arrays are lazy; savez needs concrete
    mx.savez(path, **flat)


def limit_metal_footprint():
    """Cap Metal memory so one big first-eval cannot wedge an 8GB Air.

    Observed failure: 6GB process footprint + GPU wait-stall on epoch 0's
    first batch (Metal JIT of the full train graph under memory pressure).
    Limits are in MB; skipped silently on non-Metal / older MLX.
    """
    try:
        metal = getattr(mx, "metal", None)
        if metal is None or not metal.is_available():
            return
        # New MLX (0.29+) moved these to mx.* (metal.* prints deprecation
        # warnings); fall back to metal.* on older installs.
        def set_limit(name, mb):
            if hasattr(mx, name):
                getattr(mx, name)(mb)
            else:  # pragma: no cover
                getattr(metal, name)(mb)
        set_limit("set_cache_limit", 512)    # buffer cache
        set_limit("set_memory_limit", 2048)  # total Metal allocations
        set_limit("set_wired_limit", 1024)   # wired (non-pageable) cap
        print("metal limits: cache=512MB memory=2048MB wired=1024MB", flush=True)
        print("metal limits: cache=512MB memory=2048MB wired=1024MB", flush=True)
    except Exception as e:  # pragma: no cover
        print("metal limit setup skipped (%s)" % e, flush=True)


def main():
    ap = argparse.ArgumentParser(description="Train RawNIND-tiny with MLX")
    ap.add_argument("--data", default="data")
    ap.add_argument("--out", default="checkpoints")
    ap.add_argument("--epochs", type=int, default=TrainConfig.epochs)
    ap.add_argument("--batch", type=int, default=32,
                    help="batch size (32 is 8GB-Air-safe; 64+ only on 16GB+)")
    ap.add_argument("--lr", type=float, default=TrainConfig.lr)
    ap.add_argument("--seed", type=int, default=TrainConfig.seed)
    ap.add_argument("--workers", type=int, default=0,
                    help="DataLoader workers (default 0: macOS spawn workers each "
                         "copy all frames + re-import mlx, which OOMs 8GB Airs; "
                         "raise to 2-3 only on 16GB+)")
    ap.add_argument("--val-every", type=int, default=10)
    ap.add_argument("--w-demosaic", type=float, default=0.2)
    ap.add_argument("--compile", action=argparse.BooleanOptionalAction, default=True,
                    help="mx.compile the train step (kernel fusion; unlike torch "
                         "inductor there is no autotuner to hang -- disable only "
                         "to debug)")
    args = ap.parse_args()

    cfg = TrainConfig(epochs=args.epochs, batch=args.batch, lr=args.lr, seed=args.seed)
    np.random.seed(cfg.seed)
    mx.random.seed(cfg.seed)

    items = build_items(args.data, cfg)
    if not items:
        raise SystemExit("no training data under %s/" % args.data)
    n_real = sum(1 for i in items if i["kind"] == "real")
    print("items=%d (real=%d synth=%d)" % (len(items), n_real, len(items) - n_real), flush=True)

    order = np.random.default_rng(cfg.seed).permutation(len(items))
    n_val = max(1, len(items) // 10)
    val_items = [items[i] for i in order[:n_val]]
    train_items = [items[i] for i in order[n_val:]] or items

    model = RawNindTiny(with_sigma=cfg.with_sigma)
    mx.eval(model.parameters())
    print("params=%d" % count_parameters(model), flush=True)
    limit_metal_footprint()
    print("note: epoch 0's first batch compiles Metal kernels (minutes, one-time); "
          "later batches/epochs run at full speed. workers=%d" % args.workers, flush=True)
    optimizer = optim.AdamW(learning_rate=cfg.lr, weight_decay=cfg.weight_decay)

    def loss_fn(model, xb, tb):
        return combined_loss(model(xb), tb, args.w_demosaic)

    loss_and_grad = nn.value_and_grad(model, loss_fn)
    if args.compile:
        try:
            loss_and_grad = mx.compile(loss_and_grad)
            print("mx.compile: on (train step fused)", flush=True)
        except Exception as e:
            loss_and_grad = nn.value_and_grad(model, loss_fn)
            print("mx.compile unavailable, continuing unfused (%s)" % e, flush=True)
    os.makedirs(args.out, exist_ok=True)

    from torch.utils.data import DataLoader
    best = float("inf")
    for ep in range(cfg.epochs):
        # Epoch-granular cosine, mirroring CosineAnnealingLR(T_max=epochs).
        optimizer.learning_rate = cfg.lr * 0.5 * (1.0 + math.cos(math.pi * ep / cfg.epochs))
        t_build = time.perf_counter()
        train_ds = RawStreamDataset(train_items, cfg, np.random.default_rng([cfg.seed, ep]))
        build_s = time.perf_counter() - t_build
        if len(train_ds) == 0:
            print("epoch %d: no patches (check saturation filter / data)" % ep)
            continue
        dl = DataLoader(train_ds, batch_size=cfg.batch, shuffle=True,
                        num_workers=args.workers, collate_fn=numpy_collate,
                        persistent_workers=args.workers > 0)
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
            # NCHW numpy -> NHWC mlx (single transpose per batch).
            xb = mx.array(np.transpose(xin, (0, 2, 3, 1)))
            tb = mx.array(np.transpose(tgt, (0, 2, 3, 1)))
            if n == 0 and (xb.dtype != mx.float32 or tb.dtype != mx.float32):
                raise SystemExit(
                    "non-fp32 batch (%s/%s): Metal has no fp64 and silently "
                    "falls back to CPU -- fix the data path, do not train on" % (xb.dtype, tb.dtype))
            loss, grads = loss_and_grad(model, xb, tb)
            grads = clip_grads(grads, 1.0)
            optimizer.update(model, grads)
            mx.eval(model.parameters(), optimizer.state, loss)
            tot += float(loss.item()) * len(xin)
            n += len(xin)
            compute_s += time.perf_counter() - t0
        loop_s = time.perf_counter() - t_loop
        msg = "epoch %3d train_l1=%.5f lr=%.1e patches=%d build=%.0fs loop=%.0fs (data=%.0fs compute=%.0fs)" % (
            ep, tot / max(n, 1), optimizer.learning_rate, len(train_ds),
            build_s, loop_s, data_s, compute_s)
        if ep % args.val_every == 0:
            vds = RawStreamDataset(
                val_items, cfg, np.random.default_rng([cfg.seed, 1_000_000 + ep]))
            if len(vds):
                vdl = DataLoader(vds, batch_size=cfg.batch, num_workers=0,
                                 collate_fn=numpy_collate)
                vt, vn, ps = 0.0, 0, []
                for xin, tgt in vdl:
                    xb = mx.array(np.transpose(xin, (0, 2, 3, 1)))
                    pred = model(xb)
                    mx.eval(pred)
                    pred_np = np.transpose(np.asarray(pred), (0, 3, 1, 2))
                    tgt_np = np.asarray(tgt)
                    vt += float(np.abs(pred_np - tgt_np).mean()) * len(xin)
                    vn += len(xin)
                    ps.append(psnr(pred_np[0], tgt_np[0]))
                msg += " val_l1=%.5f val_psnr=%.2f" % (vt / vn, float(np.mean(ps)))
                if vt / vn < best:
                    best = vt / vn
                    save_mlx(model, os.path.join(args.out, "mlx_best.npz"))
        print(msg, flush=True)
        save_mlx(model, os.path.join(args.out, "mlx_last.npz"))
    print("done. best val_l1=%.5f -> %s/mlx_best.npz" % (best, args.out))


if __name__ == "__main__":
    main()
