from hf import *
run({'ESD3D2': m_esd,
     'A lam2.2 l.5 f.5 g.35': m_pk(grain=0.35, luma=0.5, chroma1=0.5, lam=2.2),
     'B lam2.0 l.3 f.3 g.35': m_pk(grain=0.35, luma=0.3, chroma1=0.3, lam=2.0),
     'C lam2.0 l.3 f.3 g.5': m_pk(grain=0.5, luma=0.3, chroma1=0.3, lam=2.0),
     'D lam1.8 l.2 f.2 g.35': m_pk(grain=0.35, luma=0.2, chroma1=0.2, lam=1.8),
     'E lam2.2 l.5 f.25 g.35': m_pk(grain=0.35, luma=0.5, chroma1=0.25, lam=2.2),
     'F lam2.7 l.3 f.3 g.5': m_pk(grain=0.5, luma=0.3, chroma1=0.3),
     'G lam2.0 l.3 f.3 g.2': m_pk(grain=0.2, luma=0.3, chroma1=0.3, lam=2.0)})
import synth
synth.run({'ESD3D2': m_esd, 'default g.35': m_pk(grain=0.35), 'B': m_pk(grain=0.35, luma=0.3, chroma1=0.3, lam=2.0), 'D': m_pk(grain=0.35, luma=0.2, chroma1=0.2, lam=1.8),
           'G': m_pk(grain=0.2, luma=0.3, chroma1=0.3, lam=2.0)})
