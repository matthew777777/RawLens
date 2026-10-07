// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Desktop HDR+ burst runner: merges a real DNG burst through the exact
// host + recorder sources the app ships (minus JNI) on desktop Vulkan,
// and writes the merged CFA as a 16-bit normalized DNG (same layout as
// the app's HdrPlusDngWriter: black 0, white 65535, reference frame's
// color metadata). This is the RAWR-style workflow: point it at a burst
// directory and get a merged DNG back.
//
// Usage:
//   hdrplus_burst <assets-dir> <out.dng> [options] <frame0.dng> [frame1.dng ...]
// Output is 16-bit normalized, like the app's HdrPlusDngWriter.
// Options:
//   --hq              frequency ("Higher quality") merge instead of spatial
//   --tile-size 16|32 (default 32)
//   --search-distance 32|64|128 (default 64)
//   --ref <i>         reference frame index (default: middle)
//   --strength <f>    noise-reduction strength 1..22 (default 13)
//   --crop x,y,w,h    merge a crop only (x/y must be even: CFA phase)
//
// Frames must be single-image uncompressed/LJPEG Bayer DNGs with identical
// geometry and CFA pattern. Exit 0 on success.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "hdrplus_host.h"
#include "tinydng.h"

namespace {

void usage(const char* argv0) {
    std::fprintf(stderr,
                 "usage: %s <assets-dir> <out.dng> [--hq] [--ref i] [--strength f]\n"
                 "             [--tile-size 16|32] [--search-distance 32|64|128]\n"
                 "             [--crop x,y,w,h] <frame0.dng> [frame1.dng ...]\n",
                 argv0);
}

struct Frame {
    std::vector<std::uint16_t> pixels;
    float black[4];
    float white;
};

bool sameCfa(const tinydng_cfa& a, const tinydng_cfa& b) {
    return a.present && b.present && a.pattern_dim[0] == b.pattern_dim[0] &&
           a.pattern_dim[1] == b.pattern_dim[1] && a.pattern_size == b.pattern_size &&
           std::memcmp(a.pattern, b.pattern, a.pattern_size) == 0 &&
           a.plane_color_count == b.plane_color_count &&
           std::memcmp(a.plane_color, b.plane_color, a.plane_color_count) == 0;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 4) {
        usage(argv[0]);
        return 2;
    }
    const char* assetsDir = argv[1];
    const char* outPath = argv[2];
    bool highQuality = false;
    int refIndex = -1;
    float strength = 13.0f;
    int tileSize = 32;
    int searchDistance = 64;
    bool doCrop = false;
    int cropX = 0, cropY = 0, cropW = 0, cropH = 0;
    std::vector<const char*> inputs;
    for (int i = 3; i < argc; ++i) {
        if (std::strcmp(argv[i], "--hq") == 0) {
            highQuality = true;
        } else if (std::strcmp(argv[i], "--ref") == 0 && i + 1 < argc) {
            refIndex = std::atoi(argv[++i]);
        } else if (std::strcmp(argv[i], "--strength") == 0 && i + 1 < argc) {
            strength = static_cast<float>(std::atof(argv[++i]));
        } else if (std::strcmp(argv[i], "--tile-size") == 0 && i + 1 < argc) {
            tileSize = std::atoi(argv[++i]);
            if (tileSize != 16 && tileSize != 32) {
                std::fprintf(stderr, "bad --tile-size (want 16 or 32)\n");
                return 2;
            }
        } else if (std::strcmp(argv[i], "--search-distance") == 0 && i + 1 < argc) {
            searchDistance = std::atoi(argv[++i]);
            if (searchDistance != 32 && searchDistance != 64 && searchDistance != 128) {
                std::fprintf(stderr, "bad --search-distance (want 32, 64 or 128)\n");
                return 2;
            }
        } else if (std::strcmp(argv[i], "--crop") == 0 && i + 1 < argc) {
            if (std::sscanf(argv[++i], "%d,%d,%d,%d", &cropX, &cropY, &cropW, &cropH) != 4 ||
                (cropX & 1) || (cropY & 1) || (cropW & 1) || (cropH & 1) || cropW <= 0 ||
                cropH <= 0) {
                std::fprintf(stderr, "bad --crop (want even x,y,w,h)\n");
                return 2;
            }
            doCrop = true;
        } else if (argv[i][0] == '-' && argv[i][1] == '-') {
            std::fprintf(stderr, "unknown option %s\n", argv[i]);
            return 2;
        } else {
            inputs.push_back(argv[i]);
        }
    }
    if (inputs.size() < 2 || inputs.size() > 64) {
        std::fprintf(stderr, "need 2..64 input frames, got %zu\n", inputs.size());
        return 2;
    }
    if (refIndex < 0) refIndex = static_cast<int>(inputs.size()) / 2;
    if (refIndex >= static_cast<int>(inputs.size())) {
        std::fprintf(stderr, "ref %d out of range\n", refIndex);
        return 2;
    }

    tinydng_context* tctx = tinydng_context_create(nullptr, nullptr);
    if (tctx == nullptr) {
        std::fprintf(stderr, "tinydng context create failed\n");
        return 2;
    }
    // Docs stay alive until after the write: the output reuses the
    // reference frame's CFA + raw metadata structs.
    std::vector<tinydng_document*> docs(inputs.size(), nullptr);
    std::vector<Frame> frames(inputs.size());
    int width = 0, height = 0;
    tinydng_cfa cfa0{};
    bool cfa0set = false;
    for (size_t i = 0; i < inputs.size(); ++i) {
        tinydng_error err{};
        if (tinydng_open_file(tctx, inputs[i], nullptr, &docs[i], &err) != 0 || docs[i] == nullptr) {
            std::fprintf(stderr, "open %s failed: %s\n", inputs[i], err.message);
            return 2;
        }
        const tinydng_image_info* info = tinydng_image_get(docs[i], 0);
        if (info == nullptr || info->samples_per_pixel != 1 || !info->cfa.present ||
            info->bits_per_sample_decoded != 16) {
            std::fprintf(stderr, "%s: not a 16-bit mono Bayer DNG\n", inputs[i]);
            return 2;
        }
        if (!cfa0set) {
            width = info->width;
            height = info->height;
            cfa0 = info->cfa;
            cfa0set = true;
        } else if (static_cast<int>(info->width) != width || static_cast<int>(info->height) != height ||
                   !sameCfa(info->cfa, cfa0)) {
            std::fprintf(stderr, "%s: geometry/CFA mismatch\n", inputs[i]);
            return 2;
        }
        tinydng_pixels px{};
        tinydng_decode_options dopts{};
        if (tinydng_decode_image(tctx, docs[i], 0, &dopts, &px, &err) != 0) {
            std::fprintf(stderr, "decode %s failed: %s\n", inputs[i], err.message);
            return 2;
        }
        if (px.width != info->width || px.height != info->height || px.size < width * height * 2u) {
            std::fprintf(stderr, "%s: unexpected decoded size\n", inputs[i]);
            return 2;
        }
        Frame& fr = frames[i];
        fr.pixels.assign(reinterpret_cast<std::uint16_t*>(px.data),
                         reinterpret_cast<std::uint16_t*>(px.data) + width * height);
        tinydng_pixels_free(tctx, &px);
        for (int k = 0; k < 4; ++k) fr.black[k] = static_cast<float>(info->raw.black_level[k]);
        fr.white = info->raw.white_level_present ? static_cast<float>(info->raw.white_level[0])
                                                 : 65535.0f;
        std::printf("frame %zu: %s black=[%.2f %.2f %.2f %.2f] white=%.0f\n", i, inputs[i],
                    fr.black[0], fr.black[1], fr.black[2], fr.black[3], fr.white);
    }
    if (doCrop) {
        if (cropX + cropW > width || cropY + cropH > height) {
            std::fprintf(stderr, "crop outside frame\n");
            return 2;
        }
        for (auto& fr : frames) {
            std::vector<std::uint16_t> c(cropW * cropH);
            for (int y = 0; y < cropH; ++y) {
                std::memcpy(&c[y * cropW], &fr.pixels[(cropY + y) * width + cropX],
                            cropW * sizeof(std::uint16_t));
            }
            fr.pixels.swap(c);
        }
        width = cropW;
        height = cropH;
    }
    std::printf("geometry %dx%d frames=%zu ref=%d path=%s strength=%.1f tile=%d search=%d\n",
                width, height, frames.size(), refIndex, highQuality ? "HQ-frequency" : "Fast-spatial",
                strength, tileSize, searchDistance);

    char herrmsg[512] = {};
    HdrPlusContext* hctx = hdrplus_create(herrmsg, sizeof(herrmsg));
    if (hctx == nullptr) {
        std::fprintf(stderr, "hdrplus_create failed: %s\n", herrmsg);
        return 2;
    }
    const int loaded = hdrplus_load_shaders(hctx, const_cast<char*>(assetsDir), herrmsg, sizeof(herrmsg));
    if (loaded != hdrplus_shader_count()) {
        std::fprintf(stderr, "shader load failed: %s\n", herrmsg);
        return 2;
    }
    std::vector<const std::uint16_t*> ptrs;
    std::vector<float> blacks, whites;
    for (const auto& fr : frames) {
        ptrs.push_back(fr.pixels.data());
        blacks.insert(blacks.end(), fr.black, fr.black + 4);
        whites.push_back(fr.white);
    }
    HdrPlusParams params{};
    hdrplus_default_params(&params);
    params.strength = strength;
    params.tile_size = static_cast<uint32_t>(tileSize);
    params.search_distance = static_cast<uint32_t>(searchDistance);
    params.high_quality = highQuality ? 1 : 0;
    std::vector<float> out(width * height);
    const int rc = hdrplus_merge(hctx, ptrs.data(), blacks.data(), whites.data(),
                                 static_cast<int>(frames.size()), width, height, refIndex, nullptr,
                                 0, &params, out.data(), herrmsg, sizeof(herrmsg));
    if (rc != 0) {
        std::fprintf(stderr, "merge failed: %s\n", herrmsg);
        return 1;
    }
    const double gpuMs = hdrplus_last_gpu_ms(hctx);
    hdrplus_destroy(hctx);
    double lo = out[0], hi = out[0], sum = 0.0;
    long nonFinite = 0;
    for (float v : out) {
        if (!std::isfinite(v)) {
            ++nonFinite;
            continue;
        }
        if (v < lo) lo = v;
        if (v > hi) hi = v;
        sum += v;
    }
    std::printf("merged: gpu=%.1fms range=[%.4f %.4f] mean=%.4f nonfinite=%ld\n", gpuMs, lo, hi,
                sum / out.size(), nonFinite);

    // Write the merged CFA as 16-bit normalized (black 0 / white 65535),
    // inheriting the reference frame's color metadata; merged noise differs
    // from any single frame, so drop the reference noise profile like the
    // app. Quantization mirrors LinearRgbDngWriter.quantize exactly:
    // clamp[0,1] * 65535 in double, round half up, clamp to [0,65535].
    // Non-finite samples (which fail the run below) quantize by clamp
    // semantics: +inf -> 65535, anything else -> 0.
    std::vector<std::uint16_t> q(out.size());
    for (size_t i = 0; i < out.size(); ++i) {
        const float v = out[i];
        if (!std::isfinite(v)) {
            q[i] = v > 0.0f ? 65535 : 0;
            continue;
        }
        const double clamped = v < 0.0f ? 0.0 : v > 1.0f ? 1.0 : v;
        const long r = std::lround(clamped * 65535.0);
        q[i] = static_cast<std::uint16_t>(r < 0 ? 0 : r > 65535 ? 65535 : r);
    }
    const tinydng_image_info* refInfo = tinydng_image_get(docs[refIndex], 0);
    tinydng_raw_info rawOut = refInfo->raw;
    rawOut.black_level[0] = rawOut.black_level[1] = rawOut.black_level[2] = rawOut.black_level[3] = 0;
    rawOut.black_level_present = 1;
    rawOut.white_level[0] = rawOut.white_level[1] = rawOut.white_level[2] = rawOut.white_level[3] =
        65535;
    rawOut.white_level_present = 1;
    rawOut.noise_profile_count = 0;
    char desc[256];
    std::snprintf(desc, sizeof(desc),
                  "RawLens HDR+ desktop merge (%s, %zu frames, ref %d, strength %.1f, tile %d, "
                  "search %d)",
                  highQuality ? "frequency" : "spatial", frames.size(), refIndex, strength,
                  tileSize, searchDistance);
    // Pass the camera identity through (RawSpeed/darktable need Make for
    // camera ID; tinydng does not parse UniqueCameraModel, so that one
    // stays absent rather than guessed).
    tinydng_field fields[3];
    size_t fieldCount = 0;
    auto asciiField = [&](uint16_t tag, const char* s) {
        fields[fieldCount++] = {tag, 2 /* ASCII */, static_cast<uint32_t>(std::strlen(s) + 1),
                                reinterpret_cast<uint8_t*>(const_cast<char*>(s)),
                                std::strlen(s) + 1};
    };
    asciiField(270 /* ImageDescription */, desc);
    if (refInfo->exif.make != nullptr) asciiField(271 /* Make */, refInfo->exif.make);
    if (refInfo->exif.model != nullptr) asciiField(272 /* Model */, refInfo->exif.model);
    tinydng_write_image img{};
    img.width = width;
    img.height = height;
    img.samples_per_pixel = 1;
    img.bits_per_sample = 16;
    img.sample_format = TINYDNG_SAMPLEFORMAT_UINT;
    img.photometric = 32803;  // CFA
    img.data = reinterpret_cast<const uint8_t*>(q.data());
    img.data_size = q.size() * sizeof(std::uint16_t);
    img.cfa = &refInfo->cfa;
    img.raw = &rawOut;
    img.fields = fields;
    img.field_count = fieldCount;
    tinydng_write_options wopts{};
    wopts.as_dng = 1;
    wopts.compression = 1;
    tinydng_error werr{};
    if (tinydng_write_file(tctx, outPath, &img, &wopts, &werr) != 0) {
        std::fprintf(stderr, "write %s failed: %s\n", outPath, werr.message);
        return 1;
    }
    for (auto* d : docs) tinydng_document_destroy(tctx, d);
    tinydng_context_destroy(tctx);
    std::printf("wrote %s\n", outPath);
    return nonFinite == 0 ? 0 : 1;
}
