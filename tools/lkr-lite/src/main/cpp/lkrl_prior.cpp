// SPDX-License-Identifier: Apache-2.0
#include "lkrl_prior.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <thread>
#include <utility>
#include <vector>

#ifdef __ANDROID__
#include <android/NeuralNetworks.h>
#include <android/log.h>
#include "lkrl_prior_model.h"   // generated: lkrl_prior_model[] = prior_v2.bin
#endif
#ifndef LKRL_PRIOR_DEFAULT_BIN
#define LKRL_PRIOR_DEFAULT_BIN ""
#endif

namespace lkrl {
namespace {

struct Layer { uint32_t cin, cout, k, relu; std::vector<float> w, b; };   // w: OHWI (NNAPI CONV_2D filter)

const std::vector<Layer>& Layers() {
  static const std::vector<Layer> L = [] {
    std::vector<uint8_t> raw;
#ifdef __ANDROID__
    raw.assign(lkrl_prior_model, lkrl_prior_model + sizeof(lkrl_prior_model));
#else
    const char* p = std::getenv("LKRL_PRIOR_BIN"); if (!p) p = LKRL_PRIOR_DEFAULT_BIN;
    if (FILE* f = std::fopen(p, "rb")) {
      std::fseek(f, 0, SEEK_END); raw.resize(size_t(std::ftell(f))); std::fseek(f, 0, SEEK_SET);
      if (std::fread(raw.data(), 1, raw.size(), f) != raw.size()) raw.clear();
      std::fclose(f);
    }
#endif
    std::vector<Layer> out;
    if (raw.size() < 4) return out;
    size_t off = 0;
    auto u32 = [&]() { uint32_t v = 0; if (off + 4 <= raw.size()) std::memcpy(&v, &raw[off], 4); off += 4; return v; };
    out.resize(u32());
    for (auto& l : out) {
      l.cin = u32(); l.cout = u32(); l.k = u32(); l.relu = u32();
      const size_t nw = size_t(l.cout) * l.k * l.k * l.cin;
      if (off + (nw + l.cout) * 4 > raw.size()) return std::vector<Layer>();
      l.w.resize(nw); std::memcpy(l.w.data(), &raw[off], nw * 4); off += nw * 4;
      l.b.resize(l.cout); std::memcpy(l.b.data(), &raw[off], size_t(l.cout) * 4); off += size_t(l.cout) * 4;
    }
    return out;
  }();
  return L;
}

// NHWC input: sqrt of the image (ch 0-2) + 100 * sigma (ch 3), sigma = sqrt(0.18 a) (training convention).
void MakeInput(const float* z, int H, int W, float noise_a, std::vector<float>* in) {
  const size_t HW = size_t(H) * W;
  const float s100 = 100.0f * std::sqrt(std::max(noise_a, 1e-6f) * 0.18f);
  in->resize(HW * 4);
  for (size_t i = 0; i < HW; ++i) {
    for (int c = 0; c < 3; ++c) (*in)[i * 4 + c] = std::sqrt(std::max(z[size_t(c) * HW + i], 0.0f));
    (*in)[i * 4 + 3] = s100;
  }
}

void WriteBack(const std::vector<float>& out, int H, int W, float* z, float* delta) {
  const size_t HW = size_t(H) * W;
  for (size_t i = 0; i < HW; ++i)
    for (int c = 0; c < 3; ++c) {
      const float o = std::max(out[i * 3 + c], 0.0f), v = o * o;
      float& d = z[size_t(c) * HW + i];
      if (delta) delta[size_t(c) * HW + i] = v - d;
      d = v;
    }
}

#ifndef __ANDROID__
// Host reference (tests on the Mac): the same graph on the CPU, zero padding, threads over rows.
void RunCpu(const std::vector<Layer>& L, const std::vector<float>& in, int H, int W, std::vector<float>* out) {
  std::vector<float> a = in, b;
  int C = int(L[0].cin);
  for (const Layer& l : L) {
    b.assign(size_t(H) * W * l.cout, 0.0f);
    const int r = int(l.k) / 2;
    auto rows = [&](int y0, int y1) {
      for (int y = y0; y < y1; ++y)
        for (int x = 0; x < W; ++x) {
          float* o = &b[(size_t(y) * W + x) * l.cout];
          for (uint32_t co = 0; co < l.cout; ++co) {
            float acc = l.b[co];
            for (int ky = 0; ky < int(l.k); ++ky) {
              const int yy = y + ky - r; if (yy < 0 || yy >= H) continue;
              for (int kx = 0; kx < int(l.k); ++kx) {
                const int xx = x + kx - r; if (xx < 0 || xx >= W) continue;
                const float* ip = &a[(size_t(yy) * W + xx) * C];
                const float* wp = &l.w[((size_t(co) * l.k + ky) * l.k + kx) * l.cin];
                for (uint32_t ci = 0; ci < l.cin; ++ci) acc += wp[ci] * ip[ci];
              }
            }
            o[co] = l.relu ? std::max(acc, 0.0f) : acc;
          }
        }
    };
    const int nt = std::max(1u, std::thread::hardware_concurrency());
    std::vector<std::thread> th;
    for (int t = 0; t < nt; ++t) th.emplace_back(rows, H * t / nt, H * (t + 1) / nt);
    for (auto& t : th) t.join();
    a.swap(b); C = int(l.cout);
  }
  out->resize(size_t(H) * W * 3);
  for (size_t i = 0; i < size_t(H) * W; ++i)
    for (int c = 0; c < 3; ++c) (*out)[i * 3 + c] = a[i * 3 + c] + in[i * 4 + c];   // identity skip
}
#else
// ---- NNAPI, one accelerator only ----
struct Compiled { ANeuralNetworksModel* model = nullptr; ANeuralNetworksCompilation* comp = nullptr; std::string dev; };
std::mutex g_mu;
std::map<std::pair<int, int>, Compiled> g_cache;
std::vector<std::vector<uint32_t>> g_dims;   // operand dimension storage (kept alive)
std::vector<float> g_idw, g_idb;             // identity skip weights (kept alive: >128 B values are referenced)

const ANeuralNetworksDevice* PickDevice(std::string* name) {
  const char* want = std::getenv("LKRL_PRIOR_DEVICE"); if (!want) want = "mdla";
  uint32_t nd = 0; if (ANeuralNetworks_getDeviceCount(&nd) != ANEURALNETWORKS_NO_ERROR) return nullptr;
  for (uint32_t i = 0; i < nd; ++i) {
    ANeuralNetworksDevice* d = nullptr; if (ANeuralNetworks_getDevice(i, &d) != ANEURALNETWORKS_NO_ERROR) continue;
    const char* n = nullptr; ANeuralNetworksDevice_getName(d, &n);
    if (n && std::strstr(n, want)) { *name = n; return d; }
  }
  return nullptr;
}

bool Build(int H, int W, Compiled* out, std::string* err) {
  const auto& L = Layers();
  if (L.empty()) { *err = "prior weights missing"; return false; }
  std::string dname; const ANeuralNetworksDevice* dev = PickDevice(&dname);
  if (!dev) { *err = "no NPU device (mdla) for the prior"; return false; }
  ANeuralNetworksModel* m = nullptr;
  if (ANeuralNetworksModel_create(&m) != ANEURALNETWORKS_NO_ERROR) { *err = "model create"; return false; }
  uint32_t nop = 0; bool ok = true; int nOps = 0;
  auto tensor = [&](std::vector<uint32_t> d) {
    g_dims.push_back(std::move(d));
    ANeuralNetworksOperandType t{ANEURALNETWORKS_TENSOR_FLOAT32, uint32_t(g_dims.back().size()), g_dims.back().data(), 0.f, 0};
    ok &= ANeuralNetworksModel_addOperand(m, &t) == ANEURALNETWORKS_NO_ERROR; return nop++;
  };
  auto scalar = [&](int32_t v) {
    ANeuralNetworksOperandType t{ANEURALNETWORKS_INT32, 0, nullptr, 0.f, 0};
    ok &= ANeuralNetworksModel_addOperand(m, &t) == ANEURALNETWORKS_NO_ERROR;
    ok &= ANeuralNetworksModel_setOperandValue(m, nop, &v, 4) == ANEURALNETWORKS_NO_ERROR; return nop++;
  };
  const uint32_t uH = uint32_t(H), uW = uint32_t(W);
  auto conv = [&](uint32_t x, const float* w, const float* b, uint32_t ci, uint32_t co, uint32_t k, bool relu) {
    uint32_t fw = tensor({co, k, k, ci}); ok &= ANeuralNetworksModel_setOperandValue(m, fw, w, size_t(co) * k * k * ci * 4) == 0;
    uint32_t fb = tensor({co}); ok &= ANeuralNetworksModel_setOperandValue(m, fb, b, size_t(co) * 4) == 0;
    uint32_t in[7] = {x, fw, fb, scalar(ANEURALNETWORKS_PADDING_SAME), scalar(1), scalar(1),
                      scalar(relu ? ANEURALNETWORKS_FUSED_RELU : ANEURALNETWORKS_FUSED_NONE)};
    uint32_t y = tensor({1, uH, uW, co});
    ok &= ANeuralNetworksModel_addOperation(m, ANEURALNETWORKS_CONV_2D, 7, in, 1, &y) == 0; ++nOps; return y;
  };
  const uint32_t CIN = L[0].cin, COUT = L.back().cout;
  const uint32_t input = tensor({1, uH, uW, CIN});
  uint32_t h = input;
  for (const Layer& l : L) h = conv(h, l.w.data(), l.b.data(), l.cin, l.cout, l.k, l.relu != 0);
  if (g_idw.empty()) {
    g_idw.assign(size_t(COUT) * CIN, 0.f); g_idb.assign(COUT, 0.f);
    for (uint32_t o = 0; o < COUT; ++o) g_idw[size_t(o) * CIN + o] = 1.f;
  }
  uint32_t skip = conv(input, g_idw.data(), g_idb.data(), CIN, COUT, 1, false);
  uint32_t outp = tensor({1, uH, uW, COUT});
  { uint32_t in[3] = {h, skip, scalar(ANEURALNETWORKS_FUSED_NONE)};
    ok &= ANeuralNetworksModel_addOperation(m, ANEURALNETWORKS_ADD, 3, in, 1, &outp) == 0; ++nOps; }
  ok &= ANeuralNetworksModel_identifyInputsAndOutputs(m, 1, &input, 1, &outp) == 0;
  ok &= ANeuralNetworksModel_relaxComputationFloat32toFloat16(m, true) == 0;   // fp16: lossless here (Kodak)
  ok &= ANeuralNetworksModel_finish(m) == 0;
  if (!ok) { ANeuralNetworksModel_free(m); *err = "model build"; return false; }
  // Every operation must run on the chosen accelerator: no partitioning to GPU / CPU.
  std::unique_ptr<bool[]> sup(new bool[size_t(nOps)]);
  if (ANeuralNetworksModel_getSupportedOperationsForDevices(m, &dev, 1, sup.get()) != 0) { ANeuralNetworksModel_free(m); *err = "support query"; return false; }
  for (int i = 0; i < nOps; ++i)
    if (!sup[i]) { ANeuralNetworksModel_free(m); *err = "op " + std::to_string(i) + " not supported on " + dname; return false; }
  ANeuralNetworksCompilation* c = nullptr;
  if (ANeuralNetworksCompilation_createForDevices(m, &dev, 1, &c) != 0 ||
      ANeuralNetworksCompilation_setPreference(c, ANEURALNETWORKS_PREFER_SUSTAINED_SPEED) != 0 ||
      ANeuralNetworksCompilation_finish(c) != 0) {
    if (c) ANeuralNetworksCompilation_free(c);
    ANeuralNetworksModel_free(m); *err = "compile for " + dname; return false;
  }
  out->model = m; out->comp = c; out->dev = dname;
  return true;
}

const Compiled* Get(int H, int W, std::string* err) {
  std::lock_guard<std::mutex> lk(g_mu);
  auto it = g_cache.find({H, W});
  if (it != g_cache.end()) return &it->second;
  Compiled c;
  if (!Build(H, W, &c, err)) return nullptr;
  return &g_cache.emplace(std::make_pair(H, W), c).first->second;
}
#endif

}  // namespace

bool PriorEnabled() {
  static const bool on = std::getenv("LKRL_PRIOR") && std::atoi(std::getenv("LKRL_PRIOR")) != 0;
  return on;
}

bool PriorPrewarm(int H, int W, std::string* err) {
#ifdef __ANDROID__
  return Get(H, W, err) != nullptr;
#else
  (void)H; (void)W;
  if (Layers().empty()) { *err = "prior weights missing"; return false; }
  return true;
#endif
}

bool PriorApply(float* z, int H, int W, float noise_a, float* delta, PriorStats* st, std::string* err) {
  const auto t0 = std::chrono::steady_clock::now();
  const auto& L = Layers();
  if (L.empty()) { *err = "prior weights missing"; return false; }
  std::vector<float> in, out(size_t(H) * W * 3);
  MakeInput(z, H, W, noise_a, &in);
#ifdef __ANDROID__
  const Compiled* c = Get(H, W, err);
  if (!c) { __android_log_print(ANDROID_LOG_WARN, "LkrPrior", "skipped: %s", err->c_str()); return false; }
  // One execution at a time: the MDLA shim failed concurrent executions on one compilation (2 workers),
  // and the NPU runs one job at a time anyway; the other worker keeps the GPU busy meanwhile.
  static std::mutex exec_mu;
  int rc = 0; const char* step = "create";
  {
    std::lock_guard<std::mutex> lk(exec_mu);
    ANeuralNetworksExecution* ex = nullptr;
    rc = ANeuralNetworksExecution_create(c->comp, &ex);
    if (rc == 0) { step = "setInput"; rc = ANeuralNetworksExecution_setInput(ex, 0, nullptr, in.data(), in.size() * 4); }
    if (rc == 0) { step = "setOutput"; rc = ANeuralNetworksExecution_setOutput(ex, 0, nullptr, out.data(), out.size() * 4); }
    if (rc == 0) { step = "compute"; rc = ANeuralNetworksExecution_compute(ex); }
    if (ex) ANeuralNetworksExecution_free(ex);
  }
  if (rc != 0) {
    *err = std::string("execute on ") + c->dev + ": " + step + " = " + std::to_string(rc);
    __android_log_print(ANDROID_LOG_WARN, "LkrPrior", "%s (tile %dx%d)", err->c_str(), W, H); return false;
  }
  if (st) st->device = c->dev;
  __android_log_print(ANDROID_LOG_INFO, "LkrPrior", "tile %dx%d on %s: %.1f ms (incl. host convert)", W, H, c->dev.c_str(),
                      std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
#else
  RunCpu(L, in, H, W, &out);
  if (st) st->device = "cpu-reference";
#endif
  WriteBack(out, H, W, z, delta);
  if (const char* dp = std::getenv("LKRL_PRIOR_DUMP"))   // tests: corrected planar tile
    if (FILE* f = std::fopen(dp, "wb")) { std::fwrite(z, 4, size_t(3) * H * W, f); std::fclose(f); }
  if (st) { st->ran = true; st->ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count(); }
  return true;
}

}  // namespace lkrl
