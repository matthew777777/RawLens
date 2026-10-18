// Unified DNG writer roundtrip proof.
//
// Usage: srp_proof_dng <out.dng>
// Writes a synthetic float32 RGB gradient through srp::DngWriteLinearRgb16
// (with one ASCII tag + two deliberately out-of-range pixels exercising the
// clamp), then reads the file back through the TinyDNG decode API and
// compares every pixel against an independently recomputed quantization.
// Prints geometry + maxabs and exits nonzero on any mismatch.

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "srp_dng.h"
#include "tinydng.h"

namespace {

constexpr uint32_t kW = 64, kH = 48;

// Independent oracle of the writer's quantizer (kept in sync by inspection:
// clamp + round-to-nearest of x*65535).
uint16_t Quantize(float x) {
  const float c = x < 0.0f ? 0.0f : (x > 1.0f ? 1.0f : x);
  return static_cast<uint16_t>(std::lround(c * 65535.0f));
}

}  // namespace

int main(int argc, char** argv) {
  if (argc != 2) {
    std::fprintf(stderr, "usage: %s <out.dng>\n", argv[0]);
    return 2;
  }

  std::vector<float> rgb(static_cast<size_t>(kW) * kH * 3);
  for (uint32_t y = 0; y < kH; ++y) {
    for (uint32_t x = 0; x < kW; ++x) {
      const size_t i = (static_cast<size_t>(y) * kW + x) * 3;
      rgb[i + 0] = static_cast<float>(x) / static_cast<float>(kW - 1);
      rgb[i + 1] = static_cast<float>(y) / static_cast<float>(kH - 1);
      rgb[i + 2] = 0.5f;
    }
  }
  rgb[0] = -0.25f;  // exercises the low clamp
  rgb[2] = 1.5f;    // exercises the high clamp

  static const char kMake[] = "sr-pipeline proof";
  srp::DngField make{};
  make.tag = 271;  // Make
  make.type = 2;   // ASCII
  make.count = sizeof(kMake);
  make.payload = reinterpret_cast<const uint8_t*>(kMake);
  make.payload_size = sizeof(kMake);

  std::string error;
  if (srp::DngWriteLinearRgb16(argv[1], kW, kH, rgb.data(), &make, 1,
                               nullptr, &error) != srp::DngStatus::kOk) {
    std::fprintf(stderr, "FAIL: write failed: %s\n", error.c_str());
    return 1;
  }

  tinydng_error ctx_error{};
  tinydng_context* ctx = tinydng_context_create(nullptr, &ctx_error);
  if (ctx == nullptr) {
    std::fprintf(stderr, "FAIL: context create: %s\n", ctx_error.message);
    return 1;
  }
  int rc = 1;
  tinydng_error err{};
  if (tinydng_is_dng(argv[1], &err) != TINYDNG_OK) {
    std::fprintf(stderr, "FAIL: not a TIFF/DNG: %s\n", err.message);
  } else {
    tinydng_document* doc = nullptr;
    if (tinydng_open_file(ctx, argv[1], nullptr, &doc, &err) != TINYDNG_OK ||
        doc == nullptr) {
      std::fprintf(stderr, "FAIL: open: %s\n", err.message);
    } else {
      const tinydng_image_info* info = tinydng_image_get(doc, 0);
      if (info == nullptr) {
        std::fprintf(stderr, "FAIL: no image 0\n");
      } else {
        std::printf("image: %ux%u samples=%u bits=%u compression=%u\n",
                    info->width, info->height, info->samples_per_pixel,
                    info->bits_per_sample_decoded, info->compression);
        tinydng_pixels px{};
        if (tinydng_decode_image(ctx, doc, 0, nullptr, &px, &err) != TINYDNG_OK) {
          std::fprintf(stderr, "FAIL: decode: %s\n", err.message);
        } else {
          const bool geom_ok =
              px.width == kW && px.height == kH && px.samples_per_pixel == 3 &&
              px.bits_per_sample == 16 &&
              px.size == static_cast<size_t>(kW) * kH * 3 * 2;
          uint16_t maxabs = 0;
          if (geom_ok) {
            const auto* got = reinterpret_cast<const uint16_t*>(px.data);
            for (size_t i = 0; i < static_cast<size_t>(kW) * kH * 3; ++i) {
              const uint16_t want = Quantize(rgb[i]);
              const uint16_t d =
                  got[i] > want ? got[i] - want : want - got[i];
              if (d > maxabs) maxabs = d;
            }
          } else {
            std::fprintf(stderr, "FAIL: geometry mismatch (px %ux%u s=%u b=%u size=%zu)\n",
                         px.width, px.height, px.samples_per_pixel,
                         px.bits_per_sample, px.size);
          }
          std::printf("maxabs_vs_oracle=%u\n", maxabs);
          if (geom_ok && maxabs == 0) {
            std::printf("PASS: DNG roundtrip pixel-exact\n");
            rc = 0;
          } else if (geom_ok) {
            std::fprintf(stderr, "FAIL: pixel mismatch\n");
          }
          if (px.owns_memory) tinydng_pixels_free(ctx, &px);
        }
      }
      tinydng_document_destroy(ctx, doc);
    }
  }
  tinydng_context_destroy(ctx);
  return rc;
}
