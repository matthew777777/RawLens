// SPDX-License-Identifier: GPL-3.0-or-later
#include "defringe_rgba.h"
#include <cassert>
#include <iostream>
#include <random>
using namespace rawlens_fringe;
std::vector<float> reference(const std::vector<float>&src,int w,int h,Config c) {
 const int r=std::ceil(3*c.radius),m=std::ceil(2*c.radius);std::vector<float> kernel(2*r+1);double sum=0;
 for(int k=-r;k<=r;++k){kernel[k+r]=std::exp(-.5f*k*k/(c.radius*c.radius));sum+=kernel[k+r];}for(auto&v:kernel)v/=sum;
 std::vector<float> ga(w*h*2),weight(w*h),out=src;double total=0;
 for(int y=0;y<h;++y)for(int x=0;x<w;++x) {
  double a=0,b=0;
  for(int yy=-r;yy<=r;++yy)for(int xx=-r;xx<=r;++xx){int i=(std::clamp(y+yy,0,h-1)*w+std::clamp(x+xx,0,w-1))*3;double k=kernel[yy+r]*kernel[xx+r];a+=src[i+1]*k;b+=src[i+2]*k;}
  int i=(y*w+x)*3;float da=src[i+1]-a,db=src[i+2]-b;
  weight[y*w+x]=hueFactor(src[i+1],src[i+2],c.purpleOnly)*(da*da+db*db);total+=weight[y*w+x];
 }
 float mean=total/(w*h);if(!(mean>0))return out;
 for(auto&v:weight)v=1/(v+mean);
 float threshold=1/((c.threshold/33.f)*(c.threshold/33.f)*mean*5+mean);
 for(int y=0;y<h;++y)for(int x=0;x<w;++x)if(weight[y*w+x]<threshold){
  double a=0,b=0,norm=0;
  for(int yy=std::max(0,y-m);yy<std::min(h,y+m+1);++yy)for(int xx=std::max(0,x-m);xx<std::min(w,x+m+1);++xx){float wt=weight[yy*w+xx];a+=wt*src[(yy*w+xx)*3+1];b+=wt*src[(yy*w+xx)*3+2];norm+=wt;}
  out[(y*w+x)*3+1]=a/norm;out[(y*w+x)*3+2]=b/norm;
 }
 return out;
}
int main(){
 std::mt19937 rng(42);std::uniform_real_distribution<float> noise(-12,12);
 float maximum=0;
 for(auto size:std::vector<std::pair<int,int>>{{1,1},{3,2},{19,13},{47,35}})for(float radius:{.5f,1.f,2.f,4.f})for(bool purple:{false,true}){
  int w=size.first,h=size.second;std::vector<float> src(w*h*3);
  for(int y=0;y<h;++y)for(int x=0;x<w;++x){int i=(y*w+x)*3;src[i]=50+(x%5);src[i+1]=noise(rng)+(x>w/2?20:0);src[i+2]=noise(rng)-10;}
  Config c{radius,13,purple};auto expected=reference(src,w,h,c),actual=src;auto stats=defringeLab(actual.data(),w,h,c);
  for(size_t i=0;i<src.size();++i){assert(std::isfinite(actual[i]));if(i%3==0)assert(actual[i]==src[i]);maximum=std::max(maximum,std::abs(expected[i]-actual[i]));}
  assert(maximum<.003f);
  auto higher=std::vector<float>(w*(h+100)*3,0.f);auto hs=defringeLab(higher.data(),w,h+100,c);
  // Early no-op doesn't allocate weighted rings; the maximum budget is height-independent.
  assert(hs.scratchBytes<=stats.scratchBytes);
 }
 // Neutral, blue/green and uniform real purple images survive bit-exactly; alpha is never touched.
 for(auto color:std::vector<std::array<uint8_t,4>>{{128,128,128,71},{20,100,190,83},{145,30,170,99},{20,150,40,11}}){
  std::vector<uint8_t> pixels(64*48*4);for(size_t i=0;i<pixels.size();++i)pixels[i]=color[i%4];auto original=pixels;
  defringeRgba(pixels.data(),64,48,64*4,Config{1,13,true});
  assert(pixels==original);
 }
 // Mixed colours, padded rows, alpha and low/high luminance boundaries.
 std::vector<uint8_t> pixels(48*51*4,0xA7);
 for(int y=0;y<48;++y)for(int x=0;x<47;++x){auto*p=pixels.data()+(y*51+x)*4;p[0]=(x*23+y*11)%256;p[1]=(x*17+y*13)%256;p[2]=(x*29+y*3)%256;p[3]=y;}
 auto original=pixels;defringeRgba(pixels.data(),47,48,51*4,Config{1,13,true});
 for(int y=0;y<48;++y)for(int x=0;x<51;++x){size_t i=(y*51+x)*4;assert(pixels[i+3]==original[i+3]);if(x>=47)for(int c=0;c<4;++c)assert(pixels[i+c]==original[i+c]);}
 for(auto size:std::vector<std::pair<int,int>>{{1,1},{3,2},{19,13},{47,35},{129,71}}) {
  int w=size.first,h=size.second;std::vector<uint8_t> input(w*h*4);
  for(auto& v:input)v=rng()%256;
  auto serial=input,parallel=input;
  defringeRgba(serial.data(),w,h,w*4,Config{1,13,true});
  defringeRgbaParallel(parallel.data(),w,h,w*4,Config{1,13,true},4);
  for(size_t i=0;i<input.size();++i)assert(std::abs(int(serial[i])-int(parallel[i]))<=1);
 }
 bool rejected=false;try{defringeLab(nullptr,0,5,Config{});}catch(const std::invalid_argument&){rejected=true;}assert(rejected);
 std::cout<<"PASS: streaming/reference, tiny images, boundaries, hue gate, in-place, unchanged L, alpha, stride, uniform colours; maximum Lab error "<<maximum<<"\n";
}
