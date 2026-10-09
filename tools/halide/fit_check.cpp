// SPDX-License-Identifier: GPL-3.0-or-later
// Host check for the BGU fit: fits a grid to a synthetic smooth pair, then
// verifies the fit's defining property with an independent scalar trilinear
// slice — slicing the grid at the training guide must reproduce the developed
// output. Also asserts grid finiteness and reports fit time.
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>

#include "HalideBuffer.h"
#include "bgu_fit.h"

using Halide::Runtime::Buffer;

static uint32_t rngState = 0xB6F17u;
static float nextUnit() {
    rngState = rngState * 1664525u + 1013904223u;
    return (rngState >> 8) * (1.0f / 16777216.0f);
}

int main(int argc, char **argv) {
    // Default: gate run (realistic low-res VF dims, production params, both
    // tripwired fixtures). With args: single tuning probe, no tripwires.
    int W = 68, H = 51;  // realistic low-res VF dims, odd extents
    int S = 16;
    float R = 1.0f / 8;
    float LAMBDA = 1e-6f;
    int onlyMode = -1;
    if (argc == 7) {
        W = atoi(argv[1]);
        H = atoi(argv[2]);
        S = atoi(argv[3]);
        R = (float)atof(argv[4]);
        LAMBDA = (float)atof(argv[5]);
        onlyMode = atoi(argv[6]);
    }
    const int GW = (W + S - 1) / S, GH = (H + S - 1) / S, GZ = (int)roundf(1.0f / R) + 1;

    // Three fixtures: an exactly-affine target (strict tripwires guard the
    // machinery), a gamma-ish curve with spatial grade (loose tripwires
    // guard approximation quality; dark-region curvature over 9 luma bins is
    // expected BGU behavior, cf. the paper's 27-33 dB on hard operators),
    // and a lens-like operator (Reinhard+gamma of a smooth radial field,
    // guide unlensed: the production RAW-branch structure, probe only).
    const char *names[3] = {"affine", "curve", "lenslike"};
    const double meanWire[3] = {2e-4, 1.5e-2, 1e-0};
    const double maxWire[3] = {2e-2, 8e-2, 1e-0};
    int firstMode = onlyMode >= 0 ? onlyMode : 0;
    int lastMode = onlyMode >= 0 ? onlyMode : 1;
    for (int mode = firstMode; mode <= lastMode; mode++) {
        Buffer<float> guide(W, H, 3), dev(W, H, 3);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                float r = (x + 0.5f) / W, g = (y + 0.5f) / H;
                float b = 0.5f + 0.5f * sinf((x * 0.7f + y * 1.3f) * 0.1f);
                float n0 = (nextUnit() - 0.5f) * 0.02f;
                float n1 = (nextUnit() - 0.5f) * 0.02f;
                float n2 = (nextUnit() - 0.5f) * 0.02f;
                guide(x, y, 0) = r + n0;
                guide(x, y, 1) = g + n1;
                guide(x, y, 2) = b + n2;
                float v[3] = {r + n0, g + n1, b + n2};
                float gain[3] = {1.1f, 1.0f, 0.9f};
                // Smooth radial field, 1.0 center to ~4x corners with mild
                // per-channel spread (logged production lens maps run
                // 1.0-5.4 over 17x17 cells).
                float dx = (x + 0.5f - W * 0.5f) / (W * 0.5f);
                float dy = (y + 0.5f - H * 0.5f) / (H * 0.5f);
                float dd = dx * dx + dy * dy;
                for (int ch = 0; ch < 3; ch++) {
                    float vv = v[ch] < 0 ? 0 : v[ch];
                    float graded;
                    if (mode == 0) {
                        graded = vv * gain[ch] + 0.02f * (ch + 1);
                    } else if (mode == 1) {
                        graded = powf(vv, 0.8f) * gain[ch] + 0.03f * sinf(x * 0.05f + ch);
                    } else {
                        float field = 1.0f + (2.0f + 0.5f * ch) * dd * 0.5f;
                        float lin = vv * field * 2.0f;
                        graded = powf(lin / (lin + 1.0f), 1.0f / 2.2f);
                    }
                    dev(x, y, ch) = graded;
                }
            }
        }

        Buffer<float> grid(GW, GH, GZ, 12);
        auto t0 = std::chrono::high_resolution_clock::now();
        int rc = bgu_fit(guide, dev, S, R, LAMBDA, grid);
        auto t1 = std::chrono::high_resolution_clock::now();
        double ms =
            std::chrono::duration_cast<std::chrono::microseconds>(t1 - t0).count() / 1000.0;
        if (rc != 0) {
            printf("FAIL %s: filter rc=%d\n", names[mode], rc);
            return 1;
        }

        int nonFinite = 0;
        grid.for_each_element([&](int x, int y, int z, int ch) {
            if (!std::isfinite(grid(x, y, z, ch))) nonFinite++;
        });
        if (nonFinite > 0) {
            printf("FAIL %s: %d non-finite grid coeffs\n", names[mode], nonFinite);
            return 1;
        }

    // Independent scalar slice at every training pixel.
    auto coeff = [&](int gx, int gy, int gz, int ch) -> float {
        gx = gx < 0 ? 0 : (gx >= GW ? GW - 1 : gx);
        gy = gy < 0 ? 0 : (gy >= GH ? GH - 1 : gy);
        gz = gz < 0 ? 0 : (gz >= GZ ? GZ - 1 : gz);
        return grid(gx, gy, gz, ch);
    };
    double sumAbs = 0;
    double maxAbs = 0;
    long n = 0;
    for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
            float r = guide(x, y, 0), g = guide(x, y, 1), b = guide(x, y, 2);
            float luma = 0.25f * r + 0.5f * g + 0.25f * b;
            if (luma < 0) luma = 0;
            if (luma > 1) luma = 1;
            float gx = (float)x / S, gy = (float)y / S, gz = luma / R;
            int x0 = (int)floorf(gx), y0 = (int)floorf(gy), z0 = (int)floorf(gz);
            float fx = gx - x0, fy = gy - y0, fz = gz - z0;
            for (int row = 0; row < 3; row++) {
                float m[4];
                for (int k = 0; k < 4; k++) {
                    int ch = row * 4 + k;
                    float c000 = coeff(x0, y0, z0, ch), c100 = coeff(x0 + 1, y0, z0, ch);
                    float c010 = coeff(x0, y0 + 1, z0, ch), c110 = coeff(x0 + 1, y0 + 1, z0, ch);
                    float c001 = coeff(x0, y0, z0 + 1, ch), c101 = coeff(x0 + 1, y0, z0 + 1, ch);
                    float c011 = coeff(x0, y0 + 1, z0 + 1, ch), c111 = coeff(x0 + 1, y0 + 1, z0 + 1, ch);
                    float c00 = c000 + (c100 - c000) * fx;
                    float c10 = c010 + (c110 - c010) * fx;
                    float c01 = c001 + (c101 - c001) * fx;
                    float c11 = c011 + (c111 - c011) * fx;
                    float c0 = c00 + (c10 - c00) * fy;
                    float c1 = c01 + (c11 - c01) * fy;
                    m[k] = c0 + (c1 - c0) * fz;
                }
                float got = m[0] * r + m[1] * g + m[2] * b + m[3];
                double err = fabs(got - dev(x, y, row));
                sumAbs += err;
                if (err > maxAbs) maxAbs = err;
                n++;
            }
        }
    }
        printf("fit %s %dx%dx%d grid in %.2f ms host; slice-reconstruction mean=%.3g max=%.3g\n",
               names[mode], GW, GH, GZ, ms, sumAbs / n, maxAbs);
        if (onlyMode >= 0) {
            // Sweep probe: machine-readable, never fails.
            printf("SWEEP %d %d %d %.4f %.2e %s %d %d %d %.2f %.6g %.6g\n",
                   W, H, S, R, (double)LAMBDA, names[mode], GW, GH, GZ, ms,
                   sumAbs / n, maxAbs);
        } else if (!(sumAbs / n < meanWire[mode]) || !(maxAbs < maxWire[mode])) {
            printf("FAIL %s: reconstruction error above tripwire\n", names[mode]);
            return 1;
        }
    }
    printf("fit check OK\n");
    return 0;
}
