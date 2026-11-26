#!/usr/bin/env python3
"""Host parity harness: GLES AMaZE passes -> Vulkan SPIR-V. usage: to_vulkan.py <shaders dir> <out dir>"""
import re, subprocess, sys
from pathlib import Path
D, O = Path(sys.argv[1]), Path(sys.argv[2]); O.mkdir(parents=True, exist_ok=True)
UBO = '''layout(std140, set=0, binding=15) uniform U {
 ivec4 u_fc; vec4 bal4; vec4 white4; vec4 unbal4; vec4 invwb4; ivec4 u_active; mat3 u_camera_to_acescg;
 vec2 u_white_window; ivec2 u_size; ivec2 u_insize; ivec2 u_off; ivec2 u_outsize; ivec2 u_outoff; ivec2 u_inner; ivec2 u_sensor_origin;
 float u_clip; float u_defringe; int u_camera_linear; int u_fc_median; int u_apply_luma; int u_apply_lens;
};
#define u_demosaic_balance bal4.xyz
#define u_camera_white_normalized white4.xyz
#define u_demosaic_unbalance unbal4.xyz
#define u_inv_wb invwb4.xyz
'''
rd = lambda p: (D / p).read_text() if (D / p).exists() else ''
for n in 'pad gradcd bound hvwt nyq2 area green nyqref rbpm pmrbint gcorr chroma final'.split():
    s = rd(f'amaze/{n}.glsl').replace('#import amaze', rd('utils/import_amaze.glsl')).replace('#import cleanup', rd('amaze/cleanup.glsl')).replace('#import luma', rd('amaze/luma.glsl'))
    s = re.sub(r'^#version.*$', '', s, flags=re.M)
    s = s.replace('rgba16f', 'rgba32f')
    k = [8]
    def sam(m):
        if k[0] == 8 or True:
            r = f'layout(set=0, binding={k[0]}) uniform highp sampler2D {m[1]};'; names.append(m[1]); k[0] += 1; return r
    names = []
    s = re.sub(r'^uniform (?:highp )?sampler2D (\w+);', sam, s, flags=re.M)
    s = re.sub(r'^uniform [^;]*;', '', s, flags=re.M)
    s = s.replace('layout(binding = ', 'layout(set=0, binding = ')
    s = re.sub(r'^precision .*$', '', s, flags=re.M)
    used = [x for x in names if len(re.findall(r'\b' + x + r'\b', s)) > 1]
    # only used samplers stay bound (u_cfa of the common header)
    s = '#version 450\n' + UBO + s
    (O / f'{n}.comp').write_text(s)
    (O / f'{n}.sam').write_text(' '.join(f'{x}' for x in names) + '\n' + ' '.join(used) + '\n')
    subprocess.run(['glslangValidator', '-V', '--target-env', 'vulkan1.1', '-o', str(O / f'{n}.spv'), str(O / f'{n}.comp')], check=True, stdout=subprocess.PIPE)
