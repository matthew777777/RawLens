"""LK-R vs Lite DNG pair: noise level in dark flat areas, fine detail in bright areas, per channel.
python -I dng_stats.py DIR  (pairs <burst>-x<s>-lkr.dng / -lite.dng)"""
import glob, os, sys
import numpy as np, tifffile
from scipy.ndimage import gaussian_filter, uniform_filter
def load(p):
    with tifffile.TiffFile(p) as t:
        pages = [pg for pg in t.pages] + [s for pg in t.pages for s in (pg.pages or [])]
        a = max((pg.asarray() for pg in t.pages), key=lambda x: x.size)
    return a.astype(np.float32) / 65535.0
def main():
  for lk in sorted(glob.glob(os.path.join(sys.argv[1], '*-lkr.dng'))):
      li = lk.replace('-lkr.dng', '-lite.dng')
      if not os.path.exists(li): continue
      A, B = load(lk), load(li)
      Y = lambda X: X.mean(-1)
      ya, yb = Y(A), Y(B)
      hp = lambda y: y - gaussian_filter(y, 1.5)
      ha, hb = hp(ya), hp(yb)
      m = uniform_filter(ya, 31)
      lv = np.percentile(m, [20, 80])
      dark = m < lv[0]; bright = m > lv[1]
      # dark: noise sd; bright: fine-detail energy; chroma noise sd in dark
      ch = lambda X: X[..., 0] - X[..., 2]
      hca, hcb = hp(ch(A)), hp(ch(B))
      r = lambda a, b, msk: np.sqrt((b[msk] ** 2).mean() / max((a[msk] ** 2).mean(), 1e-20))
      print('%-30s dark noise Lite/LK-R %.2f | dark chroma noise %.2f | bright detail %.2f | detail corr %.3f' % (
          os.path.basename(lk)[:-8], r(ha, hb, dark), r(hca, hcb, dark), r(ha, hb, bright),
          np.corrcoef(ha[bright], hb[bright])[0, 1]), flush=True)


if __name__ == "__main__":
    main()
