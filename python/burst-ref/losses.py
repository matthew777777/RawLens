"""Training/eval losses (DBSR Sec 5.2 + FBAnet Sec 4.4, numpy reference).

1. aligned_l1(pred, gt, flow, ...): DBSR real-data loss.
     pred/hr prediction (H, W, 3), gt ground truth (H, W, 3),
     flow (H, W, 2) maps prediction coords -> gt coords (PWC-Net estimate).
     Steps: warp pred to gt coords (bilinear), estimate a global 3x3 color
     matrix C from Gaussian-smoothed (burst, gt) pair by least squares,
     apply C, then masked L1. The mask drops pixels where the smoothed
     burst-vs-gt residual exceeds `mask_thresh` (misaligned regions).
2. cobi_lite(...): contextual bilateral lite for RAW training (FBAnet cites
     CoBi, Zhang et al.): per-pixel L1 to the best-matching gt pixel inside a
     small window + spatial penalty. Robust to +/-2px misalignment.
3. gw_l1(...): gradient-weighted L1 for RGB training (FBAnet cites CDC):
     weights = 1 + gw_k * normalized gradient magnitude of gt; reconstructs
     high-frequency regions preferentially.

All ops are numpy so the torch trainer (future) and this spec agree; the
Kotlin side reuses the mask/color convention in SingleCompare-style eval.
"""
import numpy as np


def bilinear_sample(img, xs, ys):
    """Sample img (H, W, C) at float coords xs, ys (clamped)."""
    h, w = img.shape[:2]
    xs = np.clip(xs, 0, w - 1)
    ys = np.clip(ys, 0, h - 1)
    x0 = np.floor(xs).astype(int)
    y0 = np.floor(ys).astype(int)
    x1 = np.minimum(x0 + 1, w - 1)
    y1 = np.minimum(y0 + 1, h - 1)
    fx = (xs - x0)[..., None]
    fy = (ys - y0)[..., None]
    top = img[y0, x0] * (1 - fx) + img[y0, x1] * fx
    bot = img[y1, x0] * (1 - fx) + img[y1, x1] * fx
    return top * (1 - fy) + bot * fy


def warp_to_gt(pred, flow):
    """Warp pred into gt coordinates with flow (H, W, 2), dx then dy."""
    h, w = pred.shape[:2]
    ys, xs = np.mgrid[0:h, 0:w]
    return bilinear_sample(pred, xs + flow[..., 0], ys + flow[..., 1])


def gaussian_blur(img, radius=2):
    """Separable box-blur stand-in for Gaussian smoothing (DBSR Sec 5.2)."""
    k = 2 * radius + 1
    pad = np.pad(img, ((radius, radius), (radius, radius), (0, 0)), mode="edge")
    acc = np.zeros_like(img)
    for dy in range(k):
        for dx in range(k):
            acc += pad[dy:dy + img.shape[0], dx:dx + img.shape[1]]
    return acc / (k * k)


def estimate_color_matrix(src, dst):
    """Least-squares 3x3 matrix C with dst ~= src @ C.T (DBSR Sec 5.2)."""
    a = src.reshape(-1, 3)
    b = dst.reshape(-1, 3)
    c, _, _, _ = np.linalg.lstsq(a, b, rcond=None)
    return c.T.astype(np.float32)  # apply as img @ C.T


def aligned_l1(pred, gt, flow, burst_ref, mask_thresh=0.1):
    """DBSR Eq.3: masked L1 after flow warp + global color mapping.

    burst_ref: smoothed base-frame burst image at pred resolution, used for
    the validity mask R = ||gt_smooth - C(burst_smooth)||_2 (upsampled here
    by construction since inputs share resolution).
    Returns (loss, mask_fraction_kept, color_matrix).
    """
    pred_w = warp_to_gt(pred, flow)
    c = estimate_color_matrix(gaussian_blur(burst_ref), gaussian_blur(gt))
    pred_c = np.clip(pred_w.reshape(-1, 3) @ c.T, 0, None).reshape(pred.shape)
    r = np.linalg.norm(gaussian_blur(gt) - gaussian_blur(burst_ref) @ c.T,
                       axis=-1)
    mask = (r < mask_thresh).astype(np.float32)
    kept = float(mask.mean())
    if kept <= 0:
        return float("inf"), 0.0, c
    loss = float((np.abs(pred_c - gt).mean(axis=-1) * mask).sum() / mask.sum())
    return loss, kept, c


def cobi_lite(pred, gt, window=5, spatial_w=0.1):
    """Contextual-bilateral-lite: min over window of L1 + spatial penalty."""
    h, w = pred.shape[:2]
    r = window // 2
    gt_p = np.pad(gt, ((r, r), (r, r), (0, 0)), mode="edge")
    best = np.full((h, w), np.inf)
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            cand = gt_p[r + dy:r + dy + h, r + dx:r + dx + w]
            d = np.abs(pred - cand).mean(axis=-1) + spatial_w * (dx * dx + dy * dy) / (r * r + 1)
            best = np.minimum(best, d)
    return float(best.mean())


def gw_l1(pred, gt, gw_k=2.0):
    """Gradient-weighted L1 (CDC/FBAnet RGB): emphasize textured regions."""
    gx = np.abs(np.diff(gt, axis=1, prepend=gt[:, :1]))
    gy = np.abs(np.diff(gt, axis=0, prepend=gt[:1]))
    g = (gx.mean(axis=-1) + gy.mean(axis=-1)) / 2.0
    g = g / (g.max() + 1e-8)
    w = 1.0 + gw_k * g
    return float((np.abs(pred - gt).mean(axis=-1) * w).mean() / w.mean())


def _self_test():
    rng = np.random.default_rng(5)
    gt = rng.random((24, 24, 3)).astype(np.float32)
    # Perfect prediction, zero flow: loss ~0, mask fully kept, C ~= I.
    loss, kept, c = aligned_l1(gt, gt, np.zeros((24, 24, 2), np.float32), gt)
    assert loss < 1e-4, loss
    assert kept == 1.0, kept
    assert np.abs(c - np.eye(3)).max() < 1e-3, c
    # Shifted prediction with compensating flow still scores ~0.
    flow = np.zeros((24, 24, 2), np.float32)
    flow[..., 0] = 2.0
    pred = bilinear_sample(gt, *np.meshgrid(np.arange(24) - 2.0, np.arange(24)))
    loss2, _, _ = aligned_l1(pred, gt, flow, gt)
    # Interior is exact; the 2 clamped border columns carry edge error.
    interior = np.abs(warp_to_gt(pred, flow)[:, :-2] - gt[:, :-2]).max()
    assert interior < 1e-5, interior
    assert loss2 < 0.05, loss2
    # CoBi tolerates a 1px shift better than strict L1.
    shifted = np.roll(gt, 1, axis=1)
    strict = float(np.abs(shifted - gt).mean())
    assert cobi_lite(shifted, gt) < strict
    # GW-L1 equals L1 on flat content, exceeds it on edges.
    flat = np.full((16, 16, 3), 0.5, np.float32)
    assert abs(gw_l1(flat + 0.01, flat) - 0.01) < 1e-6
    edge = flat.copy()
    edge[:, 8:] = 0.9
    # Error concentrated on the edge column must weigh more than the same
    # error mass spread uniformly (weight normalization cancels uniform err).
    on_edge = edge.copy()
    on_edge[:, 8] += 0.08
    spread = edge.copy() + 0.005
    assert gw_l1(on_edge, edge) > gw_l1(spread, edge), (
        gw_l1(on_edge, edge), gw_l1(spread, edge))
    print("losses.py self-test ok")


if __name__ == "__main__":
    _self_test()
