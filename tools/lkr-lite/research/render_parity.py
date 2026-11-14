"""Side-by-side parity crops for every LK-R / Lite DNG pair in a folder.

python -I render_parity.py DIR [OUTDIR]
Both DNGs are developed identically with LibRaw (camera WB, no auto brightness, sensor orientation
so coordinates match full_compare's block picks). Per <burst>-x<s>: 8 crops (centre, 4 quadrant
centres, the 3 most different 64 px blocks from the json), each LK-R | Lite at 1:1, one JPEG.
"""
import glob, json, os, sys
import numpy as np, rawpy
from PIL import Image, ImageDraw

C = 256

def develop(p):
    with rawpy.imread(p) as r:
        return r.postprocess(use_camera_wb=True, no_auto_bright=True, user_flip=0, output_bps=16,
                             gamma=(1, 1), bright=1.0).astype(np.float32) / 65535.0

def main():
    src = sys.argv[1]; out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(src, 'crops')
    os.makedirs(out, exist_ok=True)
    index = []
    for js in sorted(glob.glob(os.path.join(src, '*-x[12].json'))):
        j = json.load(open(js))
        base = js[:-5]
        lk, li = base + '-lkr.dng', base + '-lite.dng'
        name = os.path.basename(base)
        if not (os.path.exists(lk) and os.path.exists(li)):
            continue
        dst = os.path.join(out, name + '.jpg')
        if not os.path.exists(dst):
            A, B = develop(lk), develop(li)
            H, W = A.shape[:2]
            pos = [(0.5, 0.5, 'centre'), (0.25, 0.25, 'top-left'), (0.25, 0.75, 'top-right'),
                   (0.75, 0.25, 'bottom-left'), (0.75, 0.75, 'bottom-right')]
            pts = [(int(fy * H), int(fx * W), lab) for fy, fx, lab in pos]
            for by, bx in j.get('picks', [])[:3]:
                pts.append((by * 64 + 32, bx * 64 + 32, 'most different'))
            rows = []
            for cy, cx, lab in pts:
                y0 = min(max(cy - C // 2, 0), H - C); x0 = min(max(cx - C // 2, 0), W - C)
                a, b = A[y0:y0 + C, x0:x0 + C], B[y0:y0 + C, x0:x0 + C]
                # One exposure per crop, from the LK-R crop, applied to both (dark areas readable).
                g = float(np.clip(0.18 / max(np.percentile(a.mean(-1), 50), 1e-5), 1.0, 32.0))
                srgb = lambda x: (np.where(x < 0.0031308, 12.92 * x, 1.055 * np.clip(x, 0, None) ** (1 / 2.4) - 0.055) * 255).clip(0, 255).astype(np.uint8)
                a, b = srgb(np.clip(a * g, 0, 1)), srgb(np.clip(b * g, 0, 1))
                lab = lab + (' x%.0f' % g if g > 1.5 else '')
                row = np.concatenate([a, np.full((C, 6, 3), 255, np.uint8), b], 1)
                im = Image.fromarray(row); d = ImageDraw.Draw(im)
                for t, x in ((('LK-R ' + lab), 4), ('Lite', C + 10)):
                    d.rectangle([x - 2, 2, x + 7 * len(t) + 2, 14], fill=(0, 0, 0)); d.text((x, 2), t, fill=(255, 255, 255))
                rows += [np.asarray(im), np.full((6, 2 * C + 6, 3), 255, np.uint8)]
            Image.fromarray(np.concatenate(rows, 0)).save(dst, quality=88)
        index.append({'name': name, 'img': os.path.basename(dst), 'psnr': j['psnr'], 'p1': j['block_p1'],
                      'min': j['block_min'], 'lkr_s': j['lkr']['merge_ms'] / 1e3, 'lite_s': j['lite']['merge_ms'] / 1e3})
        print(name, 'ok', flush=True)
    json.dump(index, open(os.path.join(out, 'index.json'), 'w'), indent=1)

if __name__ == '__main__':
    main()
