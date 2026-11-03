#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Build an isolated scalar reference of RT's Bayer CA algorithm, without its GUI.
The numerical algorithm is unchanged. Avoid-colour-shift is deliberately disabled
for this bounded experiment; its sigma-30 blur/extra image arrays are not ported.
"""
from pathlib import Path
import subprocess,sys
root=Path(__file__).resolve().parent
out=Path(sys.argv[1] if len(sys.argv)>1 else '/tmp/rawlens-rt-ca-build');out.mkdir(parents=True,exist_ok=True)
source=(root/'upstream/CA_correct_RT.cc').read_text()
source='\n'.join(line for line in source.splitlines() if not line.startswith('#include'))
source='#undef __SSE2__\n#undef RT_SIMDE\n'+source
wrapper=r'''
#include <algorithm>
#include <array>
#include <cassert>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
template<class T> class array2D {
 std::vector<T> data;int width;
 public:array2D(int w,int h):data(static_cast<size_t>(w)*h),width(w){}
 T*operator[](int y){return data.data()+static_cast<size_t>(y)*width;}
 const T*operator[](int y)const{return data.data()+static_cast<size_t>(y)*width;}
};
namespace rtengine {
template<class T> inline T SQR(T x){return x*x;}
template<class T> inline T LIM(T x,T low,T high){return std::clamp(x,low,high);}
template<class T,size_t N> T median(std::array<T,N> a){std::nth_element(a.begin(),a.begin()+N/2,a.end());return a[N/2];}
inline float intp(float a,float b,float c){return a*b+(1-a)*c;}
struct StopWatch {explicit StopWatch(const char*){}};
struct Settings {bool verbose=false;};static Settings config;static Settings*settings=&config;
struct Progress {void setProgress(double){}};
inline void gaussianBlur(array2D<float>&,array2D<float>&,int,int,double){throw std::runtime_error("Avoid-colour-shift is not enabled in this reference harness");}
struct RawImageSource {
 int W,H;unsigned pattern[2][2]={{1,2},{0,1}};Progress*plistener=nullptr;
 unsigned FC(int y,int x)const{return pattern[y&1][x&1];}
 float* CA_correct_RT(bool,size_t,double,double,bool,int,array2D<float>&,double*,bool,bool,float*,bool,size_t,bool);
};
}
'''
main=r'''
int main(int argc,char**argv){
 if(argc!=5&&argc!=6){std::cerr<<"usage: raw_ca input.f32 output.f32 W H [GBRG]\n";return 2;}
 try{
  int w=std::stoi(argv[3]),h=std::stoi(argv[4]);if(w<128||h<128||w>20000||h>20000)throw std::invalid_argument("size");
  array2D<float> raw(w,h);std::ifstream in(argv[1],std::ios::binary);
  for(int y=0;y<h;++y){in.read(reinterpret_cast<char*>(raw[y]),w*4);for(int x=0;x<w;++x)raw[y][x]*=65535.f;}
  if(!in||in.peek()!=EOF)throw std::runtime_error("input size mismatch");
  rtengine::RawImageSource processor;processor.W=w;processor.H=h;
  std::string pattern=argc==6?argv[5]:"GBRG";
  if(pattern.size()!=4||std::count(pattern.begin(),pattern.end(),'R')!=1||std::count(pattern.begin(),pattern.end(),'B')!=1||std::count(pattern.begin(),pattern.end(),'G')!=2)throw std::invalid_argument("Bayer pattern");
  for(int n=0;n<4;++n)processor.pattern[n/2][n%2]=pattern[n]=='R'?0:pattern[n]=='G'?1:2;
  auto start=std::chrono::steady_clock::now();
  processor.CA_correct_RT(true,1,0,0,false,0,raw,nullptr,false,false,nullptr,true,1,false);
  double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
  std::ofstream out(argv[2],std::ios::binary);
  for(int y=0;y<h;++y){for(int x=0;x<w;++x)raw[y][x]/=65535.f;out.write(reinterpret_cast<const char*>(raw[y]),w*4);}
  if(!out)throw std::runtime_error("output write failed");
  std::cout<<"{\"milliseconds\":"<<ms<<",\"minimumAdditionalScratchBytes\":"<<static_cast<size_t>(w)*h*4<<",\"autoIterations\":1,\"avoidColourShift\":false}\n";
 }catch(const std::exception&e){std::cerr<<e.what()<<"\n";return 1;}
}
'''
p=out/'raw_ca.cpp';p.write_text(wrapper+'\n'+source+'\n'+main)
subprocess.run(['clang++','-std=c++17','-O3',str(p),'-o',str(out/'raw_ca')],check=True)
print(out/'raw_ca')
