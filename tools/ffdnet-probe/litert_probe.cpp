#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_environment.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_options.h"
#include "litert/c/litert_tensor_buffer.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>
using Clock = std::chrono::steady_clock;
void ck(LiteRtStatus r, const char *s) {
  if (r != kLiteRtStatusOk) {
    fprintf(stderr, "FAIL %s status=%d\n", s, (int)r);
    exit(2);
  }
}
double elapsed(Clock::time_point t) {
  return std::chrono::duration<double, std::milli>(Clock::now() - t).count();
}
std::vector<float> read(const std::string &p, size_t size) {
  std::vector<float> v(size);
  FILE *f = fopen(p.c_str(), "rb");
  if (!f || fread(v.data(), 4, size, f) != size) {
    fprintf(stderr, "bad tensor %s\n", p.c_str());
    exit(3);
  }
  fclose(f);
  return v;
}
int main(int argc, char **argv) {
  setbuf(stdout, nullptr);
  if (argc < 5) {
    fprintf(stderr, "usage: probe directory tile runs cpu|npu\n");
    return 1;
  }
  std::string dir = argv[1], backend = argv[4];
  int tile = atoi(argv[2]), runs = atoi(argv[3]), n = tile / 2;
  if (tile < 2 || tile % 2 || runs < 1 ||
      (backend != "cpu" && backend != "npu"))
    return 1;
  int hw = backend == "npu" ? kLiteRtHwAcceleratorNpu : kLiteRtHwAcceleratorCpu;
  LiteRtEnvOption envopts[3] = {};
  envopts[0].tag = kLiteRtEnvOptionTagCompilerPluginLibraryDir;
  envopts[0].value.type = kLiteRtAnyTypeString;
  envopts[0].value.str_value = dir.c_str();
  envopts[1] = envopts[0];
  envopts[1].tag = kLiteRtEnvOptionTagDispatchLibraryDir;
  envopts[2].tag = kLiteRtEnvOptionTagAutoRegisterAccelerators;
  envopts[2].value.type = kLiteRtAnyTypeInt;
  envopts[2].value.int_value = hw;
  LiteRtEnvironment env = nullptr;
  ck(LiteRtCreateEnvironment(3, envopts, &env), "environment");
  LiteRtModel model = nullptr;
  ck(LiteRtCreateModelFromFile(
         env,
         (dir + "/ffdnet-color-" + std::to_string(tile) + ".tflite").c_str(),
         &model),
     "load model");
  LiteRtOptions opts = nullptr;
  ck(LiteRtCreateOptions(&opts), "options");
  ck(LiteRtSetOptionsHardwareAccelerators(opts, hw), "select accelerator");
  auto t = Clock::now();
  LiteRtCompiledModel compiled = nullptr;
  ck(LiteRtCreateCompiledModel(env, model, opts, &compiled), "compile");
  double setup = elapsed(t);
  bool full = false;
  ck(LiteRtCompiledModelIsNonCpuFullyAccelerated(compiled, &full),
     "verify non-CPU delegation");
  printf("DELEGATION requested=%s non_cpu_fully_accelerated=%d setup_ms=%.3f\n",
         backend.c_str(), full, setup);
  if (backend == "npu" && !full) {
    fprintf(
        stderr,
        "REJECTED: NPU requested but model is not fully non-CPU accelerated\n");
    return 4;
  }
  LiteRtSignature sig;
  ck(LiteRtGetModelSignature(model, 0, &sig), "signature");
  LiteRtTensor it, ot;
  ck(LiteRtGetSignatureInputTensorByIndex(sig, 0, &it), "input tensor");
  ck(LiteRtGetSignatureOutputTensorByIndex(sig, 0, &ot), "output tensor");
  LiteRtRankedTensorType ity, oty;
  ck(LiteRtGetRankedTensorType(it, &ity), "input type");
  ck(LiteRtGetRankedTensorType(ot, &oty), "output type");
  LiteRtTensorBufferRequirements ir, orr;
  ck(LiteRtGetCompiledModelInputBufferRequirements(compiled, 0, 0, &ir),
     "input req");
  ck(LiteRtGetCompiledModelOutputBufferRequirements(compiled, 0, 0, &orr),
     "output req");
  LiteRtTensorBuffer ib, ob;
  ck(LiteRtCreateManagedTensorBufferFromRequirements(env, &ity, ir, &ib),
     "input buffer");
  ck(LiteRtCreateManagedTensorBufferFromRequirements(env, &oty, orr, &ob),
     "output buffer");
  auto x = read(dir + "/input-" + std::to_string(tile) + ".bin", n * n * 13);
  auto ref =
      read(dir + "/expected-" + std::to_string(tile) + ".bin", n * n * 12);
  std::vector<float> y(ref.size());
  std::vector<double> samples;
  for (int k = -2; k < runs; k++) {
    t = Clock::now();
    void *p = nullptr;
    ck(LiteRtLockTensorBuffer(ib, &p, kLiteRtTensorBufferLockModeWrite),
       "input lock");
    memcpy(p, x.data(), x.size() * 4);
    ck(LiteRtUnlockTensorBuffer(ib), "input unlock");
    ck(LiteRtRunCompiledModel(compiled, 0, 1, &ib, 1, &ob), "run");
    ck(LiteRtLockTensorBuffer(ob, &p, kLiteRtTensorBufferLockModeRead),
       "output lock");
    memcpy(y.data(), p, y.size() * 4);
    ck(LiteRtUnlockTensorBuffer(ob), "output unlock");
    double time = elapsed(t);
    if (k >= 0)
      samples.push_back(time);
    printf("sample backend=litert-%s tile=%d iteration=%d ms=%.3f\n",
           backend.c_str(), tile, k, time);
  }
  double maxerr = 0, sq = 0;
  for (size_t i = 0; i < y.size(); i++) {
    if (!std::isfinite(y[i]))
      return 5;
    double e = y[i] - ref[i];
    maxerr = std::max(maxerr, fabs(e));
    sq += e * e;
  }
  std::sort(samples.begin(), samples.end());
  printf("RESULT backend=litert-%s tile=%d runs=%d median_ms=%.3f min_ms=%.3f "
         "max_ms=%.3f setup_ms=%.3f max_error=%g rmse=%g fully_non_cpu=%d\n",
         backend.c_str(), tile, runs, samples[samples.size() / 2],
         samples.front(), samples.back(), setup, maxerr, sqrt(sq / y.size()),
         full);
  FILE *f = fopen(
      (dir + "/output-" + std::to_string(tile) + "-litert-" + backend + ".bin")
          .c_str(),
      "wb");
  if (!f || fwrite(y.data(), 4, y.size(), f) != y.size())
    return 6;
  fclose(f);
  std::ifstream maps("/proc/self/maps");
  std::string line;
  while (std::getline(maps, line))
    if (line.find("LiteRt") != std::string::npos ||
        line.find("neuron") != std::string::npos)
      printf("MAP %s\n", line.c_str());
  LiteRtDestroyTensorBuffer(ib);
  LiteRtDestroyTensorBuffer(ob);
  LiteRtDestroyCompiledModel(compiled);
  LiteRtDestroyOptions(opts);
  LiteRtDestroyModel(model);
  LiteRtDestroyEnvironment(env);
}
