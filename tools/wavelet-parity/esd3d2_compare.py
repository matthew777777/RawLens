"""RawLens (steps 1-4 host logic) vs RAWR vs PhotonCamera ESD3D2 on real DNGs.
Production chain: clip01 -> RCD oracle -> denoise. Writes PNG grids + report.
usage: esdcmp.py <scratch> <repo> <out_dir> dng [dng ...]"""
import sys, os, subprocess, time, numpy as np, tifffile
from PIL import Image, ImageDraw
from scipy.ndimage import uniform_filter, sobel
S, REPO, OUT = sys.argv[1:4]
sys.path.insert(0, REPO + '/tools/wavelet-parity')
import esd3d2_port as E
F32 = np.float32
BIN = S + '/cand-bench'; SPV = REPO + '/app/src/main/assets/spirv/wavelet'; REPLAY = S + '/nm/replay'
CFAI = {(0, 1, 1, 2): 0, (1, 0, 2, 1): 1, (1, 2, 0, 1): 2, (2, 1, 1, 0): 3}
NIGHT = [('palm', 560, 330, 2), ('clouds', 300, 480, 2.5), ('road', 1000, 1150, 3), ('fence', 230, 780, 2.5), ('gate', 1560, 700, 0)]


def vals(tag):
    v = tag.value; v = v if isinstance(v, tuple) else (v,)
    return [v[i] / v[i + 1] for i in range(0, len(v), 2)] if 'RATIONAL' in str(tag.dtype) else list(v)


def load(p):
    t = tifffile.TiffFile(p)
    pg = t.pages[0]; tg = {x.name: x for x in pg.tags.values()}; raw = pg.asarray().astype(F32)
    black = float(np.mean(vals(tg['BlackLevel']))); white = float(vals(tg['WhiteLevel'])[0])
    cfa = tuple(tg['CFAPattern'].value)
    asn = np.array(vals(tg['AsShotNeutral'])); wb = (asn[1] / asn).astype(F32)
    prof = None
    if 'NoiseProfile' in tg:
        n = tg['NoiseProfile'].value; prof = (float(n[2]), float(n[3])) if len(n) >= 4 else (float(n[0]), float(n[1]))
    iso = tg['ISOSpeedRatings'].value if 'ISOSpeedRatings' in tg else 0
    return np.clip((raw - black) / (white - black), 0, 1).astype(F32), cfa, wb, prof, iso


def estimate(x, cfa):
    h, w = x.shape
    np.repeat(x.astype(np.float16)[:, :, None], 4, 2).tofile(S + '/nm/in.f16')
    o = subprocess.check_output([REPLAY, 'est', S + '/nm/in.f16', str(w), str(h), str(CFAI[cfa])]).split()
    return int(o[0]), float(o[1]), float(o[2]), int(o[3]), float(o[4]), float(o[5])


def choose(est, prof):
    """wavelet_host choose_noise (no learned model yet: first shots)."""
    ok, ea, eb, bins, med, corr = est
    a, b, src = (prof[0], prof[1], 'profile') if prof else (0, 0, 'none')
    if ok:
        x = max(med, 1e-3)
        ratio = (ea * x + eb) / max(a * x + b, 1e-12) if prof else 0
        if not prof or ratio < 0.4 or ratio > 2.5:
            a, b, src = ea, eb, 'estimate'
    x = max(med, 1e-3)
    erel = np.sqrt(max(ea * x + eb, 0)) / x if ok else 0.0
    tt = np.clip((erel - 0.06) / 0.06, 0, 1); tt = tt * tt * (3 - 2 * tt)
    boost = 1 + tt * (np.clip(corr / 1.2, 1, 3) - 1)          # step 5c (host mirror)
    pass  # boost measured, not applied (host mirror)
    rel = np.sqrt(max(a * x + b, 0)) / x
    t = np.clip((rel - 0.04) / 0.06, 0, 1)
    return dict(a=a, b=b, src=src, rel=rel, corr=corr, boost=boost, luma=float(np.clip(rel / 0.08, 0.4, 1)),
                lowres=float(t * t * (3 - 2 * t)), impulse=4.0 if rel >= 0.10 else 0.0)


def rcd(x, cfa, y0, x0, h, w):
    x[y0:y0 + h, x0:x0 + w].astype(F32).tofile(S + '/e_g.f32')
    subprocess.run([S + '/rcdoracle/oracle', str(w), str(h), str(w), '0', '0', str(w), str(h),
                    ''.join('RGB'[k] for k in cfa), S + '/e_g.f32', S + '/e_rcd.f32', 'normalized-rgb', '-'],
                   check=True, capture_output=True)
    return np.fromfile(S + '/e_rcd.f32', F32).reshape(h, w, 3).astype(np.float16).astype(F32)


def bench(img, wb, a, b, alg, extra):
    h, w, _ = img.shape
    np.concatenate([img, np.ones((h, w, 1), F32)], -1).tofile(S + '/e_in.f32')
    r = subprocess.run([BIN, '--spv-dir', SPV, '--input-f32', S + '/e_in.f32', '--width', str(w), '--height', str(h),
                        '--tile', '1536', '--scales', '7', '--wb', *map(str, wb), '--a', str(a), '--b', str(b),
                        '--algorithm', str(alg), '--force-y', '0.25', '--force-uv', '0.75', '--dump-out', S + '/e_out.f32'] + extra,
                       capture_output=True, text=True)
    assert r.returncode == 0, r.stdout[-400:] + r.stderr[-400:]
    return np.fromfile(S + '/e_out.f32', F32).reshape(h, w, 4)[..., :3]


def run_all(img, wb, nz, prof):
    outs = {'input': img}
    if prof:
        outs['RAWR'] = bench(img, wb, prof[0], prof[1], 0, [])
    outs['RawLens'] = bench(img, wb, nz['a'], nz['b'], 1, ['--grain', '0.25', '--luma-scale', str(nz['luma']),
                            '--lowres', str(nz['lowres']), '--impulse', str(nz['impulse']), '--exact-inverse']
                            + (['--loo-y', '--neutral', '1', '--despeck', '3'] if nz['impulse'] > 0 else []))
    t = time.time()
    outs['ESD3D2'], info = E.esd3d2(img, nz['a'], nz['b'])
    info['py_s'] = time.time() - t
    return outs, info


def disp(im, wb, norm, ev):
    y = np.clip(im * wb / norm * 2 ** ev, 0, 1)
    y = np.where(y <= 0.0031308, 12.92 * y, 1.055 * np.power(y, 1 / 2.4) - 0.055)
    return (y * 255 + 0.5).astype(np.uint8)


def metrics(outs, wb, tag):
    g = lambda im: np.clip(im * wb, 1e-6, None) ** (1 / 2.2)
    gi = g(outs['input']); lum = gi.mean(-1)
    grad = np.hypot(sobel(uniform_filter(lum, 3), 0), sobel(uniform_filter(lum, 3), 1))
    tex = grad > np.percentile(grad, 85); flat = grad < np.percentile(grad, 30)
    hp = lambda x: x - uniform_filter(x, 5)
    lines = []
    for k, v in outs.items():
        go = g(v); res = (gi - go).mean(-1)
        cf = go[flat]; chroma = (np.std(cf[:, 0] - cf[:, 1]) + np.std(cf[:, 2] - cf[:, 1])) / 2
        luma = np.std(hp(go.mean(-1))[flat])
        keep = np.std(hp(go.mean(-1))[tex]) / np.std(hp(lum)[tex])
        mn = np.corrcoef(uniform_filter(res ** 2, 7).ravel(), grad.ravel())[0, 1] if k != 'input' else 0
        sm = uniform_filter(outs['input'].mean(-1), 15); dark = sm < np.percentile(sm, 20)
        bias = (v[dark].mean() / max(outs['input'][dark].mean(), 1e-9) - 1) * 100
        lines.append(f"  {tag:10s} {k:8s} flat_chroma={chroma:.4f} flat_luma_hp={luma:.4f} "
                     f"texture_kept={keep:.3f} removed_vs_edges_corr={mn:+.3f} dark_mean_shift={bias:+.1f}%")
    return lines


def grid(rows, cols, path, C, Z=2):
    G = Image.new('RGB', (len(cols) * C * Z, len(rows) * (C * Z + 16)), (20, 24, 26)); d = ImageDraw.Draw(G)
    for r, (name, tiles) in enumerate(rows):
        for c, (lab, im) in enumerate(zip(cols, tiles)):
            G.paste(Image.fromarray(im).resize((C * Z, C * Z), Image.NEAREST), (c * C * Z, r * (C * Z + 16) + 16))
            d.text((c * C * Z + 4, r * (C * Z + 16) + 2), f"{lab} | {name}", fill=(255, 230, 0))
    G.save(path)


report = []
for path in sys.argv[4:]:
    base = os.path.basename(path)[:-4]
    x, cfa, wb, prof, iso = load(path)
    nz = choose(estimate(x, cfa), prof)
    head = (f"{base} ISO {iso}: noise {nz['src']} a={nz['a']:.3g} b={nz['b']:.3g} rel={nz['rel']:.3f} "
            f"corr={nz['corr']:.2f} boost={nz['boost']:.2f} luma={nz['luma']:.2f} lowres={nz['lowres']:.2f} impulse={nz['impulse']:.0f}")
    print(head, flush=True); report.append(head)
    if 'IMG_20261102_181122_683' in base:
        # NIGHT crops are in half-res (binned) coordinates: x2 for full res.
        y0, x0, h, w = 660, 460, 2160, 3180
        img = rcd(x, cfa, y0, x0, h, w)
        outs, info = run_all(img, wb, nz, prof)
        report.append(f"  ESD3D2 {info}"); report += metrics(outs, wb, 'region')
        C = 520; norm = np.percentile(x * wb[1], 99.7) * 1.2
        cols = list(outs); rows = []
        for name, cx, cy, ev in NIGHT:
            yy, xx = 2 * cy - y0, 2 * cx - x0
            rows.append((f"{name} +{ev}EV", [disp(outs[k][yy:yy + C, xx:xx + C], wb, norm, ev) for k in cols]))
        grid(rows, cols, f"{OUT}/{base}_esd3d2_compare.png", C, 1)
    else:
        H, W = x.shape; C = 260; M = 64
        # pick crops on a cheap green-only preview: most textured + flattest bright-ish
        gprev = uniform_filter(x, 2)[::2, ::2]
        gg = np.hypot(sobel(gprev, 0), sobel(gprev, 1))
        dens = uniform_filter(gg, C // 2)
        lum = uniform_filter(gprev, C // 2)
        hb = C // 4 + M // 2
        inner = (slice(hb, -hb), slice(hb, -hb))
        ty, tx = np.unravel_index(np.argmax(dens[inner]), dens[inner].shape)
        score = dens[inner] / dens[inner].mean() + np.abs(np.log(np.maximum(lum[inner], 1e-4) / np.median(lum)))
        fy, fx = np.unravel_index(np.argmin(score), score.shape)
        rows = []; cols = None
        for name, (cy, cx), ev in (('detail', (ty, tx), 0), ('flat', (fy, fx), 1)):
            cy = (cy * 2) & ~1; cx = (cx * 2) & ~1
            y0, x0 = cy, cx
            img = rcd(x, cfa, y0, x0, C + 2 * M, C + 2 * M)
            outs, info = run_all(img, wb, nz, prof)
            outs = {k: v[M:M + C, M:M + C] for k, v in outs.items()}
            report.append(f"  ESD3D2 {name} {info}"); report += metrics(outs, wb, name)
            norm = np.percentile(outs['input'][..., 1] * wb[1], 99.5) if name == 'detail' else norm
            cols = list(outs)
            rows.append((f"{name} +{ev}EV", [disp(outs[k], wb, norm, ev) for k in cols]))
        grid(rows, cols, f"{OUT}/{base}_esd3d2_compare.png", C)
    print('\n'.join(report[-12:]), flush=True)
open(OUT + '/esd3d2_report.txt', 'w').write('\n'.join(report) + '\n')
