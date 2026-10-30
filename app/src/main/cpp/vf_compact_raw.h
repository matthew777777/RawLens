// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>

namespace vfcompact {

// Preserve the four RAW16 codes used by each output texel, before channel
// permutation/normalization. The existing shader sees a tight Bayer plane:
// left=top=0, step=2, pitch=2*width. No color arithmetic moves to the CPU.
struct Plan {
    size_t sourceOffset;
    size_t sourceRowBytes;
    size_t sourceStepBytes;
    size_t sourceRowStepBytes;
    size_t packedRowBytes;
    size_t bytes;
    int width;
    int height;
    bool contiguous;
};

inline bool plan(int left, int top, int width, int height, int step, int pitch,
                 uint64_t sourceBytes, Plan* out) {
    if (!out || left < 0 || top < 0 || width <= 0 || height <= 0 ||
        width > 4096 || height > 4096 || step < 2 || step > 16384 || pitch <= 0 || pitch > 16384)
        return false;
    const uint64_t lastX = uint64_t(left) + uint64_t(width - 1) * step + 1;
    const uint64_t lastY = uint64_t(top) + uint64_t(height - 1) * step + 1;
    if (lastX >= uint64_t(pitch)) return false;
    const uint64_t end = (lastY * uint64_t(pitch) + lastX + 1) * 2;
    const uint64_t packedBytes = uint64_t(width) * height * 8;
    if (end > sourceBytes || end > std::numeric_limits<size_t>::max() ||
        packedBytes > std::numeric_limits<size_t>::max()) return false;
    *out = Plan{size_t((uint64_t(top) * pitch + left) * 2), size_t(pitch) * 2,
                size_t(step) * 2, size_t(uint64_t(step) * pitch * 2),
                size_t(width) * 4, size_t(packedBytes), width, height, step == 2};
    return true;
}

// Destination writes are sequential: important for write-combined Vulkan
// mappings. Source rows are visited once; padding and discarded quads never
// enter staging. memcpy handles unaligned camera addresses without UB.
inline void copy(const void* source, void* destination, const Plan& p) {
    const auto* src = static_cast<const uint8_t*>(source) + p.sourceOffset;
    auto* dst = static_cast<uint8_t*>(destination);
    for (int y = 0; y < p.height; ++y) {
        for (size_t row = 0; row < 2; ++row) {
            const auto* in = src + row * p.sourceRowBytes;
            if (p.contiguous) {
                std::memcpy(dst, in, p.packedRowBytes);
            } else {
                for (int x = 0; x < p.width; ++x) {
                    std::memcpy(dst + size_t(x) * 4, in + size_t(x) * p.sourceStepBytes, 4);
                }
            }
            dst += p.packedRowBytes;
        }
        // Do not form a pointer beyond the allocation after the final row.
        if (y + 1 < p.height) src += p.sourceRowStepBytes;
    }
}

} // namespace vfcompact
