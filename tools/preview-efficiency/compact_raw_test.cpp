// SPDX-License-Identifier: GPL-3.0-or-later
#include "vf_compact_raw.h"
#include <algorithm>
#include <cassert>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <random>
#include <vector>

static void parity() {
    std::mt19937 rng(104729);
    for (int trial = 0; trial < 2000; ++trial) {
        const int w = 1 + rng() % 50, h = 1 + rng() % 40;
        const int step = 2 + rng() % 16, left = rng() % 8, top = rng() % 8;
        const int pitch = left + (w - 1) * step + 2 + rng() % 17;
        const size_t end = (size_t(top + (h - 1) * step + 1) * pitch +
                            left + (w - 1) * step + 2) * 2;
        // Offset by one to exercise unaligned camera plane addresses, with
        // the last readable code exactly at the allocation boundary.
        std::vector<uint8_t> storage(end + 1);
        auto* src = storage.data() + 1;
        for (size_t i = 0; i < end; ++i) src[i] = uint8_t(rng());
        vfcompact::Plan p{};
        assert(vfcompact::plan(left, top, w, h, step, pitch, end, &p));
        std::vector<uint8_t> packed(p.bytes + 16, 0xa5);
        vfcompact::copy(src, packed.data(), p);
        for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
            // Check the raw codes addressed by every channel of the existing
            // shader before and after the native geometry remap.
            for (int site = 0; site < 4; ++site) {
                const size_t before = (size_t(top + y * step + site / 2) * pitch +
                                       left + x * step + site % 2) * 2;
                const size_t after = (size_t(y * 2 + site / 2) * w * 2 + x * 2 + site % 2) * 2;
                assert(std::memcmp(src + before, packed.data() + after, 2) == 0);
            }
        }
        for (size_t i = p.bytes; i < packed.size(); ++i) assert(packed[i] == 0xa5);
        assert(!vfcompact::plan(left, top, w, h, step, pitch, end - 1, &p));
    }
    vfcompact::Plan p{};
    assert(!vfcompact::plan(-1, 0, 1, 1, 2, 4, 32, &p));
    assert(!vfcompact::plan(0, -1, 1, 1, 2, 4, 32, &p));
    assert(!vfcompact::plan(0, 0, 0, 1, 2, 4, 32, &p));
    assert(!vfcompact::plan(0, 0, 1, 1, 1, 4, 32, &p));
    assert(!vfcompact::plan(3, 0, 1, 1, 2, 4, 32, &p));
    assert(!vfcompact::plan(0, 0, 4097, 1, 2, 16384, UINT64_MAX, &p));
    assert(!vfcompact::plan(INT32_MAX, INT32_MAX, 4096, 4096, INT32_MAX, 16384, UINT64_MAX, &p));
    std::puts("Compact RAW: 2000 randomized layouts, all sites, exact bounds PASS");
}

template<class F> static double medianMs(F task) {
    for (int i = 0; i < 10; ++i) task();
    std::vector<double> samples;
    for (int round = 0; round < 7; ++round) {
        const auto start = std::chrono::steady_clock::now();
        for (int i = 0; i < 30; ++i) task();
        samples.push_back(std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - start).count() / 30);
    }
    std::sort(samples.begin(), samples.end());
    return samples[3];
}

static void benchmark() {
    const int pitch = 4096, rawHeight = 3060;
    std::vector<uint8_t> source(size_t(pitch) * rawHeight * 2, 37), oldUpload(source.size());
    for (int step : {2, 4, 8, 16}) {
        const int w = 4080 / step, h = rawHeight / step;
        vfcompact::Plan p{};
        assert(vfcompact::plan(0, 0, w, h, step, pitch, source.size(), &p));
        std::vector<uint8_t> compact(p.bytes);
        const double before = medianMs([&] {
            std::memcpy(oldUpload.data(), source.data(), source.size());
            asm volatile("" : : "r"(oldUpload.data()) : "memory");
        });
        const double after = medianMs([&] {
            vfcompact::copy(source.data(), compact.data(), p);
            asm volatile("" : : "r"(compact.data()) : "memory");
        });
        std::printf("%dx%d step%d: staging %zu -> %zu bytes (%.2fx less), CPU %.4f -> %.4f ms (%.2fx)\n",
            w, h, step, source.size(), p.bytes, double(source.size()) / p.bytes, before, after, before / after);
    }
    std::puts("CPU heap benchmark only; excludes AHB lock, Vulkan mapping/flush, GPU and display.");
}

int main(int argc, char**) {
    parity();
    if (argc > 1) benchmark();
}
