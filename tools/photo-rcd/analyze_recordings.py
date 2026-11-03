#!/usr/bin/env python3
"""Delivered-cadence assessment from sensor identities plus complete file decode."""
import argparse
import json
import math
from pathlib import Path
import statistics
import subprocess

def percentile(values,p):return sorted(values)[max(0,min(len(values)-1,math.ceil(len(values)*p)-1))] if values else None
def analyze(folder):
    source=folder/'recording.json'
    if not source.exists():return {'passed':False,'error':'No recording evidence file'}
    report=json.loads(source.read_text());w,h,fps=report['requested'];events=report.get('trace',{}).get('events',[])
    groups={i:[e for e in events if e[0]==i] for i in range(1,7)}
    submitted=groups[3];queued=groups[4];muxed=groups[5]
    result={'scope':'real recording cadence; no pass from PTS alone','requested':[w,h,fps],'recording_error':report.get('error',report.get('recordingError')),'stats':report.get('stats',{}),'actualOutput':report.get('actualOutput'),'trace_counts':{str(k):len(v) for k,v in groups.items()}}
    if submitted:
        # Bound warmup to two seconds after first submitted sensor timestamp.
        start=submitted[0][2]+2_000_000_000
        capture=[e for e in groups[1] if e[2]>=start];captured={e[2]:e[3] for e in groups[1]}
        distinct_submitted=[e for e in submitted if e[2]>=start]
        distinct_queued=[e for e in queued if e[2]>=start]
        def cadence(rows):
            timestamps=sorted(e[2] for e in rows);gaps=[b-a for a,b in zip(timestamps,timestamps[1:])]
            duration=(timestamps[-1]-timestamps[0])/1e9 if len(timestamps)>1 else 0
            return {'frames':len(rows),'unique_sensor_timestamps':len(set(timestamps)),'fps_over_sensor_elapsed':(len(rows)-1)/duration if duration else 0,'p95_gap_ms':percentile(gaps,.95)/1e6 if gaps else None,'max_gap_ms':max(gaps)/1e6 if gaps else None,'duration_s':duration}
        result['capture_cadence']=cadence(capture);result['submitted_cadence']=cadence(distinct_submitted);result['queued_cadence']=cadence(distinct_queued)
        timestamps=sorted(e[2] for e in distinct_queued)
        if timestamps:
            windows=[sum(start+i*1_000_000_000<=t<start+(i+1)*1_000_000_000 for t in timestamps) for i in range(max(0,int((timestamps[-1]-start)/1e9)))]
            result['queued_whole_second_counts']=windows
        queue_times={e[2] for e in distinct_queued};capture_times={e[2] for e in capture if not queue_times or e[2]<=max(queue_times)}
        result['captured_not_queued']=len(capture_times-queue_times)
        result['submitted_missing_exact_capture_result']=sum(e[2] not in captured for e in submitted)
        # Match actual mux PTS to source PTS mappings (without resynthesized ids).
        mapping={e[4]:e[2] for e in queued};result['mux_pts_without_source']=sum(e[3] not in mapping for e in muxed)
        result['duplicate_mux_pts']=len(muxed)-len({e[3] for e in muxed})
        values=[e[4]/1e6 for e in groups[6]]
        result['rcd_render_wall_ms']={'median':statistics.median(values),'p95':percentile(values,.95),'max':max(values)} if values else {}
    movie=folder/'recording.mp4'
    if movie.exists():
        probe=subprocess.run(['ffprobe','-v','error','-count_frames','-show_streams','-show_format','-of','json',str(movie)],text=True,capture_output=True)
        result['ffprobe_returncode']=probe.returncode
        if probe.returncode==0:result['media']=json.loads(probe.stdout)
        decoded=subprocess.run(['ffmpeg','-v','error','-i',str(movie),'-map','0:v:0','-map','0:a:0?','-f','null','-'],text=True,capture_output=True)
        result['complete_decode_ok']=decoded.returncode==0;result['decode_errors']=decoded.stderr
    stats=result['stats'];cadence=result.get('queued_cadence',{})
    result['passed']=bool(not result['recording_error'] and result.get('complete_decode_ok') and
        stats.get('rcdFrames',0)>0 and stats.get('fusedFrames',1)==0 and
        stats.get('framesGraded')==stats.get('rcdFrames') and stats.get('framesDropped')==0 and
        stats.get('frameErrors')==0 and stats.get('inputStarved')==0 and stats.get('degradedFrames')==0 and
        stats.get('hasAudio') and stats.get('true10Bit') and result.get('captured_not_queued',1)==0 and
        result.get('duplicate_mux_pts',1)==0 and result.get('mux_pts_without_source',1)==0 and
        cadence.get('frames',0)==cadence.get('unique_sensor_timestamps',-1) and
        cadence.get('fps_over_sensor_elapsed',0)>=fps*.995 and cadence.get('p95_gap_ms',1e9)<=1200/fps and cadence.get('max_gap_ms',1e9)<=1500/fps and
        all(fps-1<=n<=fps+1 for n in result.get('queued_whole_second_counts',[])))
    result['reconstruction_scale']=report.get('reconstructionScale',1)
    result['cadence_contract_passed']=result['passed']
    if result['reconstruction_scale']>1:
        result['quality_contract']='Unaccepted approximate geometry; full-source quality equivalence is not claimed'
        result['passed']=False
    return result
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('folder',type=Path);a=p.parse_args()
    rows=[]
    for f in sorted(a.folder.iterdir()):
        if f.is_dir() and (f/'instrumentation.txt').exists():
            r=analyze(f);(f/'analysis.json').write_text(json.dumps(r,indent=2)+'\n');rows.append({'mode':f.name,'passed':r['passed'],'fps':r.get('queued_cadence',{}).get('fps_over_sensor_elapsed'),'render_ms':r.get('rcd_render_wall_ms'),'error':r.get('recording_error',r.get('error'))})
    print(json.dumps(rows,indent=2));(a.folder/'analysis-summary.json').write_text(json.dumps(rows,indent=2)+'\n')
