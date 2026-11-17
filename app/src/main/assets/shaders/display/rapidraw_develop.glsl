// SPDX-License-Identifier: AGPL-3.0-only
// GLES 3.1 port of RapidRAW v1.7.0 (Furka engine) src-tauri/src/shaders/develop.wgsl:
// the RAW preprocessing denoise (nr_*), sharpen (sh_*) and GPU median (md_*) kernels
// plus the per-pixel reconstruction of out_band. Highlight reconstruction (hl_*, pp_*)
// is not ported: RawLens reconstructs highlights during its own demosaic.
// Copyright Timon Käch / RapidRAW contributors; GLSL port RawLens contributors, 2026.
//
// Differences from upstream, all layout only (same arithmetic, same sample order):
//  - planes are textures instead of one f32 storage buffer;
//  - the input is RawLens ACEScg and is filtered in linear Rec.709/sRGB, as upstream;
//  - out_band is fused into the JPEG output shader (RAPIDRAW_OUTPUT_FUSED), so no
//    full-resolution scratch image exists; the sharpen blur is cached in shared memory.
precision highp float;
precision highp int;
#ifndef RAPIDRAW_OUTPUT_FUSED
#if MODE == 7 || MODE == 9
layout(local_size_x=256) in;
#elif MODE == 10
layout(local_size_x=256) in;
#else
layout(local_size_x=8,local_size_y=8) in;
#endif
#endif

// Modes: 0 nr_half, 1 nr_quarter, 2 nr_relwiden, 3 nr_filter, 4 nr_upsample, 5 nr_core,
//        7 md_sample, 9 md_hist, 10 md_pick, 8 standalone out_band (tests only).
layout(binding=0) uniform highp sampler2D u_source; // full-res ACEScg
layout(binding=1) uniform highp sampler2D u_a;
layout(binding=2) uniform highp sampler2D u_b;
layout(binding=3) uniform highp sampler2D u_c;
uniform ivec2 u_full_size;
uniform ivec2 u_half;        // unpadded half size
uniform ivec2 u_quarter;     // unpadded quarter size
uniform ivec2 u_dispatch_offset;
uniform float u_amount;      // RapidRAW raw_preprocessing_color_nr, clamped to [0,1]
uniform float u_sharpen;     // RapidRAW raw_preprocessing_sharpening
#ifndef RAPIDRAW_OUTPUT_FUSED
uniform ivec2 u_size;
uniform int u_scale;         // 0 half, 1 quarter
uniform int u_use_ref;       // nr_filter T.y
uniform int u_kind;          // md_sample kind
uniform int u_pass;          // md_hist / md_pick pass
uniform int u_count;         // sample count n
uniform int u_slot;          // median slot
#endif

#if !defined(RAPIDRAW_OUTPUT_FUSED) && (MODE <= 5 || MODE == 8)
#if MODE == 0 || MODE == 1
layout(binding=0,rgba32f) writeonly uniform highp image2D out_image;
#else
layout(binding=0,rgba16f) writeonly uniform highp image2D out_image;
#endif
#endif

const float FLOOR=0.002;
const int PAD=2;
const uint RADIX=0u;
const uint PREFIX=2048u;
const uint RANK=2049u;
const int MED_HALF_Q=0, MED_HALF_C=1, MED_QUARTER_Q=2, MED_QUARTER_C=3,
          MED_CORE=4, MED_FULL_C=5, MED_SHARPEN=6;

layout(std430,binding=2) buffer Medians { float medians[8]; };
#if !defined(RAPIDRAW_OUTPUT_FUSED) && (MODE == 7 || MODE == 9 || MODE == 10)
layout(std430,binding=0) buffer Samples { float samples[]; };
layout(std430,binding=1) buffer Stats { uint stats[]; };
#endif

// ACEScg (D60) -> Rec.2020 (D65) -> linear sRGB, the exact matrices of the output shader.
const mat3 TO_2020=mat3(
    1.025877552449,-.002232441770,-.005013950857,
    -.020020686312,1.004568990995,-.025282661381,
    -.005775003430,-.002349522759,1.030082295555);
const mat3 TO_SRGB=mat3(
    1.6604910021,-.1245504745,-.0181507634,
    -.5876411388,1.1328998971,-.1005788980,
    -.0728498633,-.0083494226,1.1187296614);
const mat3 TO_RGB=TO_SRGB*TO_2020;

float luma(vec3 p) { return .2126*p.r+.7152*p.g+.0722*p.b; }
// developed(): upstream clamps coordinates in observed().
vec3 developed(ivec2 p) { return TO_RGB*texelFetch(u_source,clamp(p,ivec2(0),u_full_size-1),0).rgb; }
float lumaAt(ivec2 p) { return sqrt(max(luma(developed(p)),0.0)+FLOOR); }
vec3 stabilize(vec3 rgb) {
    float s=sqrt(max(luma(rgb),0.0)+FLOOR);
    return vec3(s,sqrt(max(rgb.r,0.0)+FLOOR)-s,sqrt(max(rgb.b,0.0)+FLOOR)-s);
}
float noiseOf(int m) { return max(medians[m]*1.4826/sqrt(2.0),1e-3); }
float upsampleEps() { float t=(1.5+2.0*u_amount)*noiseOf(MED_HALF_Q); return 16.0*t*t; }

// taps(): two bilinear taps of a 2x upsample with clamped edges.
vec4 taps(int v,int n) {
    int i=min(v/2,n-1);
    if(v%2==0)return vec4(float(max(i-1,0)),.25,float(i),.75);
    return vec4(float(i),.75,float(min(i+1,n-1)),.25);
}
struct Upsampled { vec2 c; float qRange; vec2 lo; vec2 hi; };
// upsample(): guided 2x upsample. q is the coarse PADDED stabilized plane, chroma unpadded.
Upsampled upsample(ivec2 p,float qc,highp sampler2D q,highp sampler2D chroma,ivec2 coarse,float eps) {
    Upsampled u; u.c=vec2(0); u.lo=vec2(3.4e38); u.hi=vec2(-3.4e38);
    float qMin=3.4e38,qMax=-3.4e38,sd=0.0,sdd=0.0; vec2 sdc=vec2(0);
    vec4 ty=taps(p.y,coarse.y),tx=taps(p.x,coarse.x);
    for(int row=0;row<2;row++){
        int ly=int(row==0?ty.x:ty.z); float wy=row==0?ty.y:ty.w;
        for(int side=0;side<2;side++){
            int lx=int(side==0?tx.x:tx.z); float wx=side==0?tx.y:tx.w;
            float gq=texelFetch(q,ivec2(lx,ly)+PAD,0).r;
            vec2 g=texelFetch(chroma,ivec2(lx,ly),0).rg;
            float gw=wy*wx,d=gq-qc,wd=gw*d;
            u.c+=gw*g; sd+=wd; sdd+=wd*d; sdc+=wd*g;
            qMin=min(qMin,gq); qMax=max(qMax,gq); u.lo=min(u.lo,g); u.hi=max(u.hi,g);
        }
    }
    float varD=max(sdd-sd*sd,0.0)+eps;
    u.c-=(sdc-u.c*sd)/varD*sd;
    u.qRange=qMax-qMin;
    return u;
}
float blurAt(ivec2 p) {
    float v[5];
    for(int k=0;k<5;k++){
        int cx=p.x+k-2;
        v[k]=(lumaAt(ivec2(cx,p.y-2))+lumaAt(ivec2(cx,p.y+2))+4.0*(lumaAt(ivec2(cx,p.y-1))+lumaAt(ivec2(cx,p.y+1)))
            +6.0*lumaAt(ivec2(cx,p.y)))*(1.0/16.0);
    }
    return (v[0]+v[4]+4.0*(v[1]+v[3])+6.0*v[2])*(1.0/16.0);
}

#if MODE == 8
// out_band. Fused callers bind u_a = half stabilized (padded), u_b = cleaned half chroma.
// Shared tile: rows -2..9 of the 8x8 group: horizontal 5-tap blur, 3-tap min, max, centre.
shared vec4 tileRows[96];
void prepareCleanupTile() {
    // All 64 lanes reach the barrier, including those past the image edge.
    int lane=int(gl_LocalInvocationIndex);
    ivec2 origin=ivec2(gl_WorkGroupID.xy)*8+u_dispatch_offset;
    for(int i=lane;i<96;i+=64){
        ivec2 at=origin+ivec2(i%8,i/8-2);
        float a=lumaAt(at+ivec2(-2,0)),b=lumaAt(at+ivec2(-1,0)),c=lumaAt(at);
        float d=lumaAt(at+ivec2(1,0)),e=lumaAt(at+ivec2(2,0));
        tileRows[i]=vec4((a+e+4.0*(b+d)+6.0*c)*(1.0/16.0),min(b,min(c,d)),max(b,max(c,d)),c);
    }
    barrier();
}
vec3 cleanupPixel(ivec2 p) {
    vec3 rgb=developed(p);
    float qc=sqrt(max(luma(rgb),0.0)+FLOOR);
    if(u_amount>0.0){
        float a=u_amount,lk=luma(rgb);
        vec2 c=vec2(sqrt(max(rgb.r,0.0)+FLOOR),sqrt(max(rgb.b,0.0)+FLOOR))-qc;
        float eps=upsampleEps();
        Upsampled up=upsample(p,qc,u_a,u_b,u_half,eps);
        float edge=up.qRange*(0.5/sqrt(eps));
        float core=(2.0+2.0*a)*noiseOf(MED_FULL_C),tex=4.0*(0.5+a);
        vec2 e=c-up.c;
        float keep=clamp(length(e)/(core+tex*up.qRange)-1.0,0.0,1.0)/(1.0+edge*edge);
        vec2 n=min(max(up.c+e*keep,up.lo),up.hi);
        vec2 v=max(qc+n,vec2(0));
        vec2 drb=v*v-FLOOR-lk;
        float dg=-(.2126*drb.x+.0722*drb.y)*(1.0/.7152);
        float m=min(min(drb.x,dg),drb.y);
        float s=lk+m<0.0?-lk/m:1.0;
        if(lk>0.0)rgb=max(vec3(lk+drb.x*s,lk+dg*s,lk+drb.y*s),vec3(0));
    }
    if(u_sharpen>0.0){
        ivec2 local=ivec2(gl_LocalInvocationID.xy);
        int bi=local.y*8+local.x;
        vec4 r1=tileRows[bi+8],r2=tileRows[bi+16],r3=tileRows[bi+24];
        float blur=(tileRows[bi].x+tileRows[bi+32].x+4.0*(r1.x+r3.x)+6.0*r2.x)*(1.0/16.0);
        float n=medians[MED_SHARPEN]*1.4826;
        float threshold=max((1.5*n)*(1.5*n),1e-12);
        float taper=0.3/sqrt(threshold);
        float d=qc-blur;
        d=d*d*d/(d*d+threshold);
        d=d/(1.0+abs(d)*taper);
        float lo=min(r1.y,min(r2.y,r3.y)),hi=max(r1.z,max(r2.z,r3.z));
        float delta=4.5*u_sharpen*d,range=hi-lo;
        float upRoom=hi-qc+range*.15+.004,downRoom=qc-lo+range*.05+.002;
        float upD=max(delta,0.0),downD=max(-delta,0.0)*.7;
        float qn=qc+upD*upRoom/(upRoom+upD)-downD*downRoom/(downRoom+downD);
        float l=qc*qc-FLOOR,ln=max(qn*qn-FLOOR,0.0);
        if(l>1e-6){
            float peak=max(rgb.r,max(rgb.g,rgb.b)),ratio=ln/l;
            if(ratio>1.0&&peak*ratio>1.0)ratio=max(1.0/peak,1.0);
            rgb*=ratio;
        }else rgb=max(rgb+vec3(ln-l),vec3(0));
    }
    return inverse(TO_RGB)*rgb;
}
#endif

#ifndef RAPIDRAW_OUTPUT_FUSED
#if MODE == 9 || MODE == 10
shared uint bins[2048];
shared uint totals[256];
#endif
int sampleCols(int paddedWidth) { return (paddedWidth-4+6)/7; }
void main() {
#if MODE == 7
    // md_sample; grid-stride over the 1D sample index.
    for(int s=int(gl_GlobalInvocationID.x);s<u_count;s+=int(gl_NumWorkGroups.x)*256){
        float v=0.0;
        if(u_kind==0||u_kind==2){
            ivec2 pw=(u_kind==0?u_half:u_quarter)+2*PAD; int cols=sampleCols(pw.x);
            ivec2 at=ivec2((s%cols)*7,(s/cols)*8);
            v=abs(texelFetch(u_a,at+ivec2(4,0),0).r-texelFetch(u_a,at,0).r);
        }else if(u_kind==1||u_kind==3){
            ivec2 pw=(u_kind==1?u_half:u_quarter)+2*PAD; int cols=sampleCols(pw.x),k=s/2;
            ivec2 at=ivec2((k%cols)*7,(k/cols)*8); int ch=s%2==0?1:2;
            v=abs(texelFetch(u_a,at+ivec2(4,0),0)[ch]-texelFetch(u_a,at,0)[ch]);
        }else if(u_kind==4){
            int i=s*7; ivec2 at=ivec2(i%u_half.x,i/u_half.x);
            v=length(texelFetch(u_a,at,0).rg-texelFetch(u_b,at,0).rg); // half filtered - ref
        }else if(u_kind==5){
            int cols=sampleCols(u_full_size.x),k=s/2;
            ivec2 at=ivec2((k%cols)*7,(k/cols)*8);
            vec3 a=stabilize(developed(at)),b=stabilize(developed(at+ivec2(4,0)));
            v=s%2==0?abs(b.y-a.y):abs(b.z-a.z);
        }else{
            int cols=(u_full_size.x+4)/5;
            ivec2 at=ivec2((s%cols)*5,(s/cols)*16);
            v=abs(lumaAt(at)-blurAt(at));
        }
        samples[s]=v;
    }
#elif MODE == 9
    // md_hist: 11/11/10-bit radix histogram of non-negative float bits.
    uint li=gl_LocalInvocationIndex;
    for(uint b=li;b<2048u;b+=256u)bins[b]=0u;
    barrier();
    uint prefix=stats[PREFIX];
    for(int s=int(gl_GlobalInvocationID.x);s<u_count;s+=int(gl_NumWorkGroups.x)*256){
        uint bits=floatBitsToUint(samples[s]);
        if(u_pass==0)atomicAdd(bins[bits>>21u],1u);
        else if(u_pass==1){ if((bits>>21u)==prefix)atomicAdd(bins[(bits>>10u)&2047u],1u); }
        else if((bits>>10u)==prefix)atomicAdd(bins[bits&1023u],1u);
    }
    barrier();
    for(uint b=li;b<2048u;b+=256u){ uint v=bins[b]; if(v!=0u)atomicAdd(stats[RADIX+b],v); }
#elif MODE == 10
    // md_pick: one workgroup finds the bucket holding rank n/2, then clears the histogram.
    uint li=gl_LocalInvocationIndex,n=uint(u_count);
    if(n==0u){ if(li==0u)medians[u_slot]=0.0; return; }
    uint rank=u_pass==0?n/2u:stats[RANK];
    uint prefix=u_pass==0?0u:stats[PREFIX];
    uint per=(u_pass==2?1024u:2048u)/256u,first=li*per,own=0u;
    uint counts[8];
    for(uint k=0u;k<per;k++){ counts[k]=stats[RADIX+first+k]; stats[RADIX+first+k]=0u; own+=counts[k]; }
    totals[li]=own;
    barrier();
    for(uint step=1u;step<256u;step<<=1u){
        uint add=li>=step?totals[li-step]:0u;
        barrier();
        totals[li]+=add;
        barrier();
    }
    uint before=totals[li]-own;
    if(rank<before||rank>=totals[li])return;
    uint left=rank-before,bucket=first;
    for(uint k=0u;k<per;k++){ if(left<counts[k]){bucket=first+k;break;} left-=counts[k]; }
    uint next=u_pass==0?bucket:((prefix<<(u_pass==2?10u:11u))|bucket);
    stats[PREFIX]=next; stats[RANK]=left;
    if(u_pass==2)medians[u_slot]=uintBitsToFloat(next);
#else
    ivec2 p=ivec2(gl_GlobalInvocationID.xy)+u_dispatch_offset;
#if MODE == 8
    prepareCleanupTile();
#endif
    if(any(greaterThanEqual(p,u_size)))return;
#if MODE == 0
    // nr_half: padded output; 2x2 box of developed RGB, then stabilize.
    ivec2 xy=clamp(p-PAD,ivec2(0),u_half-1); vec3 sum=vec3(0);
    for(int k=0;k<2;k++)for(int j=0;j<2;j++)
        sum+=developed(min(2*xy+ivec2(j,k),u_full_size-1))*.25;
    imageStore(out_image,p,vec4(stabilize(sum),0));
#elif MODE == 1
    // nr_quarter: padded output from the padded half plane (u_a).
    ivec2 xy=clamp(p-PAD,ivec2(0),u_quarter-1); vec3 sum=vec3(0);
    for(int k=0;k<2;k++)for(int j=0;j<2;j++)
        sum+=texelFetch(u_a,min(2*xy+ivec2(j,k),u_half-1)+PAD,0).rgb*.25;
    imageStore(out_image,p,vec4(sum,0));
#elif MODE == 2
    // nr_relwiden on the padded plane u_a of scale u_scale.
    float rel=1.0,widen=1.0;
    if(all(greaterThan(p,ivec2(0)))&&all(lessThan(p+1,u_size))){
        float noiseQ=noiseOf(u_scale==0?MED_HALF_Q:MED_QUARTER_Q);
        float lumaTol=(1.5+2.0*u_amount)*noiseQ;
        float g=.5*max(abs(texelFetch(u_a,p+ivec2(1,0),0).r-texelFetch(u_a,p-ivec2(1,0),0).r),
                       abs(texelFetch(u_a,p+ivec2(0,1),0).r-texelFetch(u_a,p-ivec2(0,1),0).r));
        float t=g/lumaTol; rel=1.0/(1.0+t*t); float f=1.0+2.0*t; widen=1.0/(f*f);
    }
    imageStore(out_image,p,vec4(rel,widen,0,0));
#elif MODE == 3
    // nr_filter: u_a padded stabilized plane, u_b padded rel/widen, u_c reference (u_use_ref).
    float a=u_amount;
    float noiseQ=noiseOf(u_scale==0?MED_HALF_Q:MED_QUARTER_Q),noiseC=noiseOf(u_scale==0?MED_HALF_C:MED_QUARTER_C);
    float lumaTol=(1.5+2.0*a)*noiseQ,chromaTol=(2.0+3.0*a)*noiseC,sigma=1.5+a;
    float kq=1.0/(lumaTol*lumaTol),kc=1.0/(chromaTol*chromaTol),eps=lumaTol*lumaTol;
    float inv2s2=1.0/(2.0*sigma*sigma);
    ivec2 c=p+PAD;
    float qc=texelFetch(u_a,c,0).r,wc=texelFetch(u_b,c,0).g;
    vec2 ref=vec2(0);
    if(u_use_ref==1)ref=texelFetch(u_c,p,0).rg;
    else{
        float refW=0.0;
        for(int j=-1;j<=1;j++)for(int i=-1;i<=1;i++){
            ivec2 k=c+ivec2(i,j); vec3 s=texelFetch(u_a,k,0).rgb;
            float sp=exp(-float(i*i+j*j)*inv2s2),d=s.r-qc,t=max(1.0-d*d*kq*wc,0.0);
            float wt=sp*texelFetch(u_b,k,0).r*t*t;
            ref+=wt*s.gb; refW+=wt;
        }
        ref*=1.0/refW;
    }
    float sw=0.0,sd=0.0,sdd=0.0; vec2 sc=vec2(0),sdc=vec2(0);
    for(int j=-2;j<=2;j++)for(int i=-2;i<=2;i++){
        ivec2 k=c+ivec2(i,j); vec3 s=texelFetch(u_a,k,0).rgb;
        float sp=exp(-float(i*i+j*j)*inv2s2),d=s.r-qc; vec2 e=s.gb-ref;
        float t=max(1.0-(d*d*kq+dot(e,e)*kc)*wc,0.0);
        float wt=sp*texelFetch(u_b,k,0).r*t*t,wd=wt*d;
        sw+=wt; sd+=wd; sdd+=wd*d; sc+=wt*s.gb; sdc+=wd*s.gb;
    }
    vec2 f=ref;
    if(sw>1e-6){
        float inv=1.0/sw,md=sd*inv; vec2 mc=sc*inv;
        float varD=max(sdd*inv-md*md,0.0)+eps;
        f=mc-(sdc*inv-mc*md)/varD*md;
    }
    imageStore(out_image,p,vec4(f,0,0));
#elif MODE == 4
    // nr_upsample: quarter filtered chroma (u_b) guided by quarter q (u_a) and half q (u_c).
    float qc=texelFetch(u_c,p+PAD,0).r;
    imageStore(out_image,p,vec4(upsample(p,qc,u_a,u_b,u_quarter,upsampleEps()).c,0,0));
#elif MODE == 5
    // nr_core: u_a padded half plane, u_b upsampled reference, u_c half filtered chroma.
    float a=u_amount,core=max(medians[MED_CORE]*(1.5+a),1e-6),tex=4.0*(0.5+a);
    float lo=3.4e38,hi=-3.4e38;
    for(int j=-1;j<=1;j++)for(int i=-1;i<=1;i++){
        float v=texelFetch(u_a,p+PAD+ivec2(i,j),0).r; lo=min(lo,v); hi=max(hi,v);
    }
    vec2 r=texelFetch(u_b,p,0).rg,e=texelFetch(u_c,p,0).rg-r;
    float keep=clamp(length(e)/(core+tex*(hi-lo))-1.0,0.0,1.0);
    imageStore(out_image,p,vec4(r+e*keep,0,0));
#elif MODE == 8
    imageStore(out_image,p,vec4(cleanupPixel(p),texelFetch(u_source,p,0).a));
#endif
#endif
}
#endif
