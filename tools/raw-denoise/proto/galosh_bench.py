"""rawdn vs ESD3D2 vs GALOSH (github.com/luxgrain/GALOSH, Vulkan raw engine) on the synthetic scorecard.

usage: GALOSH_VK=<path to galosh_vk> python3 galosh_bench.py [sweep|final] [levels]
Build of the GALOSH binary: ../galosh/build.sh

Columns
  PSNR/Y/C  display-referred PSNR (colour, luma, chroma)          higher = cleaner
  blot      low-frequency chroma error x1000                       lower = fewer colour blotches
  keepF     slope of output fine (1-2 px) luma on clean, textured  1 = all real detail kept
  grain     rms of luma error on the flat half, relative to the noisy input   (how much grain is left)
  patch     coefficient of variation of the local grain rms on the flat half  lower = even grain
  white     fine/mid energy ratio of the luma error relative to the input noise  1 = same grain colour, <1 = mushy
  cnoise    rms chroma error on the flat half, relative to the noisy input   lower = less colour noise
"""
import sys, os, time, subprocess, tempfile, numpy as np
from scipy.ndimage import gaussian_filter, uniform_filter
import synth
from synth import *

GV = os.environ.get('GALOSH_VK', '')
TMP = tempfile.mkdtemp(prefix='galosh_')


def galosh_cfa(x, a=0.0, b=0.0, strength=1.0, luma=1.0, chroma=1.0, flags=()):
    """RGGB CFA float -> denoised CFA. a = b = 0 -> GALOSH's own blind noise fit."""
    H, W = x.shape
    fi, fo = TMP + '/i.bin', TMP + '/o.bin'
    x.astype(np.float32).tofile(fi)
    r = subprocess.run([GV, fi, fo, str(W), str(H), 'galosh', str(strength), str(luma), str(chroma), repr(float(a)), repr(float(b)), *flags],
                       capture_output=True, text=True)
    assert r.returncode == 0, r.stderr[-400:]
    return np.fromfile(fo, np.float32).reshape(H, W)


def m_gal(blind=True, grain=0.0, **kw):
    """grain: fraction of the removed noise put back, binomial 3x3 on the mosaic (carrier-free -> achromatic)."""
    def f(x, a, b, wb):
        o = galosh_cfa(x, 0 if blind else a, 0 if blind else b, **kw)
        if grain > 0:
            o = o + grain * C.blur(torch.from_numpy(x - o)[None], 1)[0].numpy()
        return demosaic(o)
    return f


import torch  # noqa: E402  (after synth: cfadct already imported it)


def bands(y):
    g1 = gaussian_filter(y, 1.0); g2 = gaussian_filter(y, 2.5)
    return y - g1, g1 - g2


def run(methods, levels=('hi', 'mid', 'lo'), seed=0):
    rng = np.random.default_rng(seed); acc = {}
    for name, rgb, wb in cleans():
        for lv in levels:
            a, b, sc = LEVELS[lv]; c = rgb * sc; cc = mosaic(c)
            noisy = (rng.poisson(np.maximum(cc, 0) / a) * a + rng.normal(0, np.sqrt(b), cc.shape)).astype(F32)
            norm = np.percentile(c[..., 1], 99) * 1.3
            ref = develop(c, wb, norm); yr, cbr, crr = ycc(ref); fr, mr = bands(yr)
            act = uniform_filter(fr * fr, 9); tex = act > np.median(act)
            edge = np.zeros_like(tex); edge[16:-16, 16:-16] = True
            tex &= edge; flat = ~(act > np.median(act)) & edge
            dn = develop(demosaic(noisy), wb, norm); yn, cbn, crn = ycc(dn)
            en = yn - yr; fn_, mn_ = bands(en)
            base_white = np.sqrt((fn_[flat] ** 2).mean() / (mn_[flat] ** 2).mean())
            base_grain = np.sqrt((en[flat] ** 2).mean())
            base_c = np.sqrt(((cbn - cbr)[flat] ** 2 + (crn - crr)[flat] ** 2).mean())
            for mname, fn in methods.items():
                t = time.time(); o = fn(noisy, a, b, wb); dt = time.time() - t
                d = develop(o, wb, norm); m = metrics(d, ref)
                yo, cbo, cro = ycc(d); fo, mo = bands(yo)
                keep = float((fr[tex] * fo[tex]).sum() / (fr[tex] ** 2).sum())
                e = yo - yr; ef, em = bands(e)
                loc = np.sqrt(uniform_filter(e * e, 17))[flat]
                acc.setdefault((lv, mname), []).append([
                    m['psnr'], m['y'], m['c'], m['blot'], keep,
                    np.sqrt((e[flat] ** 2).mean()) / base_grain, loc.std() / loc.mean(),
                    np.sqrt((ef[flat] ** 2).mean() / (em[flat] ** 2).mean()) / base_white,
                    np.sqrt(((cbo - cbr)[flat] ** 2 + (cro - crr)[flat] ** 2).mean()) / base_c, dt])
    print(f"{'level':5s} {'method':34s} {'PSNR':>6s} {'Y':>6s} {'C':>6s} {'blot':>5s} {'keepF':>5s} {'grain':>5s} {'patch':>5s} {'white':>5s} {'cnoise':>6s} {'s':>4s}")
    for (lv, mn), v in acc.items():
        m = np.mean(v, 0)
        print(f"{lv:5s} {mn:34s} {m[0]:6.2f} {m[1]:6.2f} {m[2]:6.2f} {m[3]:5.2f} {m[4]:5.2f} {m[5]:5.2f} {m[6]:5.2f} {m[7]:5.2f} {m[8]:6.2f} {m[9]:4.1f}")
    return acc


BASE = {
    'noisy': m_noisy,
    'ESD3D2': m_esd,
    'rawdn base (grain 0)': m_pk(),
    'rawdn DNG pick (grain .6 uniform)': m_pk(grain=0.6, grain_y=False),
}

if __name__ == '__main__':
    mode = sys.argv[1] if len(sys.argv) > 1 else 'final'
    lv = tuple(sys.argv[2].split(',')) if len(sys.argv) > 2 else ('hi', 'mid', 'lo')
    M = dict(BASE)
    if mode == 'sweep':
        M = {'noisy': m_noisy, 'ESD3D2': m_esd, 'rawdn DNG pick (grain .6 uniform)': m_pk(grain=0.6, grain_y=False)}
        M['GALOSH blind 1/1/1'] = m_gal()
        M['GALOSH oracle noise 1/1/1'] = m_gal(blind=False)
        for l in (0.5, 0.65, 0.8):
            M[f'GALOSH luma {l}'] = m_gal(luma=l)
        for c in (1.5, 2.0, 3.0):
            M[f'GALOSH chroma {c}'] = m_gal(chroma=c)
        for g in (0.3, 0.45, 0.6):
            M[f'GALOSH + grain {g}'] = m_gal(grain=g)
        M['GALOSH wht4 fast'] = m_gal(flags=('--wht=4', '--upsample=fast'))
    else:
        M['GALOSH default (blind)'] = m_gal()
        M['GALOSH tuned'] = m_gal(**eval(os.environ.get('GALOSH_TUNED', "dict(chroma=1.0, grain=0.45)")))
    run(M, lv)
