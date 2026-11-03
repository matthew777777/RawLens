#!/usr/bin/env python3
"""Collect measured recording stages and immutable artifact identities."""
import hashlib
import json
from pathlib import Path
import statistics
from analyze_recordings import percentile
ROOT=Path(__file__).resolve().parents[2]
DOC=ROOT/'docs/refactor/rcd-replacement'

def run():
    rows=[]
    for directory in ('recordings-requirements-input','recordings-approximate-scale4','recordings-approximate-scale8','recordings-independent-writer','recordings-independent-writer-final'):
        for analysis in sorted((DOC/directory).glob('*/analysis.json')):
            r=json.loads(analysis.read_text());source=analysis.with_name('recording.json');s=json.loads(source.read_text())
            events=s['trace']['events'];costs={}
            for kind in range(10,22):
                values=[e[4]/1e6 for e in events if e[0]==kind and e[4]>=0]
                if values:costs[s['trace']['kinds'].get(str(kind),str(kind))]={'median_ms':statistics.median(values),'p95_ms':percentile(values,.95)}
            movie=source.with_suffix('.mp4')
            rows.append({'report':str(analysis.relative_to(ROOT)),'requested':s['requested'],'scale':s.get('reconstructionScale',1),'encoder_owned_output_requested':s.get('gpuWriterRequested',False),'crop':r['stats']['cropRect'],'source_policy':s['sourcePolicy'],'cadence_contract_passed':r.get('cadence_contract_passed',r['passed']),'accepted':False,'quality_accepted':False if s.get('reconstructionScale',1)>1 else None,'delivered_sensor_fps':r['queued_cadence']['fps_over_sensor_elapsed'],'render_wall_ms':r['rcd_render_wall_ms'],'complete_decode_ok':r['complete_decode_ok'],'stats':r['stats'],'gpu_and_adapter_costs':costs,'recording_sha256':hashlib.sha256(movie.read_bytes()).hexdigest(),'recording_bytes':movie.stat().st_size,'source_report_sha256':hashlib.sha256(source.read_bytes()).hexdigest()})
    return {'scope':'Authorized headless phone diagnostics under uncontrolled workload; no 10-minute soak or qualified performance/approximate-quality acceptance','device':'Xiaomi 25080RABDG','serial':'fe79feha9lmb6hhi','reference':'Pinned RawTherapee public-source baseline; approximate modes explicitly reduce source detail while covering the full crop','mandatory_targets':['1920x1080@30','2560x1440@30','3840x2160@24'],'targets_accepted':False,'runs':rows}

if __name__=='__main__':
    r=run();(DOC/'requirements-recording-summary.json').write_text(json.dumps(r,indent=2)+'\n');print(json.dumps([{'requested':x['requested'],'scale':x['scale'],'fps':x['delivered_sensor_fps'],'median_render_ms':x['render_wall_ms']['median']} for x in r['runs']],indent=2))
