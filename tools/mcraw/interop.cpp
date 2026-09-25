// Regression probe for RawLens containers against both upstream readers.
#include <MediaCinemaRAW/ContainerReader.h>
#include <MediaCinemaRAW/ContainerWriter.h>
#include <MediaCinemaRAW/Encoder.h>
#include <motioncam/Decoder.hpp>
#include <cassert>
#include <cstring>
#include <iostream>

int main(int argc, char** argv) {
    const std::string path = argc > 1 ? argv[1] : "/private/tmp/rawlens-interop.mcraw";
    if (argc == 1) {
        mediacinemaraw::ContainerWriter writer(path,
            R"({"extraData":{"audioSampleRate":48000,"audioChannels":1}})");
        std::vector<uint8_t> raw(64*8*2, 17), payload;
        mediacinemaraw::encode(raw.data(),raw.size(),64,8,128,false,0,8,false,payload);
        writer.writeFrame(payload,1000000000,R"({"width":64,"height":8,"compressionType":7})");
        int16_t samples[] = {1,-2,32767,-32768};
        writer.writeAudio(samples,4,1000000000);
        writer.close();
    }
    motioncam::Decoder motion(path);
    mediacinemaraw::ContainerReader media(path);
    assert(motion.getFrames() == media.frameTimestamps());
    auto& timestamps = motion.getFrames();
    assert(!timestamps.empty());
    for (size_t i : {size_t(0), timestamps.size()/2, timestamps.size()-1}) {
        std::vector<uint8_t> raw; nlohmann::json meta;
        motion.loadFrame(timestamps[i],raw,meta);
        mediacinemaraw::Frame frame;
        media.loadFrame(timestamps[i],frame);
        assert(raw.size() == frame.pixels.size()*2);
        assert(std::memcmp(raw.data(),frame.pixels.data(),raw.size()) == 0);
        std::cout << "Matching frame " << i << ": " << frame.width << "x" << frame.height << " metadata=" << meta.dump() << '\n';
    }
    std::vector<motioncam::AudioChunk> a;
    std::vector<mediacinemaraw::AudioChunk> b;
    motion.loadAudio(a); media.loadAudio(b);
    assert(!a.empty() && a.size() == b.size());
    size_t samples = 0; int peak = 0;
    for (size_t i=0;i<a.size();++i) {
        assert(a[i].first == b[i].timestampNs && a[i].second == b[i].samples);
        samples += a[i].second.size();
        for (int16_t s : a[i].second) peak = std::max(peak,std::abs(int(s)));
    }
    std::cout << "Frames=" << timestamps.size() << " audio chunks=" << a.size()
              << " samples=" << samples << " peak=" << peak << '\n';
    if (motion.getContainerMetadata().contains("extraData")) {
        assert(motion.audioSampleRateHz() == media.audioSampleRateHz());
        assert(motion.numAudioChannels() == media.numAudioChannels());
        std::cout << "Audio rate=" << motion.audioSampleRateHz() << " channels=" << motion.numAudioChannels() << '\n';
    } else std::cout << "BROKEN audio declaration: extraData missing\n";
}
