#version 450
// darktable basic.cl, revision 891e46cd57b64d67dbde183d5d3c0e430edd11d6.
// Copyright darktable developers / RawLens contributors. GPL-3.0-or-later.
layout(local_size_x=8, local_size_y=8) in;
layout(binding=0, r32f) uniform readonly image2D src;
layout(binding=1, r32ui) uniform uimage2D masks;
layout(binding=3, rgba32f) uniform image2D rows;
layout(binding=4, r32f) uniform writeonly image2D dst;
layout(binding=6) uniform sampler2D gains;
layout(binding=7) uniform sampler2D coordinates;
layout(binding=8) uniform usampler2D eligible;
layout(std140,binding=5) uniform Params {
    ivec4 sizeMode; // width, height, mode, explicit HDR clipping mask enabled
    ivec4 cells;
    vec4 clips;
    vec4 correction;
    vec4 chroma;
    ivec4 origin; // sensor origin x/y, gain-map columns/rows (0 means identity)
    ivec4 roi; // output x/y/width/height
    ivec4 sensorArea; // left/top, width/height
} p;
int color(int x,int y) { return p.cells[2*(y&1)+(x&1)]; }
// Correct a hardware division estimate using the exact product at float midpoints.
int divisionMidpoint(uint lower,float x,float y) {
    uint xb=floatBitsToUint(x), yb=floatBitsToUint(y);
    uint rm=(lower&0x7fffffu)|0x800000u;
    uint ym=(yb&0x7fffffu)|0x800000u;
    uint xm=(xb&0x7fffffu)|0x800000u;
    int re=int(lower>>23)-150, ye=int(yb>>23)-150, xe=int(xb>>23)-150;
    uint hi,lo; umulExtended(2u*rm+1u,ym,hi,lo);
    int shift=xe-(re-1+ye);
    uvec3 rhs=uvec3(0); int word=shift/32; int bits=shift%32;
    rhs[word]=xm<<bits;
    if(bits!=0 && word<2) rhs[word+1]=xm>>(32-bits);
    uvec3 lhs=uvec3(lo,hi,0);
    for(int j=2;j>=0;--j) { if(lhs[j]<rhs[j]) return -1; if(lhs[j]>rhs[j]) return 1; }
    return 0;
}
float divideRounded(float x,float y) {
    if(y==1.0) return x;
    float estimate=x/y;
    // Sensor samples and lens gains are normal finite floats in the corrected path.
    if(x<=0.0 || estimate<1.17549435e-38 || isinf(estimate)) return estimate;
    uint r=floatBitsToUint(estimate);
    for(int i=0;i<4;++i) {
        int high=divisionMidpoint(r,x,y);
        if(high<0 || (high==0 && (r&1u)!=0u)) { ++r; continue; }
        int low=divisionMidpoint(r-1u,x,y);
        if(low>0 || (low==0 && (r&1u)!=0u)) { --r; continue; }
        break;
    }
    return uintBitsToFloat(r);
}
// Undo lens shading while evaluating opposed in the upstream sensor-normalized domain.
// The final pass restores shading only for pixels whose reconstructed value changed.
float gainAt(ivec2 q) {
    if(p.origin.z==0) return 1.0;
    ivec2 sensor=q+p.origin.xy;
    int c=color(q.x,q.y);
    int ch=c==0 ? 0 : c==2 ? 3 : ((sensor.y&1)==0 ? 1 : 2);
    // CPU computes each axis once; exact shared coordinates replace per-tap divides.
    precise vec2 m=vec2(texelFetch(coordinates,ivec2(q.x,0),0).x,
                        texelFetch(coordinates,ivec2(q.y,1),0).x);
    ivec2 a=ivec2(floor(m)); ivec2 b=min(a+1,p.origin.zw-1);
    vec2 t=m-vec2(a);
    precise float top=texelFetch(gains,a,0)[ch]*(1.0-t.x)+texelFetch(gains,ivec2(b.x,a.y),0)[ch]*t.x;
    precise float bottom=texelFetch(gains,ivec2(a.x,b.y),0)[ch]*(1.0-t.x)+texelFetch(gains,b,0)[ch]*t.x;
    precise float gain=top*(1.0-t.y)+bottom*t.y;
    return gain;
}
float rawAt(ivec2 q) { return divideRounded(imageLoad(src,q).x,gainAt(q)); }
// Round cube root to the nearest binary32 value using exact integer midpoint cubes.
// pow supplies only an initial estimate. The rounding decision is independent of
// the GPU transcendental approximation; all products fit in 96 bits.
uvec3 cube25(uint m) {
    uint hi,lo,a,b,c,d;
    umulExtended(m,m,hi,lo);
    umulExtended(lo,m,a,b);
    umulExtended(hi,m,c,d);
    uint mid=a+d;
    return uvec3(b,mid,c+uint(mid<a));
}
int midpointCompare(uint lower,float x) {
    uint xb=floatBitsToUint(x);
    uint m=(lower&0x7fffffu)|0x800000u;
    int e=int(lower>>23)-150;
    uint xm=xb&0x7fffffu;
    int xe=-149;
    if((xb>>23)!=0u) { xm|=0x800000u; xe=int(xb>>23)-150; }
    int shift=xe-3*(e-1);
    uvec3 rhs=uvec3(0);
    // x and the midpoint cube have the same exponent basis.
    int word=shift/32; int bits=shift%32;
    rhs[word]=xm<<bits;
    if(bits!=0 && word<2) rhs[word+1]=xm>>(32-bits);
    uvec3 lhs=cube25(2u*m+1u);
    for(int j=2;j>=0;--j) { if(lhs[j]<rhs[j]) return -1; if(lhs[j]>rhs[j]) return 1; }
    return 0;
}
float cubeRoot(float x) {
    if(x==0.0) return 0.0;
    uint r=floatBitsToUint(pow(x,1.0/3.0));
    for(int i=0;i<128;++i) {
        int high=midpointCompare(r,x);
        if(high<0 || (high==0 && (r&1u)!=0u)) { ++r; continue; }
        int low=midpointCompare(r-1u,x);
        if(low>0 || (low==0 && (r&1u)!=0u)) { --r; continue; }
        break;
    }
    return uintBitsToFloat(r);
}
float refavg(int x,int y) {
    precise vec3 mean=vec3(0); vec3 cnt=vec3(0);
    // Preserve upstream's exclusive maxrow-1/maxcol-1 limits, including borders.
    for(int yy=max(0,y-1); yy<min(p.sizeMode.y-1,y+2); ++yy)
        for(int xx=max(0,x-1); xx<min(p.sizeMode.x-1,x+2); ++xx) {
            int c=color(xx,yy); mean[c]+=max(0.0,rawAt(ivec2(xx,yy))); cnt[c]+=1.0;
        }
    for(int c=0;c<3;++c) mean[c]=cnt[c]>0.0 ? cubeRoot(divideRounded(p.correction[c]*mean[c],cnt[c])) : 0.0;
    precise vec3 opp=0.5*vec3(mean.y+mean.z,mean.x+mean.z,mean.x+mean.y);
    precise float v=opp[color(x,y)]; return (v*v)*v;
}
bool clippedAt(ivec2 q,float value) {
    if(p.sizeMode.w!=0) {
        uint word=texelFetch(eligible,ivec2(q.x/32,q.y),0).x;
        return (word & (1u<<uint(q.x&31)))!=0u;
    }
    return value>=p.clips[color(q.x,q.y)];
}
void main() {
    ivec2 q=ivec2(gl_GlobalInvocationID.xy); ivec2 wh=p.sizeMode.xy; ivec2 mw=wh/3;
    int mode=p.sizeMode.z;
    if(mode==0) {
        if(any(greaterThanEqual(q,mw))) return;
        uint m=0u;
        for(int y=3*q.y;y<3*q.y+3;++y) for(int x=3*q.x;x<3*q.x+3;++x) {
            int c=color(x,y); if(clippedAt(ivec2(x,y),rawAt(ivec2(x,y)))) m|=1u<<c;
        }
        imageStore(masks,q,uvec4(m));
    } else if(mode==1) {
        if(any(greaterThanEqual(q,mw))) return;
        uint m=imageLoad(masks,q).x;
        if(q.x>=3 && q.y>=3 && q.x<mw.x-3 && q.y<mw.y-3)
            for(int y=-3;y<=3;++y) for(int x=-3;x<=3;++x)
                if(abs(x)!=3 || abs(y)!=3) m|=imageLoad(masks,q+ivec2(x,y)).x;
        imageStore(masks,q+ivec2(mw.x,0),uvec4(m));
    } else if(mode==2) {
        // One invocation per row, exactly the upstream sequential column reduction.
        q.y=q.y*8+q.x;
        if(q.y>=wh.y) return;
        precise vec3 sums=vec3(0); vec3 counts=vec3(0); float clipped=0; int minX=wh.x, maxX=-1;
        for(int x=0;x<wh.x;++x) {
            int c=color(x,q.y); float v=rawAt(ivec2(x,q.y));
            // Upstream mask is planar with linear row/3*mwidth+col/3 addressing.
            int ix=(q.y/3)*mw.x+x/3;
            uint m=ix<mw.x*mw.y ? imageLoad(masks,ivec2(ix%mw.x+mw.x,ix/mw.x)).x : 0u;
            bool nearClip=(m&(1u<<c))!=0u;
            if(nearClip && !clippedAt(ivec2(x,q.y),v) && v>0.2*p.clips[c] && v<p.clips[c]) {
                sums[c]+=v-refavg(x,q.y); counts[c]+=1.0;
            }
            if(nearClip) clipped+=1.0;
            if(clippedAt(ivec2(x,q.y),v)) { minX=min(minX,x); maxX=max(maxX,x); }
        }
        imageStore(rows,ivec2(0,q.y),vec4(sums,clipped));
        imageStore(rows,ivec2(1,q.y),vec4(counts,0));
        imageStore(rows,ivec2(2,q.y),vec4(minX,maxX,0,0));
    } else {
        if(any(greaterThanEqual(q,p.roi.zw))) return;
        ivec2 outputPos=q;
        q+=p.roi.xy;
        float original=imageLoad(src,q).x;
        float gain=gainAt(q); float v=divideRounded(original,gain); int c=color(q.x,q.y);
        float result=original;
        if(clippedAt(q,v)) {
            float reconstructed=refavg(q.x,q.y)+p.chroma[c];
            if(reconstructed>v) result=max(original,reconstructed*gain);
        }
        imageStore(dst,outputPos,vec4(result));
    }
}
