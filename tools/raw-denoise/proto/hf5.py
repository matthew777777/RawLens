from hf import *
import synth, steer
m_st = lambda prm=None, **sp: (lambda x, a, b, wb: demosaic(steer.denoise(x, a, b, wb, prm, sp)))
G = dict(grain=0.35)
ms = {'ESD3D2': m_esd, 'base g.35 (Y grain)': m_pk(grain=0.35),
      'steer default g.35': m_st(G), 'steer kappa.5': m_st(G, kappa=0.5), 'steer kappa1.5': m_st(G, kappa=1.5),
      'steer len4 taps8': m_st(G, st_len=4.0, st_taps=8), 'steer len1.5 taps3': m_st(G, st_len=1.5, st_taps=3),
      'steer coh1': m_st(G, coh_pow=1.0), 'steer g0': m_st(dict(grain=0.0)), 'steer tensor1.5': m_st(G, tensor_sigma=1.5)}
run(ms)
synth.run(ms)
