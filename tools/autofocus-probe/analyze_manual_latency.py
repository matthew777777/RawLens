#!/usr/bin/env python3
"""Summarize exact-frame lens feedback. Optical settling is provisional, not an AF verdict."""
import argparse
import json
import math
import statistics
from pathlib import Path


def summarize(records):
    config = next(r for r in records if r['type'] == 'config')
    commands = [r for r in records if r['type'] == 'command']
    frames = [r for r in records if r['type'] == 'frame']
    step = config.get('focusStep')
    tolerance = min(.02, step * .2) if step else max(.02, config['maxFocus'] * .02)
    rows = []
    for command in commands:
        samples = sorted((f for f in frames if f['command'] == command['command']), key=lambda f: f['frame'])
        def delay(f, field):
            value = f.get(field)
            return None if value is None else (value - command['submittedNs']) / 1e6
        def stable(f):
            p = f.get('reportedFocus')
            return p is not None and abs(p - command['target']) <= tolerance and f.get('lensState') == 0
        run_index = next((i for i in range(len(samples) - 2) if all(stable(f) for f in samples[i:i + 3])), None)
        run = samples[run_index] if run_index is not None else None
        def available_delay(f):
            times = [f.get(k) for k in ('imageReceivedNs', 'resultReceivedNs') if f.get(k) is not None]
            return (max(times) - command['submittedNs']) / 1e6 if times else None
        # Sensor timestamps share elapsedRealtimeNanos only for REALTIME (1).
        row = dict(command=command['command'], target=command['target'], pairedFrames=len(samples),
                   firstTaggedCallbackMs=delay(samples[0], 'startedCallbackNs') if samples else None,
                   stableMetadataCallbackMs=delay(run, 'startedCallbackNs') if run else None,
                   stableSensorMs=delay(run, 'timestampNs') if run and config.get('timestampSource') == 1 else None,
                   firstFrame=samples[0]['frame'] if samples else None,
                   metadataTolerance=tolerance,
                   threeFrameEvidenceAvailableMs=available_delay(samples[run_index + 2]) if run_index is not None else None)
        tail = samples[-5:]
        if len(tail) >= 5:
            score = statistics.median(f['gradientEnergy'] for f in tail)
            mean = statistics.median(f['mean'] for f in tail)
            row['tailScore'] = score
            row['tailMean'] = mean
            row['tailRelativeMAD'] = statistics.median(abs(f['gradientEnergy'] - score) for f in tail) / max(score, 1e-12)
            # Three consecutive valid observations agreeing with the final plateau.
            def optical_ok(f):
                return stable(f) and f['validPixels'] > 2000 and score > 1e-8 and abs(f['gradientEnergy'] - score) <= .15 * score and abs(f['mean'] - mean) <= max(.005, .05 * mean)
            plateau = next((samples[i] for i in range(len(samples) - 2) if all(optical_ok(f) for f in samples[i:i + 3])), None)
            row['provisionalOpticalCallbackMs'] = delay(plateau, 'startedCallbackNs') if plateau else None
        rows.append(row)
    def distribution(field):
        values = sorted(r[field] for r in rows if r.get(field) is not None)
        return {'count': len(values), 'medianMs': statistics.median(values) if values else None,
                'p95Ms': values[max(0, math.ceil(.95 * len(values)) - 1)] if values else None}
    scores = [r['tailScore'] for r in rows if r.get('tailScore', 0) > 0]
    return dict(config=config, commands=rows, captureFailures=sum(r['type'] == 'captureFailure' for r in records),
                metadataFeedback=distribution('stableMetadataCallbackMs'),
                threeFrameEvidenceAvailable=distribution('threeFrameEvidenceAvailableMs'),
                provisionalOpticalFeedback=distribution('provisionalOpticalCallbackMs'),
                scoreRangeRatio=max(scores) / min(scores) if scores else None,
                limitations=['Callback delays include delivery latency; REALTIME sensor delays are reported separately.',
                             'Gradient energy is a provisional metric; scene motion, exposure and noise can change it.',
                             'A plateau at the commanded position is not proof of correct focus or mechanical settling.',
                             'One camera/stream/scene run does not qualify a production settling policy.'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('trace', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = summarize([json.loads(line) for line in args.trace.read_text().splitlines() if line.strip()])
    text = json.dumps(result, indent=2, allow_nan=False) + '\n'
    if args.output:
        args.output.write_text(text)
    else:
        print(text, end='')

if __name__ == '__main__':
    main()
