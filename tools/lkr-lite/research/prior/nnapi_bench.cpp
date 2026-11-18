// SPDX-License-Identifier: Apache-2.0
// NNAPI benchmark of the LK-R Lite post-fit prior on ONE explicit accelerator (e.g. MediaTek MDLA):
// the graph is built from prior.bin (export_nnapi.py: CONV_2D + fused ReLU, identity 1x1 skip, ADD),
// every operation is checked to be supported by that device, and the compilation targets ONLY that
// device (no CPU / GPU fallback or partitioning). fp16 via relaxComputationFloat32toFloat16.
//   nnapi_bench DIR [device-substring=mdla] [tile=256] [runs=50] [fp16=1]
#include <android/NeuralNetworks.h>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#define CK(x) do { int r_ = (x); if (r_ != ANEURALNETWORKS_NO_ERROR) { std::fprintf(stderr, "FAIL %s = %d (line %d)\n", #x, r_, __LINE__); return 1; } } while (0)

struct Layer { uint32_t cin, cout, k, relu; std::vector<float> w, b; };

static bool readFile(const std::string& p, std::vector<uint8_t>* out) {
  FILE* f = std::fopen(p.c_str(), "rb"); if (!f) return false;
  std::fseek(f, 0, SEEK_END); out->resize(size_t(std::ftell(f))); std::fseek(f, 0, SEEK_SET);
  bool ok = std::fread(out->data(), 1, out->size(), f) == out->size(); std::fclose(f); return ok;
}

int main(int argc, char** argv) {
  if (argc < 2) { std::fprintf(stderr, "usage: %s DIR [device=mdla] [tile=256] [runs=50] [fp16=1]\n", argv[0]); return 2; }
  const std::string dir = argv[1], want = argc > 2 ? argv[2] : "mdla";
  const uint32_t T = argc > 3 ? uint32_t(std::atoi(argv[3])) : 256; const int runs = argc > 4 ? std::atoi(argv[4]) : 50;
  const bool fp16 = argc > 5 ? std::atoi(argv[5]) != 0 : true;

  // ---- weights ----
  std::vector<uint8_t> raw; if (!readFile(dir + "/prior.bin", &raw)) { std::fprintf(stderr, "no prior.bin\n"); return 1; }
  size_t off = 0; auto u32 = [&]() { uint32_t v; std::memcpy(&v, &raw[off], 4); off += 4; return v; };
  std::vector<Layer> L(u32());
  for (auto& l : L) {
    l.cin = u32(); l.cout = u32(); l.k = u32(); l.relu = u32();
    l.w.resize(size_t(l.cout) * l.k * l.k * l.cin); std::memcpy(l.w.data(), &raw[off], l.w.size() * 4); off += l.w.size() * 4;
    l.b.resize(l.cout); std::memcpy(l.b.data(), &raw[off], l.b.size() * 4); off += l.b.size() * 4;
  }
  const uint32_t CIN = L[0].cin, COUT = L.back().cout;

  // ---- device ----
  uint32_t nd = 0; CK(ANeuralNetworks_getDeviceCount(&nd));
  ANeuralNetworksDevice* dev = nullptr;
  for (uint32_t i = 0; i < nd; ++i) {
    ANeuralNetworksDevice* d; CK(ANeuralNetworks_getDevice(i, &d));
    const char* name = nullptr; ANeuralNetworksDevice_getName(d, &name);
    int32_t type = 0; ANeuralNetworksDevice_getType(d, &type);
    int64_t fl = 0; ANeuralNetworksDevice_getFeatureLevel(d, &fl);
    std::printf("device %u: %s type %d feature level %lld\n", i, name, type, (long long)fl);
    if (!dev && name && std::string(name).find(want) != std::string::npos) dev = d;
  }
  if (!dev) { std::fprintf(stderr, "no device matching '%s'\n", want.c_str()); return 1; }
  const char* dname; ANeuralNetworksDevice_getName(dev, &dname); std::printf("using ONLY: %s\n", dname);

  // ---- model ----
  ANeuralNetworksModel* model; CK(ANeuralNetworksModel_create(&model));
  uint32_t nop = 0;
  auto tensor = [&](std::vector<uint32_t> dims) {
    ANeuralNetworksOperandType t{ANEURALNETWORKS_TENSOR_FLOAT32, uint32_t(dims.size()), nullptr, 0.f, 0};
    static std::vector<std::vector<uint32_t>> keep; keep.push_back(dims); t.dimensions = keep.back().data();
    if (ANeuralNetworksModel_addOperand(model, &t) != ANEURALNETWORKS_NO_ERROR) return -1; return int(nop++);
  };
  auto scalarI = [&](int32_t v) {
    ANeuralNetworksOperandType t{ANEURALNETWORKS_INT32, 0, nullptr, 0.f, 0};
    ANeuralNetworksModel_addOperand(model, &t); ANeuralNetworksModel_setOperandValue(model, nop, &v, 4); return int(nop++);
  };
  const int in = tensor({1, T, T, CIN});
  int h = in;
  std::vector<uint32_t> opsList;   // operation count for the support query
  int nOps = 0;
  auto conv = [&](int x, const float* w, const float* b, uint32_t cin, uint32_t cout, uint32_t k, bool relu) {
    int fw = tensor({cout, k, k, cin}); ANeuralNetworksModel_setOperandValue(model, fw, w, size_t(cout) * k * k * cin * 4);
    int fb = tensor({cout}); ANeuralNetworksModel_setOperandValue(model, fb, b, size_t(cout) * 4);
    int pad = scalarI(ANEURALNETWORKS_PADDING_SAME), s1 = scalarI(1), s2 = scalarI(1), act = scalarI(relu ? ANEURALNETWORKS_FUSED_RELU : ANEURALNETWORKS_FUSED_NONE);
    int y = tensor({1, T, T, cout});
    uint32_t ins[7] = {uint32_t(x), uint32_t(fw), uint32_t(fb), uint32_t(pad), uint32_t(s1), uint32_t(s2), uint32_t(act)}, outs[1] = {uint32_t(y)};
    ANeuralNetworksModel_addOperation(model, ANEURALNETWORKS_CONV_2D, 7, ins, 1, outs); ++nOps;
    return y;
  };
  for (auto& l : L) h = conv(h, l.w.data(), l.b.data(), l.cin, l.cout, l.k, l.relu != 0);
  // skip: identity 1x1 (input channels 0..COUT-1) + ADD
  static std::vector<float> idw(size_t(COUT) * CIN, 0.f), idb(COUT, 0.f);
  for (uint32_t o = 0; o < COUT; ++o) idw[size_t(o) * CIN + o] = 1.f;
  int skip = conv(in, idw.data(), idb.data(), CIN, COUT, 1, false);
  int out = tensor({1, T, T, COUT});
  { int act = scalarI(ANEURALNETWORKS_FUSED_NONE); uint32_t ins[3] = {uint32_t(h), uint32_t(skip), uint32_t(act)}, outs[1] = {uint32_t(out)};
    ANeuralNetworksModel_addOperation(model, ANEURALNETWORKS_ADD, 3, ins, 1, outs); ++nOps; }
  uint32_t mi[1] = {uint32_t(in)}, mo[1] = {uint32_t(out)};
  CK(ANeuralNetworksModel_identifyInputsAndOutputs(model, 1, mi, 1, mo));
  if (fp16) CK(ANeuralNetworksModel_relaxComputationFloat32toFloat16(model, true));
  CK(ANeuralNetworksModel_finish(model));

  // ---- every op must run on this device ----
  bool all = true;
  { std::unique_ptr<bool[]> s(new bool[nOps]); const ANeuralNetworksDevice* ds[1] = {dev};
    CK(ANeuralNetworksModel_getSupportedOperationsForDevices(model, ds, 1, s.get()));
    int n = 0; for (int i = 0; i < nOps; ++i) { n += s[i]; all &= s[i]; }
    std::printf("operations supported on %s: %d / %d\n", dname, n, nOps); }
  if (!all) { std::fprintf(stderr, "NOT all operations run on %s: refusing (no fallback wanted)\n", dname); return 1; }

  // ---- compile for that device only ----
  auto t0 = std::chrono::steady_clock::now();
  ANeuralNetworksCompilation* comp; const ANeuralNetworksDevice* ds[1] = {dev};
  CK(ANeuralNetworksCompilation_createForDevices(model, ds, 1, &comp));
  CK(ANeuralNetworksCompilation_setPreference(comp, ANEURALNETWORKS_PREFER_SUSTAINED_SPEED));
  CK(ANeuralNetworksCompilation_finish(comp));
  std::printf("compile %.1f ms (fp16 relax %d)\n", std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count(), int(fp16));

  // ---- run ----
  std::vector<uint8_t> xin, xref; readFile(dir + "/test_in.f32", &xin); readFile(dir + "/test_out.f32", &xref);
  const bool check = xin.size() == size_t(T) * T * CIN * 4 && xref.size() == size_t(T) * T * COUT * 4;
  if (!check) { xin.assign(size_t(T) * T * CIN * 4, 0); for (size_t i = 0; i < xin.size() / 4; ++i) reinterpret_cast<float*>(xin.data())[i] = 0.3f + 0.2f * float((i * 2654435761u) % 1000) / 1000.f; }
  std::vector<float> y(size_t(T) * T * COUT);
  double best = 1e30, sum = 0; uint64_t hw = 0;
  for (int r = 0; r < runs + 3; ++r) {
    ANeuralNetworksExecution* ex; CK(ANeuralNetworksExecution_create(comp, &ex));
    CK(ANeuralNetworksExecution_setMeasureTiming(ex, true));
    CK(ANeuralNetworksExecution_setInput(ex, 0, nullptr, xin.data(), xin.size()));
    CK(ANeuralNetworksExecution_setOutput(ex, 0, nullptr, y.data(), y.size() * 4));
    auto a = std::chrono::steady_clock::now();
    CK(ANeuralNetworksExecution_compute(ex));
    double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - a).count();
    uint64_t d = 0; ANeuralNetworksExecution_getDuration(ex, ANEURALNETWORKS_DURATION_ON_HARDWARE, &d);
    ANeuralNetworksExecution_free(ex);
    if (r >= 3) { best = std::min(best, ms); sum += ms; if (d != UINT64_MAX) hw += d; }
  }
  // accuracy vs the PyTorch reference (interior; border padding differs by design)
  const float* ref = reinterpret_cast<const float*>(xref.data()); double md = 0, mad = 0; size_t n = 1;
  if (check) for (uint32_t yy = 8; yy < T - 8; ++yy) for (uint32_t xx = 8; xx < T - 8; ++xx) for (uint32_t c = 0; c < COUT; ++c) {
    size_t i = (size_t(yy) * T + xx) * COUT + c; double d = std::fabs(double(y[i]) - ref[i]); md = std::max(md, d); mad += d; ++n; }
  std::printf("tile %ux%u: best %.2f ms, mean %.2f ms, on-hardware %.2f ms | max |diff| %.2e mean %.2e vs torch\n",
              T, T, best, sum / runs, hw ? double(hw) / runs / 1e6 : -1.0, md, mad / n);
  const double tiles2x = std::ceil(8160.0 / (T - 32)) * std::ceil(6120.0 / (T - 32));
  std::printf("full 2x frame (8160x6120, %.0f tiles of %u with 16 px overlap): ~%.1f s at best tile time\n", tiles2x, T, tiles2x * best / 1000.0);
  ANeuralNetworksCompilation_free(comp); ANeuralNetworksModel_free(model);
  return 0;
}
