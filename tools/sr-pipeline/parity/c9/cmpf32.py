import sys, numpy as np
a = np.fromfile(sys.argv[1], '<f4').astype(np.float64); b = np.fromfile(sys.argv[2], '<f4').astype(np.float64)
H = int(sys.argv[3]); a = a.reshape(H, -1, 3); b = b.reshape(H, -1, 3)
B = int(sys.argv[4]) if len(sys.argv) > 4 else 0
for name, (x, y) in {"full": (a, b), f"interior{B}": (a[B:H-B, B:-B or None], b[B:H-B, B:-B or None])}.items():
    m = np.isfinite(x) & np.isfinite(y); d = np.abs(x-y)[m]; mse = (d**2).mean()
    print(f"{name:12s} PSNR(peak1)={10*np.log10(1/mse) if mse else np.inf:.1f}dB max={d.max():.4g} mean={d.mean():.3g} >1/255={(d>1/255).mean()*100:.3f}% nanmis={(np.isfinite(x)!=np.isfinite(y)).sum()}")
    if name == "full":
        yy, xx, cc = np.unravel_index(np.nanargmax(np.where(m, np.abs(x-y), -1)), x.shape); print("  worst at", yy, xx, cc, x[yy,xx,cc], y[yy,xx,cc])
