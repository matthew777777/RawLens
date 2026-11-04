#include <algorithm>
#include <android/NeuralNetworks.h>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <gpu.h>
#include <net.h>
#include <random>
#include <string>
#include <vector>
using Clock = std::chrono::steady_clock;
double ms(Clock::time_point t) {
  return std::chrono::duration<double, std::milli>(Clock::now() - t).count();
}
struct Layer {
  int ci, co;
  std::vector<float> w, b;
};
void check(int r, const char *s) {
  if (r) {
    fprintf(stderr, "FAIL %s rc=%d\n", s, r);
    exit(2);
  }
}
void stats(const char *backend, int tile, std::vector<double> ts,
           const std::vector<float> &y, const std::vector<float> &ref,
           double compile) {
  std::sort(ts.begin(), ts.end());
  double err = 0, sq = 0;
  for (size_t i = 0; i < y.size(); i++) {
    if (!std::isfinite(y[i])) {
      fprintf(stderr, "nonfinite\n");
      exit(3);
    }
    if (!ref.empty()) {
      double e = y[i] - ref[i];
      err = std::max(err, fabs(e));
      sq += e * e;
    }
  }
  printf("RESULT backend=%s tile=%d core=%d runs=%zu median_ms=%.3f "
         "min_ms=%.3f max_ms=%.3f setup_ms=%.3f max_error=%g rmse=%g\n",
         backend, tile, tile / 2, ts.size(), ts[ts.size() / 2], ts.front(),
         ts.back(), compile, err, sqrt(sq / y.size()));
  fflush(stdout);
}
int main(int argc, char **argv) {
  setbuf(stdout, nullptr);
  int tile = argc > 1 ? atoi(argv[1]) : 256;
  int runs = argc > 2 ? atoi(argv[2]) : 5;
  std::string backend = argc > 3 ? argv[3] : "cpu";
  if (tile < 2 || tile % 2 || runs < 1)
    return 6;
  const bool pretrained = argc > 4;
  int n = tile / 2;
  std::mt19937 rng(20261104);
  std::vector<Layer> ls;
  size_t params = 0;
  for (int l = 0; l < 15; l++) {
    Layer a;
    a.ci = l == 0 ? 5 : 64;
    a.co = l == 14 ? 4 : 64;
    a.w.resize(a.ci * a.co * 9);
    a.b.resize(a.co);
    std::uniform_real_distribution<float> u(-sqrt(6.f / (a.ci * 9)),
                                            sqrt(6.f / (a.ci * 9)));
    for (auto &v : a.w)
      v = u(rng);
    ls.push_back(a);
    params += a.w.size() + a.b.size();
  }
  if (pretrained) {
    FILE *f = fopen(argv[4], "rb");
    if (!f)
      return 7;
    uint32_t depth = 0;
    if (fread(&depth, 4, 1, f) != 1 || depth != 12)
      return 8;
    ls.clear();
    params = 0;
    for (uint32_t i = 0; i < depth; i++) {
      Layer a;
      uint32_t dims[2];
      if (fread(dims, 4, 2, f) != 2)
        return 9;
      a.ci = dims[0];
      a.co = dims[1];
      if (a.ci != (i == 0 ? 13 : 96) || a.co != (i == depth - 1 ? 12 : 96))
        return 10;
      a.w.resize(a.ci * a.co * 9);
      a.b.resize(a.co);
      if (fread(a.w.data(), 4, a.w.size(), f) != a.w.size() ||
          fread(a.b.data(), 4, a.b.size(), f) != a.b.size())
        return 11;
      params += a.w.size() + a.b.size();
      ls.push_back(std::move(a));
    }
    if (fgetc(f) != EOF)
      return 12;
    fclose(f);
  }
  int depth = ls.size(), cin = ls.front().ci, cout = ls.back().co;
  printf("MODEL kind=%s layers=%d hidden=%d packed_input=%d output=%d "
         "params=%zu fp32_bytes=%zu tile=%d\n",
         pretrained ? "pretrained-color-FFDNet" : "synthetic-gray", depth,
         ls.front().co, cin, cout, params, params * 4, tile);
  std::vector<float> x(n * n * cin), y(n * n * cout), ref;
  for (auto &v : x)
    v = (rng() % 10000) / 10000.f;
  for (int p = 0; p < n * n; p++)
    x[p * cin + cin - 1] = 0.05f;
  std::string base = "/data/local/tmp/ffdnet-probe/";
  if (pretrained) {
    base += "trained-";
    FILE *f =
        fopen((base + "input-" + std::to_string(tile) + ".bin").c_str(), "rb");
    if (!f || fread(x.data(), 4, x.size(), f) != x.size())
      return 13;
    fclose(f);
  }
  std::string refpath = base + "ref-" + std::to_string(tile) + ".bin";
  if (backend != "cpu") {
    FILE *f = fopen(refpath.c_str(), "rb");
    if (!f)
      return 14;
    if (f) {
      ref.resize(y.size());
      if (fread(ref.data(), 4, ref.size(), f) != ref.size())
        ref.clear();
      fclose(f);
    }
  }
  if (backend == "cpu" || backend == "cpu16" || backend == "gpu") {
    auto start = Clock::now();
    ncnn::Net net;
    net.opt.num_threads = 4;
    net.opt.use_vulkan_compute = backend == "gpu";
    net.opt.use_fp16_storage = backend != "cpu";
    net.opt.use_fp16_arithmetic = backend != "cpu";
    net.opt.use_fp16_packed = backend != "cpu";
    net.opt.use_subgroup_ops = false;
    std::string p = "7767517\n" + std::to_string(depth + 1) + " " +
                    std::to_string(depth + 1) + "\nInput in 0 1 b0\n";
    std::vector<unsigned char> bin;
    auto append = [&](const void *v, size_t z) {
      auto b = (const unsigned char *)v;
      bin.insert(bin.end(), b, b + z);
    };
    for (int l = 0; l < depth; l++) {
      auto &a = ls[l];
      p += "Convolution c" + std::to_string(l) + " 1 1 b" + std::to_string(l) +
           " b" + std::to_string(l + 1) + " 0=" + std::to_string(a.co) +
           " 1=3 3=1 4=1 5=1 6=" + std::to_string(a.w.size()) +
           " 9=" + std::to_string(l == depth - 1 ? 0 : 1) + "\n";
      int tag = 0;
      append(&tag, 4);
      append(a.w.data(), a.w.size() * 4);
      append(a.b.data(), a.b.size() * 4);
    }
    check(net.load_param_mem(p.c_str()), "ncnn param");
    net.load_model(bin.data());
    double setup = ms(start);
    if (backend == "gpu")
      printf("GPU count=%d name=%s\n", ncnn::get_gpu_count(),
             ncnn::get_gpu_info(0).device_name());
    ncnn::Mat in(n, n, cin);
    for (int c = 0; c < cin; c++)
      for (int j = 0; j < n * n; j++)
        ((float *)in.channel(c))[j] = x[j * cin + c];
    std::vector<double> ts;
    for (int k = -2; k < runs; k++) {
      start = Clock::now();
      auto ex = net.create_extractor();
      check(ex.input("b0", in), "ncnn input");
      ncnn::Mat out;
      check(ex.extract(("b" + std::to_string(depth)).c_str(), out),
            "ncnn extract");
      double t = ms(start);
      if (k >= 0)
        ts.push_back(t);
      for (int c = 0; c < cout; c++)
        for (int j = 0; j < n * n; j++)
          y[j * cout + c] = ((float *)out.channel(c))[j];
      printf("sample backend=%s tile=%d iteration=%d ms=%.3f\n",
             backend.c_str(), tile, k, t);
    }
    if (backend == "cpu") {
      FILE *f = fopen(refpath.c_str(), "wb");
      if (f) {
        fwrite(y.data(), 4, y.size(), f);
        fclose(f);
      }
    }
    stats(backend.c_str(), tile, ts, y, ref, setup);
    if (pretrained) {
      FILE *f = fopen(
          (base + "output-" + std::to_string(tile) + "-" + backend + ".bin")
              .c_str(),
          "wb");
      if (!f || fwrite(y.data(), 4, y.size(), f) != y.size())
        return 15;
      fclose(f);
    }
  } else {
    uint32_t dc = 0;
    check(ANeuralNetworks_getDeviceCount(&dc), "device count");
    ANeuralNetworksDevice *dev = nullptr;
    for (uint32_t i = 0; i < dc; i++) {
      ANeuralNetworksDevice *d;
      const char *name;
      ANeuralNetworks_getDevice(i, &d);
      ANeuralNetworksDevice_getName(d, &name);
      printf("NNAPI_DEVICE %s\n", name);
      if (backend == name)
        dev = d;
    }
    if (!dev)
      return 4;
    const ANeuralNetworksDevice *devices[] = {dev};
    ANeuralNetworksModel *m;
    check(ANeuralNetworksModel_create(&m), "create model");
    std::vector<std::vector<float>> weightStorage;
    weightStorage.reserve(depth);
    uint32_t idx = 0;
    auto operand = [&](int type, std::vector<uint32_t> dims, const void *data,
                       size_t bytes) {
      ANeuralNetworksOperandType t = {type, (uint32_t)dims.size(), dims.data(),
                                      0, 0};
      uint32_t id = idx++;
      check(ANeuralNetworksModel_addOperand(m, &t), "operand");
      if (data)
        check(ANeuralNetworksModel_setOperandValue(m, id, data, bytes),
              "constant");
      return id;
    };
    uint32_t input = operand(ANEURALNETWORKS_TENSOR_FLOAT32,
                             {1, (uint32_t)n, (uint32_t)n, (uint32_t)cin},
                             nullptr, 0),
             prev = input;
    for (int l = 0; l < depth; l++) {
      auto &a = ls[l];
      weightStorage.emplace_back(a.w.size());
      auto &ohwi = weightStorage.back();
      for (int o = 0; o < a.co; o++)
        for (int i = 0; i < a.ci; i++)
          for (int k = 0; k < 9; k++)
            ohwi[(o * 9 + k) * a.ci + i] = a.w[(o * a.ci + i) * 9 + k];
      uint32_t w = operand(ANEURALNETWORKS_TENSOR_FLOAT32,
                           {(uint32_t)a.co, 3, 3, (uint32_t)a.ci}, ohwi.data(),
                           ohwi.size() * 4),
               b = operand(ANEURALNETWORKS_TENSOR_FLOAT32, {(uint32_t)a.co},
                           a.b.data(), a.b.size() * 4);
      int32_t pad = 1, stride = 1, act = l == depth - 1 ? 0 : 1;
      uint32_t pd = operand(ANEURALNETWORKS_INT32, {}, &pad, 4),
               sx = operand(ANEURALNETWORKS_INT32, {}, &stride, 4),
               sy = operand(ANEURALNETWORKS_INT32, {}, &stride, 4),
               ac = operand(ANEURALNETWORKS_INT32, {}, &act, 4);
      uint32_t out =
          operand(ANEURALNETWORKS_TENSOR_FLOAT32,
                  {1, (uint32_t)n, (uint32_t)n, (uint32_t)a.co}, nullptr, 0);
      uint32_t ins[] = {prev, w, b, pd, sx, sy, ac};
      check(ANeuralNetworksModel_addOperation(m, ANEURALNETWORKS_CONV_2D, 7,
                                              ins, 1, &out),
            "conv");
      prev = out;
    }
    check(ANeuralNetworksModel_identifyInputsAndOutputs(m, 1, &input, 1, &prev),
          "io");
    check(ANeuralNetworksModel_relaxComputationFloat32toFloat16(m, true),
          "relax");
    check(ANeuralNetworksModel_finish(m), "finish model");
    bool supported[15] = {};
    check(ANeuralNetworksModel_getSupportedOperationsForDevices(m, devices, 1,
                                                                supported),
          "support");
    int sc = 0;
    for (int i = 0; i < depth; i++)
      sc += supported[i];
    printf("SUPPORTED %d/%d CPU fallback excluded by createForDevices\n", sc,
           depth);
    if (sc != depth)
      return 5;
    ANeuralNetworksCompilation *c;
    auto start = Clock::now();
    check(ANeuralNetworksCompilation_createForDevices(m, devices, 1, &c),
          "compilation");
    check(ANeuralNetworksCompilation_setPreference(
              c, ANEURALNETWORKS_PREFER_SUSTAINED_SPEED),
          "preference");
    check(ANeuralNetworksCompilation_finish(c), "compile finish");
    double setup = ms(start);
    std::vector<double> ts;
    for (int k = -2; k < runs; k++) {
      ANeuralNetworksExecution *e;
      check(ANeuralNetworksExecution_create(c, &e), "exec create");
      check(ANeuralNetworksExecution_setInput(e, 0, nullptr, x.data(),
                                              x.size() * 4),
            "input");
      check(ANeuralNetworksExecution_setOutput(e, 0, nullptr, y.data(),
                                               y.size() * 4),
            "output");
      ANeuralNetworksExecution_setMeasureTiming(e, true);
      start = Clock::now();
      check(ANeuralNetworksExecution_compute(e), "compute");
      double t = ms(start);
      uint64_t hw = 0;
      ANeuralNetworksExecution_getDuration(
          e, ANEURALNETWORKS_DURATION_ON_HARDWARE, &hw);
      printf(
          "sample backend=%s tile=%d iteration=%d ms=%.3f hardware_ns=%llu\n",
          backend.c_str(), tile, k, t, (unsigned long long)hw);
      if (k >= 0)
        ts.push_back(t);
      ANeuralNetworksExecution_free(e);
    }
    stats(backend.c_str(), tile, ts, y, ref, setup);
    if (pretrained) {
      FILE *f = fopen(
          (base + "output-" + std::to_string(tile) + "-" + backend + ".bin")
              .c_str(),
          "wb");
      if (!f || fwrite(y.data(), 4, y.size(), f) != y.size())
        return 15;
      fclose(f);
    }
    ANeuralNetworksCompilation_free(c);
    ANeuralNetworksModel_free(m);
  }
}
