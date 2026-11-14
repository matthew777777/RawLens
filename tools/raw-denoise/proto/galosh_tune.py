"""GALOSH + organic grain variants: put back a uniform fraction of the removed noise, achromatic only."""
import sys, numpy as np, torch
from galosh_bench import *
def m_gy(grain=0.5, luma=1.0, chroma=1.0):
    """grain = fraction of the removed 2x2 Hadamard Y residual (achromatic, all frequencies of the half-res lattice)."""
    def f(x, a, b, wb):
        o = galosh_cfa(x, luma=luma, chroma=chroma)
        z = lambda u: C.gat(u.astype(np.float64), a, b)
        r = (z(x) - z(o)); H, W = r.shape
        s = np.sqrt(np.array([wb[0], wb[1], wb[1], wb[2]]) / wb[1]); g = np.tile(np.array([[s[0], s[1]], [s[2], s[3]]]), (H // 2, W // 2))
        y = (r * g).reshape(H // 2, 2, W // 2, 2).mean((1, 3))
        y = np.repeat(np.repeat(y, 2, 0), 2, 1) / g
        return demosaic(C.gat_inv(z(o) + grain * y, a, b).astype(F32))
    return f
M = {'ESD3D2': m_esd, 'rawdn DNG pick': m_pk(grain=0.6, grain_y=False)}
for g in (0.35, 0.5, 0.65): M[f'GALOSH +Ygrain {g}'] = m_gy(g)
for g in (0.35, 0.5): M[f'GALOSH chroma1.5 +Ygrain {g}'] = m_gy(g, chroma=1.5)
M['GALOSH luma.8 c1.5 +Ygrain .4'] = m_gy(0.4, luma=0.8, chroma=1.5)
run(M, tuple(sys.argv[1].split(',')) if len(sys.argv) > 1 else ('hi', 'mid', 'lo'))
