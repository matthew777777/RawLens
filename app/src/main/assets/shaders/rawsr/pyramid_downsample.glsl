// SPDX-License-Identifier: GPL-3.0-or-later
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
int reflectAt(int p,int n){return p<0?-p-1:(p>=n?2*n-p-1:p);}
void main(){
 ivec2 p=ivec2(gl_GlobalInvocationID.xy);if(any(greaterThanEqual(p,u_size)))return;
 ivec2 size=textureSize(u_source,0);
 ivec2 center=p;if(u_axis==0)center.x*=u_factor;else center.y*=u_factor;
 float sum=0.0;
 for(int k=0;k<17;k++){
  if(k>4*u_factor)break;
  ivec2 q=center;if(u_axis==0)q.x=reflectAt(q.x+k-2*u_factor,size.x);
  else q.y=reflectAt(q.y+k-2*u_factor,size.y);
  sum+=texelFetch(u_source,q,0).r*u_weights[k];
 }
 imageStore(img_out,p,vec4(sum));
}
