#!/usr/bin/env python3
"""Inventory CFA sources and replay every burst; full-frame artifacts and 1:1 crops.

Requires numpy, tifffile, rawpy and Pillow. Never writes to input directories.
The PNG view is white-balanced camera RGB with an sRGB transfer curve, not a
camera-profile color rendering. Linear TIFFs preserve the camera RGB values.
"""
import argparse
import hashlib
import html
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

import numpy as np
from PIL import Image, ImageDraw
import rawpy
import tifffile


def atomic_json(path, value):
    tmp = path.with_suffix('.tmp')
    tmp.write_text(json.dumps(value, indent=2) + '\n')
    tmp.replace(path)


def fingerprint(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def inventory(roots, dest):
    cases, excluded = [], []
    for root in roots:
        for folder in sorted(p for p in root.iterdir() if p.is_dir()):
            files = []
            for p in sorted(folder.rglob('*')):
                if p.suffix.lower() != '.dng':
                    continue
                if any(x.lower() in ('google_out', 'out') for x in p.relative_to(folder).parts[:-1]):
                    excluded.append(dict(path=str(p), reason='existing output directory'))
                    continue
                with tifffile.TiffFile(p) as t:
                    pages = list(t.pages)
                    pages += [s for q in list(pages) if q.pages for s in q.pages]
                    cfa = [q for q in pages if q.photometric == 32803]
                    if not cfa:
                        excluded.append(dict(path=str(p), reason='processed/non-CFA DNG'))
                        continue
                    shape = list(cfa[0].shape)
                files.append(dict(path=str(p), shape=shape, sha256=fingerprint(p)))
            if not files:
                excluded.append(dict(path=str(folder), reason='no source CFA frames'))
                continue
            def key(f):
                m = re.search(r'_F(\d+)\.dng$', f['path'], re.I)
                return (int(m[1]) if m else 0, f['path'])
            files.sort(key=key)
            unique, seen = [], set()
            for f in files:
                if f['sha256'] in seen:
                    excluded.append(dict(path=f['path'], reason='duplicate source bytes'))
                else:
                    seen.add(f['sha256'])
                    unique.append(f)
            if len({tuple(f['shape']) for f in unique}) != 1:
                raise ValueError(f'mixed RAW dimensions in {folder}')
            with rawpy.imread(unique[0]['path']) as raw:
                wb = np.array(raw.camera_whitebalance[:3], dtype=float)
                wb /= wb[1]
            case = dict(collection=root.name, burst=folder.name, files=unique,
                        frames=len(unique), height=unique[0]['shape'][0], width=unique[0]['shape'][1],
                        reference=unique[0]['path'], wb=wb.tolist())
            stage = dest / 'inputs' / root.name / folder.name
            stage.mkdir(parents=True, exist_ok=True)
            for i, f in enumerate(unique):
                link = stage / f'frame{i:03}.dng'
                if not link.exists():
                    link.symlink_to(f['path'])
            case['input'] = str(stage)
            cases.append(case)
    atomic_json(dest / 'inventory.json', dict(cases=cases, excluded=excluded,
                                              reference_policy='F00 first when present; otherwise lexical source order'))
    print(f'{len(cases)} bursts, {sum(c["frames"] for c in cases)} frames; {len(excluded)} exclusions', flush=True)


def crop_specs(case, scale):
    w, h = case['width'] * scale, case['height'] * scale
    side = 320  # output pixels: native 1:1 at either scale
    specs = []
    for yi, fy in enumerate((.15, .5, .85)):
        for xi, fx in enumerate((.15, .5, .85)):
            x = min(max(int(w * fx - side / 2), 0), w - side)
            y = min(max(int(h * fy - side / 2), 0), h - side)
            specs.append((f'grid-{yi}-{xi}', (x, y, x + side, y + side)))
    named = {
        'IMG_20260915_080720_100': [('bicycle-railings', (1420, 540, 1720, 840))],
        '33TJ_20150612_201525_012': [('moving-pedestrian', (2500,2250,3150,3120)),
                                     ('moving-taxis', (900,2000,1500,2600))],
        'IMG_20260919_184641_979': [('moving-car', (1760,650,2280,1100))],
        'IMG_20261112_074919_397': [('far-building-pole', (1640, 1280, 1960, 1680)),
                                    ('balconies', (1300, 100, 1700, 500))],
    }
    for name, box in named.get(case['burst'], []):
        specs.append((name, tuple(v * scale for v in box)))
    return specs


def render(case, scale, target):
    log = (target / 'run.log').read_text()
    match = re.search(r'-> (\d+)x(\d+) s=', log)
    if not match:
        raise ValueError(f'output dimensions missing: {target}')
    w, h = map(int, match.groups())
    if (w, h) != (case['width'] * scale, case['height'] * scale):
        raise ValueError(f'full-sensor coverage mismatch: {(w,h)}')
    data = target / 'output.dng.f32'
    if data.stat().st_size != h * w * 3 * 4:
        raise ValueError('float output length mismatch')
    z = np.memmap(data, dtype='<f4', mode='r', shape=(h, w, 3))
    if not np.isfinite(z).all():
        raise ValueError('non-finite reconstruction')
    # Deflate needs no external imagecodecs package. Preserve 16-bit linear RGB.
    tifffile.imwrite(target / 'linear-camera-rgb.tif', np.uint16(np.clip(z, 0, 1) * 65535 + .5),
                     photometric='rgb', compression='deflate', rowsperstrip=64,
                     metadata=dict(wb=case['wb'], reference=case['reference'], scale=scale))
    # One exposure, derived ONLY from the input reference, shared by all variants/scales.
    exp_path = Path(case['input']) / 'display.json'
    if exp_path.exists():
        exposure = json.loads(exp_path.read_text())['exposure']
    else:
        with rawpy.imread(case['reference']) as raw:
            a = raw.raw_image[::8, ::8].astype('f4')
            black = float(np.mean(raw.black_level_per_channel))
            level = np.median(np.maximum(a - black, 0)) / max(raw.white_level - black, 1)
            exposure = float(np.clip(.18 / max(level, .001), .25, 32))
        atomic_json(exp_path, dict(exposure=exposure))
    pixels = np.empty((h, w, 3), dtype='u1')
    for y in range(0, h, 128):
        a = np.clip(z[y:y+128] * np.array(case['wb'], dtype='f4') * exposure, 0, 1)
        a = np.where(a <= .0031308, a * 12.92, 1.055 * a ** (1/2.4) - .055)
        pixels[y:y+128] = np.uint8(a * 255 + .5)
    im = Image.fromarray(pixels)
    im.save(target / 'full.png', compress_level=3)
    overview = im.copy()
    overview.thumbnail((1400, 1000))
    overview.save(target / 'overview.jpg', quality=94)
    crops = target / 'crops'
    crops.mkdir(exist_ok=True)
    for name, box in crop_specs(case, scale):
        im.crop(box).save(crops / (name + '.png'))
    meta = dict(width=w, height=h, exposure=exposure, seconds=None,
                f32_sha256=fingerprint(data), display='WB camera RGB; shared exposure; sRGB transfer')
    maps = target / 'maps.f32'
    if maps.exists():
        mm = re.search(r'debug maps: 5 x (\d+) x (\d+)', log)
        if mm:
            mh, mw = map(int, mm.groups())
            a = np.memmap(maps, dtype='f4', mode='r', shape=(5,mh,mw))
            support = np.clip(a[0] / case['frames'], 0, 1)
            meta.update(support_mean=float(support.mean()), support_below_half=float((support < .5).mean()))
            heat = np.stack([1-support, support, np.zeros_like(support)], -1)
            Image.fromarray(np.uint8(heat * 255)).resize(overview.size).save(target / 'support.png')
            np.savez_compressed(target / 'diagnostic-maps-stride4.npz', maps=np.array(a[:,::4,::4]), stride=4)
            del a
        # Images remain full resolution; diagnostic previews are spatially subsampled.
        maps.unlink()
    del z
    # These are our transient outputs; the lossless TIFF and hash are retained.
    data.unlink()
    (target / 'output.dng').unlink()
    return meta


def report(dest, inv):
    rows = ['<!doctype html><meta charset="utf-8"><title>LK-R rejection validation</title>',
            '<style>body{font:16px system-ui;margin:24px;background:#222;color:#eee}a{color:#9cf}'
            'img{max-width:46vw}td{vertical-align:top}details{margin:18px 0} .crop img{max-width:none}</style>',
            '<h1>LK-R rejection validation</h1><p>Full sensor outputs, 1:1 crops; fixed input reference and exposure. '
            'Camera RGB previews; linear TIFFs retain source color space. Automatic detail changes are not quality scores.</p>']
    for c in inv['cases']:
        rows.append(f'<h2>{html.escape(c["collection"]+" / "+c["burst"])}</h2>')
        for scale in (1,2):
            root = dest / 'runs' / c['collection'] / c['burst'] / f'x{scale}'
            variants = [p for p in sorted(root.glob('*')) if (p / 'done.json').exists()]
            if not variants:
                continue
            rows.append(f'<h3>Full sensor ×{scale}</h3><table><tr>')
            for p in variants:
                rel = str(p.relative_to(dest))
                rows.append(f'<td><b>{p.name}</b><br><a href="{rel}/full.png"><img src="{rel}/overview.jpg"></a><br>'
                            f'<a href="{rel}/full.png">Full PNG</a> · <a href="{rel}/linear-camera-rgb.tif">Linear TIFF</a>'
                            f' · <a href="{rel}/run.log">Log</a></td>')
            rows.append('</tr></table>')
            rows.append('<details><summary>Native pixel crops</summary>')
            for name, box in crop_specs(c, scale):
                rows.append(f'<p>{name}: output box {box}</p><table class="crop"><tr>')
                for p in variants:
                    rel = str(p.relative_to(dest))
                    rows.append(f'<td>{p.name}<br><img src="{rel}/crops/{name}.png"></td>')
                rows.append('</tr></table>')
            rows.append('</details>')
    (dest / 'index.html').write_text('\n'.join(rows))


def run(args, dest):
    inv = json.loads((dest / 'inventory.json').read_text())
    cases = inv['cases']
    if args.only:
        cases = [c for c in cases if args.only in c['burst']]
    for case in cases:
        for scale in args.scales:
            target = dest / 'runs' / case['collection'] / case['burst'] / f'x{scale}' / args.variant
            target.mkdir(parents=True, exist_ok=True)
            if (target / 'done.json').exists():
                continue
            if shutil.disk_usage(dest).free < 2 * 1024**3:
                raise RuntimeError('less than 2 GiB free; refusing next reconstruction')
            cmd = [str(args.binary.resolve()), case['input'], str(target / 'output.dng'),
                   '--s', str(scale), '--workers', '2', '--prior', '0'] + args.extra
            atomic_json(target / 'command.json', dict(command=cmd, binary_sha256=fingerprint(args.binary),
                                                      input_sha256=[f['sha256'] for f in case['files']]))
            print('START', case['collection'], case['burst'], scale, args.variant, flush=True)
            start = time.monotonic()
            with (target / 'run.log').open('w') as log:
                subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT, check=True,
                               env=dict(os.environ, DYLD_FALLBACK_LIBRARY_PATH='/opt/homebrew/lib',
                                        LKR_DEBUG_MAPS=str(target / 'maps.f32')))
            meta = render(case, scale, target)
            meta['seconds'] = time.monotonic() - start
            atomic_json(target / 'done.json', meta)
            report(dest, inv)
            print('DONE', case['burst'], scale, args.variant, round(meta['seconds'],1), flush=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('action', choices=['inventory', 'run', 'report'])
    ap.add_argument('--out', type=Path, required=True)
    ap.add_argument('--roots', nargs='+', type=Path)
    ap.add_argument('--binary', type=Path)
    ap.add_argument('--variant', default='before')
    ap.add_argument('--scales', nargs='+', type=int, default=[1,2])
    ap.add_argument('--only', default='')
    args, extra = ap.parse_known_args()
    args.extra = extra
    args.out.mkdir(parents=True, exist_ok=True)
    if args.action == 'inventory':
        inventory(args.roots, args.out)
    elif args.action == 'run':
        run(args, args.out)
    else:
        report(args.out, json.loads((args.out / 'inventory.json').read_text()))


if __name__ == '__main__':
    main()
