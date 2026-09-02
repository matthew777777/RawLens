#version 310 es
precision highp float;precision highp int;precision highp sampler2D;precision highp image2D;
layout(local_size_x=8,local_size_y=8) in;uniform sampler2D u_noisy,u_pilot;layout(rgba16f,binding=0) uniform writeonly highp image2D u_output;uniform ivec2 u_size;uniform vec3 u_noise_s,u_noise_o;uniform float u_strength,u_detail_protection;uniform int u_second_pass,u_enabled;
vec3 f(sampler2D s,ivec2 p){return texelFetch(s,clamp(p,ivec2(0),u_size-1),0).rgb;}void h(inout float a[4]){float x=a[0]+a[1],y=a[0]-a[1],z=a[2]+a[3],w=a[2]-a[3];a[0]=x+z;a[1]=y+w;a[2]=x-z;a[3]=y-w;}void wh(inout float a[16]){for(int y=0;y<4;++y){float q[4];for(int x=0;x<4;++x)q[x]=a[4*y+x];h(q);for(int x=0;x<4;++x)a[4*y+x]=q[x];}for(int x=0;x<4;++x){float q[4];for(int y=0;y<4;++y)q[y]=a[4*y+x];h(q);for(int y=0;y<4;++y)a[4*y+x]=q[y]*.25;}}
void main(){ivec2 p=ivec2(gl_GlobalInvocationID.xy);if(any(greaterThanEqual(p,u_size)))return;vec3 c=f(u_noisy,p);if(u_enabled==0){imageStore(u_output,p,vec4(c,0));return;}float n[16],q[16];int k=0;for(int y=-1;y<=2;++y)for(int x=-1;x<=2;++x){n[k]=f(u_noisy,p+ivec2(x,y)).x;q[k]=f(u_pilot,p+ivec2(x,y)).x;++k;}wh(n);wh(q);float s2=max(u_noise_s.x*max(c.x,0.)+u_noise_o.x,1e-10);
 // The first implementation treated the modeled sigma as a hard removal target twice,
 // visibly flattening labels and wall texture. The pilot now supplies only conservative
 // empirical-Wiener evidence; strength never reaches the old threshold at 100%.
 float l=mix(1.65,.82,u_detail_protection)*mix(.22,.72,u_strength);
 for(int i=1;i<16;++i){
  // Only the three lowest non-DC spatial modes are eligible for shrinkage. Every
  // fine/high-sequency coefficient is copied exactly, retaining natural luma grain.
  bool coarse=(i==1||i==4||i==5);if(u_second_pass!=0&&!coarse)continue;
  float e=u_second_pass!=0?q[i]*q[i]:n[i]*n[i];float gain=max(0.,(e-l*l*s2)/(e+1e-10));n[i]*=gain;
 }wh(n);imageStore(u_output,p,vec4(n[5],c.yz,0));}
