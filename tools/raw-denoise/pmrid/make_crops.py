"""Clean training crops: Redmi single raw frames, ISO<=100, not HDR+/merged/linear, burst frames F00 only.
Packed RGGB float16 (4,256,256) per crop (512 px CFA), 6 crops/file, + wb. -> CACHE/crops.npy, wb.npy"""
import sys,os,subprocess,numpy as np,tifffile
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
from lib import load_dng
out=sys.argv[1]; os.makedirs(out,exist_ok=True)
fs=sorted(l for l in subprocess.run(['find',*[os.path.expanduser(d) for d in os.environ['RAWDN_DNG_DIRS'].split(':')],'-iname','*.dng'],capture_output=True,text=True).stdout.split('\n') if l)
rng=np.random.default_rng(0); C=[];W=[];names=[]
for f in fs:
    b=os.path.basename(f).upper()
    if any(k in b for k in ('HDRPLUS','LINEAR','MFSR','LKR','MERGE','DENO')): continue
    if '_F' in b and not b.endswith('_F00.DNG'): continue
    try:
        t=tifffile.TiffFile(f); tg={x.name:x for x in t.pages[0].tags.values()}
        model=str(tg['Model'].value) if 'Model' in tg else ''
        iso=int(tg['ISOSpeedRatings'].value) if 'ISOSpeedRatings' in tg else 999
        if t.pages[0].photometric!=32803: continue
    except Exception: continue
    if ('REDMI' not in model.upper() and '25080RABDG' not in model) or iso>100: continue
    try: x,wb,_,_=load_dng(f)
    except Exception: continue
    H,Wd=x.shape
    for k in range(6):
        y=int(rng.integers(0,(H-512)//2))*2; z=int(rng.integers(0,(Wd-512)//2))*2
        c=x[y:y+512,z:z+512]
        if c.mean()<0.01 or (c>0.95).mean()>0.05: continue     # skip black / clipped crops
        p=np.stack([c[0::2,0::2],c[0::2,1::2],c[1::2,0::2],c[1::2,1::2]]).astype(np.float16)
        C.append(p); W.append(wb)
    names.append(f); print(len(names),len(C),os.path.basename(f),iso,flush=True)
np.save(os.path.join(out,'crops.npy'),np.stack(C)); np.save(os.path.join(out,'wb.npy'),np.array(W,np.float32))
open(os.path.join(out,'files.txt'),'w').write('\n'.join(names))
print('crops',len(C),'files',len(names))
