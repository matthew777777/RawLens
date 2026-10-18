#pragma once

#include <cstddef>
#include <cstdint>
#include <initializer_list>
#include <map>
#include <utility>
#include <vector>

#include <vulkan/vulkan.h>

#include "srp_context.h"

// Resident fft.comp chain (port of host/gpu_fft.py resident functions).
//
// Complex data is interleaved float pairs ([re,im], _f32_view layout) in
// host-visible coherent buffers; all transients are arena-owned by the
// chain. Lifetime discipline: each composite stage (fwd/inv/bluestein)
// releases every arena buffer it created except the handle(s) it returns
// (releaseExcept), so only live values occupy device memory; freeAll
// releases the remainder at chain end. A released buffer is dead by
// construction (never read again — the bit-identity gate would catch any
// violation), so pool reacquire of the same slot mid-chain is safe.
// dummy_ is never released mid-chain. Dispatch order, push bytes, table
// math, and host staging (transposes, Bluestein pad/slice, fftshift) mirror
// the port exactly; only the pool/checkout bookkeeping is replaced by the
// arena. Internal header (NOT public API).
namespace srp {

constexpr uint32_t kFftLocalSize = 256;

struct FftBuf {
  VkBuffer buf = VK_NULL_HANDLE;
  VkDeviceMemory mem = VK_NULL_HANDLE;
  size_t nfloats = 0;
  bool valid() const { return buf != VK_NULL_HANDLE; }
};

class FftChain {
 public:
  FftChain(Context* ctx, VkShaderModule mod);
  ~FftChain();

  bool ok() const { return device_ != VK_NULL_HANDLE; }

  // Arena buffers (host-visible coherent). alloc leaves contents undefined
  // (every fft.comp op pure-writes its grid lanes); upload copies `v`.
  FftBuf alloc(size_t nfloats);
  FftBuf uploadVec(const std::vector<float>& v);
  bool downloadVec(const FftBuf& b, std::vector<float>* out);
  void freeAll();

  // One fft.comp dispatch: B must be bound (dummy for ops 0/4).
  Status op(uint32_t opcode, const FftBuf& A, const FftBuf& B,
            const FftBuf& C, uint32_t n_pairs, uint32_t grid,
            const uint32_t pads[6]);

  // Chain stages. Inputs are borrowed (arena frees); returned buffers are
  // arena-owned. A null (.invalid) buffer marks failure — callers abort.
  std::pair<FftBuf, FftBuf> potLoop(const FftBuf& cur, const FftBuf& nxt,
                                    uint32_t n, uint32_t count);
  FftBuf conjScale(const FftBuf& in, uint32_t n_pairs, float scale);
  FftBuf cmul(uint32_t opcode, const FftBuf& A, const FftBuf& B);
  // Returns {spectra, spare}.
  std::pair<FftBuf, FftBuf> fwdHave(const FftBuf& cur, const FftBuf& nxt,
                                    uint32_t n, uint32_t count);
  // Conj trick; `cur` borrowed, 1/n scaled.
  FftBuf invHave(const FftBuf& cur, const FftBuf& nxt, uint32_t n,
                 uint32_t count);
  // Host complex vector in (pairs) → resident spectra.
  FftBuf fwd1Res(const std::vector<float>& host_pairs, uint32_t n,
                 uint32_t count);
  // Device-input core of fwd1Res (no upload): resident pairs → spectra.
  FftBuf fwd1Have(const FftBuf& cur, const FftBuf& nxt, uint32_t n,
                  uint32_t count);
  // On-device (lead,h,w)->(lead,w,h) pair transpose (fft.comp op 10):
  // bit-exact mirror of TransposeLead (pure permutation, zero arithmetic).
  FftBuf transpose2d(const FftBuf& in, uint32_t lead, uint32_t h, uint32_t w);
  // Device pair-range copy C[dst..dst+n) = A[src..src+n) (fft.comp op 11,
  // pure permute). Ranges must fit; fail-closed (kInitFailed, no OOB).
  Status copyPairs(const FftBuf& dst, uint32_t dst_off, const FftBuf& src,
                   uint32_t src_off, uint32_t npairs);
  // Bluestein staging ops 12-14 (all pure permutes, zero arithmetic):
  // zero-pad rows n->m, slice first n of m-wide rows, broadcast one row.
  FftBuf padRows(const FftBuf& in, uint32_t n, uint32_t m, uint32_t count);
  FftBuf sliceRows(const FftBuf& in, uint32_t n, uint32_t m, uint32_t count);
  FftBuf broadcastRow(const FftBuf& row, uint32_t n, uint32_t count);
  // Exact copy of one half of stacked spectra (fft.comp op 11, pure
  // permute). half_index 0/1 selects the first/second half.
  FftBuf splitHalf(const FftBuf& stacked, size_t half_pairs,
                   uint32_t half_index);
  // Device-input core of fwd2Res (no upload): resident pairs →
  // resident spectra. Same dispatches, same order as fwd2Res minus the
  // upload. Consumes `pairs` (released unless aliased by the result,
  // exactly like fwd1Have) — the caller must not reuse the handle.
  FftBuf fwd2Have(const FftBuf& pairs, uint32_t lead, uint32_t h,
                  uint32_t w);
  // Host real vector → resident pairs via corr op 3 (SrpReal2PairsGpu):
  // upload once, interleave on-device. Bit-exact vs ToComplex (pure
  // interleave). Needs the corr module. Consumes nothing (fresh allocs);
  // the caller owns the returned pairs.
  FftBuf real2pairs(const std::vector<float>& real,
                    VkShaderModule corr_mod);
  // Host (lead,h,w) pairs → resident (lead,h,w) spectra. Fully resident:
  // upload once, rows FFT, on-device transpose, cols FFT, on-device
  // transpose back (no host bounce). The back-transpose is load-bearing:
  // the (lead,h,w) arrangement is the contract, not just the values.
  FftBuf fwd2Res(const std::vector<float>& host_pairs, uint32_t lead,
                 uint32_t h, uint32_t w);
  // Resident product → resident (lead,h,w) image (consumes nothing;
  // caller frees `prod` when done, mirroring the port). Fully resident:
  // inverse rows, on-device transpose, inverse cols, transpose back.
  FftBuf inv2Res(const FftBuf& prod, uint32_t lead, uint32_t h, uint32_t w);
  // Resident spectra → resident lowpassed spectra: device fftshift (op 7
  // fwd), outer-quarter zeroing (op 3), device ifftshift (op 7 inv).
  // Consumes `spec` (released before return; the caller must not reuse the
  // handle — grey() holds no further use for it).
  FftBuf greyLowpass(const FftBuf& spec, uint32_t h, uint32_t w);
  // Full on-device FFT lowpass grey: real (h,w) in → real (h,w) out.
  std::vector<float> grey(const std::vector<float>& real, uint32_t h,
                          uint32_t w);

  // Full circular correlation: real (n,s,s) in → fftshifted real (n,s,s).
  // Mirrors device_correlate minus its ref-spectra cache (no cross-call
  // state; a later pass may pin ref spectra). Returns empty on failure.
  std::vector<float> correlate(const std::vector<float>& ref,
                               const std::vector<float>& mov, uint32_t n,
                               uint32_t s);
  // Pinned ref spectra: host real (n,s,s) → resident (n,s,s) spectra,
  // computed once per level. Same forward passes as correlate()'s ref
  // half, unbatched (lead=n): per-row math is identical (twiddles are
  // indexed by within-row position), so downstream cmul/inv match the
  // staged path bit-exactly. Needs the corr module (real2pairs). The
  // caller adopts the returned arena buffer into a resident (or consumes
  // it before chain death); empty on failure.
  FftBuf pinRefSpectra(const std::vector<float>& ref, uint32_t n,
                       uint32_t s, VkShaderModule corr_mod);
  // Pinned circular correlation: mov real (n,s,s) + resident ref spectra
  // → fftshifted real (n,s,s). Same chunking (4096) and same per-chunk
  // dispatch order as correlate() minus the ref half (no stacking, no
  // split): mov real2pairs, mov-forward, slice ref chunk, cmul, inverse,
  // download, host fftshift. `ref_spec` is borrowed (caller-owned, never
  // released here). Returns empty on failure.
  std::vector<float> correlatePinned(const FftBuf& ref_spec,
                                     const std::vector<float>& mov,
                                     uint32_t n, uint32_t s,
                                     VkShaderModule corr_mod);

  // Pure-host complex helpers (interleaved pairs), mirroring the port's
  // numpy staging exactly.
  static std::vector<float> ToComplex(const std::vector<float>& real);
  static std::vector<float> RealPart(const std::vector<float>& pairs);
  static std::vector<float> TransposeLead(const std::vector<float>& v,
                                          uint32_t lead, uint32_t h,
                                          uint32_t w);
  static std::vector<float> FftShift2D(const std::vector<float>& v,
                                       uint32_t lead, uint32_t h, uint32_t w);
  static std::vector<float> BuildTwiddles(uint32_t n);
  struct ChirpPlan {
    uint32_t m = 0;
    std::vector<float> w;     // a_chirp, n pairs
    std::vector<float> bfft;  // B_fft, m pairs (device-computed)
  };
  ChirpPlan bluestein(uint32_t n);  // cached per chain, like _BLUESTEIN_CACHE
  static bool FiniteVec(const std::vector<float>& v);

 private:
  Context* ctx_ = nullptr;
  VkDevice device_ = VK_NULL_HANDLE;
  VkShaderModule mod_ = VK_NULL_HANDLE;
  // False under SRP_NO_MIDCHAIN_RELEASE=1 (before/after measurement).
  bool midchain_release_ = true;
  // Row-chunk threshold (count*m cells), SRP_CHUNK_CELLS when set.
  size_t chunk_cells_ = 0;
  FftBuf dummy_;
  std::vector<FftBuf> arena_;
  std::map<uint32_t, std::vector<float>> twiddle_cache_;
  std::map<uint32_t, ChirpPlan> chirp_cache_;
  FftBuf track(VkBuffer b, VkDeviceMemory m, size_t nfloats);
  void flushWritten(VkDeviceMemory mem, VkDeviceSize size);
  void invalidateBeforeRead(VkDeviceMemory mem, VkDeviceSize size);
  // Returns one arena buffer to the pool early (no-op when absent, never
  // dummy_). Matches by (buf,mem) slot: the caller must never release a
  // handle that aliases a live result (PoT ping-pong can return the input
  // or spare itself) — a dangling handle would later match the slot's
  // next occupant and kill it. Guarded at fwd1Res/fwd2Res/inv2Res ends.
  void release(const FftBuf& b);
  void releaseExcept(std::initializer_list<FftBuf> keep);
};

}  // namespace srp
