from hf import *
import synth
ms = {'ESD3D2': m_esd, 'default g.35': m_pk(grain=0.35),
      'L ly2.0 l.3 g.35': m_pk(grain=0.35, luma=0.3, lam_y=2.0),
      'M ly1.7 l.2 g.35': m_pk(grain=0.35, luma=0.2, lam_y=1.7),
      'N ly2.0 l.3 g.5 binom': m_pk(grain=0.5, luma=0.3, lam_y=2.0, grain_y=False),
      'O ly1.7 l.15 g.5': m_pk(grain=0.5, luma=0.15, lam_y=1.7)}
run(ms)
synth.run(ms)
