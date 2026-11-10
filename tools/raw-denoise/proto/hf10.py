from hf import *
import synth, joined
G0 = dict(grain=0.0)
ms = {'ESD3D2': m_esd, 'DNG luma grain .6': m_pk(grain=0.6, grain_y=False),
      'JPEG uniform luma .45': lambda x, a, b, wb: joined.blend(x, a, b, wb, G0, 0.45), 'JPEG uniform luma .55': lambda x, a, b, wb: joined.blend(x, a, b, wb, G0, 0.55)}
run(ms); synth.run(ms)
