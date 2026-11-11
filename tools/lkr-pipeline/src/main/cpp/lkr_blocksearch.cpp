// SPDX-License-Identifier: Apache-2.0
#include "lkr_blocksearch.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "lkr_spv.h"

namespace lkr {

namespace {

struct HmsrPush {   // align.comp push block (tools/sr-pipeline shaders)
  uint32_t width, height, pixelCount, tileCount, level, tileSize;
  float scale, alpha, beta, robustness;
  uint32_t mode;
  uint32_t pad[21];
};
static_assert(sizeof(HmsrPush) == 128, "align.comp push layout");

constexpr int kTile = 8;   // grey px per search tile = 16 LR px = solver local block

// Grey = mean of each RGGB cell (half resolution, cell centres at LR 2J+1).
std::vector<float> CellGrey(const float* raw, int n) {
  const int g = n / 2;
  std::vector<float> o(size_t(g) * g);
  for (int i = 0; i < g; ++i)
    for (int j = 0; j < g; ++j) {
      const float* r0 = raw + size_t(2 * i) * n + 2 * j;
      o[size_t(i) * g + j] = 0.25f * (r0[0] + r0[1] + r0[n] + r0[n + 1]);
    }
  return o;
}

// w(x) = img(A^-1 (x - t)) in grey px (affine t halves with the resolution).
std::vector<float> WarpToRef(const std::vector<float>& img, int g, const float* p) {
  const double a = p[0], b = p[1], c = p[3], d = p[4], tx = p[2] * 0.5, ty = p[5] * 0.5;
  const double det = a * d - b * c;
  const double ia = d / det, ib = -b / det, ic = -c / det, id = a / det;
  std::vector<float> o(size_t(g) * g, 0.0f);
  for (int i = 0; i < g; ++i)
    for (int j = 0; j < g; ++j) {
      const double X = j + 0.5 - tx, Y = i + 0.5 - ty;
      const double u = ia * X + ib * Y - 0.5, v = ic * X + id * Y - 0.5;
      const int u0 = int(std::floor(u)), v0 = int(std::floor(v));
      const float fu = float(u - u0), fv = float(v - v0);
      auto at = [&](int yy, int xx) {
        xx = std::clamp(xx, 0, g - 1);
        yy = std::clamp(yy, 0, g - 1);
        return img[size_t(yy) * g + xx];
      };
      o[size_t(i) * g + j] = (at(v0, u0) * (1 - fu) + at(v0, u0 + 1) * fu) * (1 - fv) +
                             (at(v0 + 1, u0) * (1 - fu) + at(v0 + 1, u0 + 1) * fu) * fv;
    }
  return o;
}

std::vector<float> Half(const std::vector<float>& a, int g) {
  const int h = g / 2;
  std::vector<float> o(size_t(h) * h);
  for (int i = 0; i < h; ++i)
    for (int j = 0; j < h; ++j)
      o[size_t(i) * h + j] = 0.25f * (a[size_t(2 * i) * g + 2 * j] + a[size_t(2 * i) * g + 2 * j + 1] +
                                      a[size_t(2 * i + 1) * g + 2 * j] + a[size_t(2 * i + 1) * g + 2 * j + 1]);
  return o;
}

}  // namespace

BlockSearch::BlockSearch(Gpu* gpu) : gpu_(gpu) {
  if (gpu_ && gpu_->ok()) pipe_ = gpu_->pipeline(spv::srp_align, sizeof(spv::srp_align), 3, sizeof(HmsrPush));
}
BlockSearch::~BlockSearch() {
  if (gpu_ && gpu_->ok()) gpu_->destroy(pipe_);
}

bool BlockSearch::run(const float* frames, int K, int n, const float* p, int levels, int radius, float sigma,
                      std::vector<float>* field) {
  // Mean |difference| of two grey cells under noise only: grey = mean of 4
  // raw samples (sigma/2), difference of two -> sqrt(2) sigma/2, E|N| = s sqrt(2/pi).
  const double noiseL1 = std::sqrt(2.0) * 0.5 * sigma * std::sqrt(2.0 / 3.14159265);
  const int g0 = n / 2;
  // Pyramid sizes must keep whole search tiles at every level.
  if (g0 % (kTile << (levels - 1)) != 0) return false;
  const int LB = 2 * kTile, nb1 = (n + LB - 1) / LB;
  field->assign(size_t(K) * nb1 * nb1 * 2, 0.0f);
  std::vector<std::vector<float>> refPyr(levels);
  refPyr[0] = CellGrey(frames, n);
  for (int l = 1; l < levels; ++l) refPyr[l] = Half(refPyr[l - 1], g0 >> (l - 1));
  Buffer ref = gpu_->alloc(size_t(g0) * g0 * 4), tgt = gpu_->alloc(size_t(g0) * g0 * 4);
  const int ntMax = (g0 / kTile) * (g0 / kTile);
  Buffer flow = gpu_->alloc(size_t(ntMax) * 8);
  VkDescriptorSet set = gpu_->set(pipe_, {&ref, &tgt, &flow});
  for (int k = 1; k < K; ++k) {
    std::vector<std::vector<float>> mov(levels);
    mov[0] = WarpToRef(CellGrey(frames + size_t(k) * n * n, n), g0, p + k * 6);
    for (int l = 1; l < levels; ++l) mov[l] = Half(mov[l - 1], g0 >> (l - 1));
    std::vector<float> f;   // flow per tile at the current level (grey px of that level)
    for (int l = levels - 1; l >= 0; --l) {
      const int g = g0 >> l, nt = g / kTile;
      std::vector<float> init(size_t(nt) * nt * 2, 0.0f);
      if (!f.empty()) {          // upsample the coarser level: x2 shift, nearest parent tile
        const int ntp = nt / 2;
        for (int ty = 0; ty < nt; ++ty)
          for (int tx = 0; tx < nt; ++tx)
            for (int c = 0; c < 2; ++c)
              init[(size_t(ty) * nt + tx) * 2 + c] = 2.0f * f[(size_t(ty / 2) * ntp + tx / 2) * 2 + c];
      }
      std::memcpy(ref.map, refPyr[l].data(), size_t(g) * g * 4);
      std::memcpy(tgt.map, mov[l].data(), size_t(g) * g * 4);
      std::memcpy(flow.map, init.data(), init.size() * 4);
      HmsrPush q{};
      q.width = uint32_t(g);
      q.height = uint32_t(g);
      q.pixelCount = uint32_t(g * g);
      q.tileCount = uint32_t(nt * nt);
      q.level = uint32_t(l);
      q.tileSize = kTile;
      q.scale = 1.0f;
      q.pad[0] = 0;
      q.pad[1] = uint32_t(radius);
      q.pad[2] = uint32_t(nt);
      gpu_->begin();
      gpu_->dispatch(pipe_, set, &q, (q.tileCount + 255) / 256);
      if (!gpu_->submit()) return false;
      f.assign(flow.as<float>(), flow.as<float>() + init.size());
    }
    // Confidence: keep a tile's flow only for LARGE motion (>= 2 grey px =
    // 4 LR px, beyond Gauss-Newton's reach) that matches clearly better than
    // no local motion (L1 at least 20% lower at level 0).
    {
      const int nt0 = g0 / kTile;
      const std::vector<float>& R = refPyr[0];
      const std::vector<float>& M = mov[0];
      auto sad = [&](int tx, int ty, int fx, int fy) {
        double e = 0;
        for (int yy = 0; yy < kTile; ++yy)
          for (int xx = 0; xx < kTile; ++xx) {
            const int y = ty * kTile + yy, x = tx * kTile + xx;
            const int my = std::clamp(y + fy, 0, g0 - 1), mx = std::clamp(x + fx, 0, g0 - 1);
            e += std::fabs(R[size_t(y) * g0 + x] - M[size_t(my) * g0 + mx]);
          }
        return e;
      };
      for (int ty = 0; ty < nt0; ++ty)
        for (int tx = 0; tx < nt0; ++tx) {
          float* ft = f.data() + (size_t(ty) * nt0 + tx) * 2;
          const int fx = int(std::lround(ft[0])), fy = int(std::lround(ft[1]));
          if (fx == 0 && fy == 0) continue;
          // Small shifts are Gauss-Newton's job (exact, sub-pixel); integer
          // search results there are aliasing-driven on static scenes.
          const double s0 = sad(tx, ty, 0, 0), s1 = sad(tx, ty, fx, fy), npx = kTile * kTile;
          // Real motion: zero shift mismatched far beyond noise, and the found
          // shift explains most of it. Static / flat / repetitive tiles fail.
          if (std::max(std::abs(fx), std::abs(fy)) < 2 || s0 / npx < 4.0 * noiseL1 || !(s1 < 0.6 * s0))
            ft[0] = ft[1] = 0.0f;
        }
    }
    // tgt(x + f) ~ ref(x) with tgt the affine-warped frame  =>  local d = -f
    // (grey px -> LR px: x2). Field blocks are indexed in frame coords; the
    // frame block at u_c sits at ref x_c = A u_c + t.
    const int nt = g0 / kTile;
    const float* q = p + k * 6;
    for (int by = 0; by < nb1; ++by)
      for (int bx = 0; bx < nb1; ++bx) {
        const float uxc = bx * LB + LB * 0.5f, uyc = by * LB + LB * 0.5f;
        const float xr = q[0] * uxc + q[1] * uyc + q[2], yr = q[3] * uxc + q[4] * uyc + q[5];
        const int tx = std::clamp(int(xr / LB), 0, nt - 1), ty = std::clamp(int(yr / LB), 0, nt - 1);
        float* d = field->data() + ((size_t(k) * nb1 + by) * nb1 + bx) * 2;
        d[0] = -2.0f * f[(size_t(ty) * nt + tx) * 2];
        d[1] = -2.0f * f[(size_t(ty) * nt + tx) * 2 + 1];
      }
  }
  for (Buffer* b : {&ref, &tgt, &flow}) gpu_->free(*b);
  return true;
}

}  // namespace lkr
