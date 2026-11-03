#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Run isolated MHC correctness, sustained GPU budgets, and camera/codec checks.

Install the -PmhcHeadless deviceTest and androidTest APKs first.
No preview/UI is opened. No frame may be dropped in the recording checks.
"""
import argparse,json,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--adb',default='adb');p.add_argument('--output',type=Path,required=True);p.add_argument('--seconds',type=int,default=60);p.add_argument('--iterations',type=int,default=300);p.add_argument('--precision',choices=['fp32','mixed'],default='fp32');p.add_argument('--compute-only',action='store_true');a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
pkg='com.matthew.rawlens.mhccheck';runner=pkg+'.test/androidx.test.runner.AndroidJUnitRunner'
def adb(*args):return subprocess.run([a.adb,*args],check=True,capture_output=True,text=True).stdout
def test(name,cls,params):
 print('START',name,flush=True)
 cmd=['shell','am','instrument','-w','-r','-e','class','com.matthew.rawlens.'+cls]
 for k,v in params.items():cmd+=['-e',k,str(v)]
 text=adb(*cmd,runner);(a.output/(name+'.txt')).write_text(text)
 passed='OK (' in text and 'FAILURES' not in text
 print(name, 'PASS' if passed else 'FAIL',flush=True)
 return passed
results={}
results['image']=test('image','MhcHighlightDeviceTest#clippedWaterAndChromaCells',{})
results['gpu']=test('gpu','MhcHighlightDeviceTest#sustainedHeadlessFrameBudgets',{'rawlensMhcPerf':'true','iterations':a.iterations,'precision':a.precision})
for w,h,fps in ([] if a.compute_only else [(1920,1080,30),(2560,1440,30),(3840,2160,24)]):
 name=f'{w}x{h}-{fps}fps'
 adb('shell','rm','-f',f'/sdcard/Android/data/{pkg}/files/encoded_video_mhc_single.mp4')
 results[name]=test(name,'EncodedVideoRecordDeviceTest#recordMain10Mp4',{'rawlensEncodedVideo':'true','singleMode':'true','zeroDrops':'true','seconds':a.seconds,'width':w,'height':h,'fps':fps,'profile':'ACES','frameStages':'true','fast':'true'})
 video=a.output/(name+'.mp4')
 pull=subprocess.run([a.adb,'pull',f'/sdcard/Android/data/{pkg}/files/encoded_video_mhc_single.mp4',str(video)],capture_output=True,text=True)
 if pull.returncode:
  (a.output/(name+'-cadence.json')).write_text(json.dumps({'valid':False,'reason':'no artifact from this test','adb_error':pull.stderr},indent=2))
  results[name+'-cadence']=False
  continue
 probe=subprocess.run(['ffprobe','-v','error','-select_streams','v:0','-show_entries','packet=pts_time','-of','json',str(video)],check=True,capture_output=True,text=True)
 frames=json.loads(probe.stdout)['packets'];pts=sorted(float(f['pts_time']) for f in frames if 'pts_time' in f)
 gaps=[b-c for c,b in zip(pts,pts[1:])];bad=[{'frame':i+1,'gap_ms':g*1000} for i,g in enumerate(gaps) if g>1.5/fps]
 data={'frames':len(pts),'duration_s':pts[-1]-pts[0] if pts else 0,'observed_fps':(len(pts)-1)/(pts[-1]-pts[0]) if len(pts)>1 else 0,'max_gap_ms':max(gaps,default=0)*1000,'gaps_over_1_5_frames':bad}
 (a.output/(name+'-cadence.json')).write_text(json.dumps(data,indent=2));print(name,data,flush=True)
 results[name+'-cadence']=not bad and len(pts)>fps*(a.seconds-2) and data['observed_fps']>=fps*.995
for file in ['mhc-highlight-validation.txt','mhc-headless-performance.txt']:
 subprocess.run([a.adb,'pull',f'/sdcard/Android/data/{pkg}/files/{file}',str(a.output/file)],check=False,capture_output=True)
(a.output/'summary.json').write_text(json.dumps(results,indent=2));print(results,flush=True)
raise SystemExit(0 if all(results.values()) else 1)
