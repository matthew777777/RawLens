from hf import *
import synth
ms = {'P1 lam2.2 l.5 f.5': m_pk(grain=0.35, luma=0.5, chroma1=0.5, lam=2.2),
      'Q1 ly2.0 l.3 f.7 c2 g.5': m_pk(grain=0.5, luma=0.3, lam_y=2.0, chroma1=0.7, chroma=2.0),
      'Q2 ly1.8 l.2 f.7 c2 g.5': m_pk(grain=0.5, luma=0.2, lam_y=1.8, chroma1=0.7, chroma=2.0),
      'Q3 lam2.4 ly1.8 l.2 f.6 c2 g.5': m_pk(grain=0.5, luma=0.2, lam_y=1.8, lam=2.4, chroma1=0.6, chroma=2.0),
      'Q4 ly1.6 l.1 f.8 c2 g.6': m_pk(grain=0.6, luma=0.1, lam_y=1.6, chroma1=0.8, chroma=2.0)}
run(ms, levels=('hi', 'mid'))
synth.run(ms, levels=('hi', 'mid'))
