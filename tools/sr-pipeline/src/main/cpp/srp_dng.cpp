// Unified linear-RGB DNG writer over TinyDNG v3 (desktop + Android).

#include "srp_dng.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

#include "tinydng.h"

namespace srp {

DngStatus DngWriteLinearRgb16(const char* path, uint32_t width,
                              uint32_t height, const float* rgb,
                              const DngField* fields, size_t field_count,
                              const DngColorMeta* color, std::string* error) {
  auto fail = [&](DngStatus status, const char* message) {
    if (error != nullptr) *error = message;
    return status;
  };
  if (path == nullptr || path[0] == '\0') {
    return fail(DngStatus::kBadArgument, "srp_dng: null or empty path");
  }
  if (width == 0 || height == 0 || rgb == nullptr) {
    return fail(DngStatus::kBadArgument, "srp_dng: null pixels or zero size");
  }
  const uint64_t count =
      static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
  if (count > static_cast<uint64_t>(SIZE_MAX) / 3) {
    return fail(DngStatus::kBadArgument, "srp_dng: image too large");
  }

  std::vector<tinydng_field> tags;
  std::vector<std::vector<uint8_t>> owned;
  if (field_count > 0) {
    if (fields == nullptr) {
      return fail(DngStatus::kBadArgument, "srp_dng: null field list");
    }
    if (field_count > 64) {
      return fail(DngStatus::kBadArgument, "srp_dng: too many fields");
    }
    tags.reserve(field_count);
    owned.reserve(field_count);
    for (size_t i = 0; i < field_count; ++i) {
      const DngField& f = fields[i];
      if (f.payload == nullptr || f.payload_size == 0 || f.count == 0 ||
          f.type < 1 || f.type > 12) {
        return fail(DngStatus::kBadArgument, "srp_dng: bad field payload");
      }
      owned.emplace_back(f.payload, f.payload + f.payload_size);
      tinydng_field tag{};
      tag.tag = f.tag;
      tag.type = f.type;
      tag.count = f.count;
      tag.data = owned.back().data();
      tag.size = owned.back().size();
      tags.push_back(tag);
    }
  }

  std::vector<uint16_t> u16(static_cast<size_t>(count) * 3);
  for (size_t i = 0; i < u16.size(); ++i) {
    const float x = rgb[i];
    const float clamped = std::min(std::max(x, 0.0f), 1.0f);
    u16[i] = static_cast<uint16_t>(std::lround(clamped * 65535.0f));
  }

  tinydng_error ctx_error{};
  tinydng_context* ctx = tinydng_context_create(nullptr, &ctx_error);
  if (ctx == nullptr) {
    return fail(DngStatus::kEncodeFailed, ctx_error.message[0] != '\0'
                                             ? ctx_error.message
                                             : "srp_dng: context create failed");
  }

  // DNG tags (raw editors such as RawTherapee/Darktable refuse files
  // without them): DNGVersion 1.4 default, zero black, full-scale white,
  // and the camera profile (`color`, else identity matrix + neutral white
  // under D65 describing uncalibrated camera-native RGB).
  tinydng_raw_info raw{};
  raw.black_level[0] = raw.black_level[1] = raw.black_level[2] = 0;
  raw.black_level_present = 1;
  raw.white_level[0] = 65535;
  raw.white_level_present = 1;
  if (color != nullptr && color->has_color_matrix1) {
    for (int i = 0; i < 9; ++i) raw.color_matrix1[i] = color->color_matrix1[i];
  } else {
    raw.color_matrix1[0] = raw.color_matrix1[4] = raw.color_matrix1[8] = 1.0;
  }
  raw.color_matrix_present = 1;
  if (color != nullptr && color->has_as_shot_neutral) {
    for (int i = 0; i < 3; ++i) raw.as_shot_neutral[i] = color->as_shot_neutral[i];
  } else {
    raw.as_shot_neutral[0] = raw.as_shot_neutral[1] = raw.as_shot_neutral[2] = 1.0;
  }
  raw.has_as_shot_neutral = 1;
  raw.calibration_illuminant1 =
      (color != nullptr && color->calibration_illuminant1 != 0)
          ? color->calibration_illuminant1
          : 21;  // D65

  tinydng_write_image image{};
  image.width = width;
  image.height = height;
  image.samples_per_pixel = 3;
  image.bits_per_sample = 16;
  image.sample_format = 0;  // UINT
  image.photometric = 0;    // auto => RGB for 3 samples
  image.data = reinterpret_cast<const uint8_t*>(u16.data());
  image.data_size = u16.size() * sizeof(uint16_t);
  image.raw = &raw;
  image.fields = tags.empty() ? nullptr : tags.data();
  image.field_count = tags.size();

  tinydng_write_options options{};  // LE, uncompressed, classic TIFF
  options.as_dng = 1;               // emit the DNG tag set above
  options.compression = 1;          // 1 => none (explicit, like the app)

  tinydng_error write_error{};
  const tinydng_status status =
      tinydng_write_file(ctx, path, &image, &options, &write_error);
  tinydng_context_destroy(ctx);
  if (status != TINYDNG_OK) {
    if (write_error.message[0] != '\0') {
      return fail(DngStatus::kIoFailed, write_error.message);
    }
    return fail(DngStatus::kIoFailed, "srp_dng: tinydng_write_file failed");
  }
  return DngStatus::kOk;
}

namespace {

struct DngDoc {
  tinydng_context* ctx = nullptr;
  tinydng_document* doc = nullptr;
  const tinydng_image_info* img = nullptr;

  ~DngDoc() {
    if (doc != nullptr && ctx != nullptr) tinydng_document_destroy(ctx, doc);
    if (ctx != nullptr) tinydng_context_destroy(ctx);
  }
};

// Opens `path` and selects image 0. On success fills meta-relevant
// pointers; maps tinydng failures to the read status.
DngReadStatus OpenRaw(const char* path, DngDoc* holder) {
  if (path == nullptr || path[0] == '\0' || holder == nullptr) {
    return DngReadStatus::kBadArgument;
  }
  tinydng_error ctx_error{};
  holder->ctx = tinydng_context_create(nullptr, &ctx_error);
  if (holder->ctx == nullptr) return DngReadStatus::kOpenFailed;
  tinydng_error open_error{};
  if (tinydng_open_file(holder->ctx, path, nullptr, &holder->doc,
                        &open_error) != TINYDNG_OK ||
      holder->doc == nullptr) {
    return DngReadStatus::kOpenFailed;
  }
  if (tinydng_image_count(holder->doc) < 1) {
    return DngReadStatus::kOpenFailed;
  }
  holder->img = tinydng_image_get(holder->doc, 0);
  if (holder->img == nullptr || holder->img->samples_per_pixel != 1 ||
      holder->img->bits_per_sample > 16 || holder->img->width == 0 ||
      holder->img->height == 0) {
    return DngReadStatus::kUnsupported;
  }
  return DngReadStatus::kOk;
}

// Container-level open for color metadata: image 0 of any sample count
// (our own linear-RGB outputs included), no raw-raster restriction.
DngReadStatus OpenImage(const char* path, DngDoc* holder) {
  if (path == nullptr || path[0] == '\0' || holder == nullptr) {
    return DngReadStatus::kBadArgument;
  }
  tinydng_error ctx_error{};
  holder->ctx = tinydng_context_create(nullptr, &ctx_error);
  if (holder->ctx == nullptr) return DngReadStatus::kOpenFailed;
  tinydng_error open_error{};
  if (tinydng_open_file(holder->ctx, path, nullptr, &holder->doc,
                        &open_error) != TINYDNG_OK ||
      holder->doc == nullptr) {
    return DngReadStatus::kOpenFailed;
  }
  if (tinydng_image_count(holder->doc) < 1) {
    return DngReadStatus::kOpenFailed;
  }
  holder->img = tinydng_image_get(holder->doc, 0);
  if (holder->img == nullptr || holder->img->width == 0 ||
      holder->img->height == 0) {
    return DngReadStatus::kUnsupported;
  }
  return DngReadStatus::kOk;
}

}  // namespace

DngReadStatus SrpReadDngMeta(const char* path, SrpDngMeta* out) {
  if (out == nullptr) return DngReadStatus::kBadArgument;
  *out = SrpDngMeta{};
  DngDoc holder;
  const DngReadStatus status = OpenRaw(path, &holder);
  if (status != DngReadStatus::kOk) return status;
  const tinydng_image_info* img = holder.img;
  out->width = img->width;
  out->height = img->height;
  for (int i = 0; i < 4; ++i) {
    out->black_level[i] = img->raw.black_level[i];
    uint8_t v = 0;
    if (i < img->cfa.pattern_size) v = img->cfa.pattern[i];
    out->cfa[i] = (v == 3) ? 1 : v;  // LibRaw G2 convention
  }
  if (img->raw.white_level_present) {
    out->white_level = static_cast<uint32_t>(img->raw.white_level[0]);
  }
  const size_t nn =
      img->raw.noise_profile_count < 8 ? img->raw.noise_profile_count : 8;
  for (size_t i = 0; i < nn; ++i) out->noise[i] = img->raw.noise_profile[i];
  out->noise_count = static_cast<uint16_t>(nn);
  if (img->raw.color_matrix_present) {
    for (int i = 0; i < 9; ++i) out->color_matrix1[i] = img->raw.color_matrix1[i];
    out->color_matrix_present = 1;
  }
  if (img->raw.has_as_shot_neutral) {
    for (int i = 0; i < 3; ++i) out->as_shot_neutral[i] = img->raw.as_shot_neutral[i];
    out->has_as_shot_neutral = 1;
  }
  out->calibration_illuminant1 = img->raw.calibration_illuminant1;
  if (img->exif.make != nullptr) {
    std::strncpy(out->make, img->exif.make, sizeof(out->make) - 1);
  }
  if (img->exif.model != nullptr) {
    std::strncpy(out->model, img->exif.model, sizeof(out->model) - 1);
  }
  return DngReadStatus::kOk;
}

DngReadStatus SrpReadDngColorMeta(const char* path, SrpDngMeta* out) {
  if (out == nullptr) return DngReadStatus::kBadArgument;
  *out = SrpDngMeta{};
  DngDoc holder;
  const DngReadStatus status = OpenImage(path, &holder);
  if (status != DngReadStatus::kOk) return status;
  const tinydng_image_info* img = holder.img;
  if (img->raw.color_matrix_present) {
    for (int i = 0; i < 9; ++i) out->color_matrix1[i] = img->raw.color_matrix1[i];
    out->color_matrix_present = 1;
  }
  if (img->raw.has_as_shot_neutral) {
    for (int i = 0; i < 3; ++i) out->as_shot_neutral[i] = img->raw.as_shot_neutral[i];
    out->has_as_shot_neutral = 1;
  }
  out->calibration_illuminant1 = img->raw.calibration_illuminant1;
  if (img->exif.make != nullptr) {
    std::strncpy(out->make, img->exif.make, sizeof(out->make) - 1);
  }
  if (img->exif.model != nullptr) {
    std::strncpy(out->model, img->exif.model, sizeof(out->model) - 1);
  }
  return DngReadStatus::kOk;
}

DngReadStatus SrpReadDngOrientation(const char* path, int* out) {
  if (out == nullptr) return DngReadStatus::kBadArgument;
  *out = 0;
  if (path == nullptr) return DngReadStatus::kBadArgument;
  DngDoc holder;
  const DngReadStatus status = OpenImage(path, &holder);
  if (status != DngReadStatus::kOk) return status;
  const uint16_t o = holder.img->exif.orientation;
  *out = (o >= 1 && o <= 8) ? static_cast<int>(o) : 0;
  return DngReadStatus::kOk;
}

DngReadStatus SrpReadDngPixels(const char* path, uint16_t* out_pixels,
                               uint32_t reg_x, uint32_t reg_y, uint32_t reg_w,
                               uint32_t reg_h) {
  if (out_pixels == nullptr) return DngReadStatus::kBadArgument;
  const bool full = (reg_w == 0 && reg_h == 0);
  if (!full &&
      ((reg_w == 0 || reg_h == 0 || (reg_x & 1) != 0 || (reg_y & 1) != 0 ||
        (reg_w & 1) != 0 || (reg_h & 1) != 0))) {
    return DngReadStatus::kBadArgument;
  }
  DngDoc holder;
  const DngReadStatus status = OpenRaw(path, &holder);
  if (status != DngReadStatus::kOk) return status;
  const tinydng_image_info* img = holder.img;
  const uint32_t w = full ? img->width : reg_w;
  const uint32_t h = full ? img->height : reg_h;
  if (!full &&
      (static_cast<uint64_t>(reg_x) + reg_w > img->width ||
       static_cast<uint64_t>(reg_y) + reg_h > img->height)) {
    return DngReadStatus::kBadArgument;
  }
  tinydng_pixels px{};
  tinydng_error dec_error{};
  tinydng_decode_options opts{};
  opts.num_threads = 1;
  tinydng_status dec;
  if (full) {
    dec = tinydng_decode_image(holder.ctx, holder.doc, 0, &opts, &px,
                               &dec_error);
  } else {
    dec = tinydng_decode_region(holder.ctx, holder.doc, 0, reg_x, reg_y, reg_w,
                                reg_h, &opts, &px, &dec_error);
  }
  if (dec != TINYDNG_OK) return DngReadStatus::kDecodeFailed;
  const size_t want = static_cast<size_t>(w) * h * 2;
  bool ok = (px.width == w && px.height == h && px.data != nullptr &&
             px.size >= want);
  if (ok) std::memcpy(out_pixels, px.data, want);
  tinydng_pixels_free(holder.ctx, &px);
  return ok ? DngReadStatus::kOk : DngReadStatus::kDecodeFailed;
}

DngReadStatus SrpReadDngNormalized(const char* path, float* out,
                                   uint32_t w, uint32_t h,
                                   const int32_t black[4], int32_t white,
                                   const int32_t cfa[4]) {
  if (out == nullptr || black == nullptr || cfa == nullptr || w == 0 ||
      h == 0) {
    return DngReadStatus::kBadArgument;
  }
  // CFA select mirrors flipToRggb (RGGB identity, BGGR both axes, GBRG
  // horizontal, GRBG vertical); anything else is kBadArgument.
  const bool rggb = (cfa[0] == 0 && cfa[1] == 1 && cfa[2] == 1 && cfa[3] == 2);
  const bool bggr = (cfa[0] == 2 && cfa[1] == 1 && cfa[2] == 1 && cfa[3] == 0);
  const bool gbrg = (cfa[0] == 1 && cfa[1] == 0 && cfa[2] == 2 && cfa[3] == 1);
  const bool grbg = (cfa[0] == 1 && cfa[1] == 2 && cfa[2] == 0 && cfa[3] == 1);
  if (!rggb && !bggr && !gbrg && !grbg) return DngReadStatus::kBadArgument;
  DngDoc holder;
  const DngReadStatus status = OpenRaw(path, &holder);
  if (status != DngReadStatus::kOk) return status;
  tinydng_pixels px{};
  tinydng_error dec_error{};
  tinydng_decode_options opts{};
  opts.num_threads = 1;
  const tinydng_status dec =
      tinydng_decode_image(holder.ctx, holder.doc, 0, &opts, &px, &dec_error);
  if (dec != TINYDNG_OK) return DngReadStatus::kDecodeFailed;
  const size_t want = static_cast<size_t>(w) * h * 2;
  const bool ok = (px.width == w && px.height == h && px.data != nullptr &&
                   px.size >= want);
  if (ok) {
    const uint16_t* src = reinterpret_cast<const uint16_t*>(px.data);
    // Exact Kotlin normalize ops per output (x, y): unsigned value,
    // flip index map, Int (white - plane), float64 divide, round once.
    // Loop order is irrelevant (per-pixel independent).
    for (uint32_t y = 0; y < h; ++y) {
      for (uint32_t x = 0; x < w; ++x) {
        uint32_t si;
        if (rggb) {
          si = y * w + x;
        } else if (bggr) {
          si = (h - 1 - y) * w + (w - 1 - x);
        } else if (gbrg) {
          si = y * w + (w - 1 - x);
        } else {
          si = (h - 1 - y) * w + x;
        }
        const double u = static_cast<double>(src[si]);
        int32_t plane;
        if ((y & 1) == 0 && (x & 1) == 0) {
          plane = black[0];
        } else if ((y & 1) == 0) {
          plane = black[1];
        } else if ((x & 1) == 0) {
          plane = black[3];
        } else {
          plane = black[2];
        }
        const double v = (u - static_cast<double>(plane)) /
                         static_cast<double>(white - plane);
        out[static_cast<size_t>(y) * w + x] = static_cast<float>(v);
      }
    }
  }
  tinydng_pixels_free(holder.ctx, &px);
  return ok ? DngReadStatus::kOk : DngReadStatus::kDecodeFailed;
}

}  // namespace srp
