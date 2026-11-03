// SPDX-License-Identifier: GPL-3.0-or-later
#include "defringe_rgba.h"
#include <chrono>
#include <fstream>
#include <iostream>
int main(int argc,char**argv) {
 if(argc!=7){std::cerr<<"usage: rgba_defringe in.rgba out.rgba W H radius threshold\n";return 2;}
 try {
  int w=std::stoi(argv[3]),h=std::stoi(argv[4]);if(w<1||h<1||static_cast<size_t>(w)*h>200000000)throw std::invalid_argument("size");
  std::vector<uint8_t> rgba(static_cast<size_t>(w)*h*4);std::ifstream in(argv[1],std::ios::binary);in.read(reinterpret_cast<char*>(rgba.data()),rgba.size());
  if(!in||in.peek()!=EOF)throw std::runtime_error("input size");
  rawlens_fringe::Config config{std::stof(argv[5]),std::stoi(argv[6]),true};
  auto begin=std::chrono::steady_clock::now();auto stats=rawlens_fringe::defringeRgbaParallel(rgba.data(),w,h,w*4,config);
  double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-begin).count();
  std::ofstream out(argv[2],std::ios::binary);out.write(reinterpret_cast<const char*>(rgba.data()),rgba.size());if(!out)throw std::runtime_error("output write");
  std::cout<<"{\"milliseconds\":"<<ms<<",\"scratchBytes\":"<<stats.scratchBytes<<",\"correctedPixels\":"<<stats.corrected<<",\"chromaMean\":"<<stats.chromaMean<<"}\n";
 }catch(const std::exception&e){std::cerr<<e.what()<<"\n";return 1;}
}
