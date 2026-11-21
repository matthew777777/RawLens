"""Fit the Redmi per-ISO noise model var = a*x + b from our OWN blind estimates (never the DNG profile).
Writes noise_iso.json: log-log fits a(ISO), b(ISO) + raw points."""
import sys,os,glob,json,subprocess,numpy as np
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
from lib import load_dng,estimate_noise
fs=[l for l in subprocess.run(['find',*[os.path.expanduser(d) for d in os.environ['RAWDN_DNG_DIRS'].split(':')],'-iname','*.dng'],capture_output=True,text=True).stdout.split('\n') if l]
pts=[]
import tifffile
for f in fs:
    try:
        t=tifffile.TiffFile(f); tg={x.name:x for x in t.pages[0].tags.values()}
        model=str(tg.get('Model').value if 'Model' in tg else '')
        iso=int(tg['ISOSpeedRatings'].value) if 'ISOSpeedRatings' in tg else 0
    except Exception: continue
    if 'REDMI' not in model.upper() and '25080RABDG' not in model: continue
    if iso<150 or '_F0' in f and not f.endswith('_F00.dng'): continue
    try:
        x,wb,_,_=load_dng(f); a,b,_=estimate_noise(x[::1,::1])
        pts.append((iso,a,b)); print(os.path.basename(f),iso,'%.3e %.3e'%(a,b),flush=True)
    except Exception as e: print('skip',f,e)
    if len(pts)>=60: break
P=np.array(pts); li=np.log(P[:,0])
fa=np.polyfit(li,np.log(P[:,1]),1); okb=P[:,2]>0; fb=np.polyfit(li[okb],np.log(P[okb,2]),2)
json.dump(dict(a=list(fa),b=list(fb),pts=pts),open(os.path.join(os.path.dirname(os.path.abspath(__file__)),'noise_iso.json'),'w'),indent=1)
print('a(ISO)=exp(%.3f*ln ISO %+.3f)'%tuple(fa))
