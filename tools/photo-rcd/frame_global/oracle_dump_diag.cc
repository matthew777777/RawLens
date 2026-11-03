#include "photo_rcd_engine.h"
#include <cmath>
#include <cstring>
#include <fstream>
#include <iostream>
#include <vector>
namespace photo_rcd { extern uint32_t diagFlag; }
static std::vector<uint32_t> load(const char *p){std::ifstream f(p,std::ios::binary|std::ios::ate);std::vector<uint32_t> v(size_t(f.tellg())/4);f.seekg(0);f.read((char*)v.data(),v.size()*4);return v;}
static bool bad(uint16_t h){return (h&0x7c00)==0x7c00;}
int main(int argc,char**argv){
  uint32_t x=std::stoul(argv[3]),y=std::stoul(argv[4]);int n=std::stoi(argv[5]);
  photo_rcd::Engine engine(load(argv[1]),true,false,false,false,false,{},false,load(argv[2]),1,true,true,false,{x,y});
  const int sw=4080,sh=3060,row=sw*2,w=3840,h=2160;
  std::vector<uint8_t> raw(size_t(row)*sh);
  for(int sy=0;sy<sh;++sy)for(int sx=0;sx<sw;++sx){uint16_t v=uint16_t((sx*137+sy*991+17)%5000);raw[size_t(sy)*row+sx*2]=v&255;raw[size_t(sy)*row+sx*2+1]=v>>8;}
  std::array<int,9> g={sw,sh,row,2,120,450,w,h,148};std::array<float,4> black={64,64.5,63.25,65};
  std::vector<uint32_t> config(16);config[0]=120;config[1]=450;float one=1;for(int i=2;i<5;++i)std::memcpy(&config[i],&one,4);
  std::vector<uint16_t> half,first,prev;
  for(int i=0;i<n;++i){
    photo_rcd::diagFlag=0;engine.renderRawHalf(raw.data(),raw.size(),g,black,4095,config,half);
    size_t nb=0,diff=0;int minx=1<<30,miny=1<<30,maxx=-1,maxy=-1;
    for(size_t k=0;k<half.size();++k){size_t px=k/4;int X=px%w,Y=px/w;
      bool b=bad(half[k]);bool d=!first.empty()&&half[k]!=first[k];
      if(b)nb++; if(d)diff++;
      if(b||d){minx=std::min(minx,X);miny=std::min(miny,Y);maxx=std::max(maxx,X);maxy=std::max(maxy,Y);}}
    uint64_t hsh=1469598103934665603ull;for(auto v:half){hsh^=v;hsh*=1099511628211ull;}std::cout<<"hash="<<std::hex<<hsh<<std::dec<<" frame "<<i<<" flag="<<photo_rcd::diagFlag<<" nonfinite="<<nb<<" diff_vs_f0="<<diff<<" bbox="<<minx<<","<<miny<<"-"<<maxx<<","<<maxy<<std::endl;
    if(!prev.empty()){std::vector<long> lx(176),ly(176);size_t dc=0;float mx=0;
      for(size_t k=0;k<half.size();++k)if(half[k]!=prev[k]){size_t px=k/4;int X=px%w,Y=px/w;lx[X%176]++;ly[Y%176]++;dc++;}
      std::cout<<" consecutive_diff="<<dc<<" xmod176:";for(int i=0;i<176;i+=8){long t=0;for(int j=i;j<i+8;++j)t+=lx[j];std::cout<<t<<" ";}
      std::cout<<"\n ymod176:";for(int i=0;i<176;i+=8){long t=0;for(int j=i;j<i+8;++j)t+=ly[j];std::cout<<t<<" ";}std::cout<<std::endl;}
    prev=half;if(first.empty()){first=half;if(argc>6){std::ofstream f(argv[6],std::ios::binary);f.write((const char*)half.data(),half.size()*2);}}
  }
}
