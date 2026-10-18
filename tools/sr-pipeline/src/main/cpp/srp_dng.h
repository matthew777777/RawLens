#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

// Unified linear-RGB DNG/TIFF writer for the sr-pipeline module.
//
// Same sources on desktop and Android: the implementation calls TinyDNG v3
// (tinydng_write_file over the vendored tinydng_v3 static library), so the
// desktop CLI and the Android JNI path emit byte-identical files for the
// same pixels and tags. This is the ONLY image-output path for sr-pipeline;
// there is deliberately no PNG writer here.
namespace srp {

enum class DngStatus : int32_t {
  kOk = 0,
  kBadArgument = 1,  // null/empty input, bad dimensions, short payload
  kEncodeFailed = 2,  // tinydng rejected the image or options
  kIoFailed = 3,      // tinydng_write_file could not write `path`
};

// Optional output color profile. Null selects the writer defaults
// (identity matrix, neutral white, D65); a set matrix/neutral overrides
// them, and non-empty make/model ride the normal extra-tag path.
struct DngColorMeta {
  double color_matrix1[9] = {0, 0, 0, 0, 0, 0, 0, 0, 0};
  uint8_t has_color_matrix1 = 0;
  double as_shot_neutral[3] = {0, 0, 0};
  uint8_t has_as_shot_neutral = 0;
  uint16_t calibration_illuminant1 = 21;  // D65
};

// One extra TIFF tag. Payload bytes are copied by the writer at call time
// and must already be in little-endian byte order.
struct DngField {
  uint16_t tag = 0;    // e.g. 271 (Make), 272 (Model), 33432 (Copyright)
  uint16_t type = 0;   // TIFF field type: 1=BYTE, 2=ASCII, 3=SHORT, 4=LONG, ...
  uint32_t count = 0;  // component count (includes NUL for ASCII)
  const uint8_t* payload = nullptr;
  size_t payload_size = 0;
};

// Writes row-major chunky float32 RGB in [0,1] as an uncompressed 16-bit
// linear-RGB DNG (photometric auto => RGB, one strip, DNGVersion 1.4 +
// black/white level + color tags via as_dng). Quantizes with clamp +
// round-to-nearest: u16 = round(clamp(x,0,1) * 65535).
// `fields`/`field_count` carry optional extra TIFF tags (may be null/0;
// e.g. Make/Model passthrough). `color` overrides the default identity
// profile (may be null for defaults).
// On failure returns != kOk and, when `error` is non-null, a message.
DngStatus DngWriteLinearRgb16(const char* path, uint32_t width,
                              uint32_t height, const float* rgb,
                              const DngField* fields, size_t field_count,
                              const DngColorMeta* color, std::string* error);

// Raw CFA reader (same TinyDNG v3 sources as the writer, so desktop and
// Android ingest byte-identical rasters). Opens `path` through tinydng,
// selects image 0, and decodes the full frame (or one even-aligned
// region) to uint16.
//
// Metadata mirrors rawpy/LibRaw where the pipeline reads it:
// black_level[4] in R,G1,B,G2 plane order (DNG BlackLevel tag order),
// white_level the single white level, cfa[4] the CFAPattern values in
// row-major order with G2 remapped 3 -> 1 (LibRaw convention), and
// noise[8] the NoiseProfile (S,O) pairs in R,G1,B,G2 plane order with
// noise_count entries (0 when the tag is absent).
enum class DngReadStatus : int32_t {
  kOk = 0,
  kBadArgument = 1,  // null path, null out, bad region
  kOpenFailed = 2,   // not a DNG/TIFF the loader accepts
  kUnsupported = 3,  // image 0 is not a single-plane <=16-bit raw raster
  kDecodeFailed = 4,
};

struct SrpDngMeta {
  uint32_t width = 0;
  uint32_t height = 0;
  int32_t black_level[4] = {0, 0, 0, 0};
  uint32_t white_level = 0;
  uint8_t cfa[4] = {0, 0, 0, 0};
  double noise[8] = {0, 0, 0, 0, 0, 0, 0, 0};
  uint16_t noise_count = 0;
  // Camera color metadata for output passthrough (rendering). Filled when
  // the tags exist; `*_present` is 0 otherwise.
  double color_matrix1[9] = {0, 0, 0, 0, 0, 0, 0, 0, 0};
  uint8_t color_matrix_present = 0;
  double as_shot_neutral[3] = {0, 0, 0};
  uint8_t has_as_shot_neutral = 0;
  uint16_t calibration_illuminant1 = 0;
  char make[64] = {0};
  char model[64] = {0};
};

// Fills `out` with image-0 geometry and tags. Fails kOpenFailed /
// kUnsupported (non-raw image 0) without touching pixels.
DngReadStatus SrpReadDngMeta(const char* path, SrpDngMeta* out);

// Fills only the color profile of `out` (matrix/neutral/illuminant +
// make/model) from image 0 of any sample count, including our own
// linear-RGB outputs. Raw-raster fields are left zeroed.
DngReadStatus SrpReadDngColorMeta(const char* path, SrpDngMeta* out);

// Reads the EXIF orientation of image 0 into `out` (1..8); writes 0 when
// the file is unreadable or carries no valid orientation. Never fails
// the caller: orientation is advisory display metadata.
DngReadStatus SrpReadDngOrientation(const char* path, int* out);

// Decodes image 0 to `out_pixels` (width*height uint16, row-major).
// With (reg_w, reg_h) == (0, 0) decodes the full frame; otherwise
// decodes the (reg_x, reg_y, reg_w, reg_h) even-aligned region into
// `out_pixels` (reg_w*reg_h uint16). Even alignment keeps Bayer phase.
DngReadStatus SrpReadDngPixels(const char* path, uint16_t* out_pixels,
                               uint32_t reg_x, uint32_t reg_y, uint32_t reg_w,
                               uint32_t reg_h);

// Decodes image 0 and normalizes to `out` (w*h floats, row-major) in
// one pass: same bytes as decode + flipToRggb + normalize (same
// unsigned value, same flip index map, same float64 formula rounded
// once), so callers can skip the ShortArray/IntArray transients and
// the host per-pixel loop. `black`/`cfa` hold 4 ints each (LibRaw
// CFA order); unsupported CFA returns kBadArgument, mirroring the
// Kotlin IllegalArgumentException.
DngReadStatus SrpReadDngNormalized(const char* path, float* out,
                                   uint32_t w, uint32_t h,
                                   const int32_t black[4], int32_t white,
                                   const int32_t cfa[4]);

}  // namespace srp
