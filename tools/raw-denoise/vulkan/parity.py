"""Vulkan vs (a) numpy mirror of the same algorithm is implied by RMS vs frozen v1; here: final RGB vs frozen v1 + CFA bias."""
import sys,os,json,glob,subprocess,numpy as np,cv2
H=os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0,H+'/../v1')
from lib import demosaic
host,spv,d,tmp=sys.argv[1:5]; F=np.float32; rows=[]
for j in sorted(glob.glob(d+'/*.json')):
    m=json.load(open(j)); p=j[:-5]; x=np.load(p+'.in.npy'); ref=np.load(p+'.out.npy'); wb=np.array(m['wb'],F); K=(.55+.35*m['t'])*(1.1-.1*m['t']); h,w=x.shape
    BK,WH=1000.,61000.; np.clip(np.round(x*(WH-BK)+BK),0,65535).astype('<u2').tofile(tmp+'/i.u16')
    r=subprocess.run([host,spv,tmp+'/i.u16',str(w),str(h),'0',str(BK),str(WH),str(wb[0]/wb[1]),str(wb[2]/wb[1]),tmp+'/o.u16',repr(m['a']),repr(m['b']),str(K)],capture_output=True,text=True)
    if r.returncode: print(r.stdout,r.stderr); sys.exit(1)
    o=(np.fromfile(tmp+'/o.u16','<u2').reshape(h,w)/65535.-.0625)/.9375; lu=np.fromfile(tmp+'/o.u16.luma','<f2').reshape(h,w).astype(F); o=demosaic(o.astype(F))+lu[...,None]*wb[1]/wb
    f=lambda v:np.sqrt(np.clip(v*wb,0,None)); i=slice(16,-16); e=(f(o)-f(ref))[i,i]; nz=demosaic(x)
    fi=lambda v:(lambda l:l-cv2.GaussianBlur(l,(0,0),1))(f(v).mean(-1)); print('  fine %.4f v1 %.4f corr %.3f'%(fi(o).std(),fi(ref).std(),np.corrcoef(fi(o)[i,i].ravel(),fi(ref)[i,i].ravel())[0,1]))
    print(os.path.basename(p),'rms vs v1 %.3f%% (noisy %.3f%%) mean shift %s'%(100*np.sqrt((e**2).mean()),100*np.sqrt(((f(nz)-f(ref))[i,i]**2).mean()),' '.join('%+.2f%%'%(100*(o[i,i,c].mean()/nz[i,i,c].mean()-1)) for c in range(3))))
    q=np.quantile(f(ref),.99); rows.append(np.concatenate([np.clip(f(v)[160:320,160:400]/q*1.2,0,1) for v in (nz,ref,o)],1))
cv2.imwrite(sys.argv[5],cv2.resize((np.concatenate(rows,0)[...,::-1]*255).astype(np.uint8),None,fx=2,fy=2,interpolation=cv2.INTER_NEAREST))
