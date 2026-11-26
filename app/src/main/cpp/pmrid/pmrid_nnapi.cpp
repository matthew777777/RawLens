// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
#include "pmrid_nnapi.h"

#include <android/NeuralNetworks.h>
#include <android/log.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <future>
#include <thread>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

constexpr int kTile = 576;     // graph input side (packed planes): 512 kept + 32 overlap each side
constexpr int kOverlap = 32;

struct Tq { float scale; int32_t zp; };
struct Op {
    uint8_t kind, stride, padHead, padTail;  // kind 0 conv, 1 depthwise, 2 transposed conv
    uint32_t dims[4];
    std::vector<float> scales;
    std::vector<int8_t> w;
    std::vector<int32_t> bias;
    uint32_t channels() const { return kind == 1 ? dims[3] : dims[0]; }
};

struct Blob {
    float gain{};
    uint32_t c0{};
    std::map<std::string, Tq> tensors;
    std::map<std::string, Op> ops;
    std::vector<float> sbBias, sbW4;
};

Blob parse(const uint8_t* p, size_t n) {
    size_t o = 0;
    auto need = [&](size_t k) { if (o + k > n) throw std::runtime_error("pmrid.bin truncated"); };
    auto rd = [&](void* dst, size_t k) { need(k); std::memcpy(dst, p + o, k); o += k; };
    auto u32 = [&] { uint32_t v; rd(&v, 4); return v; };
    auto name = [&] { uint16_t k; rd(&k, 2); need(k); std::string s(reinterpret_cast<const char*>(p + o), k); o += k; return s; };
    need(4);
    if (std::memcmp(p, "PMQ1", 4) != 0) throw std::runtime_error("not a pmrid.bin");
    o = 4;
    Blob b;
    if (u32() != 1) throw std::runtime_error("pmrid.bin version");
    rd(&b.gain, 4);
    b.c0 = u32();
    const uint32_t nt = u32(), no = u32();
    for (uint32_t i = 0; i < nt; i++) {
        std::string k = name();
        Tq t{};
        rd(&t.scale, 4);
        rd(&t.zp, 4);
        b.tensors[k] = t;
    }
    for (uint32_t i = 0; i < no; i++) {
        std::string k = name();
        Op op{};
        rd(&op.kind, 1); rd(&op.stride, 1); rd(&op.padHead, 1); rd(&op.padTail, 1);
        rd(op.dims, 16);
        const size_t c = op.channels(), count = size_t(op.dims[0]) * op.dims[1] * op.dims[2] * op.dims[3];
        op.scales.resize(c); rd(op.scales.data(), 4 * c);
        op.w.resize(count); rd(op.w.data(), count);
        op.bias.resize(c); rd(op.bias.data(), 4 * c);
        b.ops[k] = std::move(op);
    }
    b.sbBias.resize(b.c0); rd(b.sbBias.data(), 4 * b.c0);
    b.sbW4.resize(b.c0); rd(b.sbW4.data(), 4 * b.c0);
    return b;
}

// Dedicated accelerators by name (MediaTek MDLA, Qualcomm HTP/DSP/HTA), then any accelerator that is not a GPU / CPU.
const ANeuralNetworksDevice* pickDevice(std::string* name) {
    uint32_t nd = 0;
    if (ANeuralNetworks_getDeviceCount(&nd) != ANEURALNETWORKS_NO_ERROR) return nullptr;
    std::vector<std::pair<ANeuralNetworksDevice*, std::string>> devs;
    for (uint32_t i = 0; i < nd; ++i) {
        ANeuralNetworksDevice* d = nullptr;
        if (ANeuralNetworks_getDevice(i, &d) != ANEURALNETWORKS_NO_ERROR) continue;
        const char* n = nullptr;
        ANeuralNetworksDevice_getName(d, &n);
        if (n) devs.emplace_back(d, n);
    }
    for (const char* part : {"mdla", "qti-htp", "qti-dsp", "qti-hta"})
        for (auto& [d, n] : devs)
            if (std::strstr(n.c_str(), part)) { *name = n; return d; }
    for (auto& [d, n] : devs) {
        int32_t type = 0;
        ANeuralNetworksDevice_getType(d, &type);
        if (type == ANEURALNETWORKS_DEVICE_ACCELERATOR && !std::strstr(n.c_str(), "gpu") && !std::strstr(n.c_str(), "cpu") &&
            !std::strstr(n.c_str(), "reference")) { *name = n; return d; }
    }
    return nullptr;
}

// Builds qnet.QNet.body as NNAPI operations (same order and names as tools/raw-denoise/pmrid/blob_ref.py).
struct Builder {
    const Blob& b;
    ANeuralNetworksModel* m;
    uint32_t next = 0, nOps = 0;
    std::map<std::string, uint32_t> id;
    std::map<std::string, std::array<uint32_t, 4>> shape;
    std::vector<std::unique_ptr<std::vector<uint8_t>>> keep;  // constant data NNAPI may reference until the model is freed

    void ck(int rc, const char* what) { if (rc != ANEURALNETWORKS_NO_ERROR) throw std::runtime_error(std::string(what) + " = " + std::to_string(rc)); }
    uint32_t scalar(int32_t v) {
        ANeuralNetworksOperandType t{ANEURALNETWORKS_INT32, 0, nullptr, 0.f, 0};
        ck(ANeuralNetworksModel_addOperand(m, &t), "addOperand int");
        ck(ANeuralNetworksModel_setOperandValue(m, next, &v, 4), "setOperandValue int");
        return next++;
    }
    uint32_t boolean(bool v) {
        ANeuralNetworksOperandType t{ANEURALNETWORKS_BOOL, 0, nullptr, 0.f, 0};
        const uint8_t x = v;
        ck(ANeuralNetworksModel_addOperand(m, &t), "addOperand bool");
        ck(ANeuralNetworksModel_setOperandValue(m, next, &x, 1), "setOperandValue bool");
        return next++;
    }
    uint32_t tensor(const std::string& n, std::array<uint32_t, 4> dims) {
        auto t = b.tensors.find(n);
        if (t == b.tensors.end()) throw std::runtime_error("pmrid.bin has no tensor " + n);
        ANeuralNetworksOperandType ty{ANEURALNETWORKS_TENSOR_QUANT8_ASYMM, 4, dims.data(), t->second.scale, t->second.zp};
        ck(ANeuralNetworksModel_addOperand(m, &ty), ("addOperand " + n).c_str());
        id[n] = next;
        shape[n] = dims;
        return next++;
    }
    template <typename T> const void* hold(const std::vector<T>& v) {
        keep.push_back(std::make_unique<std::vector<uint8_t>>(reinterpret_cast<const uint8_t*>(v.data()),
                                                              reinterpret_cast<const uint8_t*>(v.data()) + v.size() * sizeof(T)));
        return keep.back()->data();
    }
    // conv / depthwise / transposed conv `n` reading tensor `src`, output tensor n (fused RELU when relu).
    void op(const std::string& n, const std::string& src, bool relu) {
        auto it = b.ops.find(n);
        if (it == b.ops.end()) throw std::runtime_error("pmrid.bin has no op " + n);
        const Op& o = it->second;
        const auto s = shape.at(src);
        const uint32_t k = o.dims[1];
        std::array<uint32_t, 4> od{1, 0, 0, o.channels()};
        if (o.kind == 2) { od[1] = s[1] * 2; od[2] = s[2] * 2; }
        else { od[1] = (s[1] + o.padHead + o.padTail - k) / o.stride + 1; od[2] = (s[2] + o.padHead + o.padTail - k) / o.stride + 1; }
        ANeuralNetworksOperandType ft{ANEURALNETWORKS_TENSOR_QUANT8_SYMM_PER_CHANNEL, 4, o.dims, 0.f, 0};
        ck(ANeuralNetworksModel_addOperand(m, &ft), "addOperand filter");
        const uint32_t fid = next++;
        ck(ANeuralNetworksModel_setOperandValue(m, fid, hold(o.w), o.w.size()), "filter value");
        ANeuralNetworksSymmPerChannelQuantParams qp{uint32_t(o.kind == 1 ? 3 : 0), uint32_t(o.scales.size()), o.scales.data()};
        ck(ANeuralNetworksModel_setOperandSymmPerChannelQuantParams(m, fid, &qp), "filter scales");
        const uint32_t bd[1] = {o.channels()};
        ANeuralNetworksOperandType bt{ANEURALNETWORKS_TENSOR_INT32, 1, bd, 0.f, 0};
        ck(ANeuralNetworksModel_addOperand(m, &bt), "addOperand bias");
        const uint32_t bid = next++;
        ck(ANeuralNetworksModel_setOperandValue(m, bid, hold(o.bias), o.bias.size() * 4), "bias value");
        const uint32_t out = tensor(n, od);
        std::vector<uint32_t> in{id.at(src), fid, bid, scalar(o.padHead), scalar(o.padTail), scalar(o.padHead), scalar(o.padTail),
                                 scalar(o.stride), scalar(o.stride)};
        int code = ANEURALNETWORKS_CONV_2D;
        if (o.kind == 1) { in.push_back(scalar(1)); code = ANEURALNETWORKS_DEPTHWISE_CONV_2D; }
        in.push_back(scalar(relu ? ANEURALNETWORKS_FUSED_RELU : ANEURALNETWORKS_FUSED_NONE));
        if (o.kind == 2) { in.push_back(boolean(false)); code = ANEURALNETWORKS_TRANSPOSE_CONV_2D; }
        ck(ANeuralNetworksModel_addOperation(m, code, uint32_t(in.size()), in.data(), 1, &out), ("op " + n).c_str());
        nOps++;
    }
    void add(const std::string& a, const std::string& c, const std::string& n, bool relu) {
        auto s = shape.at(a);
        const uint32_t out = tensor(n, s);
        const uint32_t in[3] = {id.at(a), id.at(c), scalar(relu ? ANEURALNETWORKS_FUSED_RELU : ANEURALNETWORKS_FUSED_NONE)};
        ck(ANeuralNetworksModel_addOperation(m, ANEURALNETWORKS_ADD, 3, in, 1, &out), ("add " + n).c_str());
        nOps++;
    }
    std::string sep(const std::string& src, const std::string& n, bool relu) {
        op(n + ".dw", src, false);
        op(n + ".pw", n + ".dw", relu);
        return n + ".pw";
    }
    std::string enc(const std::string& src, const std::string& n) {
        const std::string a = sep(src, n + ".a", true);
        const std::string c = sep(a, n + ".b", false);
        const std::string p = b.ops.count(n + ".p.dw") ? sep(src, n + ".p", false) : src;
        add(c, p, n + ".add", true);
        return n + ".add";
    }
    std::string dec(const std::string& src, const std::string& skip, const std::string& n) {
        op(n + ".up", src, false);
        const std::string s = sep(skip, n + ".sk", false);
        add(n + ".up", s, n + ".add", true);
        return enc(n + ".add", n + ".f");
    }
    void build(int t) {
        const uint32_t in = tensor("in", {1, uint32_t(t), uint32_t(t), 4});
        const uint32_t sb = tensor("inc.sb", {1, 1, 1, b.c0});
        op("inc.conv", "in", false);
        add("inc.conv", "inc.sb", "inc", true);
        std::string x = "inc";
        std::vector<std::string> lv{x};
        const int depth[4] = {2, 2, 2, 3};
        for (int e = 0; e < 4; e++) {
            for (int i = 0; i < depth[e]; i++) x = enc(x, "e" + std::to_string(e + 1) + "." + std::to_string(i));
            lv.push_back(x);
        }
        std::string y = lv[4];
        for (int d = 3; d >= 0; d--) y = dec(y, lv[size_t(d)], "d" + std::to_string(d));
        op("out", y, false);
        const uint32_t ins[2] = {in, sb}, outs[1] = {id.at("out")};
        ck(ANeuralNetworksModel_identifyInputsAndOutputs(m, 2, ins, 1, outs), "identifyInputsAndOutputs");
        ck(ANeuralNetworksModel_finish(m), "model finish");
    }
};

void fail(char* e, int n, const char* what) {
    if (e && n > 0) std::snprintf(e, size_t(n), "%s", what);
    __android_log_print(ANDROID_LOG_WARN, "Pmrid", "%s", what);
}

double gat(double x, double a, double b) { return (2.0 / a) * std::sqrt(std::max(a * x + 0.375 * a * a + b, 0.0)); }
// Exact unbiased inverse (closed-form approximation, Makitalo & Foi) as tools/raw-denoise/pmrid/apply_pmrid.py.
double igat(double z, double a, double b) {
    z = std::max(z, 1.0);
    const double s = std::sqrt(1.5);
    const double i0 = 0.25 * z * z + 0.25 * s / z - 11.0 / 8.0 / (z * z) + 5.0 / 8.0 * s / (z * z * z) - 0.125;
    return a * i0 - b / a;
}
int reflect(int i, int n) {
    if (i < 0) i = -i;
    if (i >= n) i = 2 * n - 2 - i;
    return std::clamp(i, 0, n - 1);
}

}  // namespace

struct PmridContext {
    Blob blob;
    std::unique_ptr<Builder> builder;
    ANeuralNetworksModel* model{};
    ANeuralNetworksCompilation* comp{};
    std::string device;
};

// Compilation cache key: the graph file and the tile (NNAPI also checks the driver itself).
void cacheToken(const uint8_t* p, size_t n, uint8_t out[ANEURALNETWORKS_BYTE_SIZE_OF_CACHE_TOKEN]) {
    for (int k = 0; k < ANEURALNETWORKS_BYTE_SIZE_OF_CACHE_TOKEN / 8; k++) {
        uint64_t h = 1469598103934665603ull ^ (uint64_t(k) * 0x9e3779b97f4a7c15ull) ^ uint64_t(kTile);
        for (size_t i = 0; i < n; i++) { h ^= p[i]; h *= 1099511628211ull; }
        std::memcpy(out + 8 * k, &h, 8);
    }
}

extern "C" PmridContext* pmrid_create(const uint8_t* data, size_t bytes, const char* cacheDir, char* errmsg, int errlen) {
    auto c = std::make_unique<PmridContext>();
    try {
        c->blob = parse(data, bytes);
        const ANeuralNetworksDevice* dev = pickDevice(&c->device);
        if (!dev) throw std::runtime_error("no NPU");
        if (ANeuralNetworksModel_create(&c->model) != ANEURALNETWORKS_NO_ERROR) throw std::runtime_error("model create");
        c->builder = std::make_unique<Builder>(Builder{c->blob, c->model});
        c->builder->build(kTile);
        const uint32_t nops = c->builder->nOps;
        // every operation must run on the NPU: nothing may split to the GPU / CPU drivers
        std::unique_ptr<bool[]> sup(new bool[nops]());
        if (ANeuralNetworksModel_getSupportedOperationsForDevices(c->model, &dev, 1, sup.get()) != ANEURALNETWORKS_NO_ERROR)
            throw std::runtime_error("support query on " + c->device);
        for (uint32_t i = 0; i < nops; i++)
            if (!sup[i]) throw std::runtime_error("op " + std::to_string(i) + " of " + std::to_string(nops) + " not supported on " + c->device);
        if (ANeuralNetworksCompilation_createForDevices(c->model, &dev, 1, &c->comp) != ANEURALNETWORKS_NO_ERROR ||
            ANeuralNetworksCompilation_setPreference(c->comp, ANEURALNETWORKS_PREFER_SUSTAINED_SPEED) != ANEURALNETWORKS_NO_ERROR)
            throw std::runtime_error("compile for " + c->device);
        // The MDLA compile takes minutes; the driver's cache makes every later start a load.
        if (cacheDir && *cacheDir) {
            uint8_t token[ANEURALNETWORKS_BYTE_SIZE_OF_CACHE_TOKEN];
            cacheToken(data, bytes, token);
            if (ANeuralNetworksCompilation_setCaching(c->comp, cacheDir, token) != ANEURALNETWORKS_NO_ERROR)
                __android_log_print(ANDROID_LOG_WARN, "Pmrid", "compilation cache unavailable");
        }
        if (ANeuralNetworksCompilation_finish(c->comp) != ANEURALNETWORKS_NO_ERROR) throw std::runtime_error("compile for " + c->device);
        __android_log_print(ANDROID_LOG_INFO, "Pmrid", "compiled %d ops for %s, tile %d", int(nops), c->device.c_str(), kTile);
        return c.release();
    } catch (const std::exception& e) {
        fail(errmsg, errlen, e.what());
        pmrid_destroy(c.release());
        return nullptr;
    }
}

extern "C" const char* pmrid_device(const PmridContext* c) { return c ? c->device.c_str() : ""; }
extern "C" int pmrid_tile(const PmridContext*) { return kTile; }

extern "C" int pmrid_run_tile(PmridContext* c, const uint8_t* u, float sigma, uint8_t* out, char* errmsg, int errlen) {
    const Blob& b = c->blob;
    const Tq sbq = b.tensors.at("inc.sb");
    std::vector<uint8_t> sb(b.c0);
    for (uint32_t i = 0; i < b.c0; i++)
        sb[i] = uint8_t(std::clamp(std::lround((b.sbBias[i] + sigma * b.sbW4[i]) / sbq.scale) + sbq.zp, 0L, 255L));
    ANeuralNetworksExecution* ex = nullptr;
    const size_t n = size_t(kTile) * kTile * 4;
    int rc = ANeuralNetworksExecution_create(c->comp, &ex);
    if (rc == 0) rc = ANeuralNetworksExecution_setInput(ex, 0, nullptr, u, n);
    if (rc == 0) rc = ANeuralNetworksExecution_setInput(ex, 1, nullptr, sb.data(), sb.size());
    if (rc == 0) rc = ANeuralNetworksExecution_setOutput(ex, 0, nullptr, out, n);
    if (rc == 0) rc = ANeuralNetworksExecution_compute(ex);
    if (ex) ANeuralNetworksExecution_free(ex);
    if (rc != 0) {
        fail(errmsg, errlen, ("execute on " + c->device + " = " + std::to_string(rc)).c_str());
        return -1;
    }
    return 0;
}

extern "C" int pmrid_process(PmridContext* c, const uint16_t* in, size_t inStride, uint16_t* out, int w, int h, int cfa,
                             const float black[4], float white, float a, float bn, float strength, double* npuMs,
                             char* errmsg, int errlen) {
    try {
        if (w < 64 || h < 64 || (w & 1) || (h & 1) || inStride < size_t(w)) throw std::runtime_error("unsupported frame size");
        if (!(a > 0.f) || bn < 0.f) throw std::runtime_error("invalid noise model");
        const Blob& b = c->blob;
        const Tq iq = b.tensors.at("in"), oq = b.tensors.at("out");
        // Plane offsets (row, column) of R, G1, G2, B for each CFA pattern: the net was trained on RGGB order.
        static const int kOff[4][4][2] = {{{0, 0}, {0, 1}, {1, 0}, {1, 1}}, {{0, 1}, {0, 0}, {1, 1}, {1, 0}},
                                          {{1, 0}, {0, 0}, {1, 1}, {0, 1}}, {{1, 1}, {0, 1}, {1, 0}, {0, 0}}};
        const auto& off = kOff[cfa & 3];
        const int H = h / 2, W = w / 2;
        const double g0 = gat(0.0, a, bn), g1 = gat(1.0, a, bn) - g0, sigma = 1.0 / g1;
        // Per plane, per raw value: linear x, its GAT u and the graph's uint8 input.
        constexpr int kN = 65536;
        std::vector<float> lutX(size_t(4) * kN), lutU(size_t(4) * kN);
        std::vector<uint8_t> lutQ(size_t(4) * kN);
        for (int p = 0; p < 4; p++) {
            const float blk = black[off[p][0] * 2 + off[p][1]], range = 1.f / std::max(white - blk, 1.f);
            for (int v = 0; v < kN; v++) {
                const double x = (double(v) - blk) * range, u = (gat(x, a, bn) - g0) / g1;
                lutX[size_t(p) * kN + v] = float(x);
                lutU[size_t(p) * kN + v] = float(u);
                lutQ[size_t(p) * kN + v] = uint8_t(std::clamp(std::lround(u / iq.scale) + iq.zp, 0L, 255L));
            }
        }
        auto sample = [&](int y, int x, int p) { return in[size_t(2 * y + off[p][0]) * inStride + size_t(2 * x + off[p][1])]; };
        const int nThreads = int(std::clamp(std::thread::hardware_concurrency(), 1u, 4u));
        auto parallelRows = [&](int y0, int y1, auto&& fn) {
            std::vector<std::thread> t;
            const int step = (y1 - y0 + nThreads - 1) / nThreads;
            for (int k = 0; k < nThreads; k++) {
                const int a0 = y0 + k * step, a1 = std::min(y1, a0 + step);
                if (a0 < a1) t.emplace_back([&, a0, a1] { for (int y = a0; y < a1; y++) fn(y); });
            }
            for (auto& th : t) th.join();
        };
        // The graph input of the whole frame, NHWC.
        std::vector<uint8_t> U(size_t(H) * W * 4);
        parallelRows(0, H, [&](int y) {
            for (int x = 0; x < W; x++)
                for (int p = 0; p < 4; p++) U[(size_t(y) * W + x) * 4 + p] = lutQ[size_t(p) * kN + sample(y, x, p)];
        });
        const int core = kTile - 2 * kOverlap;
        const float k = float(b.gain * sigma * oq.scale), zo = float(oq.zp);
        const float gg1 = float(g1), gg0 = float(g0), fa = a, fb = bn, wcl = white;
        // Output of one tile, written while the NPU runs the next.
        auto post = [&](int ty, int tx, const uint8_t* t) {
            parallelRows(ty, std::min(ty + core, H), [&](int y) {
                for (int x = tx; x < std::min(tx + core, W); x++) {
                    const uint8_t* o = &t[(size_t(y - ty + kOverlap) * kTile + (x - tx + kOverlap)) * 4];
                    for (int p = 0; p < 4; p++) {
                        const uint16_t r = sample(y, x, p);
                        const float x0 = lutX[size_t(p) * kN + r];
                        float v = x0;
                        if (r < wcl) {   // clipped sites pass through
                            float z = std::max((lutU[size_t(p) * kN + r] + k * (float(o[p]) - zo)) * gg1 + gg0, 1.f);
                            const float iz = 1.f / z, s15 = 1.2247449f;
                            const float i0 = 0.25f * z * z + 0.25f * s15 * iz - 1.375f * iz * iz + 0.625f * s15 * iz * iz * iz - 0.125f;
                            v = x0 + strength * ((fa * i0 - fb / fa) - x0);
                        }
                        const long e = std::lround((v * 15.f / 16.f + 1.f / 16.f) * 65535.f);
                        out[size_t(2 * y + off[p][0]) * size_t(w) + size_t(2 * x + off[p][1])] = uint16_t(std::clamp(e, 0L, 65535L));
                    }
                }
            });
        };
        std::vector<uint8_t> tin(size_t(kTile) * kTile * 4), tout[2] = {std::vector<uint8_t>(tin.size()), std::vector<uint8_t>(tin.size())};
        std::future<void> pending;
        double ms = 0;
        int n = 0;
        for (int ty = 0; ty < H; ty += core)
            for (int tx = 0; tx < W; tx += core, n++) {
                for (int y = 0; y < kTile; y++) {
                    const int sy = reflect(ty - kOverlap + y, H);
                    uint8_t* dst = &tin[size_t(y) * kTile * 4];
                    const uint8_t* row = &U[size_t(sy) * W * 4];
                    for (int x = 0; x < kTile; x++) std::memcpy(dst + 4 * x, row + 4 * reflect(tx - kOverlap + x, W), 4);
                }
                std::vector<uint8_t>& t = tout[n & 1];
                const auto t0 = std::chrono::steady_clock::now();
                const int rc = pmrid_run_tile(c, tin.data(), float(sigma), t.data(), errmsg, errlen);
                ms += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
                if (pending.valid()) pending.get();   // the previous tile's output, written meanwhile
                if (rc != 0) return -1;
                pending = std::async(std::launch::async, post, ty, tx, t.data());
            }
        if (pending.valid()) pending.get();
        if (npuMs) *npuMs = ms;
        return 0;
    } catch (const std::exception& e) {
        fail(errmsg, errlen, e.what());
        return -1;
    }
}

extern "C" void pmrid_destroy(PmridContext* c) {
    if (!c) return;
    if (c->comp) ANeuralNetworksCompilation_free(c->comp);
    if (c->model) ANeuralNetworksModel_free(c->model);
    delete c;
}
