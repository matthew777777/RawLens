// SPDX-License-Identifier: GPL-3.0-or-later
// Persistent production RAW engine vs validated normalized-input product
// engine.
#include "photo_rcd_engine.h"
#include "photo_rcd_input.h"
#include <fstream>
#include <iostream>
#include <sstream>
template <class T> std::vector<T> read(const std::string &name) {
  std::ifstream f(name, std::ios::binary | std::ios::ate);
  if (!f || size_t(f.tellg()) % sizeof(T))
    throw std::runtime_error("input layout");
  std::vector<T> v(size_t(f.tellg()) / sizeof(T));
  f.seekg(0);
  f.read(reinterpret_cast<char *>(v.data()), v.size() * sizeof(T));
  if (!f)
    throw std::runtime_error("input read");
  return v;
}
int main(int argc, char **argv) {
  try {
    if (argc != 5 && argc != 6 && argc != 7)
      throw std::runtime_error(
          "usage: normalized.spv normalize.spv baseline-product.spv jobs");
    std::array<uint32_t, 2> shape = {16, 16};
    if (argc == 7) {
      std::string value = argv[6];
      auto split = value.find('x');
      shape = {uint32_t(std::stoi(value.substr(0, split))),
               uint32_t(std::stoi(value.substr(split + 1)))};
    }
    photo_rcd::Engine actual(
        read<uint32_t>(argv[1]), true,
        argc >= 6 && std::string(argv[5]) == "cached", false, false, false, {},
        false, read<uint32_t>(argv[2]), 1, true,
        argc >= 6 && std::string(argv[5]) == "tile-local", false, shape);
    photo_rcd::Engine baseline(read<uint32_t>(argv[3]));
    std::ifstream jobs(argv[4]);
    std::string line;
    int count = 0;
    float maximum = 0;
    float fpMaximum = 0;
    double largestMean = 0;
    while (std::getline(jobs, line)) {
      std::istringstream job(line);
      std::string metadata, source;
      int phase;
      job >> metadata >> source >> phase;
      auto p = read<uint8_t>(metadata);
      if (p.size() != 64)
        throw std::runtime_error("metadata length");
      std::array<int, 9> g;
      std::memcpy(g.data(), p.data(), 32);
      g[8] = phase;
      std::array<float, 4> black;
      float white;
      std::memcpy(black.data(), p.data() + 32, 16);
      std::memcpy(&white, p.data() + 48, 4);
      auto raw = read<uint8_t>(source);
      auto cfa =
          photo_rcd::normalizeRaw16(raw.data(), raw.size(), g[0], g[1], g[2],
                                    g[3], g[4], g[5], g[6], g[7], black, white);
      std::vector<uint32_t> config(16 + 24);
      config[0] = g[4];
      config[1] = g[5];
      float one = 1;
      for (int i = 2; i < 5; ++i)
        std::memcpy(&config[i], &one, 4);
      if (count % 2) {
        const float wb[3] = {1.7f, 1.f, 1.3f};
        for (int i = 0; i < 3; ++i)
          std::memcpy(&config[2 + i], wb + i, 4);
        config[5] = 2;
        config[6] = 3;
        config[7] = 0;
        config[8] = 0;
        config[9] = g[0];
        config[10] = g[1];
        config[11] = 1;
        for (int i = 0; i < 24; ++i) {
          float value = .75f + float((i + count) % 13) / 16.f;
          std::memcpy(&config[16 + i], &value, 4);
        }
      }
      std::vector<uint16_t> a, b;
      actual.renderRawHalf(raw.data(), raw.size(), g, black, white, config, a);
      baseline.renderHalf(g[6], g[7], phase, cfa.data(), cfa.size(), config, b);
      if (a.size() != b.size())
        throw std::runtime_error("output size");
      float error = 0;
      for (size_t i = 0; i < a.size(); ++i)
        error = std::max(error, std::abs(photo_rcd::halfToFloat(a[i]) -
                                         photo_rcd::halfToFloat(b[i])));
      if (!std::isfinite(error) || error > .001f)
        throw std::runtime_error("half boundary comparison failed");
      maximum = std::max(error, maximum);
      std::vector<float> rawRgb, reference(size_t(g[6]) * g[7] * 4);
      actual.renderRaw(raw.data(), raw.size(), g, black, white, rawRgb);
      baseline.render(g[6], g[7], phase, cfa.data(), cfa.size(),
                      reference.data(), reference.size());
      double sum = 0;
      float fpError = 0;
      for (size_t i = 0; i < reference.size(); ++i) {
        float difference = std::abs(rawRgb[i] - reference[i]);
        if (!std::isfinite(rawRgb[i]))
          throw std::runtime_error("nonfinite FP32 output");
        fpError = std::max(fpError, difference);
        sum += difference;
      }
      double mean = sum / reference.size();
      if (fpError > 2e-5f || mean > 2e-6)
        throw std::runtime_error("canonical FP32 numerical budget exceeded");
      fpMaximum = std::max(fpMaximum, fpError);
      largestMean = std::max(largestMean, mean);
      ++count;
    }
    std::cout << actual.deviceName() << "\nframes=" << count
              << " max_half_error=" << maximum
              << " max_fp32_error=" << fpMaximum
              << " largest_mean_fp32_error=" << largestMean << "\n";
  } catch (const std::exception &e) {
    std::cerr << e.what() << "\n";
    return 2;
  }
}
