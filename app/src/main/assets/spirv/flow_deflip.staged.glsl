#version 450
uniform highp uvec3 u_dispatch_offset;
// SPDX-License-Identifier: GPL-3.0-or-later
// RGGB-processing-space flow field back to sensor space (CPU
// RawSrCfaOrientation.remapFieldToSensor twin, float32): mirror flips
// negate the mirrored flow component, residuals/det ride unchanged.
// u_mode: 1 hflip (negate dx), 2 vflip (negate dy), 3 rot180 (negate
// both). Identity patterns skip this pass host-side.
// The source tile is the processing tile holding the majority of the
// flipped sensor tile (u_img_w/u_img_h raw dims, u_tile tile size) —
// NOT grid-1-t, which is off by one tile row whenever the image
// height is not a multiple of the tile size (CPU twin documents the
// burst case). On divisible dims this reproduces the exact mirror.
precision highp float;
precision highp int;
precision highp sampler2D;
layout(local_size_x=8,local_size_y=8) in;
uniform sampler2D u_flow;
uniform int u_mode;
uniform int u_img_w;
uniform int u_img_h;
uniform int u_tile;
layout(binding=0,rgba32f) writeonly uniform highp image2D img_out;
void main(){
 ivec2 t=ivec2((gl_GlobalInvocationID + u_dispatch_offset).xy);
 ivec2 grid=textureSize(u_flow,0);
 if(any(greaterThanEqual(t,grid)))return;
 ivec2 s=t;
 if(u_mode==1||u_mode==3){int lo=t.x*u_tile;int hi=min(lo+u_tile-1,u_img_w-1);int c=(u_img_w-1)-(lo+hi)/2;s.x=clamp(c/u_tile,0,grid.x-1);}
 if(u_mode==2||u_mode==3){int lo=t.y*u_tile;int hi=min(lo+u_tile-1,u_img_h-1);int c=(u_img_h-1)-(lo+hi)/2;s.y=clamp(c/u_tile,0,grid.y-1);}
 vec4 v=texelFetch(u_flow,s,0);
 if(u_mode==1||u_mode==3)v.x=-v.x;
 if(u_mode==2||u_mode==3)v.y=-v.y;
 imageStore(img_out,t,v);
}
