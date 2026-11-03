// SPDX-License-Identifier: GPL-3.0-or-later
#include "defringe.h"
#include <chrono>
#include <fstream>
#include <iostream>
int main(int argc,char**argv) {
    if(argc<7) {std::cerr<<"usage: defringe input.lab output.lab W H radius threshold [purpleOnly=1]\n";return 2;}
    try {
        int w=std::stoi(argv[3]),h=std::stoi(argv[4]);
        if(w<1||h<1||static_cast<size_t>(w)*h>200000000)throw std::invalid_argument("size");
        std::vector<float> lab(static_cast<size_t>(w)*h*3);
        std::ifstream in(argv[1],std::ios::binary);in.read(reinterpret_cast<char*>(lab.data()),lab.size()*4);
        if(!in||in.peek()!=EOF)throw std::runtime_error("input size mismatch");
        rawlens_fringe::Config c{std::stof(argv[5]),std::stoi(argv[6]),argc<8||std::stoi(argv[7])!=0};
        auto start=std::chrono::steady_clock::now();auto stats=rawlens_fringe::defringeLab(lab.data(),w,h,c);
        double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
        std::ofstream out(argv[2],std::ios::binary);out.write(reinterpret_cast<const char*>(lab.data()),lab.size()*4);
        if(!out)throw std::runtime_error("output write failed");
        std::cout<<"{\"milliseconds\":"<<ms<<",\"scratchBytes\":"<<stats.scratchBytes<<",\"correctedPixels\":"<<stats.corrected<<",\"chromaMean\":"<<stats.chromaMean<<"}\n";
    }catch(const std::exception&e){std::cerr<<e.what()<<"\n";return 1;}
}
