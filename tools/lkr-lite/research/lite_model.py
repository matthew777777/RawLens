# SPDX-License-Identifier: Apache-2.0
"""LK-R Lite research model: LK-R's exact forward operator as a sparse matrix,
fed from an LK-R solver trace (LKR_TRACE_PREFIX), so cheap solver variants can
be compared with LK-R's final image on identical inputs.

Conventions are those of tools/lkr-pipeline/src/main/cpp/shaders/lkr_common.glsl.
"""
import numpy as np
import scipy.sparse as sp


def load_trace(prefix):
    meta = {}
    for tok in open(prefix + "_meta.txt").read().split():
        k, v = tok.split("=")
        meta[k] = [float(x) for x in v.split(",")] if "," in v else float(v)
    K, h, w, H, W = (int(meta[k]) for k in ("K", "h", "w", "H", "W"))
    f = lambda n: np.fromfile(prefix + "_" + n + ".f32", np.float32)
    nb = ((h + 15) // 16) * ((w + 15) // 16)
    t = dict(meta=meta, K=K, h=h, w=w, H=H, W=W, s=H // h,
             y=f("frames").reshape(-1, h, w)[:K],
             z_ref=f("final_z").reshape(3, H, W),
             look_ref=f("final_look"),
             z0=f("reference_rgb").reshape(3, H, W),
             p0=f("motion_initial").reshape(K, 6), p1=f("motion_final").reshape(-1, 6)[:K],
             d0=f("local_initial").reshape(-1, nb, 2)[:K], d1=f("local_final").reshape(-1, nb, 2)[:K],
             wt=f("weights_final").reshape(-1, h, w)[:K])
    return t


def bayer(h, w):
    i, j = np.mgrid[0:h, 0:w]
    ei, ej = (i & 1) == 0, (j & 1) == 0
    return np.where(ei & ej, 0, np.where(~ei & ~ej, 2, 1))


def forward_matrix(t, p, d, LB=16):
    """U (K*h*w x 3*H*W) and per-row validity, exactly like resid_adj_w.comp."""
    K, h, w, H, W, s = t["K"], t["h"], t["w"], t["H"], t["W"], t["s"]
    nbx = (w + LB - 1) // LB
    ii, jj = np.mgrid[0:h, 0:w]
    c = bayer(h, w)
    rows, cols, vals = [], [], []
    valid = np.ones((K, h, w), bool)
    for k in range(K):
        a = p[k].astype(np.float32)
        rk, ck, vk = [], [], []
        ok = np.ones((h, w), bool)
        for oy in range(s):
            for ox in range(s):
                ux = (jj + (ox + .5) / s).astype(np.float32)
                uy = (ii + (oy + .5) / s).astype(np.float32)
                bx = np.clip(ux.astype(int) // LB, 0, nbx - 1)
                by = np.clip(uy.astype(int) // LB, 0, (h + LB - 1) // LB - 1)
                dl = d[k][by * nbx + bx]
                qx = np.float32(s) * (a[0] * ux + a[1] * uy + a[2] + dl[..., 0])
                qy = np.float32(s) * (a[3] * ux + a[4] * uy + a[5] + dl[..., 1])
                fx, fy = qx - .5, qy - .5
                x0, y0 = np.floor(fx).astype(int), np.floor(fy).astype(int)
                ax, ay = fx - x0, fy - y0
                ok &= (x0 >= 0) & (y0 >= 0) & (x0 + 1 < W) & (y0 + 1 < H)
                x0c, y0c = np.clip(x0, 0, W - 2), np.clip(y0, 0, H - 2)
                base = c * H * W + y0c * W + x0c
                for dy, dx, wgt in ((0, 0, (1 - ax) * (1 - ay)), (0, 1, ax * (1 - ay)),
                                    (1, 0, (1 - ax) * ay), (1, 1, ax * ay)):
                    rk.append((k * h + ii) * w + jj); ck.append(base + dy * W + dx); vk.append(wgt / (s * s))
        valid[k] = ok
        for r_, c_, v_ in zip(rk, ck, vk):
            m = ok.ravel()
            rows.append(r_.ravel()[m]); cols.append(c_.ravel()[m]); vals.append(v_.ravel()[m])
    U = sp.csr_matrix((np.concatenate(vals).astype(np.float64),
                       (np.concatenate(rows), np.concatenate(cols))), shape=(K * h * w, 3 * H * W))
    return U, valid


def psnr(a, b, border=16, peak=1.0):
    a = a[:, border:-border, border:-border]; b = b[:, border:-border, border:-border]
    return 10 * np.log10(peak ** 2 / np.mean((a - b) ** 2))


def weights(t, U, valid, y, z, rob_k, rob_pow, tau=0.1, spread=3):
    """One robust-weight pass vs estimate z (weight_e + weight + weight_min of LK-R, once)."""
    from scipy.ndimage import uniform_filter, minimum_filter
    K, h, w, m = t["K"], t["h"], t["w"], t["meta"]
    a, b = m["a"], m["b"]
    Y = y.reshape(K, h, w)
    r = (U @ z - y).reshape(K, h, w)
    sh = lambda A, di, dj: np.take(np.take(A, np.clip(np.arange(h) + di, 0, h - 1), 1), np.clip(np.arange(w) + dj, 0, w - 1), 2)
    gx = (sh(Y, 0, 2) - sh(Y, 0, -2)) * .25
    gy = (sh(Y, 2, 0) - sh(Y, -2, 0)) * .25
    st = np.stack([sh(Y, di, dj) for di in (-2, 0, 2) for dj in (-2, 0, 2)])
    rg = (st.max(0) - st.min(0)) * .25
    var = np.maximum(a * np.maximum(Y, 0) + b + tau * tau * np.maximum(gx * gx + gy * gy, rg * rg), 1e-12)
    e = r * r / var
    e = uniform_filter(e, size=(1, 3, 3), mode="nearest")
    wt = 1 / (1 + (e / rob_k ** 2) ** max(rob_pow, 1))
    if spread:
        wt = minimum_filter(wt, size=(1, 2 * spread + 1, 2 * spread + 1), mode="nearest")
    wt[0] = 1
    return (wt * valid * (Y < m["clip"])).ravel()


def jacobi(U, W, y, z, iters, omega=1.0):
    D = np.maximum(U.T @ W, 1e-6)
    for _ in range(iters):
        z = z - omega * (U.T @ (W * (U @ z - y))) / D
    return z


def gt_align(z, gt_hwc, s, blur=0.3):
    """Ground truth at z's grid: lens blur, scale, and the shift that best fits z (fixed per case)."""
    from scipy.ndimage import gaussian_filter, fourier_shift
    g = np.stack([gaussian_filter(gt_hwc[..., c], 2 * blur) for c in range(3)])
    if s == 1:
        g = g.reshape(3, g.shape[1] // 2, 2, g.shape[2] // 2, 2).mean((2, 4))
    a = z[1] - gaussian_filter(z[1], 2); b = g[1] - gaussian_filter(g[1], 2)
    X = np.fft.fftshift(np.fft.ifft2(np.fft.fft2(a) * np.conj(np.fft.fft2(b))).real)
    cy, cx = np.unravel_index(np.argmax(X), X.shape)
    par = lambda m, c, p: 0.5 * (m - p) / (m - 2 * c + p)
    sy = cy - X.shape[0] // 2 + par(X[cy - 1, cx], X[cy, cx], X[cy + 1, cx])
    sx = cx - X.shape[1] // 2 + par(X[cy, cx - 1], X[cy, cx], X[cy, cx + 1])
    return np.stack([np.fft.ifft2(fourier_shift(np.fft.fft2(g[c]), (sy, sx))).real for c in range(3)]), (sy, sx)


def gt_score(z, g, s, border=16):
    """PSNR (dB, peak = max gt) of z (3,H,W) vs aligned gt after one per-channel gain fit."""
    b = border * s
    zz, gg = z[:, b:-b, b:-b], g[:, b:-b, b:-b]
    m = gg.max(0) < 0.9
    e = np.stack([(zz[c][m] * gg[c][m]).sum() / (zz[c][m] ** 2).sum() * zz[c][m] - gg[c][m] for c in range(3)])
    return 10 * np.log10(gg[:, m].max() ** 2 / np.mean(e ** 2))


def gn_motion(t, z, y, W, p, d, lam_local=0.1, LB=16, local=True):
    """One Gauss-Newton step per frame of the affine p_k (then the 16-px block translations d_k)
    against the HR estimate z: J from HR gradients gathered by the forward model."""
    K, h, w, H, W_, s = t["K"], t["h"], t["w"], t["H"], t["W"], t["s"]
    Z = z.reshape(3, H, W_)
    gx = np.zeros_like(Z); gy = np.zeros_like(Z)
    gx[:, :, 1:-1] = (Z[:, :, 2:] - Z[:, :, :-2]) * .5
    gy[:, 1:-1] = (Z[:, 2:] - Z[:, :-2]) * .5
    U, valid = forward_matrix(t, p, d, LB)
    r = (y - U @ z).reshape(K, h, w)
    Gx = (U @ gx.ravel()).reshape(K, h, w) * s
    Gy = (U @ gy.ravel()).reshape(K, h, w) * s
    Wk = W.reshape(K, h, w) * valid
    ii, jj = np.mgrid[0:h, 0:w]
    ux, uy = jj + .5, ii + .5
    nbx, nby = (w + LB - 1) // LB, (h + LB - 1) // LB
    p = p.copy(); d = d.copy()
    var = t["meta"]["a"] * np.maximum(y.reshape(K, h, w), 0) + t["meta"]["b"]
    for k in range(1, K):
        wk = Wk[k] / var[k]
        J = np.stack([Gx[k] * ux, Gx[k] * uy, Gx[k], Gy[k] * ux, Gy[k] * uy, Gy[k]], -1).reshape(-1, 6)
        A = J.T @ (J * wk.reshape(-1, 1)); bvec = J.T @ (wk * r[k]).ravel()
        dp = np.linalg.solve(A + 1e-9 * np.trace(A) * np.eye(6), bvec)
        p[k] += dp
        if local:
            # residual after the affine update (linearised), then per-block translations
            rk = r[k] - (J @ dp).reshape(h, w)
            bi = np.minimum(ii // LB, nby - 1) * nbx + np.minimum(jj // LB, nbx - 1)
            sxx = np.bincount(bi.ravel(), (wk * Gx[k] ** 2).ravel(), nbx * nby)
            sxy = np.bincount(bi.ravel(), (wk * Gx[k] * Gy[k]).ravel(), nbx * nby)
            syy = np.bincount(bi.ravel(), (wk * Gy[k] ** 2).ravel(), nbx * nby)
            bx = np.bincount(bi.ravel(), (wk * Gx[k] * rk).ravel(), nbx * nby)
            by = np.bincount(bi.ravel(), (wk * Gy[k] * rk).ravel(), nbx * nby)
            # Tikhonov toward the current block offset, scaled like the block's own information
            reg = lam_local * (sxx + syy) * .5 + 1e-12
            det = (sxx + reg) * (syy + reg) - sxy ** 2
            d[k, :, 0] += ((syy + reg) * bx - sxy * by) / det
            d[k, :, 1] += ((sxx + reg) * by - sxy * bx) / det
    return p, d
