# SPDX-License-Identifier: GPL-3.0-or-later
"""Torch (MPS/CPU) version of lkr.lk_reloaded -- same math, GPU speed.

U_p = D B W_p is built from grid_sample (bilinear, pixel-centre convention
identical to lkr.bilinear_setup), avg_pool (B), and an RGGB mask (D). The
adjoint U^T comes from autograd, so it is exact by construction. The
Gauss-Newton Jacobian is analytic (sampled HR gradients x dq/dp), as in
lkr.FrameOp.jacobian. Checked against lkr.py by `run.py synth --torch`.
"""
import numpy as np
import torch
import torch.nn.functional as F


def _bayer_mask(h, w, dev):
    m = torch.zeros(3, h, w, device=dev)
    m[0, 0::2, 0::2] = 1
    m[1, 0::2, 1::2] = 1
    m[1, 1::2, 0::2] = 1
    m[2, 1::2, 1::2] = 1
    return m


class Ops:
    """All K frame operators at once; p is a (K,6) tensor."""

    def __init__(self, h, w, s, dev):
        self.h, self.w, self.s, self.dev = h, w, s, dev
        self.mask = _bayer_mask(h, w, dev)
        # sub-sample positions u (LR coords) laid out on the (s h, s w) grid
        ux = (torch.arange(s * w, device=dev) + 0.5) / s
        uy = (torch.arange(s * h, device=dev) + 0.5) / s
        self.uy, self.ux = torch.meshgrid(uy, ux, indexing='ij')

    def setup(self, p):
        """Sampling grid (K,sh,sw,2) and validity (K,h,w) for motions p."""
        s, H, W = self.s, self.s * self.h, self.s * self.w
        a = p[:, :, None, None]
        rx = a[:, 0] * self.ux + a[:, 1] * self.uy + a[:, 2]
        ry = a[:, 3] * self.ux + a[:, 4] * self.uy + a[:, 5]
        qx, qy = s * rx, s * ry
        grid = torch.stack([2 * qx / W - 1, 2 * qy / H - 1], -1)
        ok = ((qx >= 0.5) & (qy >= 0.5) & (qx < W - 0.5) & (qy < H - 0.5)).float()
        valid = -F.max_pool2d(-ok[:, None], s)[:, 0]       # all sub-samples inside
        self.grid, self.valid, self.p = grid, valid, p
        return self

    def _sample(self, img):
        K = self.grid.shape[0]
        return F.grid_sample(img[None].expand(K, -1, -1, -1), self.grid, mode='bilinear',
                             padding_mode='border', align_corners=False)

    def fwd(self, z):
        """(3,sh,sw) -> (K,h,w) raw mosaics."""
        b = F.avg_pool2d(self._sample(z), self.s)
        return (b * self.mask).sum(1)

    def adj(self, residual):
        """Exact differentiable fixed-grid transpose, including validity mask."""
        H, W = self.s*self.h, self.s*self.w
        u=((self.grid[...,0]+1)*W/2-.5).clamp(0,W-1)
        v=((self.grid[...,1]+1)*H/2-.5).clamp(0,H-1)
        x0,y0=u.floor().long(),v.floor().long()
        x1,y1=(x0+1).clamp(max=W-1),(y0+1).clamp(max=H-1)
        ax,ay=u-x0,v-y0
        values=((residual*self.valid)[:,None]*self.mask[None]).repeat_interleave(self.s,-2).repeat_interleave(self.s,-1)/(self.s*self.s)
        offsets=(torch.arange(3,device=self.dev)*H*W)[None,:,None,None]
        out=residual.new_zeros(3*H*W)
        for ix,iy,weight in ((x0,y0,(1-ax)*(1-ay)),(x1,y0,ax*(1-ay)),(x0,y1,(1-ax)*ay),(x1,y1,ax*ay)):
            out=out.scatter_add(0,(iy[:,None]*W+ix[:,None]+offsets).reshape(-1),(values*weight[:,None]).reshape(-1))
        return out.reshape(3,H,W)

    def jacobian(self, z):
        """(K, h*w, 6): d(U_p z)/dp from sampled HR gradients."""
        # Differentiate the bilinear samples themselves, rather than sampling
        # central image gradients (which gives a different Jacobian).
        H, W = z.shape[-2:]
        u = ((self.grid[..., 0] + 1) * W / 2 - .5).clamp(0, W - 1)
        v = ((self.grid[..., 1] + 1) * H / 2 - .5).clamp(0, H - 1)
        x0, y0 = u.floor().long(), v.floor().long()
        x1, y1 = (x0 + 1).clamp(max=W - 1), (y0 + 1).clamp(max=H - 1)
        ax, ay = u - x0, v - y0
        flat = z.flatten(1)
        z00, z01, z10, z11 = [flat[:, iy * W + ix].permute(1, 0, 2, 3)
                              for iy, ix in ((y0,x0),(y0,x1),(y1,x0),(y1,x1))]
        sx = ((z01-z00)*(1-ay[:,None])+(z11-z10)*ay[:,None])*self.s
        sy = ((z10-z00)*(1-ax[:,None])+(z11-z01)*ax[:,None])*self.s
        cols = []
        for g in (sx, sy):
            for f in (self.ux, self.uy, None):
                v = g if f is None else g * f
                cols.append((F.avg_pool2d(v, self.s) * self.mask).sum(1))
        return torch.stack(cols, -1).reshape(cols[0].shape[0], -1, 6)


def _grad_data(ops, z, y):
    """U^T m (U z - y) via autograd, plus the residual energy."""
    zz = z.detach().requires_grad_(True)
    r = (ops.fwd(zz) - y) * ops.valid
    e = 0.5 * (r ** 2).sum()
    g, = torch.autograd.grad(e, zz)
    return g, float(e.detach()) * 2


def _power_norm(ops, shape, it=15):
    v = torch.randn(shape, device=ops.dev, generator=torch.Generator('cpu').manual_seed(0)
                    ) if ops.dev == 'cpu' else torch.randn(shape).to(ops.dev)
    L = 1.0
    for _ in range(it):
        vv = v.detach().requires_grad_(True)
        e = 0.5 * ((ops.fwd(vv) * ops.valid) ** 2).sum()
        w, = torch.autograd.grad(e, vv)
        L = float(w.norm())
        v = w / L
    return L


def lk_reloaded(frames, s, prior, T=8, sigma_noise=0.01, sigma_max=0.1, lam=1.0,
                p_init=None, refine_motion=True, z_steps=1, step_scale=1.0, sigma_end=None, prior_at=None, L=None,
                device='cpu', log=print):
    """Same contract as lkr.lk_reloaded; prior(z_np, sigma) works on numpy."""
    import lkr
    K = len(frames)
    h, w = frames[0].shape
    if p_init is None:
        g = [lkr.grey(f) for f in frames]
        p_init = np.stack([lkr.lk_affine(g[0], g[k]) for k in range(K)])
        p_init[0] = lkr.affine_identity(1)[0]
    log('coarse p (tx,ty): ' + ' '.join(f'({q[2]:+.2f},{q[5]:+.2f})' for q in p_init))
    dev = device
    y = torch.tensor(np.stack(frames), dtype=torch.float32, device=dev)
    p = torch.tensor(p_init, dtype=torch.float32, device=dev)
    ops = Ops(h, w, s, dev)
    ref = torch.tensor(lkr.demosaic_bilinear(frames[0]), dtype=torch.float32, device=dev)
    z = F.interpolate(ref[None], scale_factor=s, mode='bilinear', align_corners=False)[0]
    x = z.clone()
    sigmas = np.geomspace(sigma_max, sigma_noise if sigma_end is None else sigma_end, T)
    if L == 'bound':
        # Schur bound ||U^T U|| <= ||U||_1 ||U||_inf = max(U^T 1) (rows sum to 1):
        # one pass, deterministic, always >= the true L (eta = c/(L+mu) is
        # stable for any c < 2). Mirrors tools/lkr-pipeline (resid_adj mode 2).
        ops.setup(p)
        vv = torch.zeros_like(z, requires_grad=True)
        (ops.fwd(vv) * ops.valid).sum().backward()
        L = float(vv.grad.max())
    elif L is None:
        L = _power_norm(ops.setup(p), z.shape)
    log(f'L = {L:.3f}')
    for t in range(T):
        ops.setup(p)
        mu = lam * sigma_noise ** 2 / sigmas[t] ** 2
        eta = step_scale / (L + mu)
        for _ in range(z_steps):                                    # Eq. (5)
            g, res = _grad_data(ops, z, y)
            z = z - eta * (g + mu * (z - x))
        if refine_motion:                                           # Eq. (7)
            ops.setup(p)
            r = ((ops.fwd(z) - y) * ops.valid).reshape(K, -1, 1)
            J = ops.jacobian(z) * ops.valid.reshape(K, -1, 1)
            JtJ = (J.transpose(1, 2) @ J).cpu().double()
            Jtr = (J.transpose(1, 2) @ r).cpu().double()
            dp = torch.linalg.solve(JtJ + 1e-9 * torch.eye(6, dtype=torch.float64), Jtr)[..., 0]
            dp[0] = 0                                               # reference fixed
            p = p - dp.float().to(dev)
        # prior_at: iterations that call the prior (phone budget); others keep
        # the last denoised estimate as the HQS anchor. None = every iteration.
        if prior_at is None or t in prior_at:
            x = prior.torch(z, sigmas[t]) if hasattr(prior, 'torch') else \
                torch.tensor(prior(z.cpu().double().numpy(), sigmas[t]), dtype=torch.float32, device=dev)
        log(f't={t} sigma={sigmas[t]:.4f} mu={mu:.3g} eta={eta:.3g} '
            f'rmse={np.sqrt(res / (K * h * w)):.5f}')
    return x.cpu().double().numpy(), p.cpu().double().numpy()


def unrolled(y, p, z0, prior, scale, mu, eta, refine_motion=True):
    """Differentiable research reference: paper equations 5/7 and CNN x-step.

    Explicit positive schedules may be learned; no pretrained paper weights
    are implied. Caller supplies initialization and a direct RGB prior.
    """
    if len(mu) != len(eta) or not len(mu):
        raise ValueError('mu/eta must have equal nonzero length')
    if bool((mu <= 0).any()) or bool((eta <= 0).any()):
        raise ValueError('positive schedules required')
    ops=Ops(y.shape[-2],y.shape[-1],scale,y.device)
    z,x=z0,z0
    trace={'reference_rgb':z0}
    for t in range(len(mu)):
        ops.setup(p)
        residual=(ops.fwd(z)-y)*ops.valid
        gradient=ops.adj(residual)
        z=z-eta[t]*(gradient+mu[t]*(z-x))
        if refine_motion and len(p)>1:
            J=ops.jacobian(z)*ops.valid.flatten(1)[...,None]
            r=((ops.fwd(z)-y)*ops.valid).flatten(1)[...,None]
            A=J.transpose(1,2)@J; b=J.transpose(1,2)@r
            dp=torch.linalg.solve(A.double()+1e-9*torch.eye(6,device=y.device,dtype=torch.float64),b.double())[...,0].to(p.dtype)
            mask=torch.cat([p.new_zeros(1),p.new_ones(len(p)-1)])[:,None]
            p=p-dp*mask
        x=prior(z[None])[0]
        trace.update({f'iter{t}_z':z,f'iter{t}_x':x,f'iter{t}_motion':p,f'iter{t}_residual':residual,f'iter{t}_gradient':gradient})
    return x,p,trace
