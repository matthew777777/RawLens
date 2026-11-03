# SPDX-License-Identifier: GPL-3.0-or-later
"""Reproduce the clipped-water MHC regression (baseline vs clipping fallback).

Dependencies: numpy, pillow, rawpy. This is an offline fp32 diagnostic;
shipping fp16/P010 correctness is checked by MhcHighlightDeviceTest.
"""
import argparse
import rawpy
import numpy as np
from PIL import Image
from pathlib import Path
parser=argparse.ArgumentParser()
parser.add_argument('dng',type=Path)
parser.add_argument('output',type=Path)
args=parser.parse_args()
root=args.output;root.mkdir(parents=True,exist_ok=True)
with rawpy.imread(str(args.dng)) as raw:
    assert raw.white_level==1023 and raw.black_level_per_channel==[64]*4
    assert raw.raw_pattern.tolist()==[[3,2],[0,1]], 'fixture must be GBRG'
    mosaic=raw.raw_image_visible.copy()
    assert mosaic.shape==(2040,4080), 'this diagnostic targets the reported frame'
    mosaic[1050:1114,100:164].astype('<u2').tofile(root/'water_64x64.u16')
a=(mosaic.astype(np.float32)-64)/959
# crop glitter field, preserve GBRG phase
x0,y0=0,1000
a=a[y0:1900,x0:1500]; H,W=a.shape
p=np.pad(a,2,mode='reflect')
def tap(x,y):return p[2+y:2+y+H,2+x:2+x+W]
c=a;n=tap(0,-1);s=tap(0,1);w=tap(-1,0);e=tap(1,0)
n2=tap(0,-2);s2=tap(0,2);w2=tap(-2,0);e2=tap(2,0)
nw=tap(-1,-1);ne=tap(1,-1);sw=tap(-1,1);se=tap(1,1)
ch=2*c-w2-e2;cv=2*c-n2-s2;dh=abs(w-e)+.5*abs(ch);dv=abs(n-s)+.5*abs(cv)
gt=np.clip((np.minimum(dh,dv)-.03)/.05,0,1);gf=np.clip((.03-np.maximum(dh,dv))/.03,0,1);gg=np.maximum(gt,gf);he=dh<dv
mix=lambda a,b,t:a+(b-a)*t
ratio=lambda num,den:np.maximum(num+.002,.002)/np.maximum(den+.002,.002)
gh=.5*(w+e)+.25*ch;gv=.5*(n+s)+.25*cv;green=mix(np.where(he,gh,gv),.5*(gh+gv),gt)
dw=.5*(nw+sw)-w;de=.5*(ne+se)-e;dn=.5*(nw+ne)-n;ds=.5*(sw+se)-s
ww=.001+abs(c-e2);we=.001+abs(c-w2);wn=.001+abs(c-s2);ws=.001+abs(c-n2)
opp=np.where(he,green+(wn*dn*ratio(green,n)+ws*ds*ratio(green,s))/(wn+ws),green+(ww*dw*ratio(green,w)+we*de*ratio(green,e))/(ww+we))
opp=mix(opp,(6*c+2*(nw+ne+sw+se)-1.5*(n2+s2+w2+e2))*.125,gg)
gww=np.where(he,.5*(c+w2),.5*(nw+sw));gwe=np.where(he,.5*(c+e2),.5*(ne+se))
gnn=np.where(he,.5*(nw+ne),.5*(c+n2));gns=np.where(he,.5*(sw+se),.5*(c+s2))
ew=c+(ww*(w-gww)*ratio(c,gww)+we*(e-gwe)*ratio(c,gwe))/(ww+we)
ns=c+(wn*(n-gnn)*ratio(c,gnn)+ws*(s-gns)*ratio(c,gns))/(wn+ws)
ew=mix(ew,(5*c+4*(w+e)-w2-e2-nw-ne-sw-se+.5*(n2+s2))*.125,gg)
ns=mix(ns,(5*c+4*(n+s)-n2-s2-nw-ne-sw-se+.5*(w2+e2))*.125,gg)
y,x=np.indices(a.shape);q=(y%2)*2+x%2
rgb=np.stack([np.where(q==2,c,np.where(q==1,opp,np.where(q==0,ns,ew))),np.where((q==0)|(q==3),c,green),np.where(q==1,c,np.where(q==2,opp,np.where(q==0,ew,ns)))],-1)
np.save(root/'old.npy',rgb)
gains=np.array([1.9296875,1,1.60546875]);ccm=np.array([[1.652374,-.5976887,-.05468537],[-.12891701,1.3691307,-.24021374],[.01562188,-.499987,1.4843651]])
def render(rgb,name):
 rgb=np.clip(rgb,0,4); t=np.clip((rgb.max(-1)-.7)/.29,0,1);t=t*t*(3-2*t); rgb=mix(rgb,1/gains,t[...,None]); v=(rgb*gains)@ccm.T; v=np.where(v<.018,4.5*v,1.099*np.maximum(v,0)**.45-.099);Image.fromarray(np.uint8(np.clip(v,0,1)*255)).save(root/(name+'.png'));return np.clip(v,0,1)
old_encoded=render(rgb,'before')
print('raw clip',np.sum(a>=.99),'ratio excursions >1.5',np.sum(rgb>1.5),'negative',np.sum(rgb<0),'max',rgb.max())
# Saturation makes inter-channel curvature unreliable. Revert only there
# to positive, channel-separate interpolation, keeping the measured sample.
peak=np.maximum.reduce([c,n,s,w,e,n2,s2,w2,e2,nw,ne,sw,se]);t=np.clip((peak-.90)/.09,0,1);t=t*t*(3-2*t)
bg=.25*(n+s+w+e);bo=.25*(nw+ne+sw+se);bew=.5*(w+e);bns=.5*(n+s)
bilinear=np.stack([np.where(q==2,c,np.where(q==1,bo,np.where(q==0,bns,bew))),np.where((q==0)|(q==3),c,bg),np.where(q==1,c,np.where(q==2,bo,np.where(q==0,bew,bns)))],-1)
clipping_only=mix(rgb,bilinear,t[...,None])
# Correct two-dimensional subpixel specular aliases. Same-channel
# spatial differences reject flat saturated colors and straight edges.
ax=np.maximum.reduce([abs(c-w2),abs(c-e2),abs(w-e),abs(nw-ne),abs(sw-se)])
ay=np.maximum.reduce([abs(c-n2),abs(c-s2),abs(n-s),abs(nw-sw),abs(ne-se)])
def smooth(lo,hi,x):
    z=np.clip((x-lo)/(hi-lo),0,1);return z*z*(3-2*z)
alias_mask=smooth(.015,.08,np.minimum(ax,ay))*smooth(.03,.12,peak)
gc=np.array([1.,1.60546875,1.9296875,1.])[q]
gx=np.array([1.60546875,1.,1.,1.9296875])[q]
gy=np.array([1.9296875,1.,1.,1.60546875])[q]
gd=np.array([1.,1.9296875,1.60546875,1.])[q]
intensity=np.maximum(0,(4*c*gc+2*((n+s)*gy+(w+e)*gx)+(nw+ne+sw+se)*gd)/16)
fixed=mix(np.clip(clipping_only,0,4),intensity[...,None]/gains,alias_mask[...,None])
new_encoded=render(fixed,'candidate');np.save(root/'candidate.npy',fixed)
# magnified identical area
for name in ['before','candidate']:
 im=Image.open(root/(name+'.png'));im.crop((100,0,500,250)).resize((1200,750)).save(root/(name+'-zoom.png'))

import json
mask=peak>=.99
metrics={'crop':[x0,y0,W,H],'clipped_support_pixels':int(mask.sum()),'before_negative_channels_in_clipped_support':int(((rgb<0)&mask[...,None]).sum()),'after_negative_channels_in_clipped_support':int(((fixed<0)&mask[...,None]).sum()),'before_over_one_channels_in_clipped_support':int(((rgb>1)&mask[...,None]).sum()),'after_over_one_channels_in_clipped_support':int(((fixed>1)&mask[...,None]).sum()),'alias_support_pixels':int((alias_mask>.1).sum()),'neutralized_specular_pixels':int((alias_mask>.99).sum())}
(root/'image-metrics.json').write_text(json.dumps(metrics,indent=2))
print(json.dumps(metrics))

# Visualize the old top-row-only chroma versus complete 2x2 chroma.
# This is an uncompressed 709/P010-layout diagnostic, not a codec decode.
def preview420(v, complete):
    y=v@np.array([.2126,.7152,.0722])
    uv=np.stack([(v[...,2]-y)/1.8556,(v[...,0]-y)/1.5748],-1)
    cells=uv.reshape(H//2,2,W//2,2,2)
    uv=cells.mean((1,3)) if complete else cells[:,0].mean(2)
    uv=uv.repeat(2,0).repeat(2,1)
    b=y+1.8556*uv[...,0];r=y+1.5748*uv[...,1];g=(y-.2126*r-.0722*b)/.7152
    return np.uint8(np.clip(np.stack([r,g,b],-1),0,1)*255)
before420=preview420(old_encoded,False);after420=preview420(new_encoded,True)
Image.fromarray(before420).save(root/'before-p010-preview.png')
Image.fromarray(after420).save(root/'after-p010-preview.png')
comparison=Image.new('RGB',(1200,750))
for i,arr in enumerate([before420,after420]):
    im=Image.fromarray(arr).crop((100,0,300,250)).resize((600,750),Image.Resampling.NEAREST)
    comparison.paste(im,(i*600,0))
comparison.save(root/'comparison-left-before-right-after.png')
