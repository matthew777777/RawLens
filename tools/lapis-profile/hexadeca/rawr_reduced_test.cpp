// SPDX-License-Identifier: GPL-3.0-only
#include "../../../app/src/main/cpp/hexa/rawr_reduced.h"
#include <iostream>
#include <random>
#include <stdexcept>

static void check(bool value, const char* why) { if (!value) throw std::runtime_error(why); }
int main() {
    constexpr unsigned w=96, h=80, ow=w/2, oh=h/2;
    std::mt19937 random(20261122);
    for (unsigned order=0; order<4; ++order) {
        std::vector<uint16_t> source(w*h), result(ow*oh), reference(ow*oh), parallel(ow*oh);
        for (auto& value: source) value=64+random()%960;
        const auto original=source;
        for (bool fit: {false,true}) {
            rawlens::rawrReduced(source.data(),w,h,order,64,result.data(),fit,1);
            if (fit) rawrcam::imaging::hexadecaSumWithFit(source.data(),w,h,64,3,reference.data(),1);
            else for (unsigned y=0; y<oh; ++y) for (unsigned x=0; x<ow; ++x) {
                const size_t i=size_t(2*y)*w+2*x;
                reference[y*ow+x]=source[i]+source[i+1]+source[i+w]+source[i+w+1];
            }
            rawrcam::imaging::remosaicBlockCfa(reference.data(),ow,oh,2,order,1,
                rawrcam::imaging::CfaRemosaicQuality::Detail,4.0f,256.0f);
            check(result==reference,"Upstream CPU reference parity failed");
            rawlens::rawrReduced(source.data(),w,h,order,64,parallel.data(),fit,8);
            check(result==parallel,"Thread count changes pixels");
            check(source==original,"Source changed");
        }
        for (uint16_t level: {uint16_t(0),uint16_t(64),uint16_t(1023)}) {
            std::fill(source.begin(),source.end(),level);
            rawlens::rawrReduced(source.data(),w,h,order,64,result.data(),false,8);
            check(std::all_of(result.begin(),result.end(),[=](auto v){return v==4*level;}),"Constant level not preserved");
        }
    }
    std::cout << "PASS: four CFA orders; upstream CPU parity, fit on/off, 1/8 threads, source retention, black/white and borders\n";
}
