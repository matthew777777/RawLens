#!/usr/bin/env python3
"""Export real KAIR color FFDNet weights, GAT inputs, and PyTorch references."""
import argparse, hashlib, json, pathlib, struct, sys
import numpy as np
import torch
import torch.nn.functional as F
root=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root/'python/lkr'))
from drunet import load_ffdnet
p=argparse.ArgumentParser();p.add_argument('--weights',required=True);p.add_argument('--image',required=True);p.add_argument('--output',required=True);p.add_argument('--tiles',nargs='+',type=int,default=[128,256,512]);a=p.parse_args()
out=pathlib.Path(a.output);out.mkdir(parents=True,exist_ok=True)
torch.set_num_threads(4)
net=load_ffdnet(a.weights)
ls=[m for m in net.model if isinstance(m,torch.nn.Conv2d)]
assert len(ls)==12
with (out/'weights.bin').open('wb') as f:
 f.write(struct.pack('<I',len(ls)))
 for m in ls:
  f.write(struct.pack('<II',m.in_channels,m.out_channels))
  f.write(m.weight.detach().numpy().astype('<f4').tobytes());f.write(m.bias.detach().numpy().astype('<f4').tobytes())
image=np.load(a.image)
assert image.ndim==3 and image.shape[0]==3 and np.isfinite(image).all()
meta={'weights_sha256':hashlib.sha256(pathlib.Path(a.weights).read_bytes()).hexdigest(),'weights_url':'https://github.com/cszn/KAIR/releases/download/v1.0/ffdnet_color.pth','checkpoint_bytes':pathlib.Path(a.weights).stat().st_size,'params':sum(v.numel() for v in net.parameters()),'image_sha256':hashlib.sha256(pathlib.Path(a.image).read_bytes()).hexdigest(),'image_shape':list(image.shape),'domain':'gat','note':'Real reconstruction crop; illustrative noise calibration, not calibrated to source camera. Inference benchmark only.','a':0.002,'b':0.00002,'wb':[1.9,1.,1.5],'sigma_ratio':1.,'tiles':{}}
for tile in a.tiles:
 z=torch.from_numpy(np.ascontiguousarray(image[:,:tile,:tile])).float()
 if z.shape[-2:]!=(tile,tile):z=F.interpolate(z[None],size=(tile,tile),mode='bilinear',align_corners=False)[0]
 wb=torch.tensor(meta['wb']).reshape(3,1,1);ac=meta['a']*wb;bc=meta['b']*wb**2
 x=(z*wb).clamp_min(0);D=2/ac*torch.sqrt(ac*x+0.375*ac**2+bc)
 lo,hi=float(D.min()),float(D.max());span=max(hi-lo,1e-6)
 inp=torch.cat([(D-lo)/span,torch.full_like(D[:1],meta['sigma_ratio']/span)],0)[None]
 packed=torch.cat([F.pixel_unshuffle(inp[:,:3],2),F.avg_pool2d(inp[:,3:4],2)],1)
 with torch.no_grad():
  expected=net(inp);core=net.model(packed)
  assert torch.equal(F.pixel_shuffle(core,2),expected)
 def save(t,path):t[0].permute(1,2,0).contiguous().numpy().astype('<f4').tofile(path)
 save(packed,out/f'input-{tile}.bin');save(core,out/f'expected-{tile}.bin')
 # Save transforms to compare reconstructed linear output against the exact local PnP prior.
 np.savez(out/f'domain-{tile}.npz',lo=lo,span=span,ac=ac.numpy(),bc=bc.numpy(),wb=wb.numpy())
 meta['tiles'][str(tile)]={'packed_shape':list(packed.shape),'lo':lo,'span':span,'noise_sigma':meta['sigma_ratio']/span,'output_min':float(expected.min()),'output_max':float(expected.max())}
(out/'manifest.json').write_text(json.dumps(meta,indent=2)+'\n')
print(json.dumps(meta,indent=2))
