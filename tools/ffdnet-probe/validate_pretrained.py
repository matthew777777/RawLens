#!/usr/bin/env python3
"""Compare Android outputs to independent PyTorch results, before/after inverse GAT."""
import argparse,json,pathlib
import numpy as np
p=argparse.ArgumentParser();p.add_argument('--backends',nargs='+',default=['cpu','cpu16','gpu','mtk-mdla_shim','mtk-neuron_shim']);p.add_argument('--tiles',nargs='+',type=int);p.add_argument('--prepared',required=True);p.add_argument('--results',required=True);a=p.parse_args()
src=pathlib.Path(a.prepared);out=pathlib.Path(a.results);manifest=json.loads((src/'manifest.json').read_text());rows=[]
def unpack(p,t):
 n=t//2
 return p.reshape(n,n,3,2,2).transpose(0,3,1,4,2).reshape(t,t,3)
def inverse(y,d):
 D=np.maximum(y*float(d['span'])+float(d['lo']),1e-3)
 ac,bc,wb=[d[k].reshape(1,1,3) for k in ['ac','bc','wb']]
 s2=bc/ac**2;r=np.sqrt(1.5)
 v=.25*D**2+.25*r/D-11/8/D**2+5/8*r/D**3-.125-s2
 v=np.where(D<1.5,.25*D**2-.375-s2,v)
 return ac*v/wb
for st in (a.tiles or manifest['tiles']):
 t=int(st);expected=np.fromfile(src/f'expected-{t}.bin',dtype='<f4').astype(np.float64)
 with np.load(src/f'domain-{t}.npz') as d:
  linref=inverse(unpack(expected,t),d)
  for backend in a.backends:
   y=np.fromfile(out/f'output-{t}-{backend}.bin',dtype='<f4').astype(np.float64)
   assert y.shape==expected.shape and np.isfinite(y).all()
   delta=y-expected;lin=inverse(unpack(y,t),d)-linref
   row={'tile':t,'backend':backend,'max_error':float(np.max(np.abs(delta))),'rmse':float(np.sqrt(np.mean(delta**2))),'linear_max_error':float(np.max(np.abs(lin))),'linear_rmse':float(np.sqrt(np.mean(lin**2)))}
   row['agreement_psnr_db']=float(-20*np.log10(max(row['rmse'],1e-30)))
   if backend=='cpu':assert row['max_error']<5e-4, row
   rows.append(row);print(json.dumps(row))
(out/'validation.json').write_text(json.dumps(rows,indent=2)+'\n')
