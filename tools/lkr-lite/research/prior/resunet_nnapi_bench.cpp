// SPDX-License-Identifier: Apache-2.0
// Synthetic 2022 LK-R CNN workload. Build with the Android NDK, linking neuralnetworks.
#include <android/NeuralNetworks.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <initializer_list>
#include <memory>
#include <random>
#include <string>
#include <vector>

void check(int result, int line) {
  if (result == ANEURALNETWORKS_NO_ERROR) return;
  std::fprintf(stderr, "NNAPI error %d at line %d\n", result, line);
  std::exit(1);
}
#define CHECK(expr) check((expr), __LINE__)

struct Tensor {
  uint32_t id, side, channels;
};

struct Graph {
  ANeuralNetworksModel* model{};
  uint32_t operands = 0;
  std::vector<const char*> operations;
  std::vector<std::vector<float>> constants;
  std::mt19937 random{2022};
  uint64_t macs = 0;

  Graph() { CHECK(ANeuralNetworksModel_create(&model)); }
  ~Graph() { ANeuralNetworksModel_free(model); }

  uint32_t operand(int type, std::initializer_list<uint32_t> dims) {
    ANeuralNetworksOperandType info{type, uint32_t(dims.size()), dims.begin(), 0, 0};
    CHECK(ANeuralNetworksModel_addOperand(model, &info));
    return operands++;
  }

  uint32_t integer(int32_t value) {
    const auto id = operand(ANEURALNETWORKS_INT32, {});
    CHECK(ANeuralNetworksModel_setOperandValue(model, id, &value, sizeof(value)));
    return id;
  }

  Tensor image(uint32_t side, uint32_t channels) {
    return {operand(ANEURALNETWORKS_TENSOR_FLOAT32, {1, side, side, channels}),
            side, channels};
  }

  void op(int type, const char* name, std::initializer_list<uint32_t> inputs, Tensor out) {
    CHECK(ANeuralNetworksModel_addOperation(model, type, inputs.size(), inputs.begin(), 1, &out.id));
    operations.push_back(name);
  }

  Tensor conv(Tensor x, uint32_t channels, uint32_t kernel, uint32_t stride = 1,
              int32_t activation = ANEURALNETWORKS_FUSED_NONE) {
    const auto weights = operand(ANEURALNETWORKS_TENSOR_FLOAT32,
                                 {channels, kernel, kernel, x.channels});
    auto& values = constants.emplace_back(size_t(channels) * kernel * kernel * x.channels);
    const float bound = 1.f / std::sqrt(float(kernel * kernel * x.channels));
    std::uniform_real_distribution<float> sample(-bound, bound);
    for (auto& value : values) value = sample(random);
    CHECK(ANeuralNetworksModel_setOperandValue(model, weights, values.data(), values.size() * sizeof(float)));
    const auto bias = operand(ANEURALNETWORKS_TENSOR_FLOAT32, {channels});
    auto& zeros = constants.emplace_back(channels, 0.f);
    CHECK(ANeuralNetworksModel_setOperandValue(model, bias, zeros.data(), zeros.size() * sizeof(float)));
    const Tensor out = image(x.side / stride, channels);
    macs += uint64_t(out.side) * out.side * channels * kernel * kernel * x.channels;
    op(ANEURALNETWORKS_CONV_2D, "CONV_2D",
       {x.id, weights, bias, integer(ANEURALNETWORKS_PADDING_SAME), integer(stride),
        integer(stride), integer(activation)}, out);
    return out;
  }

  Tensor add(Tensor a, Tensor b) {
    const Tensor out = image(a.side, a.channels);
    op(ANEURALNETWORKS_ADD, "ADD", {a.id, b.id, integer(ANEURALNETWORKS_FUSED_NONE)}, out);
    return out;
  }

  Tensor residualStack(Tensor x) {
    for (int block = 0; block < 2; ++block) {
      auto h = conv(x, x.channels, 3, 1, ANEURALNETWORKS_FUSED_RELU);
      x = add(x, conv(h, x.channels, 3));
    }
    return x;
  }

  Tensor resunet(Tensor input, const std::array<uint32_t, 4>& widths, uint32_t outputs) {
    Tensor x = conv(input, widths[0], 3);
    std::array<Tensor, 3> skips{};
    for (size_t scale = 0; scale < skips.size(); ++scale) {
      skips[scale] = residualStack(x);
      x = conv(skips[scale], widths[scale + 1], 2, 2);
    }
    x = residualStack(x);
    for (int scale = 2; scale >= 0; --scale) {
      x = conv(x, widths[scale] * 4, 1);
      const Tensor up = image(x.side * 2, widths[scale]);
      op(ANEURALNETWORKS_DEPTH_TO_SPACE, "DEPTH_TO_SPACE", {x.id, integer(2)}, up);
      x = residualStack(add(up, skips[scale]));
    }
    return conv(x, outputs, 3);
  }
};

int main(int argc, char** argv) {
  if (argc != 5 && argc != 6) {
    std::fprintf(stderr, "usage: %s {prior|feature|confidence} TILE RUNS DEVICE [FLAT_WIDTH]\n", argv[0]);
    return 2;
  }
  const std::string kind = argv[1];
  const int side = std::atoi(argv[2]), runs = std::atoi(argv[3]);
  if (side < 8 || side % 8 || runs < 1 ||
      (kind != "prior" && kind != "feature" && kind != "confidence")) return 2;
  const uint32_t inputs = kind == "prior" ? 4 : kind == "feature" ? 1 : 6;
  const uint32_t outputs = kind == "prior" ? 3 : 1;
  std::array<uint32_t, 4> widths = kind == "prior"
      ? std::array<uint32_t, 4>{32, 64, 128, 128}
      : std::array<uint32_t, 4>{16, 16, 16, 16};
  if (argc == 6) {
    const int width = std::atoi(argv[5]);
    if (width < 1 || width > 128) return 2;
    widths.fill(uint32_t(width));
  }

  Graph graph;
  const auto in = graph.image(side, inputs);
  const auto out = graph.resunet(in, widths, outputs);
  CHECK(ANeuralNetworksModel_identifyInputsAndOutputs(graph.model, 1, &in.id, 1, &out.id));
  CHECK(ANeuralNetworksModel_relaxComputationFloat32toFloat16(graph.model, true));
  CHECK(ANeuralNetworksModel_finish(graph.model));

  uint32_t count = 0;
  CHECK(ANeuralNetworks_getDeviceCount(&count));
  ANeuralNetworksDevice* selected = nullptr;
  for (uint32_t i = 0; i < count; ++i) {
    ANeuralNetworksDevice* device;
    const char* name;
    int32_t type;
    CHECK(ANeuralNetworks_getDevice(i, &device));
    CHECK(ANeuralNetworksDevice_getName(device, &name));
    CHECK(ANeuralNetworksDevice_getType(device, &type));
    if (type == ANEURALNETWORKS_DEVICE_ACCELERATOR && std::string(name) == argv[4]) selected = device;
  }
  if (!selected) {
    std::fprintf(stderr, "No accelerator named %s\n", argv[4]);
    return 1;
  }

  const ANeuralNetworksDevice* devices[] = {selected};
  const auto size = graph.operations.size();
  auto supported = std::make_unique<bool[]>(size);
  CHECK(ANeuralNetworksModel_getSupportedOperationsForDevices(graph.model, devices, 1, supported.get()));
  size_t supportedCount = 0;
  for (size_t i = 0; i < size; ++i) {
    if (supported[i]) ++supportedCount;
    else std::fprintf(stderr, "Unsupported op %zu: %s\n", i, graph.operations[i]);
  }
  std::printf("%s %d px %s widths=%u/%u/%u/%u ops=%zu/%zu GMAC=%.3f\n", kind.c_str(), side,
              argv[4], widths[0], widths[1], widths[2], widths[3], supportedCount, size,
              graph.macs / 1e9);
  if (supportedCount != size) return 1;

  using Clock = std::chrono::steady_clock;
  const auto start = Clock::now();
  ANeuralNetworksCompilation* compilation;
  CHECK(ANeuralNetworksCompilation_createForDevices(graph.model, devices, 1, &compilation));
  CHECK(ANeuralNetworksCompilation_setPreference(compilation, ANEURALNETWORKS_PREFER_SUSTAINED_SPEED));
  CHECK(ANeuralNetworksCompilation_finish(compilation));
  const auto ms = [](auto since) {
    return std::chrono::duration<double, std::milli>(Clock::now() - since).count();
  };
  std::printf("compile_ms=%.2f\n", ms(start));
  std::vector<float> x(size_t(side) * side * inputs), y(size_t(side) * side * outputs);
  std::uniform_real_distribution<float> sample(0.f, 1.f);
  for (auto& v : x) v = sample(graph.random);
  std::vector<double> times;
  std::vector<double> hardwareTimes;
  double totalMs = 0;
  for (int run = -2; run < runs; ++run) {
    const auto begin = Clock::now();
    ANeuralNetworksExecution* execution;
    CHECK(ANeuralNetworksExecution_create(compilation, &execution));
    CHECK(ANeuralNetworksExecution_setMeasureTiming(execution, true));
    CHECK(ANeuralNetworksExecution_setInput(execution, 0, nullptr, x.data(), x.size() * sizeof(float)));
    CHECK(ANeuralNetworksExecution_setOutput(execution, 0, nullptr, y.data(), y.size() * sizeof(float)));
    CHECK(ANeuralNetworksExecution_compute(execution));
    const double elapsed = ms(begin);
    uint64_t hardwareNs = UINT64_MAX;
    CHECK(ANeuralNetworksExecution_getDuration(execution, ANEURALNETWORKS_DURATION_ON_HARDWARE,
                                               &hardwareNs));
    ANeuralNetworksExecution_free(execution);
    if (run >= 0) {
      times.push_back(elapsed);
      totalMs += elapsed;
      if (hardwareNs != UINT64_MAX) hardwareTimes.push_back(double(hardwareNs) / 1e6);
    }
  }
  ANeuralNetworksCompilation_free(compilation);
  const auto [min, max] = std::minmax_element(y.begin(), y.end());
  if (*min == *max || !std::all_of(y.begin(), y.end(), [](float v) { return std::isfinite(v); })) {
    std::fprintf(stderr, "Invalid output\n");
    return 1;
  }
  std::sort(times.begin(), times.end());
  std::sort(hardwareTimes.begin(), hardwareTimes.end());
  const double medianHardware = hardwareTimes.empty() ? -1 : hardwareTimes[hardwareTimes.size() / 2];
  std::printf("n=%d min_ms=%.2f median_ms=%.2f max_ms=%.2f total_ms=%.2f median_hw_ms=%.2f "
              "output=[%.6f,%.6f]\n", runs, times.front(), times[times.size() / 2], times.back(),
              totalMs, medianHardware, *min, *max);
}
