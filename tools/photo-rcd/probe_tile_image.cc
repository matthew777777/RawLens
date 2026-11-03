// SPDX-License-Identifier: GPL-3.0-or-later
// Real external RGBA16F capability and pixel probe, no recording.
#include "photo_rcd_engine.h"
#include <android/hardware_buffer.h>
#include <cstring>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <vector>
std::vector<uint32_t> load(const char *path) {
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
    if (argc != 3)
      throw std::invalid_argument("usage: image.spv normalize.spv");
    photo_rcd::Engine engine(load(argv[1]), true, false, false, false, false,
                             {}, false, load(argv[2]), 1, true, true, true);
    AHardwareBuffer_Desc desc{};
    desc.width = 40;
    desc.height = 40;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT;
    desc.usage = AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
                 AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN |
                 AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                 AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
    desc.usage |= engine.outputUsage();
    AHardwareBuffer *buffer = nullptr;
    int allocation = AHardwareBuffer_allocate(&desc, &buffer);
    if (allocation || !buffer)
      throw std::runtime_error("AHB allocation failed " +
                               std::to_string(allocation));
    try {
      std::vector<uint8_t> raw(40 * 40 * 2);
      for (size_t i = 0; i < 1600; ++i) {
        raw[i * 2] = 0;
        raw[i * 2 + 1] = 4;
      }
      std::array<int, 9> g = {40, 40, 80, 2, 0, 0, 40, 40, 148};
      std::array<float, 4> black = {0, 0, 0, 0};
      std::vector<uint32_t> config(16);
      float one = 1;
      for (int i = 2; i < 5; ++i)
        std::memcpy(&config[i], &one, 4);
      engine.renderRawImage(raw.data(), raw.size(), g, black, 4096, config,
                            buffer);
      AHardwareBuffer_describe(buffer, &desc);
      void *data = nullptr;
      if (AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1,
                               nullptr, &data) ||
          !data)
        throw std::runtime_error("read lock failed");
      int differences = 0;
      for (int y = 0; y < 40; ++y)
        for (int x = 0; x < 40; ++x)
          for (int c = 0; c < 4; ++c) {
            auto value = static_cast<uint16_t *>(
                data)[(size_t(y) * desc.stride + x) * 4 + c];
            if (value != (c == 3 ? 0x3c00 : 0x3400))
              ++differences;
          }
      int unlock = AHardwareBuffer_unlock(buffer, nullptr);
      std::cout << "pixel_differences=" << differences << " unlock=" << unlock
                << " stride=" << desc.stride << " usage=" << desc.usage << "\n";
      if (differences || unlock)
        throw std::runtime_error("GPU image content mismatch");
    } catch (...) {
      AHardwareBuffer_release(buffer);
      throw;
    }
    AHardwareBuffer_release(buffer);
    return 0;
  } catch (const std::exception &e) {
    std::cerr << e.what() << "\n";
    return 2;
  }
}
