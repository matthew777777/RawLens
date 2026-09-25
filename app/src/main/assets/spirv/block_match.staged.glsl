#version 450
uniform highp uvec3 u_dispatch_offset;
// SPDX-License-Identifier: GPL-3.0-or-later
precision highp float;
precision highp int;
precision highp sampler2D;
layout(local_size_x=1,local_size_y=1) in;
uniform sampler2D u_reference,u_moving,u_initial_flow;
uniform ivec2 u_size,u_tile_grid,u_previous_grid;
uniform int u_tile_size,u_search_radius,u_has_initial_flow,u_scale,u_l1;
uniform float u_min_fraction;
layout(binding=0,rgba32f) writeonly uniform highp image2D img_flow;
const float INVALID=1e6;
bool finite(float x){return !isnan(x)&&!isinf(x);}
float score(ivec2 origin,ivec2 end,ivec2 shift,bool l1){
 float error=0.0;int count=0;
 for(int y=0;y<32;y++){
  if(origin.y+y>=end.y)break;
  for(int x=0;x<32;x++){
   if(origin.x+x>=end.x)break;
   ivec2 p=origin+ivec2(x,y),q=p+shift;
   if(any(lessThan(q,ivec2(0)))||any(greaterThanEqual(q,u_size)))continue;
   float d=texelFetch(u_moving,q,0).r-texelFetch(u_reference,p,0).r;
   if(!finite(d))return INVALID;
   error+=l1?abs(d):d*d;count++;
  }
 }
 int area=(end.x-origin.x)*(end.y-origin.y);
 return count>=max(4,int(ceil(float(area)*u_min_fraction)))&&finite(error)?min(error/float(count),INVALID):INVALID;
}
void main(){
 ivec2 tile=ivec2((gl_GlobalInvocationID + u_dispatch_offset).xy);if(any(greaterThanEqual(tile,u_tile_grid)))return;
 ivec2 origin=tile*u_tile_size,end=min(origin+ivec2(u_tile_size),u_size),base=ivec2(0);
 if(u_has_initial_flow!=0){
  ivec2 parent=tile/u_scale;
  ivec2 direction=ivec2(tile.x%u_scale<u_scale/2?-1:1,tile.y%u_scale<u_scale/2?-1:1);
  float bestSeed=INVALID;
  for(int c=0;c<3;c++){
   ivec2 p=parent+(c==1?ivec2(direction.x,0):(c==2?ivec2(0,direction.y):ivec2(0)));
   if(any(lessThan(p,ivec2(0)))||any(greaterThanEqual(p,u_previous_grid)))continue;
   vec4 prior=texelFetch(u_initial_flow,p,0);if(prior.w==0.0)continue;
   ivec2 candidate=ivec2(prior.xy*float(u_scale));
   float error=score(origin,end,candidate,true);
   if(error<bestSeed){bestSeed=error;base=candidate;}
  }
 }
 float bestError=INVALID;int bestDistance=2147483647;ivec2 best=base;
 for(int oy=-6;oy<=6;oy++){
  if(abs(oy)>u_search_radius)continue;
  for(int ox=-6;ox<=6;ox++){
   if(abs(ox)>u_search_radius)continue;
   float error=score(origin,end,base+ivec2(ox,oy),u_l1!=0);
   int distance=ox*ox+oy*oy;
   if(error<bestError||(error==bestError&&error<INVALID&&distance<bestDistance)){
    bestError=error;bestDistance=distance;best=base+ivec2(ox,oy);
   }
  }
 }
 imageStore(img_flow,tile,vec4(vec2(best),bestError,bestError<INVALID?1.0:0.0));
}
