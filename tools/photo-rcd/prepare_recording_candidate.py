#!/usr/bin/env python3
"""Prepare the preserved 200 ms photo candidate without restoring its archive.

This is a compatibility candidate, not the pinned RawTherapee oracle. Outputs
are deliberately outside app assets until numerical and Android gates pass.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'tools/rcd-baseline/fast-20261026/vf_rcd_measured_200ms.comp'
PIN = '79c7896d38d2d91d3034b7c94731475a27692d4f03d7ddc10b72153c20b015d8'


def replace_once(source, before, after):
    if source.count(before) != 1:
        raise ValueError('Candidate adaptation anchor changed: ' + before[:80])
    return source.replace(before, after, 1)


def prepare():
    original = SOURCE.read_bytes()
    if hashlib.sha256(original).hexdigest() != PIN:
        raise ValueError('Preserved candidate hash changed; audit before adapting')
    source = original.decode()
    # Preserve equations and epsilon constants. Eliminate all same-dispatch
    # RGB read/write overlap, including reads of supposedly unchanged channels.
    source = source.replace('rgba16f', 'rgba32f')
    source = replace_once(source,
        'layout(binding = 1, rgba32f) uniform image2D img_rgb;',
        'layout(binding = 1, rgba32f) writeonly uniform image2D img_rgb;\n'
        'layout(binding = 3, rgba32f) readonly uniform image2D img_previous;')
    source = source.replace('imageLoad(img_rgb,', 'imageLoad(img_previous,')
    # Full-grid ownership establishes an uncomplicated reference for later
    # paired-site optimization and initializes every odd-width pixel.
    source = replace_once(source,
        'ivec2 groupBase() { return ivec2(gl_WorkGroupID.xy) * ivec2(stageIndex() == 0 ? 8 : 16, 8); }',
        'ivec2 groupBase() { return ivec2(gl_WorkGroupID.xy) * ivec2(8); }')
    source = replace_once(source, '''    if (stage != 0) {
        int x0Green = colorAt(ivec2(0, ij.y)) == 1 ? 1 : 0;
        int parity = stage == 3 ? 1 - x0Green : x0Green;
        ij.x = ij.x * 2 + parity;
    }
''', '')
    source = replace_once(source, '''        ivec2 mate = ivec2(ij.x ^ 1, ij.y);
        if (mate.x < p.u_dims.x) imageStore(img_rgb, mate, vec4(0.0, fetchN(mate), 0.0, 1.0));
''', '')
    source = replace_once(source, '        if (c == 1) return;',
        '        if (c == 1) { imageStore(img_rgb, ij, imageLoad(img_previous, ij)); return; }')
    source = replace_once(source, '    if (c != 1) return;',
        '    if (c != 1) { imageStore(img_rgb, ij, imageLoad(img_previous, ij)); return; }')
    # Original one-reflection helper is unsafe for tiny dimensions; periodic
    # mirror also preserves CFA phase for all supported dimensions >= 2.
    source = replace_once(source, '''    if (i < 0) i = -i;
    if (i >= n) i = 2 * n - 2 - i;
    return i;''', '''    if (n <= 1) return 0;
    int period = 2 * (n - 1);
    int t = ((i % period) + period) % period;
    return t < n ? t : period - t;''')
    # Replace stale race/storage/dispatch claims, while retaining the original
    # SPDX attribution and mathematical step comments below #version.
    body = source[source.index('#version 450'):]
    notice = '''// SPDX-License-Identifier: GPL-3.0-or-later
// RawLens recording RCD compatibility candidate, adapted 2026-11-01.
// Preserved photo-port source SHA256: ''' + PIN + '''
// Public mathematical attribution: Luis Sanz Rodriguez / Ingo Weyrich,
// RawTherapee RCD 2.3, GPL-3.0-or-later; RawLens adaptation: matthew777777.
// Separate pass inputs/outputs, FP32 arithmetic and image storage.
// Full-grid dispatch: ceil(width/8), ceil(height/8), for every stage.
// Binding 3 must never alias binding 1. See recording-candidate.md.
// This adaptation is unvalidated and is not an application default.
'''
    return notice + body


def engine_source():
    """Full-frame SSBO adapter for the shared validation/recording engine."""
    import re
    from build_variants import PRODUCT
    s = prepare()
    start = s.index('layout(binding = 0)')
    end = s.index('const float RCD_EPS')
    s = s[:start] + r"""
layout(binding=0,std430) readonly buffer Cfa { float cfa[]; };
layout(binding=1,std430) buffer Directions { vec4 directions[]; };
layout(binding=4,std430) buffer Green { vec4 greenRgb[]; };
layout(binding=5,std430) buffer Opposite { vec4 oppositeRgb[]; };
layout(binding=6,std430) buffer Final { vec4 outputRgb[]; };
layout(push_constant) uniform Params { int width; int height; int phase; int stage; } p;
layout(constant_id=9) const int RCD_STAGE=-1;
int stageIndex() { return RCD_STAGE>=0 ? RCD_STAGE : p.stage; }
""" + s[end:]
    a=s.index('int quadOf('); b=s.index('// Workgroup CFA tile',a)
    s=s[:a]+r"""
int colorAt(ivec2 s) { return (p.phase >> (((s.y&1)*2+(s.x&1))*2)) & 3; }
float normCode(ivec2 s) { return cfa[s.y*p.width+s.x]; }
"""+s[b:]
    s=s.replace('layout(constant_id = 0) const int RCD_STAGE = -1;\nint stageIndex() { return RCD_STAGE >= 0 ? RCD_STAGE : (p.u_mode & 3); }','')
    s=s.replace('p.u_dims','ivec2(p.width,p.height)')
    s=s.replace('(RCD_STAGE >= 0 ? RCD_STAGE : (p.u_mode & 3))','stageIndex()')
    s=s.replace('imageLoad(img_previous, mirror(s))','previousAt(mirror(s))')
    s=s.replace('imageLoad(img_scratch, mirror(s))','directions[linearIndex(mirror(s))]')
    s=s.replace('imageLoad(img_previous, ij)','previousAt(ij)')
    s=s.replace('imageStore(img_scratch, ij, vec4(vh, lp, pq, 0.0));','directions[linearIndex(ij)]=vec4(vh,lp,pq,0.0);')
    s=s.replace('imageStore(img_rgb, ij,','storeRgb(ij,')
    # Pinned RawTherapee stores diagonal high-pass values at odd columns
    # and indexes pairs through indx/2. The photo port omitted that convention.
    # Extend the shared load for the extra positive-X sample, retaining Y halo 4.
    s=s.replace('uint tw = stageIndex() == 0 ? 16u : 24u;', 'uint tw = 24u;')
    s=s.replace('float hpfP(ivec2 s) {','float hpfP(ivec2 s) {\n    s.x=s.x|1;')
    s=s.replace('float hpfQ(ivec2 s) {','float hpfQ(ivec2 s) {\n    s.x=s.x|1;')
    helper=r"""
int linearIndex(ivec2 s) { return s.y*p.width+s.x; }
vec4 previousAt(ivec2 s) { return stageIndex()==2 ? greenRgb[linearIndex(s)] : oppositeRgb[linearIndex(s)]; }
void storeRgb(ivec2 s,vec4 v) {
    int i=linearIndex(s);
    if(stageIndex()==1) greenRgb[i]=v;
    else if(stageIndex()==2) oppositeRgb[i]=v;
    else outputRgb[i]=vec4(max(v.rgb,vec3(0.0)),1.0);
}
"""
    s=s.replace('// Prior-mode rgb read',helper+'\n// Prior-mode rgb read')
    s=s.replace('void main() {',PRODUCT+'\nvoid main() {\n    if(stageIndex()==6) { productOutput();return; }')
    s=s.replace('float nb = 0.25 * (d0 + d1 + d2 + d3);', 'float nb = stageIndex()==2 ? 0.25*(d0+d1+d2+d3) : 0.25*((d0+d1)+(d2+d3));')
    s=re.sub(r'(?<!precise )\bfloat ([a-zA-Z_]\w*)\s*=',r'precise float \1 =',s)
    return s


def canonical_engine_source():
    """Keep candidate shared-CFA reconstruction; preserve pinned tile/border rules.

    The public-source baseline supplies scratch-domain and border adapters,
    not the historical candidate's expected output.
    """
    s=engine_source()
    baseline=(ROOT/'tools/photo-rcd/baseline.comp').read_text()
    helpers=baseline[baseline.index('const float eps='):baseline.index('precise float diagonalResponse')]
    helpers=helpers.replace('precise float g(ivec2 s) { return green[indexOf(s)]; }','')
    s=s.replace('int colorAt(ivec2 s)',helpers+'\nint colorAt(ivec2 s)')
    s=s.replace('float normCode(ivec2 s) { return cfa[s.y*p.width+s.x]; }','float normCode(ivec2 s) { return raw(s); }')
    s=s.replace('int linearIndex(ivec2 s) { return s.y*p.width+s.x; }','int linearIndex(ivec2 s) { return indexOf(s); }')
    s=s.replace('mirror(s))','s)') # Prior-pass atlas reads use explicitly initialized halo, not mirror.
    s=s.replace('else outputRgb[i]=vec4(max(v.rgb,vec3(0.0)),1.0);','else if(domain(s,9)) { ivec2 site=s+tileOrigin(); outputRgb[site.y*p.width+site.x]=vec4(max(v.rgb,vec3(0.0)),1.0); }')
    s=s.replace('normCode(mirror(base + t - ivec2(4)))','normCode(base + t - ivec2(4))')
    # The full shared tile load can extend beyond the canonical tile; raw()
    # reflects global CFA safely. Only written upstream domains are consumed.
    s=s.replace('    s.x=s.x|1;', '    s.x=s.x|1;\n    if(!domain(s,3)) return 0.0;')
    start=baseline.index('    if(p.stage==4) {')
    end=baseline.index('    if(any(greaterThanEqual(s,tileExtent()))',start)
    border=baseline[start:end].replace('p.stage==4','stageIndex()==4')
    s=s.replace('    if(stageIndex()==6) { productOutput();return; }',
       '    if(stageIndex()==6) { productOutput();return; }\n    ivec2 s=ivec2(gl_GlobalInvocationID.xy);\n'+border+
       '    if(tileExtent().x==18 || tileExtent().y==18) return;\n    readOrigin=tileOrigin();')
    s=s.replace('greaterThanEqual(groupBase(), ivec2(p.width,p.height))','greaterThanEqual(groupBase(), tileExtent())')
    s=s.replace('greaterThanEqual(ij, ivec2(p.width,p.height))','greaterThanEqual(ij, tileExtent())')
    s=s.replace('precise float vh = vhDir(ij);','precise float vh = domain(ij,4)?vhDir(ij):upstreamVh(ij);')
    s=s.replace('if (c != 1) {\n            lp = lpf(ij);\n            pq = pqDir(ij);','if (c != 1) {\n            lp = domain(ij,2)?lpf(ij):0.0;\n            pq = domain(ij,4)?pqDir(ij):0.0;')
    s=s.replace('if (c == 1) {\n            g = fetchN(ij);','if (c == 1 || !domain(ij,4)) {\n            g = fetchN(ij);')
    s=s.replace('        int opp = 2 - c;','        if(!domain(ij,4)) { storeRgb(ij,vec4(fetchN(ij),fetchN(ij),fetchN(ij),1.0));return; }\n        int opp = 2 - c;')
    # Restore independently audited paired-site dispatch only after the
    # canonical FP32 candidate has passed. Stage 2 owns final R/B pixels;
    # stage 3 owns green pixels, with immutable green and opposite buffers.
    s=s.replace('ivec2(gl_WorkGroupID.xy) * ivec2(8)', 'ivec2(gl_WorkGroupID.xy) * ivec2(stageIndex()>=2 && stageIndex()<=3 ? 16 : 8,8)')
    s=s.replace('    ivec2 ij = ivec2(gl_GlobalInvocationID.xy);',
        '    ivec2 ij = ivec2(gl_GlobalInvocationID.xy);\n    if(stageIndex()==2 || stageIndex()==3) { int rb=colorAt(ivec2(0,ij.y))==1?1:0; ij.x=ij.x*2+(stageIndex()==2?rb:1-rb); }')
    s=s.replace('vec4 previousAt(ivec2 s) { return stageIndex()==2 ? greenRgb[linearIndex(s)] : oppositeRgb[linearIndex(s)]; }',
        'vec4 previousAt(ivec2 s) { int i=linearIndex(s); if(stageIndex()==2 || colorAt(s)==1) return greenRgb[i]; return vec4(oppositeRgb[i].r,greenRgb[i].g,oppositeRgb[i].b,1.0); }')
    s=s.replace('else if(stageIndex()==2) oppositeRgb[i]=v;',
        'else if(stageIndex()==2) { oppositeRgb[i]=v; if(domain(s,9)) { ivec2 site=s+tileOrigin();outputRgb[site.y*p.width+site.x]=vec4(max(v.rgb,vec3(0.0)),1.0); } }')
    s=s.replace('    if (c != 1) { storeRgb(ij, previousAt(ij)); return; }','    if (c != 1) return;')
    s=s.replace('if(any(lessThan(s,ivec2(4)))) return 0.0;', 'if(any(lessThan(s,ivec2(4))) || any(greaterThanEqual(s,ivec2(tileSize-4)))) return 0.0;')
    s=s.replace('int indexOf(ivec2 s) { return int(gl_GlobalInvocationID.z)*tileSize*tileSize+reflection(s.y,tileSize)*tileSize+reflection(s.x,tileSize); }',
        'int indexOf(ivec2 s) { return int(gl_GlobalInvocationID.z)*tileSize*tileSize+s.y*tileSize+s.x; }')
    s=s.replace('    int c = colorAt(ij);', '    if(stageIndex()==3 && !domain(ij,9)) return;\n    int c = colorAt(ij);')
    return s


def canonical_image_source():
    """Same FP32 stage values in separately owned Vulkan storage images."""
    import re
    s=canonical_engine_source()
    for binding,name,block in [(1,'directions','Directions'),(4,'greenRgb','Green'),(5,'oppositeRgb','Opposite')]:
        old=f'layout(binding={binding},std430) buffer {block} {{ vec4 {name}[]; }};'
        new=f'layout(binding={binding},rgba32f) uniform image2DArray image_{name};\n'
        new+=f'vec4 load_{name}(int i) {{ return imageLoad(image_{name},ivec3(i%194,(i/194)%194,i/(194*194))); }}\n'
        new+=f'void store_{name}(int i,vec4 v) {{ imageStore(image_{name},ivec3(i%194,(i/194)%194,i/(194*194)),v); }}'
        s=replace_once(s,old,new)
        # All assignments use one-line expressions without embedded semicolons.
        s=re.sub(r'\b'+name+r'\[([^]\n]+)\]\s*=\s*([^;]+);',lambda m:f'store_{name}('+m[1]+','+m[2]+');',s)
        s=re.sub(r'\b'+name+r'\[([^]\n]+)\]',lambda m:f'load_{name}('+m[1]+')',s)
    return s


def cached_engine_source():
    """Compact FP32 intermediates and immutable high-pass response cache."""
    s=canonical_engine_source()
    s=replace_once(s,'layout(binding=4,std430) buffer Green { vec4 greenRgb[]; };',
        'layout(binding=4,std430) buffer Green { float greenChannel[]; };')
    s=replace_once(s,'layout(binding=5,std430) buffer Opposite { vec4 oppositeRgb[]; };',
        'layout(binding=5,std430) buffer Opposite { vec2 oppositeChannels[]; };')
    s=replace_once(s,'layout(binding=1,std430) buffer Directions { vec4 directions[]; };',
        'layout(binding=1,std430) buffer Directions { vec4 directions[]; };\nlayout(binding=2,std430) buffer Responses { vec4 responses[]; };')
    s=s.replace('greenRgb[i]=v;','greenChannel[i]=v.g;')
    s=s.replace('oppositeRgb[i]=v;','oppositeChannels[i]=v.rb;')
    s=s.replace('return greenRgb[i];','return vec4(colorAt(s)==0?fetchN(s):0.0,greenChannel[i],colorAt(s)==2?fetchN(s):0.0,1.0);')
    s=s.replace('oppositeRgb[i].r,greenRgb[i].g,oppositeRgb[i].b','oppositeChannels[i].r,greenChannel[i],oppositeChannels[i].g')
    for axis in ['V','H','P','Q']:
        s=replace_once(s,f'float hpf{axis}(ivec2 s) {{',f'float uncachedHpf{axis}(ivec2 s) {{')
    # Stage 7 writes every cache texel before the dependent guide dispatch.
    # Diagonal values retain the pinned odd-column pairing in uncachedHpfP/Q.
    wrappers="""
float hpfV(ivec2 s) { return responses[indexOf(s)].x; }
float hpfH(ivec2 s) { return responses[indexOf(s)].y; }
float hpfP(ivec2 s) { return responses[indexOf(s)].z; }
float hpfQ(ivec2 s) { return responses[indexOf(s)].w; }
"""
    # Prototypes are needed because vhDir appears before the diagonal functions.
    s=s.replace('// RT step 1.2:',wrappers+'\n// RT step 1.2:')
    s=replace_once(s,'    int c = colorAt(ij);',
        '    if(stageIndex()==7) { responses[indexOf(ij)]=vec4(uncachedHpfV(ij),uncachedHpfH(ij),uncachedHpfP(ij),uncachedHpfQ(ij));return; }\n    int c = colorAt(ij);')
    # Only odd columns carry diagonal HPF in the pinned source. Store that
    # response once per horizontal pair; keep cardinal responses per pixel.
    s=s.replace('responses[indexOf(s)].z','responses[indexOf(ivec2(s.x|1,s.y))].z')
    s=s.replace('responses[indexOf(s)].w','responses[indexOf(ivec2(s.x|1,s.y))].w')
    s=s.replace('uncachedHpfP(ij),uncachedHpfQ(ij)', '(ij.x&1)!=0?uncachedHpfP(ij):0.0,(ij.x&1)!=0?uncachedHpfQ(ij):0.0')
    # Green reconstruction owns one R/B site and its native-green companion.
    # The missing mate at an odd tile width is initialized explicitly.
    s=s.replace('stageIndex()>=2 && stageIndex()<=3','stageIndex()>=1 && stageIndex()<=3')
    s=s.replace('if(stageIndex()==2 || stageIndex()==3) { int rb=colorAt(ivec2(0,ij.y))==1?1:0; ij.x=ij.x*2+(stageIndex()==2?rb:1-rb); }',
        'if(stageIndex()>=1 && stageIndex()<=3) { int rb=colorAt(ivec2(0,ij.y))==1?1:0; ij.x=ij.x*2+(stageIndex()==3?1-rb:rb); }')
    s=s.replace('if(stageIndex()==1) greenChannel[i]=v.g;',
        'if(stageIndex()==1) { greenChannel[i]=v.g;ivec2 mate=ivec2(s.x^1,s.y); if(mate.x<tileExtent().x) greenChannel[indexOf(mate)]=fetchN(mate); }')
    s=replace_once(s,'    if (any(greaterThanEqual(ij, tileExtent()))) return;',
        '    if(stageIndex()==1 && (tileExtent().x&1)!=0 && ij.x==tileExtent().x && ij.y<tileExtent().y) { ivec2 mate=ivec2(ij.x^1,ij.y);greenChannel[indexOf(mate)]=fetchN(mate); }\n    if (any(greaterThanEqual(ij, tileExtent()))) return;')
    # Tile metadata and CFA reflection are invariant for each invocation.
    # Keep the uncommon reflection path only for actual sensor-edge samples.
    s=replace_once(s,'ivec2 tileOrigin() { int nx=(p.width+tileStep-1)/tileStep; int t=int(gl_GlobalInvocationID.z);return ivec2(t%nx,t/nx)*tileStep; }',
        'ivec2 cachedOrigin,cachedExtent,cachedGroupBase;int cachedAtlasBase;\nivec2 tileOrigin() { return cachedOrigin; }')
    s=replace_once(s,'ivec2 tileExtent() { return min(ivec2(tileSize),ivec2(p.width,p.height)-tileOrigin()); }',
        'ivec2 tileExtent() { return cachedExtent; }')
    s=replace_once(s,'int indexOf(ivec2 s) { return int(gl_GlobalInvocationID.z)*tileSize*tileSize+s.y*tileSize+s.x; }',
        'int indexOf(ivec2 s) { return cachedAtlasBase+s.y*tileSize+s.x; }')
    s=replace_once(s,'ivec2 groupBase() { return ivec2(gl_WorkGroupID.xy) * ivec2(stageIndex()>=1 && stageIndex()<=3 ? 16 : 8,8); }',
        'ivec2 groupBase() { return cachedGroupBase; }')
    s=replace_once(s,'precise float raw(ivec2 s) { s+=readOrigin;return cfa[reflection(s.y,p.height)*p.width+reflection(s.x,p.width)]; }',
        'precise float raw(ivec2 s) { s+=readOrigin; if(all(greaterThanEqual(s,ivec2(0))) && all(lessThan(s,ivec2(p.width,p.height)))) return cfa[s.y*p.width+s.x];return cfa[reflection(s.y,p.height)*p.width+reflection(s.x,p.width)]; }')
    s=replace_once(s,'void main() {',"""void main() {
    int nx=(p.width+tileStep-1)/tileStep;int t=int(gl_GlobalInvocationID.z);
    cachedOrigin=ivec2(t%nx,t/nx)*tileStep;
    cachedExtent=min(ivec2(tileSize),ivec2(p.width,p.height)-cachedOrigin);
    cachedAtlasBase=t*tileSize*tileSize;
    cachedGroupBase=ivec2(gl_WorkGroupID.xy)*ivec2(stageIndex()>=1 && stageIndex()<=3?16:8,8);
""")
    return s


def baked_stage_source(stage):
    if stage not in (0,1,2,3,4,6,7):
        raise ValueError('unsupported recording stage')
    return replace_once(cached_engine_source(),
        'layout(constant_id=9) const int RCD_STAGE=-1;',f'const int RCD_STAGE={stage};')


def packed_engine_source():
    """Eight FP32 guide scalars per Bayer cell, with separate compact stages.

    CFA, addressing and reconstruction retain full source resolution. Four VH
    sites and two R/B LP/PQ sites are independently addressed scalar locations.
    """
    import re
    s=cached_engine_source()
    s=replace_once(s,'layout(binding=2,std430) buffer Responses { vec4 responses[]; };','')
    for axis in ['V','H','P','Q']:
        s=re.sub(r'float hpf'+axis+r'\(ivec2 s\) \{[^}]+\}',f'float hpf{axis}(ivec2 s) {{ return uncachedHpf{axis}(s); }}',s)
    s=re.sub(r'    if\(stageIndex\(\)==7\) \{[^}]+\}\n','',s)
    s=replace_once(s,'layout(binding=1,std430) buffer Directions { vec4 directions[]; };',
        'layout(binding=1,std430) buffer Directions { float cellGuides[]; };')
    s=replace_once(s,'float hpfV(ivec2 s) {','float uncachedHpfP(ivec2 s);float uncachedHpfQ(ivec2 s);\nfloat hpfV(ivec2 s) {')
    helpers="""
int guideCell(ivec2 s) { return cachedAtlasBase*2+((s.y/2)*97+s.x/2)*8; }
vec4 readGuides(ivec2 s) {
    int base=guideCell(s),row=s.y&1,q=row*2+(s.x&1);
    // Green-site LP/PQ are not defined and are never consumed.
    return vec4(cellGuides[base+q],colorAt(s)==1?0.0:cellGuides[base+4+row],colorAt(s)==1?0.0:cellGuides[base+6+row],0.0);
}
void writeGuides(ivec2 s,float vh,float lp,float pq) {
    int base=guideCell(s),row=s.y&1,q=row*2+(s.x&1);
    cellGuides[base+q]=vh;
    if(colorAt(s)!=1) { cellGuides[base+4+row]=lp;cellGuides[base+6+row]=pq; }
}
"""
    s=replace_once(s,'// Scratch direction read',helpers+'\n// Scratch direction read')
    s=replace_once(s,'return directions[linearIndex(s)];','return readGuides(s);')
    s=replace_once(s,'directions[linearIndex(ij)]=vec4(vh,lp,pq,0.0);','writeGuides(ij,vh,lp,pq);')
    return s


def direct_raw_engine_source():
    """Identical packed stage layout with read-only CFA instead of shared CFA.

    This is a device-dependent experiment: no claim that shared memory is faster
    on every GPU. The canonical raw() adapter retains sensor-edge reflection.
    """
    s=packed_engine_source()
    start=s.index('void loadTile() {');end=s.index('// Prior-mode rgb read',start)
    # Keep the intermediate helpers between fetchN and rgbAt, replacing only
    # shared loading and fetchN. The helper section starts at linearIndex.
    helpers=s.index('int linearIndex(',start)
    s=s[:start]+'void loadTile() {}\nfloat fetchN(ivec2 s) { return raw(s); }\n\n'+s[helpers:]
    s=s.replace('shared float tile[16][24];','')
    return s


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--compiler', default='glslangValidator')
    parser.add_argument('--validator', default='spirv-val')
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    source = args.out / 'recording_candidate_fp32.comp'
    source.write_text(prepare())
    binary = source.with_suffix('.spv')
    subprocess.run([args.compiler, '-V', '--target-env', 'vulkan1.1',
                    str(source), '-o', str(binary)], check=True)
    subprocess.run([args.validator, '--target-env', 'vulkan1.1', str(binary)], check=True)
    manifest = {
        'candidate': str(SOURCE.relative_to(ROOT)), 'candidate_sha256': PIN,
        'outputs': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in (source, binary)},
        'compiler': subprocess.check_output([args.compiler, '--version'], text=True),
        'validator': subprocess.check_output([args.validator, '--version'], text=True),
        'dispatch': 'ceil(width/8), ceil(height/8), 1; four specialized stages 0..3',
        'storage': 'RGBA32F; two distinct RGB images plus immutable directions after stage 0',
        'push_constant_bytes': 72,
        'validation': 'compile and SPIR-V validation only; no numerical/device/performance pass',
        'production_default': False,
    }
    (args.out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')


if __name__ == '__main__':
    main()
