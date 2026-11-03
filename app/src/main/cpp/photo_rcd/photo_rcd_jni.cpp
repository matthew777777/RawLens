// SPDX-License-Identifier: GPL-3.0-or-later
// New Android adapter; no retired backend imports, RawLens/Codex 2026-11-01.
#include "photo_rcd_engine.h"
#include "photo_rcd_input.h"
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <chrono>
#include <jni.h>
#include <map>
#include <memory>
#include <mutex>

namespace {
std::mutex registryMutex;
std::map<jlong, std::shared_ptr<photo_rcd::Engine>> engines;
jlong nextHandle = 1;
std::map<jlong, std::array<double, 4>> frameCosts;
using Clock = std::chrono::steady_clock;
double milliseconds(Clock::time_point start) {
  return std::chrono::duration<double, std::milli>(Clock::now() - start)
      .count();
}
void failure(JNIEnv *env, const std::exception &error) {
  if (!env->ExceptionCheck())
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                  error.what());
}
std::shared_ptr<photo_rcd::Engine> get(jlong handle) {
  std::lock_guard<std::mutex> lock(registryMutex);
  auto found = engines.find(handle);
  if (found == engines.end())
    throw std::invalid_argument("closed photo RCD engine");
  return found->second;
}
} // namespace
extern "C" JNIEXPORT jlong JNICALL
Java_com_matthew_rawlens_PhotoRcdRenderer_openNative(
    JNIEnv *env, jobject, jbyteArray code, jboolean specialize,
    jboolean cacheResponses, jboolean tiledReconstruction,
    jboolean imageIntermediates, jboolean recordingCandidate,
    jobjectArray stageCodes, jboolean packedCells, jbyteArray rawPackCode,
    jint reconstructionScale, jboolean normalizeOnce, jboolean tileLocal,
    jboolean imageOutput, jboolean frameGlobal) {
  try {
    if (!code || env->GetArrayLength(code) < 20 ||
        env->GetArrayLength(code) % 4)
      throw std::invalid_argument("invalid shader bytes");
    std::vector<uint32_t> shader(env->GetArrayLength(code) / 4);
    env->GetByteArrayRegion(code, 0, env->GetArrayLength(code),
                            reinterpret_cast<jbyte *>(shader.data()));
    if (env->ExceptionCheck())
      return 0;
    std::vector<std::vector<uint32_t>> stages;
    if (stageCodes) {
      if (env->GetArrayLength(stageCodes) != 8)
        throw std::invalid_argument("invalid stage-program array");
      stages.resize(8);
      for (int pass : {0, 1, 2, 3, 4, 6, 7}) {
        auto bytes = static_cast<jbyteArray>(
            env->GetObjectArrayElement(stageCodes, pass));
        if (!bytes || env->GetArrayLength(bytes) < 20 ||
            env->GetArrayLength(bytes) % 4) {
          if (bytes)
            env->DeleteLocalRef(bytes);
          throw std::invalid_argument("invalid stage-program bytes");
        }
        stages[pass].resize(env->GetArrayLength(bytes) / 4);
        env->GetByteArrayRegion(bytes, 0, env->GetArrayLength(bytes),
                                reinterpret_cast<jbyte *>(stages[pass].data()));
        env->DeleteLocalRef(bytes);
        if (env->ExceptionCheck())
          return 0;
      }
    }
    std::vector<uint32_t> rawPack;
    if (rawPackCode) {
      const auto n = env->GetArrayLength(rawPackCode);
      if (n < 20 || n % 4)
        throw std::invalid_argument("invalid RAW pack shader");
      rawPack.resize(n / 4);
      env->GetByteArrayRegion(rawPackCode, 0, n,
                              reinterpret_cast<jbyte *>(rawPack.data()));
      if (env->ExceptionCheck())
        return 0;
    }
    auto engine = std::make_shared<photo_rcd::Engine>(
        shader, specialize == JNI_TRUE, cacheResponses == JNI_TRUE,
        tiledReconstruction == JNI_TRUE, imageIntermediates == JNI_TRUE,
        recordingCandidate == JNI_TRUE, stages, packedCells == JNI_TRUE,
        rawPack, reconstructionScale, normalizeOnce == JNI_TRUE,
        tileLocal == JNI_TRUE, imageOutput == JNI_TRUE,
        std::array<uint32_t, 2>{16, 16}, frameGlobal == JNI_TRUE);
    std::lock_guard<std::mutex> lock(registryMutex);
    jlong handle = nextHandle++;
    engines.emplace(handle, std::move(engine));
    return handle;
  } catch (const std::exception &e) {
    failure(env, e);
    return 0;
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_matthew_rawlens_PhotoRcdRenderer_closeNative(JNIEnv *, jobject,
                                                      jlong handle) {
  std::shared_ptr<photo_rcd::Engine> removed;
  {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto found = engines.find(handle);
    if (found != engines.end()) {
      removed = std::move(found->second);
      engines.erase(found);
      frameCosts.erase(handle);
    }
  }
  // A concurrently obtained shared_ptr keeps an in-flight frame alive. Final
  // engine destruction drains its own GPU before destroying any resources.
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_matthew_rawlens_PhotoRcdRenderer_canonicalNative(
    JNIEnv *env, jobject, jlong handle, jint width, jint height, jint phase,
    jfloatArray input) {
  try {
    if (!input || width < 19 || height < 19 || width > 16384 ||
        height > 16384 || uint64_t(width) * height > 16000000 ||
        env->GetArrayLength(input) != int64_t(width) * height)
      throw std::invalid_argument("invalid canonical readback geometry");
    std::vector<float> cfa(env->GetArrayLength(input)), rgba(cfa.size() * 4);
    env->GetFloatArrayRegion(input, 0, cfa.size(), cfa.data());
    if (env->ExceptionCheck())
      return nullptr;
    get(handle)->render(width, height, uint32_t(phase), cfa.data(), cfa.size(),
                        rgba.data(), rgba.size());
    auto output = env->NewFloatArray(rgba.size());
    if (output)
      env->SetFloatArrayRegion(output, 0, rgba.size(), rgba.data());
    return output;
  } catch (const std::exception &e) {
    failure(env, e);
    return nullptr;
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_matthew_rawlens_PhotoRcdRenderer_renderNative(
    JNIEnv *env, jobject, jlong handle, jobject input, jintArray geometry,
    jfloatArray blacks, jfloat white, jfloatArray whiteBalance,
    jintArray shadingDims, jshortArray shading, jobject output) {
  try {
    if (!input || !geometry || env->GetArrayLength(geometry) != 9 || !blacks ||
        env->GetArrayLength(blacks) != 4 || !whiteBalance ||
        env->GetArrayLength(whiteBalance) != 3 || !output)
      throw std::invalid_argument("invalid frame arguments");
    int g[9];
    std::array<float, 4> black;
    std::array<float, 3> wb;
    env->GetIntArrayRegion(geometry, 0, 9, g);
    env->GetFloatArrayRegion(blacks, 0, 4, black.data());
    env->GetFloatArrayRegion(whiteBalance, 0, 3, wb.data());
    if (env->ExceptionCheck())
      return;
    auto *bytes = static_cast<uint8_t *>(env->GetDirectBufferAddress(input));
    jlong capacity = env->GetDirectBufferCapacity(input);
    if (!bytes || capacity < 0)
      throw std::invalid_argument("RAW input must be a direct buffer");
    auto clockStart = Clock::now();
    const auto engine = get(handle);
    std::vector<float> cfa;
    if (engine->usesRawInput())
      photo_rcd::validateRaw16(bytes, size_t(capacity), g[0], g[1], g[2], g[3],
                               g[4], g[5], g[6], g[7], black, white);
    else
      cfa =
          photo_rcd::normalizeRaw16(bytes, size_t(capacity), g[0], g[1], g[2],
                                    g[3], g[4], g[5], g[6], g[7], black, white);
    const double normalizationMs = milliseconds(clockStart);
    clockStart = Clock::now();
    std::vector<int> dimensions;
    std::vector<uint16_t> gains;
    if (shadingDims) {
      auto n = env->GetArrayLength(shadingDims);
      if (n != 6)
        throw std::invalid_argument("invalid shading dimensions");
      dimensions.resize(n);
      env->GetIntArrayRegion(shadingDims, 0, n, dimensions.data());
    }
    if (shading) {
      auto n = env->GetArrayLength(shading);
      if (n > 65536 * 4)
        throw std::invalid_argument("shading map too large");
      gains.resize(n);
      env->GetShortArrayRegion(shading, 0, n,
                               reinterpret_cast<jshort *>(gains.data()));
    }
    if (env->ExceptionCheck())
      return;
    // Validate metadata through the unchanged CPU contract, then send exact
    // FP32 map samples to a separate Vulkan product pass. No FP16 arithmetic.
    float validationPixel[4] = {0.f, 0.f, 0.f, 1.f};
    photo_rcd::productCorrections(validationPixel, 1, 1, g[4], g[5], wb,
                                  dimensions, gains);
    std::vector<uint32_t> parameters(16 + gains.size(), 0);
    parameters[0] = g[4];
    parameters[1] = g[5];
    for (int i = 0; i < 3; ++i)
      std::memcpy(&parameters[2 + i], &wb[i], 4);
    if (!dimensions.empty()) {
      parameters[5] = dimensions[0];
      parameters[6] = dimensions[1];
      for (int i = 2; i < 6; ++i)
        parameters[5 + i] = dimensions[i];
      parameters[11] = 1;
      for (size_t i = 0; i < gains.size(); ++i) {
        float value = photo_rcd::halfToFloat(gains[i]);
        std::memcpy(&parameters[16 + i], &value, 4);
      }
    }
    if (engine->usesImageOutput()) {
      auto *buffer = AHardwareBuffer_fromHardwareBuffer(env, output);
      if (!buffer)
        throw std::invalid_argument("Invalid GPU output buffer");
      std::array<int, 9> geometryValues;
      std::copy(g, g + 9, geometryValues.begin());
      engine->renderRawImage(bytes, size_t(capacity), geometryValues, black,
                             white, parameters, buffer);
      const double reconstructionMs = milliseconds(clockStart);
      std::lock_guard<std::mutex> lock(registryMutex);
      frameCosts[handle] = {{normalizationMs, reconstructionMs, 0.0, 0.0}};
      return;
    }
    std::vector<uint16_t> half;
    if (engine->usesRawInput()) {
      std::array<int, 9> geometryValues;
      std::copy(g, g + 9, geometryValues.begin());
      engine->renderRawHalf(bytes, size_t(capacity), geometryValues, black,
                            white, parameters, half);
    } else
      engine->renderHalf(g[6], g[7], uint32_t(g[8]), cfa.data(), cfa.size(),
                         parameters, half);
    const double reconstructionMs = milliseconds(clockStart);
    const double correctionMs =
        0; // included in GPU reconstruction/product batch
    clockStart = Clock::now();
    AHardwareBuffer *buffer = AHardwareBuffer_fromHardwareBuffer(env, output);
    if (!buffer)
      throw std::invalid_argument("invalid output buffer");
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    const int scale = engine->reconstructionScale();
    const uint32_t outputW =
        scale == 1 ? g[6] : 2 * ((g[6] + 2 * scale - 1) / (2 * scale));
    const uint32_t outputH =
        scale == 1 ? g[7] : 2 * ((g[7] + 2 * scale - 1) / (2 * scale));
    if (desc.width != outputW || desc.height != outputH ||
        half.size() != size_t(outputW) * outputH * 4 || desc.layers != 1 ||
        desc.format != AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT ||
        desc.stride < desc.width)
      throw std::invalid_argument("invalid RGBA16F output layout");
    void *mapped = nullptr;
    if (AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1,
                             nullptr, &mapped) != 0 ||
        !mapped)
      throw std::runtime_error("RGBA16F CPU lock failed");
    auto *destination = static_cast<uint16_t *>(mapped);
    for (uint32_t y = 0; y < desc.height; ++y)
      std::memcpy(destination + size_t(y) * desc.stride * 4,
                  half.data() + size_t(y) * desc.width * 4,
                  size_t(desc.width) * 8);
    // Null release-fence output requests a synchronous unlock. The separate
    // grade context cannot read this buffer until this method returns.
    if (AHardwareBuffer_unlock(buffer, nullptr) != 0)
      throw std::runtime_error("RGBA16F CPU unlock failed");
    {
      std::lock_guard<std::mutex> lock(registryMutex);
      frameCosts[handle] = {{normalizationMs, reconstructionMs, correctionMs,
                             milliseconds(clockStart)}};
    }
  } catch (const std::exception &e) {
    failure(env, e);
  }
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_matthew_rawlens_PhotoRcdRenderer_frameCostsNative(JNIEnv *env, jobject,
                                                           jlong handle) {
  std::array<double, 4> costs{};
  {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto found = frameCosts.find(handle);
    if (found != frameCosts.end())
      costs = found->second;
  }
  auto gpuCosts = get(handle)->gpuStageCosts();
  std::vector<double> allCosts(costs.begin(), costs.end());
  allCosts.insert(allCosts.end(), gpuCosts.begin(), gpuCosts.end());
  auto result = env->NewDoubleArray(allCosts.size());
  if (result)
    env->SetDoubleArrayRegion(result, 0, allCosts.size(), allCosts.data());
  return result;
}

// FP32 numerical gate before product correction or final half storage.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_matthew_rawlens_PhotoRcdRenderer_canonicalRawNative(
    JNIEnv *env, jobject, jlong handle, jobject input, jintArray geometry,
    jfloatArray blacks, jfloat white) {
  try {
    if (!input || !geometry || env->GetArrayLength(geometry) != 9 || !blacks ||
        env->GetArrayLength(blacks) != 4)
      throw std::invalid_argument("invalid canonical RAW arguments");
    std::array<int, 9> g;
    std::array<float, 4> black;
    env->GetIntArrayRegion(geometry, 0, 9, g.data());
    env->GetFloatArrayRegion(blacks, 0, 4, black.data());
    if (env->ExceptionCheck())
      return nullptr;
    auto *bytes = static_cast<uint8_t *>(env->GetDirectBufferAddress(input));
    auto capacity = env->GetDirectBufferCapacity(input);
    if (!bytes || capacity < 0)
      throw std::invalid_argument("RAW input must be direct");
    std::vector<float> rgba;
    get(handle)->renderRaw(bytes, size_t(capacity), g, black, white, rgba);
    auto output = env->NewFloatArray(rgba.size());
    if (output)
      env->SetFloatArrayRegion(output, 0, rgba.size(), rgba.data());
    return output;
  } catch (const std::exception &e) {
    failure(env, e);
    return nullptr;
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_matthew_rawlens_PhotoRcdRenderer_outputUsageNative(JNIEnv *env,
                                                            jobject,
                                                            jlong handle) {
  try {
    return jlong(get(handle)->outputUsage());
  } catch (const std::exception &e) {
    failure(env, e);
    return 0;
  }
}
