// SPDX-License-Identifier: GPL-3.0-or-later
// JAMY-L inter-level flow propagation 1:1 (alignment.upscale_lvl via
// RawSrCoreAlign.upsampleFlow): torch interpolate align_corners=False
// (edge-clamped taps; bicubic Keys a=-0.75) by u_repeat, scaled by
// u_factor, zero-padded past the upsampled footprint (the dispatch grid
// crops oversized grids). u_mode: 0 nearest, 1 bilinear (reference
// default), 2 bicubic. z is 0 (no residual at seed stage), w is 1.
precision highp float;
precision highp int;
precision highp sampler2D;
layout(local_size_x=1,local_size_y=1) in;
uniform sampler2D u_prior;
uniform ivec2 u_src_grid,u_dst_grid;
uniform int u_repeat,u_factor,u_mode;
layout(binding=0,rgba32f) writeonly uniform highp image2D img_flow;
float tap(ivec2 g,int channel){
 ivec2 c=clamp(g,ivec2(0),u_src_grid-ivec2(1));
 vec4 v=texelFetch(u_prior,c,0);
 return channel==0?v.x:v.y;
}
float cubicKeys(float x){
 float a=-0.75;
 if(x<=1.0)return (a+2.0)*x*x*x-(a+3.0)*x*x+1.0;
 if(x<2.0)return a*x*x*x-5.0*a*x*x+8.0*a*x-4.0*a;
 return 0.0;
}
float interpChannel(int channel,ivec2 t){
 if(u_mode==0)return tap(t/u_repeat,channel);
 float sx=(float(t.x)+0.5)/float(u_repeat)-0.5;
 float sy=(float(t.y)+0.5)/float(u_repeat)-0.5;
 ivec2 base=ivec2(floor(vec2(sx,sy)));
 vec2 f=vec2(sx,sy)-vec2(base);
 if(u_mode==2){
  float acc=0.0;
  for(int j=-1;j<=2;j++){
   float row=0.0;
   for(int i=-1;i<=2;i++)row+=tap(base+ivec2(i,j),channel)*cubicKeys(abs(float(i)-f.x));
   acc+=row*cubicKeys(abs(float(j)-f.y));
  }
  return acc;
 }
 float top=tap(base,channel)+(tap(base+ivec2(1,0),channel)-tap(base,channel))*f.x;
 float bot=tap(base+ivec2(0,1),channel)+(tap(base+ivec2(1,1),channel)-tap(base+ivec2(0,1),channel))*f.x;
 return top+(bot-top)*f.y;
}
void main(){
 ivec2 t=ivec2(gl_GlobalInvocationID.xy);if(any(greaterThanEqual(t,u_dst_grid)))return;
 vec2 flow=vec2(0.0);
 if(all(lessThan(t,u_src_grid*u_repeat))){
  flow=vec2(interpChannel(0,t),interpChannel(1,t))*float(u_factor);
 }
 imageStore(img_flow,t,vec4(flow,0.0,1.0));
}
