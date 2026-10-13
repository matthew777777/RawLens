// SPDX-License-Identifier: GPL-3.0-only
#include <MediaCinemaRAW/Encoder.h>

#include <algorithm>
#include <cstdint>
#include <thread>
#include <vector>

#include "detail/BitPacking.h"
#include "detail/Tiles.h"

namespace mediacinemaraw {
namespace {

// Payload header: padded width, height, then the bits-stream and
// refs-stream offsets, patched in once the tile payload is complete.
constexpr size_t kBitsOffsetField = 8;
constexpr size_t kRefsOffsetField = 12;

// Worst-case reservation: 2 bytes/px of tile literals plus metadata.
size_t EstimatePayloadSize(int paddedWidth, int height) {
    return size_t(paddedWidth) * height * 2 + size_t(paddedWidth) * height / 8 + 1024;
}

size_t PaddedChannelCount(int paddedWidth, int height) {
    return (size_t(paddedWidth) * height / 64 + 63) / 64 * 64;
}

void AppendFooter(std::vector<uint8_t>& out, std::vector<uint16_t>& bits,
                  std::vector<uint16_t>& refs) {
    detail::PatchU32LE(out, kBitsOffsetField, static_cast<uint32_t>(out.size()));
    detail::AppendMetadataStream(out, bits);
    detail::PatchU32LE(out, kRefsOffsetField, static_cast<uint32_t>(out.size()));
    detail::AppendMetadataStream(out, refs);
}

} // namespace

void encode(const uint8_t* rawData, size_t rawSize, int width, int height, int strideBytes,
            bool isPackedRaw10, int cropTop, int cropHeight, bool downscale4x,
            std::vector<uint8_t>& output) {
    const detail::ValidatedGeometry geometry =
        detail::ValidateGeometry(rawData, rawSize, width, height, strideBytes, isPackedRaw10,
                                 cropTop, cropHeight, downscale4x);
    // clear() keeps capacity, so repeated encodes into the same buffer reuse
    // it; the reserve below covers the worst case once.
    output.clear();
    output.reserve(EstimatePayloadSize(geometry.paddedWidth, geometry.height));
    detail::AppendU32LE(output, static_cast<uint32_t>(geometry.paddedWidth));
    detail::AppendU32LE(output, static_cast<uint32_t>(geometry.height));
    detail::AppendU32LE(output, 0);
    detail::AppendU32LE(output, 0);

    std::vector<uint16_t> bits, refs;
    // AppendMetadataStream() pads these to a multiple of 64; reserve the
    // padded size so the padding resize never reallocates.
    const size_t channelCapacity = PaddedChannelCount(geometry.paddedWidth, geometry.height);
    bits.reserve(channelCapacity);
    refs.reserve(channelCapacity);

    detail::EncodeTileRows(rawData, strideBytes, isPackedRaw10, cropTop, downscale4x,
                           geometry.width, geometry.paddedWidth, 0, geometry.height, output, bits,
                           refs);
    AppendFooter(output, bits, refs);
}

void encode(const uint8_t* rawData, size_t rawSize, int width, int height, int strideBytes,
            std::vector<uint8_t>& output, bool isPackedRaw10) {
    encode(rawData, rawSize, width, height, strideBytes, isPackedRaw10, 0, height, false, output);
}

void encode_parallel(const uint8_t* rawData, size_t rawSize, int width, int height, int strideBytes,
                     bool isPackedRaw10, int cropTop, int cropHeight, bool downscale4x,
                     std::vector<uint8_t>& output, unsigned threadCount) {
    const detail::ValidatedGeometry geometry =
        detail::ValidateGeometry(rawData, rawSize, width, height, strideBytes, isPackedRaw10,
                                 cropTop, cropHeight, downscale4x);
    output.clear();
    const size_t estimate = EstimatePayloadSize(geometry.paddedWidth, geometry.height);
    output.reserve(estimate);
    detail::AppendU32LE(output, static_cast<uint32_t>(geometry.paddedWidth));
    detail::AppendU32LE(output, static_cast<uint32_t>(geometry.height));
    detail::AppendU32LE(output, 0);
    detail::AppendU32LE(output, 0);

    const size_t channelCapacity = PaddedChannelCount(geometry.paddedWidth, geometry.height);
    std::vector<uint16_t> bits, refs;
    bits.reserve(channelCapacity);
    refs.reserve(channelCapacity);

    // Bands are 4-row tile stripes; every band holds whole tiles.
    const int bandCount = geometry.height / detail::kTileHeight;
    unsigned workers =
        threadCount == 0 ? std::max(1u, std::thread::hardware_concurrency()) : threadCount;
    if (workers > static_cast<unsigned>(bandCount))
        workers = static_cast<unsigned>(bandCount);

    if (workers <= 1) {
        detail::EncodeTileRows(rawData, strideBytes, isPackedRaw10, cropTop, downscale4x,
                               geometry.width, geometry.paddedWidth, 0, geometry.height, output,
                               bits, refs);
    } else {
        struct BandJob {
            std::vector<uint8_t> bytes;
            std::vector<uint16_t> bits;
            std::vector<uint16_t> refs;
            int yBegin, yEnd;
        };
        std::vector<BandJob> jobs(workers);
        int y = 0;
        for (unsigned i = 0; i < workers; ++i) {
            const int workerCount = static_cast<int>(workers);
            const int count =
                bandCount / workerCount + (static_cast<int>(i) < bandCount % workerCount ? 1 : 0);
            jobs[i].yBegin = y;
            jobs[i].yEnd = y + count * detail::kTileHeight;
            y += count * detail::kTileHeight;
            jobs[i].bytes.reserve(estimate * size_t(count) / size_t(bandCount) + 64);
            jobs[i].bits.reserve(channelCapacity * size_t(count) / size_t(bandCount) + 64);
            jobs[i].refs.reserve(channelCapacity * size_t(count) / size_t(bandCount) + 64);
        }
        std::vector<std::thread> threads;
        threads.reserve(workers);
        std::vector<std::exception_ptr> errors(workers);
        for (unsigned i = 0; i < workers; ++i)
            threads.emplace_back([&, i] {
                try {
                    detail::EncodeTileRows(rawData, strideBytes, isPackedRaw10, cropTop,
                                           downscale4x, geometry.width, geometry.paddedWidth,
                                           jobs[i].yBegin, jobs[i].yEnd, jobs[i].bytes,
                                           jobs[i].bits, jobs[i].refs);
                } catch (...) {
                    errors[i] = std::current_exception();
                }
            });
        for (auto& thread : threads)
            thread.join();
        for (auto& error : errors)
            if (error)
                std::rethrow_exception(error);
        // Concatenate in band order: byte-identical to the serial encode().
        for (auto& job : jobs) {
            output.insert(output.end(), job.bytes.begin(), job.bytes.end());
            bits.insert(bits.end(), job.bits.begin(), job.bits.end());
            refs.insert(refs.end(), job.refs.begin(), job.refs.end());
        }
    }
    AppendFooter(output, bits, refs);
}

} // namespace mediacinemaraw
