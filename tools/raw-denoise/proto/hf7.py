from hf import *
import synth, steer
m_st = lambda prm=None, **sp: (lambda x, a, b, wb: demosaic(steer.denoise(x, a, b, wb, prm, sp)))
G = dict(grain=0.35)
ms = {'ESD3D2': m_esd, 'raw k.25': m_st(G, pre='none', kappa=0.25),
      'green k1': m_st(G, pre='green'), 'green k.5': m_st(G, pre='green', kappa=0.5), 'green k.25': m_st(G, pre='green', kappa=0.25),
      'green k0': m_st(G, pre='green', kappa=0.0), 'green k.25 len1.5 t3': m_st(G, pre='green', kappa=0.25, st_len=1.5, st_taps=3),
      'green k.25 g0': m_st(dict(grain=0.0), pre='green', kappa=0.25), 'green k0 g0': m_st(dict(grain=0.0), pre='green', kappa=0.0)}
run(ms, levels=('hi', 'mid'))
synth.run(ms, levels=('hi', 'mid'))
