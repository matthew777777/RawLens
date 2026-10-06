#include <MediaCinemaRAW/Encoder.h>
// SPDX-License-Identifier: GPL-3.0-only
#include <algorithm>
#include <stdexcept>
#include <cstring>
#include <thread>
#if defined(__ARM_NEON)
#include <arm_neon.h>
#elif defined(__SSE2__) || defined(_M_X64) || (defined(_M_IX86_FP) && _M_IX86_FP >= 2)
#include <emmintrin.h>
#define MCRAW_HAVE_SSE2 1
#endif

namespace mediacinemaraw {
namespace {
void u32(std::vector<uint8_t>& out, uint32_t n) {
    for (int i = 0; i < 4; ++i) out.push_back(n >> (8*i));
}
void patch(std::vector<uint8_t>& out, size_t pos, uint32_t n) {
    for (int i = 0; i < 4; ++i) out[pos+i] = n >> (8*i);
}
// The decoder uses eight interleaved lanes, not a conventional contiguous bitstream.
// Fast paths: 0 writes nothing, 8 truncates to bytes, 16 memcpys literals.
void pack(std::vector<uint8_t>& out, const uint16_t* p, int bits) {
    if (!bits) return;
    const size_t offset = out.size();
    out.resize(offset + (bits == 16 ? 128 : bits*8));
    uint8_t* dst = out.data()+offset;
    if (bits == 16) {
        // Android targets are little-endian, matching the codec's literal representation.
        std::memcpy(dst,p,128);
    } else if (bits == 8) {
#if defined(__ARM_NEON)
        for (int i = 0; i < 64; i += 8)
            vst1_u8(dst+i,vmovn_u16(vld1q_u16(p+i)));
#elif defined(MCRAW_HAVE_SSE2)
        // Deltas are < 256, so saturating pack is exact.
        for (int i = 0; i < 64; i += 16)
            _mm_storeu_si128(reinterpret_cast<__m128i*>(dst+i),
                _mm_packus_epi16(_mm_loadu_si128(reinterpret_cast<const __m128i*>(p+i)),
                                 _mm_loadu_si128(reinterpret_cast<const __m128i*>(p+i+8))));
#else
        for (int i = 0; i < 64; ++i) dst[i] = p[i];
#endif
    } else if (bits == 3 || bits == 5 || bits == 6) {
        uint8_t* packed = dst;
        for (int lane = 0; lane < 8; ++lane) {
            uint16_t v[8];
            for (int g = 0; g < 8; ++g) v[g] = p[g*8+lane];
            if (bits == 3) {
                packed[lane] = v[0] | (v[1]<<3) | ((v[2]&3)<<6);
                packed[8+lane] = v[3] | (v[4]<<3) | ((v[5]&3)<<6);
                packed[16+lane] = v[6] | (v[7]<<3) | ((v[2]>>2)<<6) | ((v[5]>>2)<<7);
            } else if (bits == 5) {
                packed[lane] = v[0] | ((v[5]&7)<<5);
                packed[8+lane] = v[1] | ((v[6]&7)<<5);
                packed[16+lane] = v[2] | ((v[7]&7)<<5);
                packed[24+lane] = v[3] | ((v[5]>>3)<<5) | (((v[7]>>3)&1)<<7);
                packed[32+lane] = v[4] | ((v[6]>>3)<<5) | ((v[7]>>4)<<7);
            } else {
                for (int g = 0; g < 6; ++g)
                    packed[g*8+lane] = v[g] | (((v[6+g/3]>>(2*(g%3)))&3)<<6);
            }
        }

    } else if (bits == 10) {
        for (int half = 0; half < 2; ++half) {
#if defined(__ARM_NEON)
            for (int i = 0; i < 32; i += 8)
                vst1_u8(dst+half*40+i,vmovn_u16(vld1q_u16(p+half*32+i)));
#elif defined(MCRAW_HAVE_SSE2)
            // Deltas reach 1023: mask to low bytes first, packus saturates.
            const __m128i lobits = _mm_set1_epi16(0x00FF);
            for (int i = 0; i < 32; i += 16) {
                __m128i a = _mm_and_si128(_mm_loadu_si128(
                    reinterpret_cast<const __m128i*>(p+half*32+i)), lobits);
                __m128i b = _mm_and_si128(_mm_loadu_si128(
                    reinterpret_cast<const __m128i*>(p+half*32+i+8)), lobits);
                _mm_storeu_si128(reinterpret_cast<__m128i*>(dst+half*40+i),
                                 _mm_packus_epi16(a,b));
            }
#else
            for (int i = 0; i < 32; ++i) dst[half*40+i] = p[half*32+i];
#endif
            for (int lane = 0; lane < 8; ++lane) {
                uint8_t hi = 0;
                for (int group = 0; group < 4; ++group)
                    hi |= (p[half*32+group*8+lane] >> 8) << (group*2);
                dst[half*40+32+lane] = hi;
            }
        }
    } else {
        const int groups = 8 / bits;
        for (int base = 0; base < 64; base += groups*8)
            for (int lane = 0; lane < 8; ++lane) {
                uint8_t v = 0;
                for (int g = 0; g < groups; ++g) v |= p[base+g*8+lane] << (g*bits);
                *dst++ = v;
            }
    }
}
int bitWidth(uint16_t delta) {
    if (!delta) return 0;
    for (int b : {1, 2, 3, 4, 5, 6, 8, 10}) if (delta < (1u << b)) return b;
    return 16;
}
// Split 2*n contiguous Bayer samples into n even + n odd channel entries.
// src is 2-aligned (even x); unaligned SIMD loads/stores are used throughout.
// Like pack()'s 16-bit path, the native u16 loads assume a little-endian host.
void deintN(const uint16_t* src, uint16_t* even, uint16_t* odd, int n) {
#if defined(__ARM_NEON)
    int i = 0;
    for (; i+8 <= n; i += 8) {
        uint16x8x2_t v = vld2q_u16(src+i*2);
        vst1q_u16(even+i,v.val[0]); vst1q_u16(odd+i,v.val[1]);
    }
    for (; i < n; ++i) { even[i] = src[i*2]; odd[i] = src[i*2+1]; }
#elif defined(MCRAW_HAVE_SSE2)
    int i = 0;
    for (; i+4 <= n; i += 4) {
        __m128i v = _mm_loadu_si128(reinterpret_cast<const __m128i*>(src+i*2));
        // t = [e0 e1 o0 o1 e2 e3 o2 o3]; s rotates dwords to [d2 d3 d0 d1]
        // so unpacklo interleaves [d0 d2 d1 d3] = evens, odds.
        __m128i t = _mm_shufflehi_epi16(_mm_shufflelo_epi16(v, _MM_SHUFFLE(3,1,2,0)),
                                        _MM_SHUFFLE(3,1,2,0));
        __m128i s = _mm_shuffle_epi32(t, _MM_SHUFFLE(1,0,3,2));
        __m128i u = _mm_unpacklo_epi32(t,s);
        _mm_storel_epi64(reinterpret_cast<__m128i*>(even+i), u);
        _mm_storel_epi64(reinterpret_cast<__m128i*>(odd+i), _mm_srli_si128(u,8));
    }
    for (; i < n; ++i) { even[i] = src[i*2]; odd[i] = src[i*2+1]; }
#else
    for (int i = 0; i < n; ++i) { even[i] = src[i*2]; odd[i] = src[i*2+1]; }
#endif
}
void metadata(std::vector<uint8_t>& out, std::vector<uint16_t>& values) {
    // Zero tail padding is a wire convention, not just filler: deployed
    // readers validate that entries past the unpadded count decode to zero
    // (sibling ContainerReader rejects nonzero padding), so an in-range pad
    // value here would break compatibility for ~100 B/frame. Keep zeros.
    values.resize((values.size()+63)/64*64, 0);
    u32(out, static_cast<uint32_t>(values.size()));
    for (size_t i = 0; i < values.size(); i += 64) {
        uint16_t ref = std::min<uint16_t>(*std::min_element(values.begin()+i, values.begin()+i+64), 4095);
        uint16_t p[64];
        uint16_t max = 0;
        for (int j = 0; j < 64; ++j) { p[j] = values[i+j]-ref; max = std::max(max, p[j]); }
        int bits = bitWidth(max);
        // Header's four-bit field represents the 16-bit literal mode as 15.
        out.push_back(((bits == 16 ? 15 : bits) << 4) | (ref >> 8));
        out.push_back(ref);
        pack(out, p, bits);
    }
}
}
namespace {
// Validated output geometry shared by encode() and encode_parallel() so both
// throw identical errors before any threading or output happens.
struct Geometry { int w, h, ew; };
Geometry checkGeometry(const uint8_t* raw, size_t size, int width, int height,
                       int stride, bool raw10, int cropTop, int cropHeight, bool bin) {
    if (!raw || width <= 0 || height <= 0 || width > 65536 || height > 65536
        || (width & 1) || (raw10 && width % 4) || cropTop < 0 || (cropTop & 1)
        || cropHeight <= 0 || cropTop > height-cropHeight)
        throw std::invalid_argument("Invalid RAW geometry");
    const size_t rowBytes = raw10 ? size_t(width)/4*5 : size_t(width)*2;
    if (stride < 0 || size_t(stride) < rowBytes || size < size_t(height-1)*stride+rowBytes)
        throw std::invalid_argument("Truncated RAW plane or invalid stride");
    const int w = bin ? width/2 : width;
    const int h = bin ? cropHeight/2 : cropHeight;
    if ((w & 1) || h % 4 || (bin && width % 4))
        throw std::invalid_argument("MediaCinemaRAW requires even width and height divisible by four");
    return {w, h, (w+63)/64*64};
}
// Encode tile rows [yBegin, yEnd) of 64x4 tiles, appending payload bytes and
// per-channel bits/refs in deterministic (y, x, channel) order. Bands are
// independent: parallel workers run this on disjoint ranges and the caller
// concatenates in band order for byte-identical output.
void encodeTiles(const uint8_t* raw, int stride, bool raw10, int cropTop, bool bin,
                 int w, int ew, int yBegin, int yEnd,
                 std::vector<uint8_t>& tileOut, std::vector<uint16_t>& tBits,
                 std::vector<uint16_t>& tRefs) {
    auto sample = [&](int x, int y) -> uint16_t {
        const uint8_t* row = raw + size_t(y+cropTop)*stride;
        if (raw10) return (uint16_t(row[x/4*5+x%4]) << 2) | ((row[x/4*5+4] >> (2*(x%4))) & 3);
        return uint16_t(row[x*2]) | (uint16_t(row[x*2+1]) << 8);
    };
    auto pixel = [&](int x, int y) -> uint16_t {
        // Padding is outside the visible width and is discarded by the decoder.
        if (x >= w) return 0;
        if (!bin) return sample(x,y);
        int sx = (x/2)*4+x%2, sy = (y/2)*4+y%2;
        // Average four same-colour samples; retain the original black/white levels.
        return (uint32_t(sample(sx,sy))+sample(sx+2,sy)+sample(sx,sy+2)+sample(sx+2,sy+2)+2)/4;
    };
    for (int y = yBegin; y < yEnd; y += 4) for (int x = 0; x < ew; x += 64) {
        uint16_t channels[4][64];
        if (!raw10 && !bin) {
            // RAW16: deinterleave contiguous Bayer rows once, without
            // per-pixel format/crop branches. Partial edge tiles bound the
            // run to the visible pairs (reads must stay inside the stride);
            // the clamp below then fills padding with the visible minimum.
            const int n = std::min(32, (w-x)/2);
            for (int row = 0; row < 4; ++row) {
                const auto* src = reinterpret_cast<const uint16_t*>(raw+size_t(y+row+cropTop)*stride+x*2);
                deintN(src, channels[(row%2)*2]+(row/2)*32, channels[(row%2)*2+1]+(row/2)*32, n);
            }
        } else if (!bin && x+64 <= w) {
            // Packed RAW10 full tile: unpack 64 pixels per row, then the same
            // deinterleave. Partial RAW10 tiles stay on the pixel() path.
            for (int row = 0; row < 4; ++row) {
                const uint8_t* src = raw+size_t(y+row+cropTop)*stride+size_t(x)/4*5;
                uint16_t tmp[64];
                for (int i = 0; i < 64; i += 4) {
                    const uint8_t* g = src+size_t(i)/4*5;
                    const unsigned l = g[4];
                    tmp[i] = (uint16_t(g[0])<<2)|(l&3);
                    tmp[i+1] = (uint16_t(g[1])<<2)|((l>>2)&3);
                    tmp[i+2] = (uint16_t(g[2])<<2)|((l>>4)&3);
                    tmp[i+3] = (uint16_t(g[3])<<2)|(l>>6);
                }
                deintN(tmp, channels[(row%2)*2]+(row/2)*32, channels[(row%2)*2+1]+(row/2)*32, 32);
            }
        } else {
            for (int c = 0; c < 4; ++c) for (int i = 0; i < 64; ++i)
                channels[c][i] = pixel(x+(i%32)*2+c%2, y+(i/32)*2+c/2);
        }
        const bool partial = (x+64 > w);
        for (int c = 0; c < 4; ++c) {
            uint16_t* p = channels[c];
            if (partial) {
                // Padding (x >= w) is discarded by the decoder. Clamp it to
                // the visible minimum so zero padding cannot inflate hi-lo
                // (and the bit width) of edge tiles. Visible deltas are
                // unchanged relative to the new lo, so visible pixels still
                // decode to lo + delta.
                uint16_t vis = 65535;
                for (int i = 0; i < 64; ++i)
                    if (x+(i%32)*2+c%2 < w) vis = std::min(vis, p[i]);
                if (vis == 65535) vis = 0; // fully padded; unreachable for even w >= 2
                for (int i = 0; i < 64; ++i)
                    if (x+(i%32)*2+c%2 >= w) p[i] = vis;
            }
            uint16_t lo = 65535, hi = 0;
#if defined(__ARM_NEON)
            uint16x8_t vlo = vdupq_n_u16(65535), vhi = vdupq_n_u16(0);
            for (int i = 0; i < 64; i += 8) {
                uint16x8_t v = vld1q_u16(p+i);
                vlo = vminq_u16(vlo,v); vhi = vmaxq_u16(vhi,v);
            }
            uint16_t mins[8], maxs[8];
            vst1q_u16(mins,vlo); vst1q_u16(maxs,vhi);
            for (int i = 0; i < 8; ++i) {
                lo = std::min(lo,mins[i]); hi = std::max(hi,maxs[i]);
            }
#elif defined(MCRAW_HAVE_SSE2)
            // SSE2 min/max are signed only: flip the sign bit so unsigned
            // order matches signed order, then flip back on reduction.
            const __m128i bias = _mm_set1_epi16((short)0x8000);
            __m128i vlo = _mm_set1_epi16((short)0x7FFF), vhi = _mm_set1_epi16((short)0x8000);
            for (int i = 0; i < 64; i += 8) {
                __m128i v = _mm_xor_si128(_mm_loadu_si128(reinterpret_cast<const __m128i*>(p+i)), bias);
                vlo = _mm_min_epi16(vlo,v); vhi = _mm_max_epi16(vhi,v);
            }
            uint16_t mins[8], maxs[8];
            _mm_storeu_si128(reinterpret_cast<__m128i*>(mins),vlo);
            _mm_storeu_si128(reinterpret_cast<__m128i*>(maxs),vhi);
            for (int i = 0; i < 8; ++i) {
                lo = std::min(lo,uint16_t(mins[i]^0x8000)); hi = std::max(hi,uint16_t(maxs[i]^0x8000));
            }
#else
            for (int i = 0; i < 64; ++i) {
                lo = std::min(lo,p[i]); hi = std::max(hi,p[i]);
            }
#endif
            int b = bitWidth(hi-lo);
            tBits.push_back(b); tRefs.push_back(lo);
            // pack() ignores its input when b == 0, so skip the deltas.
            if (b) {
#if defined(__ARM_NEON)
            uint16x8_t reference = vdupq_n_u16(lo);
            for (int i = 0; i < 64; i += 8)
                vst1q_u16(p+i,vsubq_u16(vld1q_u16(p+i),reference));
#elif defined(MCRAW_HAVE_SSE2)
            __m128i reference = _mm_set1_epi16((short)lo);
            for (int i = 0; i < 64; i += 8)
                _mm_storeu_si128(reinterpret_cast<__m128i*>(p+i),
                    _mm_sub_epi16(_mm_loadu_si128(reinterpret_cast<const __m128i*>(p+i)),reference));
#else
            for (int i = 0; i < 64; ++i) p[i] -= lo;
#endif
            }
            pack(tileOut,p,b);
        }
    }
}
}
void encode(const uint8_t* raw, size_t size, int width, int height, int stride,
            bool raw10, int cropTop, int cropHeight, bool bin, std::vector<uint8_t>& out) {
    const Geometry g = checkGeometry(raw, size, width, height, stride, raw10, cropTop, cropHeight, bin);
    const int w = g.w, h = g.h, ew = g.ew;
    // clear() keeps capacity, so repeated encodes into the same buffer reuse it;
    // the reserve below covers the worst case (2 B/px tiles + metadata) once.
    out.clear();
    out.reserve(size_t(ew)*h*2 + size_t(ew)*h/8 + 1024);
    u32(out, ew); u32(out, h); u32(out, 0); u32(out, 0);
    std::vector<uint16_t> bits, refs;
    // metadata() pads these to a multiple of 64; reserve the padded size so
    // the padding resize never reallocates.
    const size_t nChanPadded = (size_t(ew)*h/64+63)/64*64;
    bits.reserve(nChanPadded); refs.reserve(nChanPadded);
    encodeTiles(raw, stride, raw10, cropTop, bin, w, ew, 0, h, out, bits, refs);
    patch(out,8,static_cast<uint32_t>(out.size())); metadata(out,bits);
    patch(out,12,static_cast<uint32_t>(out.size())); metadata(out,refs);
}
void encode(const uint8_t* raw, size_t size, int width, int height, int stride,
            std::vector<uint8_t>& output, bool raw10) {
    encode(raw, size, width, height, stride, raw10, 0, height, false, output);
}
void encode_parallel(const uint8_t* raw, size_t size, int width, int height, int stride,
                     bool raw10, int cropTop, int cropHeight, bool bin,
                     std::vector<uint8_t>& out, unsigned threads) {
    const Geometry g = checkGeometry(raw, size, width, height, stride, raw10, cropTop, cropHeight, bin);
    const int w = g.w, h = g.h, ew = g.ew;
    out.clear();
    const size_t estimate = size_t(ew)*h*2 + size_t(ew)*h/8 + 1024;
    out.reserve(estimate);
    u32(out, ew); u32(out, h); u32(out, 0); u32(out, 0);
    const size_t nChanPadded = (size_t(ew)*h/64+63)/64*64;
    std::vector<uint16_t> bits, refs;
    bits.reserve(nChanPadded); refs.reserve(nChanPadded);
    const int bands = h/4;
    unsigned workers = threads == 0 ? std::max(1u, std::thread::hardware_concurrency()) : threads;
    if (workers > (unsigned)bands) workers = (unsigned)bands;
    if (workers <= 1) {
        encodeTiles(raw, stride, raw10, cropTop, bin, w, ew, 0, h, out, bits, refs);
    } else {
        struct Job { std::vector<uint8_t> bytes; std::vector<uint16_t> bits, refs; int y0, y1; };
        std::vector<Job> jobs(workers);
        int y = 0;
        for (unsigned i = 0; i < workers; ++i) {
            const int cnt = bands/(int)workers + ((int)i < bands%(int)workers ? 1 : 0);
            jobs[i].y0 = y; jobs[i].y1 = y+cnt*4; y += cnt*4;
            jobs[i].bytes.reserve(estimate*(size_t)cnt/(size_t)bands + 64);
            jobs[i].bits.reserve(nChanPadded*(size_t)cnt/(size_t)bands + 64);
            jobs[i].refs.reserve(nChanPadded*(size_t)cnt/(size_t)bands + 64);
        }
        std::vector<std::thread> th; th.reserve(workers);
        std::vector<std::exception_ptr> err(workers);
        for (unsigned i = 0; i < workers; ++i)
            th.emplace_back([&, i] {
                try {
                    encodeTiles(raw, stride, raw10, cropTop, bin, w, ew,
                                jobs[i].y0, jobs[i].y1, jobs[i].bytes, jobs[i].bits, jobs[i].refs);
                } catch (...) { err[i] = std::current_exception(); }
            });
        for (auto& t : th) t.join();
        for (auto& e : err) if (e) std::rethrow_exception(e);
        // Concatenate in band order: byte-identical to the serial encode().
        for (auto& j : jobs) {
            out.insert(out.end(), j.bytes.begin(), j.bytes.end());
            bits.insert(bits.end(), j.bits.begin(), j.bits.end());
            refs.insert(refs.end(), j.refs.begin(), j.refs.end());
        }
    }
    patch(out,8,static_cast<uint32_t>(out.size())); metadata(out,bits);
    patch(out,12,static_cast<uint32_t>(out.size())); metadata(out,refs);
}
}
