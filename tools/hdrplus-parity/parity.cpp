// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Desktop parity runner for the HDR+ merge port (tools/hdrplus-parity).
// Runs the exact host + recorder sources the app ships (minus JNI) on
// desktop Vulkan and checks both merge paths on synthetic bursts:
//
// - identity: N identical frames merge back to the input (float rounding
//   only for spatial; + mild deconvolution gain for frequency),
// - denoise: noisy bursts merge closer to clean than any single frame,
// - determinism: repeated runs are bit-identical,
// - shift: a translated alternate still merges (exercises tile alignment
//   + warp end to end; multi-level geometry like production),
// - HQ per-pass (frequency_align_once=0, the upstream-exact schedule)
//   clears the same identity/shift bars as the align-once default,
// - every output sample is finite.
//
// Usage: hdrplus_parity <assets-dir>  (e.g. app/src/main/assets)
// Exit 0 iff every check passes.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "hdrplus_host.h"

namespace {

constexpr int kN = 4, kRef = kN / 2;
constexpr float kBlack = 64.0f, kWhite = 1023.0f;
constexpr float kStrength = 13.0f;

// Deterministic PRNG (xorshift64*).
struct Rng {
    std::uint64_t s;
    explicit Rng(std::uint64_t seed) : s(seed ? seed : 0x243f6a8885a308d3ull) {}
    std::uint64_t next() {
        s ^= s >> 12;
        s ^= s << 25;
        s ^= s >> 27;
        return s * 0x2545f4914f6cdd1dull;
    }
    double uniform() { return (next() >> 11) * (1.0 / 9007199254740992.0); }  // [0,1)
};

// Textured 10-bit-ish pattern spanning ~[120,880] DN: smoothed white
// noise (box radius 2), which like natural texture carries unique,
// non-periodic signal at every pyramid level. (Deliberately NOT sines or
// checkers: periodic patterns alias across pyramid levels, so coarse
// levels lock onto near-period ghost shifts — a test artifact, not a
// matcher bug. Verified: sines+blocks produced systematic (4,-8) locks.)
std::vector<std::uint16_t> makeClean(int w, int h) {
    Rng rng(0x5eed1234);
    std::vector<double> noise(w * h);
    for (auto& v : noise) v = rng.uniform();
    std::vector<std::uint16_t> img(w * h);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            double acc = 0.0;
            int n = 0;
            for (int dy = -2; dy <= 2; ++dy) {
                for (int dx = -2; dx <= 2; ++dx) {
                    const int sx = x + dx < 0 ? 0 : x + dx >= w ? w - 1 : x + dx;
                    const int sy = y + dy < 0 ? 0 : y + dy >= h ? h - 1 : y + dy;
                    acc += noise[sy * w + sx];
                    ++n;
                }
            }
            img[y * w + x] = static_cast<std::uint16_t>(std::lround(500.0 + 760.0 * (acc / n - 0.5)));
        }
    }
    return img;
}

// Smooth low-frequency gradient for the denoise checks: with no
// high-frequency content, mismatch vanishes and both paths reduce to
// near-perfect temporal averaging (the Wiener operating point on
// noise-textured inputs is conservative by design — texture-level
// detail is preserved rather than averaged — so textured patterns
// cannot pin the averaging gain).
std::vector<std::uint16_t> makeSmooth(int w, int h) {
    std::vector<std::uint16_t> img(w * h);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const double v = 500.0 + 300.0 * std::sin(x * 0.02) * std::sin(y * 0.017);
            img[y * w + x] = static_cast<std::uint16_t>(std::lround(v));
        }
    }
    return img;
}

std::vector<std::uint16_t> addNoise(const std::vector<std::uint16_t>& clean, double amplitude,
                                    std::uint64_t seed) {
    Rng rng(seed);
    std::vector<std::uint16_t> img = clean;
    for (auto& v : img) {
        const double n = (rng.uniform() * 2.0 - 1.0) * amplitude;
        const long r = std::lround(static_cast<double>(v) + n);
        v = static_cast<std::uint16_t>(r < 0 ? 0 : r > 65535 ? 65535 : r);
    }
    return img;
}

// Translate by (dx,dy) with edge clamping (borders excluded from metrics).
std::vector<std::uint16_t> shiftImage(const std::vector<std::uint16_t>& clean, int w, int h, int dx,
                                      int dy) {
    std::vector<std::uint16_t> img(w * h);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const int sx = x + dx < 0 ? 0 : x + dx >= w ? w - 1 : x + dx;
            const int sy = y + dy < 0 ? 0 : y + dy >= h ? h - 1 : y + dy;
            img[y * w + x] = clean[sy * w + sx];
        }
    }
    return img;
}

std::vector<float> normalize(const std::vector<std::uint16_t>& img) {
    std::vector<float> out(img.size());
    for (size_t i = 0; i < img.size(); ++i) out[i] = (img[i] - kBlack) / (kWhite - kBlack);
    return out;
}

bool allFinite(const std::vector<float>& v) {
    for (float x : v) {
        if (!std::isfinite(x)) return false;
    }
    return true;
}

// PSNR over the full frame (peak 1.0, normalized domain); +inf when exact.
double psnr(const std::vector<float>& a, const std::vector<float>& b) {
    double sse = 0.0;
    for (size_t i = 0; i < a.size(); ++i) {
        const double d = static_cast<double>(a[i]) - b[i];
        sse += d * d;
    }
    if (sse == 0.0) return 1e9;
    return -10.0 * std::log10(sse / a.size());
}

// PSNR over the center crop (margin excluded on all sides).
double psnrCrop(const std::vector<float>& a, const std::vector<float>& b, int w, int h,
                int margin) {
    double sse = 0.0;
    long n = 0;
    for (int y = margin; y < h - margin; ++y) {
        for (int x = margin; x < w - margin; ++x) {
            const double d = static_cast<double>(a[y * w + x]) - b[y * w + x];
            sse += d * d;
            ++n;
        }
    }
    if (sse == 0.0) return 1e9;
    return -10.0 * std::log10(sse / n);
}

struct RunResult {
    bool ok = false;
    std::vector<float> out;
    double gpuMs = 0.0;
    std::string err;
};

RunResult runMerge(HdrPlusContext* ctx, const std::vector<std::vector<std::uint16_t>>& frames, int w,
                   int h, bool highQuality, bool alignOnce = true) {
    RunResult r;
    r.out.resize(w * h);
    std::vector<const std::uint16_t*> ptrs;
    for (const auto& f : frames) ptrs.push_back(f.data());
    std::vector<float> blacks(frames.size() * 4, kBlack), whites(frames.size(), kWhite);
    HdrPlusParams params{};
    hdrplus_default_params(&params);
    params.strength = kStrength;
    params.high_quality = highQuality ? 1 : 0;
    params.frequency_align_once = alignOnce ? 1 : 0;
    char errmsg[512] = {};
    const int rc = hdrplus_merge(ctx, ptrs.data(), blacks.data(), whites.data(),
                                 static_cast<int>(frames.size()), w, h, kRef, nullptr, 0, &params,
                                 r.out.data(), errmsg, sizeof(errmsg));
    if (rc != 0) {
        r.err = errmsg;
        return r;
    }
    r.ok = true;
    r.gpuMs = hdrplus_last_gpu_ms(ctx);
    return r;
}

int failures = 0;
void check(bool cond, const char* name, const std::string& detail = "") {
    std::printf("%-28s %s  %s\n", name, cond ? "PASS" : "FAIL", detail.c_str());
    if (!cond) ++failures;
}
char psnrBuf[64];
const char* db(double v) {
    std::snprintf(psnrBuf, sizeof(psnrBuf), "%.1f dB", v > 1e8 ? 999.0 : v);
    return psnrBuf;
}

}  // namespace

int main(int argc, char** argv) {
    // Optional second arg restricts the run (for HDRPLUS_DUMP_DIR debugging,
    // where dump files reflect the last merge): "fast" or "hq" runs only
    // that path's checks; "shift-fast"/"shift-hq" only the shift check.
    std::string only;
    if (argc == 3) only = argv[2];
    if (argc != 2 && argc != 3) {
        std::fprintf(stderr, "usage: %s <assets-dir> [fast|hq|shift-fast|shift-hq]\n", argv[0]);
        return 2;
    }
    char errmsg[512] = {};
    HdrPlusContext* ctx = hdrplus_create(errmsg, sizeof(errmsg));
    if (ctx == nullptr) {
        std::fprintf(stderr, "create failed: %s\n", errmsg);
        return 2;
    }
    const int loaded = hdrplus_load_shaders(ctx, argv[1], errmsg, sizeof(errmsg));
    if (loaded != hdrplus_shader_count()) {
        std::fprintf(stderr, "shader load failed: %s\n", errmsg);
        hdrplus_destroy(ctx);
        return 2;
    }
    std::printf("shaders: %d loaded\n", loaded);

    // Small geometry for the cheap checks (single pyramid level).
    constexpr int kSmall = 128;
    const auto cleanSmall = makeClean(kSmall, kSmall);
    const auto cleanSmallNorm = normalize(cleanSmall);
    std::vector<std::vector<std::uint16_t>> identicalSmall(kN, cleanSmall);
    const auto smoothSmall = makeSmooth(kSmall, kSmall);
    const auto smoothSmallNorm = normalize(smoothSmall);
    std::vector<std::vector<std::uint16_t>> noisySmall;
    for (int i = 0; i < kN; ++i) noisySmall.push_back(addNoise(smoothSmall, 3.0, 1000 + i));
    const double singleSmallPsnr = psnr(normalize(noisySmall[kRef]), smoothSmallNorm);
    std::printf("single-frame baseline: %s (ref frame vs clean)\n", db(singleSmallPsnr));

    const bool wantFast = only.empty() || only == "fast" || only == "shift-fast";
    const bool wantHq = only.empty() || only == "hq" || only == "shift-hq";
    const bool shiftOnly = only == "shift-fast" || only == "shift-hq";
    for (const bool hq : {false, true}) {
        const char* tag = hq ? "HQ" : "Fast";
        if (hq && !wantHq) continue;
        if (!hq && !wantFast) continue;
        if (shiftOnly) continue;  // shift checks run in the block below

        // Identity.
        RunResult id = runMerge(ctx, identicalSmall, kSmall, kSmall, hq);
        char detail[128];
        if (!id.ok) {
            check(false, (std::string(tag) + " identity runs").c_str(), id.err);
        } else {
            std::snprintf(detail, sizeof(detail), "%s gpu=%.1fms", db(psnr(id.out, cleanSmallNorm)),
                          id.gpuMs);
            check(allFinite(id.out), (std::string(tag) + " identity finite").c_str(), detail);
            // Spatial must be float-rounding only (~140 dB); frequency adds a
            // mild deconvolution gain, so its bar is lower (still >> visible).
            check(psnr(id.out, cleanSmallNorm) >= (hq ? 50.0 : 80.0),
                  (std::string(tag) + " identity PSNR").c_str(), detail);
        }

        // Denoise: the merge must beat the single noisy frame by >2 dB.
        RunResult dn = runMerge(ctx, noisySmall, kSmall, kSmall, hq);
        if (!dn.ok) {
            check(false, (std::string(tag) + " denoise runs").c_str(), dn.err);
        } else {
            const double m = psnr(dn.out, smoothSmallNorm);
            std::snprintf(detail, sizeof(detail), "%s (gain %+.1f dB) gpu=%.1fms", db(m),
                          m - singleSmallPsnr, dn.gpuMs);
            check(allFinite(dn.out), (std::string(tag) + " denoise finite").c_str(), detail);
            check(m >= 45.0 && m - singleSmallPsnr >= 2.0,
                  (std::string(tag) + " denoise gain").c_str(), detail);
        }

        // Determinism: same input twice -> bitwise identical output.
        RunResult again = runMerge(ctx, noisySmall, kSmall, kSmall, hq);
        if (!again.ok) {
            check(false, (std::string(tag) + " rerun").c_str(), again.err);
        } else {
            const bool same =
                dn.ok && again.out.size() == dn.out.size() &&
                std::memcmp(again.out.data(), dn.out.data(), dn.out.size() * sizeof(float)) == 0;
            check(same, (std::string(tag) + " determinism").c_str(), "");
        }
    }

    // Shifted alternates (+2px x, +2px y, clamp edges) at production-like
    // multi-level geometry (512px -> 3 pyramid levels, 15x15 tiles). Border
    // tiles legitimately misalign (correct shift samples outside the frame
    // and upstream penalizes that); the center crop covers interior tiles
    // only. A correct alignment recovers the clean center; a broken
    // warp/alignment ghosts and fails the bar by ~20 dB.
    constexpr int kBig = 512, kMargin = 80;
    const auto cleanBig = makeClean(kBig, kBig);
    const auto cleanBigNorm = normalize(cleanBig);
    for (const bool hq : {false, true}) {
        const char* tag = hq ? "HQ" : "Fast";
        if (hq && !wantHq) continue;
        if (!hq && !wantFast) continue;
        std::vector<std::vector<std::uint16_t>> shifted;
        for (int i = 0; i < kN; ++i) {
            shifted.push_back(i == kRef ? cleanBig : shiftImage(cleanBig, kBig, kBig, 2, 2));
        }
        RunResult sh = runMerge(ctx, shifted, kBig, kBig, hq);
        if (!sh.ok) {
            check(false, (std::string(tag) + " shift runs").c_str(), sh.err);
        } else {
            const double m = psnrCrop(sh.out, cleanBigNorm, kBig, kBig, kMargin);
            char detail[128];
            std::snprintf(detail, sizeof(detail), "%s (center crop) gpu=%.1fms", db(m), sh.gpuMs);
            check(allFinite(sh.out), (std::string(tag) + " shift finite").c_str(), detail);
            check(m >= 40.0, (std::string(tag) + " shift recovery").c_str(), detail);
        }
    }

    // HQ per-pass mode (frequency_align_once=0) is the upstream-exact
    // alignment schedule (align every pass, upstream warp weights); it must
    // clear the same identity/shift bars as the align-once default.
    if (wantHq && !shiftOnly) {
        RunResult id = runMerge(ctx, identicalSmall, kSmall, kSmall, true, false);
        char detail[128];
        if (!id.ok) {
            check(false, "HQ-perpass identity runs", id.err);
        } else {
            std::snprintf(detail, sizeof(detail), "%s gpu=%.1fms", db(psnr(id.out, cleanSmallNorm)),
                          id.gpuMs);
            check(allFinite(id.out), "HQ-perpass identity finite", detail);
            check(psnr(id.out, cleanSmallNorm) >= 50.0, "HQ-perpass identity PSNR", detail);
        }
    }
    if (wantHq) {
        std::vector<std::vector<std::uint16_t>> shifted;
        for (int i = 0; i < kN; ++i) {
            shifted.push_back(i == kRef ? cleanBig : shiftImage(cleanBig, kBig, kBig, 2, 2));
        }
        RunResult sh = runMerge(ctx, shifted, kBig, kBig, true, false);
        if (!sh.ok) {
            check(false, "HQ-perpass shift runs", sh.err);
        } else {
            const double m = psnrCrop(sh.out, cleanBigNorm, kBig, kBig, kMargin);
            char detail[128];
            std::snprintf(detail, sizeof(detail), "%s (center crop) gpu=%.1fms", db(m), sh.gpuMs);
            check(allFinite(sh.out), "HQ-perpass shift finite", detail);
            check(m >= 40.0, "HQ-perpass shift recovery", detail);
        }
    }

    hdrplus_destroy(ctx);
    std::printf(failures == 0 ? "PARITY: ALL CHECKS PASSED\n" : "PARITY: %d CHECK(S) FAILED\n",
                failures);
    return failures == 0 ? 0 : 1;
}
