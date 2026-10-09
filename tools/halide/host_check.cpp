// SPDX-License-Identifier: GPL-3.0-or-later
// Host smoke check for the AOT spike filter: runs bgu_spike_downsample on a
// 16x16x3 u16 ramp with factor 8 and compares against an independent scalar
// loop. Catches generator/build/signature errors before any device work.
#include <cstdint>
#include <cstdio>

#include "HalideBuffer.h"
#include "bgu_spike_downsample.h"

int main() {
    const int W = 16, H = 16, C = 3, F = 8;
    Halide::Runtime::Buffer<uint16_t> in(W, H, C);
    in.for_each_element([&](int x, int y, int c) {
        in(x, y, c) = (uint16_t)(x + y * W + c * 1000);
    });
    Halide::Runtime::Buffer<uint16_t> out(W / F, H / F, C);
    if (bgu_spike_downsample(in, F, out) != 0) {
        printf("filter returned error\n");
        return 1;
    }
    int mismatches = 0;
    out.for_each_element([&](int x, int y, int c) {
        int sum = 0;
        for (int ry = 0; ry < F; ry++) {
            for (int rx = 0; rx < F; rx++) {
                sum += in(x * F + rx, y * F + ry, c);
            }
        }
        int expected = (sum + F * F / 2) / (F * F);
        if (out(x, y, c) != expected) {
            if (mismatches < 8) {
                printf("mismatch (%d,%d,%d): got %d want %d\n", x, y, c, out(x, y, c), expected);
            }
            mismatches++;
        }
    });
    if (mismatches != 0) {
        printf("FAILED: %d mismatches\n", mismatches);
        return 1;
    }
    printf("host check OK: 2x2x3 downsample matches (0,0,0)=%d\n", out(0, 0, 0));
    return 0;
}
