import os,sys; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from lite_model import *
S="/private/tmp/claude-501/-Users-monikamalinowska-AndroidStudioProjects-RawLens/dd8e0948-6166-4022-a986-573483c43e5b/scratchpad/"
case=sys.argv[1]; t=load_trace(S+"ref/"+case+"_tile_0_0"); K=t["K"]
m=np.fromfile(S+"lite/"+case+".f32.motion",np.float32); p=m[:K*6].reshape(K,6); d=m[K*6:].reshape(K,-1,2)
for nm,pp,dd in (("init",t["p0"],t["d0"]),("lkr",t["p1"],t["d1"]),("lite",p,d)):
    print("%-5s tx+med(d):"%nm, np.round(pp[1:7,2]+np.median(dd[1:7,:,0],1),2), " ty+med:",np.round(pp[1:7,5]+np.median(dd[1:7,:,1],1),2), " |d| p90 %.2f"%np.percentile(np.abs(dd[1:]),90))
