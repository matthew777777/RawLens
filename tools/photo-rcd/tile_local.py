#!/usr/bin/env python3
"""One-workgroup public RCD reconstruction; full-resolution, FP32.

GPL-3.0-or-later. Mathematical source: pinned RawTherapee baseline.comp.
Storage and schedule independently derived from its dependency radii.
"""
from requirements_input_shaders import app_normalized_reconstruction_source

SHARED = r'''
// 16x16 owned core. Raw radius 11 also covers upstream's odd-column
// diagonal response. Other radii follow the reconstruction dependency graph.
shared float tileRaw[38*38];
shared float tileVh[28*28];
shared float tileLp[30*30];
shared float tilePq[24*24];
shared float tileGreen[26*26];
shared float tileMissing[22*22];
ivec2 coreOrigin;
int localIndex(ivec2 site,int radius,int edge) {
    ivec2 q=site-coreOrigin+ivec2(radius);return q.y*edge+q.x;
}
bool validLocal(ivec2 site) {
    return all(greaterThanEqual(site,ivec2(0))) && all(lessThan(site,tileExtent()));
}
'''

RECONSTRUCT = r'''
precise float reconstructGreen(ivec2 s) {
    if(color(s)==1 || !domain(s,4)) return raw(s);
    precise float ng=rawGradient(s,N),sg=rawGradient(s,S),wg=rawGradient(s,W),eg=rawGradient(s,E);
    precise float ne=greenEstimate(s,N),se=greenEstimate(s,S),we=greenEstimate(s,W),ee=greenEstimate(s,E);
    precise float ve=(sg*ne+ng*se)/(ng+sg),he=(wg*ee+eg*we)/(eg+wg);
    return rcdInterpolate(vhAt(s),he,ve);
}
precise float reconstructMissing(ivec2 s) {
    if(color(s)==1) return 0.0;
    if(!domain(s,4)) return raw(s);
    precise float nwg=diagonalGradient(s,NW),neg=diagonalGradient(s,NE),swg=diagonalGradient(s,SW),seg=diagonalGradient(s,SE);
    precise float nwe=raw(s+NW)-g(s+NW),nee=raw(s+NE)-g(s+NE),swe=raw(s+SW)-g(s+SW),see=raw(s+SE)-g(s+SE);
    precise float pe=(nwg*see+seg*nwe)/(nwg+seg),qe=(neg*swe+swg*nee)/(neg+swg);
    precise float disc=refine(tilePq[localIndex(s,4,24)],tilePq[localIndex(s+NW,4,24)],tilePq[localIndex(s+NE,4,24)],tilePq[localIndex(s+SW,4,24)],tilePq[localIndex(s+SE,4,24)],false);
    return g(s)+rcdInterpolate(disc,qe,pe);
}
void reconstructLocal() {
    // Uniform exits precede all barriers. Last partial canonical tiles preserve
    // their established ownership, borders and exactly-18-pixel skip rule.
    if(any(greaterThanEqual(ivec2(gl_WorkGroupID.xy),ivec2(11))) || tileExtent().x==18 || tileExtent().y==18) return;
    coreOrigin=ivec2(gl_WorkGroupID.xy)*16+ivec2(9);
    if(any(greaterThanEqual(coreOrigin,tileExtent()-ivec2(9)))) return;
    readOrigin=tileOrigin();
    for(uint k=gl_LocalInvocationIndex;k<38u*38u;k+=256u) {
        ivec2 s=coreOrigin+ivec2(int(k)%38,int(k)/38)-ivec2(11);
        ivec2 sensor=s+readOrigin;
        tileRaw[k]=all(greaterThanEqual(sensor,ivec2(0))) && all(lessThan(sensor,ivec2(p.width,p.height)))?normalizedCode(sensor):0.0;
    }
    barrier();
    for(uint k=gl_LocalInvocationIndex;k<28u*28u;k+=256u) {
        ivec2 s=coreOrigin+ivec2(int(k)%28,int(k)/28)-ivec2(6);
        // Retained VH entries outside a short tile's written domain may have
        // been set by an earlier tile. upstreamVh independently recomputes it.
        tileVh[k]=upstreamVh(s);
    }
    for(uint k=gl_LocalInvocationIndex;k<30u*30u;k+=256u) {
        ivec2 s=coreOrigin+ivec2(int(k)%30,int(k)/30)-ivec2(7);
        tileLp[k]=domain(s,2) && color(s)!=1?raw(s)+0.5*(raw(s+N)+raw(s+S)+raw(s+W)+raw(s+E))+0.25*(raw(s+NW)+raw(s+NE)+raw(s+SW)+raw(s+SE)):0.0;
    }
    for(uint k=gl_LocalInvocationIndex;k<24u*24u;k+=256u) {
        ivec2 s=coreOrigin+ivec2(int(k)%24,int(k)/24)-ivec2(4);
        tilePq[k]=domain(s,4) && color(s)!=1?diagonalDirection(s):0.0;
    }
    barrier();
    for(uint k=gl_LocalInvocationIndex;k<26u*26u;k+=256u) {
        ivec2 s=coreOrigin+ivec2(int(k)%26,int(k)/26)-ivec2(5);
        tileGreen[k]=validLocal(s)?reconstructGreen(s):0.0;
    }
    barrier();
    for(uint k=gl_LocalInvocationIndex;k<22u*22u;k+=256u) {
        ivec2 s=coreOrigin+ivec2(int(k)%22,int(k)/22)-ivec2(3);
        tileMissing[k]=validLocal(s)?reconstructMissing(s):0.0;
    }
    barrier();
    ivec2 s=coreOrigin+ivec2(gl_LocalInvocationID.xy);
    if(!domain(s,9)) return;
    int c=color(s);
    precise float missing=tileMissing[localIndex(s,3,22)];
    precise vec3 result=c==0?vec3(raw(s),g(s),missing):c==2?vec3(missing,g(s),raw(s)):vec3(0.0,g(s),0.0);
    if(c==1) for(int k=0;k<=2;k+=2) {
        precise float ng=cardinalGradient(s,N,k),sg=cardinalGradient(s,S,k),wg=cardinalGradient(s,W,k),eg=cardinalGradient(s,E,k);
        precise float ne=colorAt(s+N,k)-g(s+N),se=colorAt(s+S,k)-g(s+S),we=colorAt(s+W,k)-g(s+W),ee=colorAt(s+E,k)-g(s+E);
        precise float ve=(ng*se+sg*ne)/(ng+sg),he=(eg*we+wg*ee)/(eg+wg);
        result[k]=g(s)+rcdInterpolate(vhAt(s),he,ve);
    }
    ivec2 site=s+tileOrigin();
    writePixel(site,max(vec3(0.0),result));
}
'''

def source(core=(16,16)):
    s=app_normalized_reconstruction_source()
    s=s.replace('layout(local_size_x=8,local_size_y=8) in;','layout(local_size_x=16,local_size_y=16) in;')
    s=s.replace('ivec2 readOrigin;','ivec2 readOrigin;'+SHARED)
    old='precise float raw(ivec2 s) { s+=readOrigin;return normalizedCode(s); }'
    new='''precise float raw(ivec2 s) {
    if(all(equal(readOrigin,tileOrigin()))) {
        ivec2 q=s-coreOrigin+ivec2(11);
        if(all(greaterThanEqual(q,ivec2(0))) && all(lessThan(q,ivec2(38)))) return tileRaw[q.y*38+q.x];
    }
    return normalizedCode(s+readOrigin);
}'''
    assert old in s;s=s.replace(old,new)
    s=s.replace('return green[indexOf(s)];','return tileGreen[localIndex(s,5,26)];')
    for point in ['s','s+NW','s+NE','s+SW','s+SE']:
        s=s.replace('vh[indexOf('+point+')]','tileVh[localIndex('+point+',6,28)]')
    s=s.replace('lp[rbIndex(s)]','tileLp[localIndex(s,7,30)]').replace('lp[rbIndex(s+2*d)]','tileLp[localIndex(s+2*d,7,30)]')
    s=s.replace('missingRb[rbIndex(s)]','tileMissing[localIndex(s,3,22)]')
    # Reuse the existing application product equations at the final pixel,
    # after identical RCD clamping. FP32 reference mode retains RGB32F output.
    s=s.replace('void productOutput() {\n    ivec2 site=ivec2(gl_GlobalInvocationID.xy);',
        'void productPixel(ivec2 site,vec3 sourceRgb) {')
    s=s.replace('precise vec3 rgb=outputRgb[index].rgb;','precise vec3 rgb=sourceRgb;')
    write_pixel='''
void writePixel(ivec2 site,vec3 rgb) {
    int stage=(algorithmPass<0?p.stage:algorithmPass);
    if(stage==3 || stage==5) productPixel(site,rgb);
    else outputRgb[site.y*p.width+site.x]=vec4(rgb,1.0);
}
'''
    start=s.index('void main() {')
    # Keep the canonical public border and separate product adapter, replacing
    # only the five full-atlas reconstruction stages with the cooperative one.
    border_start=s.index('    if((algorithmPass<0?p.stage:algorithmPass)==4) {',start)
    border_end=s.index('    if(any(greaterThanEqual(s,tileExtent()))',border_start)
    border=s[border_start:border_end].replace('if((algorithmPass<0?p.stage:algorithmPass)==4)', 'if(stage==4 || stage==5)').replace('outputRgb[s.y*p.width+s.x]=vec4(result,1.0);','writePixel(s,result);')
    s=s[:start]+write_pixel+RECONSTRUCT+'''\nvoid main() {
    int stage=(algorithmPass<0?p.stage:algorithmPass);
    if(stage==0 || stage==3) { reconstructLocal();return; }
    ivec2 s=ivec2(gl_GlobalInvocationID.xy);
'''+border+'}\n'
    # Global intermediates have no remaining readers/writers; descriptors are
    # omitted entirely from the module. No hidden atlas allocations are needed.
    import re
    for binding in range(1,6):
        s=re.sub(r'layout\(binding='+str(binding)+r',std430\)[^\n]+\n','',s)
    for symbol in ['vh[','lp[','pq[','green[','missingRb[']:
        assert symbol not in s,symbol
    return specialize_core(s.replace(' buffer ',' restrict buffer '),core)


def image_source(core=(16,16)):
    """Same final-half boundary through a queried external RGBA16F image."""
    s=source(core)
    old='layout(binding=8,std430) writeonly restrict buffer HalfOutput { uvec2 halfOutput[]; };'
    new='layout(binding=8,rgba16f) uniform writeonly image2D halfOutputImage;'
    assert old in s;s=s.replace(old,new)
    old='halfOutput[index]=uvec2(packHalf2x16(rgb.rg),packHalf2x16(vec2(rgb.b,1.0)));'
    # Explicit pack/unpack retains the existing round-to-half operation before
    # imageStore. RGBA16F then stores already-representable values unchanged.
    new='''uvec2 rounded=uvec2(packHalf2x16(rgb.rg),packHalf2x16(vec2(rgb.b,1.0)));
    imageStore(halfOutputImage,site,vec4(unpackHalf2x16(rounded.x),unpackHalf2x16(rounded.y)));'''
    assert old in s;s=s.replace(old,new)
    return s


def specialize_core(s,core):
    x,y=core
    assert x in (8,16,32) and y in (8,16,32) and x*y<=512
    s=s.replace('local_size_x=16,local_size_y=16',f'local_size_x={x},local_size_y={y}')
    s=s.replace('ivec2(gl_WorkGroupID.xy)*16+ivec2(9)',f'ivec2(gl_WorkGroupID.xy)*ivec2({x},{y})+ivec2(9)')
    s=s.replace('ivec2(gl_WorkGroupID.xy),ivec2(11)',f'ivec2(gl_WorkGroupID.xy),ivec2({(176+x-1)//x},{(176+y-1)//y})')
    # Two phases: sequential replacement collided when a new edge equalled a
    # later array's 16x16 edge (8x8 raw 38->30 was then rewritten as LP 30->22),
    # leaving shared memory partially unloaded. Placeholders cannot collide.
    final={}
    for old,radius in [(38,11),(28,6),(30,7),(24,4),(26,5),(22,3)]:
        w=x+2*radius;h=y+2*radius;W,H=f'@W{old}@',f'@H{old}@';final[W]=str(w);final[H]=str(h)
        for a,b in [(f'[{old}*{old}]',f'[{W}*{H}]'),(f'k<{old}u*{old}u',f'k<{W}u*{H}u'),
                    (f'int(k)%{old}',f'int(k)%{W}'),(f'int(k)/{old}',f'int(k)/{W}'),
                    (f',{radius},{old})',f',{radius},{W})')]:
            s=s.replace(a,b)
        assert f'[{W}*{H}]' in s,old
    for a,b in [('q,ivec2(38)','q,ivec2(@W38@,@H38@)'),('q.y*38+q.x','q.y*@W38@+q.x'),('k+=256u',f'k+={x*y}u')]:
        assert a in s,a;s=s.replace(a,b)
    for token,value in final.items():s=s.replace(token,value)
    assert '@' not in s
    return s
