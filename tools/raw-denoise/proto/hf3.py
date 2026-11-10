from hf import *
import synth
ms = {'ESD3D2': m_esd,
      'A lam2.2 l.5 f.5 g.35': m_pk(grain=0.35, luma=0.5, chroma1=0.5, lam=2.2),
      'H lam2.2 l.3 f1 g.5': m_pk(grain=0.5, luma=0.3, lam=2.2),
      'I lam2.2 l.5 f1 g.5 binom': m_pk(grain=0.5, luma=0.5, lam=2.2, grain_y=False),
      'J lam2.2 l.3 f1 g.7 binom': m_pk(grain=0.7, luma=0.3, lam=2.2, grain_y=False),
      'K default g.6 binom': m_pk(grain=0.6, grain_y=False)}
run(ms, levels=('hi', 'mid'))
synth.run(ms, levels=('hi', 'mid'))
