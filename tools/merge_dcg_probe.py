#!/usr/bin/env python3
"""Simple unregistered linear CFA fusion of the opt-in 0 + 3/0 phone probe.

No alignment, deghosting, denoising or demosaic. Two repeated captures per route
estimate a single variance per CFA site; only the first capture of each is fused.
Requires numpy and tifffile. This is an experiment, not a production HDR pipeline.
"""
import argparse
import json
from pathlib import Path

import numpy as np
import tifffile


def rational(v):
    return np.asarray(v, dtype=np.float64).reshape(-1, 2)[:, 0] / np.asarray(v, dtype=np.float64).reshape(-1, 2)[:, 1]


def read(path):
    with tifffile.TiffFile(path) as t:
        p = t.pages[0]
        # Some vendor DngCreator versions emit invalid orientation 9; we set 1.
        tags = {x.code: (int(x.dtype), x.count, x.value) for x in p.tags.values() if x.code != 274}
        raw = p.asarray().astype(np.float32)
    value = lambda code: tags[code][2]
    assert raw.ndim == 2 and tuple(value(33421)) == (2, 2)
    assert 50712 not in tags, 'Nonlinear RAW requires its linearization table'
    black = rational(value(50714)).reshape(2, 2)
    white = float(value(50717))
    exposure = float(rational(value(33434))[0]) * int(value(34855))
    normalized = raw.copy()
    for y in range(2):
        for x in range(2):
            normalized[y::2, x::2] = (raw[y::2, x::2] - black[y, x]) / (white - black[y, x])
    return normalized, exposure, tags


def merge(folder, output):
    frames = {}
    for route in (0, 3):
        for repeat in (0, 1):
            frames[route, repeat] = read(folder / f'merge-route{route}-{repeat}.dng')
    reference, exposure, tags = frames[0, 0]
    for image, _, other_tags in frames.values():
        assert image.shape == reference.shape
        for code in (33422, 50719, 50720, 50829):
            assert other_tags[code][2] == tags[code][2], f'RAW geometry mismatch: {code}'
    moving = frames[3, 0][0]
    scale = exposure / frames[3, 0][1]
    result = np.empty_like(reference)
    report = {'alignment': False, 'motion_rejection': False, 'high_to_low_exposure_scale': scale,
              'fused_sources': ['merge-route0-0.dng', 'merge-route3-0.dng'], 'cfa_sites': []}
    for y in range(2):
        for x in range(2):
            variances = []
            for route in (0, 3):
                a, ea, _ = frames[route, 0]
                b, eb, _ = frames[route, 1]
                difference = a[y::2, x::2] * (exposure / ea) - b[y::2, x::2] * (exposure / eb)
                # No robust masking: repeated-scene variation is included in this estimate.
                variances.append(max(float(np.var(difference, dtype=np.float64)) / 2, 1e-12))
            a = reference[y::2, x::2]
            b = moving[y::2, x::2]
            # Inverse-variance weighting and a simple saturation taper, no motion masks.
            wa = np.clip((0.99 - a) / 0.09, 0, 1) / variances[0]
            wb = np.clip((0.99 - b) / 0.09, 0, 1) / variances[1]
            denominator = wa + wb
            result[y::2, x::2] = np.divide(wa * a + wb * b * scale, denominator,
                out=a.copy(), where=denominator > 0)
            report['cfa_sites'].append({'y': y, 'x': x, 'variances': variances,
                'low_gain_weight_unclipped': variances[1] / sum(variances)})

    # 16-bit linear CFA container retains fractional fusion precision. The pedestal
    # preserves small negative black-subtracted samples. This does not add sensor DR.
    black_out, white_out = 1024, 65535
    encoded = np.rint(result * (white_out - black_out) + black_out).clip(0, 65535).astype('<u2')
    # Explicit allowlist of reference camera/color/crop/lens-shading metadata.
    # Never retain source strip offsets, raw digests, noise model or source previews.
    preserve = {271, 272, 306, 33421, 33422, 33434, 33437, 34855, 36867, 37386,
                41989, 50706, 50708, 50710, 50711, 50713, 50718, 50719, 50720,
                50721, 50722, 50723, 50724, 50727, 50728, 50730, 50778, 50779,
                50829, 50964, 50965, 51009, 51110}
    extra = [(code, dtype, count, value, False) for code, (dtype, count, value) in tags.items() if code in preserve]
    extra += [(274, 'H', 1, 1, False), (50707, 'B', 4, b'\x01\x04\x00\x00', False),
              (50714, '2I', 4, (black_out, 1) * 4, False), (50717, 'I', 1, white_out, False)]
    output.parent.mkdir(parents=True, exist_ok=True)
    tifffile.imwrite(output, encoded, photometric=32803, metadata=None,
        description='RawLens experimental sequential 0 + 3/0 variance-weighted Bayer merge; no alignment or motion rejection. Exposure metadata describes reference 0.',
        software='RawLens DCG probe simple merge', extratags=extra)
    with tifffile.TiffFile(output) as check:
        assert np.array_equal(check.pages[0].asarray(), encoded)
        assert check.pages[0].tags[50717].value == white_out
        assert check.pages[0].tags[33422].value == tags[33422][2]
    report.update({'width': encoded.shape[1], 'height': encoded.shape[0], 'output': str(output),
                   'output_black': black_out, 'output_white': white_out,
                   'clipped_fraction': float((encoded == white_out).mean()),
                   'source_scene_variance_includes_motion_and_flicker': True,
                   'reference_gain_map_preserved': 51009 in tags})
    output.with_suffix('.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('folder', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    merge(args.folder, args.output)
