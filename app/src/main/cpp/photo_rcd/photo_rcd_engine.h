// SPDX-License-Identifier: GPL-3.0-or-later
// RawLens/Codex, 2026-11-01. Independent integration for the attributed RT
// port.
#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>
namespace photo_rcd {
class Engine {
public:
  // Shader is generated from the approved scalar baseline, not the retired
  // backend.
  explicit Engine(const std::vector<uint32_t> &shader,
                  bool specializePasses = false, bool cacheResponses = false,
                  bool tiledReconstruction = false,
                  bool imageIntermediates = false,
                  bool recordingCandidate = false,
                  const std::vector<std::vector<uint32_t>> &stageShaders = {},
                  bool packedCells = false,
                  const std::vector<uint32_t> &rawPackShader = {},
                  int reconstructionScale = 1, bool normalizeOnce = false,
                  bool tileLocal = false, bool imageOutput = false,
                  std::array<uint32_t, 2> tileShape = {16, 16},
                  bool frameGlobal = false);
  ~Engine();
  Engine(const Engine &) = delete;
  Engine &operator=(const Engine &) = delete;
  // Synchronous validation baseline: returns only after all GPU reads/writes
  // finish. Reuses a frame allocation and command buffer until dimensions/phase
  // change.
  void render(int width, int height, uint32_t phase, const float *cfa,
              size_t samples, float *rgba, size_t outputSamples);
  const std::string &deviceName() const;
  std::vector<double> gpuStageCosts() const;
  void renderHalf(int width, int height, uint32_t phase, const float *cfa,
                  size_t samples, const std::vector<uint32_t> &correction,
                  std::vector<uint16_t> &half);

  void renderRaw(const uint8_t *data, size_t bytes,
                 const std::array<int, 9> &geometry,
                 const std::array<float, 4> &black, float white,
                 std::vector<float> &rgba);
  bool usesImageOutput() const;
  uint64_t outputUsage() const;
  void renderRawImage(const uint8_t *data, size_t bytes,
                      const std::array<int, 9> &geometry,
                      const std::array<float, 4> &black, float white,
                      const std::vector<uint32_t> &correction,
                      void *hardwareBuffer);
  bool usesRawInput() const;
  // Frame-global recording: the downstream grade point-samples the RCD frame
  // at int((i+0.5)*src/out); the final pass may skip source pixels it never
  // reads. 0x0 (default) computes every pixel.
  void setOutputSampling(int width, int height);
  int reconstructionScale() const;
  void renderRawHalf(const uint8_t *data, size_t bytes,
                     const std::array<int, 9> &geometry,
                     const std::array<float, 4> &black, float white,
                     const std::vector<uint32_t> &correction,
                     std::vector<uint16_t> &half);

private:
  void renderRawFrame(const uint8_t *data, size_t bytes,
                      const std::array<int, 9> &geometry,
                      const std::array<float, 4> &black, float white,
                      const std::vector<uint32_t> &correction,
                      std::vector<uint16_t> *half, std::vector<float> *rgba,
                      void *hardwareBuffer = nullptr);
  struct State;
  std::unique_ptr<State> state;
};
} // namespace photo_rcd
