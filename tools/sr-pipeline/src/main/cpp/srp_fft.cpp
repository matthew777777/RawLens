// Resident fft.comp chain (port of host/gpu_fft.py resident functions).
//
// See srp_fft.h for the ownership contract (arena buffers, borrowed
// inputs). Dispatch order, push bytes, table math, and host staging mirror
// the port exactly; pool/checkout bookkeeping is replaced by the arena.

#include "srp_fft.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>

#include "srp_dispatch.h"
#include "srp_pipeline.h"

namespace srp {
namespace {

bool FindMemoryType(VkPhysicalDevice physical, uint32_t type_bits,
                    VkMemoryPropertyFlags flags, uint32_t* out_index) {
  VkPhysicalDeviceMemoryProperties props{};
  vkGetPhysicalDeviceMemoryProperties(physical, &props);
  for (uint32_t i = 0; i < props.memoryTypeCount; ++i) {
    if ((type_bits & (1u << i)) != 0u &&
        (props.memoryTypes[i].propertyFlags & flags) == flags) {
      *out_index = i;
      return true;
    }
  }
  return false;
}

uint32_t FloatBits(float f) {
  uint32_t u = 0;
  std::memcpy(&u, &f, sizeof(u));
  return u;
}

bool IsPot(uint32_t n) { return n > 0 && (n & (n - 1)) == 0; }

uint32_t NextPot(uint32_t n) {
  uint32_t m = 1;
  while (m < n) m <<= 1;
  return m;
}

constexpr double kPi = 3.14159265358979323846;

// Default row-chunk threshold for fwdHave Bluestein (cells = count*m;
// SRP_CHUNK_CELLS overrides per run). Larger = fewer chunks = fewer
// dispatches at the same GPU work, up to evict/realloc churn. Measured
// single-frame grey 3060x4080 (Apple M1): 2M -> 2.45s/6669 dispatches,
// 4M -> 2.05s/3393, 8M -> 1.75s/1755, bit-identical throughout (per-row
// math is threshold-independent). 8M ships: the transient live set
// (~12 buffers of count*m pairs, ~768MB at 8M) fits desktop RSS with
// the residents alive; Android retunes via the env knob.
constexpr size_t kChunkCellPairs = 1u << 23;

}  // namespace

static size_t ChunkCellsDefault() {
  // SRP_CHUNK_CELLS overrides the row-chunk threshold (grey/full-frame
  // tuning knob, cf. SRP_POOL_CAP_MB): larger = fewer chunks = fewer
  // dispatches, up to the pool cap into evict/realloc churn. Unset or
  // zero selects the default below.
  if (const char* env = std::getenv("SRP_CHUNK_CELLS")) {
    char* end = nullptr;
    const unsigned long v = std::strtoul(env, &end, 10);
    if (end != env && v > 0) return static_cast<size_t>(v);
  }
  return kChunkCellPairs;
}

FftChain::FftChain(Context* ctx, VkShaderModule mod)
    : ctx_(ctx),
      mod_(mod),
      midchain_release_(std::getenv("SRP_NO_MIDCHAIN_RELEASE") == nullptr),
      chunk_cells_(ChunkCellsDefault()) {
  VkDevice device = NativeDevice(ctx);
  VkPhysicalDevice physical = NativePhysicalDevice(ctx);
  if (device == VK_NULL_HANDLE || physical == VK_NULL_HANDLE ||
      mod == VK_NULL_HANDLE) {
    device_ = VK_NULL_HANDLE;
    return;
  }
  device_ = device;
  // Dummy B operand for ops 0/4 (bound but unread, like ctx.dummy(2)).
  VkBufferCreateInfo info{};
  info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
  info.size = 8;
  info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
  info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
  VkBuffer buf = VK_NULL_HANDLE;
  if (vkCreateBuffer(device_, &info, nullptr, &buf) != VK_SUCCESS) {
    device_ = VK_NULL_HANDLE;
    return;
  }
  VkMemoryRequirements req{};
  vkGetBufferMemoryRequirements(device_, buf, &req);
  uint32_t index = 0;
  if (!FindMemoryType(physical, req.memoryTypeBits,
                      VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                          VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                      &index)) {
    vkDestroyBuffer(device_, buf, nullptr);
    device_ = VK_NULL_HANDLE;
    return;
  }
  VkMemoryAllocateInfo alloc{};
  alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
  alloc.allocationSize = req.size;
  alloc.memoryTypeIndex = index;
  VkDeviceMemory mem = VK_NULL_HANDLE;
  if (vkAllocateMemory(device_, &alloc, nullptr, &mem) != VK_SUCCESS ||
      vkBindBufferMemory(device_, buf, mem, 0) != VK_SUCCESS) {
    if (mem != VK_NULL_HANDLE) vkFreeMemory(device_, mem, nullptr);
    vkDestroyBuffer(device_, buf, nullptr);
    device_ = VK_NULL_HANDLE;
    return;
  }
  void* mapped = nullptr;
  if (vkMapMemory(device_, mem, 0, 8, 0, &mapped) != VK_SUCCESS) {
    vkFreeMemory(device_, mem, nullptr);
    vkDestroyBuffer(device_, buf, nullptr);
    device_ = VK_NULL_HANDLE;
    return;
  }
  std::memset(mapped, 0, 8);
  vkUnmapMemory(device_, mem);
  dummy_ = track(buf, mem, 2);
}

FftChain::~FftChain() { freeAll(); }

FftBuf FftChain::track(VkBuffer b, VkDeviceMemory m, size_t nfloats) {
  FftBuf out{b, m, nfloats};
  arena_.push_back(out);
  return out;
}

static bool SameBuf(const FftBuf& a, const FftBuf& b) {
  return a.valid() && b.valid() && a.buf == b.buf && a.mem == b.mem;
}

void FftChain::release(const FftBuf& b) {
  // SRP_NO_MIDCHAIN_RELEASE=1 disables mid-chain release (every transient
  // lives until chain end). Unset: mid-chain release is on. Note: later
  // structural changes (on-device transpose, batching, chunking) stay
  // active either way — bit-identical by construction, verified by gates.
  if (!midchain_release_ || !b.valid()) return;
  for (auto it = arena_.begin(); it != arena_.end(); ++it) {
    if (SameBuf(*it, b) && !SameBuf(*it, dummy_)) {
      SrpReleaseBuffer(ctx_, it->buf, it->mem, it->nfloats);
      arena_.erase(it);
      return;
    }
  }
}

void FftChain::releaseExcept(std::initializer_list<FftBuf> keep) {
  if (!midchain_release_) return;
  for (auto it = arena_.begin(); it != arena_.end();) {
    bool kept = SameBuf(*it, dummy_);
    if (!kept) {
      for (const FftBuf& k : keep) {
        if (SameBuf(*it, k)) {
          kept = true;
          break;
        }
      }
    }
    if (kept) {
      ++it;
    } else {
      SrpReleaseBuffer(ctx_, it->buf, it->mem, it->nfloats);
      it = arena_.erase(it);
    }
  }
}

FftBuf FftChain::alloc(size_t nfloats) {
  FftBuf bad;
  if (device_ == VK_NULL_HANDLE || nfloats == 0) return bad;
  VkBuffer buf = VK_NULL_HANDLE;
  VkDeviceMemory mem = VK_NULL_HANDLE;
  if (!SrpAcquireBuffer(ctx_, nfloats, &buf, &mem)) return bad;
  return track(buf, mem, nfloats);
}

void FftChain::flushWritten(VkDeviceMemory mem, VkDeviceSize size) {
  // Explicit host-write availability: spec no-op for coherent memory,
  // real sync where the driver emulates coherence (MoltenVK reused
  // allocations). Unconditional: correct on every driver, free where
  // unnecessary.
  VkMappedMemoryRange range{};
  range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
  range.memory = mem;
  range.offset = 0;
  range.size = size;
  vkFlushMappedMemoryRanges(device_, 1, &range);
}

void FftChain::invalidateBeforeRead(VkDeviceMemory mem, VkDeviceSize size) {
  // Explicit GPU-write visibility for the mapped read below: spec no-op
  // for coherent memory, required in practice for reused allocations on
  // MoltenVK (without it, downloads of pooled buffers return stale,
  // pre-dispatch contents). Unconditional: correct everywhere.
  VkMappedMemoryRange range{};
  range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
  range.memory = mem;
  range.offset = 0;
  range.size = size;
  vkInvalidateMappedMemoryRanges(device_, 1, &range);
}

FftBuf FftChain::uploadVec(const std::vector<float>& v) {
  FftBuf b = alloc(v.size());
  if (!b.valid()) return b;
  void* mapped = nullptr;
  if (vkMapMemory(device_, b.mem, 0,
                  static_cast<VkDeviceSize>(v.size()) * 4, 0, &mapped) !=
      VK_SUCCESS) {
    return FftBuf{};
  }
  std::memcpy(mapped, v.data(), v.size() * 4);
  flushWritten(b.mem, static_cast<VkDeviceSize>(v.size()) * 4);
  vkUnmapMemory(device_, b.mem);
  return b;
}

bool FftChain::downloadVec(const FftBuf& b, std::vector<float>* out) {
  if (!b.valid() || out == nullptr) return false;
  out->resize(b.nfloats);
  void* mapped = nullptr;
  if (vkMapMemory(device_, b.mem, 0,
                  static_cast<VkDeviceSize>(b.nfloats) * 4, 0, &mapped) !=
      VK_SUCCESS) {
    return false;
  }
  invalidateBeforeRead(b.mem, static_cast<VkDeviceSize>(b.nfloats) * 4);
  std::memcpy(out->data(), mapped, b.nfloats * 4);
  vkUnmapMemory(device_, b.mem);
  return true;
}

void FftChain::freeAll() {
  if (device_ == VK_NULL_HANDLE) {
    arena_.clear();
    return;
  }
  for (const FftBuf& b : arena_) {
    SrpReleaseBuffer(ctx_, b.buf, b.mem, b.nfloats);
  }
  arena_.clear();
  dummy_ = FftBuf{};
}

Status FftChain::op(uint32_t opcode, const FftBuf& A, const FftBuf& B,
                    const FftBuf& C, uint32_t n_pairs, uint32_t grid,
                    const uint32_t pads[6]) {
  if (!A.valid() || !B.valid() || !C.valid() || n_pairs == 0 || grid == 0) {
    return Status::kInitFailed;
  }
  // Dispatch width is exactly n_pairs lanes: the flat ops (cmul,
  // conjscale) write C[i] for every i < pixel_count with no secondary
  // guard, so the port's 2*n_pairs width wrote 2x past the n_pairs-pair
  // output into whatever the heap allocator placed next. Harmless with
  // fresh allocations per dispatch (dead heap space), fatal with pooled
  // reuse (live neighbors, e.g. chirp buffers, deterministically
  // clobbered). Guarded ops (rev/butterfly/bandzero/shift) already
  // restrict themselves to <= n_pairs lanes, so visible lanes are
  // bit-identical; only the out-of-bounds threads are gone.
  SrpHmsrPush push{};
  push.pixel_count = n_pairs;
  push.scale = 1.0f;
  push.pad[0] = opcode;
  for (int i = 0; i < 6; ++i) push.pad[i + 1] = pads[i];
  VkBuffer bufs[3] = {A.buf, B.buf, C.buf};
  // Right-sized grid: `grid` is the needed thread count at every call site
  // (butterfly passes n_elem/2, every other op its lane count), and the
  // workgroup is kFftLocalSize threads (must match fft.comp's
  // local_size_x). Previously groups == threads (256x oversubscribed:
  // groups*n threads for n lanes); now groups == ceil(threads/256), which
  // executes exactly the same lanes — extras still exit via the shader's
  // pixelCount/b>=count guards — while scheduling 256x fewer threads.
  // Side win: group counts above ~64K lanes were out-of-spec
  // (groupCountX <= 65535); full-frame FFTs now comply.
  const uint32_t groups = static_cast<uint32_t>(
      (static_cast<uint64_t>(grid) + (kFftLocalSize - 1u)) / kFftLocalSize);
  return SrpDispatchGroups(ctx_, bufs, 3, &push, sizeof(push), kFftLocalSize,
                           groups, mod_);
}

std::vector<float> FftChain::BuildTwiddles(uint32_t n) {
  // T[q] = exp(-2*pi*i*q/N), q in 0..N/2-1, float64 math, float32 store.
  std::vector<float> tw(static_cast<size_t>(n / 2) * 2);
  for (uint32_t q = 0; q < n / 2; ++q) {
    const double ang = -2.0 * kPi * static_cast<double>(q) / n;
    tw[2 * q] = static_cast<float>(std::cos(ang));
    tw[2 * q + 1] = static_cast<float>(std::sin(ang));
  }
  return tw;
}

bool FftChain::FiniteVec(const std::vector<float>& v) {
  for (float x : v) {
    if (!std::isfinite(x)) return false;
  }
  return true;
}

std::vector<float> FftChain::ToComplex(const std::vector<float>& real) {
  std::vector<float> out(real.size() * 2, 0.0f);
  for (size_t i = 0; i < real.size(); ++i) out[2 * i] = real[i];
  return out;
}

std::vector<float> FftChain::RealPart(const std::vector<float>& pairs) {
  std::vector<float> out(pairs.size() / 2);
  for (size_t i = 0; i < out.size(); ++i) out[i] = pairs[2 * i];
  return out;
}

std::vector<float> FftChain::TransposeLead(const std::vector<float>& v,
                                           uint32_t lead, uint32_t h,
                                           uint32_t w) {
  std::vector<float> out(v.size());
  for (uint32_t b = 0; b < lead; ++b) {
    for (uint32_t y = 0; y < h; ++y) {
      for (uint32_t x = 0; x < w; ++x) {
        const size_t si = ((static_cast<size_t>(b) * h + y) * w + x) * 2;
        const size_t di = ((static_cast<size_t>(b) * w + x) * h + y) * 2;
        out[di] = v[si];
        out[di + 1] = v[si + 1];
      }
    }
  }
  return out;
}

std::vector<float> FftChain::FftShift2D(const std::vector<float>& v,
                                        uint32_t lead, uint32_t h,
                                        uint32_t w) {
  // np.fft.fftshift roll amount per axis: (n+1)/2 (== n/2 for even n).
  const uint32_t shy = (h + 1) / 2;
  const uint32_t shx = (w + 1) / 2;
  std::vector<float> out(v.size());
  for (uint32_t b = 0; b < lead; ++b) {
    for (uint32_t y = 0; y < h; ++y) {
      for (uint32_t x = 0; x < w; ++x) {
        const uint32_t sy = (y + shy) % h;
        const uint32_t sx = (x + shx) % w;
        const size_t si = ((static_cast<size_t>(b) * h + y) * w + x) * 2;
        const size_t di = ((static_cast<size_t>(b) * h + sy) * w + sx) * 2;
        out[di] = v[si];
        out[di + 1] = v[si + 1];
      }
    }
  }
  return out;
}

std::pair<FftBuf, FftBuf> FftChain::potLoop(const FftBuf& cur,
                                            const FftBuf& nxt, uint32_t n,
                                            uint32_t count) {
  const FftBuf bad;
  const uint32_t n_elem = count * n;
  // Bit-reversal permute (stride 1, batch_stride n).
  const uint32_t rev_pads[6] = {n, count, 1, n, 0, 0};
  if (op(0, cur, dummy_, nxt, n_elem, n_elem, rev_pads) != Status::kOk) {
    return {bad, bad};
  }
  FftBuf a = nxt, b = cur;
  auto it = twiddle_cache_.find(n);
  if (it == twiddle_cache_.end()) {
    it = twiddle_cache_.emplace(n, BuildTwiddles(n)).first;
  }
  const std::vector<float>& tw = it->second;
  for (uint32_t len = 2; len <= n; len <<= 1) {
    FftBuf twbuf = uploadVec(tw);
    const uint32_t fly_pads[6] = {n, count, 1, n, len, 0};
    if (!twbuf.valid() ||
        op(1, a, twbuf, b, n_elem, n_elem / 2, fly_pads) != Status::kOk) {
      return {bad, bad};
    }
    std::swap(a, b);
  }
  return {a, b};
}

FftBuf FftChain::conjScale(const FftBuf& in, uint32_t n_pairs, float scale) {
  FftBuf out = alloc(static_cast<size_t>(n_pairs) * 2);
  const uint32_t pads[6] = {0, 0, 0, 0, FloatBits(scale), 0};
  if (!out.valid() ||
      op(4, in, dummy_, out, n_pairs, n_pairs, pads) != Status::kOk) {
    return FftBuf{};
  }
  return out;
}

FftBuf FftChain::cmul(uint32_t opcode, const FftBuf& A, const FftBuf& B) {
  const uint32_t n_pairs = static_cast<uint32_t>(A.nfloats / 2);
  FftBuf out = alloc(A.nfloats);
  const uint32_t pads[6] = {0, 0, 0, 0, 0, 0};
  if (!out.valid() || n_pairs == 0 ||
      op(opcode, A, B, out, n_pairs, n_pairs, pads) != Status::kOk) {
    return FftBuf{};
  }
  return out;
}

FftChain::ChirpPlan FftChain::bluestein(uint32_t n) {
  auto it = chirp_cache_.find(n);
  if (it != chirp_cache_.end()) return it->second;
  ChirpPlan plan;
  plan.m = NextPot(2 * n - 1);
  const uint32_t m = plan.m;
  // w_pow = exp(-1j*pi*idx^2/n), float64 math, complex64 store.
  plan.w.resize(static_cast<size_t>(n) * 2);
  for (uint32_t i = 0; i < n; ++i) {
    const double ang =
        kPi * static_cast<double>(i) * static_cast<double>(i) / n;
    plan.w[2 * i] = static_cast<float>(std::cos(ang));
    plan.w[2 * i + 1] = static_cast<float>(-std::sin(ang));
  }
  // b[:n] = conj(w); b[m-n+1:] = conj(w[1:][::-1]).
  std::vector<float> b(static_cast<size_t>(m) * 2, 0.0f);
  for (uint32_t i = 0; i < n; ++i) {
    b[2 * i] = plan.w[2 * i];
    b[2 * i + 1] = -plan.w[2 * i + 1];
  }
  for (uint32_t k = 0; k + 1 < n; ++k) {
    const uint32_t src = n - 1 - k;   // w[n-1] .. w[1]
    const uint32_t dst = m - n + 1 + k;
    b[2 * dst] = plan.w[2 * src];
    b[2 * dst + 1] = -plan.w[2 * src + 1];
  }
  FftBuf bb = uploadVec(b);
  FftBuf spare = alloc(b.size());
  auto pr = potLoop(bb, spare, m, 1);
  std::vector<float> bfft;
  if (!pr.first.valid() || !downloadVec(pr.first, &bfft)) {
    return ChirpPlan{};
  }
  // bb/spare are dead (chirp spectrum is staged to the host plan); return
  // them before the padded passes allocate.
  release(bb);
  release(spare);
  plan.bfft = std::move(bfft);
  chirp_cache_[n] = plan;
  return plan;
}

std::pair<FftBuf, FftBuf> FftChain::fwdHave(const FftBuf& cur,
                                            const FftBuf& nxt, uint32_t n,
                                            uint32_t count) {
  const FftBuf bad;
  if (n == 1) return {cur, nxt};
  if (IsPot(n)) return potLoop(cur, nxt, n, count);
  // Row-chunked Bluestein: only a large m-point expansion blows memory
  // (PoT ping-pong is lean, so it never chunks; small m (tile FFTs,
  // m <= 64) fits the cap and chunking it would only multiply the
  // 17-dispatch Bluestein chain per chunk for memory the pool already
  // survives — measured 3x dispatch growth, net slower). The m-point
  // temporaries hold ~12 buffers of count*m pairs; past the effective
  // threshold (chunk_cells_) cells the live set exceeds the pool cap
  // into evict/realloc churn
  // (full-frame rows: ~2GB). Chunking splits input rows, runs the stock
  // path per chunk, and concatenates — per-row math is untouched
  // (bit-identical; proven base-vs-chunked ndiff=0 on 1.1M pairs).
  // The input spare passes through untouched, preserving the
  // {spectra, spare} contract. n < 2 bypasses (no m-point work exists
  // there; also guards the NextPot(2n-1) wraparound for n == 0).
  if (n >= 2 && count > 0) {
    const uint32_t m = NextPot(2 * n - 1);
    const size_t cells = static_cast<size_t>(count) * m;
    const size_t thresh = chunk_cells_ > 0 ? chunk_cells_ : kChunkCellPairs;
    uint32_t rows_per = 0;
    if (m > 64) rows_per = static_cast<uint32_t>(thresh / m);
    if (rows_per < 64) rows_per = 64;
    // Strictly fewer rows per chunk than total: otherwise a single chunk
    // IS the whole problem (or the 64-row floor binds on giant m) and the
    // recursion below would never reach its base case. Small m (tile
    // FFTs) is exempt outright (see above). Otherwise falls through to
    // the stock path, evict-churn and all — same as before this change.
    if (m > 64 && cells > thresh && rows_per < count) {
      FftBuf out = alloc(static_cast<size_t>(count) * n * 2);
      if (!out.valid()) return {bad, bad};
      uint32_t row = 0;
      Status st = Status::kOk;
      while (row < count && st == Status::kOk) {
        const uint32_t crow =
            std::min(rows_per, static_cast<uint32_t>(count - row));
        FftBuf in_c = alloc(static_cast<size_t>(crow) * n * 2);
        FftBuf sp_c = alloc(static_cast<size_t>(crow) * n * 2);
        if (!in_c.valid() || !sp_c.valid()) {
          st = Status::kInitFailed;
          break;
        }
        st = copyPairs(in_c, 0, cur,
                       static_cast<uint32_t>(static_cast<size_t>(row) * n),
                       static_cast<uint32_t>(static_cast<size_t>(crow) * n));
        if (st != Status::kOk) break;
        auto rc = fwdHave(in_c, sp_c, n, crow);
        if (!rc.first.valid()) {
          st = Status::kDispatchFailed;
          break;
        }
        st = copyPairs(out, static_cast<uint32_t>(static_cast<size_t>(row) * n),
                       rc.first, 0,
                       static_cast<uint32_t>(static_cast<size_t>(crow) * n));
        // Chunk temps dead (copied out or consumed); the input spare is
        // the caller's (untouched, returned below).
        release(in_c);
        release(sp_c);
        release(rc.first);
        row += crow;
      }
      if (st != Status::kOk || row != count) return {bad, bad};
      return {out, nxt};
    }
  }
  // Bluestein (mirrors _forward_have_res). Fully resident: chirp/bfft
  // broadcast, pad, and slice all run on-device (ops 12-14, pure
  // permutes); previously each boundary staged count*m-pair vectors
  // through host downloads/uploads (~200MB each at full-res).
  ChirpPlan plan = bluestein(n);
  if (plan.m == 0) return {bad, bad};
  const uint32_t m = plan.m;
  FftBuf wb = uploadVec(plan.w);
  FftBuf bb = uploadVec(plan.bfft);
  if (!wb.valid() || !bb.valid()) return {bad, bad};
  FftBuf chb = broadcastRow(wb, n, count);
  FftBuf bjb = broadcastRow(bb, m, count);
  release(wb);
  release(bb);
  if (!chb.valid() || !bjb.valid()) return {bad, bad};
  FftBuf pre = cmul(5, cur, chb);
  if (!pre.valid()) return {bad, bad};
  FftBuf cur2 = padRows(pre, n, m, count);
  release(pre);
  if (!cur2.valid()) return {bad, bad};
  FftBuf nxt2 = alloc(static_cast<size_t>(count) * m * 2);
  if (!nxt2.valid()) return {bad, bad};
  auto af = potLoop(cur2, nxt2, m, count);
  if (!af.first.valid()) return {bad, bad};
  FftBuf prod = cmul(5, af.first, bjb);
  FftBuf conv = conjScale(prod, count * m, 1.0f);
  FftBuf nxt3 = alloc(static_cast<size_t>(count) * m * 2);
  if (!nxt3.valid()) return {bad, bad};
  auto iv = potLoop(conv, nxt3, m, count);
  if (!iv.first.valid()) return {bad, bad};
  FftBuf scaled = conjScale(iv.first, count * m, 1.0f / float(m));
  if (!scaled.valid()) return {bad, bad};
  FftBuf slb = sliceRows(scaled, n, m, count);
  release(scaled);
  if (!slb.valid()) return {bad, bad};
  FftBuf post = cmul(5, slb, chb);
  if (!post.valid()) return {bad, bad};
  // Mid-chain release (explicit handles only: every buffer below was
  // created in this function and is consumed — each stage reads its
  // inputs exactly once before the next stage runs — so all are dead.
  // post survives as the result; nxt is the caller's spare, kept valid
  // per contract and reclaimed at fwd1Res/invHave end. Outer live
  // buffers (e.g. correlate's fr across the second fwd2Res) are untouched:
  // a blanket sweep here would release them while live.
  release(chb);
  release(cur2);
  release(nxt2);
  release(af.first);
  release(af.second);
  release(bjb);
  release(prod);
  release(conv);
  release(nxt3);
  release(iv.first);
  release(iv.second);
  release(slb);
  return {post, nxt};
}

FftBuf FftChain::padRows(const FftBuf& in, uint32_t n, uint32_t m,
                         uint32_t count) {
  // On-device Bluestein zero-pad (fft.comp op 12): (count,n) pairs ->
  // (count,m) pairs, zeros past n. Bit-exact mirror of the old host pad
  // loop (copy + zero-fill, zero arithmetic).
  if (!in.valid() || n == 0 || m < n || count == 0 ||
      in.nfloats != static_cast<size_t>(count) * n * 2) {
    return FftBuf{};
  }
  const size_t cells = static_cast<size_t>(count) * m;
  if (cells == 0 || cells > 0xFFFFFFFFu) return FftBuf{};
  const uint32_t np = static_cast<uint32_t>(cells);
  FftBuf out = alloc(cells * 2);
  if (!out.valid()) return FftBuf{};
  const uint32_t pads[6] = {n, m, count, 0, 0, 0};
  if (op(12, in, dummy_, out, np, np, pads) != Status::kOk) return FftBuf{};
  return out;
}

FftBuf FftChain::sliceRows(const FftBuf& in, uint32_t n, uint32_t m,
                           uint32_t count) {
  // On-device Bluestein slice (fft.comp op 13): first n pairs of each
  // m-wide row. Bit-exact mirror of the old host slice loop.
  if (!in.valid() || n == 0 || m < n || count == 0 ||
      in.nfloats != static_cast<size_t>(count) * m * 2) {
    return FftBuf{};
  }
  const size_t cells = static_cast<size_t>(count) * n;
  if (cells == 0 || cells > 0xFFFFFFFFu) return FftBuf{};
  const uint32_t np = static_cast<uint32_t>(cells);
  FftBuf out = alloc(cells * 2);
  if (!out.valid()) return FftBuf{};
  const uint32_t pads[6] = {n, m, count, 0, 0, 0};
  if (op(13, in, dummy_, out, np, np, pads) != Status::kOk) return FftBuf{};
  return out;
}

FftBuf FftChain::broadcastRow(const FftBuf& row, uint32_t n, uint32_t count) {
  // On-device row broadcast (fft.comp op 14): one n-pair row -> count
  // rows. Replaces the host chirp/bfft replication loops (exact copies).
  if (!row.valid() || n == 0 || count == 0 ||
      row.nfloats != static_cast<size_t>(n) * 2) {
    return FftBuf{};
  }
  const size_t cells = static_cast<size_t>(count) * n;
  if (cells == 0 || cells > 0xFFFFFFFFu) return FftBuf{};
  const uint32_t np = static_cast<uint32_t>(cells);
  FftBuf out = alloc(cells * 2);
  if (!out.valid()) return FftBuf{};
  const uint32_t pads[6] = {n, count, 0, 0, 0, 0};
  if (op(14, row, dummy_, out, np, np, pads) != Status::kOk) return FftBuf{};
  return out;
}

FftBuf FftChain::fwd1Have(const FftBuf& cur, const FftBuf& nxt, uint32_t n,
                          uint32_t count) {
  FftBuf r = fwdHave(cur, nxt, n, count).first;
  if (!r.valid()) return FftBuf{};
  // cur/nxt are dead after the pass above, except when the result aliases
  // one of them: PoT/n==1 ping-pong returns cur or nxt itself as the
  // result. Releasing it would leave a dangling handle whose (buf,mem)
  // a later release() could mistake for the slot's next live occupant,
  // so aliased handles are kept (reclaimed by the caller when dead).
  // Nothing belonging to the caller's caller is touched (correlate's fr
  // stays live across the second fwd2Res).
  if (!SameBuf(cur, r)) release(cur);
  if (!SameBuf(nxt, r)) release(nxt);
  return r;
}

FftBuf FftChain::fwd1Res(const std::vector<float>& host_pairs, uint32_t n,
                         uint32_t count) {
  FftBuf cur = uploadVec(host_pairs);
  FftBuf nxt = alloc(host_pairs.size());
  if (!cur.valid() || !nxt.valid()) return FftBuf{};
  return fwd1Have(cur, nxt, n, count);
}

FftBuf FftChain::transpose2d(const FftBuf& in, uint32_t lead, uint32_t h,
                             uint32_t w) {
  // On-device (lead,h,w)->(lead,w,h) pair transpose (fft.comp op 10),
  // bit-exact mirror of TransposeLead below: pure permutation, zero
  // arithmetic, so float bits are moved, never recomputed.
  if (!in.valid() || lead == 0 || h == 0 || w == 0) return FftBuf{};
  const size_t npairs = static_cast<size_t>(lead) * h * w;
  if (npairs > 0xFFFFFFFFu || in.nfloats != npairs * 2) return FftBuf{};
  const uint32_t n = static_cast<uint32_t>(npairs);
  FftBuf out = alloc(npairs * 2);
  if (!out.valid()) return FftBuf{};
  const uint32_t pads[6] = {h, w, lead, 0, 0, 0};
  if (op(10, in, dummy_, out, n, n, pads) != Status::kOk) return FftBuf{};
  return out;
}

FftBuf FftChain::fwd2Have(const FftBuf& pairs, uint32_t lead, uint32_t h,
                         uint32_t w) {
  // Device-input core of fwd2Res: resident pairs → spectra, no upload.
  // Dispatches and order match fwd2Res exactly (rows, transpose, cols,
  // transpose back); consumes `pairs` like fwd1Have consumes its inputs.
  FftBuf spare = alloc(pairs.nfloats);
  if (!spare.valid()) return FftBuf{};
  FftBuf rows = fwd1Have(pairs, spare, w, lead * h);
  if (!rows.valid()) return FftBuf{};
  FftBuf mid = transpose2d(rows, lead, h, w);
  // rows dead (consumed by the transpose); mid is a fresh alloc, so it
  // cannot alias rows and the release is exact.
  release(rows);
  if (!mid.valid()) return FftBuf{};
  FftBuf colsSpare = alloc(mid.nfloats);
  if (!colsSpare.valid()) return FftBuf{};
  FftBuf cols = fwd1Have(mid, colsSpare, h, lead * w);
  if (!cols.valid()) return FftBuf{};
  // Transpose back to (lead,h,w): the arrangement is part of the
  // contract (cmul pairs, greyLowpass image ops, inv2Res rows all assume
  // it). Dropping this (returning cols directly) yields correct spectra
  // in (lead,w,h) order — bit-correct values, transposed arrangement,
  // which reads as full-scale garbage downstream. Still zero host
  // bounce: one extra on-device permute.
  FftBuf out = transpose2d(cols, lead, w, h);
  // cols consumed (mid/colsSpare were settled in fwd1Have: released
  // unless aliased by cols, in which case this reclaims the shared
  // slot — all dead here). out is a fresh alloc, so the release is
  // exact.
  release(cols);
  if (!out.valid()) return FftBuf{};
  return out;
}

FftBuf FftChain::fwd2Res(const std::vector<float>& host_pairs, uint32_t lead,
                         uint32_t h, uint32_t w) {
  // Thin upload wrapper over fwd2Have: the staged path keeps the exact
  // dispatch sequence it always had (upload, rows, transpose, cols,
  // transpose back).
  FftBuf pairs = uploadVec(host_pairs);
  if (!pairs.valid()) return FftBuf{};
  return fwd2Have(pairs, lead, h, w);
}

FftBuf FftChain::real2pairs(const std::vector<float>& real,
                            VkShaderModule corr_mod) {
  // Host reals → resident pairs without a host interleave pass: upload
  // once, corr op 3 interleaves on-device (bit-exact vs ToComplex).
  FftBuf out;
  if (!ok() || real.empty() || real.size() > 0xFFFFFFFFu ||
      corr_mod == VK_NULL_HANDLE) {
    return out;
  }
  FftBuf rv = uploadVec(real);
  if (!rv.valid()) return out;
  FftBuf pairs = alloc(real.size() * 2);
  if (!pairs.valid()) {
    release(rv);
    return out;
  }
  if (SrpReal2PairsGpu(ctx_, static_cast<uint32_t>(real.size()), rv.buf,
                       pairs.buf, corr_mod) != Status::kOk) {
    release(rv);
    release(pairs);
    return FftBuf{};
  }
  // rv consumed by the interleave above; pairs is a fresh alloc.
  release(rv);
  return pairs;
}

FftBuf FftChain::invHave(const FftBuf& cur, const FftBuf& nxt, uint32_t n,
                         uint32_t count) {
  // Conj trick with the per-pass 1/n scale (NOT one 1/(H*W) at the end:
  // same math, different float rounding — downstream argmin ties flip on
  // the bit difference, so this order is load-bearing).
  const uint32_t n_elem = count * n;
  FftBuf conj = conjScale(cur, n_elem, 1.0f);
  auto fw = fwdHave(conj, nxt, n, count);
  if (!fw.first.valid()) return FftBuf{};
  FftBuf r = conjScale(fw.first, n_elem, 1.0f / float(n));
  if (!r.valid()) return FftBuf{};
  // conj was consumed by the forward pass and fw.first exactly once here;
  // both dead. fw.second is the caller's spare: kept valid per contract
  // (inv2Res passes a fresh alloc and reclaims it below).
  release(conj);
  release(fw.first);
  return r;
}

FftBuf FftChain::inv2Res(const FftBuf& prod, uint32_t lead, uint32_t h,
                         uint32_t w) {
  const size_t nfloats =
      static_cast<size_t>(lead) * h * w * 2;
  // Fully resident: inverse rows, on-device transpose, inverse cols,
  // on-device transpose back. Previously each boundary did download →
  // host TransposeLead → upload; only the final spectra leave the device
  // (callers download them). prod belongs to the caller, left alone.
  FftBuf rowsSpare = alloc(nfloats);
  FftBuf rows = invHave(prod, rowsSpare, w, lead * h);
  if (!rows.valid()) return FftBuf{};
  FftBuf mid = transpose2d(rows, lead, h, w);
  // rows dead (consumed); rowsSpare dead unless it aliases rows (PoT
  // ping-pong may return the spare itself — invHave keeps it valid).
  if (!SameBuf(rowsSpare, rows)) release(rowsSpare);
  release(rows);
  if (!mid.valid()) return FftBuf{};
  FftBuf colsSpare = alloc(nfloats);
  FftBuf cols = invHave(mid, colsSpare, h, lead * w);
  if (!cols.valid()) return FftBuf{};
  FftBuf back = transpose2d(cols, lead, w, h);
  // mid dead (consumed) unless aliased by cols; colsSpare likewise; cols
  // dead, and back is a fresh alloc so releasing cols is exact.
  if (!SameBuf(mid, cols)) release(mid);
  if (!SameBuf(colsSpare, cols)) release(colsSpare);
  release(cols);
  if (!back.valid()) return FftBuf{};
  return back;
}

FftBuf FftChain::greyLowpass(const FftBuf& spec, uint32_t h, uint32_t w) {
  const size_t npairs = static_cast<size_t>(h) * w;
  if (npairs == 0 || npairs > 0xFFFFFFu) return FftBuf{};
  const uint32_t n = static_cast<uint32_t>(npairs);
  FftBuf sh = alloc(npairs * 2);
  FftBuf bz = alloc(npairs * 2);
  FftBuf ish = alloc(npairs * 2);
  if (!sh.valid() || !bz.valid() || !ish.valid()) return FftBuf{};
  // pads[6] -> push.pad[1..6]: [N=W, H, ., ., ., dir]. Lanes past H*W
  // early-out in the shader (dispatch width is exactly the n_pairs
  // argument, i.e. n here).
  const uint32_t fwd[6] = {w, h, 0, 0, 0, 0};
  const uint32_t cut[6] = {w, h, 0, 0, 0, 0};
  const uint32_t inv[6] = {w, h, 0, 0, 0, 1};
  if (op(7, spec, dummy_, sh, n, n, fwd) != Status::kOk) return FftBuf{};
  if (op(3, sh, dummy_, bz, n, n, cut) != Status::kOk) return FftBuf{};
  if (op(7, bz, dummy_, ish, n, n, inv) != Status::kOk) return FftBuf{};
  // spec was consumed by the forward pass (grey never re-reads it), sh/bz
  // by the passes above; all dead. NOTE: this consumes the caller's spec
  // handle — pass a handle the caller no longer needs.
  release(spec);
  release(sh);
  release(bz);
  return ish;
}

std::vector<float> FftChain::grey(const std::vector<float>& real, uint32_t h,
                                  uint32_t w) {
  std::vector<float> empty;
  if (!ok() || h < 4 || w < 4 || !FiniteVec(real) ||
      real.size() != static_cast<size_t>(h) * w) {
    return empty;
  }
  FftBuf spec = fwd2Res(ToComplex(real), 1, h, w);
  if (!spec.valid()) return empty;
  FftBuf lp = greyLowpass(spec, h, w);
  if (!lp.valid()) return empty;
  FftBuf back = inv2Res(lp, 1, h, w);
  std::vector<float> back_h;
  if (!back.valid() || !downloadVec(back, &back_h)) return empty;
  std::vector<float> out = RealPart(back_h);
  if (out.size() != static_cast<size_t>(h) * w || !FiniteVec(out)) {
    return empty;
  }
  return out;
}

FftBuf FftChain::splitHalf(const FftBuf& stacked, size_t half_pairs,
                            uint32_t half_index) {
  // Exact copy of one half of stacked spectra (fft.comp op 11, pure
  // permute). half_index 0/1 selects the first/second half.
  if (!stacked.valid() || half_pairs == 0 || half_pairs > 0xFFFFFFFFu ||
      half_index > 1 || stacked.nfloats != half_pairs * 4) {
    return FftBuf{};
  }
  const uint32_t n = static_cast<uint32_t>(half_pairs);
  FftBuf out = alloc(half_pairs * 2);
  if (!out.valid()) return FftBuf{};
  if (copyPairs(out, 0, stacked, half_index * n, n) != Status::kOk) {
    return FftBuf{};
  }
  return out;
}

Status FftChain::copyPairs(const FftBuf& dst, uint32_t dst_off,
                           const FftBuf& src, uint32_t src_off,
                           uint32_t npairs) {
  // Device pair-range copy (fft.comp op 11, pure permute, zero
  // arithmetic). Offsets and length are in pairs; ranges must fit.
  if (!dst.valid() || !src.valid() || npairs == 0 ||
      static_cast<size_t>(dst_off) + npairs > dst.nfloats / 2 ||
      static_cast<size_t>(src_off) + npairs > src.nfloats / 2) {
    return Status::kInitFailed;
  }
  const uint32_t pads[6] = {src_off, dst_off, 0, 0, 0, 0};
  return op(11, src, dummy_, dst, npairs, npairs, pads);
}

std::vector<float> FftChain::correlate(const std::vector<float>& ref,
                                       const std::vector<float>& mov,
                                       uint32_t n, uint32_t s) {
  std::vector<float> empty;
  if (!ok() || !FiniteVec(ref) || !FiniteVec(mov)) return empty;
  // Batched forward: ref+mov stack as one (2n,s,s) problem, so the two
  // same-size forward FFTs share one dispatch sequence (~1/3 fewer
  // dispatches per correlate). Bit-identity: every fft.comp op reads only
  // within-row lanes plus twiddles indexed by within-row position, so
  // each stacked row computes exactly what its separate call computed.
  // Tile-chunked: each chunk is an independent (ct,s,s) correlate, so
  // chunking changes no tile's math (bit-identical) while bounding the
  // live device set and single-dispatch group counts. Without it the
  // full-res finest level (49K tiles) holds ~452MB stacked spectra plus
  // fr/fm/prod live — over the pool cap into swap/OOM (status-4 death).
  const size_t tile = static_cast<size_t>(s) * s;
  if (n == 0 || s == 0 || n > 0x3FFFFFFFu ||
      ref.size() != static_cast<size_t>(n) * tile ||
      mov.size() != ref.size()) {
    return empty;
  }
  constexpr uint32_t kChunkTiles = 4096;
  std::vector<float> out(ref.size());
  std::vector<float> stacked;
  uint32_t done = 0;
  while (done < n) {
    const uint32_t ct = std::min(kChunkTiles, n - done);
    const size_t off = static_cast<size_t>(done) * tile;
    const size_t clen = static_cast<size_t>(ct) * tile;
    const std::vector<float> ref_c =
        ToComplex(std::vector<float>(ref.begin() + off, ref.begin() + off + clen));
    const std::vector<float> mov_c =
        ToComplex(std::vector<float>(mov.begin() + off, mov.begin() + off + clen));
    stacked.clear();
    stacked.reserve(clen * 4);
    stacked.insert(stacked.end(), ref_c.begin(), ref_c.end());
    stacked.insert(stacked.end(), mov_c.begin(), mov_c.end());
    FftBuf both = fwd2Res(stacked, 2 * ct, s, s);
    if (!both.valid()) return empty;
    const size_t half = clen;
    FftBuf fr = splitHalf(both, half, 0);
    FftBuf fm = splitHalf(both, half, 1);
    // both dead (split read it exactly once per half); out bufs are fresh
    // allocs so the releases are exact.
    release(both);
    if (!fr.valid() || !fm.valid()) return empty;
    FftBuf prod = cmul(6, fm, fr);
    if (!prod.valid()) return empty;
    FftBuf back = inv2Res(prod, ct, s, s);
    std::vector<float> back_h;
    if (!back.valid() || !downloadVec(back, &back_h)) return empty;
    const std::vector<float> chunk = RealPart(FftShift2D(back_h, ct, s, s));
    if (chunk.size() != clen) return empty;
    std::memcpy(out.data() + off, chunk.data(), clen * 4);
    // fr/fm consumed by cmul, prod by the inverse passes, back by the
    // download above: every device buffer here is a correlate-local and
    // all are dead, so return them to the pool before the next chunk.
    release(fr);
    release(fm);
    release(prod);
    release(back);
    done += ct;
  }
  if (!FiniteVec(out)) return empty;
  return out;
}

FftBuf FftChain::pinRefSpectra(const std::vector<float>& ref, uint32_t n,
                               uint32_t s, VkShaderModule corr_mod) {
  // One-shot ref forward, unbatched (lead=n): device pairs via real2pairs,
  // then the fwd2Have pass sequence. The caller adopts the arena buffer
  // into a resident before chain death.
  FftBuf bad;
  if (!ok() || !FiniteVec(ref) || n == 0 || s == 0 || n > 0x3FFFFFFFu ||
      ref.size() != static_cast<size_t>(n) * s * s ||
      corr_mod == VK_NULL_HANDLE) {
    return bad;
  }
  FftBuf pairs = real2pairs(ref, corr_mod);
  if (!pairs.valid()) return bad;
  return fwd2Have(pairs, n, s, s);
}

std::vector<float> FftChain::correlatePinned(const FftBuf& ref_spec,
                                             const std::vector<float>& mov,
                                             uint32_t n, uint32_t s,
                                             VkShaderModule corr_mod) {
  // Pinned correlate: same chunking and per-chunk order as correlate()
  // minus the ref half. ref_spec is borrowed (whole-level resident
  // spectra); chunk slices are device copies, never host bounces.
  std::vector<float> empty;
  const size_t tile = static_cast<size_t>(s) * s;
  if (!ok() || !FiniteVec(mov) || !ref_spec.valid() || n == 0 || s == 0 ||
      n > 0x3FFFFFFFu || mov.size() != static_cast<size_t>(n) * tile ||
      ref_spec.nfloats != static_cast<size_t>(n) * tile * 2 ||
      corr_mod == VK_NULL_HANDLE) {
    return empty;
  }
  constexpr uint32_t kChunkTiles = 4096;
  std::vector<float> out(mov.size());
  uint32_t done = 0;
  while (done < n) {
    const uint32_t ct = std::min(kChunkTiles, n - done);
    const size_t off = static_cast<size_t>(done) * tile;
    const size_t clen = static_cast<size_t>(ct) * tile;
    // copyPairs offsets are uint32 pairs: fail closed past 4G pairs.
    if (off > 0xFFFFFFFFu || clen > 0xFFFFFFFFu) return empty;
    FftBuf mov_pairs = real2pairs(
        std::vector<float>(mov.begin() + off, mov.begin() + off + clen),
        corr_mod);
    if (!mov_pairs.valid()) return empty;
    FftBuf fm = fwd2Have(mov_pairs, ct, s, s);
    if (!fm.valid()) return empty;
    // Slice this chunk's ref spectra on-device (pair copy, no host).
    FftBuf fr = alloc(clen * 2);
    if (!fr.valid()) return empty;
    if (copyPairs(fr, 0, ref_spec, static_cast<uint32_t>(off),
                  static_cast<uint32_t>(clen)) != Status::kOk) {
      return empty;
    }
    FftBuf prod = cmul(6, fm, fr);
    if (!prod.valid()) return empty;
    FftBuf back = inv2Res(prod, ct, s, s);
    std::vector<float> back_h;
    if (!back.valid() || !downloadVec(back, &back_h)) return empty;
    const std::vector<float> chunk = RealPart(FftShift2D(back_h, ct, s, s));
    if (chunk.size() != clen) return empty;
    std::memcpy(out.data() + off, chunk.data(), clen * 4);
    // fm consumed by cmul, fr by cmul, prod by the inverse passes, back
    // by the download, mov_pairs by the forward: every device buffer
    // here is a correlate-local and all are dead, so return them to the
    // pool before the next chunk. ref_spec is caller-owned: untouched.
    release(fm);
    release(fr);
    release(prod);
    release(back);
    release(mov_pairs);
    done += ct;
  }
  if (!FiniteVec(out)) return empty;
  return out;
}

Status SrpGreyFftGpu(Context* ctx, uint32_t w, uint32_t h,
                     const float* in, float* out,
                     VkShaderModule shader_module) {
  if (ctx == nullptr || w < 4 || h < 4 || in == nullptr || out == nullptr ||
      shader_module == VK_NULL_HANDLE) {
    return Status::kInitFailed;
  }
  auto* device = static_cast<VkDevice>(NativeDevice(ctx));
  if (device == VK_NULL_HANDLE) return Status::kNoDevice;
  const size_t n = static_cast<size_t>(w) * h;
  FftChain chain(ctx, shader_module);
  if (!chain.ok()) return Status::kNoDevice;
  const std::vector<float> in_v(in, in + n);
  const std::vector<float> grey = chain.grey(in_v, h, w);
  if (grey.size() != n) return Status::kDispatchFailed;
  std::memcpy(out, grey.data(), n * 4);
  return Status::kOk;
}

std::vector<float> FftChain::greyHave(const FftBuf& real, uint32_t h,
                                      uint32_t w, VkShaderModule corr_mod) {
  // Resident-input grey: same passes as grey() with the host upload +
  // interleave swapped for on-device real2pairs (bit-exact vs ToComplex,
  // pure interleave). `real` is borrowed (caller-owned resident, never
  // released here).
  std::vector<float> empty;
  const size_t n = static_cast<size_t>(h) * w;
  if (!ok() || !real.valid() || real.nfloats != n || h < 4 || w < 4 ||
      n > 0xFFFFFFFFu || corr_mod == VK_NULL_HANDLE) {
    return empty;
  }
  FftBuf pairs = alloc(2 * n);
  if (!pairs.valid()) return empty;
  if (SrpReal2PairsGpu(ctx_, static_cast<uint32_t>(n), real.buf, pairs.buf,
                       corr_mod) != Status::kOk) {
    release(pairs);
    return empty;
  }
  FftBuf spec = fwd2Have(pairs, 1, h, w);
  if (!spec.valid()) {
    release(pairs);
    return empty;
  }
  FftBuf lp = greyLowpass(spec, h, w);
  if (!lp.valid()) return empty;
  FftBuf back = inv2Res(lp, 1, h, w);
  std::vector<float> back_h;
  if (!back.valid() || !downloadVec(back, &back_h)) return empty;
  std::vector<float> out = RealPart(back_h);
  // pairs consumed by the forward, spec by the lowpass, lp by the
  // inverse, back by the download: return the dead handles like
  // correlatePinned does (no-ops when already released).
  release(pairs);
  release(spec);
  release(lp);
  release(back);
  if (out.size() != n || !FiniteVec(out)) return empty;
  return out;
}

namespace {
// Radices <= 32 whose product is n (largest-first 4s, then 2, then odd
// primes); empty when n has a prime factor > 32 (Bluestein fallback).
std::vector<uint32_t> MixedRadices(uint32_t n) {
  std::vector<uint32_t> out;
  while (n % 4 == 0) { out.push_back(4); n /= 4; }
  while (n % 2 == 0) { out.push_back(2); n /= 2; }
  for (uint32_t p = 3; p <= 31 && n > 1; p += 2) {
    while (n % p == 0) { out.push_back(p); n /= p; }
  }
  if (n != 1) out.clear();
  return out;
}

Status GreyDispatch(Context* ctx, VkBuffer a, VkBuffer b, SrpHmsrPush* push,
                    VkShaderModule mod) {
  VkBuffer buffers[2] = {a, b};
  uint64_t groups = (static_cast<uint64_t>(push->pixel_count) + 255) / 256;
  if (groups > 65535) groups = 65535;
  return SrpDispatchGroups(ctx, buffers, 2, push, sizeof(*push), 256,
                           static_cast<uint32_t>(groups), mod);
}
}  // namespace

// Mixed-radix Stockham grey (grey_fft.comp): true-length transforms, no
// Bluestein padding, no host staging. Returns kInitFailed when an axis has
// a prime factor > 32 (callers fall back to the Bluestein chain).
Status SrpGreyFftMixedGpu(Context* ctx, uint32_t w, uint32_t h,
                          VkBuffer real_buffer, float* out,
                          VkShaderModule grey_module) {
  if (ctx == nullptr || w < 4 || h < 4 || (w & 1u) || (h & 1u) ||
      real_buffer == VK_NULL_HANDLE || out == nullptr ||
      grey_module == VK_NULL_HANDLE) {
    return Status::kInitFailed;
  }
  const std::vector<uint32_t> rx = MixedRadices(w), ry = MixedRadices(h);
  if (rx.empty() || ry.empty()) return Status::kInitFailed;
  const size_t n = static_cast<size_t>(w) * h;
  if (n > 0x0FFFFFFFu) return Status::kInitFailed;
  const int64_t ca = SrpCreateResident(ctx, 2 * n);
  const int64_t cb = SrpCreateResident(ctx, 2 * n);
  const int64_t ro = SrpCreateResident(ctx, n);
  Status st = Status::kInitFailed;
  VkBuffer A = VK_NULL_HANDLE, B = VK_NULL_HANDLE, R = VK_NULL_HANDLE;
  if (ca != 0 && cb != 0 && ro != 0 &&
      SrpResidentBuffer(ctx, ca, 2 * n, &A) == Status::kOk &&
      SrpResidentBuffer(ctx, cb, 2 * n, &B) == Status::kOk &&
      SrpResidentBuffer(ctx, ro, n, &R) == Status::kOk) {
    auto base = [&](uint32_t mode, uint32_t count) {
      SrpHmsrPush p{};
      p.width = w; p.height = h; p.mode = mode; p.pixel_count = count; p.scale = 1.0f;
      return p;
    };
    VkBuffer cur = A, nxt = B;
    SrpHmsrPush pk = base(0, static_cast<uint32_t>(n));
    st = GreyDispatch(ctx, real_buffer, cur, &pk, grey_module);
    auto axis = [&](const std::vector<uint32_t>& radices, uint32_t len,
                    uint32_t lines, uint32_t is_cols, uint32_t inverse) {
      uint32_t ns = 1;
      for (uint32_t r : radices) {
        if (st != Status::kOk) return;
        SrpHmsrPush pp = base(1, lines * (len / r));
        pp.pad[0] = len; pp.pad[1] = r; pp.pad[2] = ns; pp.pad[3] = is_cols;
        pp.pad[4] = inverse;
        st = GreyDispatch(ctx, cur, nxt, &pp, grey_module);
        std::swap(cur, nxt);
        ns *= r;
      }
    };
    axis(rx, w, h, 0, 0);   // forward rows
    axis(ry, h, w, 1, 0);   // forward cols
    if (st == Status::kOk) {
      SrpHmsrPush pm = base(2, static_cast<uint32_t>(n));
      st = GreyDispatch(ctx, cur, nxt, &pm, grey_module);
      std::swap(cur, nxt);
    }
    axis(rx, w, h, 0, 1);   // inverse rows
    axis(ry, h, w, 1, 1);   // inverse cols
    if (st == Status::kOk) {
      SrpHmsrPush pr = base(3, static_cast<uint32_t>(n));
      pr.scale = 1.0f / static_cast<float>(n);   // ifft2 normalization
      st = GreyDispatch(ctx, cur, R, &pr, grey_module);
    }
    if (st == Status::kOk) st = SrpDownloadResident(ctx, ro, out, n);
  }
  if (ca != 0) SrpFreeResident(ctx, ca);
  if (cb != 0) SrpFreeResident(ctx, cb);
  if (ro != 0) SrpFreeResident(ctx, ro);
  return st;
}

Status SrpGreyFftHaveGpu(Context* ctx, uint32_t w, uint32_t h,
                         VkBuffer real_buffer, float* out,
                         VkShaderModule fft_module,
                         VkShaderModule corr_module) {
  if (ctx == nullptr || w < 4 || h < 4 ||
      real_buffer == VK_NULL_HANDLE || out == nullptr ||
      fft_module == VK_NULL_HANDLE || corr_module == VK_NULL_HANDLE) {
    return Status::kInitFailed;
  }
  auto* device = static_cast<VkDevice>(NativeDevice(ctx));
  if (device == VK_NULL_HANDLE) return Status::kNoDevice;
  const size_t n = static_cast<size_t>(w) * h;
  FftChain chain(ctx, fft_module);
  if (!chain.ok()) return Status::kNoDevice;
  const FftBuf real{real_buffer, VK_NULL_HANDLE, n};
  const std::vector<float> grey = chain.greyHave(real, h, w, corr_module);
  if (grey.size() != n) return Status::kDispatchFailed;
  std::memcpy(out, grey.data(), n * 4);
  return Status::kOk;
}

Status SrpCorrelateGpu(Context* ctx, uint32_t tile_count, uint32_t s,
                       const float* ref, const float* mov, float* corrs,
                       VkShaderModule shader_module) {
  if (ctx == nullptr || tile_count == 0 || s == 0 || ref == nullptr ||
      mov == nullptr || corrs == nullptr ||
      shader_module == VK_NULL_HANDLE) {
    return Status::kInitFailed;
  }
  auto* device = static_cast<VkDevice>(NativeDevice(ctx));
  if (device == VK_NULL_HANDLE) return Status::kNoDevice;
  const size_t n = static_cast<size_t>(tile_count) * s * s;
  FftChain chain(ctx, shader_module);
  if (!chain.ok()) return Status::kNoDevice;
  const std::vector<float> ref_v(ref, ref + n);
  const std::vector<float> mov_v(mov, mov + n);
  const std::vector<float> out =
      chain.correlate(ref_v, mov_v, tile_count, s);
  if (out.size() != n) return Status::kDispatchFailed;
  std::memcpy(corrs, out.data(), n * 4);
  return Status::kOk;
}

Status SrpPinRefSpectraGpu(Context* ctx, uint32_t tile_count, uint32_t s,
                           const float* ref, VkBuffer refspec_buffer,
                           VkShaderModule fft_module,
                           VkShaderModule corr_module) {
  if (ctx == nullptr || tile_count == 0 || s == 0 || ref == nullptr ||
      refspec_buffer == VK_NULL_HANDLE || fft_module == VK_NULL_HANDLE ||
      corr_module == VK_NULL_HANDLE) {
    return Status::kInitFailed;
  }
  auto* device = static_cast<VkDevice>(NativeDevice(ctx));
  if (device == VK_NULL_HANDLE) return Status::kNoDevice;
  const size_t n = static_cast<size_t>(tile_count) * s * s;
  FftChain chain(ctx, fft_module);
  if (!chain.ok()) return Status::kNoDevice;
  const std::vector<float> ref_v(ref, ref + n);
  FftBuf spec = chain.pinRefSpectra(ref_v, tile_count, s, corr_module);
  if (!spec.valid() || spec.nfloats != n * 2) {
    return Status::kDispatchFailed;
  }
  // Adopt into the caller-owned resident: device pair-copy (exact), then
  // the arena buffer dies with the chain while the resident survives.
  // The wrapper is dispatch-only (never tracked/released/mapped).
  const FftBuf dst{refspec_buffer, VK_NULL_HANDLE, n * 2};
  if (n > 0xFFFFFFFFu ||
      chain.copyPairs(dst, 0, spec, 0, static_cast<uint32_t>(n)) !=
          Status::kOk) {
    return Status::kDispatchFailed;
  }
  return Status::kOk;
}

Status SrpCorrelatePinnedGpu(Context* ctx, uint32_t tile_count, uint32_t s,
                             const float* mov, float* corrs,
                             VkBuffer refspec_buffer,
                             VkShaderModule fft_module,
                             VkShaderModule corr_module) {
  if (ctx == nullptr || tile_count == 0 || s == 0 || mov == nullptr ||
      corrs == nullptr || refspec_buffer == VK_NULL_HANDLE ||
      fft_module == VK_NULL_HANDLE || corr_module == VK_NULL_HANDLE) {
    return Status::kInitFailed;
  }
  auto* device = static_cast<VkDevice>(NativeDevice(ctx));
  if (device == VK_NULL_HANDLE) return Status::kNoDevice;
  const size_t n = static_cast<size_t>(tile_count) * s * s;
  FftChain chain(ctx, fft_module);
  if (!chain.ok()) return Status::kNoDevice;
  // Borrowed wrapper: dispatch-only, never tracked/released (the
  // resident outlives the chain by design).
  const FftBuf ref_spec{refspec_buffer, VK_NULL_HANDLE, n * 2};
  const std::vector<float> mov_v(mov, mov + n);
  const std::vector<float> out =
      chain.correlatePinned(ref_spec, mov_v, tile_count, s, corr_module);
  if (out.size() != n) return Status::kDispatchFailed;
  std::memcpy(corrs, out.data(), n * 4);
  return Status::kOk;
}

}  // namespace srp
