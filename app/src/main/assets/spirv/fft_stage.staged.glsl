#version 450
uniform highp uvec3 u_dispatch_offset;
// SPDX-License-Identifier: GPL-3.0-or-later
// One mixed-radix DIT stage 1:1 (RawSrFft.ditStaged stages 1+2, float32):
// out[(o*p+k1)*m'+j] = W_m^{j*k1} * sum_{t<p} in[o*m+t*m'+j] * W_p^{t*k1},
// the CPU's naive() inner sum in the same t order and the same stage-2
// twiddle multiply. One thread per output element (no workgroup
// communication, so watchdog slicing only translates IDs). The host
// chains one dispatch per factor (RawSrFftPlan.stages) over ping-pong
// complex planes, then fft_remap mode 2 gathers natural order from the
// flattened stage order. Twiddle tables are the CPU double tables
// rounded to float, uploaded as m'x1 / px1 RGBA32F (re=R, im=G).
// u_first fuses the mosaic
// load: u_re_in is the real mosaic, im is 0, and u_flip applies the
// sensor->RGGB flip (RawSrCfaOrientation: 0 identity, 1 hflip, 2 vflip,
// 3 rot180) on read. u_axis 0 runs rows (n = u_size.x), 1 columns.
precision highp float;
precision highp int;
precision highp sampler2D;
layout(local_size_x=8,local_size_y=8) in;
uniform sampler2D u_re_in;
uniform sampler2D u_im_in;
uniform sampler2D u_tw_m;
uniform sampler2D u_tw_p;
uniform ivec2 u_size;
uniform int u_axis;
uniform int u_m;
uniform int u_radix;
uniform int u_first;
uniform int u_flip;
layout(binding=0,r32f) writeonly uniform highp image2D img_re_out;
layout(binding=1,r32f) writeonly uniform highp image2D img_im_out;
void main(){
 ivec2 p=ivec2((gl_GlobalInvocationID + u_dispatch_offset).xy);if(any(greaterThanEqual(p,u_size)))return;
 int pos=(u_axis==0)?p.x:p.y;
 int line=(u_axis==0)?p.y:p.x;
 int m2=u_m/u_radix;
 int outer2=pos/m2;
 int j=pos-outer2*m2;
 int k1=outer2%u_radix;
 int o=(outer2-k1)/u_radix;
 precise float sumRe=0.0;
 precise float sumIm=0.0;
 for(int t=0;t<19;t++){
  if(t>=u_radix)break;
  int src=o*u_m+t*m2+j;
  ivec2 q=(u_axis==0)?ivec2(src,line):ivec2(line,src);
  float a;
  float b;
  if(u_first==1){
   ivec2 f=q;
   if(u_flip==1||u_flip==3)f.x=u_size.x-1-f.x;
   if(u_flip==2||u_flip==3)f.y=u_size.y-1-f.y;
   a=texelFetch(u_re_in,f,0).r;
   b=0.0;
  }else{
   a=texelFetch(u_re_in,q,0).r;
   b=texelFetch(u_im_in,q,0).r;
  }
  vec4 w=texelFetch(u_tw_p,ivec2((t*k1)%u_radix,0),0);
  sumRe+=a*w.x-b*w.y;
  sumIm+=a*w.y+b*w.x;
 }
 vec4 tw=texelFetch(u_tw_m,ivec2((j*k1)%u_m,0),0);
 float outRe=sumRe*tw.x-sumIm*tw.y;
 float outIm=sumRe*tw.y+sumIm*tw.x;
 imageStore(img_re_out,p,vec4(outRe));
 imageStore(img_im_out,p,vec4(outIm));
}
