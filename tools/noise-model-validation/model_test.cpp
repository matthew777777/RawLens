// SPDX-License-Identifier: GPL-3.0-or-later
#include "../../app/src/main/cpp/wavelet/noise_model.h"
#include <cassert>
#include <limits>

int main(int argc, char** argv) {
    assert(argc == 2);
    const std::string path = argv[1];
    std::vector<rawlens_noise::Sample> samples;
    for (int i = 1; i <= 30; ++i)
        rawlens_noise::addSample(samples, {400, 8e-5f, 3e-6f, 8, 800, (uint64_t)i});
    assert(samples.size() == rawlens_noise::kWindow);
    rawlens_noise::addSample(samples, samples.back());
    assert(samples.size() == rawlens_noise::kWindow);
    auto bad = samples.back(); bad.iso = std::numeric_limits<float>::infinity();
    rawlens_noise::addSample(samples, bad);
    assert(samples.size() == rawlens_noise::kWindow);
    assert(rawlens_noise::saveSamples(path, samples));
    const auto loaded = rawlens_noise::loadSamples(path);
    assert(loaded.size() == samples.size());
    assert(loaded.back().captureId == 30 && loaded.back().analogIso == 800);
    const auto model = rawlens_noise::fit(loaded);
    assert(model.supports(400) && !model.supports(100));
    assert(std::fabs(model.predictA(400) / 8e-5f - 1) < 1e-5);
    // Schema v1 used a different fitter and must never silently contaminate v2.
    FILE* f = std::fopen(path.c_str(), "w"); assert(f);
    std::fprintf(f, "rawlens-noise v1\n400 0.0001 0.00001 8\n"); std::fclose(f);
    assert(rawlens_noise::loadSamples(path).empty());
    // A truncated row is never training data.
    f = std::fopen(path.c_str(), "w"); assert(f);
    std::fprintf(f, "rawlens-noise v2\n400 0.0001\n400 0.0001 0.00001 8 800 123\n"); std::fclose(f);
    const auto recovered = rawlens_noise::loadSamples(path);
    assert(recovered.size() == 1 && recovered[0].captureId == 123);
    std::remove(path.c_str());
}
