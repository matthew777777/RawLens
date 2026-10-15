// SPDX-License-Identifier: GPL-3.0-only
// Pins the encoder behavior (synced at 3c9519c; the detail/ refactor is
// byte-identical to 55cceb2, except the bin kernel, which is the local
// diamond: R/B single-tap, diamond-averaged green): encode_parallel() must
// be byte-identical to encode(), the convenience overload must match the
// full form, the edge-tile padding clamp must produce upstream's golden
// bytes, the binned taps must match the diamond spec pixel-for-pixel, and
// the fd-fork ContainerWriter must emit byte-exact audio/motion payloads
// through its chunked write path. Self-contained: no decoder checkouts.
#include <MediaCinemaRAW/ContainerWriter.h>
#include <MediaCinemaRAW/Encoder.h>

#include "detail/Binning.h"

#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

namespace {

uint64_t fnv1a(const std::vector<uint8_t>& v) {
    uint64_t h = 1469598103934665603ull;
    for (uint8_t b : v) {
        h ^= b;
        h *= 1099511628211ull;
    }
    return h;
}

uint32_t u32le(const std::vector<uint8_t>& v, size_t p) {
    assert(p + 4 <= v.size());
    return uint32_t(v[p]) | (uint32_t(v[p + 1]) << 8) |
           (uint32_t(v[p + 2]) << 16) | (uint32_t(v[p + 3]) << 24);
}

uint16_t xorshiftNext(uint32_t& s) {
    s ^= s << 13;
    s ^= s >> 17;
    s ^= s << 5;
    return uint16_t(s >> 7);
}

void checkParallel(int width, int height, int slack, bool raw10, int cropTop,
                   int cropHeight, bool bin, int cfa) {
    const size_t rowBytes = raw10 ? size_t(width) / 4 * 5 : size_t(width) * 2;
    const int stride = int(rowBytes) + slack;
    std::vector<uint8_t> raw(size_t(height - 1) * stride + rowBytes, 0);
    uint32_t st = 0x243F6A89u;
    for (int y = 0; y < height; ++y)
        for (int x = 0; x < width; ++x) {
            uint16_t v = xorshiftNext(st);
            if (raw10) v %= 1024;
            if (raw10) {
                raw[y * stride + x / 4 * 5 + x % 4] = uint8_t(v >> 2);
                if (x % 4 == 0) raw[y * stride + x / 4 * 5 + 4] = 0;
                raw[y * stride + x / 4 * 5 + 4] =
                    uint8_t(raw[y * stride + x / 4 * 5 + 4] | ((v & 3) << (2 * (x % 4))));
            } else {
                raw[y * stride + x * 2] = uint8_t(v);
                raw[y * stride + x * 2 + 1] = uint8_t(v >> 8);
            }
        }
    std::vector<uint8_t> serial, par;
    mediacinemaraw::encode(raw.data(), raw.size(), width, height, stride, raw10,
                           cropTop, cropHeight, bin, cfa, serial);
    for (unsigned t : {0u, 1u, 2u, 3u, 7u}) {
        mediacinemaraw::encode_parallel(raw.data(), raw.size(), width, height, stride,
                                        raw10, cropTop, cropHeight, bin, cfa, par, t);
        assert(par == serial);
    }
    // Repeated parallel calls are deterministic.
    std::vector<uint8_t> par2;
    mediacinemaraw::encode_parallel(raw.data(), raw.size(), width, height, stride,
                                    raw10, cropTop, cropHeight, bin, cfa, par2, 0);
    assert(par2 == serial);
    std::cout << "parallel parity " << width << "x" << height
              << (raw10 ? " raw10" : " raw16") << (bin ? " bin" : "")
              << (bin ? " cfa=" + std::to_string(cfa) : "")
              << " cropTop=" << cropTop << " cropH=" << cropHeight << " ok ("
              << serial.size() << " bytes)\n";
}

// Diamond binning, pixel-for-pixel: the shipped BinnedSample helper over a
// crafted frame must equal the spec taps (R/B 2x2 box, green diamond with
// same-colour edge mirrors, +2 rounding) for every CFA, RAW16 and packed
// RAW10 alike, with and without a crop offset. Distinct per-pixel values
// catch any misrouted tap; the sweeps cover the right/bottom edge clamps
// and the bottom-right corner.
void checkDiamond(int width, int height, bool raw10, int cropTop, int cropHeight,
                  int cfa) {
    assert(width % 4 == 0 && cropHeight % 8 == 0);
    const size_t rowBytes = raw10 ? size_t(width) / 4 * 5 : size_t(width) * 2;
    const int stride = int(rowBytes) + 8;
    std::vector<uint8_t> raw(size_t(height - 1) * stride + rowBytes, 0);
    std::vector<uint16_t> pixels(size_t(width) * height, 0);
    uint32_t st = 0x85EBCA6Bu;
    for (int y = 0; y < height; ++y)
        for (int x = 0; x < width; ++x) {
            uint16_t v = xorshiftNext(st);
            if (raw10) v %= 1024;
            // Fold in the coordinates so every pixel differs from its
            // neighbours: a misrouted tap cannot hide behind equal values.
            v = uint16_t((v + x * 131u + y * 17u) & (raw10 ? 1023u : 65535u));
            pixels[size_t(y) * width + x] = v;
            if (raw10) {
                raw[size_t(y) * stride + x / 4 * 5 + x % 4] = uint8_t(v >> 2);
                if (x % 4 == 0) raw[size_t(y) * stride + x / 4 * 5 + 4] = 0;
                raw[size_t(y) * stride + x / 4 * 5 + 4] = uint8_t(
                    raw[size_t(y) * stride + x / 4 * 5 + 4] | ((v & 3) << (2 * (x % 4))));
            } else {
                raw[size_t(y) * stride + x * 2] = uint8_t(v);
                raw[size_t(y) * stride + x * 2 + 1] = uint8_t(v >> 8);
            }
        }
    auto sample = [&](int x, int y) -> uint16_t {
        const uint8_t* row = raw.data() + size_t(y + cropTop) * stride;
        if (raw10)
            return uint16_t((uint16_t(row[x / 4 * 5 + x % 4]) << 2) |
                            ((row[x / 4 * 5 + 4] >> (2 * (x % 4))) & 3));
        return uint16_t(uint16_t(row[x * 2]) | (uint16_t(row[x * 2 + 1]) << 8));
    };
    const bool greenEven = (cfa == 1 || cfa == 2);
    const int encW = width / 2, encH = cropHeight / 2;
    for (int oy = 0; oy < encH; ++oy)
        for (int ox = 0; ox < encW; ++ox) {
            const int sx = ox * 2, sy = cropTop + oy * 2, prow = oy * 2;
            const bool isGreen = ((((ox + oy) & 1) == 0) == greenEven);
            uint16_t expected;
            if (!isGreen) {
                // 2x2 box on the square sublattice; rows below are absolute
                // (pre-crop row + top), clamped in pre-crop space.
                const int bx = sx + (ox & 1), byp = prow + (oy & 1);
                const int rx = (ox + 1 < encW) ? bx + 2 : bx;
                const int ryp = (byp + 2 < cropHeight) ? byp + 2 : byp;
                const int by = byp + cropTop, ry = ryp + cropTop;
                expected = uint16_t((uint32_t(pixels[size_t(by) * width + bx]) +
                                     pixels[size_t(by) * width + rx] +
                                     pixels[size_t(ry) * width + bx] +
                                     pixels[size_t(ry) * width + rx] + 2) >>
                                    2);
            } else if (!greenEven) {
                const uint32_t t = pixels[size_t(sy) * width + sx + 1];
                const uint32_t l = pixels[size_t(sy + 1) * width + sx];
                const uint32_t r =
                    (ox + 1 < encW) ? pixels[size_t(sy + 1) * width + sx + 2] : l;
                const uint32_t b =
                    (prow + 2 < cropHeight) ? pixels[size_t(sy + 2) * width + sx + 1] : t;
                expected = uint16_t((t + l + r + b + 2) >> 2);
            } else {
                const uint32_t a = pixels[size_t(sy) * width + sx];
                const uint32_t q = pixels[size_t(sy + 1) * width + sx + 1];
                const uint32_t r =
                    (ox + 1 < encW) ? pixels[size_t(sy) * width + sx + 2] : a;
                const uint32_t b =
                    (prow + 2 < cropHeight) ? pixels[size_t(sy + 2) * width + sx] : a;
                expected = uint16_t((a + q + r + b + 2) >> 2);
            }
            const uint16_t got = mediacinemaraw::detail::BinnedSample(
                sample, ox, oy, encW, cropHeight, cfa);
            assert(got == expected);
        }
    std::cout << "diamond bin " << width << "x" << height
              << (raw10 ? " raw10" : " raw16") << " cfa=" << cfa
              << " cropTop=" << cropTop << " cropH=" << cropHeight << " ok ("
              << encW << "x" << encH << " samples)\n";
}

int64_t loadI64(const std::string& b, size_t p) {
    assert(p + 8 <= b.size());
    uint64_t v = 0;
    for (size_t i = 0; i < 8; ++i) v |= uint64_t(uint8_t(b[p + i])) << (8 * i);
    return int64_t(v);
}

uint32_t loadU32(const std::string& b, size_t p) {
    assert(p + 4 <= b.size());
    return uint32_t(uint8_t(b[p])) | (uint32_t(uint8_t(b[p + 1])) << 8) |
           (uint32_t(uint8_t(b[p + 2])) << 16) | (uint32_t(uint8_t(b[p + 3])) << 24);
}

struct Item {
    uint32_t type;
    size_t at;
    size_t payload;
};

std::vector<Item> scanItems(const std::string& bytes) {
    assert(bytes.size() > 8);
    assert(bytes[0] == 'M' && bytes[1] == 'O' && bytes[2] == 'T' &&
           bytes[3] == 'I' && bytes[4] == 'O' && bytes[5] == 'N' &&
           bytes[6] == ' ' && bytes[7] == 3);
    std::vector<Item> items;
    size_t p = 8;
    while (p < bytes.size()) {
        assert(p + 8 <= bytes.size());
        const uint32_t type = loadU32(bytes, p);
        const uint32_t len = loadU32(bytes, p + 4);
        assert(p + 8 + len <= bytes.size());
        items.push_back({type, p, p + 8});
        p += 8 + len;
    }
    return items;
}

}  // namespace

int main() {
    // encode_parallel() byte-identity across RAW16/RAW10, bin, stride slack,
    // crops, and partial edge tiles (band splits must not disturb tile order
    // or the padding clamp).
    checkParallel(64, 8, 16, false, 0, 8, false, 0);
    checkParallel(68, 12, 0, false, 0, 12, false, 0);
    checkParallel(68, 16, 8, true, 2, 12, false, 0);
    for (int cfa = 0; cfa < 4; ++cfa)
        checkParallel(128, 8, 4, false, 0, 8, true, cfa);
    checkParallel(64, 16, 0, false, 4, 8, true, 0);
    checkParallel(128, 16, 8, true, 0, 16, true, 1);
    checkParallel(260, 24, 24, true, 0, 24, false, 0);
    // Diamond taps, pixel-for-pixel, over every CFA plus RAW10 and cropped
    // variants (the sweeps hit the right/bottom edge mirrors and corners).
    for (int cfa = 0; cfa < 4; ++cfa) {
        checkDiamond(8, 8, false, 0, 8, cfa);
        checkDiamond(12, 16, false, 4, 8, cfa);
    }
    checkDiamond(8, 8, true, 0, 8, 0);
    checkDiamond(12, 16, true, 4, 8, 1);
    // Error parity: same geometry rejection before any thread spawns.
    {
        std::vector<uint8_t> tiny(64, 0), tmp;
        for (int bad = 0; bad < 3; ++bad) {
            const int stride = bad == 1 ? 10 : 128;
            const int top = bad == 0 ? 1 : 0;
            const int cfa = bad == 2 ? 9 : 0;
            bool s = false, p = false;
            try {
                mediacinemaraw::encode(tiny.data(), tiny.size(), 64, 8,
                                       stride, false, top, 8, false, cfa, tmp);
            } catch (const std::invalid_argument&) {
                s = true;
            }
            try {
                mediacinemaraw::encode_parallel(tiny.data(), tiny.size(), 64, 8,
                                                stride, false, top, 8, false,
                                                cfa, tmp, 4);
            } catch (const std::invalid_argument&) {
                p = true;
            }
            assert(s && p);
        }
        std::cout << "parallel error parity ok\n";
    }

    // Convenience overload: full-frame lossless, identical to the 10-argument
    // form with cropTop = 0, cropHeight = height, bin = false.
    {
        constexpr int wO = 68, hO = 12, sO = wO * 2 + 8;
        std::vector<uint8_t> rawO((hO - 1) * sO + wO * 2, 0);
        for (int y = 0; y < hO; ++y)
            for (int x = 0; x < wO; ++x) {
                const uint16_t v = static_cast<uint16_t>((x * 257 + y * 13) & 8191);
                rawO[y * sO + x * 2] = static_cast<uint8_t>(v);
                rawO[y * sO + x * 2 + 1] = static_cast<uint8_t>(v >> 8);
            }
        std::vector<uint8_t> full, easy;
        mediacinemaraw::encode(rawO.data(), rawO.size(), wO, hO, sO, false, 0,
                               hO, false, 0, full);
        mediacinemaraw::encode(rawO.data(), rawO.size(), wO, hO, sO, easy);
        assert(easy == full);
        constexpr int wR = 64, hR = 8, sR = wR / 4 * 5;
        std::vector<uint8_t> rawR(hR * sR, 0);
        for (int y = 0; y < hR; ++y)
            for (int x = 0; x < wR; ++x) {
                const uint16_t v = static_cast<uint16_t>((x * 31 + y * 7) & 1023);
                rawR[y * sR + x / 4 * 5 + x % 4] = static_cast<uint8_t>(v >> 2);
                if (x % 4 == 0) rawR[y * sR + x / 4 * 5 + 4] = 0;
                rawR[y * sR + x / 4 * 5 + 4] = static_cast<uint8_t>(
                    rawR[y * sR + x / 4 * 5 + 4] | ((v & 3) << (2 * (x % 4))));
            }
        mediacinemaraw::encode(rawR.data(), rawR.size(), wR, hR, sR, true, 0,
                               hR, false, 0, full);
        mediacinemaraw::encode(rawR.data(), rawR.size(), wR, hR, sR, easy, true);
        assert(easy == full);
        std::cout << "shorthand overload parity ok\n";
    }

    // Edge-clamp goldens (upstream values): 66x4 constant 3000 (ew = 128).
    // Zero padding used to drag edge channels to 16-bit (708 bytes); the
    // clamp keeps padded samples at the visible minimum so every channel is
    // bits=0 and the tile payload is empty.
    {
        constexpr int wB = 66, hB = 4, sB = wB * 2;
        std::vector<uint8_t> rawB(hB * sB, 0);
        for (int y = 0; y < hB; ++y)
            for (int x = 0; x < wB; ++x) {
                rawB[y * sB + x * 2] = uint8_t(3000);
                rawB[y * sB + x * 2 + 1] = uint8_t(3000 >> 8);
            }
        std::vector<uint8_t> outB;
        mediacinemaraw::encode(rawB.data(), rawB.size(), wB, hB, sB, false, 0,
                               hB, false, 0, outB);
        assert(u32le(outB, 0) == 128);  // ew preserved
        assert(u32le(outB, 4) == 4);    // h preserved
        assert(u32le(outB, 8) == 16);   // off1: zero tile payload
        assert(outB.size() == 156);
        assert(fnv1a(outB) == 0x907450a98cf63d41ULL);
        std::cout << "edge-clamp golden 66x4 ok\n";

        constexpr int wC = 64, hC = 4, sC = wC * 2;
        std::vector<uint8_t> rawC(hC * sC, 0);
        for (int y = 0; y < hC; ++y)
            for (int x = 0; x < wC; ++x) {
                rawC[y * sC + x * 2] = uint8_t(3000);
                rawC[y * sC + x * 2 + 1] = uint8_t(3000 >> 8);
            }
        std::vector<uint8_t> outC;
        mediacinemaraw::encode(rawC.data(), rawC.size(), wC, hC, sC, false, 0,
                               hC, false, 0, outC);
        assert(outC.size() == 156);
        assert(fnv1a(outC) == 0x6f46729dbbcc0211ULL);
        std::cout << "unaligned-width golden 64x4 ok\n";
    }

    // Diamond-bin golden: 8x8 coordinate ramp binned 4x4 (cfa 0). Pins the
    // kernel bytes end-to-end (taps + packing); checkDiamond above pins the
    // tap values themselves.
    {
        constexpr int wD = 8, hD = 8, sD = wD * 2;
        std::vector<uint8_t> rawD(hD * sD, 0);
        for (int y = 0; y < hD; ++y)
            for (int x = 0; x < wD; ++x) {
                const uint16_t v = static_cast<uint16_t>(y * wD + x);
                rawD[y * sD + x * 2] = static_cast<uint8_t>(v);
                rawD[y * sD + x * 2 + 1] = static_cast<uint8_t>(v >> 8);
            }
        std::vector<uint8_t> outD;
        mediacinemaraw::encode(rawD.data(), rawD.size(), wD, hD, sD, false, 0,
                               hD, true, 0, outD);
        assert(u32le(outD, 0) == 64);  // padded width of the 4-wide frame
        assert(u32le(outD, 4) == 4);
        assert(outD.size() == 284);
        assert(fnv1a(outD) == 0xfecfd97ff350db85ULL);
        std::cout << "diamond-bin golden 8x8 ok\n";
    }

    // Chunked ContainerWriter path: single calls larger than the internal
    // chunk sizes (8192 audio shorts, 4096 motion samples) must still land
    // byte-exact, with indexes and tail order intact.
    {
        const std::string path = "/private/tmp/rawlens-parity-chunk.mcraw";
        std::vector<uint8_t> raw(64 * 8 * 2, 7), payload;
        mediacinemaraw::encode(raw.data(), raw.size(), 64, 8, 128, false, 0, 8,
                               false, 0, payload);
        std::vector<mediacinemaraw::GyroSample> gyro1(6000), gyro2(10);
        for (size_t i = 0; i < gyro1.size(); ++i)
            gyro1[i] = {int64_t(100 + i), 0.1f * float(i), -0.2f * float(i),
                        0.3f * float(i)};
        for (size_t i = 0; i < gyro2.size(); ++i)
            gyro2[i] = {int64_t(100000 + i), 1.5f, -2.5f, 3.5f};
        std::vector<mediacinemaraw::AccelerometerSample> accel1(5000), accel2(10);
        for (size_t i = 0; i < accel1.size(); ++i)
            accel1[i] = {int64_t(200 + i), 9.1f + 0.01f * float(i), 0.4f, -0.6f};
        for (size_t i = 0; i < accel2.size(); ++i)
            accel2[i] = {int64_t(200000 + i), 0.0f, 9.81f, 0.0f};
        std::vector<int16_t> pcm(20000);
        for (size_t i = 0; i < pcm.size(); ++i)
            pcm[i] = int16_t((i * 7919 + 13) & 0xFFFF);
        pcm[0] = -32768;
        pcm[1] = 32767;
        {
            mediacinemaraw::ContainerWriter writer(path, "{\"meta\":1}");
            writer.writeGyro(gyro1.data(), gyro1.size());
            writer.writeAccelerometer(accel1.data(), accel1.size());
            writer.writeFrame(payload, 1000, "{}");
            writer.writeAudio(pcm.data(), pcm.size(), 2000);
            writer.writeFrame(payload, 3000, "{}");
            writer.writeGyro(gyro2.data(), gyro2.size());
            writer.writeAccelerometer(accel2.data(), accel2.size());
            writer.close();
        }
        std::ifstream in(path, std::ios::binary);
        std::ostringstream ss;
        ss << in.rdbuf();
        const std::string bytes = ss.str();
        const std::vector<Item> items = scanItems(bytes);
        std::vector<uint32_t> types;
        for (const auto& it : items) types.push_back(it.type);
        const std::vector<uint32_t> want = {
            3, 9, 13, 2, 3, 5, 6, 2, 3, 4, 9, 13, 8, 12, 1, 0,
        };
        assert(types == want);

        // Audio payload byte-exact (one 40000-byte item from the 20000-call).
        const Item& audio = items[5];
        assert(loadU32(bytes, audio.at + 4) == 40000);
        for (size_t i = 0; i < pcm.size(); ++i) {
            const uint16_t v = uint16_t(pcm[i]);
            assert(uint8_t(bytes[audio.payload + i * 2]) == uint8_t(v));
            assert(uint8_t(bytes[audio.payload + i * 2 + 1]) == uint8_t(v >> 8));
        }
        assert(loadI64(bytes, items[6].payload) == 2000);  // audio ts marker

        // Motion payloads byte-exact, both the multi-chunk flush and the
        // trailing chunk.
        auto checkMotion = [&](size_t idx, auto& expect) {
            const Item& it = items[idx];
            assert(loadU32(bytes, it.payload) == 1);  // version
            assert(loadU32(bytes, it.payload + 4) == expect.size());
            for (size_t i = 0; i < expect.size(); ++i) {
                const size_t at = it.payload + 8 + i * 24;
                assert(loadI64(bytes, at) == expect[i].timestampNs);
                uint32_t bits;
                std::memcpy(&bits, &expect[i].x, 4);
                assert(loadU32(bytes, at + 8) == bits);
                std::memcpy(&bits, &expect[i].y, 4);
                assert(loadU32(bytes, at + 12) == bits);
                std::memcpy(&bits, &expect[i].z, 4);
                assert(loadU32(bytes, at + 16) == bits);
                assert(loadU32(bytes, at + 20) == 0);
            }
        };
        checkMotion(1, gyro1);
        checkMotion(2, accel1);
        checkMotion(10, gyro2);
        checkMotion(11, accel2);

        // Audio index precedes trailing motion (tail-order contract), holds
        // the full-ns origin (fork behavior, verified against a real take),
        // and every index entry points at its item header.
        const Item& audioIndex = items[9];
        assert(loadI64(bytes, audioIndex.payload) == 1);      // chunk count
        assert(loadI64(bytes, audioIndex.payload + 8) == 2000);  // ns origin
        assert(loadI64(bytes, audioIndex.payload + 16) == int64_t(audio.at));
        assert(loadI64(bytes, audioIndex.payload + 24) == 2000);
        assert(audioIndex.at < items[10].at && audioIndex.at < items[11].at);
        const Item& gyroIndex = items[12];
        assert(loadU32(bytes, gyroIndex.payload) == 1);
        assert(loadU32(bytes, gyroIndex.payload + 4) == 2);
        assert(loadI64(bytes, gyroIndex.payload + 8) == int64_t(items[1].at));
        assert(loadI64(bytes, gyroIndex.payload + 16) == gyro1[0].timestampNs);
        assert(loadI64(bytes, gyroIndex.payload + 24) == int64_t(items[10].at));
        assert(loadI64(bytes, gyroIndex.payload + 32) == gyro2[0].timestampNs);
        const Item& accelIndex = items[13];
        assert(loadU32(bytes, accelIndex.payload + 4) == 2);
        assert(loadI64(bytes, accelIndex.payload + 8) == int64_t(items[2].at));
        assert(loadI64(bytes, accelIndex.payload + 24) == int64_t(items[11].at));
        const Item& frameIndex = items[14];
        assert(loadI64(bytes, frameIndex.payload) == int64_t(items[3].at));
        assert(loadI64(bytes, frameIndex.payload + 8) == 1000);
        assert(loadI64(bytes, frameIndex.payload + 16) == int64_t(items[7].at));
        assert(loadI64(bytes, frameIndex.payload + 24) == 3000);
        const Item& footer = items[15];
        assert(loadU32(bytes, footer.payload) == 0x8A905612);
        assert(loadU32(bytes, footer.payload + 4) == 2);
        assert(loadI64(bytes, footer.payload + 8) == int64_t(frameIndex.payload));
        std::remove(path.c_str());
        std::cout << "chunked container round-trip ok (" << bytes.size()
                  << " bytes, 2 motion chunks per sensor for 2 frames)\n";
    }

    std::cout << "parallel_parity: all checks passed\n";
}
