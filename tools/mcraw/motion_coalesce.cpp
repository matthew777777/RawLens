// Regression: motioncam-decoder rejects files with more motion chunks than
// frames + 1 ("Invalid gyro/accelerometer index" aborts the whole open,
// hiding audio too), while the recorder drains sensors ~100x/s. The writer
// must coalesce those tiny drains to <=1 chunk per sensor per frame.
// Writes 5 frames with 50 tiny motion drains each (as the recorder does),
// then opens the file with BOTH readers and checks chunk counts on disk.
#include <MediaCinemaRAW/ContainerReader.h>
#include <MediaCinemaRAW/ContainerWriter.h>
#include <MediaCinemaRAW/Encoder.h>
#include <motioncam/Decoder.hpp>
#include <cassert>
#include <cstdio>
#include <cstring>
#include <iostream>

namespace {
struct ItemHead { uint32_t kind, size; };
uint32_t getU32(const uint8_t* p) {
    return uint32_t(p[0]) | (uint32_t(p[1]) << 8) |
           (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24);
}
int64_t getI64(const uint8_t* p) {
    uint64_t v = 0;
    for (int i = 0; i < 8; ++i) v |= uint64_t(p[i]) << (8 * i);
    int64_t o = 0;
    std::memcpy(&o, &v, 8);
    return o;
}
// Count data chunks of one item kind by walking the pre-index region.
size_t countItems(const std::string& path, uint32_t want) {
    FILE* f = std::fopen(path.c_str(), "rb");
    assert(f);
    std::fseek(f, 0, SEEK_END);
    const int64_t fileSize = std::ftell(f);
    std::fseek(f, int64_t(fileSize - 24), SEEK_SET);
    uint8_t foot[24];
    assert(std::fread(foot, 1, 24, f) == 24);
    assert(getU32(foot) == 0 && getU32(foot + 4) == 16);
    assert(getU32(foot + 8) == 0x8A905612u);
    const int64_t listHead = getI64(foot + 16) - 8;
    uint8_t hb[8];
    std::fseek(f, 8, SEEK_SET); // past magic
    assert(std::fread(hb, 1, 8, f) == 8); // container meta head
    std::fseek(f, getU32(hb + 4), SEEK_CUR); // container meta payload
    size_t n = 0;
    while (std::ftell(f) + 8 <= listHead) {
        assert(std::fread(hb, 1, 8, f) == 8);
        const uint32_t kind = getU32(hb), size = getU32(hb + 4);
        if (kind == want) ++n;
        std::fseek(f, size, SEEK_CUR);
    }
    std::fclose(f);
    return n;
}
}  // namespace

int main() {
    const std::string path = "/private/tmp/rawlens-motion-coalesce.mcraw";
    const int kFrames = 5, kDrainsPerFrame = 50, kSamplesPerDrain = 7;
    const int64_t kFrameTs0 = 2000000000;
    size_t expectGyro = 0, expectAccel = 0;
    {
        mediacinemaraw::ContainerWriter writer(
            path, R"({"extraData":{"audioSampleRate":48000,"audioChannels":1}})");
        std::vector<uint8_t> raw(64 * 8 * 2, 17), payload;
        mediacinemaraw::encode(raw.data(), raw.size(), 64, 8, 128, false, 0, 8,
                              false, payload);
        int64_t sensorTs = kFrameTs0;
        for (int f = 0; f < kFrames; ++f) {
            // Mimic the recorder: many tiny sensor drains between frames.
            for (int d = 0; d < kDrainsPerFrame; ++d) {
                std::vector<mediacinemaraw::GyroSample> g;
                std::vector<mediacinemaraw::AccelerometerSample> a;
                for (int i = 0; i < kSamplesPerDrain; ++i) {
                    g.push_back({sensorTs, 0.1f, -0.2f, 0.3f});
                    a.push_back({sensorTs, 1.0f, 2.0f, 9.8f});
                    sensorTs += 2000000; // 500 Hz
                }
                writer.writeGyro(g.data(), g.size());
                writer.writeAccelerometer(a.data(), a.size());
                expectGyro += g.size();
                expectAccel += a.size();
            }
            writer.writeFrame(payload, kFrameTs0 + int64_t(f) * 33333333,
                              R"({"width":64,"height":8,"compressionType":7})");
        }
        // Trailing motion after the last frame: the allowed "+1" chunk.
        mediacinemaraw::GyroSample g[] = {{sensorTs, 0, 0, 0}};
        mediacinemaraw::AccelerometerSample a[] = {{sensorTs, 0, 0, 0}};
        writer.writeGyro(g, 1);
        writer.writeAccelerometer(a, 1);
        ++expectGyro;
        ++expectAccel;
        writer.close();
    }
    // Structural: 251 tiny drains per sensor must land as <= frames + 1.
    const size_t gyroChunks = countItems(path, 9);
    const size_t accelChunks = countItems(path, 13);
    std::cout << "gyro chunks=" << gyroChunks << " accel chunks=" << accelChunks
              << " frames=" << kFrames << '\n';
    assert(gyroChunks <= size_t(kFrames + 1) && gyroChunks >= 1);
    assert(accelChunks <= size_t(kFrames + 1) && accelChunks >= 1);
    // The strict reader threw "Invalid gyro index" before the fix.
    motioncam::Decoder motion(path);
    assert(motion.getFrames().size() == size_t(kFrames));
    assert(motion.hasGyroData() && motion.hasAccelerometerData());
    std::vector<motioncam::MotionSample> mg, ma;
    motion.loadGyroData(mg);
    motion.loadAccelerometerData(ma);
    assert(mg.size() == expectGyro);
    assert(ma.size() == expectAccel);
    // Both readers agree on every sample; no drain was lost or reordered.
    mediacinemaraw::ContainerReader media(path);
    std::vector<mediacinemaraw::MotionSample> eg, ea;
    media.loadGyroData(eg);
    media.loadAccelerometerData(ea);
    assert(eg.size() == mg.size() && ea.size() == ma.size());
    for (size_t i = 0; i < mg.size(); ++i) {
        assert(mg[i].timestampNs == eg[i].timestampNs);
        assert(std::memcmp(&mg[i].x, &eg[i].x, 12) == 0);
        if (i > 0) assert(mg[i].timestampNs >= mg[i - 1].timestampNs);
    }
    for (size_t i = 0; i < ma.size(); ++i) {
        assert(ma[i].timestampNs == ea[i].timestampNs);
        assert(std::memcmp(&ma[i].x, &ea[i].x, 12) == 0);
        if (i > 0) assert(ma[i].timestampNs >= ma[i - 1].timestampNs);
    }
    std::cout << "Coalesced " << expectGyro << " gyro + " << expectAccel
              << " accel samples, both decoders agree\n";
}
