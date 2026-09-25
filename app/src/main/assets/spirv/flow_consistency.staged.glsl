#version 450
uniform highp uvec3 u_dispatch_offset;
// SPDX-License-Identifier: GPL-3.0-or-later
precision highp float;
precision highp int;
precision highp sampler2D;
layout(local_size_x=1,local_size_y=1) in;
uniform sampler2D u_forward,u_reverse;
uniform ivec2 u_size,u_tile_grid;
uniform int u_tile_size;
uniform float u_max_consistency;
layout(binding=0,rgba32f) writeonly uniform highp image2D img_flow;
void main(){
 ivec2 tile=ivec2((gl_GlobalInvocationID + u_dispatch_offset).xy);if(any(greaterThanEqual(tile,u_tile_grid)))return;
 vec4 f=texelFetch(u_forward,tile,0);
 ivec2 origin=tile*u_tile_size,end=min(origin+ivec2(u_tile_size),u_size);
 vec2 p=vec2(origin+end-1)*0.5+f.xy;
 bool inside=all(greaterThanEqual(p,vec2(0)))&&all(lessThan(p,vec2(u_size)));
 ivec2 backTile=clamp(ivec2(p/float(u_tile_size)),ivec2(0),u_tile_grid-1);
 vec4 b=texelFetch(u_reverse,backTile,0);
 vec2 error=abs(f.xy+b.xy);
 f.w=f.w>0.0&&inside&&b.w>0.0&&max(error.x,error.y)<=u_max_consistency?1.0:0.0;
 imageStore(img_flow,tile,f);
}
