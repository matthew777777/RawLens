#!/usr/bin/env python3
"""Direct RawTherapee scalar C++ vs RawLens GPU comparison on identical half-float input.
First run prove_parity.py. Supply the original RT cs_ref compiled with the vendored
rt_harness/build.sh (RT commit b2ee504). No NumPy algorithm substitutes for RT here.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import shutil
import numpy as np


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--gpu-proof',type=Path,required=True)
    p.add_argument('--rt-reference',type=Path,required=True)
    p.add_argument('--rt-checkout',type=Path,required=True)
    p.add_argument('--out',type=Path,required=True)
    a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True)
    revision=subprocess.check_output(['git','-C',str(a.rt_checkout),'rev-parse','HEAD'],text=True).strip()
    assert revision=='b2ee504c47e5e6d5e054395aa12bf1fe43bd1c3c'
    cases=json.loads((a.gpu_proof/'gpu-parity.json').read_text())['cases']
    sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'app/src/main/cpp/capture_sharpening/capture_sharpening/tools'))
    import parity
    extra_sources={}
    for name,offset in [('rt-default-20',0.0),('rt-corner-boost-0.5',0.5)]:
        case=dict(name=name,w=384,h=320,it=20,cfa=0,offset=offset)
        source=a.out/'extra-gpu'/name;source.mkdir(parents=True,exist_ok=True)
        rgb=(np.moveaxis(parity.scene(320,384,711,.8),0,-1)/65535.).astype(np.float16).astype(np.float32)
        rgb.tofile(source/'rgb.f32')
        (source/'params.txt').write_text(f'384 320 0 1.9 1 1.45 {int(offset==0)} 0.75 {offset} 1 10 20 1 1\n')
        with (source/'gpu.log').open('w') as log:
            subprocess.run([str(a.gpu_proof/'candidate/validate'),str(source)],check=True,stdout=log,stderr=log)
        shutil.copy2(source/'gpu_info.txt',source/'candidate-info.txt')
        cases.append(dict(case=case));extra_sources[name]=source
    report=dict(rawtherapee_revision=revision,reference='original RT scalar C++, no OpenMP/SIMD, FP contraction off',
                gpu='RawLens CaptureSharpening, RGBA16F; independent builds already match RAWR',cases=[])
    for row in cases:
        c=row['case'];name=c['name'];src=extra_sources.get(name,a.gpu_proof/'cases'/name);out=a.out/name;out.mkdir(exist_ok=True)
        w,h=c['w'],c['h'];params=(src/'params.txt').read_text().split()
        wb=np.array(params[3:6],np.float64);wb/=wb.min();to_rt=(65535.*wb).astype(np.float32)
        rgb=np.fromfile(src/'rgb.f32',np.float32).reshape(h,w,3).astype(np.float16).astype(np.float32)
        rt_rgb=rgb*to_rt
        pattern=int(params[2]);labels=[[0,1,1,2],[1,0,2,1],[1,2,0,1],[2,1,1,0]][pattern]
        raw=np.empty((h,w),np.float32);whites=[]
        for site,ch in enumerate(labels):
            raw[site//2::2,site%2::2]=rt_rgb[site//2::2,site%2::2,ch]
            whites.append(float(np.float32(.95*65535.*wb[ch])))
        for ch,n in enumerate('rgb'):rt_rgb[:,:,ch].astype(np.float32).tofile(out/(n+'.f32'))
        raw.tofile(out/'raw.f32');fc=[0,1] if pattern in (0,3) else [1,0]
        # RT: W H white00 white01 white10 white11 clipVal fc0 fc1 autoRadius radius autoContrast contrast offset iterations check
        (out/'params.txt').write_text(f"{w} {h} {' '.join(map(str,whites))} {to_rt[1]} {fc[0]} {fc[1]} {params[6]} {params[7]} {params[9]} {params[10]} {params[8]} {params[11]} {params[12]}\n")
        with (out/'reference.log').open('w') as log:subprocess.run([str(a.rt_reference),str(out)],check=True,stdout=log,stderr=log)
        reference=np.stack([np.fromfile(out/f'ref_{n}.f32',np.float32).reshape(h,w) for n in 'rgb'],-1)/to_rt
        gpu=np.fromfile(src/'gpu_rgb.f32',np.float32).reshape(h,w,3)
        err=np.abs(reference.astype(np.float64)-gpu)
        mse=float(np.mean(err*err))
        dr=(reference-rgb).astype(np.float64).ravel();dg=(gpu-rgb).astype(np.float64).ravel()
        corr=float(np.corrcoef(dr,dg)[0,1]) if np.std(dr)>0 and np.std(dg)>0 else None
        info=dict(line.split() for line in (out/'ref_info.txt').read_text().splitlines())
        gpuinfo=dict((v[0],v[1:]) for line in (src/'candidate-info.txt').read_text().splitlines() if (v:=line.split()))
        quantized=reference.astype(np.float16).astype(np.float32)
        result=dict(name=name,parameters=c,rt_radius=float(info['radius']),gpu_radius=float(gpuinfo['radius'][0]),
            rt_contrast=float(info['contrast']),gpu_contrast=float(gpuinfo['contrast'][0]),
            max_abs=float(err.max()),p99_abs=float(np.quantile(err,.99)),mean_abs=float(err.mean()),
            psnr_db=float(-10*np.log10(mse)) if mse else None,sharpening_delta_correlation=corr,
            half_rounded_equal_fraction=float(np.mean(quantized==gpu)),
            finite=bool(np.isfinite(reference).all() and np.isfinite(gpu).all()),
            original_rt_corner_scratch=c.get('offset',0)!=0)
        if c.get('offset',0)!=0:
            diagnostic=out/'ring-reset-diagnostic';diagnostic.mkdir(exist_ok=True)
            for f in ['params.txt','raw.f32','r.f32','g.f32','b.f32']:
                shutil.copy2(out/f,diagnostic/f)
            reset=a.rt_reference.with_name('cs_ref_ringreset')
            subprocess.run([str(reset),str(diagnostic)],check=True)
            reset_rgb=np.stack([np.fromfile(diagnostic/f'ref_{n}.f32',np.float32).reshape(h,w) for n in 'rgb'],-1)/to_rt
            reset_err=np.abs(reset_rgb.astype(np.float64)-gpu)
            result['ring_reset_diagnostic_only']=dict(max_abs=float(reset_err.max()),mean_abs=float(reset_err.mean()),
                p99_abs=float(np.quantile(reset_err,.99)),psnr_db=float(-10*np.log10(np.mean(reset_err**2))))
        assert result['finite']
        report['cases'].append(result)
        (a.out/'rawtherapee-comparison.json').write_text(json.dumps(report,indent=2)+'\n')
        print(f"{name}: max={result['max_abs']:.7g} mean={result['mean_abs']:.7g} PSNR={result['psnr_db']:.2f} dB correlation={corr}",flush=True)
    print('RAWTHERAPEE_DIRECT_COMPARISON_COMPLETE',flush=True)

if __name__=='__main__':main()
