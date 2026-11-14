#!/usr/bin/env python3
"""Prepare read-only DNG crops and exact CPU mirrors of RawLens cleanup variants."""
from pathlib import Path
import argparse,json,hashlib,time
import numpy as np
import tifffile
from scipy import ndimage as ndi
from PIL import Image,ImageDraw

FOOT = np.zeros((5,5),bool);FOOT[::2,::2]=True

def smooth(a,b,x):
 t=np.clip((x-a)/(b-a),0,1);return t*t*(3-2*t)

def cleanup(rgb,neutral,variant):
 if variant=='off':return rgb.copy()
 white=np.maximum(np.array(neutral,dtype='f4')/max(neutral),1e-4)
 c=np.maximum(rgb,0)/white;d=c[:,:,[0,2]]-c[:,:,1,None]
 fine=np.stack([ndi.median_filter(d[:,:,i],size=3,mode='nearest') for i in range(2)],axis=-1)
 if variant=='wide':
  wide=np.stack([ndi.median_filter(d[:,:,i],footprint=FOOT,mode='nearest') for i in range(2)],axis=-1)
  d=np.where((np.sum(wide*wide,axis=-1)<np.sum(fine*fine,axis=-1))[:,:,None],wide,fine)
 else:d=fine
 hi=ndi.maximum_filter(c[:,:,1],footprint=FOOT,mode='nearest');lo=ndi.minimum_filter(c[:,:,1],footprint=FOOT,mode='nearest')
 edge=1-smooth(.15,.35,lo/np.maximum(hi,1e-6))
 ratio=d[:,:,0]/np.where(d[:,:,1]>0,d[:,:,1],1)
 k=edge*smooth(-.6,-.1,ratio)*(d[:,:,1]>0)
 d=d+(np.minimum(d,0)-d)*k[:,:,None]
 return np.maximum(np.stack([c[:,:,1]+d[:,:,0],c[:,:,1],c[:,:,1]+d[:,:,1]],axis=-1),0)*white

def vals(tags,name,default=None):
 tag=tags.get(name)
 if tag is None:return default
 v=tag.value
 if isinstance(v,(int,float)):return [float(v)]
 if int(tag.dtype) in (5,10):return [v[i]/v[i+1] for i in range(0,len(v),2)]
 return list(v)

def metadata(tags,w,h):
 neutral=vals(tags,'AsShotNeutral',[1,1,1]);container={'sensorArrangment':'rggb','blackLevel':[0]*4,'whiteLevel':65535}
 for field,tag in [('colorMatrix1','ColorMatrix1'),('colorMatrix2','ColorMatrix2'),('calibrationMatrix1','CameraCalibration1'),('calibrationMatrix2','CameraCalibration2'),('forwardMatrix1','ForwardMatrix1'),('forwardMatrix2','ForwardMatrix2')]:
  v=vals(tags,tag)
  if v is not None:container[field]=v
 for field,tag in [('referenceIlluminant1','CalibrationIlluminant1'),('referenceIlluminant2','CalibrationIlluminant2')]:
  v=vals(tags,tag)
  if v is not None:container[field]=int(v[0])
 return container,{'width':w,'height':h,'timestamp':'0','asShotNeutral':neutral,'iso':50,'exposureTime':593000}

def main():
 p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);p.add_argument('--inputs',type=Path,required=True);args=p.parse_args()
 args.output.mkdir(parents=True,exist_ok=True);args.inputs.mkdir(parents=True,exist_ok=True)
 home=Path('/Users/monikamalinowska')
 files=sorted((home/'Downloads/LKRL_parity').glob('*-x1-lkr.dng'))
 files+=[f.with_name(f.name.replace('-x1-','-x2-')) for i,f in enumerate(files) if i in [0,2,4,10,12,16,20]]
 files+=sorted((home/'Downloads/flower').glob('*.dng'))[:2]
 # The reported vivo foliage scene is a different camera and an important stress case.
 files+=[home/'Downloads/RawLens-color-fixed/IMG_20261114_101031_103_color_fixed.dng']
 cases=[];rows=[];size=384
 for si,path in enumerate(files):
  if not path.exists():continue
  with tifffile.TiffFile(path) as t:
   page=t.pages[0]
   if len(page.shape)!=3:continue
   image=tifffile.memmap(path,mode='r');h,w=image.shape[:2]
   container,frame=metadata(page.tags,size,size);neutral=frame['asShotNeutral']
   white=np.maximum(np.array(neutral,dtype='f4')/max(neutral),1e-4)
   whites=np.array(vals(page.tags,'WhiteLevel',[65535]*3),dtype='f4');whitelevel=float(whites.max())
   center=((w-size)//2//2*2,(h-size)//2//2*2)
   best=(-1,center)
   for y in range(0,h-size+1,size):
    for x in range(0,w-size+1,size):
     if abs(x-center[0])<size and abs(y-center[1])<size:continue
     a=image[y:y+size:8,x:x+size:8,:3].astype('f4')/whitelevel/white
     mx=a.max(axis=-1);mn=a.min(axis=-1)
     sat=(mx-mn)/np.maximum(mx,1e-6);valid=(mx>.03)&(mx<2)
     score=float(np.mean(sat*valid)) if np.std(a[:,:,1])>.01 else 0
     if score>best[0]:best=(score,(x,y))
   model=page.tags.get('Model');model=str(model.value) if model else 'unknown'
   for ci,(label,(x,y)) in enumerate([('center',center),('color_detail',best[1])]):
    raw=np.ascontiguousarray(image[y:y+size,x:x+size,:3],dtype='f4')/whitelevel
    caseid=f'{si:02d}_{ci}_{path.stem}'
    spec={'id':caseid,'source':str(path),'camera':model,'crop':[x,y,size,size],'container':container,'frame':frame,'width':size,'height':size,'synthetic':False}
    outputs={}
    for mode in ['off','old','wide']:
     result=cleanup(raw,neutral,mode).astype('<f4');result.tofile(args.inputs/(caseid+'_'+mode+'.f32'));outputs[mode]=result
    a=outputs['old']/white;b=outputs['wide']/white
    ca=a[:,:,[0,2]]-a[:,:,1,None];cb=b[:,:,[0,2]]-b[:,:,1,None]
    energy_a=np.linalg.norm(ca,axis=-1);energy_b=np.linalg.norm(cb,axis=-1)
    colored=energy_a>.03
    rows.append({'id':caseid,'source':str(path),'camera':model,'changed_percent':float(100*np.mean(np.max(np.abs(b-a),axis=-1)>1/1024)),
     'colored_pixels_losing_over_20_percent':float(100*np.mean((energy_b[colored]<.8*energy_a[colored]))) if np.any(colored) else 0,
     'mean_chroma_change_percent':float(100*(energy_b.mean()/max(energy_a.mean(),1e-9)-1)),
     'green_max_abs_change':float(np.max(abs(a[:,:,1]-b[:,:,1]))),'synthetic':False})
    cases.append(spec)
   print(f'{si+1}/{len(files)} {path.name}',flush=True)
 # Known clean targets: the algorithm has no ground truth that can label a small patch "false".
 n=384;target=np.full((n,n,3),.15,dtype='f4');targets=[]
 colors={'red':[.6,.15,.15],'green':[.15,.6,.15],'blue':[.15,.15,.6],'magenta':[.6,.15,.6],'cyan':[.15,.6,.6],'yellow':[.6,.6,.15]}
 for cy,(color,rgb) in enumerate(colors.items()):
  for cx,side in enumerate([1,2,3,4,5,8,16,32]):
   x=22+cx*44;y=22+cy*58;target[y:y+side,x:x+side]=rgb
   targets.append({'color':color,'side':side,'x':x,'y':y,'rgb':rgb})
 identity=[1,0,0,0,1,0,0,0,1]
 # sRGB -> XYZ D50, so native RGB represents intentional sRGB primary colours.
 forward=[.4360747,.3850649,.1430804,.2225045,.7168786,.0606169,.0139322,.0971045,.7141733]
 spec={'id':'clean_color_targets','source':'synthetic: clean colored squares on gray; no false color','camera':'known clean target','crop':[0,0,n,n],'container':{'sensorArrangment':'rggb','blackLevel':[0]*4,'whiteLevel':65535,'forwardMatrix1':forward,'referenceIlluminant1':21},'frame':{'width':n,'height':n,'timestamp':'0','asShotNeutral':[1,1,1]},'width':n,'height':n,'synthetic':True,'targets':targets}
 output={mode:cleanup(target,[1,1,1],mode) for mode in ['off','old','wide']}
 for mode,a in output.items():a.astype('<f4').tofile(args.inputs/(spec['id']+'_'+mode+'.f32'))
 for item in targets:
  x=item['x']+item['side']//2;y=item['y']+item['side']//2
  a=output['old'][y,x];b=output['wide'][y,x];original=target[y,x]
  chroma=lambda z:float(np.linalg.norm(z[[0,2]]-z[1]))
  item['old_center_chroma_retained_percent']=100*chroma(a)/max(chroma(original),1e-9)
  item['wide_center_chroma_retained_percent']=100*chroma(b)/max(chroma(original),1e-9)
 cases.append(spec)
 (args.output/'manifest.json').write_text(json.dumps(cases,indent=2));(args.inputs/'manifest.json').write_text(json.dumps(cases))
 (args.output/'linear-metrics.json').write_text(json.dumps(rows,indent=2));(args.output/'clean-target-results.json').write_text(json.dumps(targets,indent=2))
 (args.output/'provenance.json').write_text(json.dumps({'dng_files':len(set(x['source'] for x in cases if not x['synthetic'])),'dng_crops':len(cases)-1,'dimensions':size,'variants':['off','old 3x3 median + defringe','wide two-radius median + defringe'],'cleanup_shader_sha256':hashlib.sha256(Path('app/src/main/assets/shaders/amaze/cleanup.glsl').read_bytes()).hexdigest()},indent=2))
 print('Prepared',len(cases),'cases',flush=True)
if __name__=='__main__':main()
