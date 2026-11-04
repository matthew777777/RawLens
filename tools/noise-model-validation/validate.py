#!/usr/bin/env python3
"""Controlled model comparison using compiled production C++ and pinned Photon Java.

Shared measurements isolate storage/fitting from the different image pipelines.
Reports variance prediction error, not denoised-image quality. Stdlib only.
"""
import argparse
import ctypes as C
import json
import hashlib
import math
from pathlib import Path
import random
import statistics
import subprocess

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
PIN = 'b2eff821a858033515eb2700e7292befecc95225'


def build(out, legacy=None):
    so = out / ('legacy.so' if legacy else 'model.so')
    cmd = ['clang++', '-std=c++17', '-O2', '-shared', '-fPIC', str(HERE / 'model_probe.cpp'), '-o', str(so)]
    if legacy:
        cmd += ['-DLEGACY', f'-DMODEL_HEADER="{legacy.resolve()}"']
    subprocess.run(cmd, check=True)
    lib = C.CDLL(str(so))
    lib.predict.argtypes = [C.POINTER(C.c_double), C.c_int, C.c_float, C.c_float, C.c_float,
                            C.c_int, C.c_int, C.POINTER(C.c_double)]
    lib.estimate_frame.argtypes = [C.c_float, C.c_float, C.c_int, C.c_int, C.c_int, C.POINTER(C.c_double)]
    return lib


def predict(lib, samples, iso, evidence=None):
    rows = (C.c_double * (len(samples) * 6))(*(v for s in samples for v in s))
    out = (C.c_double * 3)()
    ea, eb, bins = evidence or (0, 0, 0)
    ok = lib.predict(rows, len(samples), iso, ea, eb, bins, evidence is not None, out)
    return tuple(out[:2]) if ok else None


def truth(iso, analog=800, scale=1):
    return scale * (2e-7 * iso + 2e-6), scale * (2e-12 * iso**2 + 3e-6 * max(iso / analog, 1)**2)


def errors(ab, target):
    # Equal log-spaced brightnesses give deep shadows a real vote.
    return [abs((ab[0]*x + ab[1]) / (target[0]*x + target[1]) - 1)
            for x in (0.001, 0.003, 0.01, 0.03, 0.1, 0.3, 0.7)]


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--out', type=Path, default=Path('/tmp/rawlens-noise-validation'))
    ap.add_argument('--legacy-header', type=Path)
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    subprocess.run(['clang++', '-std=c++17', '-O2', str(HERE / 'model_test.cpp'),
                    '-o', str(args.out / 'model-test')], check=True)
    subprocess.run([str(args.out / 'model-test'), str(args.out / 'store-test.txt')], check=True)
    lib = build(args.out)
    legacy = build(args.out, args.legacy_header) if args.legacy_header else None
    subprocess.run(['javac', '-d', str(args.out), str(HERE / 'PhotonIsoBin.java')], check=True)
    rng = random.Random(241104)
    cases = []
    def case(name, rows, iso, photon_rows, evidence=None):
        cases.append((name, rows, iso, photon_rows, evidence))
    # Multiple sensors, repeated random realizations, balanced and skewed ISO
    # coverage, and held-out ISO queries. Same noisy input for both methods.
    for scale in (0.25, 1, 4):
        for repeat in range(12):
            rows = []
            for iso in (100, 200, 400, 800, 1600, 3200):
                for j in range(10):
                    a, b = truth(iso, scale=scale)
                    leak = 1 if j < 6 else (2 + 4 * rng.random())
                    jitter = math.exp(rng.gauss(0, 0.05))
                    rows.append((iso, a * leak * jitter, b * leak * jitter, 8, 800, len(rows)+1))
            for iso in (100, 200, 400, 800, 1600, 3200):
                pr = [(s[1], s[2]) for s in rows if s[0] == iso]
                case(f'texture_s{scale}_{repeat}_{iso}', rows, iso, pr)
            # PhotonCamera returns the containing whole-stop bin's average.
            for iso, biniso in ((140, 100), (280, 200), (560, 400), (1100, 800), (2200, 1600)):
                pr = [(s[1], s[2]) for s in rows if s[0] == biniso]
                case(f'heldout_s{scale}_{repeat}_{iso}', rows, iso, pr)
    # Get PhotonCamera's result from its actual, unchanged Java implementation.
    text = ''.join(str(len(c[3]))+' '+' '.join(f'{a} {b}' for a,b in c[3])+'\n' for c in cases)
    result = subprocess.run(['java', '-cp', str(args.out), 'PhotonIsoBin'], input=text,
                            text=True, capture_output=True, check=True)
    photon = [tuple(map(float, line.split())) for line in result.stdout.splitlines()]
    report = {'photon_revision': PIN, 'scope': 'shared-estimate model variance prediction; no image-quality claim',
              'cases': len(cases), 'groups': {},
              'model_sha256': hashlib.sha256((ROOT / 'app/src/main/cpp/wavelet/noise_model.h').read_bytes()).hexdigest(),
              'reference_java_sha256': hashlib.sha256((HERE / 'PhotonIsoBin.java').read_bytes()).hexdigest()}
    for group in ('texture', 'heldout'):
        scores = {'rawlens': [], 'photon': [], 'legacy': []}
        for c, pc in zip(cases, photon):
            name, rows, iso, _, evidence = c
            if not name.startswith(group):
                continue
            scale = float(name.split('_')[1][1:])
            target = truth(iso, scale=scale)
            pred = predict(lib, rows, iso, evidence)
            assert pred is not None, name
            scores['rawlens'] += errors(pred, target)
            scores['photon'] += errors(pc, target)
            if legacy:
                scores['legacy'] += errors(predict(legacy, rows, iso, evidence), target)
        report['groups'][group] = {k: {'mean_relative_variance_error': statistics.mean(v),
                                       'p95_relative_variance_error': sorted(v)[int(.95*(len(v)-1))]}
                                   for k,v in scores.items() if v}
        # At calibrated ISOs require parity within 1 percentage point; at
        # held-out ISOs require improvement over the full-stop bin baseline.
        assert statistics.mean(scores['rawlens']) <= statistics.mean(scores['photon']) + 0.01, group
    # Physical gain law on uncontaminated calibration data, warmup at just
    # one ISO, replay suppression, unsupported extrapolation, bounded evidence.
    rows = [(iso, *truth(iso), 8, 800, i+1)
            for i,iso in enumerate((100,200,400,800,1600,3200,100,200,400,800,1600,3200))]
    for iso in (150, 600, 1200, 2400):
        assert max(errors(predict(lib, rows, iso), truth(iso))) < 0.01, ('gain law',iso)
    same = [(400, *truth(400), 8, 800, i+1) for i in range(3)]
    assert predict(lib, same, 400) is not None
    assert predict(lib, [same[0]]*100, 400) is None, 'replay must not satisfy warmup'
    assert predict(lib, rows, 12800) is None, 'unsupported extrapolation'
    assert predict(lib, rows + [(float('nan'),1,1,8,800,99)], 400) is not None
    base = predict(lib, rows, 400)
    contaminated = predict(lib, rows, 400, (base[0]*8,base[1]*8,40))
    assert max(errors(contaminated, base)) < 1e-5, 'textured evidence must not lift prior'
    report['invariants'] = 'PASS: gain law, same-ISO warmup, replay, extrapolation, nonfinite input, evidence bound'
    # End-to-end blind estimates: known injected noise, four CFA origins,
    # flat and partially textured scenes, before/after ISO-model fitting.
    estimates = []
    for cfa in range(4):
        for textured in (0,1):
            target = truth(800)
            out = (C.c_double * 3)()
            assert lib.estimate_frame(*target, 900+cfa, cfa, textured, out)
            err = statistics.mean(errors(tuple(out[:2]), target))
            estimates.append({'cfa': cfa, 'textured': bool(textured), 'a':out[0], 'b':out[1],
                              'bins':out[2], 'mean_relative_variance_error':err})
            # Textured scenes are diagnostics: without clean patches the
            # variance can contain real texture. Do not relabel it ground truth.
            if not textured:
                assert err < 0.2, ('flat blind estimator', cfa, err)
    report['blind_estimator'] = estimates
    # Feed actual production frame estimates into both persistent models.
    # The held-out ISO is absent from training; both see identical samples.
    measured = []
    for iso in (100, 200, 400, 800, 1600, 3200):
        for shot in range(6):
            out = (C.c_double * 3)()
            assert lib.estimate_frame(*truth(iso), 1230 + shot, shot % 4, shot >= 4, out)
            measured.append((iso, out[0], out[1], int(out[2]), 800, len(measured)+1))
    queries = (140, 280, 560, 1100, 2200)
    pc_bins = [100 * 2**int(math.floor(math.log2(iso/100)+0.5)) for iso in queries]
    lists = [[s for s in measured if s[0] == biniso] for biniso in pc_bins]
    pc_input = ''.join(str(len(ss))+' '+' '.join(f'{s[1]} {s[2]}' for s in ss)+'\n' for ss in lists)
    result = subprocess.run(['java', '-cp', str(args.out), 'PhotonIsoBin'], input=pc_input,
                            text=True, capture_output=True, check=True)
    pc_predictions = [tuple(map(float, line.split())) for line in result.stdout.splitlines()]
    measured_errors = {'rawlens': [], 'photon_store_on_same_estimates': []}
    for iso, pc in zip(queries, pc_predictions):
        pred = predict(lib, measured, iso)
        assert pred is not None, ('measured interpolation', iso)
        measured_errors['rawlens'] += errors(pred, truth(iso))
        measured_errors['photon_store_on_same_estimates'] += errors(pc, truth(iso))
    report['production_estimator_then_model'] = {
        k: {'mean_relative_variance_error': statistics.mean(v),
            'p95_relative_variance_error': sorted(v)[int(.95*(len(v)-1))]}
        for k,v in measured_errors.items()}
    assert statistics.mean(measured_errors['rawlens']) < statistics.mean(measured_errors['photon_store_on_same_estimates'])
    (args.out / 'report.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
