#!/usr/bin/env python3
"""Requirements-derived integer input adapter around the pinned public baseline.

GPL-3.0-or-later; RawLens/Codex 2026-11-01. Reconstruction remains baseline.comp.
"""
from pathlib import Path
ROOT=Path(__file__).resolve().parent

def packing_source():
    s=(ROOT/'raw_cell_pack.comp').read_text()
    for old,new in [('binding=0,','binding=7,'),('binding=1,','binding=0,'),('binding=2,','binding=8,')]:s=s.replace(old,new)
    return s

def reconstruction_source():
    s=(ROOT/'baseline.comp').read_text()
    s=s.replace('readonly buffer Cfa { float cfa[]; }','readonly buffer Cfa { uint cellWords[]; }')
    old='layout(push_constant) uniform Params { int width; int height; int phase; int stage; } p;'
    new='layout(push_constant) uniform Params { int width; int height; int phase; int stage; vec4 black; float white; int left; int top; int reserved; } p;'
    assert s.count(old)==1;s=s.replace(old,new)
    helper='''
// Pixel coordinates have already passed the public scalar domain/border rule.
precise float normalizedCode(ivec2 s) {
    int cell=(s.y/2)*((p.width+1)/2)+s.x/2;
    int lane=(s.y&1)*2+(s.x&1);
    uint code=(cellWords[2*cell+lane/2]>>uint(16*(lane&1)))&65535u;
    int quad=(((s.y+p.top)&1)*2)+((s.x+p.left)&1);
    precise float numerator=float(code)-p.black[quad];
    precise float denominator=p.white-p.black[quad];
    return clamp(numerator/denominator,0.0,1.0);
}
'''
    s=s.replace('precise float raw(ivec2 s)',helper+'\nprecise float raw(ivec2 s)')
    old='return cfa[reflection(s.y,p.height)*p.width+reflection(s.x,p.width)];'
    assert s.count(old)==1;s=s.replace(old,'return normalizedCode(ivec2(reflection(s.x,p.width),reflection(s.y,p.height)));')
    s=s.replace('cfa[n.y*p.width+n.x]','normalizedCode(n)').replace('cfa[s.y*p.width+s.x]','normalizedCode(s)')
    assert 'cfa[' not in s
    # Requirements-derived compact planes: LP/PQ and missing R/B exist only
    # at one R/B site per cell row; native samples remain in packed input.
    index_helper="""
int rbIndex(ivec2 s) {
    return int(gl_GlobalInvocationID.z)*(194*194/2)+((s.y/2)*97+s.x/2)*2+(s.y&1);
}
"""
    s=s.replace('bool domain(ivec2 s,int margin)',index_helper+'\nbool domain(ivec2 s,int margin)')
    s=s.replace('buffer Opposite { vec2 opposite[]; };','buffer Opposite { float missingRb[]; };')
    s=s.replace('opposite[indexOf(s)][c/2]','missingRb[rbIndex(s)]')
    s=s.replace('lp[indexOf(s)]','lp[rbIndex(s)]').replace('lp[indexOf(s+2*d)]','lp[rbIndex(s+2*d)]')
    s=s.replace('        lp[i]=','        if(c!=1) lp[rbIndex(s)]=')
    s=s.replace('        pq[i]=','        if(c!=1) pq[rbIndex(s)]=')
    for point in ['s','s+NW','s+NE','s+SW','s+SE']:
        s=s.replace('pq[indexOf('+point+')]','pq[rbIndex('+point+')]')
    s=s.replace('refine(pq[i],','refine(pq[rbIndex(s)],')
    s=s.replace('if(c==1 || !domain(s,4)) { opposite[i]=vec2(raw(s)); return; }',
        'if(c==1) return;\n        if(!domain(s,4)) { missingRb[rbIndex(s)]=raw(s);return; }')
    s=s.replace('opposite[i]=c==0?vec2(raw(s),missing):vec2(missing,raw(s));','missingRb[rbIndex(s)]=missing;')
    s=s.replace('precise vec3 result=vec3(opposite[i].x,g(s),opposite[i].y);',
        'precise vec3 result=c==0?vec3(raw(s),g(s),missingRb[rbIndex(s)]):c==2?vec3(missingRb[rbIndex(s)],g(s),raw(s)):vec3(0.0,g(s),0.0);')
    assert 'opposite[' not in s
    # Every reconstruction read is within the selected public tile domain:
    # guide radius 4 with margin 4, green radius 4 with margin 4, opposite
    # radius 3 with margin 4, final radius 3 with margin 9. Border reads
    # explicitly test sensor bounds. Retained VH selects an earlier valid
    # domain before changing readOrigin. No reflective addressing is reached.
    old='int(gl_GlobalInvocationID.z)*tileSize*tileSize+reflection(s.y,tileSize)*tileSize+reflection(s.x,tileSize)'
    assert old in s;s=s.replace(old,'int(gl_GlobalInvocationID.z)*tileSize*tileSize+s.y*tileSize+s.x')
    old='normalizedCode(ivec2(reflection(s.x,p.width),reflection(s.y,p.height)))'
    assert old in s;s=s.replace(old,'normalizedCode(s)')
    return s

# Persistent engine metadata uses the same 64-byte validated RAW layout as the
# standalone harness. Product parameters retain their independent binding.
META='''layout(binding=10,std430) readonly buffer RawMetadata {
    int sensorWidth; int sensorHeight; int rowBytes; int pixelBytes;
    int left; int top; int width; int height;
    vec4 black; float white; int reserved0; int reserved1; int reserved2;
} m;'''

def app_packing_source():
    s=packing_source().replace('binding=7,','binding=9,')
    start=s.index('layout(push_constant) uniform Params {')
    end=s.index('} p;',start)+4
    s=s[:start]+META+s[end:].replace('p.','m.')
    s=s.replace('        codes[q]=sensorCode(sensor);','''        if(m.reserved2<=1) codes[q]=sensorCode(sensor);
        else {
            // Approximate mode: average each CFA color independently over a
            // rectangular source-cell block; never mix R/G/B raw samples.
            uint total=0u;uint count=0u;
            for(int dy=0;dy<m.reserved2;++dy) for(int dx=0;dx<m.reserved2;++dx) {
                ivec2 point=2*cell*m.reserved2+ivec2(q&1,q>>1)+2*ivec2(dx,dy);
                if(point.x>=m.reserved0 || point.y>=m.reserved1) continue;
                total+=sensorCode(point+ivec2(m.left,m.top));++count;
            }
            codes[q]=count==0u?0u:(total+count/2u)/count;
        }''')
    return s

def app_reconstruction_source():
    from build_variants import PRODUCT
    s=reconstruction_source()
    start=s.index('layout(push_constant) uniform Params {')
    end=s.index('} p;',start)+4
    s=s[:start]+'layout(push_constant) uniform Params { int width; int height; int phase; int stage; } p;\n'+META+s[end:]
    for field in ('black','white','left','top'):s=s.replace('p.'+field,'m.'+field)
    s=s.replace('writeonly buffer Output','buffer Output')
    product=PRODUCT.replace('float(site.x+int(config[0])-int(config[7]))',
        '(float(site.x)*float(max(1,m.reserved2))+0.5*float(max(1,m.reserved2)-1)+float(int(config[0])-int(config[7])))').replace('float(site.y+int(config[1])-int(config[8]))',
        '(float(site.y)*float(max(1,m.reserved2))+0.5*float(max(1,m.reserved2)-1)+float(int(config[1])-int(config[8])))')
    s=s.replace('void main() {',product+'\nvoid main() {\n    if(p.stage==6) { productOutput();return; }')
    s=s.replace('const float eps=','layout(constant_id=9) const int algorithmPass=-1;\nconst float eps=')
    return s.replace('p.stage','(algorithmPass<0?p.stage:algorithmPass)')


def app_normalizing_source():
    """Packed RAW16 input -> one immutable FP32 normalization cache.

    Explicit bandwidth experiment: remove repeated decode/division from RCD
    sample reads. No CPU normalization, source decimation or FP16 arithmetic.
    """
    s=app_packing_source()
    s=s.replace('layout(binding=0,std430) writeonly buffer Cells { uvec2 cells[]; };',
        'layout(binding=0,std430) writeonly buffer Normalized { float cfa[]; };')
    s=s.replace('if(WRITE_NORMALIZED) {','{')
    s=s.replace('''    cells[index]=uvec2(codes.x|(codes.y<<16u),codes.z|(codes.w<<16u));
    if(WRITE_NORMALIZED) normalizedCells[index]=normalized;''','''    for(int q=0;q<4;++q) {
        ivec2 local=2*cell+ivec2(q&1,q>>1);
        if(local.x<m.width && local.y<m.height) cfa[local.y*m.width+local.x]=normalized[q];
    }''')
    assert 'cells[index]=' not in s
    return s


def app_normalized_reconstruction_source():
    s=app_reconstruction_source()
    s=s.replace('readonly buffer Cfa { uint cellWords[]; }','readonly buffer Cfa { float cfa[]; }')
    start=s.index('precise float normalizedCode(ivec2 s) {')
    end=s.index('\n}',start)+2
    s=s[:start]+'precise float normalizedCode(ivec2 s) { return cfa[s.y*p.width+s.x]; }'+s[end:]
    # Interior guide cells always use their current tile, so avoid entering
    # the retained-scratch traversal at all. The exact fallback remains.
    marker='precise float upstreamVh(ivec2 s) {'
    assert marker in s
    s=s.replace(marker,marker+'\n    if(any(greaterThanEqual(s,ivec2(tileSize-4)))) return 0.0;\n    if(domain(s,4)) return direction(s,S,E);')
    return s


def app_normalized_cached_source():
    """Compute directional responses once; reuse dead final-RGB storage.

    Layout/scheduling derived from the public response -> guide -> RGB lifetime.
    No extra frame allocation; response storage is dead before RGB is written.
    """
    s=app_normalized_reconstruction_source()
    s=s.replace('precise float response(ivec2 s,ivec2 d)', 'precise float uncachedResponse(ivec2 s,ivec2 d)')
    marker='precise float direction(ivec2 s,ivec2 a,ivec2 b)'
    helper='''precise float response(ivec2 s,ivec2 d) {
    ivec2 position=s+readOrigin;
    int component=d.x==0?0:d.y==0?1:d.x==d.y?2:3;
    return outputRgb[position.y*p.width+position.x][component];
}
'''
    assert marker in s;s=s.replace(marker,helper+marker)
    prepass='''
    if((algorithmPass<0?p.stage:algorithmPass)==5) {
        ivec2 site=ivec2(gl_GlobalInvocationID.xy);
        if(site.x>=p.width || site.y>=p.height) return;
        readOrigin=ivec2(0);
        precise vec4 values=vec4(0.0);
        if(site.x>=3 && site.y>=3 && site.x<p.width-3 && site.y<p.height-3) {
            values.x=uncachedResponse(site,S);values.y=uncachedResponse(site,E);
            if((site.x&1)!=0) { values.z=uncachedResponse(site,SE);values.w=uncachedResponse(site,SW); }
        }
        outputRgb[site.y*p.width+site.x]=values;return;
    }
'''
    s=s.replace('void main() {','void main() {'+prepass)
    # Native engine uses distinct allocations for every binding. Tell the
    # compiler these storage blocks cannot alias each other. Reuse of the
    # response/output array across passes is within the same declaration.
    return s.replace(' buffer ', ' restrict buffer ')


def app_normalized_shared_final_source():
    """Cooperative final-stencil reads, same FP32 equations and full crop.

    An 8x8 output group requires a radius-three halo: 14x14 CFA, green,
    and missing-color arrays = 2352 shared bytes. All invocations load and
    synchronize before any per-pixel bounds return.
    """
    s=app_normalized_cached_source()
    declarations='''
shared float finalRaw[196];
shared float finalGreen[196];
shared float finalMissing[196];
ivec2 finalOrigin() { return ivec2(gl_WorkGroupID.xy)*8-ivec2(3); }
int finalIndex(ivec2 site) { ivec2 local=site-finalOrigin();return local.y*14+local.x; }
'''
    s=s.replace('ivec2 readOrigin;', 'ivec2 readOrigin;'+declarations)
    stage='(algorithmPass<0?p.stage:algorithmPass)'
    s=s.replace('s+=readOrigin;return normalizedCode(s);',
        'if('+stage+'==3) return finalRaw[finalIndex(s)];s+=readOrigin;return normalizedCode(s);')
    s=s.replace('return green[indexOf(s)];',
        'return '+stage+'==3?finalGreen[finalIndex(s)]:green[indexOf(s)];')
    s=s.replace('return color(s)==c?raw(s):missingRb[rbIndex(s)];',
        'return color(s)==c?raw(s):('+stage+'==3?finalMissing[finalIndex(s)]:missingRb[rbIndex(s)]);')
    prepare='''
void prepareFinal() {
    ivec2 extent=tileExtent();
    for(uint k=gl_LocalInvocationIndex;k<196u;k+=64u) {
        ivec2 site=finalOrigin()+ivec2(int(k)%14,int(k)/14);
        float r=0.0,g=0.0,m=0.0;
        if(all(greaterThanEqual(site,ivec2(0))) && all(lessThan(site,extent)) && extent.x!=18 && extent.y!=18) {
            r=normalizedCode(site+tileOrigin());g=green[indexOf(site)];
            if(color(site)!=1) m=missingRb[rbIndex(site)];
        }
        finalRaw[k]=r;finalGreen[k]=g;finalMissing[k]=m;
    }
    barrier();
}
'''
    s=s.replace('void main() {',prepare+'\nvoid main() {\n    if('+stage+'==3) prepareFinal();')
    old='precise vec3 result=c==0?vec3(raw(s),g(s),missingRb[rbIndex(s)]):c==2?vec3(missingRb[rbIndex(s)],g(s),raw(s)):vec3(0.0,g(s),0.0);'
    new='precise vec3 result=c==0?vec3(raw(s),g(s),finalMissing[finalIndex(s)]):c==2?vec3(finalMissing[finalIndex(s)],g(s),raw(s)):vec3(0.0,g(s),0.0);'
    assert old in s;s=s.replace(old,new)
    assert 'finalRaw[finalIndex(s)]' in s and 'finalGreen[finalIndex(s)]' in s
    return s
