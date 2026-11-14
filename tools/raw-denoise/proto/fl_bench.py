import sys
from galosh_bench import *
import fullluma as FL
fl = lambda **p: (lambda x, a, b, wb: demosaic(FL.denoise(x, a, b, wb, p)))
M = {'ESD3D2': m_esd, 'rawdn DNG pick': m_pk(grain=0.6, grain_y=False),
     'FL own g.6': fl(), 'FL own g.3': fl(grain=0.3), 'FL own lam2.2 g.3': fl(grain=0.3, fl_lam=2.2),
     'FL own fl.6 g.3': fl(grain=0.3, fl=0.6), 'FL own hp2 g.3': fl(grain=0.3, fl_hp=2), 'FL own s1 g.3': fl(grain=0.3, fl_s=1)}
run(M, tuple(sys.argv[1].split(',')) if len(sys.argv) > 1 else ('hi', 'mid', 'lo'))
