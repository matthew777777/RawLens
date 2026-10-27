// SPDX-License-Identifier: GPL-3.0-or-later
// Reader bridge: .CRAW takes (Cinematic RAW Studio decoder, Apache-2.0,
// third_party/craw/decoder) for offline burst merge. One handle per merge
// thread; negative returns become Kotlin exceptions.
#include <jni.h>

#include <craw/Reader.h>

#include <cstring>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

struct Handle {
    explicit Handle(const std::string& path) : reader(path) {
        // Index from chunk headers only: one corrupt frame must not hide the take.
        size_t i = 0;
        for (const auto& c : reader.chunks()) {
            if (std::memcmp(c.fourcc.data(), "FRAM", 4) != 0) continue;
            byTs.emplace(c.timestampNs, i++);
            timestamps.push_back(c.timestampNs);
        }
    }
    craw::Reader reader;
    std::unordered_map<int64_t, size_t> byTs;
    std::vector<int64_t> timestamps;
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
        return env->NewStringUTF(withGeometry(rec.metaJson, info).c_str());
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
        craw::decodeFrame(rec.payload, rec.payloadSize, pixels);
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
