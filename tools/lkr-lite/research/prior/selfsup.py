# SPDX-License-Identifier: Apache-2.0
"""Self-supervised data and forward model for the LK-R Lite post-fit prior on REAL bursts.

Lite is run with --holdout k and LKRL_TRACE_PREFIX: the merge leaves frame k out, and the adapter saves
that frame's raw tile + motion (holdout-raw / -affine / -local / -meta). project() re-samples an HR image
at the held-out frame's sample positions and Bayer pattern exactly like Lite's forward model
(lite_common.glsl: u = subpixel centre, q = s (A u + t + d(u)), d bilinear between 16 px block centres,
bilinear HR taps with pixel centres at +0.5, average of the s x s sub-samples) -- differentiable (torch).
"""
import os
import numpy as np
import torch
import torch.nn.functional as F


def load_trace(pre):
    """pre = TRACE_PREFIX + '_tile_0_0'. Returns dict with z (3,H,W), raw (h,w), A (6,), D (nby,nbx,2) or None."""
    h, w, s, LB = np.fromfile(pre + '-holdout-meta.i32', np.int32)
    H, W = s * h, s * w
    z = np.fromfile(pre + '-hybrid.f32', np.float32).reshape(3, H, W)
    raw = np.fromfile(pre + '-holdout-raw.f32', np.float32).reshape(h, w)
    A = np.fromfile(pre + '-holdout-affine.f32', np.float32)
    D = None
    if LB and os.path.exists(pre + '-holdout-local.f32'):
        nbx, nby = (w + LB - 1) // LB, (h + LB - 1) // LB
        D = np.fromfile(pre + '-holdout-local.f32', np.float32).reshape(nby, nbx, 2)
    return dict(z=z, raw=raw, A=A, D=D, h=int(h), w=int(w), s=int(s), LB=int(LB))


def sample_positions(t, device='cpu'):
    """HR coordinates (x, y) of every sub-sample of the held-out frame: (s*s, h, w, 2), plus Bayer channel (h, w)."""
    h, w, s, LB = t['h'], t['w'], t['s'], t['LB']
    A = torch.tensor(t['A'], dtype=torch.float64, device=device)
    ii, jj = torch.meshgrid(torch.arange(h, dtype=torch.float64, device=device), torch.arange(w, dtype=torch.float64, device=device), indexing='ij')
    pos = []
    for oy in range(s):
        for ox in range(s):
            ux = jj + (ox + 0.5) / s; uy = ii + (oy + 0.5) / s
            qx = A[0] * ux + A[1] * uy + A[2]; qy = A[3] * ux + A[4] * uy + A[5]
            if t['D'] is not None:
                D = torch.tensor(t['D'], dtype=torch.float64, device=device)
                nby, nbx = D.shape[:2]
                cx = ux / LB - 0.5; cy = uy / LB - 0.5
                fx0 = torch.floor(cx); fy0 = torch.floor(cy); fx = cx - fx0; fy = cy - fy0
                x0 = fx0.long().clamp(0, nbx - 1); x1 = (fx0.long() + 1).clamp(0, nbx - 1)
                y0 = fy0.long().clamp(0, nby - 1); y1 = (fy0.long() + 1).clamp(0, nby - 1)
                def g(yy, xx, c): return D[yy, xx, c]
                for c, tgt in ((0, 'x'), (1, 'y')):
                    a = g(y0, x0, c) + (g(y0, x1, c) - g(y0, x0, c)) * fx
                    b = g(y1, x0, c) + (g(y1, x1, c) - g(y1, x0, c)) * fx
                    d = a + (b - a) * fy
                    if tgt == 'x': qx = qx + d
                    else: qy = qy + d
            pos.append(torch.stack([s * qx, s * qy], -1))
    ch = torch.full((h, w), 1, dtype=torch.long, device=device)
    ev_i = (torch.arange(h, device=device) % 2 == 0)[:, None]; ev_j = (torch.arange(w, device=device) % 2 == 0)[None, :]
    ch[ev_i & ev_j] = 0; ch[(~ev_i) & (~ev_j)] = 2
    return torch.stack(pos).float(), ch


def project(z, pos, ch, H, W):
    """z: (3, H, W) HR image (torch, differentiable). Returns predicted raw (h, w) and validity mask."""
    S2, h, w, _ = pos.shape
    grid = torch.stack([2 * pos[..., 0] / W - 1, 2 * pos[..., 1] / H - 1], -1)          # align_corners=False: centres at +0.5
    smp = F.grid_sample(z[None].expand(S2, -1, -1, -1) if z.dim() == 3 else z, grid, mode='bilinear', align_corners=False)  # (S2,3,h,w)
    pred = smp.mean(0)                                                                    # (3, h, w)
    pred = torch.gather(pred, 0, ch[None]).squeeze(0)
    inside = ((pos[..., 0] >= 1) & (pos[..., 0] <= W - 1) & (pos[..., 1] >= 1) & (pos[..., 1] <= H - 1)).all(0)
    return pred, inside


if __name__ == '__main__':   # check: predict the held-out frame from Lite's merge
    import sys
    t = load_trace(sys.argv[1])
    pos, ch = sample_positions(t)
    H, W = t['s'] * t['h'], t['s'] * t['w']
    pred, ok = project(torch.from_numpy(t['z']), pos, ch, H, W)
    y = torch.from_numpy(t['raw'])
    r = (pred - y)[ok].numpy(); yv = y[ok].numpy()
    k = (pred[ok] * y[ok]).sum() / (pred[ok] ** 2).sum()
    print('held-out prediction: gain %.4f  residual rms %.5f  mean |r| %.5f  signal mean %.4f  usable %.1f %%' % (
        k.item(), np.sqrt((r ** 2).mean()), np.abs(r).mean(), yv.mean(), 100 * ok.float().mean().item()))
    for dx in (-0.5, 0.0, 0.5):   # sanity: residual should be smallest at zero extra shift
        p2 = pos.clone(); p2[..., 0] += dx
        pr, ok2 = project(torch.from_numpy(t['z']), p2, ch, H, W)
        print('  extra shift x %+.1f HR px: residual rms %.5f' % (dx, ((pr - y)[ok2] ** 2).mean().sqrt().item()))
