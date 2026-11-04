// SPDX-License-Identifier: GPL-3.0-or-later
#include "lkr_litert.h"
#include "lkr_parallel.h"

#include <dlfcn.h>

#include <type_traits>

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace lkr {

namespace {

// Minimal LiteRT C ABI (LiteRT 2.3, litert/c/*.h). Handles are opaque
// pointers; LiteRtRankedTensorType is only passed back to LiteRT, so it is
// opaque storage of its documented size (static_assert == 72 in the SDK).
using St = int;                                    // LiteRtStatus, kLiteRtStatusOk = 0
using H = void*;
struct Any {                                       // LiteRtAny
  int type;                                        // kLiteRtAnyTypeInt 2, String 8
  union { bool b; int64_t i; double r; const char* s; const void* p; };
};
static_assert(sizeof(Any) == 16, "LiteRtAny ABI");
struct EnvOption { int tag; Any value; };          // LiteRtEnvOption
struct alignas(8) RankedType { unsigned char bytes[72]; };
constexpr int kTagPluginDir = 0, kTagDispatchDir = 1, kTagAutoRegister = 24;
constexpr int kAnyInt = 2, kAnyString = 8;
constexpr int kHwNpu = 1 << 2;
constexpr int kLockRead = 0, kLockWrite = 1;

struct Api {
  void* lib = nullptr;
  St (*CreateEnvironment)(int, const EnvOption*, H*);
  void (*DestroyEnvironment)(H);
  St (*CreateModelFromFile)(H, const char*, H*);
  void (*DestroyModel)(H);
  St (*CreateOptions)(H*);
  void (*DestroyOptions)(H);
  St (*SetOptionsHardwareAccelerators)(H, int);
  St (*CreateCompiledModel)(H, H, H, H*);
  void (*DestroyCompiledModel)(H);
  St (*IsNonCpuFullyAccelerated)(H, bool*);
  St (*GetModelSignature)(H, size_t, H*);
  St (*GetSignatureInputTensorByIndex)(H, size_t, H*);
  St (*GetSignatureOutputTensorByIndex)(H, size_t, H*);
  St (*GetRankedTensorType)(H, RankedType*);
  St (*GetInputReq)(H, size_t, size_t, H*);
  St (*GetOutputReq)(H, size_t, size_t, H*);
  St (*CreateManagedTensorBufferFromRequirements)(H, const RankedType*, H, H*);
  void (*DestroyTensorBuffer)(H);
  St (*Lock)(H, void**, int);
  St (*Unlock)(H);
  St (*Run)(H, size_t, size_t, H*, size_t, H*);

  bool load(const std::string& dir, std::string* err) {
    lib = dlopen((dir + "/libLiteRt.so").c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!lib) lib = dlopen("libLiteRt.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) { if (err) *err = std::string("dlopen libLiteRt.so: ") + dlerror(); return false; }
    bool ok = true;
    auto sym = [&](auto& fn, const char* name) {
      fn = reinterpret_cast<std::remove_reference_t<decltype(fn)>>(dlsym(lib, name));
      if (!fn) { ok = false; if (err) *err = std::string("missing ") + name; }
    };
    sym(CreateEnvironment, "LiteRtCreateEnvironment");
    sym(DestroyEnvironment, "LiteRtDestroyEnvironment");
    sym(CreateModelFromFile, "LiteRtCreateModelFromFile");
    sym(DestroyModel, "LiteRtDestroyModel");
    sym(CreateOptions, "LiteRtCreateOptions");
    sym(DestroyOptions, "LiteRtDestroyOptions");
    sym(SetOptionsHardwareAccelerators, "LiteRtSetOptionsHardwareAccelerators");
    sym(CreateCompiledModel, "LiteRtCreateCompiledModel");
    sym(DestroyCompiledModel, "LiteRtDestroyCompiledModel");
    sym(IsNonCpuFullyAccelerated, "LiteRtCompiledModelIsNonCpuFullyAccelerated");
    sym(GetModelSignature, "LiteRtGetModelSignature");
    sym(GetSignatureInputTensorByIndex, "LiteRtGetSignatureInputTensorByIndex");
    sym(GetSignatureOutputTensorByIndex, "LiteRtGetSignatureOutputTensorByIndex");
    sym(GetRankedTensorType, "LiteRtGetRankedTensorType");
    sym(GetInputReq, "LiteRtGetCompiledModelInputBufferRequirements");
    sym(GetOutputReq, "LiteRtGetCompiledModelOutputBufferRequirements");
    sym(CreateManagedTensorBufferFromRequirements, "LiteRtCreateManagedTensorBufferFromRequirements");
    sym(DestroyTensorBuffer, "LiteRtDestroyTensorBuffer");
    sym(Lock, "LiteRtLockTensorBuffer");
    sym(Unlock, "LiteRtUnlockTensorBuffer");
    sym(Run, "LiteRtRunCompiledModel");
    return ok;
  }
};

}  // namespace

struct LiteRtCore::Impl {
  Api api;
  H env = nullptr, model = nullptr, opts = nullptr, cm = nullptr, ib = nullptr, ob = nullptr;
  int H2 = 0, W2 = 0;
  ~Impl() {
    if (!api.lib) return;
    if (ib) api.DestroyTensorBuffer(ib);
    if (ob) api.DestroyTensorBuffer(ob);
    if (cm) api.DestroyCompiledModel(cm);
    if (opts) api.DestroyOptions(opts);
    if (model) api.DestroyModel(model);
    if (env) api.DestroyEnvironment(env);
    // libLiteRt stays loaded (vendor runtimes do not support unload reliably).
  }
};

LiteRtCore::LiteRtCore() : d_(new Impl) {}
LiteRtCore::~LiteRtCore() = default;
int LiteRtCore::h2() const { return d_->H2; }
int LiteRtCore::w2() const { return d_->W2; }

bool LiteRtCore::open(const std::string& lib_dir, const std::string& model, int H2, int W2, std::string* err) {
  Impl& d = *d_;
  if (!d.api.load(lib_dir, err)) return false;
  EnvOption o[3] = {};
  o[0].tag = kTagPluginDir;
  o[0].value.type = kAnyString;
  o[0].value.s = lib_dir.c_str();
  o[1] = o[0];
  o[1].tag = kTagDispatchDir;
  o[2].tag = kTagAutoRegister;
  o[2].value.type = kAnyInt;
  o[2].value.i = kHwNpu;
  auto ck = [&](St s, const char* what) {
    if (s != 0 && err) *err = std::string(what) + " status " + std::to_string(s);
    return s == 0;
  };
  if (!ck(d.api.CreateEnvironment(3, o, &d.env), "environment")) return false;
  if (!ck(d.api.CreateModelFromFile(d.env, model.c_str(), &d.model), "model")) return false;
  if (!ck(d.api.CreateOptions(&d.opts), "options")) return false;
  if (!ck(d.api.SetOptionsHardwareAccelerators(d.opts, kHwNpu), "accelerator")) return false;
  if (!ck(d.api.CreateCompiledModel(d.env, d.model, d.opts, &d.cm), "compile")) return false;
  bool full = false;
  if (!ck(d.api.IsNonCpuFullyAccelerated(d.cm, &full), "delegation") || !full) {
    if (err && full == false) *err = "model not fully NPU-accelerated";
    return false;
  }
  H sig, it, ot, ir, orq;
  RankedType ity, oty;
  if (!ck(d.api.GetModelSignature(d.model, 0, &sig), "signature")) return false;
  if (!ck(d.api.GetSignatureInputTensorByIndex(sig, 0, &it), "input")) return false;
  if (!ck(d.api.GetSignatureOutputTensorByIndex(sig, 0, &ot), "output")) return false;
  if (!ck(d.api.GetRankedTensorType(it, &ity), "input type")) return false;
  if (!ck(d.api.GetRankedTensorType(ot, &oty), "output type")) return false;
  if (!ck(d.api.GetInputReq(d.cm, 0, 0, &ir), "input req")) return false;
  if (!ck(d.api.GetOutputReq(d.cm, 0, 0, &orq), "output req")) return false;
  if (!ck(d.api.CreateManagedTensorBufferFromRequirements(d.env, &ity, ir, &d.ib), "input buffer")) return false;
  if (!ck(d.api.CreateManagedTensorBufferFromRequirements(d.env, &oty, orq, &d.ob), "output buffer")) return false;
  d.H2 = H2;
  d.W2 = W2;
  return true;
}

float* LiteRtCore::lockInput() {
  void* p = nullptr;
  return d_->api.Lock(d_->ib, &p, kLockWrite) == 0 ? static_cast<float*>(p) : nullptr;
}
bool LiteRtCore::unlockAndRun() {
  d_->api.Unlock(d_->ib);
  return d_->api.Run(d_->cm, 0, 1, &d_->ib, 1, &d_->ob) == 0;
}
const float* LiteRtCore::lockOutput() {
  void* p = nullptr;
  return d_->api.Lock(d_->ob, &p, kLockRead) == 0 ? static_cast<const float*>(p) : nullptr;
}
void LiteRtCore::unlockOutput() { d_->api.Unlock(d_->ob); }

bool LiteRtCore::run(const float* in, float* out) {
  Impl& d = *d_;
  const size_t n = size_t(d.H2) * d.W2;
  void* p = nullptr;
  if (d.api.Lock(d.ib, &p, kLockWrite) != 0) return false;
  float* q = static_cast<float*>(p);                     // CHW -> NHWC
  ParallelFor(d.H2, [&](int r0, int r1) {
    for (size_t i = size_t(r0) * d.W2; i < size_t(r1) * d.W2; ++i)
      for (int c = 0; c < 13; ++c) q[i * 13 + c] = in[size_t(c) * n + i];
  });
  d.api.Unlock(d.ib);
  if (d.api.Run(d.cm, 0, 1, &d.ib, 1, &d.ob) != 0) return false;
  if (d.api.Lock(d.ob, &p, kLockRead) != 0) return false;
  const float* r = static_cast<const float*>(p);         // NHWC -> CHW
  ParallelFor(d.H2, [&](int r0, int r1) {
    for (size_t i = size_t(r0) * d.W2; i < size_t(r1) * d.W2; ++i)
      for (int c = 0; c < 12; ++c) out[size_t(c) * n + i] = r[i * 12 + c];
  });
  d.api.Unlock(d.ob);
  return true;
}

}  // namespace lkr
