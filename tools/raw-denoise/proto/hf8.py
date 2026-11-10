from hf import *
import synth, joined
m_j = lambda prm=None, **k: (lambda x, a, b, wb: joined.joined(x, a, b, wb, prm, **k))
G = dict(grain=0.35)
ms = {'ESD3D2': m_esd, 'base g.35': m_pk(grain=0.35),
      'DNG P1 lam2.2 l.5 f.5': m_pk(grain=0.35, luma=0.5, chroma1=0.5, lam=2.2),
      'DNG P2 lam2.1 l.4 f.4 c2': m_pk(grain=0.35, luma=0.4, chroma1=0.4, lam=2.1, chroma=2.0),
      'DNG P3 lam2.0 l.3 f.3 c2': m_pk(grain=0.35, luma=0.3, chroma1=0.3, lam=2.0, chroma=2.0),
      'JPEG joined hp2': m_j(G), 'JPEG joined hp1.2': m_j(G, hp_sigma=1.2), 'JPEG joined hp4': m_j(G, hp_sigma=4.0),
      'JPEG joined hp2 g0': m_j(dict(grain=0.0)), 'JPEG joined hp2 amt.7': m_j(G, amount=0.7)}
run(ms)
synth.run(ms)
