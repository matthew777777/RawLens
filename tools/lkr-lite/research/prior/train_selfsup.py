# SPDX-License-Identifier: Apache-2.0
"""Fine-tune the post-fit prior on REAL bursts (self-supervised, held-out frame) + synthetic pairs.

Per step:
  1. real tile (gen_selfsup.sh): the network corrects a window of Lite's merge (frame k left out); its
     output is re-sampled at frame k's positions / Bayer pattern (selfsup.project) and compared with frame
     k's raw samples. Weighted to edges / lines (where the zipper lives: Noise2Noise would otherwise learn
     to denoise = remove grain); samples Lite's own merge cannot explain (> 6 sigma: motion, occlusion) out.
  2. grain keep: on flat parts of that window the fine-scale output must equal the input.
  3. synthetic grain-preserving pairs (train.py batch), as before.

  train_selfsup.py REAL_DIR SYN_PAIRS INIT.pt OUT.pt [--steps 4000] [--exclude IMG_20260919_184641_979]
"""
import argparse, glob, os, sys, time
import numpy as np
import torch
import torch.nn.functional as F
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from train import Prior, load_pairs, batch, highpass
from selfsup import load_trace, sample_positions, project


def load_real(d, exclude):
    out = []
    for f in sorted(glob.glob(os.path.join(d, '*_tile_0_0-holdout-raw.f32'))):
        pre = f[:-len('-holdout-raw.f32')]
        if any(e and e in os.path.basename(pre) for e in exclude): continue
        if not os.path.exists(pre + '-hybrid.f32'): continue
        t = load_trace(pre)
        pos, ch = sample_positions(t)
        na = np.fromfile(pre + '-adjoint-push.bin', np.float32)[8] if os.path.exists(pre + '-adjoint-push.bin') else None
        out.append(dict(z=t['z'], raw=t['raw'], pos=pos, ch=ch, h=t['h'], w=t['w'], s=t['s'], name=os.path.basename(pre), noise_a=na))
    return out


def real_step(m, r, rng, dev, win=64, a_default=2e-4):
    """One real-tile loss. win = LR window side. Returns (selfsup loss, grain loss)."""
    h, w, s = r['h'], r['w'], r['s']; H, W = s * h, s * w
    i0 = int(rng.integers(8, h - win - 8)) // 2 * 2; j0 = int(rng.integers(8, w - win - 8)) // 2 * 2
    pos = r['pos'][:, i0:i0 + win, j0:j0 + win].to(dev); ch = r['ch'][i0:i0 + win, j0:j0 + win].to(dev)
    y = torch.from_numpy(np.ascontiguousarray(r['raw'][i0:i0 + win, j0:j0 + win])).to(dev)
    # HR window covering every warped sample (+ margin for the 13 px receptive field and bilinear taps)
    x0 = int(max(0, np.floor(pos[..., 0].min().item()) - 16)); x1 = int(min(W, np.ceil(pos[..., 0].max().item()) + 16))
    y0 = int(max(0, np.floor(pos[..., 1].min().item()) - 16)); y1 = int(min(H, np.ceil(pos[..., 1].max().item()) + 16))
    if x1 - x0 < 32 or y1 - y0 < 32: return None
    zw = torch.from_numpy(np.ascontiguousarray(r['z'][:, y0:y1, x0:x1])).to(dev)
    a = float(r['noise_a']) if r['noise_a'] is not None else a_default
    xin = torch.sqrt(zw.clamp(min=0))[None]
    sig = torch.full((1, 1, y1 - y0, x1 - x0), float(np.sqrt(a * 0.18)), device=dev)
    out = m(xin, sig)[0].clamp(min=0) ** 2
    p = pos.clone(); p[..., 0] -= x0; p[..., 1] -= y0
    pred, ok = project(out, p, ch, y1 - y0, x1 - x0)
    with torch.no_grad():
        pl, _ = project(zw, p, ch, y1 - y0, x1 - x0)
        var = (a * y.clamp(min=0) + a * 0.05).clamp(min=1e-8)
        consistent = (pl - y).abs() < 6 * var.sqrt()
        # edge / line weight from Lite's own image at the samples (gradient energy, normalised)
        lum = zw.mean(0, keepdim=True)[None]
        gx = lum[..., :, 2:] - lum[..., :, :-2]; gy = lum[..., 2:, :] - lum[..., :-2, :]
        gm = F.pad(gx.abs(), (1, 1, 0, 0))[0, 0] + F.pad(gy.abs(), (0, 0, 1, 1))[0, 0]
        gm = F.avg_pool2d(gm[None, None], 5, 1, 2)[0, 0]
        ge = project(gm[None].expand(3, -1, -1), p, ch, y1 - y0, x1 - x0)[0]
        wgt = (ge / (ge.mean() + 1e-8)).clamp(0, 4) ** 2
        wgt = wgt * (ok & consistent).float()
    lss = ((torch.sqrt((pred - y) ** 2 / var + 1e-3) - np.sqrt(1e-3)) * wgt).sum() / wgt.sum().clamp(min=1)
    # grain keep: fine scale unchanged where the image is flat (low gradient)
    flat = (gm < torch.quantile(gm.flatten()[::7], 0.4)).float()[None]
    hi_o = highpass(torch.sqrt(out.clamp(min=0))[None]); hi_i = highpass(xin)
    lgr = (((hi_o - hi_i) ** 2).mean(1) * flat).sum() / flat.sum().clamp(min=1) / max(a * 0.18, 1e-8) * 0.25
    return lss, lgr


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('real'); ap.add_argument('syn'); ap.add_argument('init'); ap.add_argument('out')
    ap.add_argument('--steps', type=int, default=4000); ap.add_argument('--lr', type=float, default=1e-4)
    ap.add_argument('--exclude', default='IMG_20260919_184641_979'); ap.add_argument('--w_real', type=float, default=0.02)
    ap.add_argument('--w_grain', type=float, default=1.0); ap.add_argument('--real_per_step', type=int, default=4)
    a = ap.parse_args()
    dev = 'mps' if torch.backends.mps.is_available() else 'cpu'
    ck = torch.load(a.init, map_location='cpu'); m = Prior(ck['ch'], ck['layers'], ck.get('cond', 1)); m.load_state_dict(ck['state']); m.to(dev)
    real = load_real(a.real, a.exclude.split(','))
    syn = load_pairs(a.syn); val, tr = syn[:10], syn[10:]
    print(f'{len(real)} real tiles (excluded: {a.exclude}), {len(tr)} synthetic pairs, device {dev}', flush=True)
    opt = torch.optim.Adam(m.parameters(), lr=a.lr)
    rng = np.random.default_rng(0); vrng = np.random.default_rng(123)
    vb = [batch(val, vrng, 16, 256, dev) for _ in range(4)]
    t0 = time.time(); acc = np.zeros(3)
    for it in range(1, a.steps + 1):
        x, y, s = batch(tr, rng, 16, 128, dev)
        o = m(x, s)
        loss_syn = F.l1_loss(o, y) + 2.0 * F.l1_loss(highpass(o), highpass(y))
        lss = lgr = torch.zeros((), device=dev); n = 0
        for _ in range(a.real_per_step):
            r = real[rng.integers(len(real))]
            res = real_step(m, r, rng, dev)
            if res is None: continue
            lss = lss + res[0]; lgr = lgr + res[1]; n += 1
        if n: lss, lgr = lss / n, lgr / n
        loss = loss_syn + a.w_real * lss + a.w_real * a.w_grain * lgr
        opt.zero_grad(); loss.backward(); torch.nn.utils.clip_grad_norm_(m.parameters(), 1.0); opt.step()
        acc += [loss_syn.item(), lss.item(), lgr.item()]
        if it % 250 == 0 or it == a.steps:
            with torch.no_grad():
                vin = sum(F.l1_loss(x, y).item() for x, y, s in vb) / len(vb); vout = sum(F.l1_loss(m(x, s), y).item() for x, y, s in vb) / len(vb)
            print(f'step {it} syn {acc[0] / 250:.5f} real {acc[1] / 250:.4f} grain {acc[2] / 250:.4f} | synthetic val L1 Lite {vin:.5f} -> {vout:.5f} | {time.time() - t0:.0f} s', flush=True)
            acc[:] = 0
            torch.save({'state': {k: v.cpu() for k, v in m.state_dict().items()}, 'ch': ck['ch'], 'layers': ck['layers']}, a.out)


if __name__ == '__main__':
    main()
