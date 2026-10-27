// SPDX-License-Identifier: GPL-3.0-or-later
// Reader bridge: .CRAW takes (Cinematic RAW Studio decoder, Apache-2.0,
// third_party/craw/decoder) for offline burst merge. One handle per merge
// thread; negative returns become Kotlin exceptions.
#include <jni.h>

#include <craw/Reader.h>

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <thread>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

struct Shading {
    uint16_t columns = 0, rows = 0;
    std::vector<float> gains;  // 4 per point, colour order R, Gr, Gb, B
};

struct Handle {
    explicit Handle(const std::string& path) : reader(path) {
        // Index from chunk headers only: one corrupt frame must not hide the take.
        size_t i = 0;
        for (const auto& c : reader.chunks()) {
            if (std::memcmp(c.fourcc.data(), "LSHD", 4) == 0 && reader.crcOk(c)) {
                const auto p = reader.payload(c);
                if (p.size() < 16) continue;
                Shading sh;
                uint32_t id;
                std::memcpy(&id, p.data() + 8, 4);
                std::memcpy(&sh.columns, p.data() + 12, 2);
                std::memcpy(&sh.rows, p.data() + 14, 2);
                const size_t n = size_t{sh.columns} * sh.rows * 4;
                if (n == 0 || p.size() != 16 + 4 * n) continue;
                sh.gains.resize(n);
                std::memcpy(sh.gains.data(), p.data() + 16, 4 * n);
                shading[id] = std::move(sh);
                continue;
            }
            if (std::memcmp(c.fourcc.data(), "FRAM", 4) != 0) continue;
            byTs.emplace(c.timestampNs, i++);
            timestamps.push_back(c.timestampNs);
        }
    }
    craw::Reader reader;
    std::unordered_map<int64_t, size_t> byTs;
    std::vector<int64_t> timestamps;
    std::unordered_map<uint32_t, Shading> shading;
};

Handle* from(jlong h) { return reinterpret_cast<Handle*>(h); }

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// Frame JSON plus the decoded geometry readers rely on ("width"/"height").
std::string withGeometry(const std::string& json, const craw::FrameInfo& info) {
    const std::string dims = "\"width\":" + std::to_string(info.width) +
                             ",\"height\":" + std::to_string(info.height);
    if (json.find("\"width\"") != std::string::npos) return json;
    if (json.size() < 2 || json.front() != '{') return "{" + dims + "}";
    const bool empty = json.find_first_not_of(" \t\r\n", 1) == json.size() - 1;
    return "{" + dims + (empty ? "" : ",") + json.substr(1);
}

// Expands `"lshd_ref":N` into the legacy per-frame triplet
// (lensShadingMapWidth/Height + four colour planes) that frame readers parse.
std::string withShading(const std::string& json, const Handle& h) {
    const auto key = json.find("\"lshd_ref\":");
    if (key == std::string::npos || json.find("\"lensShadingMap\"") != std::string::npos) return json;
    const auto id = static_cast<uint32_t>(std::strtoul(json.c_str() + key + 11, nullptr, 10));
    const auto it = h.shading.find(id);
    if (it == h.shading.end()) return json;
    const Shading& sh = it->second;
    std::string t = "\"lensShadingMapWidth\":" + std::to_string(sh.columns) +
                    ",\"lensShadingMapHeight\":" + std::to_string(sh.rows) + ",\"lensShadingMap\":[";
    const size_t points = size_t{sh.columns} * sh.rows;
    char num[32];
    for (int ch = 0; ch < 4; ++ch) {
        t += ch ? ",[" : "[";
        for (size_t i = 0; i < points; ++i) {
            std::snprintf(num, sizeof num, i ? ",%.9g" : "%.9g", sh.gains[i * 4 + ch]);
            t += num;
        }
        t += ']';
    }
    t += ']';
    const auto close = json.rfind('}');
    if (close == std::string::npos) return json;
    return json.substr(0, close) + "," + t + json.substr(close);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_matthew_rawlens_CrawReader_openReader(JNIEnv* env, jobject, jstring path) {
    try {
        return reinterpret_cast<jlong>(new Handle(jstr(env, path)));
    } catch (...) {
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_matthew_rawlens_CrawReader_closeReader(JNIEnv*, jobject, jlong h) {
    delete from(h);
}

JNIEXPORT jstring JNICALL
Java_com_matthew_rawlens_CrawReader_containerMetadata(JNIEnv* env, jobject, jlong h) {
    auto* handle = from(h);
    return handle ? env->NewStringUTF(handle->reader.metaJson().c_str()) : nullptr;
}

JNIEXPORT jlongArray JNICALL
Java_com_matthew_rawlens_CrawReader_frameTimestamps(JNIEnv* env, jobject, jlong h) {
    auto* handle = from(h);
    if (!handle) return nullptr;
    const auto n = static_cast<jsize>(handle->timestamps.size());
    jlongArray out = env->NewLongArray(n);
    if (out) env->SetLongArrayRegion(out, 0, n, reinterpret_cast<const jlong*>(handle->timestamps.data()));
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_matthew_rawlens_CrawReader_frameMetadata(JNIEnv* env, jobject, jlong h, jlong ts) {
    auto* handle = from(h);
    if (!handle) return nullptr;
    const auto it = handle->byTs.find(ts);
    if (it == handle->byTs.end()) return nullptr;
    try {
        const auto rec = handle->reader.frame(it->second);
        const auto info = craw::probeFrame(rec.payload, rec.payloadSize);
        return env->NewStringUTF(withShading(withGeometry(rec.metaJson, info), *handle).c_str());
    } catch (...) {
        return nullptr;
    }
}

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_CrawReader_decodeFrame(JNIEnv* env, jobject, jlong h, jlong ts, jobject dst,
                                                jint dstOffset, jint dstCapacity) {
    auto* handle = from(h);
    auto* d = static_cast<uint8_t*>(env->GetDirectBufferAddress(dst));
    if (!handle || !d) return -2;
    const auto it = handle->byTs.find(ts);
    if (it == handle->byTs.end()) return -7;
    try {
        thread_local std::vector<uint16_t> pixels;
        const auto rec = handle->reader.frame(it->second);
        // Tiles decode independently: use every core (offline merge, nothing else runs).
        static const unsigned threads = std::max(1u, std::thread::hardware_concurrency());
        craw::decodeFrame(rec.payload, rec.payloadSize, pixels, threads);
        const size_t bytes = pixels.size() * 2;
        if (bytes > static_cast<size_t>(dstCapacity)) return -3;
        std::memcpy(d + dstOffset, pixels.data(), bytes);
        return static_cast<jint>(bytes);
    } catch (const craw::DecodeError&) {
        return -4;
    } catch (...) {
        return -5;
    }
}

}  // extern "C"
