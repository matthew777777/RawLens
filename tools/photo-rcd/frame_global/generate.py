#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Frame-global walker RCD for recording (option A); product from tile_local.

Equations: RawTherapee b2ee504c rcd_demosaic.cc via baseline.comp. The RAW
metadata block and the product/half-output adapter are extracted verbatim
from the tile-local generator so both routes share one product definition.
"""
import sys
from pathlib import Path
HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE.parent));sys.path.insert(0,str(HERE))
from unroll import expand  # noqa: E402

def _pieces(text):
    a=text.index('layout(binding=10,std430) readonly restrict buffer RawMetadata');b=text.index('} m;',a)+5
    c=text.index('layout(binding=7,std430) restrict buffer ProductConfig');d=text.index('\nvoid writePixel',c)
    return text[a:b],text[c:d]

def source(image=False):
    from tile_local import source as tile_source,image_source
    metadata,product=_pieces(image_source() if image else tile_source())
    s=(HERE/'rcd_frame_walk.comp.in').read_text()
    s=s.replace('//@METADATA@',metadata).replace('//@PRODUCT@',product)
    store=('uvec2 rounded=uvec2(packHalf2x16(rgb.rg),packHalf2x16(vec2(rgb.b,1.0)));\n'
           '    imageStore(halfOutputImage,site,vec4(unpackHalf2x16(rounded.x),unpackHalf2x16(rounded.y)));') if image else \
          'halfOutput[site.y*p.width+site.x]=uvec2(packHalf2x16(rgb.rg),packHalf2x16(vec2(rgb.b,1.0)));'
    assert ('imageStore(halfOutputImage' in product)==image
    s=s.replace('//@STORE@',store)
    if image: s=s.replace('#extension GL_EXT_control_flow_attributes : require','#extension GL_EXT_control_flow_attributes : require\n#define IMAGE_OUTPUT 1',1)
    # Stage 5 reads the output-resolution product RGB back.
    product_rw=s.replace('writeonly restrict buffer HalfOutput','restrict buffer HalfOutput')
    assert image or product_rw!=s
    s=product_rw
    return expand(s)

def image_source():
    return source(True)

if __name__=='__main__':
    out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
    (out/'frame_global.comp').write_text(source());(out/'frame_global_image.comp').write_text(image_source())
