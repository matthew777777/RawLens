// SPDX-License-Identifier: GPL-3.0-or-later
// Host (macOS + MoltenVK) driver for app/src/main/cpp/rawdn.
// usage: rawdn_host <spv-dir> <in.u16> <w> <h> <cfa> <black> <white> <wbR> <wbB> <out.u16> [a b] [grain] [levels] [repeat] [iso store captureId]
// a = 0 (or omitted): noise from rawdn_choose_noise (learned model when iso + store are given, else the
// blind estimate). out = "-" only reports the noise (and trains the store).
#include "rawdn.h"
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <vector>

static std::vector<uint32_t> spv(const std::string& p) {
    std::ifstream f(p, std::ios::binary | std::ios::ate);
    if (!f) { std::fprintf(stderr, "missing %s\n", p.c_str()); std::exit(2); }
    std::vector<uint32_t> v(size_t(f.tellg()) / 4);
    f.seekg(0);
    f.read(reinterpret_cast<char*>(v.data()), std::streamsize(v.size() * 4));
    return v;
}

int main(int argc, char** argv) {
    if (argc < 11) { std::fprintf(stderr, "see header for usage\n"); return 2; }
    std::string dir = argv[1];
    int w = std::atoi(argv[3]), h = std::atoi(argv[4]);
    RawdnParams p;
    rawdn_default_params(&p);
    p.cfa = std::atoi(argv[5]);
    for (float& b : p.black) b = float(std::atof(argv[6]));
    p.white = float(std::atof(argv[7]));
    p.wb[0] = float(std::atof(argv[8]));
    p.wb[2] = float(std::atof(argv[9]));
    if (argc > 12) { p.noiseA = float(std::atof(argv[11])); p.noiseB = float(std::atof(argv[12])); }
    if (argc > 13) p.grain = float(std::atof(argv[13]));
    if (argc > 14) p.levels = std::atoi(argv[14]);
    int repeat = argc > 15 ? std::atoi(argv[15]) : 1;
    std::vector<uint16_t> img(size_t(w) * size_t(h)), out(img.size());
    std::ifstream f(argv[2], std::ios::binary);
    f.read(reinterpret_cast<char*>(img.data()), std::streamsize(img.size() * 2));
    if (!f) { std::fprintf(stderr, "short input\n"); return 2; }
    if (!(p.noiseA > 0)) {
        float iso = argc > 16 ? float(std::atof(argv[16])) : 0.f;
        const char* store = argc > 17 ? argv[17] : "";
        uint64_t cap = argc > 18 ? std::strtoull(argv[18], nullptr, 10) : 0;
        RawdnNoise nz;
        int rc = rawdn_choose_noise(img.data(), w, h, p.cfa, p.black, p.white, iso, 0.f, cap, store, &nz);
        std::printf("noise source=%d a=%.4g b=%.4g est_a=%.4g est_b=%.4g bins=%d est_weight=%.2f model_samples=%d model_isos=%d\n",
                    nz.source, nz.a, nz.b, nz.estA, nz.estB, nz.estBins, nz.estWeight, nz.modelSamples, nz.modelIsos);
        if (rc) return 3;
        p.noiseA = nz.a;
        p.noiseB = nz.b;
    }
    if (std::string(argv[10]) == "-") return 0;
    char err[256] = "";
    RawdnContext* c = rawdn_create(err, sizeof(err));
    if (!c) { std::fprintf(stderr, "create: %s\n", err); return 1; }
    static const char* names[RAWDN_SHADER_COUNT] = {"pack", "down", "dct", "resid", "fin", "unpack"};
    std::vector<uint32_t> code[RAWDN_SHADER_COUNT];
    const uint32_t* ptr[RAWDN_SHADER_COUNT];
    size_t bytes[RAWDN_SHADER_COUNT];
    for (int i = 0; i < RAWDN_SHADER_COUNT; i++) {
        code[i] = spv(dir + "/rawdn_" + names[i] + ".spv");
        ptr[i] = code[i].data();
        bytes[i] = code[i].size() * 4;
    }
    if (rawdn_load_shaders(c, ptr, bytes, err, sizeof(err))) { std::fprintf(stderr, "shaders: %s\n", err); return 1; }
    for (int i = 0; i < repeat; i++) {
        if (rawdn_process(c, img.data(), out.data(), w, h, &p, err, sizeof(err))) { std::fprintf(stderr, "process: %s\n", err); return 1; }
        std::printf("gpu_ms=%.1f\n", rawdn_last_gpu_ms(c));
    }
    rawdn_destroy(c);
    std::ofstream o(argv[10], std::ios::binary);
    o.write(reinterpret_cast<const char*>(out.data()), std::streamsize(out.size() * 2));
    return 0;
}
