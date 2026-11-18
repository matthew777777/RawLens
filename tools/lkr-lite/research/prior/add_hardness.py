# SPDX-License-Identifier: Apache-2.0
"""Back-fill the hard-patch mining map (gen_pairs.hardness) for existing pairs: writes PAIR.h.npy.
  add_hardness.py PAIRS_DIR[,DIR2...]"""
import glob, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_pairs import hardness

for d in sys.argv[1].split(','):
    for f in sorted(glob.glob(os.path.join(d, 'pair_*.npz'))):
        out = f[:-4] + '.h.npy'
        if os.path.exists(out): continue
        z = np.load(f)
        h = z['hard'] if 'hard' in z.files else hardness(z['x'], z['y'])
        np.save(out, h)
print('done')
