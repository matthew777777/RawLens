#version 310 es
precision highp float; precision highp int; precision highp sampler2D; precision highp image2D;
layout(local_size_x=8,local_size_y=8) in;
uniform sampler2D u_input; layout(r32f,binding=0) uniform writeonly highp image2D u_output;
uniform ivec2 u_size; uniform ivec4 u_fc; uniform vec4 u_noise_s,u_noise_o; uniform float u_strength;
int colorAt(ivec2 p){return (p.y&1)==0?((p.x&1)==0?u_fc.x:u_fc.y):((p.x&1)==0?u_fc.z:u_fc.w);}
float cv(vec4 v,int c){return c==0?v.x:(c==2?v.w:.5*(v.y+v.z));}
float at(ivec2 p){ivec2 q=clamp(p,ivec2(0),u_size-1);if((q.x&1)!=(p.x&1))q.x+=q.x+1<u_size.x?1:-1;if((q.y&1)!=(p.y&1))q.y+=q.y+1<u_size.y?1:-1;return texelFetch(u_input,q,0).r;}
void sort8(inout float a[8]){for(int i=1;i<8;++i){float x=a[i];int j=i-1;for(;j>=0&&a[j]>x;--j)a[j+1]=a[j];a[j+1]=x;}}
void main(){ivec2 p=ivec2(gl_GlobalInvocationID.xy);if(any(greaterThanEqual(p,u_size)))return;float c=at(p);int ch=colorAt(p);float s=sqrt(max(cv(u_noise_s,ch)*max(c,0.)+cv(u_noise_o,ch),1e-12));
 const ivec2 O[8]=ivec2[8](ivec2(-2,0),ivec2(2,0),ivec2(0,-2),ivec2(0,2),ivec2(-2,-2),ivec2(2,2),ivec2(2,-2),ivec2(-2,2));
 float n[8];for(int i=0;i<8;++i)n[i]=at(p+O[i]);sort8(n);float m=.5*(n[3]+n[4]);float d[8];for(int i=0;i<8;++i)d[i]=abs(n[i]-m);sort8(d);float mad=1.4826*.5*(d[3]+d[4]);float t=max(4.5*s,3.5*mad+1e-6);bool support=false;for(int i=0;i<4;++i){float a=at(p+O[2*i]),b=at(p+O[2*i+1]);support=support||(abs(c-a)<t&&abs(c-b)<t);}bool impulse=abs(c-m)>t&&!support;float normal=(1.-smoothstep(0.,1.15,abs(c-m)/max(s,1e-6)))*smoothstep(.004,.018,s);float blend=impulse?1.:u_strength*.22*normal;imageStore(u_output,p,vec4(mix(c,m,blend)));}
