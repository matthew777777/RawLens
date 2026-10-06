#version 450
uniform highp uvec3 u_dispatch_offset;
// SPDX-License-Identifier: GPL-3.0-or-later
// JAMY-L circular pad 1:1 (alignment.init_alignment F.pad circular via
// RawSrAlignment.circularPad): right/bottom strips wrap from the left/top
// (out[p] = src[p mod srcSize] per axis). The reference pads the reference
// grey only; the moving grey stays unpadded. u_size is the padded size.
precision highp float;
precision highp int;
precision highp sampler2D;
layout(local_size_x=8,local_size_y=8) in;
uniform sampler2D u_source;
uniform ivec2 u_size;
layout(binding=0,r32f) writeonly uniform highp image2D img_out;
void main(){
 ivec2 p=ivec2((gl_GlobalInvocationID + u_dispatch_offset).xy);if(any(greaterThanEqual(p,u_size)))return;
 ivec2 src=textureSize(u_source,0);
 ivec2 q=ivec2(p.x%src.x,p.y%src.y);
 imageStore(img_out,p,texelFetch(u_source,q,0));
}
