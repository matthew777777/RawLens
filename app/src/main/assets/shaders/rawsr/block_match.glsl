// SPDX-License-Identifier: GPL-3.0-or-later
// JAMY-L block matching 1:1 (block_matching.py align_lvl_block_matching_L1/L2
// via RawSrCoreAlign.blockCostL1/blockCostL2). L1 (finest level): SAD with
// zero-filled out-of-image moving taps, seed rounded half-away (CUDA round),
// integer output. L2 (coarse levels): direct-SSD with edge-clamped moving
// taps (proven argmin-equivalent to the FFT-correlation form), seed rounded
// half-even (torch.round), winner added onto the fractional seed.
// First-minimum scan (sy outer, sx inner, strictly-less update). No validity
// gating: the reference never rejects a tile here. The seed arrives
// pre-upscaled at this level's grid (flow_upscale.glsl); z is the mean
// cost/area diagnostic, w is 1.0 (downstream consumes xy only).
precision highp float;
precision highp int;
precision highp sampler2D;
layout(local_size_x=1,local_size_y=1) in;
uniform sampler2D u_reference,u_moving,u_seed_flow;
uniform ivec2 u_mov_size,u_tile_grid;
uniform int u_tile_size,u_search_radius,u_l1;
layout(binding=0,rgba32f) writeonly uniform highp image2D img_flow;
int roundHalfAway(float x){return x>=0.0?int(floor(x+0.5)):int(ceil(x-0.5));}
int roundHalfEven(float x){
 float f=floor(x);float d=x-f;
 if(d<0.5)return int(f);
 if(d>0.5)return int(f+1.0);
 return mod(f,2.0)==0.0?int(f):int(f+1.0);
}
float costL1(ivec2 origin,ivec2 off){
 float sad=0.0;
 for(int y=0;y<64;y++){
  if(y>=u_tile_size)break;
  for(int x=0;x<64;x++){
   if(x>=u_tile_size)break;
   ivec2 p=origin+ivec2(x,y),q=p+off;
   float m=0.0;
   if(all(greaterThanEqual(q,ivec2(0)))&&all(lessThan(q,u_mov_size)))m=texelFetch(u_moving,q,0).r;
   sad+=abs(texelFetch(u_reference,p,0).r-m);
  }
 }
 return sad;
}
float costL2(ivec2 origin,ivec2 off){
 float ssd=0.0;
 for(int y=0;y<64;y++){
  if(y>=u_tile_size)break;
  for(int x=0;x<64;x++){
   if(x>=u_tile_size)break;
   ivec2 p=origin+ivec2(x,y);
   ivec2 q=clamp(p+off,ivec2(0),u_mov_size-ivec2(1));
   float e=texelFetch(u_reference,p,0).r-texelFetch(u_moving,q,0).r;
   ssd+=e*e;
  }
 }
 return ssd;
}
void main(){
 ivec2 tile=ivec2(gl_GlobalInvocationID.xy);if(any(greaterThanEqual(tile,u_tile_grid)))return;
 ivec2 origin=tile*u_tile_size;
 vec2 seed=texelFetch(u_seed_flow,tile,0).xy;
 ivec2 base=u_l1!=0?ivec2(roundHalfAway(seed.x),roundHalfAway(seed.y)):ivec2(roundHalfEven(seed.x),roundHalfEven(seed.y));
 float best=3.402823466e+38;ivec2 winner=ivec2(0);
 for(int sy=-6;sy<=6;sy++){
  if(abs(sy)>u_search_radius)continue;
  for(int sx=-6;sx<=6;sx++){
   if(abs(sx)>u_search_radius)continue;
   float c=u_l1!=0?costL1(origin,base+ivec2(sx,sy)):costL2(origin,base+ivec2(sx,sy));
   if(c<best){best=c;winner=ivec2(sx,sy);}
  }
 }
 vec2 flow=u_l1!=0?vec2(base+winner):seed+vec2(winner);
 float area=float(u_tile_size*u_tile_size);
 imageStore(img_flow,tile,vec4(flow,best/area,1.0));
}
