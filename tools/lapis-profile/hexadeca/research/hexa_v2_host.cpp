// SPDX-License-Identifier: GPL-3.0-or-later
// Host (MoltenVK) harness for app/src/main/cpp/hexa/hexa_v2.cpp.
// hexa_v2_host in.raw16 W H out.rgb16 filter.spv point.spv tilesY tilesX [interior]  (box.spv next to point.spv)
#include "../../../../app/src/main/cpp/hexa/hexa_v2.h"
#include <fstream>
#include <iostream>
#include <iterator>
static std::vector<uint32_t> spv(const char* p) {
    std::ifstream f(p, std::ios::binary); std::vector<char> b((std::istreambuf_iterator<char>(f)), {});
    std::vector<uint32_t> w(b.size() / 4); memcpy(w.data(), b.data(), w.size() * 4); return w;
}
int main(int argc, char** argv) {
    if (argc < 9) { std::cerr << "usage\n"; return 2; }
    int W = atoi(argv[2]), H = atoi(argv[3]);
    std::vector<uint16_t> raw(size_t(W) * H);
    std::ifstream(argv[1], std::ios::binary).read(reinterpret_cast<char*>(raw.data()), raw.size() * 2);
    auto g = hexa::estimateGains(raw.data(), W, W, H, 64, 1023, atoi(argv[7]), atoi(argv[8]));
    std::vector<uint16_t> out(size_t(W / 2) * (H / 2) * 3);
    hexa::Timing t; std::string err;
    if (!hexa::convert(raw.data(), W, W, H, 64, 1023, g, spv(argv[5]), spv(argv[6]), spv((std::string(argv[6]).substr(0, std::string(argv[6]).rfind('/') + 1) + "hexa_box.spv").c_str()), out.data(), &t, &err, argc > 9 ? atoi(argv[9]) : 768)) {
        std::cerr << "FAIL " << err << "\n"; return 1;
    }
    std::ofstream(argv[4], std::ios::binary).write(reinterpret_cast<char*>(out.data()), out.size() * 2);
    std::cout << "{\"tiles\":" << t.tiles << ",\"gpuMs\":" << t.gpuMs << ",\"totalMs\":" << t.totalMs << "}\n";
}
