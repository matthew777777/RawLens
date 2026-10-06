#version 450
uniform highp uvec3 u_dispatch_offset;
// SPDX-License-Identifier: GPL-3.0-or-later
// JAMY-L gaussian downsample 1:1 (utils_image.downsample via
// RawSrAlignment.downsample): separable VALID convolution (no padding;
// radius int(2f+0.5), weights uploaded by the host from
// gaussianKernel1d) then strided take of every f-th pixel from 0. One
// axis per dispatch; the host chains axis 0 then axis 1. Axis 0 is the
// DENSE x-conv (convW x H, no stride); axis 1 fuses the y-conv with
// the both-axes strided take (outW x outH). Every tap is in-bounds by
// construction. u_factor 1 never dispatches (identity, host-side).
precision highp float;
precision highp int;
precision highp sampler2D;
layout(local_size_x=8,local_size_y=8) in;
uniform sampler2D u_source;
uniform ivec2 u_size;
uniform int u_factor;
uniform int u_axis;
uniform float u_weights[17];
layout(binding=0,r32f) writeonly uniform highp image2D img_out;
void main(){
 ivec2 p=ivec2((gl_GlobalInvocationID + u_dispatch_offset).xy);if(any(greaterThanEqual(p,u_size)))return;
 int radius=int(2.0*float(u_factor)+0.5);
 int taps=radius*2+1;
 float sum=0.0;
 for(int k=0;k<17;k++){
  if(k>=taps)break;
  ivec2 q=p;
  // Axis 0: dense valid x-conv (host sizes u_size to convW x H).
  // Axis 1: valid y-conv fused with the both-axes strided take, i.e.
  // out(ox,oy) = Σ_k w[k]·row[ox·f, oy·f+k], exactly the CPU
  // rowPass/colPass/take sequence (separable, so the fused take is
  // identical to take-after-both-convs).
  if(u_axis==0)q=ivec2(p.x+k,p.y);
  else q=ivec2(p.x*u_factor,p.y*u_factor+k);
  sum+=texelFetch(u_source,q,0).r*u_weights[k];
 }
 imageStore(img_out,p,vec4(sum));
}
