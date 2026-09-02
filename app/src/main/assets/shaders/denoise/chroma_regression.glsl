#version 310 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x=8,local_size_y=8) in;

uniform sampler2D u_input;
uniform sampler2D u_guide;
layout(rgba16f,binding=0) uniform writeonly highp image2D u_output;
uniform ivec2 u_size;
uniform vec3 u_noise_s,u_noise_o;
uniform float u_strength,u_detail_protection;
uniform int u_enabled;

vec3 source(ivec2 p){return texelFetch(u_input,clamp(p,ivec2(0),u_size-1),0).rgb;}
float guide(ivec2 p){return texelFetch(u_guide,clamp(p,ivec2(0),u_size-1),0).x;}

void main(){
 ivec2 p=ivec2(gl_GlobalInvocationID.xy);
 if(any(greaterThanEqual(p,u_size)))return;
 vec3 center=source(p);
 if(u_enabled==0||u_strength<=0.){imageStore(u_output,p,vec4(center,0));return;}

 float centerGuide=guide(p);
 float lumaSigma=sqrt(max(u_noise_s.x*max(center.x,0.)+u_noise_o.x,1e-10));
 float guideScale=max(4.*lumaSigma,.004);
 vec3 mean=vec3(0);float weightSum=0.;
 for(int y=-2;y<=2;++y)for(int x=-2;x<=2;++x){
  ivec2 q=p+ivec2(x,y);float dg=guide(q)-centerGuide;
  float spatial=1./(1.+.18*float(x*x+y*y));
  float range=1./(1.+dg*dg/(guideScale*guideScale));
  float w=spatial*range;mean+=w*vec3(guide(q),source(q).yz);weightSum+=w;
 }
 mean/=max(weightSum,1e-6);

 float yy=0.;vec2 yc=vec2(0);
 for(int y=-2;y<=2;++y)for(int x=-2;x<=2;++x){
  ivec2 q=p+ivec2(x,y);float dg=guide(q)-centerGuide;
  float w=(1./(1.+.18*float(x*x+y*y)))/(1.+dg*dg/(guideScale*guideScale));
  vec3 v=vec3(guide(q),source(q).yz)-mean;yy+=w*v.x*v.x;yc+=w*v.x*v.yz;
 }
 vec2 slope=yc/max(yy,1e-8);
 vec2 predicted=mean.yz+slope*(centerGuide-mean.x);

 vec2 localVariance=vec2(0);float localWeight=0.;
 for(int y=-2;y<=2;++y)for(int x=-2;x<=2;++x){
  ivec2 q=p+ivec2(x,y);float dg=guide(q)-centerGuide;
  float w=(1./(1.+.18*float(x*x+y*y)))/(1.+dg*dg/(guideScale*guideScale));
  vec2 residual=source(q).yz-(mean.yz+slope*(guide(q)-mean.x));
  localVariance+=w*residual*residual;localWeight+=w;
 }
 localVariance/=max(localWeight,1e-6);
 vec2 modelVariance=max(u_noise_s.yz*max(center.x,0.)+u_noise_o.yz,vec2(1e-10));
 vec2 sigma=sqrt(max(modelVariance,.65*localVariance));
 float significance=length((center.yz-predicted)/sigma);

 // A genuine isoluminant feature has several neighboring pixels in the same color region.
 // The former single-neighbor test let random red/green speckles protect one another.
 const ivec2 N[8]=ivec2[8](ivec2(1,0),ivec2(-1,0),ivec2(0,1),ivec2(0,-1),ivec2(1,1),ivec2(-1,-1),ivec2(1,-1),ivec2(-1,1));
 vec2 supportSigma=max(sqrt(modelVariance),.35*sigma);
 float supporters=0.;
 for(int i=0;i<8;++i){
  ivec2 q=p+N[i];float colorDistance=length((source(q).yz-center.yz)/supportSigma);
  float guideDistance=abs(guide(q)-centerGuide)/guideScale;
  supporters+=(colorDistance<2.2&&guideDistance<2.5)?1.:0.;
 }
 float coherentColor=smoothstep(2.5,5.,supporters)*smoothstep(1.8,4.,significance);
 float lumaEdge=abs(guide(p+ivec2(1,0))-guide(p-ivec2(1,0)))+abs(guide(p+ivec2(0,1))-guide(p-ivec2(0,1)));
 float guidedEdge=.30*smoothstep(4.,10.,lumaEdge/max(lumaSigma,1e-6))*smoothstep(2.5,5.,significance);
 float strength=clamp(.5*u_strength,0.,1.);
 float exceptionalDetail=smoothstep(mix(3.8,5.5,strength),mix(7.,10.,strength),significance);
 float keep=max(exceptionalDetail,max(coherentColor,guidedEdge)*u_detail_protection);
 vec2 cleaned=mix(predicted,center.yz,keep);
 imageStore(u_output,p,vec4(center.x,mix(center.yz,cleaned,clamp(u_strength,0.,1.)),0));
}
