// Regression: MotionCam Tools v1.0 (decoder era Jan 2026, pre-gyro) stops
// its tail scan at the first motion item, so a file with trailing motion
// data BEFORE the audio index yields "No audio chunks found" and silent
// video — even though current decoders read it fine. This probe builds
// against the Jan-2026 decoder sources (extracted from the checkout's git
// history by verify.sh; link this file ONLY with those, never with the
// current decoder — same namespace) and asserts the writer's tail order
// keeps audio discoverable: audio index before any trailing motion data.
//
// Fixture mimics the recorder: frames with interleaved motion drains,
// audio chunks, and trailing motion flushed at close. With argv[1], also
// opens that file and reports what the legacy parser finds.
#include <MediaCinemaRAW/ContainerWriter.h>
#include <MediaCinemaRAW/Encoder.h>
#include <motioncam/Decoder.hpp>
#include <cassert>
#include <cstdio>
#include <cstring>
#include <iostream>

namespace {
uint32_t getU32(const uint8_t* p) {
    return uint32_t(p[0]) | (uint32_t(p[1]) << 8) |
           (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24);
}
// File positions of items of one kind in the pre-index region.
std::vector<int64_t> itemPositions(const std::string& path, uint32_t want,
                                   int64_t* lastFramePos) {
    FILE* f = std::fopen(path.c_str(), "rb");
    assert(f);
    std::fseek(f, 0, SEEK_END);
    const int64_t fileSize = std::ftell(f);
    std::fseek(f, int64_t(fileSize - 24), SEEK_SET);
    uint8_t foot[24];
    assert(std::fread(foot, 1, 24, f) == 24);
    assert(getU32(foot) == 0 && getU32(foot + 4) == 16);
    uint64_t v = 0;
    for (int i = 0; i < 8; ++i) v |= uint64_t(foot[16 + i]) << (8 * i);
    const int64_t listHead = int64_t(v) - 8;
    uint8_t hb[8];
    std::fseek(f, 8, SEEK_SET);
    assert(std::fread(hb, 1, 8, f) == 8);
    std::fseek(f, getU32(hb + 4), SEEK_CUR);
    std::vector<int64_t> out;
    *lastFramePos = -1;
    while (std::ftell(f) + 8 <= listHead) {
        const int64_t pos = std::ftell(f);
        assert(std::fread(hb, 1, 8, f) == 8);
        const uint32_t kind = getU32(hb), size = getU32(hb + 4);
        if (kind == 2) *lastFramePos = pos;
        if (kind == want) out.push_back(pos);
        std::fseek(f, size, SEEK_CUR);
    }
    std::fclose(f);
    return out;
}
}  // namespace

int main(int argc, char** argv) {
    {
        const std::string path = "/private/tmp/rawlens-legacy-audio.mcraw";
        {
            mediacinemaraw::ContainerWriter writer(
                path, R"({"extraData":{"audioSampleRate":48000,"audioChannels":1}})");
            std::vector<uint8_t> raw(64 * 8 * 2, 17), payload;
            mediacinemaraw::encode(raw.data(), raw.size(), 64, 8, 128, false,
                                  0, 8, false, payload);
            int64_t sensorTs = 0;
            for (int f = 0; f < 3; ++f) {
                for (int d = 0; d < 10; ++d) {
                    mediacinemaraw::GyroSample g[] = {{sensorTs, 0, 0, 0}};
                    mediacinemaraw::AccelerometerSample a[] = {{sensorTs, 0, 0, 0}};
                    sensorTs += 2000000;
                    writer.writeGyro(g, 1);
                    writer.writeAccelerometer(a, 1);
                }
                int16_t pcm[] = {100, 200};
                writer.writeAudio(pcm, 2, int64_t(f) * 33333333);
                writer.writeFrame(payload, int64_t(f) * 33333333,
                                  R"({"width":64,"height":8,"compressionType":7})");
            }
            // Trailing motion after the last frame (the recorder always
            // has some): must land AFTER the audio index.
            mediacinemaraw::GyroSample g[] = {{sensorTs, 0, 0, 0}};
            mediacinemaraw::AccelerometerSample a[] = {{sensorTs, 0, 0, 0}};
            writer.writeGyro(g, 1);
            writer.writeAccelerometer(a, 1);
            writer.close();
        }
        // Structural: audio index precedes any post-last-frame motion data.
        int64_t lastFrame = -1;
        const auto audioIdx = itemPositions(path, 4, &lastFrame);
        assert(audioIdx.size() == 1 && lastFrame > 0);
        int64_t dummy = -1;
        for (uint32_t kind : {9u, 13u})
            for (int64_t p : itemPositions(path, kind, &dummy))
                if (p > lastFrame) assert(audioIdx[0] < p);
        std::cout << "Tail order: audio index before trailing motion\n";
        // Behavioral: the Jan-2026 parser finds the audio (Tools v1.0
        // reported "No audio chunks found" before the fix).
        motioncam::Decoder legacy(path);
        std::vector<motioncam::AudioChunk> chunks;
        legacy.loadAudio(chunks);
        assert(chunks.size() == 3);
        assert(chunks[0].second == std::vector<int16_t>({100, 200}));
        assert(legacy.audioSampleRateHz() == 48000);
        assert(legacy.numAudioChannels() == 1);
        std::cout << "Legacy parser: 3 audio chunks found\n";
    }
    if (argc > 1) {
        motioncam::Decoder legacy(argv[1]);
        std::vector<motioncam::AudioChunk> chunks;
        legacy.loadAudio(chunks);
        size_t samples = 0;
        int peak = 0;
        for (auto& c : chunks) {
            samples += c.second.size();
            for (int16_t s : c.second) peak = std::max(peak, std::abs(int(s)));
        }
        std::cout << "Legacy parser on " << argv[1] << ": chunks=" << chunks.size()
                  << " samples=" << samples << " peak=" << peak << " rate="
                  << legacy.audioSampleRateHz() << " channels="
                  << legacy.numAudioChannels() << '\n';
    }
}
