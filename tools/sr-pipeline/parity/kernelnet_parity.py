"""KernelNet parity: the engine's (s1, s2, rho) map vs an exact float64
evaluation of PhotonCamera's model, built straight from the .ncnn.bin.

usage: kernelnet_parity.py <reference.dng> <engine_map.f32>
  engine_map.f32: `SRP_KN_DUMP=<file> sr-pipeline ... --kernel kernelnet`
  (3 x mapH x mapW little-endian float32, sensor orientation).

The python ncnn wheel is NOT used as the reference: on this Mac it differed
from the float64 graph by up to 1.65 (mean 0.18) on a real crop, and setting
its precision options after load_model silently returns garbage.
"""
import sys, struct
from pathlib import Path
import numpy as np, rawpy

BIN = Path(__file__).resolve().parents[1] / "src/main/resources/kernelnet/kernelnet_aniso_v2_2_params.ncnn.bin"
LAYERS = [(2, 16, 3, 2, 1), (16, 16, 3, 1, 1), (16, 16, 3, 1, 1), (16, 16, 3, 1, 1), (16, 16, 3, 1, 1), (16, 3, 1, 1, 0)]

def weights():
    b = BIN.read_bytes(); off = 0; out = []
    for cin, cout, k, s, r in LAYERS:
        tag = struct.unpack_from("<I", b, off)[0]; off += 4; n = cout * cin * k * k
        assert tag == 0x01306B47, hex(tag)
        w = np.frombuffer(b, np.float16, n, off).astype(np.float64).reshape(cout, cin, k, k); off += 2 * n
        bias = np.frombuffer(b, "<f4", cout, off).astype(np.float64); off += 4 * cout
        out.append((w, bias, k, s, r))
    assert off == len(b)
    return out

def graph(x, ws):
    for w, bias, k, s, r in ws:
        p = k // 2; xp = np.pad(x, ((0, 0), (p, p), (p, p))); _, H, W = x.shape
        oh = (H + 2 * p - k) // s + 1; ow = (W + 2 * p - k) // s + 1
        out = np.zeros((w.shape[0], oh, ow))
        for ky in range(k):
            for kx in range(k):
                out += np.einsum("oc,chw->ohw", w[:, :, ky, kx], xp[:, ky:ky + s * oh:s, kx:kx + s * ow:s])
        x = out + bias[:, None, None]; x = np.maximum(x, 0) if r else x
    sg = 1 / (1 + np.exp(-x))
    return np.stack([2 * sg[0], 2 * sg[1], 2 * sg[2] - 1])

def main(dng, dump):
    with rawpy.imread(dng) as r:
        raw = r.raw_image_visible.astype(np.float64)
        black = np.array(r.black_level_per_channel, np.float64); white = float(r.white_level)
        cols = r.raw_colors_visible; noise = None
    norm = (raw - black[cols]) / (white - black[cols])
    H, W = norm.shape
    L = np.sqrt(np.clip(norm.reshape(H // 2, 2, W // 2, 2).mean((1, 3)), 0, 1))
    mh, mw = (H // 2 - 1) // 2 + 1, (W // 2 - 1) // 2 + 1
    eng = np.fromfile(dump, "<f4").reshape(3, mh, mw).astype(np.float64)
    # Central 256x256 luma region; the sigma plane is taken from the engine's
    # flat-region behaviour-free path: we fit it by matching (sigma only
    # shifts flats), so pass it explicitly when known.
    sigma = float(sys.argv[3]) if len(sys.argv) > 3 else None
    assert sigma is not None, "pass the KernelNet sigma (logged by the engine) as argv[3]"
    y0, x0, n = (H // 4) & ~1, (W // 4) & ~1, 256
    ref = graph(np.stack([L[y0:y0 + n, x0:x0 + n], np.full((n, n), sigma)]), weights())
    e = eng[:, y0 // 2:y0 // 2 + n // 2, x0 // 2:x0 // 2 + n // 2]
    d = np.abs(ref - e)[:, 8:-8, 8:-8]
    print(f"engine vs float64 graph: max {d.max():.5f} mean {d.mean():.6f} (fp16 output rounding ~1e-3)")
    sys.exit(0 if d.max() < 2e-3 else 1)

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
