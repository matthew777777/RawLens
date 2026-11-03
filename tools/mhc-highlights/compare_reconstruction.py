# SPDX-License-Identifier: GPL-3.0-or-later
import numpy as np
from PIL import Image,ImageDraw
from pathlib import Path
import argparse,subprocess,rawpy,json
parser=argparse.ArgumentParser(description='Frame-specific MHC/RCD controlled reconstruction comparison')
parser.add_argument('dng',type=Path);parser.add_argument('oracle',type=Path);parser.add_argument('output',type=Path)
args=parser.parse_args();root=args.output;root.mkdir(parents=True,exist_ok=True)
with rawpy.imread(str(args.dng)) as raw:
    assert raw.white_level==1023 and raw.black_level_per_channel==[64]*4
    assert raw.raw_pattern.tolist()==[[3,2],[0,1]], 'expected GBRG'
    a=(raw.raw_image_visible.astype(np.float32)-64)/959
a=np.clip(a[960:1940,:1540],0,1);h,w=a.shape
a.astype('<f4').tofile(root/'cfa.f32')
subprocess.run([str(args.oracle),str(w),str(h),str(w),'0','0',str(w),str(h),'GBRG',str(root/'cfa.f32'),str(root/'rcd.f32'),'normalized-rgb','-'],check=True)
metrics={}
def shift(v,x,y):
 p=np.pad(v,4,mode='reflect');return p[4+y:4+y+h,4+x:4+x+w]
t=lambda x,y:shift(a,x,y)
c=a;n=t(0,-1);s=t(0,1);w1=t(-1,0);e=t(1,0);n2=t(0,-2);s2=t(0,2);w2=t(-2,0);e2=t(2,0);nw=t(-1,-1);ne=t(1,-1);sw=t(-1,1);se=t(1,1)
y,x=np.indices(a.shape);q=(y%2)*2+x%2;red=q==2;blue=q==1;isG=~(red|blue)
gh=.5*(w1+e)+.25*(2*c-w2-e2);gv=.5*(n+s)+.25*(2*c-n2-s2)
dh=abs(w1-e)+.5*abs(2*c-w2-e2);dv=abs(n-s)+.5*abs(2*c-n2-s2)
soft=(gh*(dv*dv+1e-8)+gv*(dh*dh+1e-8))/(dh*dh+dv*dv+2e-8)
row=(5*c+4*(w1+e)-w2-e2-nw-ne-sw-se+.5*(n2+s2))*.125
col=(5*c+4*(n+s)-n2-s2-nw-ne-sw-se+.5*(w2+e2))*.125
opp=(6*c+2*(nw+ne+sw+se)-1.5*(n2+s2+w2+e2))*.125
def assemble(g,op=opp,ew=row,ns=col):
 return np.stack([np.where(red,c,np.where(blue,op,np.where(q==0,ns,ew))),np.where(isG,c,g),np.where(blue,c,np.where(red,op,np.where(q==0,ew,ns)))],-1)
methods={'standard-MHC':assemble(.5*(gh+gv)),'soft-green-MHC':assemble(soft)}
# Coherent green-anchored differences: interpolate from a reconstructed
# green plane at the SAME chroma sample, without raw-side level ratios.
def cdiff(g):
 plane=np.maximum(np.where(isG,c,g),0).astype(np.float16).astype(np.float32)
 g=np.where(isG,c,plane)
 gw=shift(plane,-1,0);ge=shift(plane,1,0);gn=shift(plane,0,-1);gs=shift(plane,0,1)
 eps=.001
 ww=eps+abs(g-ge)+abs(c-e2);we=eps+abs(g-gw)+abs(c-w2)
 wn=eps+abs(g-gs)+abs(c-s2);ws=eps+abs(g-gn)+abs(c-n2)
 ew=g+(ww*(w1-gw)+we*(e-ge))/(ww+we)
 ns=g+(wn*(n-gn)+ws*(s-gs))/(wn+ws)
 gnw=shift(plane,-1,-1);gne=shift(plane,1,-1);gsw=shift(plane,-1,1);gse=shift(plane,1,1)
 wnw=1/(eps+abs(g-gnw)+abs(nw-se));wse=1/(eps+abs(g-gse)+abs(nw-se))
 wne=1/(eps+abs(g-gne)+abs(ne-sw));wsw=1/(eps+abs(g-gsw)+abs(ne-sw))
 op=g+(wnw*(nw-gnw)+wne*(ne-gne)+wsw*(sw-gsw)+wse*(se-gse))/(wnw+wne+wsw+wse)
 return assemble(g,op,ew,ns)
methods['coherent-green-difference']=cdiff(soft)
rcd=np.fromfile(root/'rcd.f32',dtype='<f4').reshape(h,w,3)
methods['RCD-reference']=rcd
methods['RCD-green-coherent-difference']=cdiff(rcd[...,1])
# Same development for all methods. No creative saturation or extra
# highlight neutralization: these comparisons isolate interpolation.
gains=np.array([1.9296875,1.,1.60546875]);ccm=np.array([[1.652374,-.5976887,-.05468537],[-.12891701,1.3691307,-.24021374],[.01562188,-.499987,1.4843651]])
for name,v in methods.items():
 np.save(root/(name+'.npy'),v)
 lin=(np.maximum(v,0)*gains)@ccm.T
 enc=np.where(lin<.018,4.5*lin,1.099*np.maximum(lin,0)**.45-.099)
 im=Image.fromarray(np.uint8(np.clip(enc,0,1)*255));im.save(root/(name+'.png'))
 # x100 y1000 in full DNG, matching earlier zoom.
 zoom=im.crop((100,40,300,290)).resize((600,750),Image.Resampling.NEAREST)
 zoom.save(root/(name+'-zoom.png'))
 comp=Image.new('RGB',(1200,780));draw=ImageDraw.Draw(comp)
 for i,nm in enumerate([name,'RCD-reference']):
  arr=methods[nm];ll=(np.maximum(arr,0)*gains)@ccm.T;ee=np.where(ll<.018,4.5*ll,1.099*np.maximum(ll,0)**.45-.099)
  crop=Image.fromarray(np.uint8(np.clip(ee,0,1)*255)).crop((100,40,300,290)).resize((600,750),Image.Resampling.NEAREST)
  comp.paste(crop,(i*600,30));draw.text((i*600+10,8),nm,fill='white')
 comp.save(root/(name+'-vs-RCD.png'))
 # Similarity is a diagnostic to the independent RCD result, not truth.
 d=v[40:-40,40:-40]-rcd[40:-40,40:-40]
 print(name,'raw RGB MAE to RCD',abs(d).mean(),'green',abs(d[...,1]).mean())

 metrics[name]={'raw_rgb_mae_to_RCD':float(abs(d).mean()),'raw_green_mae_to_RCD':float(abs(d[...,1]).mean())}
(root/'comparison-metrics.json').write_text(json.dumps(metrics,indent=2))
