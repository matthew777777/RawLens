// SPDX-License-Identifier: GPL-3.0-only
//
// Private container format constants, little-endian assembly buffers, and
// checked item-size arithmetic for the version 3 `.mcraw` writer.
#pragma once

#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <vector>

namespace mediacinemaraw {
namespace detail {

// Item types. 7 is unused; 10/11 (OIS) are never emitted, only skipped by
// readers; see docs/CONTAINER.md for the per-item payload layouts.
enum class ItemType : uint32_t {
    Footer = 0,
    FrameIndex = 1,
    FrameData = 2,
    Metadata = 3,
    AudioIndex = 4,
    AudioData = 5,
    AudioTimestamp = 6,
    GyroIndex = 8,
    GyroData = 9,
    AccelerometerIndex = 12,
    AccelerometerData = 13,
};

constexpr char kFileMagic[8] = {'M', 'O', 'T', 'I', 'O', 'N', ' ', 3};
constexpr uint32_t kFooterMagic = 0x8A905612;
constexpr uint32_t kMotionVersion = 1;

// One motion sample on the wire: int64 timestamp + 3x float32 axes +
// uint32 reserved, 24 bytes total.
constexpr size_t kMotionSampleWireSize = 24;
constexpr size_t kMotionHeaderSize = 8; // version + sample count.

// Memory backstop for coalesced motion: ~33 min of 500 Hz samples between
// two frames. Frame-cadence callers hold ~dozens, so this only trips for a
// caller streaming motion with no frames — one extra chunk beats unbounded
// growth. The payload helpers still length-check every emit.
constexpr size_t kMaxPendingMotionSamples = 1024 * 1024;

// Buffered little-endian assembly: sample loops append to a vector and flush
// with one write() instead of a put() per byte. Same bytes, same order.
template <class T> void AppendLE(std::vector<uint8_t>& buffer, T value) {
    for (size_t i = 0; i < sizeof(T); ++i)
        buffer.push_back(static_cast<uint8_t>(uint64_t(value) >> (8 * i)));
}

inline void AppendFloatLE(std::vector<uint8_t>& buffer, float value) {
    uint32_t bits;
    static_assert(sizeof(bits) == sizeof(value), "float must be 32-bit");
    std::memcpy(&bits, &value, 4);
    AppendLE(buffer, bits);
}

inline uint32_t CheckedItemSize(size_t size) {
    if (size > UINT32_MAX)
        throw std::length_error("item too large");
    return static_cast<uint32_t>(size);
}

// Checked payload sizes: validated before any narrowing or multiplication.
inline uint32_t AudioDataPayloadSize(size_t sampleCount) {
    if (sampleCount > UINT32_MAX / 2)
        throw std::length_error("audio item too large");
    return static_cast<uint32_t>(sampleCount * 2);
}

inline uint32_t MotionDataPayloadSize(size_t sampleCount, const char* what) {
    if (sampleCount > (UINT32_MAX - kMotionHeaderSize) / kMotionSampleWireSize)
        throw std::length_error(what);
    return static_cast<uint32_t>(kMotionHeaderSize + sampleCount * kMotionSampleWireSize);
}

inline uint32_t GyroDataPayloadSize(size_t sampleCount) {
    return MotionDataPayloadSize(sampleCount, "gyro item too large");
}

inline uint32_t AccelerometerDataPayloadSize(size_t sampleCount) {
    return MotionDataPayloadSize(sampleCount, "accelerometer item too large");
}

inline uint32_t AudioIndexPayloadSize(size_t chunkCount) {
    if (chunkCount > (UINT32_MAX - 16) / 16)
        throw std::length_error("audio index too large");
    return static_cast<uint32_t>(16 + chunkCount * 16);
}

inline uint32_t MotionIndexPayloadSize(size_t chunkCount) {
    if (chunkCount > (UINT32_MAX - 8) / 16)
        throw std::length_error("motion index too large");
    return static_cast<uint32_t>(8 + chunkCount * 16);
}

inline uint32_t FrameIndexPayloadSize(size_t frameCount) {
    if (frameCount > UINT32_MAX / 16)
        throw std::length_error("frame index too large");
    return static_cast<uint32_t>(frameCount * 16);
}

} // namespace detail
} // namespace mediacinemaraw
