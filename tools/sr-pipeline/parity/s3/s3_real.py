import sys, numpy as np
sys.dont_write_bytecode = True
sys.path.insert(0, sys.argv[1])
import s3_proto as P
from handheld_super_resolution.utils_dng import load_dng_burst
from handheld_super_resolution.utils_image import estimate_image_snr
burst, y0, x0, n = sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
st = load_dng_burst(burst); ref, comp = st.get_raw_arrays()
snr = float(estimate_image_snr(ref, st.alpha, st.beta))
r = ref[y0:y0+n, x0:x0+n]; c = comp[:, y0:y0+n, x0:x0+n]
off = P.merge(r, c, st.alpha, st.beta, snr)
for rho, k in [(0.1, 4), (0.1, 16), (0.2, 16)]:
    on = P.merge(r, c, st.alpha, st.beta, snr, s3=(rho, k))
    d = np.abs(on - off)[16:-16, 16:-16]
    hf = P.STATE['hf']
    print(f'{burst.split("/")[-1][:24]} rho<{rho} k={k}: hf ref px {hf.mean()*100:.2f}%  rejected per frame {np.mean(P.STATE["rejected"])*100:.2f}%  '
          f'changed px {np.mean(d.max(-1) > 1e-4)*100:.2f}%  max|d| {d.max():.3f}', flush=True)
