# SPDX-License-Identifier: GPL-3.0-or-later
"""
Lucas-Kanade Reloaded (Lecouat, Ponce, Mairal, ICCV 2021, arXiv:2104.06191)
-- numpy reference for RawLens, third burst method next to MFSR and HDR+.

Followed exactly from the paper:
  Eq. (1)  y_k = D B W_{p_k} x + eps_k, p_k = 6-param affine, B = box average
           over the LR pixel (Sec 3.4 "simple averaging"), D = spatial +
           Bayer decimation.
  Eq. (4)  HQS: 1/2||y - U_p z||^2 + mu/2 ||z - x||^2 + lambda phi(x)
  Eq. (5)  one gradient step on z
  Eq. (7)  one Gauss-Newton (Lucas-Kanade) step per frame on p_k, aligning
           the HR estimate z with the LR raw frame y_k
  x-step   plug-and-play proximal operator f(z)
  init     forward-additive LK on bilinear-grey frames to the reference
           (Sec 3.4 "Initialization by coarse alignment")

Substituted (the authors released no code/weights):
  f_theta  learned ResUNet  -> pluggable prior: 'none' (paper's "multiframe
           L2 only" ablation), 'tv' (paper's "L2 + TV" ablation), 'drunet'
           (DPIR DRUNet: the same ResUNet architecture of [48], public
           denoiser weights, noise-level conditioned).
  mu_t, eta_t  learned -> DPIR-style schedule: sigma_t log-spaced from
           sigma_max down to the noise floor, mu_t = lambda / sigma_t^2,
           eta_t = 1 / (L + mu_t) with L = ||U^T U|| by power iteration.
  coarse LK -> run on a 3-level pyramid (handheld motion exceeds LK's
           basin at full res; the paper does not say how it handles that).

Coordinates: pixel centres at integer + 0.5. p_k maps a point u of frame k
(LR coords) to the reference LR frame: A_k u + t_k, p = (a11 a12 tx a21 a22 ty).
HR coords = s * reference LR coords.
"""
import numpy as np

# ---------------------------------------------------------------- warping

def affine_identity(K):
    p = np.zeros((K, 6))
    p[:, 0] = 1
    p[:, 4] = 1
    return p


def apply_affine(p, ux, uy):
    return (p[0] * ux + p[1] * uy + p[2], p[3] * ux + p[4] * uy + p[5])


def bilinear_setup(qx, qy, H, W):
    """Indices/weights for sampling an HxW image at pixel-centre coords q."""
    fx = qx - 0.5
    fy = qy - 0.5
    x0 = np.floor(fx).astype(np.int64)
    y0 = np.floor(fy).astype(np.int64)
    ax = fx - x0
    ay = fy - y0
    valid = (x0 >= 0) & (y0 >= 0) & (x0 + 1 < W) & (y0 + 1 < H)
    x0c = np.clip(x0, 0, W - 2)
    y0c = np.clip(y0, 0, H - 2)
    i00 = y0c * W + x0c
    idx = np.stack([i00, i00 + 1, i00 + W, i00 + W + 1])
    wts = np.stack([(1 - ax) * (1 - ay), ax * (1 - ay), (1 - ax) * ay, ax * ay])
    wts = wts * valid
    return idx, wts, valid


class FrameOp:
    """U_k = D B W_{p_k}: HR RGB (3,sH,sW) -> LR raw (h,w) for one frame.

    B averages s x s HR sub-samples per LR pixel (box over the LR pixel
    area); D keeps one colour per LR pixel from the RGGB mosaic.
    """

    def __init__(self, p, h, w, s, HR_shape):
        self.p, self.h, self.w, self.s = p, h, w, s
        Hh, Wh = HR_shape
        self.HR = HR_shape
        jj, ii = np.meshgrid(np.arange(w), np.arange(h))
        # RGGB channel of each LR pixel (crop is RGGB-normalised)
        self.ch = np.where((ii % 2 == 0) & (jj % 2 == 0), 0,
                  np.where((ii % 2 == 1) & (jj % 2 == 1), 2, 1)).ravel()
        offs = (np.arange(s) + 0.5) / s
        self.sub = []
        for oy in offs:
            for ox in offs:
                ux = (jj + ox).ravel()
                uy = (ii + oy).ravel()
                rx, ry = apply_affine(p, ux, uy)
                idx, wts, valid = bilinear_setup(s * rx, s * ry, Hh, Wh)
                self.sub.append((ux, uy, idx + self.ch * Hh * Wh, wts, valid))
        self.valid = np.mean([v for *_, v in self.sub], axis=0) == 1.0
        self.n = s * s

    def fwd(self, x):
        xf = x.ravel()
        out = 0
        for _, _, idx, wts, _ in self.sub:
            out = out + (xf[idx] * wts).sum(0)
        return (out / self.n).reshape(self.h, self.w)

    def adj(self, r):
        rf = r.ravel() / self.n
        acc = np.zeros(3 * self.HR[0] * self.HR[1])
        for _, _, idx, wts, _ in self.sub:
            acc += np.bincount(idx.ravel(), (wts * rf).ravel(), acc.size)
        return acc.reshape(3, *self.HR)

    def jacobian(self, z):
        """J = (dU_p/dp) z, shape (h*w, 6), from HR image gradients."""
        s = self.s
        gy, gx = np.gradient(z, axis=(1, 2))
        gxf, gyf = gx.ravel(), gy.ravel()
        J = np.zeros((self.h * self.w, 6))
        for ux, uy, idx, wts, _ in self.sub:
            sx = (gxf[idx] * wts).sum(0) * s   # dq/d(r) = s
            sy = (gyf[idx] * wts).sum(0) * s
            J += np.stack([sx * ux, sx * uy, sx, sy * ux, sy * uy, sy], 1)
        return J / self.n


# ---------------------------------------------------------------- raw utils

def demosaic_bilinear(raw):
    """RGGB raw (h,w) -> RGB (3,h,w), plain bilinear."""
    from scipy.ndimage import convolve
    h, w = raw.shape
    m = np.zeros((3, h, w))
    m[0, 0::2, 0::2] = 1
    m[1, 0::2, 1::2] = 1
    m[1, 1::2, 0::2] = 1
    m[2, 1::2, 1::2] = 1
    k = np.array([[1, 2, 1], [2, 4, 2], [1, 2, 1]], float) / 4
    out = np.empty((3, h, w))
    for c in range(3):
        num = convolve(raw * m[c], k, mode='mirror')
        den = convolve(m[c], k, mode='mirror')
        out[c] = num / den
    return out


def grey(raw):
    return demosaic_bilinear(raw).mean(0)


# ---------------------------------------------------------------- coarse LK

def _resample(img, p):
    """Sample img at A u + t for every pixel u of img's grid."""
    h, w = img.shape
    jj, ii = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)
    qx, qy = apply_affine(p, jj.ravel(), ii.ravel())
    idx, wts, valid = bilinear_setup(qx, qy, h, w)
    return (img.ravel()[idx] * wts).sum(0).reshape(h, w), valid.reshape(h, w)


def lk_affine(ref, mov, p0=None, iters=40, levels=3):
    """Forward-additive LK: find p so ref(A u + t) ~= mov(u) (u in mov)."""
    from scipy.ndimage import zoom, gaussian_filter
    p = np.array([1, 0, 0, 0, 1, 0], float) if p0 is None else p0.copy()
    pyr = [(ref, mov)]
    for _ in range(levels - 1):
        r, m = pyr[-1]
        pyr.append((zoom(gaussian_filter(r, 1), 0.5, order=1),
                    zoom(gaussian_filter(m, 1), 0.5, order=1)))
    p[2] /= 2 ** (levels - 1)
    p[5] /= 2 ** (levels - 1)
    for lv in range(levels - 1, -1, -1):
        r, m = pyr[lv]
        h, w = m.shape
        jj, ii = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)
        ux, uy = jj.ravel(), ii.ravel()
        gry, grx = np.gradient(r)
        for _ in range(iters):
            wr, valid = _resample(r, p)
            gx, _ = _resample(grx, p)
            gy, _ = _resample(gry, p)
            v = valid.ravel()
            e = (m - wr).ravel()[v]
            gx, gy = gx.ravel()[v], gy.ravel()[v]
            J = np.stack([gx * ux[v], gx * uy[v], gx, gy * ux[v], gy * uy[v], gy], 1)
            dp = np.linalg.solve(J.T @ J + 1e-6 * np.eye(6), J.T @ e)
            p += dp
            if np.abs(dp[[2, 5]]).max() < 1e-3:
                break
        if lv:
            p[2] *= 2
            p[5] *= 2
    return p


# ---------------------------------------------------------------- priors

def prox_tv(z, weight, iters=50):
    """Chambolle TV prox per channel (paper's L2 + TV ablation)."""
    out = np.empty_like(z)
    for c in range(z.shape[0]):
        f = z[c]
        px = np.zeros_like(f)
        py = np.zeros_like(f)
        tau = 0.125
        for _ in range(iters):
            div = (px - np.roll(px, 1, 1)) + (py - np.roll(py, 1, 0))
            u = f - weight * div
            gx = np.roll(u, -1, 1) - u
            gy = np.roll(u, -1, 0) - u
            n = 1 + tau / weight * np.sqrt(gx ** 2 + gy ** 2)
            px = (px - tau / weight * gx) / n
            py = (py - tau / weight * gy) / n
        out[c] = f - weight * ((px - np.roll(px, 1, 1)) + (py - np.roll(py, 1, 0)))
    return out


class Prior:
    """x = f(z, sigma). Linear raw -> denoiser domain via WB + gamma."""

    def __init__(self, kind, wb, drunet_path=None, device='cpu', noise_map='pixel',
                 domain='gamma', ab=None, sig_ref=None, wav_prm=None):
        """domain='gat': nets see the generalized-Anscombe image of OUR fitted
        noise model ab=(a,b) (var = a x + b, raw units), where noise is unit
        Gaussian everywhere -- the AWGN FFDNet/DRUNet were trained on. The
        schedule's sigma is read as a multiple sigma/sig_ref of the sensor noise."""
        self.noise_map, self.domain, self.ab, self.sig_ref = noise_map, domain, ab, sig_ref
        self.wav_prm = wav_prm or {}
        self.kind, self.wb = kind, np.asarray(wb, float).reshape(3, 1, 1)
        self.device = device
        if kind == 'drunet':
            from drunet import load_drunet
            self.net = load_drunet(drunet_path, device)
        elif kind == 'ffdnet':
            from drunet import load_ffdnet
            self.net = load_ffdnet(drunet_path, device)
        elif kind == 'wavelet':
            import sys, os
            sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                            '..', '..', 'tools', 'wavelet-parity'))
            from rawlens_v2_proto import denoise_v2
            self._wav = denoise_v2

    def _gat_net(self, z, sigma):
        import torch
        a, b = self.ab
        wb = torch.tensor(self.wb, dtype=torch.float32, device=z.device)
        ac, bc = a * wb, b * wb ** 2                     # noise model of the WB'd channels
        x = (z * wb).clamp_min(0)
        D = 2.0 / ac * torch.sqrt(ac * x + 0.375 * ac ** 2 + bc)    # unit-variance noise
        lo, hi = float(D.min()), float(D.max())
        span = max(hi - lo, 1e-6)
        r = sigma / self.sig_ref                          # iterate noise in units of sensor noise
        inp = torch.cat([(D - lo) / span, torch.full_like(D[:1], r / span)], 0)[None].to(self.device)
        with torch.no_grad():
            Dd = self.net(inp)[0].to(z.device) * span + lo
        # closed-form unbiased inverse (Makitalo-Foi), per channel; algebraic below D=1.5
        Dd = Dd.clamp_min(1e-3)
        s2 = bc / ac ** 2
        rr = 1.5 ** 0.5
        y = 0.25 * Dd ** 2 + 0.25 * rr / Dd - 11 / 8 / Dd ** 2 + 5 / 8 * rr / Dd ** 3 - 0.125 - s2
        y = torch.where(Dd < 1.5, 0.25 * Dd ** 2 - 0.375 - s2, y)
        return (ac * y) / wb

    def wavelet(self, z, sigma):
        """RawLens v2 wavelet denoiser as a prox: pure Gaussian noise at sigma
        (a ~ 0, b = sigma^2, linear green units), no grain re-injection, no
        self-measured noise factors (the schedule sets the strength), no RCD
        noise factors (input is not RCD-demosaiced)."""
        img = np.ascontiguousarray(z.transpose(1, 2, 0))
        if self.ab is not None:                      # our fitted model, scaled to the iterate's noise
            r2 = (sigma / self.sig_ref) ** 2
            a, b = self.ab[0] * r2, self.ab[1] * r2
        else:
            b = sigma ** 2
            a = 0.05 * b / max(float(np.median(img[..., 1])), 1e-3)   # tiny shot term keeps GAT well-conditioned
        prm = dict(measure=False, grain=0.0, kappa=[1.0, 1.0, 1.0])
        prm.update(self.wav_prm)
        out, _ = self._wav(img, a, b, self.wb.ravel(), prm)
        return out.transpose(2, 0, 1).astype(np.float64)

    def torch(self, z, sigma):
        """Same as __call__ on a (3,H,W) float32 torch tensor, kept on device."""
        import torch
        if self.kind == 'none':
            return z
        if self.kind in ('tv', 'wavelet'):
            f = prox_tv if self.kind == 'tv' else self.wavelet
            return torch.tensor(f(z.cpu().double().numpy(), sigma),
                                dtype=torch.float32, device=z.device)
        import torch.nn.functional as F
        if self.domain == 'gat':
            return self._gat_net(z, sigma)
        wb = torch.tensor(self.wb, dtype=torch.float32, device=z.device)
        lin = (z * wb).clamp_min(0)
        g = lin ** (1 / 2.2)
        # Linear sigma -> gamma domain through the slope of x^(1/2.2):
        # sigma_g = sigma / 2.2 * x^(1/2.2 - 1). 'pixel' (default) evaluates the
        # slope at each pixel's smoothed luminance (a spatial noise map, which
        # FFDNet/DRUNet accept); 'median' is the old single level, which at
        # night (median ~ black) over-smooths every lit area.
        if self.noise_map == 'median':
            level = float(lin.median().clamp(1e-3, 1))
            sm = torch.full_like(g[:1], sigma * (1 / 2.2) * level ** (1 / 2.2 - 1))
        else:
            lum = F.avg_pool2d(lin.mean(0, keepdim=True)[None], 7, 1, 3, count_include_pad=False)[0]
            lum = lum.clamp(min=max(sigma, 1e-4), max=1.0)       # noise-dominated floor
            sm = (sigma / 2.2 * lum ** (1 / 2.2 - 1)).clamp(max=75 / 255)
        inp = torch.cat([g, sm], 0)[None].to(self.device)
        with torch.no_grad():
            d = self.net(inp)[0].to(z.device)
        return d.clamp_min(0) ** 2.2 / wb

    def __call__(self, z, sigma):
        if self.kind == 'none':
            return z
        if self.kind == 'tv':
            return prox_tv(z, sigma)
        if self.kind == 'wavelet':
            return self.wavelet(z, sigma)
        import torch
        t = torch.tensor(z, dtype=torch.float32, device=self.device)
        return self.torch(t, sigma).cpu().double().numpy()


# ---------------------------------------------------------------- solver

def power_norm(ops, HR, it=15, seed=0):
    v = np.random.default_rng(seed).standard_normal((3, *HR))
    for _ in range(it):
        w = sum(op.adj(op.fwd(v)) for op in ops)
        L = np.linalg.norm(w)
        v = w / L
    return L


def lk_reloaded(frames, s, prior, T=8, sigma_noise=0.01, sigma_max=0.1,
                lam=1.0, p_init=None, refine_motion=True, z_steps=1,
                step_scale=1.0, log=print):
    """z_steps=1 is the paper's single gradient step (Eq. 5); >1 repeats it
    (stand-in for the learned, typically large, eta_t)."""
    """frames: list of RGGB raw (h,w) in [0,1], frames[0] is the reference."""
    K = len(frames)
    h, w = frames[0].shape
    HR = (s * h, s * w)
    from scipy.ndimage import zoom
    if p_init is None:
        g = [grey(f) for f in frames]
        p = np.stack([lk_affine(g[0], g[k]) for k in range(K)])
        p[0] = affine_identity(1)[0]
    else:
        p = p_init.copy()
    log('coarse p (tx,ty): ' + ' '.join(f'({q[2]:+.2f},{q[5]:+.2f})' for q in p))
    # z0 = x0: upsampled bilinear demosaic of the reference
    z = zoom(demosaic_bilinear(frames[0]), (1, s, s), order=1)
    x = z.copy()
    sigmas = np.geomspace(sigma_max, sigma_noise, T)
    for t in range(T):
        ops = [FrameOp(p[k], h, w, s, HR) for k in range(K)]
        if t == 0:
            L = power_norm(ops, HR)
            log(f'L = {L:.3f}')
        # Data term weighted by 1/sigma_n^2, prox sigma_t^2 = lam/mu; everything
        # below is multiplied through by sigma_n^2, so mu' = lam sigma_n^2/sigma_t^2.
        mu = lam * sigma_noise ** 2 / sigmas[t] ** 2
        eta = step_scale / (L + mu)
        # Eq. (5): z <- z - eta [U^T (U z - y) + mu (z - x)]
        for _ in range(z_steps):
            grad = mu * (z - x)
            res = 0.0
            for op, y in zip(ops, frames):
                r = (op.fwd(z) - y) * op.valid.reshape(h, w)
                res += float((r ** 2).sum())
                grad = grad + op.adj(r)
            z = z - eta * grad
        # Eq. (7): one Gauss-Newton step per frame (reference stays fixed)
        if refine_motion:
            for k in range(1, K):
                op = FrameOp(p[k], h, w, s, HR)
                v = op.valid
                r = (op.fwd(z) - frames[k]).ravel()[v]
                J = op.jacobian(z)[v]
                p[k] = p[k] - np.linalg.solve(J.T @ J + 1e-9 * np.eye(6), J.T @ r)
        # x-step: plug-and-play prox at sigma_t = sqrt(lam / mu)
        x = prior(z, sigmas[t])
        log(f't={t} sigma={sigmas[t]:.4f} mu={mu:.3g} eta={eta:.3g} '
            f'rmse={np.sqrt(res / (K * h * w)):.5f}')
    return x, p
