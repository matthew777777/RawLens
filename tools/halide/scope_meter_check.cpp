// SPDX-License-Identifier: GPL-3.0-or-later
// Host parity check for scope_meter: the AOT filter must reproduce an
// independent scalar transcription of the four Kotlin samplers
// (RawHistogramSampler, RawWaveformSampler, RawEttrSampler.sampleGrid,
// RawFocusPeakingSampler energy/mean) BITWISE on every output. Covers
// all CFA layouts, white/black variants, LUT on/off, metering regions +
// guard, ETTR/PROGRAM densities, padded row strides, and flat/extreme/
// spike/stripe frames. Prints an FNV-1a hash so a third implementation
// can cross-check the same cases (human-verified, not asserted).
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

#include "HalideBuffer.h"
#include "scope_meter.h"

namespace {

constexpr int kHistBins = 64;
constexpr int kWaveCols = 96;
constexpr int kWaveLevels = 48;
constexpr int kEttrBins = 256;
constexpr int kFocusCols = 96;
constexpr int kLutSize = 1024;
// RawEttrSampler.SPOT_SCALE widened exactly as Kotlin does (Float -> Double).
const double kSpot = (double)0.158f;

uint32_t pattern(int x, int y, uint32_t seed) {
    return (uint32_t)(((uint32_t)x * 73856093u) ^ ((uint32_t)y * 19349663u) ^
                      (seed * 83492791u)) &
           0xFFFFu;
}

uint64_t fnv1a(const void *data, size_t len, uint64_t h) {
    const uint8_t *p = (const uint8_t *)data;
    for (size_t i = 0; i < len; i++) {
        h ^= p[i];
        h *= 1099511628211ull;
    }
    return h;
}

int colorAt(int cfa, int x, int y) {
    int ex = x & 1, ey = y & 1;
    switch (cfa) {
        case 0:
            return ey == 0 ? (ex == 0 ? 0 : 1) : (ex == 0 ? 1 : 2);
        case 1:
            return ey == 0 ? (ex == 0 ? 1 : 0) : (ex == 0 ? 2 : 1);
        case 2:
            return ey == 0 ? (ex == 0 ? 1 : 2) : (ex == 0 ? 0 : 1);
        default:
            return ey == 0 ? (ex == 0 ? 2 : 1) : (ex == 0 ? 1 : 0);
    }
}

int channelAt(int cfa, int x, int y) {
    int ex = x & 1, ey = y & 1;
    switch (cfa) {
        case 0:
            return ey == 0 ? (ex == 0 ? 0 : 1) : (ex == 0 ? 2 : 3);
        case 1:
            return ey == 0 ? (ex == 0 ? 1 : 0) : (ex == 0 ? 3 : 2);
        case 2:
            return ey == 0 ? (ex == 0 ? 1 : 3) : (ex == 0 ? 0 : 2);
        default:
            return ey == 0 ? (ex == 0 ? 3 : 1) : (ex == 0 ? 2 : 0);
    }
}

double zoneCoord(int v, int size) {
    return fabs(((double)v + 0.5) / (double)size * 2.0 - 1.0);
}

double weightForZone(double zone) {
    if (zone <= 0.20) return 500.0;
    if (zone <= 0.45) return 300.0;
    if (zone <= 0.70) return 200.0;
    return 50.0;
}

int clampInt(int v, int lo, int hi) {
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
}

// Host transcription of the Kotlin step formulas (sqrt truncation).
int scopeStepFor(int w, int h) {
    int bw = w / 2, bh = h / 2;
    double v = (double)bw * bh / 8000.0;
    if (v < 1.0) v = 1.0;
    int s = (int)sqrt(v);
    return s < 1 ? 1 : s;
}

int ettrStepFor(int rw, int rh, int target) {
    double v = (double)rw * rh / (double)target;
    if (v < 1.0) v = 1.0;
    int s = (int)sqrt(v);
    return s < 1 ? 1 : s;
}

// Host transcription of RawEttrSampler.meteringScanRegion.
void scanRegion(int w, int h, int metering, int *l, int *t, int *r, int *b) {
    double half = metering == 0 ? (double)(0.158f / 2)
                                : metering == 1 ? 0.35 : 0.5;
    *l = clampInt((int)((0.5 - half) * w), 0, w - 1);
    *t = clampInt((int)((0.5 - half) * h), 0, h - 1);
    *r = clampInt((int)((0.5 + half) * w), *l + 1, w);
    *b = clampInt((int)((0.5 + half) * h), *t + 1, h);
}

int rowsFor(int w, int h) {
    int rows = (int)(96L * h / w);
    return clampInt(rows, 24, 96);
}

struct Outputs {
    std::vector<int32_t> hist;    // 64 x 4, idx bin + 64*ch
    std::vector<int32_t> wave;    // 48 x 96 x 3, idx lvl + 48*(col+96*ch)
    std::vector<int32_t> ebins;   // 256 x 4
    std::vector<int32_t> ecounts;  // 4 x 2, idx ch + 4*row
    double egreen[4];
    std::vector<int32_t> gbins;  // 256 x 4
    std::vector<float> fenergy;  // 96 x R
    std::vector<float> fmean;    // 96 x R
};

void scalar_case(const std::vector<uint16_t> &data, int W, int H, int stride,
                 int cfa, const int black[4], int white, bool useLut,
                 const std::vector<float> &lut, int sstep, int estep, int el,
                 int et, int er, int eb, int gstep, bool doGuard, Outputs &o) {
    auto code = [&](int x, int y) -> int { return data[(size_t)y * stride + x]; };
    double invD[4];
    float invF[4];
    for (int i = 0; i < 4; i++) {
        int denom = white - black[i];
        if (denom < 1) denom = 1;
        invD[i] = 1.0 / (double)denom;
        invF[i] = 1.0f / (float)denom;
    }
    auto normD = [&](int v, int p) -> double {
        int d = v - black[p];
        if (d < 0) d = 0;
        double n = (double)d * invD[p];
        if (n < 0.0) n = 0.0;
        if (n > 1.0) n = 1.0;
        return n;
    };
    auto curved = [&](double n) -> double {
        if (!useLut) return n;
        int idx = (int)(n * 1023.0);
        idx = clampInt(idx, 0, kLutSize - 1);
        return (double)lut[idx];
    };

    // --- scope scan ---
    o.hist.assign(64 * 4, 0);
    o.wave.assign(48 * 96 * 3, 0);
    int bw = W / 2, bh = H / 2;
    for (int by = 0; by < bh; by += sstep) {
        for (int bx = 0; bx < bw; bx += sstep) {
            double quad[4];
            int qch[4];
            for (int dy = 0; dy < 2; dy++) {
                for (int dx = 0; dx < 2; dx++) {
                    int x = bx * 2 + dx, y = by * 2 + dy;
                    int ph = ((y & 1) << 1) | (x & 1);
                    double c = curved(normD(code(x, y), ph));
                    int bin = clampInt((int)(c * 63.0), 0, 63);
                    int col = colorAt(cfa, x, y);
                    o.hist[(size_t)col * 64 + bin]++;
                    int lvl = clampInt((int)(c * 47.0), 0, 47);
                    int fcol = (int)((int64_t)x * kWaveCols / W);
                    fcol = clampInt(fcol, 0, 95);
                    o.wave[(size_t)col * 96 * 48 + fcol * 48 + lvl]++;
                    quad[dy * 2 + dx] = c;
                    qch[dy * 2 + dx] = channelAt(cfa, x, y);
                }
            }
            double r = 0, gsum = 0, b = 0;
            for (int i = 0; i < 4; i++) {
                if (qch[i] == 0)
                    r = quad[i];
                else if (qch[i] == 1 || qch[i] == 2)
                    gsum += quad[i];
                else
                    b = quad[i];
            }
            double lum = 0.2126 * r + 0.7152 * (gsum / 2.0) + 0.0722 * b;
            o.hist[(size_t)3 * 64 + clampInt((int)(lum * 63.0), 0, 63)]++;
        }
    }

    // --- ETTR main scan ---
    o.ebins.assign(256 * 4, 0);
    o.ecounts.assign(4 * 2, 0);
    double wsum = 0, ww = 0, ssum = 0;
    long scnt = 0;
    int nx = (er - el + estep - 1) / estep;
    for (int y = et; y < eb; y += estep) {
        double nyZone = zoneCoord(y, H);
        int xi = 0;
        for (int x = el; x < er; x += estep, xi++) {
            int v = code(x, y);
            int ph = ((y & 1) << 1) | (x & 1);
            int d = v - black[ph];
            if (d < 0) d = 0;
            float n = (float)d * invF[ph];
            if (n < 0.0f) n = 0.0f;
            if (n > 1.0f) n = 1.0f;
            int ch = channelAt(cfa, x, y);
            if (v >= white) o.ecounts[(size_t)ch + 4 * 0]++;
            o.ebins[(size_t)ch * 256 + (int)(n * 255.0f)]++;
            o.ecounts[(size_t)ch + 4 * 1]++;
            if (ch == 1 || ch == 2) {
                int cx = el + xi * estep;
                if (cx > W - 1) cx = W - 1;
                double zone = zoneCoord(cx, W);
                if (nyZone > zone) zone = nyZone;
                double w = weightForZone(zone);
                wsum += w * (double)n;
                ww += w;
                if (zone <= kSpot) {
                    ssum += (double)n;
                    scnt++;
                }
            }
        }
        (void)nx;
    }
    o.egreen[0] = wsum;
    o.egreen[1] = ww;
    o.egreen[2] = ssum;
    o.egreen[3] = (double)scnt;

    // --- guard scan ---
    o.gbins.assign(256 * 4, 0);
    if (doGuard) {
        for (int y = 0; y < H; y += gstep) {
            for (int x = 0; x < W; x += gstep) {
                int v = code(x, y);
                int ph = ((y & 1) << 1) | (x & 1);
                int d = v - black[ph];
                if (d < 0) d = 0;
                float n = (float)d * invF[ph];
                if (n < 0.0f) n = 0.0f;
                if (n > 1.0f) n = 1.0f;
                o.gbins[(size_t)channelAt(cfa, x, y) * 256 +
                        (int)(n * 255.0f)]++;
            }
        }
    }

    // --- focus grid ---
    int rows = rowsFor(W, H);
    o.fenergy.assign((size_t)96 * rows, 0.0f);
    o.fmean.assign((size_t)96 * rows, 0.0f);
    bool sameParity = cfa == 1 || cfa == 2;
    for (int row = 0; row < rows; row++) {
        float cyf = ((float)row + 0.5f) * (float)H / (float)rows;
        int qy = clampInt((int)cyf / 2 * 2, 0, H - 8);
        for (int col = 0; col < 96; col++) {
            float cxf = ((float)col + 0.5f) * (float)W / 96.0f;
            int qx = clampInt((int)cxf / 2 * 2, 0, W - 8);
            float patch[16];
            float pmean = 0.0f;
            for (int ky = 0; ky < 4; ky++) {
                int y = qy + ky * 2;
                int x0 = qx + ((y & 1) ^ (sameParity ? 0 : 1));
                for (int kx = 0; kx < 4; kx++) {
                    int x = x0 + kx * 2;
                    int ph = ((y & 1) << 1) | (x & 1);
                    float n = (float)normD(code(x, y), ph);
                    patch[ky * 4 + kx] = n;
                    pmean += n;
                }
            }
            float sum = 0.0f;
            for (int ty = 1; ty <= 2; ty++) {
                for (int tx = 1; tx <= 2; tx++) {
                    float gx = patch[ty * 4 + tx + 1] - patch[ty * 4 + tx - 1];
                    float gy =
                        patch[(ty + 1) * 4 + tx] - patch[(ty - 1) * 4 + tx];
                    sum += gx * gx + gy * gy;
                }
            }
            o.fenergy[(size_t)row * 96 + col] = sum / 4.0f;
            o.fmean[(size_t)row * 96 + col] = pmean / 16.0f;
        }
    }
}

int check_case(const std::vector<uint16_t> &data, int W, int H, int stride,
               int cfa, const int black[4], int white, bool useLut,
               const std::vector<float> &lut, int sstep, int estep, int el,
               int et, int er, int eb, int gstep, bool doGuard,
               uint64_t &hash) {
    using Halide::Runtime::Buffer;
    halide_dimension_t shape[2] = {{0, W, 1}, {0, H, stride}};
    Buffer<uint16_t> raw(const_cast<uint16_t *>(data.data()), 2, shape);
    Buffer<float> lutBuf(const_cast<float *>(lut.data()), kLutSize);
    int rows = rowsFor(W, H);
    Buffer<int32_t> hist(64, 4), wave(48, 96, 3), ebins(256, 4),
        ecounts(4, 2), gbins(256, 4);
    Buffer<double> egreen(4);
    Buffer<float> fenergy(96, rows), fmean(96, rows);
    // Host reciprocals with the exact Kotlin expressions (IEEE-identical);
    // the scalar reference below uses the same values.
    double invD[4];
    float invF[4];
    for (int i = 0; i < 4; i++) {
        int denom = white - black[i];
        if (denom < 1) denom = 1;
        invD[i] = 1.0 / (double)denom;
        invF[i] = 1.0f / (float)denom;
    }
    int rc = scope_meter(raw, lutBuf, white, cfa, black[0], black[1], black[2],
                         black[3], invD[0], invD[1], invD[2], invD[3], invF[0],
                         invF[1], invF[2], invF[3], useLut ? 1 : 0, sstep,
                         estep, el, et, er, eb, gstep, doGuard ? 1 : 0, hist,
                         wave, ebins, ecounts, egreen, gbins, fenergy, fmean);
    if (rc != 0) {
        printf("filter returned error %d\n", rc);
        return 1;
    }
    Outputs o;
    scalar_case(data, W, H, stride, cfa, black, white, useLut, lut, sstep,
                estep, el, et, er, eb, gstep, doGuard, o);
    int mism = 0;
    auto cmp = [&](const void *got, const void *want, size_t bytes,
                   const char *nm) {
        if (memcmp(got, want, bytes) != 0) {
            printf("mismatch %s (%d bytes)\n", nm, (int)bytes);
            mism++;
        }
    };
    cmp(hist.data(), o.hist.data(), o.hist.size() * 4, "hist");
    cmp(wave.data(), o.wave.data(), o.wave.size() * 4, "wave");
    cmp(ebins.data(), o.ebins.data(), o.ebins.size() * 4, "ettr_bins");
    cmp(ecounts.data(), o.ecounts.data(), o.ecounts.size() * 4, "ettr_counts");
    cmp(egreen.data(), o.egreen, sizeof(o.egreen), "ettr_green");
    cmp(gbins.data(), o.gbins.data(), o.gbins.size() * 4, "guard_bins");
    cmp(fenergy.data(), o.fenergy.data(), o.fenergy.size() * 4, "focus_energy");
    cmp(fmean.data(), o.fmean.data(), o.fmean.size() * 4, "focus_mean");
    if (mism != 0) {
        // Detail the first float mismatches for triage.
        for (int i = 0; i < 4 && mism < 12; i++) {
            if (egreen(i) != o.egreen[i]) {
                printf("  egreen[%d]: got %a want %a\n", i, egreen(i),
                       o.egreen[i]);
                mism++;
            }
        }
        int shown = 0;
        for (int r = 0; r < rows && shown < 4; r++) {
            for (int c = 0; c < 96 && shown < 4; c++) {
                float g = fenergy(c, r), w = o.fenergy[(size_t)r * 96 + c];
                if (memcmp(&g, &w, 4) != 0) {
                    printf("  fenergy(%d,%d): got %a want %a\n", c, r, g, w);
                    shown++;
                }
                g = fmean(c, r);
                w = o.fmean[(size_t)r * 96 + c];
                if (memcmp(&g, &w, 4) != 0) {
                    printf("  fmean(%d,%d): got %a want %a\n", c, r, g, w);
                    shown++;
                }
            }
        }
        mism += shown;
    }
    hash = fnv1a(hist.data(), o.hist.size() * 4, hash);
    hash = fnv1a(wave.data(), o.wave.size() * 4, hash);
    hash = fnv1a(ebins.data(), o.ebins.size() * 4, hash);
    hash = fnv1a(ecounts.data(), o.ecounts.size() * 4, hash);
    hash = fnv1a(egreen.data(), sizeof(o.egreen), hash);
    hash = fnv1a(gbins.data(), o.gbins.size() * 4, hash);
    hash = fnv1a(fenergy.data(), o.fenergy.size() * 4, hash);
    hash = fnv1a(fmean.data(), o.fmean.size() * 4, hash);
    return mism;
}

void fill_pattern(std::vector<uint16_t> &data, int W, int H, int stride,
                  int variant) {
    for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
            uint16_t v;
            switch (variant) {
                case 0:
                    v = (uint16_t)pattern(x, y, 7);
                    break;
                case 1:
                    v = (uint16_t)(1000 + ((x + y) % 2) * 40);
                    break;
                case 2:
                    // Bayer-phase ramps: each channel gets its own range.
                    v = (uint16_t)(((x & 1) * 2 + (y & 1)) * 3000 +
                                   (x + y * 3) % 2000);
                    break;
                case 3:
                    v = (uint16_t)(((x + y) % 2) ? 65535 : 0);
                    break;
                case 4:
                    // Vertical stripes (focus energy) + horizontal band.
                    v = (uint16_t)(((x / 4) % 2 ? 3000 : 500) +
                                   ((y / 8) % 2 ? 1500 : 0));
                    break;
                default: {
                    uint32_t s = (uint32_t)(x * 1103515245u + y * 12345u +
                                            variant * 99991u);
                    s = s * 1103515245u + 12345u;
                    v = (uint16_t)((s >> 8) & 0xFFFFu);
                    break;
                }
            }
            data[(size_t)y * stride + x] = v;
        }
    }
}

}  // namespace

int main() {
    struct Shape {
        int w, h;
    };
    const Shape shapes[] = {{8, 8},    {9, 8},     {10, 9},   {16, 16},
                            {17, 13},  {31, 33},   {64, 48},  {97, 65},
                            {320, 240}, {640, 480}};
    const int whites[] = {1023, 4095, 16383, 65535};
    const int blacks[][4] = {{0, 0, 0, 0},
                             {64, 64, 64, 64},
                             {60, 64, 62, 70},
                             {1000, 1020, 1010, 1030}};
    std::vector<float> rampLut(kLutSize), curveLut(kLutSize),
        dummyLut(kLutSize, 0.0f);
    for (int i = 0; i < kLutSize; i++) {
        float t = (float)i / (kLutSize - 1);
        rampLut[i] = t;
        curveLut[i] = powf(t, 1.8f) * 0.9f + 0.05f * t;
    }
    uint64_t hash = 1469598103934665603ull;
    int total_mism = 0, cases = 0, serial = 0;
    for (const auto &sh : shapes) {
        int strides[4] = {sh.w, sh.w + 1, sh.w + 7, (sh.w + 31) & ~31};
        int nstrides = (sh.w * sh.h <= 97 * 65) ? 4 : 2;
        for (int si = 0; si < nstrides; si++) {
            int stride = strides[si];
            for (int variant = 0; variant < 3; variant++) {
                std::vector<uint16_t> data((size_t)stride * sh.h);
                fill_pattern(data, sh.w, sh.h, stride, variant);
                int cfa = (serial + variant) % 4;
                int white = whites[(serial / 2 + variant) % 4];
                const int *black = blacks[(serial + variant) % 4];
                bool useLut = ((serial + variant) % 2) == 0;
                const std::vector<float> &lut =
                    ((serial + variant) % 4) < 2 ? rampLut : curveLut;
                int metering = (serial + variant) % 3;  // 0 spot,1 center,2 avg
                int el, et, er, eb;
                scanRegion(sh.w, sh.h, metering, &el, &et, &er, &eb);
                int etarget = ((serial + variant) % 2) ? 16000 : 128000;
                total_mism += check_case(
                    data, sh.w, sh.h, stride, cfa, black, white, useLut,
                    useLut ? lut : dummyLut, scopeStepFor(sh.w, sh.h),
                    ettrStepFor(er - el, eb - et, etarget), el, et, er, eb,
                    ettrStepFor(sh.w, sh.h, 4096), metering != 2, hash);
                cases++;
                serial++;
            }
        }
    }
    // Extremes + hot-pixel spike on one shape, every CFA, both LUT modes.
    for (int cfa = 0; cfa < 4; cfa++) {
        for (int li = 0; li < 2; li++) {
            const int W = 64, H = 48, stride = 72;
            std::vector<uint16_t> data((size_t)stride * H);
            fill_pattern(data, W, H, stride, 3);
            data[(size_t)3 * stride + 5] = 65535;
            data[(size_t)40 * stride + 60] = 65535;
            const int black[4] = {64, 64, 64, 64};
            total_mism +=
                check_case(data, W, H, stride, cfa, black, 4095, li == 1,
                           li == 1 ? curveLut : dummyLut, scopeStepFor(W, H),
                           ettrStepFor(W, H, 128000), 0, 0, W, H,
                           ettrStepFor(W, H, 4096), false, hash);
            cases++;
        }
    }
    // Stripe fields for focus-energy coverage, cropped + full regions.
    for (int metering = 0; metering < 3; metering++) {
        const int W = 97, H = 65, stride = 104;
        std::vector<uint16_t> data((size_t)stride * H);
        fill_pattern(data, W, H, stride, 4);
        const int black[4] = {0, 0, 0, 0};
        int el, et, er, eb;
        scanRegion(W, H, metering, &el, &et, &er, &eb);
        total_mism += check_case(data, W, H, stride, 1, black, 4095, false,
                                 dummyLut, scopeStepFor(W, H),
                                 ettrStepFor(er - el, eb - et, 16000), el, et,
                                 er, eb, ettrStepFor(W, H, 4096),
                                 metering != 2, hash);
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
