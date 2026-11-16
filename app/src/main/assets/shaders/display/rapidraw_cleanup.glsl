// SPDX-License-Identifier: AGPL-3.0-only
// GPU port of RapidRAW image_processing.rs @ 1a39ae4e76d825c5abf0d4bafee798f99e0a0b63.
// Copyright Timon Käch / RapidRAW contributors; GPU port RawLens contributors, 2026.
precision highp float;
precision highp int;
#ifndef RAPIDRAW_OUTPUT_FUSED
layout(local_size_x=8,local_size_y=8) in;
#endif
layout(binding=0) uniform highp sampler2D u_source;
layout(binding=1) uniform highp sampler2D u_plane;
layout(binding=2) uniform highp sampler2D u_aux;
layout(binding=3) uniform highp sampler2D u_ref;
#ifndef RAPIDRAW_OUTPUT_FUSED
uniform ivec2 u_size;
#endif
uniform ivec2 u_full_size;
uniform ivec2 u_dispatch_offset;
uniform vec2 u_noise;
uniform float u_core;
uniform float u_eps;
uniform int u_kind;
uniform int u_offset;
uniform int u_count;
uniform int u_sample_width;
#if MODE == 0 || MODE == 1 || (MODE == 8 && !defined(RAPIDRAW_OUTPUT_FUSED))
layout(binding=0,rgba16f) writeonly uniform highp image2D out_image;
#elif MODE == 2
layout(binding=0,rgba32f) writeonly uniform highp image2D out_image;
#elif MODE == 3 || MODE == 4 || MODE == 10
layout(binding=0,rgba16f) writeonly uniform highp image2D out_image;
#elif MODE == 5
layout(binding=0,rgba16f) writeonly uniform highp image2D out_image;
#endif
#if MODE == 8
shared vec4 tileRows[96];
#endif
#if MODE == 7 || MODE == 9
layout(std430,binding=0) buffer Samples { float samples[]; };
#endif
const float FLOOR=0.002;
const float AMOUNT=0.5;
const mat3 TO_2020=mat3(
    1.025877552449,-.002232441770,-.005013950857,
    -.020020686312,1.004568990995,-.025282661381,
    -.005775003430,-.002349522759,1.030082295555);
const mat3 TO_SRGB=mat3(
    1.6604910021,-.1245504745,-.0181507634,
    -.5876411388,1.1328998971,-.1005788980,
    -.0728498633,-.0083494226,1.1187296614);
const mat3 TO_RGB=TO_SRGB*TO_2020;
vec3 rgbAt(ivec2 p) { return TO_RGB*texelFetch(u_source,clamp(p,ivec2(0),u_full_size-1),0).rgb; }
float luma(vec3 p) { return .2126*p.r+.7152*p.g+.0722*p.b; }
float qAt(ivec2 p) { return sqrt(max(luma(rgbAt(p)),0.0)+FLOOR); }
vec3 stabilize(vec3 p) {
    float q=sqrt(max(luma(p),0.0)+FLOOR);
    return vec3(q,sqrt(max(p.r,0.0)+FLOOR)-q,sqrt(max(p.b,0.0)+FLOOR)-q);
}
vec3 planeAt(ivec2 p) {return texelFetch(u_plane,clamp(p,ivec2(0),textureSize(u_plane,0)-1),0).rgb;}
vec2 tapsWeight(int v) {return v%2==0?vec2(.25,.75):vec2(.75,.25);}
ivec2 tapsIndex(int v,int n) {
    int i=min(v/2,n-1);
    return v%2==0?ivec2(max(i-1,0),i):ivec2(i,min(i+1,n-1));
}
struct Up {vec2 color;vec2 lo;vec2 hi;float range;};
Up upsample(ivec2 p,float qc) {
    ivec2 size=textureSize(u_plane,0);
    ivec2 xs=tapsIndex(p.x,size.x),ys=tapsIndex(p.y,size.y);
    vec2 wx=tapsWeight(p.x),wy=tapsWeight(p.y);
    Up outp;outp.color=vec2(0);outp.lo=vec2(3.4e38);outp.hi=vec2(-3.4e38);
    float sd=0.0,sdd=0.0,qmin=3.4e38,qmax=-3.4e38;vec2 sdc=vec2(0);
    for(int y=0;y<2;y++)for(int x=0;x<2;x++){
        ivec2 at=ivec2(xs[x],ys[y]);
        float q=texelFetch(u_plane,at,0).r;
        vec2 c=texelFetch(u_aux,at,0).rg;
        float w=wx[x]*wy[y],d=q-qc,wd=w*d;
        outp.color+=w*c;sd+=wd;sdd+=wd*d;sdc+=wd*c;
        qmin=min(qmin,q);qmax=max(qmax,q);outp.lo=min(outp.lo,c);outp.hi=max(outp.hi,c);
    }
    float var=max(sdd-sd*sd,0.0)+u_eps;
    outp.color-=(sdc-outp.color*sd)/var*sd;outp.range=qmax-qmin;
    return outp;
}
float sparseBlur(ivec2 p) {
    float v[5];
    for(int y=-2;y<=2;y++){
        float a=qAt(p+ivec2(-2,y)),b=qAt(p+ivec2(-1,y)),c=qAt(p+ivec2(0,y));
        float d=qAt(p+ivec2(1,y)),e=qAt(p+ivec2(2,y));
        v[y+2]=(a+e+4.0*(b+d)+6.0*c)*(1.0/16.0);
    }
    return (v[0]+v[4]+4.0*(v[1]+v[3])+6.0*v[2])*(1.0/16.0);
}
#if MODE == 8
void prepareCleanupTile(){
    // Cache horizontal blur, 3-tap extrema and center luma for 12 rows.
    // All lanes reach the barrier, including those past an odd image edge.
    int lane=int(gl_LocalInvocationIndex);
    ivec2 origin=ivec2(gl_WorkGroupID.xy)*8+u_dispatch_offset;
    for(int i=lane;i<96;i+=64){
        ivec2 at=origin+ivec2(i%8,i/8-2);
        float a=qAt(at+ivec2(-2,0)),b=qAt(at+ivec2(-1,0)),c=qAt(at);
        float d=qAt(at+ivec2(1,0)),e=qAt(at+ivec2(2,0));
        tileRows[i]=vec4((a+e+4.0*(b+d)+6.0*c)*(1.0/16.0),min(b,min(c,d)),max(b,max(c,d)),c);
    }
    barrier();
}
vec3 cleanupPixel(ivec2 p){
    vec3 rgb=rgbAt(p);float l=luma(rgb);vec3 s=stabilize(rgb);
    Up up=upsample(p,s.r);vec2 er=s.gb-up.color;
    float edge=up.range*.5/sqrt(u_eps);
    float keep=clamp(length(er)/(u_core+4.0*up.range)-1.0,0.0,1.0)/(1.0+edge*edge);
    vec2 nr=clamp(up.color+er*keep,up.lo,up.hi),v=max(s.rr+nr,vec2(0));
    vec2 drb=v*v-vec2(FLOOR+l);
    float dg=-(.2126*drb.r+.0722*drb.g)*(1.0/.7152);
    vec3 delta=vec3(drb.r,dg,drb.g);float m=min(delta.r,min(delta.g,delta.b));
    if(l>0.0)rgb=max(vec3(l)+delta*(l+m<0.0?-l/m:1.0),vec3(0));
    // Detail uses original sqrt-luma: chroma reconstruction preserves luminance.
    ivec2 local=ivec2(gl_LocalInvocationID.xy);
    int bi=local.y*8+local.x;
    vec4 row1=tileRows[bi+8],row2=tileRows[bi+16],row3=tileRows[bi+24];
    float blur=(tileRows[bi].x+tileRows[bi+32].x+4.0*(row1.x+row3.x)+6.0*row2.x)*(1.0/16.0);
    float qc=row2.w,d=qc-blur,threshold=max(2.25*u_noise.y*u_noise.y,1e-12);
    d=d*d*d/(d*d+threshold);d=d/(1.0+abs(d)*.3/sqrt(threshold));
    float lo=min(row1.y,min(row2.y,row3.y)),hi=max(row1.z,max(row2.z,row3.z));
    float dd=1.575*d,range=hi-lo,ur=hi-qc+range*.15+.004,dr=qc-lo+range*.05+.002;
    float bright=max(dd,0.0),dark=max(-dd,0.0)*.7;
    float qn=qc+bright*ur/(ur+bright)-dark*dr/(dr+dark),oldL=qc*qc-FLOOR,newL=max(qn*qn-FLOOR,0.0);
    if(oldL>1e-6){
        float peak=max(rgb.r,max(rgb.g,rgb.b)),ratio=newL/oldL;
        if(ratio>1.0&&peak*ratio>1.0)ratio=max(1.0/peak,1.0);
        rgb*=ratio;
    }else rgb=max(rgb+vec3(newL-oldL),vec3(0));
    return inverse(TO_RGB)*rgb;
}
#endif
#ifndef RAPIDRAW_OUTPUT_FUSED
void main(){
    ivec2 p=ivec2(gl_GlobalInvocationID.xy)+u_dispatch_offset;
    #if MODE == 8
    prepareCleanupTile();
    #endif
    if(any(greaterThanEqual(p,u_size)))return;
#if MODE == 0
    // Half-resolution stabilized RGB. Average RGB before taking square roots.
    vec3 s=vec3(0);
    for(int y=0;y<2;y++)for(int x=0;x<2;x++)s+=rgbAt(p*2+ivec2(x,y))*.25;
    imageStore(out_image,p,vec4(stabilize(s),0));
#elif MODE == 1
    vec3 s=vec3(0);
    for(int y=0;y<2;y++)for(int x=0;x<2;x++)s+=planeAt(p*2+ivec2(x,y))*.25;
    imageStore(out_image,p,vec4(s,0));
#elif MODE == 2
    // Padded gradient reliability and central tolerance widening.
    ivec2 at=p-2;vec2 v=vec2(1);
    if(all(greaterThan(p,ivec2(0)))&&all(lessThan(p,u_size-1))){
        float g=.5*max(abs(planeAt(at+ivec2(1,0)).r-planeAt(at-ivec2(1,0)).r),
                       abs(planeAt(at+ivec2(0,1)).r-planeAt(at-ivec2(0,1)).r));
        float t=g/(2.5*u_noise.x);
        v=vec2(1.0/(1.0+t*t),1.0/((1.0+2.0*t)*(1.0+2.0*t)));
    }
    // v is in [0,1], so bit 30 is unused in its two half encodings. Force it
    // while storing as float to prevent tiny edge weights flushing as fp32 denormals.
    imageStore(out_image,p,vec4(planeAt(at),uintBitsToFloat(packHalf2x16(v)|0x40000000u)));
#elif MODE == 3 || MODE == 10
    // 5x5 robust local linear regression, with optional quarter-scale reference.
    vec4 center=texelFetch(u_aux,p+2,0);
    float qc=center.r,lt=2.5*u_noise.x,ct=3.5*u_noise.y;
    float kq=1.0/(lt*lt),kc=1.0/(ct*ct),wc=unpackHalf2x16(floatBitsToUint(center.a)&0xbfffffffu).y;
    vec2 ref=vec2(0);float rw=0.0;
    #if MODE == 10
    ref=texelFetch(u_ref,p,0).rg;
    #else
    {
        for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){
            ivec2 at=p+ivec2(x,y);vec4 packed=texelFetch(u_aux,at+2,0);vec3 s=packed.rgb;float d=s.r-qc;
            float t=max(1.0-d*d*kq*wc,0.0);
            float w=exp(-float(x*x+y*y)/8.0)*unpackHalf2x16(floatBitsToUint(packed.a)&0xbfffffffu).x*t*t;
            ref+=w*s.gb;rw+=w;
        }
        ref*=1.0/rw;
    }
    #endif
    float sw=0.0,sd=0.0,sdd=0.0;vec2 sc=vec2(0),sdc=vec2(0);
    for(int y=-2;y<=2;y++)for(int x=-2;x<=2;x++){
        ivec2 at=p+ivec2(x,y);vec4 packed=texelFetch(u_aux,at+2,0);vec3 s=packed.rgb;float d=s.r-qc;vec2 e=s.gb-ref;
        float t=max(1.0-(d*d*kq+dot(e,e)*kc)*wc,0.0);
        float w=exp(-float(x*x+y*y)/8.0)*unpackHalf2x16(floatBitsToUint(packed.a)&0xbfffffffu).x*t*t,wd=w*d;
        sw+=w;sd+=wd;sdd+=wd*d;sc+=w*s.gb;sdc+=wd*s.gb;
    }
    vec2 c=ref;
    if(sw>1e-6){float inv=1.0/sw,md=sd*inv;vec2 mc=sc*inv;
        float var=max(sdd*inv-md*md,0.0)+lt*lt;c=mc-(sdc*inv-mc*md)/var*md;}
    imageStore(out_image,p,vec4(c,0,0));
#elif MODE == 4
    float qc=texelFetch(u_ref,p,0).r;
    imageStore(out_image,p,vec4(upsample(p,qc).color,0,0));
#elif MODE == 5
    float lo=3.4e38,hi=-3.4e38;
    for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){
        float q=planeAt(p+ivec2(x,y)).r;lo=min(lo,q);hi=max(hi,q);
    }
    vec2 ref=texelFetch(u_ref,p,0).rg,er=texelFetch(u_aux,p,0).rg-ref;
    float keep=clamp(length(er)/(u_core+4.0*(hi-lo))-1.0,0.0,1.0);
    imageStore(out_image,p,vec4(ref+er*keep,0,0));
#elif MODE == 7
    int i=p.y*u_size.x+p.x;if(i>=u_count)return;
    if(u_kind<=1){
        // Samples of the padded plane, in exactly upstream's row/channel order.
        int c=i%3,j=i/3;ivec2 at=ivec2((j%u_sample_width)*7,(j/u_sample_width)*8)-2;
        vec3 a=planeAt(at),b=planeAt(at+ivec2(4,0));samples[u_offset+i]=abs(b[c]-a[c]);
    }else if(u_kind==2){
        int c=i%2,j=i/2;ivec2 at=ivec2((j%u_sample_width)*7,(j/u_sample_width)*8);
        vec3 a=stabilize(rgbAt(at)),b=stabilize(rgbAt(at+ivec2(4,0)));
        samples[u_offset+i]=abs(b[c+1]-a[c+1]);
    }else{
        ivec2 at=ivec2((i%u_sample_width)*5,(i/u_sample_width)*16);
        samples[u_offset+i]=abs(qAt(at)-sparseBlur(at));
    }
#elif MODE == 9
    int i=p.y*u_size.x+p.x;if(i>=u_count)return;
    int j=i*7;ivec2 at=ivec2(j%u_full_size.x,j/u_full_size.x);
    samples[i]=length(texelFetch(u_plane,at,0).rg-texelFetch(u_ref,at,0).rg);
#elif MODE == 8
    imageStore(out_image,p,vec4(cleanupPixel(p),texelFetch(u_source,p,0).a));
#endif
}

#endif
