import os, subprocess, sys, numpy as np
from lib import *
import cfadct as C
HOST = '/Users/monikamalinowska/AndroidStudioProjects/RawLens/tools/raw-denoise/host/build'
x, wb, iso, lsb = load_dng(os.path.expanduser('~/Downloads/My/Bursts/IMG_20260921_194618_520/IMG_20260921_194618_520_F00.dng'))
a, b, _ = estimate_noise(x)
for nm, y0, x0, S in [('detail', 1424, 1424, 512), ('dark', 400, 528, 512), ('lamp', 1424, 3216, 512)]:
    c = x[y0:y0 + S, x0:x0 + S]
    raw = np.round(c * 959 + 64).astype(np.uint16)
    raw.tofile('p_in.u16')
    r = subprocess.run([HOST + '/rawdn_host', HOST, 'p_in.u16', str(S), str(S), '0', '64', '1023', str(wb[0]), str(wb[2]), 'p_out.u16',
                        str(a), str(b), '0.35', '4'], capture_output=True, text=True)
    print(nm, r.stdout.strip(), r.stderr.strip()[-300:])
    g = (np.fromfile('p_out.u16', np.uint16).reshape(S, S).astype(F32) - 64) / 959
    p = C.denoise_packed(c, a, b, wb, dict(grain=0.35))
    p = np.clip(np.round(p * 959 + 64), 0, 1023); p = (p - 64) / 959
    d = (g - p) * 959
    inner = d[32:-32, 32:-32]
    print(f'   GPU vs proto (LSB): mean abs {np.abs(d).mean():.3f}, inner mean abs {np.abs(inner).mean():.3f}, inner max {np.abs(inner).max():.1f}; '
          f'noise removed rms {np.sqrt(((c - p) ** 2).mean()) * 959:.2f} LSB')
