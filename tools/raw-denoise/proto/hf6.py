from hf import *
import synth, steer
m_st = lambda prm=None, **sp: (lambda x, a, b, wb: demosaic(steer.denoise(x, a, b, wb, prm, sp)))
G = dict(grain=0.35)
ms = {'ESD3D2': m_esd, 'binom k1': m_st(G),
      'raw k1': m_st(G, pre='none'), 'raw k.5': m_st(G, pre='none', kappa=0.5), 'raw k.25': m_st(G, pre='none', kappa=0.25),
      'raw k.5 len1.5 t3': m_st(G, pre='none', kappa=0.5, st_len=1.5, st_taps=3), 'raw k.5 len4 t8': m_st(G, pre='none', kappa=0.5, st_len=4.0, st_taps=8),
      'raw k.5 g0': m_st(dict(grain=0.0), pre='none', kappa=0.5), 'raw k.5 e1': m_st(G, pre='none', kappa=0.5, e_sigma=1.0)}
run(ms, levels=('hi', 'mid'))
synth.run(ms, levels=('hi', 'mid'))
