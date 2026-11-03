#!/usr/bin/env python3
"""Diagnostic detail-loss estimates for independently specified CFA-cell reduction.

GPL-3.0-or-later. RawLens/Codex 2026-11-01. Not a quality acceptance gate.
"""
import argparse
from array import array
import hashlib
import json
import math
from pathlib import Path
import subprocess
from build_oracle import ROOT, build
from gpu_validate import floats
from validate_raw_cell_pack import f32


def run(work):
    work.mkdir(parents=True, exist_ok=True)
    oracle = build(work/'oracle')
    manifest = json.loads((ROOT/'fixtures/manifest.json').read_text())
    rows = []
    for fixture in manifest['crops']:
        path = ROOT/'fixtures'/fixture['file']
        assert hashlib.sha256(path.read_bytes()).hexdigest() == fixture['sha256']
        w, h = fixture['size']
        # Same explicit reencoding as the integer-input adapter validation.
        codes = [round(64+v*16250) for v in floats(path)]
        def reconstruct(codes_, width, height, label):
            data = array('f', (max(0., min(1., f32(f32(v-64)/16250))) for v in codes_))
            src=work/(label+'.cfa');dst=work/(label+'.rgb');src.write_bytes(data.tobytes())
            subprocess.run([str(oracle),str(width),str(height),str(width),'0','0',str(width),str(height),fixture['cfa'],str(src),str(dst),'normalized-rgb',str(work/'stages-')],check=True,capture_output=True)
            return floats(dst)
        original = reconstruct(codes,w,h,'full')
        for scale in (2,3,4):
            ow=2*((w+2*scale-1)//(2*scale));oh=2*((h+2*scale-1)//(2*scale))
            reduced=[]
            for y in range(oh):
                for x in range(ow):
                    samples=[]
                    for dy in range(scale):
                        for dx in range(scale):
                            sx=(x//2)*2*scale+(x&1)+2*dx
                            sy=(y//2)*2*scale+(y&1)+2*dy
                            if sx<w and sy<h:samples.append(codes[sy*w+sx])
                    reduced.append((sum(samples)+len(samples)//2)//len(samples) if samples else 0)
            rgb=reconstruct(reduced,ow,oh,'reduced')
            errors=[]
            for y in range(h):
                for x in range(w):
                    # Explicit bilinear center mapping; this diagnostic does not
                    # assert parity with the application's grade scaler.
                    sx=max(0.,min(ow-1.,(x+.5)*ow/w-.5));sy=max(0.,min(oh-1.,(y+.5)*oh/h-.5))
                    x0=int(sx);y0=int(sy);x1=min(x0+1,ow-1);y1=min(y0+1,oh-1)
                    for c in range(3):
                        at=lambda a,b:rgb[(b*ow+a)*3+c]
                        top=at(x0,y0)+(at(x1,y0)-at(x0,y0))*(sx-x0)
                        bottom=at(x0,y1)+(at(x1,y1)-at(x0,y1))*(sx-x0)
                        value=top+(bottom-top)*(sy-y0)
                        errors.append(value-original[(y*w+x)*3+c])
            mse=sum(e*e for e in errors)/len(errors)
            rows.append({'fixture':fixture['file'],'fixture_sha256':fixture['sha256'],'source_size':[w,h],'scale':scale,'reconstruction_size':[ow,oh],'max_abs_linear_rgb':max(map(abs,errors)),'mean_abs_linear_rgb':sum(map(abs,errors))/len(errors),'psnr_linear_rgb_db':-10*math.log10(mse) if mse else None})
    return {'scope':'CPU oracle diagnostic of geometry-induced detail loss on three CC0 crops reencoded as RAW16; bilinear enlargement, not app grade/encoder output or perceptual acceptance','oracle':'RawTherapee b2ee504c47e5e6d5e054395aa12bf1fe43bd1c3c','quality_accepted':False,'threshold':'No new perceptual acceptance threshold established; old reference-mode tolerances are unchanged','cases':rows,'source_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    report=run(a.work.resolve());a.report.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report['cases'],indent=2))
