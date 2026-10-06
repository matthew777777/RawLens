#version 450
uniform highp uvec3 u_dispatch_offset;
// SPDX-License-Identifier: GPL-3.0-or-later
// Bilateral flow-field regularization (CPU
// RawSrAlignmentField.bilateralFiltered twin, float32): each tile's
// vector is replaced by the 3x3 bilateral average with the joint range
// kernel w = exp(-(|dx|^2 + |dy|^2) / (2*sigma^2)) over the vector
// difference to the center tile. Sub-sigma chatter steps collapse while
// supra-sigma motion discontinuities survive. Non-finite neighbors are
// skipped; a non-finite center rides through. Residuals/det ride with
// their tile. The host skips this pass when u_sigma <= 0.
precision highp float;
precision highp int;
precision highp sampler2D;
layout(local_size_x=8,local_size_y=8) in;
uniform sampler2D u_flow;
uniform float u_sigma;
layout(binding=0,rgba32f) writeonly uniform highp image2D img_out;
bool finite(float v){return !(isnan(v)||isinf(v));}
void main(){
 ivec2 t=ivec2((gl_GlobalInvocationID + u_dispatch_offset).xy);
 ivec2 grid=textureSize(u_flow,0);
 if(any(greaterThanEqual(t,grid)))return;
 vec4 c=texelFetch(u_flow,t,0);
 if(!finite(c.x)||!finite(c.y)){imageStore(img_out,t,c);return;}
 float denom=2.0*u_sigma*u_sigma;
 float wx=0.0;
 float wy=0.0;
 float wsum=0.0;
 for(int oy=-1;oy<=1;oy++){
  int ny=clamp(t.y+oy,0,grid.y-1);
  for(int ox=-1;ox<=1;ox++){
   int nx=clamp(t.x+ox,0,grid.x-1);
   vec4 n=texelFetch(u_flow,ivec2(nx,ny),0);
   if(!finite(n.x)||!finite(n.y))continue;
   float dx=n.x-c.x;
   float dy=n.y-c.y;
   float w=exp(-(dx*dx+dy*dy)/denom);
   wx+=n.x*w;
   wy+=n.y*w;
   wsum+=w;
  }
 }
 vec4 o=vec4(wx/wsum,wy/wsum,c.z,c.w);
 imageStore(img_out,t,o);
}
