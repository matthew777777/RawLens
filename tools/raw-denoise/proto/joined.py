"""JPEG path ("joined"): rawdn base in the raw domain for colour, blotches and shadows, then fine
luma recovered after demosaic from an ESD3D2-style luma filter of the noisy demosaiced frame.

  ours = demosaic(rawdn(cfa))                       colour, low/mid frequencies, unbiased shadows
  esd  = ESD3D2 luma pass on demosaic(cfa)          full-resolution edge-steered luma
  d    = L(esd) - L(ours),  detail = d - gauss(d)   only the fine band of the difference
  out  = ours + amount * detail                     added equally to the white-balanced channels (neutral)
"""
import os, sys, numpy as np
from scipy.ndimage import gaussian_filter
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../wavelet-parity'))
import esd3d2_port as E
from lib import demosaic, F32
import cfadct as C


def luma(rgb, wb):
    return (rgb * wb).mean(-1)


def joined(x, a, b, wb, prm=None, hp_sigma=2.0, amount=1.0):
    ours = demosaic(C.denoise_packed(x, a, b, wb, prm))
    esd = E.esd3d2(np.clip(demosaic(x), 0, None), a, b, color=False)[0]
    d = luma(esd, wb) - luma(ours, wb)
    det = d - gaussian_filter(d, hp_sigma) if hp_sigma > 0 else d
    return (ours + amount * det[..., None] / wb).astype(F32)


def blend(x, a, b, wb, prm=None, amount=0.45):
    """Uniform full-band luma grain: ours + amount * (luma of the noisy demosaiced frame - luma of ours).
    No adaptivity, so the noise floor is even everywhere; colour stays from the raw stage."""
    ours = demosaic(C.denoise_packed(x, a, b, wb, prm))
    d = luma(demosaic(x), wb) - luma(ours, wb)
    return (ours + amount * d[..., None] / wb).astype(F32)
