#!/usr/bin/env python3
"""Derive layout/scheduling variants from the approved public-source baseline.

No reconstruction equation is substituted. Generated source is reviewable;
the baseline stays immutable and is the same-device optimization oracle.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import re
from build_oracle import ROOT

FUSED = r'''
// An 8x8 output core needs opposite-color halo 3 and green halo 5.
// Opposite interpolation reads green at +/-2; final gradients read opposite
// at +/-3. Green's raw/guide dependencies stay in immutable CFA/guide buffers.
shared float localGreen[18*18];
shared vec2 localOpposite[14*14];
ivec2 reconstructionBase;
int greenIndex(ivec2 s) { ivec2 q=s-reconstructionBase+ivec2(5);return q.y*18+q.x; }
int oppositeIndex(ivec2 s) { ivec2 q=s-reconstructionBase+ivec2(3);return q.y*14+q.x; }
precise float reconstructGreen(ivec2 s) {
    if(any(lessThan(s,ivec2(0))) || any(greaterThanEqual(s,tileExtent()))) return 0.0;
    if(color(s)==1 || !domain(s,4)) return raw(s);
    precise float ng=rawGradient(s,N),sg=rawGradient(s,S),wg=rawGradient(s,W),eg=rawGradient(s,E);
    precise float ne=greenEstimate(s,N),se=greenEstimate(s,S),we=greenEstimate(s,W),ee=greenEstimate(s,E);
    precise float ve=(sg*ne+ng*se)/(ng+sg),he=(wg*ee+eg*we)/(eg+wg);
    return rcdInterpolate(vhAt(s),he,ve);
}
precise vec2 reconstructOpposite(ivec2 s) {
    if(any(lessThan(s,ivec2(0))) || any(greaterThanEqual(s,tileExtent()))) return vec2(0.0);
    int c=color(s);
    if(c==1 || !domain(s,4)) return vec2(raw(s));
    precise float nwg=diagonalGradient(s,NW),neg=diagonalGradient(s,NE),swg=diagonalGradient(s,SW),seg=diagonalGradient(s,SE);
    precise float nwe=raw(s+NW)-g(s+NW),nee=raw(s+NE)-g(s+NE),swe=raw(s+SW)-g(s+SW),see=raw(s+SE)-g(s+SE);
    precise float pe=(nwg*see+seg*nwe)/(nwg+seg),qe=(neg*swe+swg*nee)/(neg+swg);
    int i=indexOf(s);
    precise float disc=refine(pq[i],pq[indexOf(s+NW)],pq[indexOf(s+NE)],pq[indexOf(s+SW)],pq[indexOf(s+SE)],false);
    precise float missing=g(s)+rcdInterpolate(disc,qe,pe);
    return c==0?vec2(raw(s),missing):vec2(missing,raw(s));
}
void reconstructTile() {
    reconstructionBase=ivec2(gl_WorkGroupID.xy)*8+ivec2(9);
    readOrigin=tileOrigin();
    uint lane=gl_LocalInvocationIndex;
    for(uint k=lane;k<324;k+=64) {
        ivec2 s=reconstructionBase+ivec2(int(k)%18,int(k)/18)-ivec2(5);
        localGreen[k]=reconstructGreen(s);
    }
    barrier();
    for(uint k=lane;k<196;k+=64) {
        ivec2 s=reconstructionBase+ivec2(int(k)%14,int(k)/14)-ivec2(3);
        localOpposite[k]=reconstructOpposite(s);
    }
    barrier();
    ivec2 s=reconstructionBase+ivec2(gl_LocalInvocationID.xy);
    if(!domain(s,9) || tileExtent().x==18 || tileExtent().y==18) return;
    precise vec2 rb=localOpposite[oppositeIndex(s)];
    precise vec3 result=vec3(rb.x,g(s),rb.y);
    if(color(s)==1) for(int c=0;c<=2;c+=2) {
        precise float ng=cardinalGradient(s,N,c),sg=cardinalGradient(s,S,c),wg=cardinalGradient(s,W,c),eg=cardinalGradient(s,E,c);
        precise float ne=colorAt(s+N,c)-g(s+N),se=colorAt(s+S,c)-g(s+S),we=colorAt(s+W,c)-g(s+W),ee=colorAt(s+E,c)-g(s+E);
        precise float ve=(ng*se+sg*ne)/(ng+sg),he=(eg*we+wg*ee)/(eg+wg);
        result[c]=g(s)+rcdInterpolate(vhAt(s),he,ve);
    }
    ivec2 site=s+tileOrigin();
    outputRgb[site.y*p.width+site.x]=vec4(max(vec3(0.0),result),1.0);
#ifdef CAPTURE_INTERMEDIATES
    // One writer per owned site. Halo values never write global destinations.
    green[indexOf(s)]=g(s);opposite[indexOf(s)]=rb;
#endif
}
'''

PRODUCT = r'''
layout(binding=7,std430) buffer ProductConfig { uint config[]; };
layout(binding=8,std430) writeonly buffer HalfOutput { uvec2 halfOutput[]; };
float parameter(int n) { return uintBitsToFloat(config[n]); }
precise vec4 mapValue(int x,int y) {
    int base=16+(y*int(config[6])+x)*4;
    return vec4(parameter(base),parameter(base+1),parameter(base+2),parameter(base+3));
}
void productOutput() {
    ivec2 site=ivec2(gl_GlobalInvocationID.xy);
    if(site.x>=p.width || site.y>=p.height) return;
    int index=site.y*p.width+site.x;
    precise vec3 rgb=outputRgb[index].rgb;
    precise vec3 wb=vec3(parameter(2),parameter(3),parameter(4));
    precise float minimum=min(wb.r,min(wb.g,wb.b));
    precise vec3 t=clamp((rgb-vec3(0.70))/vec3(0.99-0.70),0.0,1.0);
    precise vec3 white=t*t*(vec3(3.0)-2.0*t);
    precise float blend=max(white.r,max(white.g,white.b));
    rgb=rgb+(vec3(minimum)/wb-rgb)*blend;
    if(config[11]!=0u) {
        precise float nx=clamp(float(site.x+int(config[0])-int(config[7]))/float(int(config[9])-int(config[7])),0.0,1.0)*float(config[6]-1u);
        precise float ny=clamp(float(site.y+int(config[1])-int(config[8]))/float(int(config[10])-int(config[8])),0.0,1.0)*float(config[5]-1u);
        int x=int(nx),y=int(ny),x1=min(x+1,int(config[6])-1),y1=min(y+1,int(config[5])-1);
        precise vec4 a=mapValue(x,y)+(mapValue(x1,y)-mapValue(x,y))*(nx-float(x));
        precise vec4 b=mapValue(x,y1)+(mapValue(x1,y1)-mapValue(x,y1))*(nx-float(x));
        precise vec4 gains=a+(b-a)*(ny-float(y));
        rgb*=vec3(gains.x,0.5*(gains.y+gains.z),gains.w);
    }
    if(any(isnan(rgb)) || any(isinf(rgb)) || any(lessThan(rgb,vec3(0.0))) || any(greaterThan(rgb,vec3(65504.0)))) atomicOr(config[12],1u);
    halfOutput[index]=uvec2(packHalf2x16(rgb.rg),packHalf2x16(vec2(rgb.b,1.0)));
}
'''

def generate(tiled=False,packed=False,capture=False,cache=False,direct=False,specialize=False,product=False,hpf=False,images=False):
    source=(ROOT/'baseline.comp').read_text()
    def replace(old,new):
        nonlocal source
        assert source.count(old)==1,old
        source=source.replace(old,new)
    if packed:
        replace('layout(push_constant)', 'layout(binding=7,std430) buffer PackedCfa { vec4 cell[]; };\nlayout(push_constant)')
        marker='precise float raw(ivec2 s)'
        helper='''precise float siteSample(ivec2 s) {
    int pitch=(p.width+1)/2;
    return cell[(s.y/2)*pitch+s.x/2][(s.y&1)*2+(s.x&1)];
}
'''
        replace(marker,helper+marker)
        replace('return cfa[reflection(s.y,p.height)*p.width+reflection(s.x,p.width)];','return siteSample(ivec2(reflection(s.x,p.width),reflection(s.y,p.height)));')
        replace('total[nc]+=cfa[n.y*p.width+n.x];','total[nc]+=siteSample(n);')
        replace('result[color(s)]=cfa[s.y*p.width+s.x];','result[color(s)]=siteSample(s);')
        replace('if(p.stage==4) {','''if(p.stage==5) {
        ivec2 dims=(ivec2(p.width,p.height)+1)/2;
        if(any(greaterThanEqual(s,dims))) return;
        vec4 v=vec4(0.0);
        for(int q=0;q<4;++q) {
            ivec2 n=s*2+ivec2(q&1,q/2);
            if(n.x<p.width && n.y<p.height) v[q]=cfa[n.y*p.width+n.x];
        }
        cell[s.y*dims.x+s.x]=v;return;
    }
    if(p.stage==4) {''')
    if tiled:
        # Declare shared arrays/accessors before the equation helpers which use them.
        fused=FUSED
        if product:
            # 256-invocation mobile candidate: reduce redundant halo work per
            # output while staying below the queried shared-memory limit.
            fused=fused.replace('18*18','26*26').replace('14*14','22*22').replace('q.y*18+','q.y*26+').replace('q.y*14+','q.y*22+')
            fused=fused.replace('gl_WorkGroupID.xy)*8','gl_WorkGroupID.xy)*16').replace('k<324','k<676').replace('k<196','k<484').replace('k+=64','k+=256')
            fused=fused.replace('int(k)%18','int(k)%26').replace('int(k)/18','int(k)/26').replace('int(k)%14','int(k)%22').replace('int(k)/14','int(k)/22')
            replace('layout(local_size_x=8,local_size_y=8) in;','layout(local_size_x=16,local_size_y=16) in;')
        declarations=fused[:fused.index('precise float reconstructGreen')]
        functions=fused[fused.index('precise float reconstructGreen'):]
        replace('precise float g(ivec2 s) { return green[indexOf(s)]; }',declarations+'\nprecise float g(ivec2 s) { return localGreen[greenIndex(s)]; }')
        replace('return color(s)==c?raw(s):opposite[indexOf(s)][c/2];','return color(s)==c?raw(s):localOpposite[oppositeIndex(s)][c/2];')
        replace('void main() {',functions+'\nvoid main() {\n    if(p.stage==1) { reconstructTile();return; }')
    if direct:
        assert not cache, 'source-cache addressing has its own halo mapping'
        # Every executed stencil is bounded by RT's stage domains: raw taps at
        # margin 4, diagonal-response margin 3, reconstruction margin 9. Retained
        # VH selects a previous tile whose domain contains the complete stencil.
        # Border taps are explicitly bounded in main. Thus these executed reads
        # never require reflection; remove address modulo, preserving equations.
        start=source.index('int indexOf(ivec2 s)')
        end=source.index('\n',start)
        replace(source[start:end],'int indexOf(ivec2 s) { return int(gl_GlobalInvocationID.z)*tileSize*tileSize+s.y*tileSize+s.x; }')
        start=source.index('precise float raw(ivec2 s)')
        end=source.index('\n',start)
        sample='siteSample(s)' if packed else 'cfa[s.y*p.width+s.x]'
        replace(source[start:end],'precise float raw(ivec2 s) { s+=readOrigin;return '+sample+'; }')
        # No tile ever writes VH outside [4,190); those calloc entries stay
        # zero for the entire pinned scalar traversal. Avoid scanning every
        # previous tile for values which provably cannot have been written.
        replace('if(any(lessThan(s,ivec2(4)))) return 0.0;',
            'if(any(lessThan(s,ivec2(4))) || any(greaterThanEqual(s,ivec2(tileSize-4)))) return 0.0;')
    if cache:
        sample='siteSample(s)' if packed else 'cfa[s.y*p.width+s.x]'
        declarations='''shared float sourceCache[18*18];
ivec2 sourceCacheOrigin;
void loadSourceCache() {
    sourceCacheOrigin=ivec2(gl_WorkGroupID.xy)*8-ivec2(5);
    for(uint k=gl_LocalInvocationIndex;k<324;k+=64) {
        ivec2 s=tileOrigin()+sourceCacheOrigin+ivec2(int(k)%18,int(k)/18);
        s=ivec2(reflection(s.x,p.width),reflection(s.y,p.height));
        sourceCache[k]=SAMPLE;
    }
    barrier();
}
'''.replace('SAMPLE',sample)
        replace('precise float raw(ivec2 s) {',declarations+'''precise float raw(ivec2 s) {
    ivec2 q=s-sourceCacheOrigin;
    if(p.stage==0 && all(equal(readOrigin,tileOrigin())) && all(greaterThanEqual(q,ivec2(0))) && all(lessThan(q,ivec2(18)))) return sourceCache[q.y*18+q.x];
''')
        replace('void main() {','void main() {\n    if(p.stage==0) loadSourceCache();')
    if capture:
        replace('#version 450','#version 450\n#define CAPTURE_INTERMEDIATES')
    if images:
        assert not tiled and not packed
        for binding,name,block,field,type_,fmt in [(1,'vh','Vh','vh','float','r32f'),(2,'lp','Lp','lp','float','r32f'),(3,'pq','Pq','pq','float','r32f'),(4,'green','Green','green','float','r32f'),(5,'opposite','Opposite','opposite','vec2','rgba32f')]:
            replace(f'layout(binding={binding},std430) buffer {block} {{ {type_} {field}[]; }};',f'layout(binding={binding},{fmt}) uniform image2DArray {name}Image;')
            # Translate only storage accesses, not the public equations. Explicit
            # images are immutable to later passes; no in-place RGB writeback.
            source=re.sub(r'\b'+name+r'\[i\]=([^;]+);',lambda m: f'store_{name}(s,{m.group(1)});',source)
            source=re.sub(r'\b'+name+r'\[([^\]]+)\]',lambda m: f'load_{name}('+('s' if m.group(1)=='i' else m.group(1)[8:-1])+')',source)
        helpers=''
        for name in ['vh','lp','pq','green','opposite']:
            type_='vec2' if name=='opposite' else 'float';components='.xy' if name=='opposite' else '.x'
            value='vec4(value,0.0,0.0)' if name=='opposite' else 'vec4(value)'
            helpers+=f'precise {type_} load_{name}(ivec2 s) {{ return imageLoad({name}Image,ivec3(s,int(gl_GlobalInvocationID.z))){components}; }}\n'
            helpers+=f'void store_{name}(ivec2 s,{type_} value) {{ imageStore({name}Image,ivec3(s,int(gl_GlobalInvocationID.z))),{value}); }}\n'.replace('))),',')),')
        replace('precise float g(ivec2 s)',helpers+'\nprecise float g(ivec2 s)')
    if product:
        assert not packed, 'Product config binding is independent of packed experiment'
        replace('writeonly buffer Output','buffer Output')
        replace('void main() {',PRODUCT+'\nvoid main() {\n    if(p.stage==6) { productOutput();return; }')
    if hpf:
        assert direct and not packed
        if not product:replace('writeonly buffer Output','buffer Output')
        replace('precise float response(ivec2 s,ivec2 d)', 'precise float uncachedResponse(ivec2 s,ivec2 d)')
        marker='precise float direction(ivec2 s,ivec2 a,ivec2 b)'
        replace(marker,'''precise float response(ivec2 s,ivec2 d) {
    ivec2 position=s+readOrigin;
    int component=d.x==0?0:d.y==0?1:d.x==d.y?2:3;
    return outputRgb[position.y*p.width+position.x][component];
}
'''+marker)
        replace('void main() {','''void main() {
    if(p.stage==7) {
        ivec2 s=ivec2(gl_GlobalInvocationID.xy);
        if(s.x>=p.width || s.y>=p.height) return;
        readOrigin=ivec2(0);
        precise vec4 responseValues=vec4(0.0);
        if(s.x>=3 && s.y>=3 && s.x<p.width-3 && s.y<p.height-3) {
            responseValues.x=uncachedResponse(s,S);responseValues.y=uncachedResponse(s,E);
            if((s.x&1)!=0) { responseValues.z=uncachedResponse(s,SE);responseValues.w=uncachedResponse(s,SW); }
        }
        outputRgb[s.y*p.width+s.x]=responseValues;return;
    }
''')
    if specialize:
        replace('const float eps=','layout(constant_id=9) const int algorithmPass=-1;\nconst float eps=')
        source=source.replace('p.stage','(algorithmPass<0?p.stage:algorithmPass)')
    return source

def build(out):
    out.mkdir(parents=True,exist_ok=True)
    records={}
    for name,tiled,packed,cache in [('scalar',False,False,False),('packed',False,True,False),('tiled',True,False,False),('tiled-packed',True,True,False),('cached',False,False,True),('cached-packed',False,True,True),('tiled-cached',True,False,True),('direct',False,False,False),('direct-packed',False,True,False),('direct-specialized',False,False,False),('direct-packed-specialized',False,True,False)]:
        for capture in [False,True] if tiled else [False]:
            label=name+('-capture' if capture else '')
            path=out/(label+'.comp');path.write_text(generate(tiled,packed,capture,cache,direct=name.startswith('direct'),specialize='specialized' in name))
            binary=path.with_suffix('.spv')
            subprocess.run(['glslangValidator','-V','--target-env','vulkan1.1',str(path),'-o',str(binary)],check=True)
            subprocess.run(['spirv-val','--target-env','vulkan1.1',str(binary)],check=True)
            records[label]={'source_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'spirv_sha256':hashlib.sha256(binary.read_bytes()).hexdigest()}
    (out/'variants.json').write_text(json.dumps(records,indent=2)+'\n')
    return records

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('out',type=Path);a=p.parse_args();build(a.out.resolve())
