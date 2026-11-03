// SPDX-License-Identifier: GPL-3.0-or-later
// Diagnostic isolated full-source execution; not encoder-delivered FPS.
#include "photo_rcd_engine.h"
#include <chrono>
#include <cstring>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <vector>
static std::vector<uint32_t> load(const char *path) {
  std::ifstream f(path, std::ios::binary | std::ios::ate);
  if (!f || size_t(f.tellg()) % 4)
    throw std::runtime_error("program bytes");
  std::vector<uint32_t> v(size_t(f.tellg()) / 4);
  f.seekg(0);
  f.read(reinterpret_cast<char *>(v.data()), v.size() * 4);
  if (!f)
    throw std::runtime_error("program read");
  return v;
}
int main(int argc, char **argv) {
  try {
    if (argc != 6)
      throw std::invalid_argument(
          "usage: rcd.spv normalize.spv coreX coreY repeats");
    uint32_t x = std::stoul(argv[3]), y = std::stoul(argv[4]);
    int repeats = std::stoi(argv[5]);
    if (repeats < 3 || repeats > 16)
      throw std::invalid_argument("repeats");
    photo_rcd::Engine engine(load(argv[1]), true, false, false, false, false,
                             {}, false, load(argv[2]), 1, true, true, false,
                             {x, y});
    const int sw = 4080, sh = 3060, row = sw * 2, w = 3840, h = 2160;
    std::vector<uint8_t> raw(size_t(row) * sh);
    for (int sy = 0; sy < sh; ++sy)
      for (int sx = 0; sx < sw; ++sx) {
        uint16_t v = uint16_t((sx * 137 + sy * 991 + 17) % 5000);
        raw[size_t(sy) * row + sx * 2] = v & 255;
        raw[size_t(sy) * row + sx * 2 + 1] = v >> 8;
      }
    std::array<int, 9> g = {sw, sh, row, 2, 120, 450, w, h, 148};
    std::array<float, 4> black = {64, 64.5, 63.25, 65};
    std::vector<uint32_t> config(16);
    config[0] = 120;
    config[1] = 450;
    float one = 1;
    for (int i = 2; i < 5; ++i)
      std::memcpy(&config[i], &one, 4);
    std::vector<uint16_t> half;
    std::cout << "{\"core\":[" << x << "," << y << "],\"samples\":[";
    for (int i = 0; i < repeats; ++i) {
      auto start = std::chrono::steady_clock::now();
      engine.renderRawHalf(raw.data(), raw.size(), g, black, 4095, config,
                           half);
      double wall = std::chrono::duration<double, std::milli>(
                        std::chrono::steady_clock::now() - start)
                        .count();
      auto costs = engine.gpuStageCosts();
      if (i)
        std::cout << ",";
      std::cout << "{\"wall_ms\":" << wall << ",\"gpu_stage_ms\":[";
      for (size_t j = 0; j < costs.size(); ++j) {
        if (j)
          std::cout << ",";
        std::cout << costs[j];
      }
      std::cout << "]}" << std::flush;
    }
    std::cout << "],\"half_samples\":" << half.size() << "}\n";
  } catch (const std::exception &e) {
    std::cerr << e.what() << "\n";
    return 2;
  }
}
