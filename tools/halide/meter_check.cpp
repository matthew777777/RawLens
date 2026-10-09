// SPDX-License-Identifier: GPL-3.0-or-later
// Host parity check for hdrplus_meter: the AOT filter must reproduce
// an independent scalar transcription of HdrPlusMotionMeter (stride-16
// maps + hot fraction) BITWISE on every output, plus the dense-cell
// maps per the generator's spec comment. Prints FNV-1a hashes so a
// third implementation (Python oracle) can cross-check the same cases.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

#include "HalideBuffer.h"
#include "hdrplus_meter.h"

namespace {

uint32_t pattern(int x, int y, uint32_t seed) {
    return (uint32_t)(((uint32_t)x * 73856093u) ^ ((uint32_t)y * 19349663u) ^ (seed * 83492791u)) & 0xFFFFu;
}

uint64_t fnv1a(const void *data, size_t len, uint64_t h) {
    const uint8_t *p = (const uint8_t *)data;
    for (size_t i = 0; i < len; i++) {
        h ^= p[i];
        h *= 1099511628211ull;
    }
    return h;
}

uint64_t bits(double v) {
    uint64_t u;
    memcpy(&u, &v, 8);
    return u;
}

// Scalar transcription of blockMismatchRatios + dense spec. Returns
// ratio/mad16/tex16/mad_dense/tex_dense; hot handled separately.
void scalar_case(const std::vector<uint16_t> &a, const std::vector<uint16_t> &b,
                 int W, int H, double range, std::vector<double> &ratio,
                 std::vector<double> &mad16, std::vector<double> &tex16,
                 std::vector<double> &mad_dense, std::vector<double> &tex_dense,
                 double &hot, double hot_ratio) {
    auto code = [&](const std::vector<uint16_t> &f, int x, int y) -> int { return f[(size_t)y * W + x]; };
    int gw = (W + 15) / 16, gh = (H + 15) / 16;
    int mx = (gw + 1) / 2, my = (gh + 1) / 2;
    ratio.assign((size_t)mx * my, 0.0);
    mad16.assign((size_t)mx * my, 0.0);
    tex16.assign((size_t)mx * my, 0.0);
    for (int by = 0; by < my; by++) {
        for (int bx = 0; bx < mx; bx++) {
            double mad_sum = 0.0, tex_sum = 0.0;
            int samples = 0, pairs = 0;
            for (int sy = 0; sy < 2; sy++) {
                int gy = by * 2 + sy;
                if (gy >= gh) continue;
                for (int sx = 0; sx < 2; sx++) {
                    int gx = bx * 2 + sx;
                    if (gx >= gw) continue;
                    int va = code(a, gx * 16, gy * 16);
                    int vb = code(b, gx * 16, gy * 16);
                    mad_sum += fabs((double)va - (double)vb) / range;
                    samples++;
                    if (sx + 1 < 2 && gx + 1 < gw) {
                        tex_sum += fabs((double)va - (double)code(a, (gx + 1) * 16, gy * 16)) / range;
                        tex_sum += fabs((double)vb - (double)code(b, (gx + 1) * 16, gy * 16)) / range;
                        pairs++;
                    }
                }
            }
            double mad = samples == 0 ? 0.0 : mad_sum / samples;
            double tex = pairs == 0 ? 0.0 : tex_sum / (2 * pairs);
            mad16[(size_t)by * mx + bx] = mad;
            tex16[(size_t)by * mx + bx] = tex;
            ratio[(size_t)by * mx + bx] = mad / (tex + 1e-9);
        }
    }
    int dx = (W + 31) / 32, dy = (H + 31) / 32;
    mad_dense.assign((size_t)dx * dy, 0.0);
    tex_dense.assign((size_t)dx * dy, 0.0);
    for (int cy = 0; cy < dy; cy++) {
        for (int cx = 0; cx < dx; cx++) {
            double dmad = 0.0, dtex = 0.0;
            int dn = 0, dpair = 0;
            for (int iy = 0; iy < 32; iy++) {
                for (int ix = 0; ix < 32; ix++) {
                    int x = cx * 32 + ix, y = cy * 32 + iy;
                    if (x >= W || y >= H) continue;
                    int va = code(a, x, y), vb = code(b, x, y);
                    dmad += fabs((double)va - (double)vb) / range;
                    dn++;
                    if (x + 1 < cx * 32 + 32 && x + 1 < W) {
                        dtex += fabs((double)va - (double)code(a, x + 1, y)) / range;
                        dtex += fabs((double)vb - (double)code(b, x + 1, y)) / range;
                        dpair++;
                    }
                }
            }
            mad_dense[(size_t)cy * dx + cx] = dmad / (dn == 0 ? 1 : dn);
            tex_dense[(size_t)cy * dx + cx] = dtex / (2 * (dpair == 0 ? 1 : dpair));
        }
    }
    int bnx = gw / 8, bny = gh / 8;
    if (bnx <= 0 || bny <= 0) {
        hot = 0.0;
        return;
    }
    int hot_count = 0, total = 0;
    for (int by = 0; by < bny; by++) {
        for (int bx = 0; bx < bnx; bx++) {
            double mad_sum = 0.0, sharp_sum = 0.0;
            for (int sy = 0; sy < 8; sy++) {
                int gy = by * 8 + sy;
                for (int sx = 0; sx < 8; sx++) {
                    int gx = bx * 8 + sx;
                    int va = code(a, gx * 16, gy * 16);
                    int vb = code(b, gx * 16, gy * 16);
                    mad_sum += fabs((double)va - (double)vb) / range;
                    if (sx + 1 < 8) {
                        sharp_sum += fabs((double)va - (double)code(a, (gx + 1) * 16, gy * 16)) / range;
                        sharp_sum += fabs((double)vb - (double)code(b, (gx + 1) * 16, gy * 16)) / range;
                    }
                }
            }
            if (mad_sum / 64 > hot_ratio * (sharp_sum / (2 * 8 * 7))) hot_count++;
            total++;
        }
    }
    hot = (double)hot_count / total;
}

int check_case(const std::vector<uint16_t> &pa, const std::vector<uint16_t> &pb,
               int W, int H, double range, double hot_ratio, uint64_t &hash) {
    using Halide::Runtime::Buffer;
    Buffer<uint16_t> prev(const_cast<uint16_t *>(pa.data()), W, H);
    Buffer<uint16_t> curr(const_cast<uint16_t *>(pb.data()), W, H);
    int mx = ((W + 15) / 16 + 1) / 2, my = ((H + 15) / 16 + 1) / 2;
    int dx = (W + 31) / 32, dy = (H + 31) / 32;
    Buffer<double> ratio(mx, my), mad16(mx, my), tex16(mx, my);
    Buffer<double> mad_dense(dx, dy), tex_dense(dx, dy);
    Buffer<double> hot(1);
    if (hdrplus_meter(prev, curr, range, hot_ratio, ratio, mad16, tex16,
                      mad_dense, tex_dense, hot) != 0) {
        printf("filter returned error\n");
        return 1;
    }
    std::vector<double> e_ratio, e_mad16, e_tex16, e_madd, e_texd;
    double e_hot = 0.0;
    scalar_case(pa, pb, W, H, range, e_ratio, e_mad16, e_tex16, e_madd,
                e_texd, e_hot, hot_ratio);
    int mism = 0;
    auto cmp = [&](Buffer<double> &got, std::vector<double> &want, const char *nm) {
        if ((int)want.size() != got.width() * got.height()) {
            printf("size mismatch %s: got %dx%d want %d\n", nm, got.width(),
                   got.height(), (int)want.size());
            mism++;
            return;
        }
        for (int y = 0; y < got.height(); y++) {
            for (int x = 0; x < got.width(); x++) {
                double g = got(x, y), w = want[(size_t)y * got.width() + x];
                if (bits(g) != bits(w)) {
                    if (mism < 8) {
                        printf("mismatch %s (%d,%d): got %a want %a\n", nm, x,
                               y, g, w);
                    }
                    mism++;
                }
            }
        }
    };
    cmp(ratio, e_ratio, "ratio");
    cmp(mad16, e_mad16, "mad16");
    cmp(tex16, e_tex16, "tex16");
    cmp(mad_dense, e_madd, "mad_dense");
    cmp(tex_dense, e_texd, "tex_dense");
    if (bits(hot(0)) != bits(e_hot)) {
        printf("mismatch hot: got %a want %a\n", hot(0), e_hot);
        mism++;
    }
    hash = fnv1a(ratio.data(), (size_t)ratio.width() * ratio.height() * 8, hash);
    hash = fnv1a(mad16.data(), (size_t)mad16.width() * mad16.height() * 8, hash);
    hash = fnv1a(tex16.data(), (size_t)tex16.width() * tex16.height() * 8, hash);
    hash = fnv1a(mad_dense.data(), (size_t)mad_dense.width() * mad_dense.height() * 8, hash);
    hash = fnv1a(tex_dense.data(), (size_t)tex_dense.width() * tex_dense.height() * 8, hash);
    hash = fnv1a(hot.data(), 8, hash);
    return mism;
}

}  // namespace

int main() {
    struct Shape {
        int w, h;
    };
    const Shape shapes[] = {{16, 16}, {17, 17},   {31, 31},  {32, 32},
                            {33, 33}, {48, 80},   {80, 48},  {64, 64},
                            {256, 192}, {512, 320}};
    const double ranges[] = {959.0, 1.0, 65535.0};
    uint64_t hash = 1469598103934665603ull;
    int total_mism = 0, cases = 0;
    for (const auto &sh : shapes) {
        for (double range : ranges) {
            std::vector<uint16_t> pa((size_t)sh.w * sh.h), pb((size_t)sh.w * sh.h);
            // Variant 0: shifted patterns (exercises MAD + texture).
            for (int y = 0; y < sh.h; y++) {
                for (int x = 0; x < sh.w; x++) {
                    pa[(size_t)y * sh.w + x] = (uint16_t)pattern(x, y, 0);
                    pb[(size_t)y * sh.w + x] = (uint16_t)pattern(x, y, 1);
                }
            }
            total_mism += check_case(pa, pb, sh.w, sh.h, range, 2.0, hash);
            cases++;
            // Variant 1: flat vs flat+1 (exercises strict edges: tex=0).
            for (int y = 0; y < sh.h; y++) {
                for (int x = 0; x < sh.w; x++) {
                    pa[(size_t)y * sh.w + x] = 1000;
                    pb[(size_t)y * sh.w + x] = (uint16_t)(1000 + ((x + y) % 2));
                }
            }
            total_mism += check_case(pa, pb, sh.w, sh.h, range, 2.0, hash);
            cases++;
        }
    }
    // Extremes + spike + hot_ratio path on one shape.
    {
        const int W = 128, H = 96;
        std::vector<uint16_t> pa((size_t)W * H), pb((size_t)W * H);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                pa[(size_t)y * W + x] = (uint16_t)(((x + y) % 2) ? 65535 : 0);
                pb[(size_t)y * W + x] = (uint16_t)(((x * y) % 2) ? 65535 : 0);
            }
        }
        pb[(size_t)3 * W + 5] = 65535;
        total_mism += check_case(pa, pb, W, H, 959.0, 1.0, hash);
        cases++;
    }
    printf("hash=%016llx cases=%d\n", (unsigned long long)hash, cases);
    if (total_mism != 0) {
        printf("FAILED: %d bitwise mismatches\n", total_mism);
        return 1;
    }
    printf("host parity OK: filter is bitwise identical to scalar reference\n");
    return 0;
}
