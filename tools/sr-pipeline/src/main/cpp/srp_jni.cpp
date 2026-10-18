// JNI bridge for the sr-pipeline native library.
//
// Java class (to be added on the Kotlin side when needed):
//   package com.rawlens.srpipeline; class SrpBridge
// Methods here are plain JNI entry points; no Kotlin file references
// or depends on them yet.

#include <jni.h>

#include <vulkan/vulkan.h>

#include <cstdint>
#include <cstring>
#include <functional>
#include <string>
#include <vector>

#include "srp_context.h"
#include "srp_dng.h"
#include "srp_pipeline.h"

namespace {

// Interns one SPIR-V module in the Context cache (defined below).
bool InternSpirv(JNIEnv* env, srp::Context* ctx, jbyteArray spirv,
                 VkShaderModule* out_module);

// Finds a memory type with the requested property flags.
bool FindMemoryType(VkPhysicalDevice physical, uint32_t type_bits,
                    VkMemoryPropertyFlags flags, uint32_t* out_index) {
  VkPhysicalDeviceMemoryProperties props{};
  vkGetPhysicalDeviceMemoryProperties(physical, &props);
  for (uint32_t i = 0; i < props.memoryTypeCount; ++i) {
    if ((type_bits & (1u << i)) != 0u &&
        (props.memoryTypes[i].propertyFlags & flags) == flags) {
      *out_index = i;
      return true;
    }
  }
  return false;
}

// Host-visible staging buffer, filled from `src` (may be null for
// zero-initialized output buffers).
bool MakeStagingBuffer(VkDevice device, VkPhysicalDevice physical, VkDeviceSize bytes,
                       const void* src, VkBuffer* out_buffer, VkDeviceMemory* out_memory) {
  VkBufferCreateInfo buffer_info{};
  buffer_info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
  buffer_info.size = bytes;
  buffer_info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
  buffer_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

  VkBuffer buffer = VK_NULL_HANDLE;
  if (vkCreateBuffer(device, &buffer_info, nullptr, &buffer) != VK_SUCCESS) return false;

  VkMemoryRequirements req{};
  vkGetBufferMemoryRequirements(device, buffer, &req);
  uint32_t index = 0;
  if (!FindMemoryType(physical, req.memoryTypeBits,
                      VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                          VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                      &index)) {
    vkDestroyBuffer(device, buffer, nullptr);
    return false;
  }

  VkMemoryAllocateInfo alloc{};
  alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
  alloc.allocationSize = req.size;
  alloc.memoryTypeIndex = index;

  VkDeviceMemory memory = VK_NULL_HANDLE;
  if (vkAllocateMemory(device, &alloc, nullptr, &memory) != VK_SUCCESS) {
    vkDestroyBuffer(device, buffer, nullptr);
    return false;
  }
  if (vkBindBufferMemory(device, buffer, memory, 0) != VK_SUCCESS) {
    vkFreeMemory(device, memory, nullptr);
    vkDestroyBuffer(device, buffer, nullptr);
    return false;
  }

  if (src != nullptr) {
    void* mapped = nullptr;
    if (vkMapMemory(device, memory, 0, bytes, 0, &mapped) != VK_SUCCESS) {
      vkFreeMemory(device, memory, nullptr);
      vkDestroyBuffer(device, buffer, nullptr);
      return false;
    }
    std::memcpy(mapped, src, static_cast<size_t>(bytes));
    vkUnmapMemory(device, memory);
  }

  *out_buffer = buffer;
  *out_memory = memory;
  return true;
}

}  // namespace

extern "C" {

// Creates a context; returns the native handle, or 0 on failure.
JNIEXPORT jlong JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeCreate(JNIEnv* /*env*/,
                                                                           jclass /*cls*/) {
  srp::Context* ctx = nullptr;
  if (srp::Create(&ctx) != srp::Status::kOk) return 0;
  return static_cast<jlong>(reinterpret_cast<intptr_t>(ctx));
}

// Releases a context created by nativeCreate; no-op on 0.
JNIEXPORT void JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeDestroy(JNIEnv* /*env*/,
                                                                           jclass /*cls*/,
                                                                           jlong handle) {
  if (handle == 0) return;
  srp::Destroy(reinterpret_cast<srp::Context*>(static_cast<intptr_t>(handle)));
}

// Human-readable device summary for the context (or "(no device)").
JNIEXPORT jstring JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeDeviceInfo(JNIEnv* env,
                                                                                jclass /*cls*/,
                                                                                jlong handle) {
  const auto* ctx = reinterpret_cast<const srp::Context*>(static_cast<intptr_t>(handle));
  const std::string info = srp::DeviceInfo(ctx);
  return env->NewStringUTF(info.c_str());
}

// Real GPU path: merge-accumulate over caller-owned direct buffers.
//
// `in_buffer`/`acc_buffer` are caller-owned direct ByteBuffers with at
// least count floats each; `spirv` holds the compiled
// shaders/merge_accumulate.comp module. Stages caller data through
// host-visible VkBuffers, dispatches the full grid with the params as
// push constants, fence-waits, and copies the accumulator back into
// `acc_buffer`. Returns the srp::Status code as an int.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeMergeAccumulate(
    JNIEnv* env, jclass /*cls*/, jlong handle, jobject in_buffer, jobject acc_buffer,
    jbyteArray spirv, jint count, jint width, jint height, jfloat gain) {
  auto* ctx = reinterpret_cast<srp::Context*>(static_cast<intptr_t>(handle));
  if (ctx == nullptr || in_buffer == nullptr || acc_buffer == nullptr || spirv == nullptr ||
      count <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkDevice device = srp::NativeDevice(ctx);
  VkPhysicalDevice physical = srp::NativePhysicalDevice(ctx);
  if (device == VK_NULL_HANDLE || physical == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kNoDevice);
  }

  float* in_ptr = static_cast<float*>(env->GetDirectBufferAddress(in_buffer));
  float* acc_ptr = static_cast<float*>(env->GetDirectBufferAddress(acc_buffer));
  const jlong in_cap = env->GetDirectBufferCapacity(in_buffer);
  const jlong acc_cap = env->GetDirectBufferCapacity(acc_buffer);
  const VkDeviceSize bytes = static_cast<VkDeviceSize>(count) * sizeof(float);
  if (in_ptr == nullptr || acc_ptr == nullptr || in_cap < static_cast<jlong>(bytes) ||
      acc_cap < static_cast<jlong>(bytes)) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }

  const jsize spirv_len = env->GetArrayLength(spirv);
  if (spirv_len <= 0 || spirv_len % 4 != 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  std::vector<uint8_t> spirv_bytes(static_cast<size_t>(spirv_len));
  env->GetByteArrayRegion(spirv, 0, spirv_len, reinterpret_cast<jbyte*>(spirv_bytes.data()));
  if (env->ExceptionCheck() == JNI_TRUE) return static_cast<jint>(srp::Status::kInitFailed);

  // Context-interned stage module (created once per process, owned by
  // the Context; replaces per-call create/destroy).

  VkShaderModule module =
      srp::SrpInternModule(ctx, spirv_bytes.data(), spirv_bytes.size());
  if (module == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kDispatchFailed);
  }

  VkBuffer in_vk = VK_NULL_HANDLE;
  VkDeviceMemory in_mem = VK_NULL_HANDLE;
  VkBuffer acc_vk = VK_NULL_HANDLE;
  VkDeviceMemory acc_mem = VK_NULL_HANDLE;
  srp::Status status = srp::Status::kDispatchFailed;
  if (MakeStagingBuffer(device, physical, bytes, in_ptr, &in_vk, &in_mem) &&
      MakeStagingBuffer(device, physical, bytes, acc_ptr, &acc_vk, &acc_mem)) {
    srp::SrpMergeParams params{};
    params.count = static_cast<uint32_t>(count);
    params.width = static_cast<uint32_t>(width);
    params.height = static_cast<uint32_t>(height);
    params.gain = gain;
    status = srp::SrpMergeAccumulateGpu(ctx, &params, in_vk, acc_vk, module);
    if (status == srp::Status::kOk) {
      void* mapped = nullptr;
      if (vkMapMemory(device, acc_mem, 0, bytes, 0, &mapped) == VK_SUCCESS) {
        std::memcpy(acc_ptr, mapped, static_cast<size_t>(bytes));
        vkUnmapMemory(device, acc_mem);
      } else {
        status = srp::Status::kDispatchFailed;
      }
    }
  }

  if (in_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, in_vk, nullptr);
  if (in_mem != VK_NULL_HANDLE) vkFreeMemory(device, in_mem, nullptr);
  if (acc_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, acc_vk, nullptr);
  if (acc_mem != VK_NULL_HANDLE) vkFreeMemory(device, acc_mem, nullptr);
  // (module stays alive in the Context cache)
  return static_cast<jint>(status);
}

// Unified DNG output: linear-RGB float32 direct buffer to a 16-bit
// uncompressed DNG file via TinyDNG v3 (srp_dng). Needs no context.
// `rgb` must be a direct buffer with at least width*height*3 floats;
// `make`/`model` are optional (null ok) ASCII tags 271/272. Returns the
// srp::DngStatus code as an int.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeWriteDng(
    JNIEnv* env, jclass /*cls*/, jstring path, jint width, jint height,
    jobject rgb, jstring make, jstring model) {
  if (path == nullptr || rgb == nullptr || width <= 0 || height <= 0) {
    return static_cast<jint>(srp::DngStatus::kBadArgument);
  }
  float* rgb_ptr = static_cast<float*>(env->GetDirectBufferAddress(rgb));
  const jlong rgb_cap = env->GetDirectBufferCapacity(rgb);
  const int64_t want =
      static_cast<int64_t>(width) * static_cast<int64_t>(height) * 3;
  if (rgb_ptr == nullptr || want <= 0 || rgb_cap < want * 4) {
    return static_cast<jint>(srp::DngStatus::kBadArgument);
  }

  const char* path_chars = env->GetStringUTFChars(path, nullptr);
  if (path_chars == nullptr) {
    return static_cast<jint>(srp::DngStatus::kBadArgument);
  }
  std::string path_str(path_chars);
  env->ReleaseStringUTFChars(path, path_chars);

  std::string make_str, model_str;
  if (make != nullptr) {
    const char* chars = env->GetStringUTFChars(make, nullptr);
    if (chars == nullptr) return static_cast<jint>(srp::DngStatus::kBadArgument);
    make_str = chars;
    env->ReleaseStringUTFChars(make, chars);
  }
  if (model != nullptr) {
    const char* chars = env->GetStringUTFChars(model, nullptr);
    if (chars == nullptr) return static_cast<jint>(srp::DngStatus::kBadArgument);
    model_str = chars;
    env->ReleaseStringUTFChars(model, chars);
  }

  srp::DngField tags[2];
  size_t tag_count = 0;
  if (!make_str.empty()) {
    tags[tag_count].tag = 271;  // Make
    tags[tag_count].type = 2;   // ASCII
    tags[tag_count].count = static_cast<uint32_t>(make_str.size() + 1);
    tags[tag_count].payload =
        reinterpret_cast<const uint8_t*>(make_str.c_str());
    tags[tag_count].payload_size = make_str.size() + 1;
    ++tag_count;
  }
  if (!model_str.empty()) {
    tags[tag_count].tag = 272;  // Model
    tags[tag_count].type = 2;   // ASCII
    tags[tag_count].count = static_cast<uint32_t>(model_str.size() + 1);
    tags[tag_count].payload =
        reinterpret_cast<const uint8_t*>(model_str.c_str());
    tags[tag_count].payload_size = model_str.size() + 1;
    ++tag_count;
  }

  std::string error;
  const srp::DngStatus status = srp::DngWriteLinearRgb16(
      path_str.c_str(), static_cast<uint32_t>(width),
      static_cast<uint32_t>(height), rgb_ptr, tags, tag_count, nullptr,
      &error);
  return static_cast<jint>(status);
}

// Full-profile write: `matrix` (9, nullable), `neutral` (3, nullable) and
// `illuminant` ride DngColorMeta; make/model ride the ASCII tags as above.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeDngOrientation(
    JNIEnv* env, jclass /*cls*/, jstring path) {
  if (path == nullptr) return 0;
  const char* cpath = env->GetStringUTFChars(path, nullptr);
  if (cpath == nullptr) return 0;
  int orientation = 0;
  const srp::DngReadStatus status =
      srp::SrpReadDngOrientation(cpath, &orientation);
  env->ReleaseStringUTFChars(path, cpath);
  if (status != srp::DngReadStatus::kOk) return 0;
  return static_cast<jint>(orientation);
}

JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeWriteDngColor(
    JNIEnv* env, jclass /*cls*/, jstring path, jint width, jint height,
    jobject rgb, jstring make, jstring model, jdoubleArray matrix,
    jdoubleArray neutral, jint illuminant, jint orientation) {
  if (path == nullptr || rgb == nullptr || width <= 0 || height <= 0) {
    return static_cast<jint>(srp::DngStatus::kBadArgument);
  }
  float* rgb_ptr = static_cast<float*>(env->GetDirectBufferAddress(rgb));
  const jlong rgb_cap = env->GetDirectBufferCapacity(rgb);
  const int64_t want =
      static_cast<int64_t>(width) * static_cast<int64_t>(height) * 3;
  if (rgb_ptr == nullptr || want <= 0 || rgb_cap < want * 4) {
    return static_cast<jint>(srp::DngStatus::kBadArgument);
  }

  const char* path_chars = env->GetStringUTFChars(path, nullptr);
  if (path_chars == nullptr) {
    return static_cast<jint>(srp::DngStatus::kBadArgument);
  }
  std::string path_str(path_chars);
  env->ReleaseStringUTFChars(path, path_chars);

  std::string make_str, model_str;
  if (make != nullptr) {
    const char* chars = env->GetStringUTFChars(make, nullptr);
    if (chars == nullptr) return static_cast<jint>(srp::DngStatus::kBadArgument);
    make_str = chars;
    env->ReleaseStringUTFChars(make, chars);
  }
  if (model != nullptr) {
    const char* chars = env->GetStringUTFChars(model, nullptr);
    if (chars == nullptr) return static_cast<jint>(srp::DngStatus::kBadArgument);
    model_str = chars;
    env->ReleaseStringUTFChars(model, chars);
  }

  srp::DngColorMeta color{};
  if (matrix != nullptr) {
    if (env->GetArrayLength(matrix) != 9) {
      return static_cast<jint>(srp::DngStatus::kBadArgument);
    }
    env->GetDoubleArrayRegion(matrix, 0, 9, color.color_matrix1);
    color.has_color_matrix1 = 1;
  }
  if (neutral != nullptr) {
    if (env->GetArrayLength(neutral) != 3) {
      return static_cast<jint>(srp::DngStatus::kBadArgument);
    }
    env->GetDoubleArrayRegion(neutral, 0, 3, color.as_shot_neutral);
    color.has_as_shot_neutral = 1;
  }
  color.calibration_illuminant1 = static_cast<uint16_t>(illuminant);

  srp::DngField tags[3];
  size_t tag_count = 0;
  if (!make_str.empty()) {
    tags[tag_count].tag = 271;  // Make
    tags[tag_count].type = 2;   // ASCII
    tags[tag_count].count = static_cast<uint32_t>(make_str.size() + 1);
    tags[tag_count].payload =
        reinterpret_cast<const uint8_t*>(make_str.c_str());
    tags[tag_count].payload_size = make_str.size() + 1;
    ++tag_count;
  }
  if (!model_str.empty()) {
    tags[tag_count].tag = 272;  // Model
    tags[tag_count].type = 2;   // ASCII
    tags[tag_count].count = static_cast<uint32_t>(model_str.size() + 1);
    tags[tag_count].payload =
        reinterpret_cast<const uint8_t*>(model_str.c_str());
    tags[tag_count].payload_size = model_str.size() + 1;
    ++tag_count;
  }
  // Display orientation passthrough (EXIF 274, SHORT): lets raw editors
  // show the linear output exactly like the finished PNG. Omitted when
  // unknown so files match the previous default.
  uint16_t orientation_value = 0;
  if (orientation >= 1 && orientation <= 8) {
    orientation_value = static_cast<uint16_t>(orientation);
    tags[tag_count].tag = 274;  // Orientation
    tags[tag_count].type = 3;   // SHORT
    tags[tag_count].count = 1;
    tags[tag_count].payload =
        reinterpret_cast<const uint8_t*>(&orientation_value);
    tags[tag_count].payload_size = sizeof(orientation_value);
    ++tag_count;
  }

  std::string error;
  const srp::DngStatus status = srp::DngWriteLinearRgb16(
      path_str.c_str(), static_cast<uint32_t>(width),
      static_cast<uint32_t>(height), rgb_ptr, tags, tag_count, &color,
      &error);
  return static_cast<jint>(status);
}

// Real GPU path: GAT (op 0) / decimate (op 1) over caller-owned direct
// buffers. `in_buffer` holds width*height full-res floats; `out_buffer`
// receives count floats (== width*height for op 0, half each dim for op 1);
// `ab_buffer` holds 8 floats (alpha[0:4], beta[0:4], EXIF plane order;
// zeros when op == 1). `spirv` holds the compiled shaders/gat.comp module.
// Stages through host-visible VkBuffers, dispatches ceil(count / 256)
// groups with the 128 B HmsrPush block, fence-waits, and copies the output
// back into `out_buffer`. Returns the srp::Status code as an int.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeGat(
    JNIEnv* env, jclass /*cls*/, jlong handle, jobject in_buffer,
    jobject out_buffer, jobject ab_buffer, jbyteArray spirv, jint width,
    jint height, jint count, jint op) {
  auto* ctx = reinterpret_cast<srp::Context*>(static_cast<intptr_t>(handle));
  if (ctx == nullptr || in_buffer == nullptr || out_buffer == nullptr ||
      ab_buffer == nullptr || spirv == nullptr || width <= 0 ||
      height <= 0 || count <= 0 || (op != 0 && op != 1)) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkDevice device = srp::NativeDevice(ctx);
  VkPhysicalDevice physical = srp::NativePhysicalDevice(ctx);
  if (device == VK_NULL_HANDLE || physical == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kNoDevice);
  }

  float* in_ptr = static_cast<float*>(env->GetDirectBufferAddress(in_buffer));
  float* out_ptr = static_cast<float*>(env->GetDirectBufferAddress(out_buffer));
  float* ab_ptr = static_cast<float*>(env->GetDirectBufferAddress(ab_buffer));
  const jlong in_cap = env->GetDirectBufferCapacity(in_buffer);
  const jlong out_cap = env->GetDirectBufferCapacity(out_buffer);
  const jlong ab_cap = env->GetDirectBufferCapacity(ab_buffer);
  const VkDeviceSize in_bytes =
      static_cast<VkDeviceSize>(width) * static_cast<VkDeviceSize>(height) * 4;
  const VkDeviceSize out_bytes = static_cast<VkDeviceSize>(count) * 4;
  if (in_ptr == nullptr || out_ptr == nullptr || ab_ptr == nullptr ||
      in_cap < static_cast<jlong>(in_bytes) ||
      out_cap < static_cast<jlong>(out_bytes) || ab_cap < 32) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }

  const jsize spirv_len = env->GetArrayLength(spirv);
  if (spirv_len <= 0 || spirv_len % 4 != 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  std::vector<uint8_t> spirv_bytes(static_cast<size_t>(spirv_len));
  env->GetByteArrayRegion(spirv, 0, spirv_len, reinterpret_cast<jbyte*>(spirv_bytes.data()));
  if (env->ExceptionCheck() == JNI_TRUE) return static_cast<jint>(srp::Status::kInitFailed);

  // Context-interned stage module (created once per process, owned by
  // the Context; replaces per-call create/destroy).

  VkShaderModule module =
      srp::SrpInternModule(ctx, spirv_bytes.data(), spirv_bytes.size());
  if (module == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kDispatchFailed);
  }

  VkBuffer in_vk = VK_NULL_HANDLE;
  VkDeviceMemory in_mem = VK_NULL_HANDLE;
  VkBuffer out_vk = VK_NULL_HANDLE;
  VkDeviceMemory out_mem = VK_NULL_HANDLE;
  VkBuffer ab_vk = VK_NULL_HANDLE;
  VkDeviceMemory ab_mem = VK_NULL_HANDLE;
  srp::Status status = srp::Status::kDispatchFailed;
  if (MakeStagingBuffer(device, physical, in_bytes, in_ptr, &in_vk, &in_mem) &&
      MakeStagingBuffer(device, physical, out_bytes, nullptr, &out_vk, &out_mem) &&
      MakeStagingBuffer(device, physical, 32, ab_ptr, &ab_vk, &ab_mem)) {
    status = srp::SrpGatGpu(ctx, static_cast<uint32_t>(width),
                            static_cast<uint32_t>(height),
                            static_cast<uint32_t>(count),
                            static_cast<uint32_t>(op), in_vk, out_vk, ab_vk,
                            module);
    if (status == srp::Status::kOk) {
      void* mapped = nullptr;
      if (vkMapMemory(device, out_mem, 0, out_bytes, 0, &mapped) == VK_SUCCESS) {
        std::memcpy(out_ptr, mapped, static_cast<size_t>(out_bytes));
        vkUnmapMemory(device, out_mem);
      } else {
        status = srp::Status::kDispatchFailed;
      }
    }
  }

  if (in_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, in_vk, nullptr);
  if (in_mem != VK_NULL_HANDLE) vkFreeMemory(device, in_mem, nullptr);
  if (out_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, out_vk, nullptr);
  if (out_mem != VK_NULL_HANDLE) vkFreeMemory(device, out_mem, nullptr);
  if (ab_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, ab_vk, nullptr);
  if (ab_mem != VK_NULL_HANDLE) vkFreeMemory(device, ab_mem, nullptr);
  // (module stays alive in the Context cache)
  return static_cast<jint>(status);
}

// Real GPU path: per-level L1 refinement over caller-owned direct buffers.
// `ref_buffer`/`mov_buffer` hold width*height level floats each;
// `flow_buffer` holds tile_count*2 floats ((nty,ntx,2) flattened, staged
// with the current flow and read back refined). `spirv` holds the compiled
// shaders/align.comp module. Dispatches ceil(tile_count / 256) groups with
// the 128 B HmsrPush block, fence-waits, and copies the flow back into
// `flow_buffer`. Returns the srp::Status code as an int.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeAlignL1(
    JNIEnv* env, jclass /*cls*/, jlong handle, jobject ref_buffer,
    jobject mov_buffer, jobject flow_buffer, jbyteArray spirv, jint width,
    jint height, jint tile_count, jint tile_size, jint search_radius,
    jint ntx) {
  auto* ctx = reinterpret_cast<srp::Context*>(static_cast<intptr_t>(handle));
  if (ctx == nullptr || ref_buffer == nullptr || mov_buffer == nullptr ||
      flow_buffer == nullptr || spirv == nullptr || width <= 0 ||
      height <= 0 || tile_count <= 0 || tile_size <= 0 || ntx <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkDevice device = srp::NativeDevice(ctx);
  VkPhysicalDevice physical = srp::NativePhysicalDevice(ctx);
  if (device == VK_NULL_HANDLE || physical == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kNoDevice);
  }

  float* ref_ptr = static_cast<float*>(env->GetDirectBufferAddress(ref_buffer));
  float* mov_ptr = static_cast<float*>(env->GetDirectBufferAddress(mov_buffer));
  float* flow_ptr = static_cast<float*>(env->GetDirectBufferAddress(flow_buffer));
  const jlong ref_cap = env->GetDirectBufferCapacity(ref_buffer);
  const jlong mov_cap = env->GetDirectBufferCapacity(mov_buffer);
  const jlong flow_cap = env->GetDirectBufferCapacity(flow_buffer);
  const VkDeviceSize img_bytes =
      static_cast<VkDeviceSize>(width) * static_cast<VkDeviceSize>(height) * 4;
  const VkDeviceSize flow_bytes = static_cast<VkDeviceSize>(tile_count) * 2 * 4;
  if (ref_ptr == nullptr || mov_ptr == nullptr || flow_ptr == nullptr ||
      ref_cap < static_cast<jlong>(img_bytes) ||
      mov_cap < static_cast<jlong>(img_bytes) ||
      flow_cap < static_cast<jlong>(flow_bytes)) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }

  const jsize spirv_len = env->GetArrayLength(spirv);
  if (spirv_len <= 0 || spirv_len % 4 != 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  std::vector<uint8_t> spirv_bytes(static_cast<size_t>(spirv_len));
  env->GetByteArrayRegion(spirv, 0, spirv_len, reinterpret_cast<jbyte*>(spirv_bytes.data()));
  if (env->ExceptionCheck() == JNI_TRUE) return static_cast<jint>(srp::Status::kInitFailed);

  // Context-interned stage module (created once per process, owned by
  // the Context; replaces per-call create/destroy).

  VkShaderModule module =
      srp::SrpInternModule(ctx, spirv_bytes.data(), spirv_bytes.size());
  if (module == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kDispatchFailed);
  }

  VkBuffer ref_vk = VK_NULL_HANDLE;
  VkDeviceMemory ref_mem = VK_NULL_HANDLE;
  VkBuffer mov_vk = VK_NULL_HANDLE;
  VkDeviceMemory mov_mem = VK_NULL_HANDLE;
  VkBuffer flow_vk = VK_NULL_HANDLE;
  VkDeviceMemory flow_mem = VK_NULL_HANDLE;
  srp::Status status = srp::Status::kDispatchFailed;
  if (MakeStagingBuffer(device, physical, img_bytes, ref_ptr, &ref_vk, &ref_mem) &&
      MakeStagingBuffer(device, physical, img_bytes, mov_ptr, &mov_vk, &mov_mem) &&
      MakeStagingBuffer(device, physical, flow_bytes, flow_ptr, &flow_vk, &flow_mem)) {
    status = srp::SrpAlignL1Gpu(ctx, static_cast<uint32_t>(width),
                               static_cast<uint32_t>(height),
                               static_cast<uint32_t>(tile_count),
                               static_cast<uint32_t>(tile_size),
                               static_cast<uint32_t>(search_radius),
                               static_cast<uint32_t>(ntx), ref_vk, mov_vk,
                               flow_vk, module);
    if (status == srp::Status::kOk) {
      void* mapped = nullptr;
      if (vkMapMemory(device, flow_mem, 0, flow_bytes, 0, &mapped) == VK_SUCCESS) {
        std::memcpy(flow_ptr, mapped, static_cast<size_t>(flow_bytes));
        vkUnmapMemory(device, flow_mem);
      } else {
        status = srp::Status::kDispatchFailed;
      }
    }
  }

  if (ref_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, ref_vk, nullptr);
  if (ref_mem != VK_NULL_HANDLE) vkFreeMemory(device, ref_mem, nullptr);
  if (mov_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, mov_vk, nullptr);
  if (mov_mem != VK_NULL_HANDLE) vkFreeMemory(device, mov_mem, nullptr);
  if (flow_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, flow_vk, nullptr);
  if (flow_mem != VK_NULL_HANDLE) vkFreeMemory(device, flow_mem, nullptr);
  // (module stays alive in the Context cache)
  return static_cast<jint>(status);
}

// Real GPU path: per-level L1 refinement with a device-resident
// reference image. `ref_res` is a resident of width*height floats;
// `mov_buffer`/`flow_buffer` are staged (per-frame data), flow read back
// refined in place like the staged path. Zero ref traffic per frame.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeAlignL1Res(
    JNIEnv* env, jclass /*cls*/, jlong handle, jlong ref_res,
    jobject mov_buffer, jobject flow_buffer, jbyteArray spirv, jint width,
    jint height, jint tile_count, jint tile_size, jint search_radius,
    jint ntx) {
  auto* ctx = reinterpret_cast<srp::Context*>(static_cast<intptr_t>(handle));
  if (ctx == nullptr || mov_buffer == nullptr || flow_buffer == nullptr ||
      spirv == nullptr || width <= 0 || height <= 0 || tile_count <= 0 ||
      tile_size <= 0 || ntx <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkDevice device = srp::NativeDevice(ctx);
  VkPhysicalDevice physical = srp::NativePhysicalDevice(ctx);
  if (device == VK_NULL_HANDLE || physical == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kNoDevice);
  }
  float* mov_ptr = static_cast<float*>(env->GetDirectBufferAddress(mov_buffer));
  float* flow_ptr = static_cast<float*>(env->GetDirectBufferAddress(flow_buffer));
  const VkDeviceSize img_bytes =
      static_cast<VkDeviceSize>(width) * static_cast<VkDeviceSize>(height) * 4;
  const VkDeviceSize flow_bytes = static_cast<VkDeviceSize>(tile_count) * 2 * 4;
  if (mov_ptr == nullptr || flow_ptr == nullptr ||
      env->GetDirectBufferCapacity(mov_buffer) < static_cast<jlong>(img_bytes) ||
      env->GetDirectBufferCapacity(flow_buffer) < static_cast<jlong>(flow_bytes)) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkBuffer ref_vk = VK_NULL_HANDLE;
  if (srp::SrpResidentBuffer(ctx, ref_res,
                             static_cast<size_t>(width) * height,
                             &ref_vk) != srp::Status::kOk) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkShaderModule module = VK_NULL_HANDLE;
  if (!InternSpirv(env, ctx, spirv, &module)) {
    return static_cast<jint>(srp::Status::kDispatchFailed);
  }
  VkBuffer mov_vk = VK_NULL_HANDLE;
  VkDeviceMemory mov_mem = VK_NULL_HANDLE;
  VkBuffer flow_vk = VK_NULL_HANDLE;
  VkDeviceMemory flow_mem = VK_NULL_HANDLE;
  srp::Status status = srp::Status::kDispatchFailed;
  if (MakeStagingBuffer(device, physical, img_bytes, mov_ptr, &mov_vk, &mov_mem) &&
      MakeStagingBuffer(device, physical, flow_bytes, flow_ptr, &flow_vk, &flow_mem)) {
    status = srp::SrpAlignL1Gpu(ctx, static_cast<uint32_t>(width),
                               static_cast<uint32_t>(height),
                               static_cast<uint32_t>(tile_count),
                               static_cast<uint32_t>(tile_size),
                               static_cast<uint32_t>(search_radius),
                               static_cast<uint32_t>(ntx), ref_vk, mov_vk,
                               flow_vk, module);
    if (status == srp::Status::kOk) {
      void* mapped = nullptr;
      if (vkMapMemory(device, flow_mem, 0, flow_bytes, 0, &mapped) == VK_SUCCESS) {
        std::memcpy(flow_ptr, mapped, static_cast<size_t>(flow_bytes));
        vkUnmapMemory(device, flow_mem);
      } else {
        status = srp::Status::kDispatchFailed;
      }
    }
  }
  if (mov_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, mov_vk, nullptr);
  if (mov_mem != VK_NULL_HANDLE) vkFreeMemory(device, mov_mem, nullptr);
  if (flow_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, flow_vk, nullptr);
  if (flow_mem != VK_NULL_HANDLE) vkFreeMemory(device, flow_mem, nullptr);
  // (module stays alive in the Context cache; the resident stays on-device)
  return static_cast<jint>(status);
}

// Shared staging helper for the corr entries: host-visible buffers for A
// (filled), B (filled), C (zeroed, out_count floats), plus a shader module
// from `spirv`. On success runs `dispatch` and reads C back into
// `c_ptr`. Returns the srp::Status code as an int.
namespace {

srp::Status StageAndRunCorr(JNIEnv* env, srp::Context* ctx, VkDevice device,
                           VkPhysicalDevice physical, jobject a_buffer,
                           jobject b_buffer, jobject c_buffer,
                           int64_t a_count, int64_t b_count, int64_t c_count,
                           jbyteArray spirv,
                           const std::function<srp::Status(
                               VkBuffer, VkBuffer, VkBuffer, VkShaderModule)>&
                               dispatch) {
  float* a_ptr = static_cast<float*>(env->GetDirectBufferAddress(a_buffer));
  float* b_ptr = static_cast<float*>(env->GetDirectBufferAddress(b_buffer));
  float* c_ptr = static_cast<float*>(env->GetDirectBufferAddress(c_buffer));
  if (a_ptr == nullptr || b_ptr == nullptr || c_ptr == nullptr ||
      env->GetDirectBufferCapacity(a_buffer) < a_count * 4 ||
      env->GetDirectBufferCapacity(b_buffer) < b_count * 4 ||
      env->GetDirectBufferCapacity(c_buffer) < c_count * 4) {
    return srp::Status::kInitFailed;
  }
  const jsize spirv_len = env->GetArrayLength(spirv);
  if (spirv_len <= 0 || spirv_len % 4 != 0) {
    return srp::Status::kInitFailed;
  }
  std::vector<uint8_t> spirv_bytes(static_cast<size_t>(spirv_len));
  env->GetByteArrayRegion(spirv, 0, spirv_len,
                          reinterpret_cast<jbyte*>(spirv_bytes.data()));
  if (env->ExceptionCheck() == JNI_TRUE) {
    return srp::Status::kInitFailed;
  }
  // Context-interned stage module (created once per process, owned by
  // the Context; replaces per-call create/destroy).
  VkShaderModule module =
      srp::SrpInternModule(ctx, spirv_bytes.data(), spirv_bytes.size());
  if (module == VK_NULL_HANDLE) {
    return srp::Status::kDispatchFailed;
  }
  VkBuffer a_vk = VK_NULL_HANDLE, b_vk = VK_NULL_HANDLE,
           c_vk = VK_NULL_HANDLE;
  VkDeviceMemory a_mem = VK_NULL_HANDLE, b_mem = VK_NULL_HANDLE,
                 c_mem = VK_NULL_HANDLE;
  srp::Status status = srp::Status::kDispatchFailed;
  if (MakeStagingBuffer(device, physical,
                        static_cast<VkDeviceSize>(a_count) * 4, a_ptr, &a_vk,
                        &a_mem) &&
      MakeStagingBuffer(device, physical,
                        static_cast<VkDeviceSize>(b_count) * 4, b_ptr, &b_vk,
                        &b_mem) &&
      MakeStagingBuffer(device, physical,
                        static_cast<VkDeviceSize>(c_count) * 4, nullptr, &c_vk,
                        &c_mem)) {
    status = dispatch(a_vk, b_vk, c_vk, module);
    if (status == srp::Status::kOk) {
      void* mapped = nullptr;
      if (vkMapMemory(device, c_mem, 0,
                      static_cast<VkDeviceSize>(c_count) * 4, 0, &mapped) ==
          VK_SUCCESS) {
        std::memcpy(c_ptr, mapped, static_cast<size_t>(c_count) * 4);
        vkUnmapMemory(device, c_mem);
      } else {
        status = srp::Status::kDispatchFailed;
      }
    }
  }
  if (a_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, a_vk, nullptr);
  if (a_mem != VK_NULL_HANDLE) vkFreeMemory(device, a_mem, nullptr);
  if (b_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, b_vk, nullptr);
  if (b_mem != VK_NULL_HANDLE) vkFreeMemory(device, b_mem, nullptr);
  if (c_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, c_vk, nullptr);
  if (c_mem != VK_NULL_HANDLE) vkFreeMemory(device, c_mem, nullptr);
  // (module stays alive in the Context cache)
  return status;
}

// Interns one SPIR-V module in the Context cache (same sequence as the
// staged entries, factored for entries needing two modules).
bool InternSpirv(JNIEnv* env, srp::Context* ctx, jbyteArray spirv,
                 VkShaderModule* out_module) {
  const jsize spirv_len = env->GetArrayLength(spirv);
  if (spirv_len <= 0 || spirv_len % 4 != 0) return false;
  std::vector<uint8_t> spirv_bytes(static_cast<size_t>(spirv_len));
  env->GetByteArrayRegion(spirv, 0, spirv_len,
                          reinterpret_cast<jbyte*>(spirv_bytes.data()));
  if (env->ExceptionCheck() == JNI_TRUE) return false;
  *out_module =
      srp::SrpInternModule(ctx, spirv_bytes.data(), spirv_bytes.size());
  return *out_module != VK_NULL_HANDLE;
}

srp::Context* CorrContext(jlong handle, VkDevice* device,
                        VkPhysicalDevice* physical) {
  auto* ctx = reinterpret_cast<srp::Context*>(static_cast<intptr_t>(handle));
  if (ctx == nullptr) return nullptr;
  *device = srp::NativeDevice(ctx);
  *physical = srp::NativePhysicalDevice(ctx);
  if (*device == VK_NULL_HANDLE || *physical == VK_NULL_HANDLE) return nullptr;
  return ctx;
}

// Six-buffer staging variant (all six filled from direct buffers, F read
// back) for in/out entries like ICA refine. Mirrors StageAndRunCorr.
srp::Status StageAndRun6(JNIEnv* env, srp::Context* ctx, VkDevice device, VkPhysicalDevice physical,
                         jobject a_buffer, jobject b_buffer, jobject c_buffer,
                         jobject d_buffer, jobject e_buffer, jobject f_buffer,
                         int64_t abcd_count, int64_t e_count, int64_t f_count,
                         jbyteArray spirv,
                         const std::function<srp::Status(
                             VkBuffer, VkBuffer, VkBuffer, VkBuffer, VkBuffer,
                             VkBuffer, VkShaderModule)>& dispatch) {
  float* ptrs[6] = {
      static_cast<float*>(env->GetDirectBufferAddress(a_buffer)),
      static_cast<float*>(env->GetDirectBufferAddress(b_buffer)),
      static_cast<float*>(env->GetDirectBufferAddress(c_buffer)),
      static_cast<float*>(env->GetDirectBufferAddress(d_buffer)),
      static_cast<float*>(env->GetDirectBufferAddress(e_buffer)),
      static_cast<float*>(env->GetDirectBufferAddress(f_buffer))};
  const int64_t counts[6] = {abcd_count, abcd_count, abcd_count, abcd_count,
                             e_count, f_count};
  for (int i = 0; i < 6; ++i) {
    if (ptrs[i] == nullptr) return srp::Status::kInitFailed;
  }
  jobject bufs[6] = {a_buffer, b_buffer, c_buffer,
                     d_buffer, e_buffer, f_buffer};
  for (int i = 0; i < 6; ++i) {
    if (env->GetDirectBufferCapacity(bufs[i]) < counts[i] * 4) {
      return srp::Status::kInitFailed;
    }
  }
  const jsize spirv_len = env->GetArrayLength(spirv);
  if (spirv_len <= 0 || spirv_len % 4 != 0) {
    return srp::Status::kInitFailed;
  }
  std::vector<uint8_t> spirv_bytes(static_cast<size_t>(spirv_len));
  env->GetByteArrayRegion(spirv, 0, spirv_len,
                          reinterpret_cast<jbyte*>(spirv_bytes.data()));
  if (env->ExceptionCheck() == JNI_TRUE) {
    return srp::Status::kInitFailed;
  }
  // Context-interned stage module (created once per process, owned by
  // the Context; replaces per-call create/destroy).
  VkShaderModule module =
      srp::SrpInternModule(ctx, spirv_bytes.data(), spirv_bytes.size());
  if (module == VK_NULL_HANDLE) {
    return srp::Status::kDispatchFailed;
  }
  VkBuffer vks[6] = {};
  VkDeviceMemory mems[6] = {};
  srp::Status status = srp::Status::kDispatchFailed;
  bool staged = true;
  for (int i = 0; i < 6; ++i) {
    if (!MakeStagingBuffer(device, physical,
                           static_cast<VkDeviceSize>(counts[i]) * 4, ptrs[i],
                           &vks[i], &mems[i])) {
      staged = false;
      break;
    }
  }
  if (staged) {
    status = dispatch(vks[0], vks[1], vks[2], vks[3], vks[4], vks[5], module);
    if (status == srp::Status::kOk) {
      void* mapped = nullptr;
      if (vkMapMemory(device, mems[5], 0,
                      static_cast<VkDeviceSize>(counts[5]) * 4, 0, &mapped) ==
          VK_SUCCESS) {
        std::memcpy(ptrs[5], mapped, static_cast<size_t>(counts[5]) * 4);
        vkUnmapMemory(device, mems[5]);
      } else {
        status = srp::Status::kDispatchFailed;
      }
    }
  }
  for (int i = 0; i < 6; ++i) {
    if (vks[i] != VK_NULL_HANDLE) vkDestroyBuffer(device, vks[i], nullptr);
    if (mems[i] != VK_NULL_HANDLE) vkFreeMemory(device, mems[i], nullptr);
  }
  // (module stays alive in the Context cache)
  return status;
}

// Two-buffer staging variant (a staged in, b read back) for single
// input/output entries like demosaic. Mirrors StageAndRunCorr.
srp::Status StageAndRun2(JNIEnv* env, srp::Context* ctx, VkDevice device, VkPhysicalDevice physical,
                         jobject a_buffer, jobject b_buffer, int64_t count,
                         jbyteArray spirv,
                         const std::function<srp::Status(VkBuffer, VkBuffer,
                                                         VkShaderModule)>&
                             dispatch) {
  float* a_ptr = static_cast<float*>(env->GetDirectBufferAddress(a_buffer));
  float* b_ptr = static_cast<float*>(env->GetDirectBufferAddress(b_buffer));
  if (a_ptr == nullptr || b_ptr == nullptr ||
      env->GetDirectBufferCapacity(a_buffer) < count * 4 ||
      env->GetDirectBufferCapacity(b_buffer) < count * 4) {
    return srp::Status::kInitFailed;
  }
  const jsize spirv_len = env->GetArrayLength(spirv);
  if (spirv_len <= 0 || spirv_len % 4 != 0) {
    return srp::Status::kInitFailed;
  }
  std::vector<uint8_t> spirv_bytes(static_cast<size_t>(spirv_len));
  env->GetByteArrayRegion(spirv, 0, spirv_len,
                          reinterpret_cast<jbyte*>(spirv_bytes.data()));
  if (env->ExceptionCheck() == JNI_TRUE) {
    return srp::Status::kInitFailed;
  }
  // Context-interned stage module (created once per process, owned by
  // the Context; replaces per-call create/destroy).
  VkShaderModule module =
      srp::SrpInternModule(ctx, spirv_bytes.data(), spirv_bytes.size());
  if (module == VK_NULL_HANDLE) {
    return srp::Status::kDispatchFailed;
  }
  VkBuffer a_vk = VK_NULL_HANDLE, b_vk = VK_NULL_HANDLE;
  VkDeviceMemory a_mem = VK_NULL_HANDLE, b_mem = VK_NULL_HANDLE;
  srp::Status status = srp::Status::kDispatchFailed;
  if (MakeStagingBuffer(device, physical,
                        static_cast<VkDeviceSize>(count) * 4, a_ptr, &a_vk,
                        &a_mem) &&
      MakeStagingBuffer(device, physical,
                        static_cast<VkDeviceSize>(count) * 4, nullptr, &b_vk,
                        &b_mem)) {
    status = dispatch(a_vk, b_vk, module);
    if (status == srp::Status::kOk) {
      void* mapped = nullptr;
      if (vkMapMemory(device, b_mem, 0,
                      static_cast<VkDeviceSize>(count) * 4, 0, &mapped) ==
          VK_SUCCESS) {
        std::memcpy(b_ptr, mapped, static_cast<size_t>(count) * 4);
        vkUnmapMemory(device, b_mem);
      } else {
        status = srp::Status::kDispatchFailed;
      }
    }
  }
  if (a_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, a_vk, nullptr);
  if (a_mem != VK_NULL_HANDLE) vkFreeMemory(device, a_mem, nullptr);
  if (b_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, b_vk, nullptr);
  if (b_mem != VK_NULL_HANDLE) vkFreeMemory(device, b_mem, nullptr);
  // (module stays alive in the Context cache)
  return status;
}

}  // namespace

// Real GPU path: align-L2 patch extraction (corr.comp op 0) over
// caller-owned direct buffers. `mov_buffer` holds height*width floats,
// `flow_buffer` holds tile_count*2 floats, `patch_buffer` receives
// tile_count*S*S floats (S = tile_size + 2*radius). Returns srp::Status.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeCorrExtract(
    JNIEnv* env, jclass /*cls*/, jlong handle, jobject mov_buffer,
    jobject flow_buffer, jobject patch_buffer, jbyteArray spirv, jint height,
    jint width, jint tile_count, jint tile_size, jint radius, jint ntx) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || mov_buffer == nullptr || flow_buffer == nullptr ||
      patch_buffer == nullptr || spirv == nullptr || height <= 0 ||
      width <= 0 || tile_count <= 0 || tile_size <= 0 || ntx <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const int64_t s = static_cast<int64_t>(tile_size) + 2 * radius;
  const int64_t a_count = static_cast<int64_t>(height) * width;
  const int64_t b_count = static_cast<int64_t>(tile_count) * 2;
  const int64_t c_count = static_cast<int64_t>(tile_count) * s * s;
  const srp::Status status = StageAndRunCorr(
      env, ctx, device, physical, mov_buffer, flow_buffer, patch_buffer, a_count,
      b_count, c_count, spirv,
      [&](VkBuffer a, VkBuffer b, VkBuffer c, VkShaderModule module) {
        return srp::SrpCorrExtractGpu(
            ctx, static_cast<uint32_t>(height), static_cast<uint32_t>(width),
            static_cast<uint32_t>(tile_count), static_cast<uint32_t>(tile_size),
            static_cast<uint32_t>(radius), static_cast<uint32_t>(ntx), a, b, c,
            module);
      });
  return static_cast<jint>(status);
}

// Real GPU path: align-L2 box-energy argmin (corr.comp op 1) over
// caller-owned direct buffers. `patch_buffer` holds patch_count floats,
// `corr_buffer` holds corr_count floats, `dflow_buffer` receives
// tile_count*2 winning integer shifts. Returns srp::Status.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeCorrArgmin(
    JNIEnv* env, jclass /*cls*/, jlong handle, jobject patch_buffer,
    jobject corr_buffer, jobject dflow_buffer, jbyteArray spirv,
    jint tile_count, jint tile_size, jint radius, jint ntx, jint patch_count,
    jint corr_count) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || patch_buffer == nullptr || corr_buffer == nullptr ||
      dflow_buffer == nullptr || spirv == nullptr || tile_count <= 0 ||
      tile_size <= 0 || ntx <= 0 || patch_count <= 0 || corr_count <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const int64_t c_count = static_cast<int64_t>(tile_count) * 2;
  const srp::Status status = StageAndRunCorr(
      env, ctx, device, physical, patch_buffer, corr_buffer, dflow_buffer,
      patch_count, corr_count, c_count, spirv,
      [&](VkBuffer a, VkBuffer b, VkBuffer c, VkShaderModule module) {
        return srp::SrpCorrArgminGpu(
            ctx, static_cast<uint32_t>(tile_count),
            static_cast<uint32_t>(tile_size), static_cast<uint32_t>(radius),
            static_cast<uint32_t>(ntx), static_cast<uint32_t>(patch_count),
            static_cast<uint32_t>(corr_count), a, b, c, module);
      });
  return static_cast<jint>(status);
}

// Real GPU path: centre C×C crop of full S×S corr maps (corr.comp op 2).
// `full_buffer` holds tile_count*S*S floats, `crop_buffer` receives
// tile_count*C*C floats. `dummy_buffer` is any direct buffer holding at
// least 1 float (contents ignored): op 2 never reads binding 1, but the
// shared staging helper uploads all three. Returns srp::Status.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeCorrCrop(
    JNIEnv* env, jclass /*cls*/, jlong handle, jobject full_buffer,
    jobject crop_buffer, jobject dummy_buffer, jbyteArray spirv,
    jint tile_count, jint tile_size, jint radius, jint ntx) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || full_buffer == nullptr || crop_buffer == nullptr ||
      dummy_buffer == nullptr || spirv == nullptr || tile_count <= 0 ||
      tile_size <= 0 || ntx <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const int64_t s = static_cast<int64_t>(tile_size) + 2 * radius;
  const int64_t c = 2 * static_cast<int64_t>(radius) + 1;
  const int64_t a_count = static_cast<int64_t>(tile_count) * s * s;
  const int64_t c_count = static_cast<int64_t>(tile_count) * c * c;
  const srp::Status status = StageAndRunCorr(
      env, ctx, device, physical, full_buffer, dummy_buffer, crop_buffer,
      a_count, 1, c_count, spirv,
      [&](VkBuffer a, VkBuffer b, VkBuffer c, VkShaderModule module) {
        (void)b;
        return srp::SrpCorrCropGpu(
            ctx, static_cast<uint32_t>(tile_count),
            static_cast<uint32_t>(tile_size), static_cast<uint32_t>(radius),
            static_cast<uint32_t>(ntx), a, c, module);
      });
  return static_cast<jint>(status);
}

// Real GPU path: full circular FFT correlation (fft.comp resident chain).
// Unlike the buffer-staged entries, the correlate entry works directly on
// the caller's direct-buffer memory: `ref_buffer`/`mov_buffer` hold
// tile_count*S*S floats each ((n,S,S) real), `corr_buffer` receives
// tile_count*S*S fftshifted real floats. No staging buffers or readback
// copies — the entry uploads from and stores to these addresses. `spirv`
// holds the compiled shaders/fft.comp module. Returns srp::Status.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeCorrelate(
    JNIEnv* env, jclass /*cls*/, jlong handle, jobject ref_buffer,
    jobject mov_buffer, jobject corr_buffer, jbyteArray spirv, jint tile_count,
    jint s) {
  auto* ctx = reinterpret_cast<srp::Context*>(static_cast<intptr_t>(handle));
  if (ctx == nullptr || ref_buffer == nullptr || mov_buffer == nullptr ||
      corr_buffer == nullptr || spirv == nullptr || tile_count <= 0 ||
      s <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkDevice device = srp::NativeDevice(ctx);
  if (device == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kNoDevice);
  }
  float* ref_ptr = static_cast<float*>(env->GetDirectBufferAddress(ref_buffer));
  float* mov_ptr = static_cast<float*>(env->GetDirectBufferAddress(mov_buffer));
  float* corr_ptr =
      static_cast<float*>(env->GetDirectBufferAddress(corr_buffer));
  const int64_t n =
      static_cast<int64_t>(tile_count) * static_cast<int64_t>(s) * s;
  if (ref_ptr == nullptr || mov_ptr == nullptr || corr_ptr == nullptr ||
      env->GetDirectBufferCapacity(ref_buffer) < n * 4 ||
      env->GetDirectBufferCapacity(mov_buffer) < n * 4 ||
      env->GetDirectBufferCapacity(corr_buffer) < n * 4) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const jsize spirv_len = env->GetArrayLength(spirv);
  if (spirv_len <= 0 || spirv_len % 4 != 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  std::vector<uint8_t> spirv_bytes(static_cast<size_t>(spirv_len));
  env->GetByteArrayRegion(spirv, 0, spirv_len,
                          reinterpret_cast<jbyte*>(spirv_bytes.data()));
  if (env->ExceptionCheck() == JNI_TRUE) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  // Context-interned stage module (created once per process, owned by
  // the Context; replaces per-call create/destroy).
  VkShaderModule module =
      srp::SrpInternModule(ctx, spirv_bytes.data(), spirv_bytes.size());
  if (module == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kDispatchFailed);
  }
  const srp::Status status =
      srp::SrpCorrelateGpu(ctx, static_cast<uint32_t>(tile_count),
                           static_cast<uint32_t>(s), ref_ptr, mov_ptr,
                           corr_ptr, module);
  // (module stays alive in the Context cache)
  return static_cast<jint>(status);
}

// Real GPU path: pin align-L2 reference spectra (one forward FFT per
// level, reused across frames). `ref_buffer` holds tile_count*S*S real
// floats; `refspec_res` is a resident of exactly tile_count*S*S*2 floats
// receiving the (n,s,s) spectra. Needs the fft module (forward passes)
// and the corr module (real2pairs interleave). Returns srp::Status.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeCorrelatePinRef(
    JNIEnv* env, jclass /*cls*/, jlong handle, jobject ref_buffer,
    jlong refspec_res, jbyteArray spirv_fft, jbyteArray spirv_corr,
    jint tile_count, jint s) {
  auto* ctx = reinterpret_cast<srp::Context*>(static_cast<intptr_t>(handle));
  if (ctx == nullptr || ref_buffer == nullptr || spirv_fft == nullptr ||
      spirv_corr == nullptr || tile_count <= 0 || s <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkDevice device = srp::NativeDevice(ctx);
  if (device == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kNoDevice);
  }
  float* ref_ptr = static_cast<float*>(env->GetDirectBufferAddress(ref_buffer));
  const int64_t n =
      static_cast<int64_t>(tile_count) * static_cast<int64_t>(s) * s;
  if (ref_ptr == nullptr ||
      env->GetDirectBufferCapacity(ref_buffer) < n * 4) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkShaderModule fft_mod = VK_NULL_HANDLE, corr_mod = VK_NULL_HANDLE;
  if (!InternSpirv(env, ctx, spirv_fft, &fft_mod) ||
      !InternSpirv(env, ctx, spirv_corr, &corr_mod)) {
    return static_cast<jint>(srp::Status::kDispatchFailed);
  }
  VkBuffer spec_vk = VK_NULL_HANDLE;
  if (srp::SrpResidentBuffer(ctx, refspec_res, static_cast<size_t>(n) * 2,
                             &spec_vk) != srp::Status::kOk) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const srp::Status status = srp::SrpPinRefSpectraGpu(
      ctx, static_cast<uint32_t>(tile_count), static_cast<uint32_t>(s),
      ref_ptr, spec_vk, fft_mod, corr_mod);
  // (modules stay alive in the Context cache; the resident stays on-device)
  return static_cast<jint>(status);
}

// Real GPU path: pinned-ref FFT correlation. `mov_buffer` holds
// tile_count*S*S reals, `corr_buffer` receives tile_count*S*S fftshifted
// reals; `refspec_res` is the pinned spectra resident (borrowed, never
// released here). Bit-exact vs nativeCorrelate. Returns srp::Status.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeCorrelatePinned(
    JNIEnv* env, jclass /*cls*/, jlong handle, jobject mov_buffer,
    jlong refspec_res, jobject corr_buffer, jbyteArray spirv_fft,
    jbyteArray spirv_corr, jint tile_count, jint s) {
  auto* ctx = reinterpret_cast<srp::Context*>(static_cast<intptr_t>(handle));
  if (ctx == nullptr || mov_buffer == nullptr || corr_buffer == nullptr ||
      spirv_fft == nullptr || spirv_corr == nullptr || tile_count <= 0 ||
      s <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkDevice device = srp::NativeDevice(ctx);
  if (device == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kNoDevice);
  }
  float* mov_ptr = static_cast<float*>(env->GetDirectBufferAddress(mov_buffer));
  float* corr_ptr =
      static_cast<float*>(env->GetDirectBufferAddress(corr_buffer));
  const int64_t n =
      static_cast<int64_t>(tile_count) * static_cast<int64_t>(s) * s;
  if (mov_ptr == nullptr || corr_ptr == nullptr ||
      env->GetDirectBufferCapacity(mov_buffer) < n * 4 ||
      env->GetDirectBufferCapacity(corr_buffer) < n * 4) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkShaderModule fft_mod = VK_NULL_HANDLE, corr_mod = VK_NULL_HANDLE;
  if (!InternSpirv(env, ctx, spirv_fft, &fft_mod) ||
      !InternSpirv(env, ctx, spirv_corr, &corr_mod)) {
    return static_cast<jint>(srp::Status::kDispatchFailed);
  }
  VkBuffer spec_vk = VK_NULL_HANDLE;
  if (srp::SrpResidentBuffer(ctx, refspec_res, static_cast<size_t>(n) * 2,
                             &spec_vk) != srp::Status::kOk) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const srp::Status status = srp::SrpCorrelatePinnedGpu(
      ctx, static_cast<uint32_t>(tile_count), static_cast<uint32_t>(s),
      mov_ptr, corr_ptr, spec_vk, fft_mod, corr_mod);
  // (modules stay alive in the Context cache; the resident stays on-device)
  return static_cast<jint>(status);
}

// Real GPU path: FFT lowpass grey (fft.comp resident chain). `in_buffer`
// holds width*height raw floats; `out_buffer` receives width*height grey
// floats. `spirv` holds the compiled shaders/fft.comp module. Requires
// width,height >= 4. Returns srp::Status.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeGreyFft(
    JNIEnv* env, jclass /*cls*/, jlong handle, jobject in_buffer,
    jobject out_buffer, jbyteArray spirv, jint width, jint height) {
  auto* ctx = reinterpret_cast<srp::Context*>(static_cast<intptr_t>(handle));
  if (ctx == nullptr || in_buffer == nullptr || out_buffer == nullptr ||
      spirv == nullptr || width < 4 || height < 4) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkDevice device = srp::NativeDevice(ctx);
  if (device == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kNoDevice);
  }
  float* in_ptr = static_cast<float*>(env->GetDirectBufferAddress(in_buffer));
  float* out_ptr =
      static_cast<float*>(env->GetDirectBufferAddress(out_buffer));
  const int64_t n = static_cast<int64_t>(width) * static_cast<int64_t>(height);
  if (in_ptr == nullptr || out_ptr == nullptr ||
      env->GetDirectBufferCapacity(in_buffer) < n * 4 ||
      env->GetDirectBufferCapacity(out_buffer) < n * 4) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const jsize spirv_len = env->GetArrayLength(spirv);
  if (spirv_len <= 0 || spirv_len % 4 != 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  std::vector<uint8_t> spirv_bytes(static_cast<size_t>(spirv_len));
  env->GetByteArrayRegion(spirv, 0, spirv_len,
                          reinterpret_cast<jbyte*>(spirv_bytes.data()));
  if (env->ExceptionCheck() == JNI_TRUE) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  // Context-interned stage module (created once per process, owned by
  // the Context; replaces per-call create/destroy).
  VkShaderModule module =
      srp::SrpInternModule(ctx, spirv_bytes.data(), spirv_bytes.size());
  if (module == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kDispatchFailed);
  }
  const srp::Status status =
      srp::SrpGreyFftGpu(ctx, static_cast<uint32_t>(width),
                         static_cast<uint32_t>(height), in_ptr, out_ptr,
                         module);
  // (module stays alive in the Context cache)
  return static_cast<jint>(status);
}

// Real GPU path: ICA Hessian build (ica.comp op 0) over caller-owned direct
// buffers. `gradx_buffer`/`grady_buffer` hold width*height floats each;
// `hess_buffer` receives tile_count*4 floats ((h00,h01,h11,0) per tile).
// `spirv` holds the compiled shaders/ica.comp module. Returns srp::Status.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeIcaHessian(
    JNIEnv* env, jclass /*cls*/, jlong handle, jobject gradx_buffer,
    jobject grady_buffer, jobject hess_buffer, jbyteArray spirv, jint width,
    jint height, jint tile_count, jint tile_size, jint ntx) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || gradx_buffer == nullptr || grady_buffer == nullptr ||
      hess_buffer == nullptr || spirv == nullptr || width <= 0 ||
      height <= 0 || tile_count <= 0 || tile_size <= 0 || ntx <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const int64_t img_count = static_cast<int64_t>(width) * height;
  const int64_t hess_count = static_cast<int64_t>(tile_count) * 4;
  const srp::Status status = StageAndRunCorr(
      env, ctx, device, physical, gradx_buffer, grady_buffer, hess_buffer,
      img_count, img_count, hess_count, spirv,
      [&](VkBuffer a, VkBuffer b, VkBuffer c, VkShaderModule module) {
        return srp::SrpIcaHessianGpu(
            ctx, static_cast<uint32_t>(width), static_cast<uint32_t>(height),
            static_cast<uint32_t>(tile_count), static_cast<uint32_t>(tile_size),
            static_cast<uint32_t>(ntx), a, b, c, module);
      });
  return static_cast<jint>(status);
}

// Real GPU path: ICA Gauss-Newton refine (ica.comp op 1) over caller-owned
// direct buffers. `ref/mov/gradx/grady` hold width*height floats each;
// `hess_buffer` holds tile_count*4 packed floats ((h00,h01,h11,0));
// `flow_buffer` holds tile_count*2 floats (staged with the current flow,
// read back refined). `clip_radius` uploads by bit pattern. Returns
// srp::Status.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeIcaRefine(
    JNIEnv* env, jclass /*cls*/, jlong handle, jobject ref_buffer,
    jobject mov_buffer, jobject gradx_buffer, jobject grady_buffer,
    jobject hess_buffer, jobject flow_buffer, jbyteArray spirv, jint width,
    jint height, jint tile_count, jint tile_size, jint niter,
    jfloat clip_radius, jint ntx) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || ref_buffer == nullptr || mov_buffer == nullptr ||
      gradx_buffer == nullptr || grady_buffer == nullptr ||
      hess_buffer == nullptr || flow_buffer == nullptr || spirv == nullptr ||
      width <= 0 || height <= 0 || tile_count <= 0 || tile_size <= 0 ||
      niter <= 0 || ntx <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const int64_t img_count = static_cast<int64_t>(width) * height;
  const int64_t hess_count = static_cast<int64_t>(tile_count) * 4;
  const int64_t flow_count = static_cast<int64_t>(tile_count) * 2;
  const srp::Status status = StageAndRun6(
      env, ctx, device, physical, ref_buffer, mov_buffer, gradx_buffer,
      grady_buffer, hess_buffer, flow_buffer, img_count, hess_count,
      flow_count, spirv,
      [&](VkBuffer a, VkBuffer b, VkBuffer c, VkBuffer d, VkBuffer e,
          VkBuffer f, VkShaderModule module) {
        return srp::SrpIcaRefineGpu(
            ctx, static_cast<uint32_t>(width), static_cast<uint32_t>(height),
            static_cast<uint32_t>(tile_count), static_cast<uint32_t>(tile_size),
            static_cast<uint32_t>(niter), static_cast<float>(clip_radius),
            static_cast<uint32_t>(ntx), a, b, c, d, e, f, module);
      });
  return static_cast<jint>(status);
}

// Real GPU path: ICA Gauss-Newton refine with device-resident level
// statics. `ref_res`/`gradx_res`/`grady_res` hold width*height floats
// each, `hess_res` holds tile_count*4 packed floats; `mov_buffer`/
// `flow_buffer` are staged (per-frame data), flow read back refined in
// place like the staged path. Zero static traffic per frame.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeIcaRefineRes(
    JNIEnv* env, jclass /*cls*/, jlong handle, jlong ref_res,
    jobject mov_buffer, jlong gradx_res, jlong grady_res, jlong hess_res,
    jobject flow_buffer, jbyteArray spirv, jint width, jint height,
    jint tile_count, jint tile_size, jint niter, jfloat clip_radius,
    jint ntx) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || mov_buffer == nullptr || flow_buffer == nullptr ||
      spirv == nullptr || width <= 0 || height <= 0 || tile_count <= 0 ||
      tile_size <= 0 || niter <= 0 || ntx <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  float* mov_ptr = static_cast<float*>(env->GetDirectBufferAddress(mov_buffer));
  float* flow_ptr = static_cast<float*>(env->GetDirectBufferAddress(flow_buffer));
  const int64_t img_count = static_cast<int64_t>(width) * height;
  const int64_t hess_count = static_cast<int64_t>(tile_count) * 4;
  const int64_t flow_count = static_cast<int64_t>(tile_count) * 2;
  if (mov_ptr == nullptr || flow_ptr == nullptr ||
      env->GetDirectBufferCapacity(mov_buffer) < img_count * 4 ||
      env->GetDirectBufferCapacity(flow_buffer) < flow_count * 4) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkBuffer ref_vk = VK_NULL_HANDLE, gx_vk = VK_NULL_HANDLE,
           gy_vk = VK_NULL_HANDLE, hess_vk = VK_NULL_HANDLE;
  if (srp::SrpResidentBuffer(ctx, ref_res, static_cast<size_t>(img_count),
                             &ref_vk) != srp::Status::kOk ||
      srp::SrpResidentBuffer(ctx, gradx_res, static_cast<size_t>(img_count),
                             &gx_vk) != srp::Status::kOk ||
      srp::SrpResidentBuffer(ctx, grady_res, static_cast<size_t>(img_count),
                             &gy_vk) != srp::Status::kOk ||
      srp::SrpResidentBuffer(ctx, hess_res, static_cast<size_t>(hess_count),
                             &hess_vk) != srp::Status::kOk) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkShaderModule module = VK_NULL_HANDLE;
  if (!InternSpirv(env, ctx, spirv, &module)) {
    return static_cast<jint>(srp::Status::kDispatchFailed);
  }
  VkBuffer mov_vk = VK_NULL_HANDLE;
  VkDeviceMemory mov_mem = VK_NULL_HANDLE;
  VkBuffer flow_vk = VK_NULL_HANDLE;
  VkDeviceMemory flow_mem = VK_NULL_HANDLE;
  srp::Status status = srp::Status::kDispatchFailed;
  if (MakeStagingBuffer(device, physical,
                        static_cast<VkDeviceSize>(img_count) * 4, mov_ptr,
                        &mov_vk, &mov_mem) &&
      MakeStagingBuffer(device, physical,
                        static_cast<VkDeviceSize>(flow_count) * 4, flow_ptr,
                        &flow_vk, &flow_mem)) {
    status = srp::SrpIcaRefineGpu(
        ctx, static_cast<uint32_t>(width), static_cast<uint32_t>(height),
        static_cast<uint32_t>(tile_count), static_cast<uint32_t>(tile_size),
        static_cast<uint32_t>(niter), static_cast<float>(clip_radius),
        static_cast<uint32_t>(ntx), ref_vk, mov_vk, gx_vk, gy_vk, hess_vk,
        flow_vk, module);
    if (status == srp::Status::kOk) {
      void* mapped = nullptr;
      if (vkMapMemory(device, flow_mem,
                      0, static_cast<VkDeviceSize>(flow_count) * 4, 0,
                      &mapped) == VK_SUCCESS) {
        std::memcpy(flow_ptr, mapped, static_cast<size_t>(flow_count) * 4);
        vkUnmapMemory(device, flow_mem);
      } else {
        status = srp::Status::kDispatchFailed;
      }
    }
  }
  if (mov_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, mov_vk, nullptr);
  if (mov_mem != VK_NULL_HANDLE) vkFreeMemory(device, mov_mem, nullptr);
  if (flow_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, flow_vk, nullptr);
  if (flow_mem != VK_NULL_HANDLE) vkFreeMemory(device, flow_mem, nullptr);
  // (module stays alive in the Context cache; residents stay on-device)
  return static_cast<jint>(status);
}

// Real GPU path: Menon2007 demosaic-grey (demosaic.comp ops 0-11, RGGB
// only) over caller-owned direct buffers. `cfa_buffer` holds
// width*height CFA floats, `grey_buffer` receives width*height grey
// floats. `spirv` holds the compiled shaders/demosaic.comp module.
// Finite CFA input only (no NaN fallback on-device). Returns srp::Status.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeDemosaicMenon(
    JNIEnv* env, jclass /*cls*/, jlong handle, jobject cfa_buffer,
    jobject grey_buffer, jbyteArray spirv, jint width, jint height) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || cfa_buffer == nullptr || grey_buffer == nullptr ||
      spirv == nullptr || width <= 0 || height <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const int64_t count = static_cast<int64_t>(width) * height;
  const srp::Status status = StageAndRun2(
      env, ctx, device, physical, cfa_buffer, grey_buffer, count, spirv,
      [&](VkBuffer a, VkBuffer b, VkShaderModule module) {
        return srp::SrpDemosaicMenonGpu(
            ctx, static_cast<uint32_t>(width), static_cast<uint32_t>(height),
            a, b, module);
      });
  return static_cast<jint>(status);
}

// Real GPU path: small-kernel gradients + preprocessing (grad.comp ops
// 0-3) over caller-owned direct buffers. `in_buffer` holds width*height
// floats; `out_buffer` receives count outputs (count*2 interleaved-pair
// floats for ops 0-1, count floats for ops 2-3). `spirv` holds the
// compiled shaders/grad.comp module. `exp_bits` carries the op-3 gamma
// exponent by bit pattern. Staged manually (in/out sizes differ by op),
// like nativeGat. Returns srp::Status.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeGrad(
    JNIEnv* env, jclass /*cls*/, jlong handle, jobject in_buffer,
    jobject out_buffer, jbyteArray spirv, jint width, jint height, jint count,
    jint op, jint exp_bits) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || in_buffer == nullptr || out_buffer == nullptr ||
      spirv == nullptr || width <= 0 || height <= 0 || count <= 0 ||
      op < 0 || op > 3) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const int64_t img_count = static_cast<int64_t>(width) * height;
  const int64_t out_count =
      (op <= 1) ? static_cast<int64_t>(count) * 2 : count;
  float* in_ptr = static_cast<float*>(env->GetDirectBufferAddress(in_buffer));
  float* out_ptr = static_cast<float*>(env->GetDirectBufferAddress(out_buffer));
  if (in_ptr == nullptr || out_ptr == nullptr ||
      env->GetDirectBufferCapacity(in_buffer) < img_count * 4 ||
      env->GetDirectBufferCapacity(out_buffer) < out_count * 4) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const jsize spirv_len = env->GetArrayLength(spirv);
  if (spirv_len <= 0 || spirv_len % 4 != 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  std::vector<uint8_t> spirv_bytes(static_cast<size_t>(spirv_len));
  env->GetByteArrayRegion(spirv, 0, spirv_len,
                          reinterpret_cast<jbyte*>(spirv_bytes.data()));
  if (env->ExceptionCheck() == JNI_TRUE) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  // Context-interned stage module (created once per process, owned by
  // the Context; replaces per-call create/destroy).
  VkShaderModule module =
      srp::SrpInternModule(ctx, spirv_bytes.data(), spirv_bytes.size());
  if (module == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kDispatchFailed);
  }
  VkBuffer in_vk = VK_NULL_HANDLE, out_vk = VK_NULL_HANDLE;
  VkDeviceMemory in_mem = VK_NULL_HANDLE, out_mem = VK_NULL_HANDLE;
  srp::Status status = srp::Status::kDispatchFailed;
  if (MakeStagingBuffer(device, physical,
                        static_cast<VkDeviceSize>(img_count) * 4, in_ptr,
                        &in_vk, &in_mem) &&
      MakeStagingBuffer(device, physical,
                        static_cast<VkDeviceSize>(out_count) * 4, nullptr,
                        &out_vk, &out_mem)) {
    status = srp::SrpGradGpu(ctx, static_cast<uint32_t>(width),
                             static_cast<uint32_t>(height),
                             static_cast<uint32_t>(count),
                             static_cast<uint32_t>(op),
                             static_cast<uint32_t>(exp_bits), in_vk, out_vk,
                             module);
    if (status == srp::Status::kOk) {
      void* mapped = nullptr;
      if (vkMapMemory(device, out_mem, 0,
                      static_cast<VkDeviceSize>(out_count) * 4, 0, &mapped) ==
          VK_SUCCESS) {
        std::memcpy(out_ptr, mapped, static_cast<size_t>(out_count) * 4);
        vkUnmapMemory(device, out_mem);
      } else {
        status = srp::Status::kDispatchFailed;
      }
    }
  }
  if (in_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, in_vk, nullptr);
  if (in_mem != VK_NULL_HANDLE) vkFreeMemory(device, in_mem, nullptr);
  if (out_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, out_vk, nullptr);
  if (out_mem != VK_NULL_HANDLE) vkFreeMemory(device, out_mem, nullptr);
  // (module stays alive in the Context cache)
  return static_cast<jint>(status);
}

// Real GPU path: kernel-covariance build (kernels.comp, Alg.5) over
// caller-owned direct buffers. `grads_buffer` holds gradW*gradH*2
// interleaved-pair floats, `kparams_buffer` holds 8 floats (six shaping
// scalars + selection_law/is_iso by bit pattern), `cov_buffer` receives
// covW*covH*4 floats ((xx,xy,yy,0) per cov pixel). `spirv` holds the
// compiled shaders/kernels.comp module. Staged via StageAndRunCorr
// (kparams staged in, cov read back). Returns srp::Status.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeKernelsCov(
    JNIEnv* env, jclass /*cls*/, jlong handle, jobject grads_buffer,
    jobject kparams_buffer, jobject cov_buffer, jbyteArray spirv, jint grad_w,
    jint grad_h, jint cov_w, jint cov_h) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || grads_buffer == nullptr || kparams_buffer == nullptr ||
      cov_buffer == nullptr || spirv == nullptr || grad_w <= 0 ||
      grad_h <= 0 || cov_w <= 0 || cov_h <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const int64_t grads_count =
      static_cast<int64_t>(grad_w) * grad_h * 2;
  const int64_t cov_count = static_cast<int64_t>(cov_w) * cov_h * 4;
  const srp::Status status = StageAndRunCorr(
      env, ctx, device, physical, grads_buffer, kparams_buffer, cov_buffer,
      grads_count, 8, cov_count, spirv,
      [&](VkBuffer a, VkBuffer b, VkBuffer c, VkShaderModule module) {
        return srp::SrpKernelsCovGpu(
            ctx, static_cast<uint32_t>(grad_w), static_cast<uint32_t>(grad_h),
            static_cast<uint32_t>(cov_w), static_cast<uint32_t>(cov_h), a, c,
            b, module);
      });
  return static_cast<jint>(status);
}

// Real GPU path: elementwise divide (divide.comp, utils.divide) over
// caller-owned direct buffers. `num_buffer`/`den_buffer` hold `count`
// floats each; `out_buffer` receives `count` quotients. `spirv` holds
// the compiled shaders/divide.comp module. `num_buffer` and
// `out_buffer` may alias (same object) for in-place `num /= den`.
// Staged via StageAndRunCorr. Returns srp::Status.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeDivide(
    JNIEnv* env, jclass /*cls*/, jlong handle, jobject num_buffer,
    jobject den_buffer, jobject out_buffer, jbyteArray spirv, jint count) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || num_buffer == nullptr || den_buffer == nullptr ||
      out_buffer == nullptr || spirv == nullptr || count <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const srp::Status status = StageAndRunCorr(
      env, ctx, device, physical, num_buffer, den_buffer, out_buffer,
      static_cast<int64_t>(count), static_cast<int64_t>(count),
      static_cast<int64_t>(count), spirv,
      [&](VkBuffer a, VkBuffer b, VkBuffer c, VkShaderModule module) {
        return srp::SrpDivideGpu(ctx, static_cast<uint32_t>(count), a, b, c,
                                 module);
      });
  return static_cast<jint>(status);
}

// Manual staging for the robustness entries (buffer counts differ per
// entry, like nativeGrad). Stages `n_in` floats from each input object,
// leaves outputs uninitialised, and reads back `n_out` floats from the
// last one. Returns the entry status, or kInitFailed/kDispatchFailed on
// staging failures.
srp::Status StageRobust(JNIEnv* env, srp::Context* ctx, VkDevice device, VkPhysicalDevice physical,
                        jobject* in_bufs, const int64_t* in_counts, int n_in,
                        jobject out_buf, int64_t out_count, jbyteArray spirv,
                        const std::function<srp::Status(VkBuffer*, VkBuffer,
                                                        VkShaderModule)>&
                            dispatch) {
  std::vector<float*> in_ptrs(static_cast<size_t>(n_in));
  for (int i = 0; i < n_in; ++i) {
    in_ptrs[i] =
        static_cast<float*>(env->GetDirectBufferAddress(in_bufs[i]));
    if (in_ptrs[i] == nullptr ||
        env->GetDirectBufferCapacity(in_bufs[i]) < in_counts[i] * 4) {
      return srp::Status::kInitFailed;
    }
  }
  float* out_ptr = static_cast<float*>(env->GetDirectBufferAddress(out_buf));
  if (out_ptr == nullptr ||
      env->GetDirectBufferCapacity(out_buf) < out_count * 4) {
    return srp::Status::kInitFailed;
  }
  const jsize spirv_len = env->GetArrayLength(spirv);
  if (spirv_len <= 0 || spirv_len % 4 != 0) {
    return srp::Status::kInitFailed;
  }
  std::vector<uint8_t> spirv_bytes(static_cast<size_t>(spirv_len));
  env->GetByteArrayRegion(spirv, 0, spirv_len,
                          reinterpret_cast<jbyte*>(spirv_bytes.data()));
  if (env->ExceptionCheck() == JNI_TRUE) {
    return srp::Status::kInitFailed;
  }
  // Context-interned stage module (created once per process, owned by
  // the Context; replaces per-call create/destroy).
  VkShaderModule module =
      srp::SrpInternModule(ctx, spirv_bytes.data(), spirv_bytes.size());
  if (module == VK_NULL_HANDLE) {
    return srp::Status::kDispatchFailed;
  }
  std::vector<VkBuffer> vks(static_cast<size_t>(n_in) + 1, VK_NULL_HANDLE);
  std::vector<VkDeviceMemory> mems(static_cast<size_t>(n_in) + 1,
                                   VK_NULL_HANDLE);
  srp::Status status = srp::Status::kDispatchFailed;
  bool staged = true;
  for (int i = 0; i < n_in; ++i) {
    if (!MakeStagingBuffer(device, physical,
                           static_cast<VkDeviceSize>(in_counts[i]) * 4,
                           in_ptrs[i], &vks[i], &mems[i])) {
      staged = false;
      break;
    }
  }
  if (staged &&
      MakeStagingBuffer(device, physical,
                        static_cast<VkDeviceSize>(out_count) * 4, nullptr,
                        &vks[n_in], &mems[n_in])) {
    status = dispatch(vks.data(), vks[n_in], module);
    if (status == srp::Status::kOk) {
      void* mapped = nullptr;
      if (vkMapMemory(device, mems[n_in], 0,
                      static_cast<VkDeviceSize>(out_count) * 4, 0, &mapped) ==
          VK_SUCCESS) {
        std::memcpy(out_ptr, mapped, static_cast<size_t>(out_count) * 4);
        vkUnmapMemory(device, mems[n_in]);
      } else {
        status = srp::Status::kDispatchFailed;
      }
    }
  }
  for (size_t i = 0; i < vks.size(); ++i) {
    if (vks[i] != VK_NULL_HANDLE) vkDestroyBuffer(device, vks[i], nullptr);
    if (mems[i] != VK_NULL_HANDLE) vkFreeMemory(device, mems[i], nullptr);
  }
  // (module stays alive in the Context cache)
  return status;
}

// Real GPU path: init_robustness (ops 0+1, bayer) over caller-owned
// direct buffers. `ref_buffer` holds width*height raw floats;
// `means_buffer`/`vars_buffer` each receive 3*gh*gw floats, both
// downloaded explicitly (manual staging, like nativeGrad).
// Returns srp::Status.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeRobustInit(
    JNIEnv* env, jclass /*cls*/, jlong handle, jobject ref_buffer,
    jobject means_buffer, jobject vars_buffer, jbyteArray spirv, jint width,
    jint height) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || ref_buffer == nullptr || means_buffer == nullptr ||
      vars_buffer == nullptr || spirv == nullptr || width < 2 ||
      height < 2) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const int64_t n = static_cast<int64_t>(width) / 2 * (height / 2);
  float* ref_ptr = static_cast<float*>(env->GetDirectBufferAddress(ref_buffer));
  float* means_ptr =
      static_cast<float*>(env->GetDirectBufferAddress(means_buffer));
  float* vars_ptr =
      static_cast<float*>(env->GetDirectBufferAddress(vars_buffer));
  if (ref_ptr == nullptr || means_ptr == nullptr || vars_ptr == nullptr ||
      env->GetDirectBufferCapacity(ref_buffer) <
          static_cast<int64_t>(width) * height * 4 ||
      env->GetDirectBufferCapacity(means_buffer) < 3 * n * 4 ||
      env->GetDirectBufferCapacity(vars_buffer) < 3 * n * 4) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const jsize spirv_len = env->GetArrayLength(spirv);
  if (spirv_len <= 0 || spirv_len % 4 != 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  std::vector<uint8_t> spirv_bytes(static_cast<size_t>(spirv_len));
  env->GetByteArrayRegion(spirv, 0, spirv_len,
                          reinterpret_cast<jbyte*>(spirv_bytes.data()));
  if (env->ExceptionCheck() == JNI_TRUE) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  // Context-interned stage module (created once per process, owned by
  // the Context; replaces per-call create/destroy).
  VkShaderModule module =
      srp::SrpInternModule(ctx, spirv_bytes.data(), spirv_bytes.size());
  if (module == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kDispatchFailed);
  }
  VkBuffer ref_vk = VK_NULL_HANDLE, means_vk = VK_NULL_HANDLE,
           vars_vk = VK_NULL_HANDLE;
  VkDeviceMemory ref_mem = VK_NULL_HANDLE, means_mem = VK_NULL_HANDLE,
                 vars_mem = VK_NULL_HANDLE;
  srp::Status status = srp::Status::kDispatchFailed;
  if (MakeStagingBuffer(device, physical,
                        static_cast<VkDeviceSize>(width) * height * 4, ref_ptr,
                        &ref_vk, &ref_mem) &&
      MakeStagingBuffer(device, physical,
                        static_cast<VkDeviceSize>(3 * n) * 4, nullptr,
                        &means_vk, &means_mem) &&
      MakeStagingBuffer(device, physical,
                        static_cast<VkDeviceSize>(3 * n) * 4, nullptr,
                        &vars_vk, &vars_mem)) {
    status = srp::SrpRobustInitGpu(
        ctx, static_cast<uint32_t>(width), static_cast<uint32_t>(height),
        ref_vk, means_vk, vars_vk, module);
    if (status == srp::Status::kOk) {
      void* mapped = nullptr;
      if (vkMapMemory(device, means_mem, 0,
                      static_cast<VkDeviceSize>(3 * n) * 4, 0, &mapped) ==
              VK_SUCCESS) {
        std::memcpy(means_ptr, mapped, static_cast<size_t>(3 * n) * 4);
        vkUnmapMemory(device, means_mem);
        mapped = nullptr;
      } else {
        status = srp::Status::kDispatchFailed;
      }
      if (status == srp::Status::kOk &&
          vkMapMemory(device, vars_mem, 0,
                      static_cast<VkDeviceSize>(3 * n) * 4, 0, &mapped) ==
              VK_SUCCESS) {
        std::memcpy(vars_ptr, mapped, static_cast<size_t>(3 * n) * 4);
        vkUnmapMemory(device, vars_mem);
      } else if (status == srp::Status::kOk) {
        status = srp::Status::kDispatchFailed;
      }
    }
  }
  if (ref_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, ref_vk, nullptr);
  if (ref_mem != VK_NULL_HANDLE) vkFreeMemory(device, ref_mem, nullptr);
  if (means_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, means_vk, nullptr);
  if (means_mem != VK_NULL_HANDLE) vkFreeMemory(device, means_mem, nullptr);
  if (vars_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, vars_vk, nullptr);
  if (vars_mem != VK_NULL_HANDLE) vkFreeMemory(device, vars_mem, nullptr);
  // (module stays alive in the Context cache)
  return static_cast<jint>(status);
}

// Real GPU path: compute_robustness minus the smap leg (ops 0+1+2+3+4+5,
// bayer) over caller-owned direct buffers. `comp_buffer` holds
// width*height raw floats; `means_buffer`/`vars_buffer` hold 3*gh*gw
// reference stats; `flow_buffer` holds ftx*fty*2 pairs; `lut_buffer`
// holds 2*lutN curve floats; `s_buffer` holds the ftx*fty spread map;
// `out_buffer` receives the gh*gw robustness map. Returns srp::Status.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeRobustCompute(
    JNIEnv* env, jclass /*cls*/, jlong handle, jobject comp_buffer,
    jobject means_buffer, jobject vars_buffer, jobject flow_buffer,
    jobject lut_buffer, jobject s_buffer, jobject out_buffer, jbyteArray spirv,
    jint width, jint height, jint tile_size, jint lut_n, jint noise_correction,
    jint t_bits, jint ftx, jint fty) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || comp_buffer == nullptr || means_buffer == nullptr ||
      vars_buffer == nullptr || flow_buffer == nullptr ||
      lut_buffer == nullptr || s_buffer == nullptr || out_buffer == nullptr ||
      spirv == nullptr || width < 2 || height < 2 || tile_size < 2 ||
      lut_n <= 0 || ftx <= 0 || fty <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const int64_t n = static_cast<int64_t>(width) / 2 * (height / 2);
  const int64_t nf = static_cast<int64_t>(ftx) * fty;
  jobject ins[6] = {comp_buffer, means_buffer, vars_buffer, flow_buffer,
                    lut_buffer, s_buffer};
  const int64_t counts[6] = {
      static_cast<int64_t>(width) * height, 3 * n, 3 * n, nf * 2,
      static_cast<int64_t>(lut_n) * 2, nf};
  const srp::Status status = StageRobust(
      env, ctx, device, physical, ins, counts, 6, out_buffer, n, spirv,
      [&](VkBuffer* staged, VkBuffer out, VkShaderModule module) {
        return srp::SrpRobustComputeGpu(
            ctx, static_cast<uint32_t>(width), static_cast<uint32_t>(height),
            staged[0], staged[1], staged[2], staged[3], staged[4], staged[5],
            out, static_cast<uint32_t>(tile_size),
            static_cast<uint32_t>(lut_n),
            static_cast<uint32_t>(noise_correction),
            static_cast<uint32_t>(t_bits), static_cast<uint32_t>(ftx),
            static_cast<uint32_t>(fty), module);
      });
  return static_cast<jint>(status);
}

// Real GPU path: init_robustness (ops 0+1, bayer) with device-resident
// stats. `ref_buffer` holds width*height raw floats (staged up once);
// `means_res`/`vars_res` are residents of exactly 3*gh*gw floats each
// that receive the stats and stay on-device — no host download, unlike
// nativeRobustInit. Same shader, same input bytes, so the on-device
// stats are bit-identical to the staged path. Returns srp::Status.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeRobustInitRes(
    JNIEnv* env, jclass /*cls*/, jlong handle, jobject ref_buffer,
    jlong means_res, jlong vars_res, jbyteArray spirv, jint width,
    jint height) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || ref_buffer == nullptr || spirv == nullptr ||
      width < 2 || height < 2) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const int64_t n = static_cast<int64_t>(width) / 2 * (height / 2);
  float* ref_ptr = static_cast<float*>(env->GetDirectBufferAddress(ref_buffer));
  if (ref_ptr == nullptr ||
      env->GetDirectBufferCapacity(ref_buffer) <
          static_cast<int64_t>(width) * height * 4) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkBuffer means_vk = VK_NULL_HANDLE, vars_vk = VK_NULL_HANDLE;
  if (srp::SrpResidentBuffer(ctx, means_res, static_cast<size_t>(3 * n),
                             &means_vk) != srp::Status::kOk ||
      srp::SrpResidentBuffer(ctx, vars_res, static_cast<size_t>(3 * n),
                             &vars_vk) != srp::Status::kOk) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const jsize spirv_len = env->GetArrayLength(spirv);
  if (spirv_len <= 0 || spirv_len % 4 != 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  std::vector<uint8_t> spirv_bytes(static_cast<size_t>(spirv_len));
  env->GetByteArrayRegion(spirv, 0, spirv_len,
                          reinterpret_cast<jbyte*>(spirv_bytes.data()));
  if (env->ExceptionCheck() == JNI_TRUE) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkShaderModule module =
      srp::SrpInternModule(ctx, spirv_bytes.data(), spirv_bytes.size());
  if (module == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kDispatchFailed);
  }
  VkBuffer ref_vk = VK_NULL_HANDLE;
  VkDeviceMemory ref_mem = VK_NULL_HANDLE;
  srp::Status status = srp::Status::kDispatchFailed;
  if (MakeStagingBuffer(device, physical,
                        static_cast<VkDeviceSize>(width) * height * 4, ref_ptr,
                        &ref_vk, &ref_mem)) {
    status = srp::SrpRobustInitGpu(
        ctx, static_cast<uint32_t>(width), static_cast<uint32_t>(height),
        ref_vk, means_vk, vars_vk, module);
  }
  if (ref_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, ref_vk, nullptr);
  if (ref_mem != VK_NULL_HANDLE) vkFreeMemory(device, ref_mem, nullptr);
  // (module stays alive in the Context cache; residents stay on-device)
  return static_cast<jint>(status);
}

// Real GPU path: compute_robustness minus the smap leg (ops 0+1+2+3+4+5,
// bayer) with device-resident reference stats. `comp_buffer` holds
// width*height raw floats; `means_res`/`vars_res` are the residents
// written by nativeRobustInitRes; `flow_buffer` holds ftx*fty*2 pairs;
// `lut_buffer` holds 2*lutN curve floats; `s_buffer` holds the ftx*fty
// spread map; `out_buffer` receives the gh*gw robustness map (staged,
// like nativeRobustCompute). Same shader, same bytes: output is
// bit-identical to the staged path. Returns srp::Status.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeRobustComputeRes(
    JNIEnv* env, jclass /*cls*/, jlong handle, jobject comp_buffer,
    jlong means_res, jlong vars_res, jobject flow_buffer,
    jobject lut_buffer, jobject s_buffer, jobject out_buffer, jbyteArray spirv,
    jint width, jint height, jint tile_size, jint lut_n, jint noise_correction,
    jint t_bits, jint ftx, jint fty) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || comp_buffer == nullptr || flow_buffer == nullptr ||
      lut_buffer == nullptr || s_buffer == nullptr || out_buffer == nullptr ||
      spirv == nullptr || width < 2 || height < 2 || tile_size < 2 ||
      lut_n <= 0 || ftx <= 0 || fty <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const int64_t n = static_cast<int64_t>(width) / 2 * (height / 2);
  const int64_t nf = static_cast<int64_t>(ftx) * fty;
  VkBuffer means_vk = VK_NULL_HANDLE, vars_vk = VK_NULL_HANDLE;
  if (srp::SrpResidentBuffer(ctx, means_res, static_cast<size_t>(3 * n),
                             &means_vk) != srp::Status::kOk ||
      srp::SrpResidentBuffer(ctx, vars_res, static_cast<size_t>(3 * n),
                             &vars_vk) != srp::Status::kOk) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  float* comp_ptr =
      static_cast<float*>(env->GetDirectBufferAddress(comp_buffer));
  float* flow_ptr =
      static_cast<float*>(env->GetDirectBufferAddress(flow_buffer));
  float* lut_ptr = static_cast<float*>(env->GetDirectBufferAddress(lut_buffer));
  float* s_ptr = static_cast<float*>(env->GetDirectBufferAddress(s_buffer));
  float* out_ptr = static_cast<float*>(env->GetDirectBufferAddress(out_buffer));
  if (comp_ptr == nullptr || flow_ptr == nullptr || lut_ptr == nullptr ||
      s_ptr == nullptr || out_ptr == nullptr ||
      env->GetDirectBufferCapacity(comp_buffer) <
          static_cast<int64_t>(width) * height * 4 ||
      env->GetDirectBufferCapacity(flow_buffer) < nf * 2 * 4 ||
      env->GetDirectBufferCapacity(lut_buffer) <
          static_cast<int64_t>(lut_n) * 2 * 4 ||
      env->GetDirectBufferCapacity(s_buffer) < nf * 4 ||
      env->GetDirectBufferCapacity(out_buffer) < n * 4) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const jsize spirv_len = env->GetArrayLength(spirv);
  if (spirv_len <= 0 || spirv_len % 4 != 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  std::vector<uint8_t> spirv_bytes(static_cast<size_t>(spirv_len));
  env->GetByteArrayRegion(spirv, 0, spirv_len,
                          reinterpret_cast<jbyte*>(spirv_bytes.data()));
  if (env->ExceptionCheck() == JNI_TRUE) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkShaderModule module =
      srp::SrpInternModule(ctx, spirv_bytes.data(), spirv_bytes.size());
  if (module == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kDispatchFailed);
  }
  VkBuffer vks[5] = {};
  VkDeviceMemory mems[5] = {};
  float* ins[5] = {comp_ptr, flow_ptr, lut_ptr, s_ptr, nullptr};
  const int64_t in_counts[5] = {
      static_cast<int64_t>(width) * height, nf * 2,
      static_cast<int64_t>(lut_n) * 2, nf, n};
  srp::Status status = srp::Status::kDispatchFailed;
  bool staged = true;
  for (int i = 0; i < 5; ++i) {
    if (!MakeStagingBuffer(device, physical,
                           static_cast<VkDeviceSize>(in_counts[i]) * 4, ins[i],
                           &vks[i], &mems[i])) {
      staged = false;
      break;
    }
  }
  if (staged) {
    status = srp::SrpRobustComputeGpu(
        ctx, static_cast<uint32_t>(width), static_cast<uint32_t>(height),
        vks[0], means_vk, vars_vk, vks[1], vks[2], vks[3], vks[4],
        static_cast<uint32_t>(tile_size), static_cast<uint32_t>(lut_n),
        static_cast<uint32_t>(noise_correction),
        static_cast<uint32_t>(t_bits), static_cast<uint32_t>(ftx),
        static_cast<uint32_t>(fty), module);
    if (status == srp::Status::kOk) {
      void* mapped = nullptr;
      if (vkMapMemory(device, mems[4], 0, static_cast<VkDeviceSize>(n) * 4, 0,
                      &mapped) == VK_SUCCESS) {
        std::memcpy(out_ptr, mapped, static_cast<size_t>(n) * 4);
        vkUnmapMemory(device, mems[4]);
      } else {
        status = srp::Status::kDispatchFailed;
      }
    }
  }
  for (int i = 0; i < 5; ++i) {
    if (vks[i] != VK_NULL_HANDLE) vkDestroyBuffer(device, vks[i], nullptr);
    if (mems[i] != VK_NULL_HANDLE) vkFreeMemory(device, mems[i], nullptr);
  }
  // (module stays alive in the Context cache; residents stay on-device)
  return static_cast<jint>(status);
}

// Real GPU path: FFT lowpass grey with a device-resident frame.
// `frame_res` is a resident holding width*height reals (uploaded once
// per frame, reused across entries); `out_buffer` receives width*height
// host floats (the align loop needs rows on the host). Same passes as
// nativeGreyFft (bit-identical); saves the per-entry input upload.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeGreyFftRes(
    JNIEnv* env, jclass /*cls*/, jlong handle, jlong frame_res,
    jobject out_buffer, jbyteArray fft_spirv, jbyteArray corr_spirv,
    jint width, jint height) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || out_buffer == nullptr || fft_spirv == nullptr ||
      corr_spirv == nullptr || width < 4 || height < 4) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const int64_t n = static_cast<int64_t>(width) * height;
  VkBuffer frame_vk = VK_NULL_HANDLE;
  if (srp::SrpResidentBuffer(ctx, frame_res, static_cast<size_t>(n),
                             &frame_vk) != srp::Status::kOk) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  float* out_ptr = static_cast<float*>(env->GetDirectBufferAddress(out_buffer));
  if (out_ptr == nullptr ||
      env->GetDirectBufferCapacity(out_buffer) < n * 4) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const jsize fft_len = env->GetArrayLength(fft_spirv);
  const jsize corr_len = env->GetArrayLength(corr_spirv);
  if (fft_len <= 0 || fft_len % 4 != 0 || corr_len <= 0 ||
      corr_len % 4 != 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  std::vector<uint8_t> fft_bytes(static_cast<size_t>(fft_len));
  env->GetByteArrayRegion(fft_spirv, 0, fft_len,
                          reinterpret_cast<jbyte*>(fft_bytes.data()));
  if (env->ExceptionCheck() == JNI_TRUE) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  std::vector<uint8_t> corr_bytes(static_cast<size_t>(corr_len));
  env->GetByteArrayRegion(corr_spirv, 0, corr_len,
                          reinterpret_cast<jbyte*>(corr_bytes.data()));
  if (env->ExceptionCheck() == JNI_TRUE) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkShaderModule fft_module =
      srp::SrpInternModule(ctx, fft_bytes.data(), fft_bytes.size());
  VkShaderModule corr_module =
      srp::SrpInternModule(ctx, corr_bytes.data(), corr_bytes.size());
  if (fft_module == VK_NULL_HANDLE || corr_module == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kDispatchFailed);
  }
  const srp::Status status = srp::SrpGreyFftHaveGpu(
      ctx, static_cast<uint32_t>(width), static_cast<uint32_t>(height),
      frame_vk, out_ptr, fft_module, corr_module);
  // (modules stay alive in the Context cache; the resident stays on-device)
  return static_cast<jint>(status);
}

// Real GPU path: gat/decimate with resident frame scratch. `in_res`
// holds width*height floats, `out_res` holds `count`; `ab_buffer` is the
// tiny staged profile. Same shader, same bytes: bit-identical to
// nativeGat, minus the per-entry upload/download round-trip.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeGatRes(
    JNIEnv* env, jclass /*cls*/, jlong handle, jlong in_res, jlong out_res,
    jobject ab_buffer, jbyteArray spirv, jint width, jint height, jint count,
    jint op) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || ab_buffer == nullptr || spirv == nullptr ||
      width <= 0 || height <= 0 || count <= 0 || (op != 0 && op != 1)) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkBuffer in_vk = VK_NULL_HANDLE, out_vk = VK_NULL_HANDLE;
  if (srp::SrpResidentBuffer(ctx, in_res,
                             static_cast<size_t>(width) * height,
                             &in_vk) != srp::Status::kOk ||
      srp::SrpResidentBuffer(ctx, out_res, static_cast<size_t>(count),
                             &out_vk) != srp::Status::kOk) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  float* ab_ptr = static_cast<float*>(env->GetDirectBufferAddress(ab_buffer));
  if (ab_ptr == nullptr ||
      env->GetDirectBufferCapacity(ab_buffer) < 32) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const jsize spirv_len = env->GetArrayLength(spirv);
  if (spirv_len <= 0 || spirv_len % 4 != 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  std::vector<uint8_t> spirv_bytes(static_cast<size_t>(spirv_len));
  env->GetByteArrayRegion(spirv, 0, spirv_len,
                          reinterpret_cast<jbyte*>(spirv_bytes.data()));
  if (env->ExceptionCheck() == JNI_TRUE) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkShaderModule module =
      srp::SrpInternModule(ctx, spirv_bytes.data(), spirv_bytes.size());
  if (module == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kDispatchFailed);
  }
  VkBuffer ab_vk = VK_NULL_HANDLE;
  VkDeviceMemory ab_mem = VK_NULL_HANDLE;
  srp::Status status = srp::Status::kDispatchFailed;
  if (MakeStagingBuffer(device, physical, 32, ab_ptr, &ab_vk, &ab_mem)) {
    status = srp::SrpGatGpu(ctx, static_cast<uint32_t>(width),
                            static_cast<uint32_t>(height),
                            static_cast<uint32_t>(count),
                            static_cast<uint32_t>(op), in_vk, out_vk, ab_vk,
                            module);
  }
  if (ab_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, ab_vk, nullptr);
  if (ab_mem != VK_NULL_HANDLE) vkFreeMemory(device, ab_mem, nullptr);
  // (module stays alive in the Context cache; residents stay on-device)
  return static_cast<jint>(status);
}

// Real GPU path: kerngrad/sobel/sqrt/gamma with resident scratch.
// `in_res` holds width*height floats, `out_res` holds count (op<=1) or
// count*2 floats. Same shader, same bytes: bit-identical to nativeGrad.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeGradRes(
    JNIEnv* env, jclass /*cls*/, jlong handle, jlong in_res, jlong out_res,
    jbyteArray spirv, jint width, jint height, jint count, jint op,
    jint exp_bits) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || spirv == nullptr || width <= 0 || height <= 0 ||
      count <= 0 || op < 0 || op > 3) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const int64_t out_count =
      (op <= 1) ? static_cast<int64_t>(count) * 2 : count;
  VkBuffer in_vk = VK_NULL_HANDLE, out_vk = VK_NULL_HANDLE;
  if (srp::SrpResidentBuffer(ctx, in_res,
                             static_cast<size_t>(width) * height,
                             &in_vk) != srp::Status::kOk ||
      srp::SrpResidentBuffer(ctx, out_res, static_cast<size_t>(out_count),
                             &out_vk) != srp::Status::kOk) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const jsize spirv_len = env->GetArrayLength(spirv);
  if (spirv_len <= 0 || spirv_len % 4 != 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  std::vector<uint8_t> spirv_bytes(static_cast<size_t>(spirv_len));
  env->GetByteArrayRegion(spirv, 0, spirv_len,
                          reinterpret_cast<jbyte*>(spirv_bytes.data()));
  if (env->ExceptionCheck() == JNI_TRUE) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkShaderModule module =
      srp::SrpInternModule(ctx, spirv_bytes.data(), spirv_bytes.size());
  if (module == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kDispatchFailed);
  }
  const srp::Status status = srp::SrpGradGpu(
      ctx, static_cast<uint32_t>(width), static_cast<uint32_t>(height),
      static_cast<uint32_t>(count), static_cast<uint32_t>(op),
      static_cast<uint32_t>(exp_bits), in_vk, out_vk, module);
  // (module stays alive in the Context cache; residents stay on-device)
  return static_cast<jint>(status);
}

// Real GPU path: kernel-covariance build with resident scratch.
// `grads_res` holds gradW*gradH*2 floats, `cov_res` receives
// covW*covH*4; `kp_buffer` is the tiny staged profile. Bit-identical to
// nativeKernelsCov.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeKernelsCovRes(
    JNIEnv* env, jclass /*cls*/, jlong handle, jlong grads_res,
    jobject kp_buffer, jlong cov_res, jbyteArray spirv, jint grad_w,
    jint grad_h, jint cov_w, jint cov_h) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || kp_buffer == nullptr || spirv == nullptr ||
      grad_w <= 0 || grad_h <= 0 || cov_w <= 0 || cov_h <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkBuffer grads_vk = VK_NULL_HANDLE, cov_vk = VK_NULL_HANDLE;
  if (srp::SrpResidentBuffer(
          ctx, grads_res,
          static_cast<size_t>(grad_w) * grad_h * 2,
          &grads_vk) != srp::Status::kOk ||
      srp::SrpResidentBuffer(ctx, cov_res,
                             static_cast<size_t>(cov_w) * cov_h * 4,
                             &cov_vk) != srp::Status::kOk) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  float* kp_ptr = static_cast<float*>(env->GetDirectBufferAddress(kp_buffer));
  if (kp_ptr == nullptr ||
      env->GetDirectBufferCapacity(kp_buffer) < 32) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const jsize spirv_len = env->GetArrayLength(spirv);
  if (spirv_len <= 0 || spirv_len % 4 != 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  std::vector<uint8_t> spirv_bytes(static_cast<size_t>(spirv_len));
  env->GetByteArrayRegion(spirv, 0, spirv_len,
                          reinterpret_cast<jbyte*>(spirv_bytes.data()));
  if (env->ExceptionCheck() == JNI_TRUE) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkShaderModule module =
      srp::SrpInternModule(ctx, spirv_bytes.data(), spirv_bytes.size());
  if (module == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kDispatchFailed);
  }
  VkBuffer kp_vk = VK_NULL_HANDLE;
  VkDeviceMemory kp_mem = VK_NULL_HANDLE;
  srp::Status status = srp::Status::kDispatchFailed;
  if (MakeStagingBuffer(device, physical, 32, kp_ptr, &kp_vk, &kp_mem)) {
    status = srp::SrpKernelsCovGpu(
        ctx, static_cast<uint32_t>(grad_w), static_cast<uint32_t>(grad_h),
        static_cast<uint32_t>(cov_w), static_cast<uint32_t>(cov_h), grads_vk,
        cov_vk, kp_vk, module);
  }
  if (kp_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, kp_vk, nullptr);
  if (kp_mem != VK_NULL_HANDLE) vkFreeMemory(device, kp_mem, nullptr);
  // (module stays alive in the Context cache; residents stay on-device)
  return static_cast<jint>(status);
}

// Real GPU path: compute_s with a resident spread map. `flow_buffer`
// holds ntx*nty*2 staged floats; `s_res` receives ntx*nty floats and
// stays on-device for the robust-compute leg. Bit-identical to
// nativeSmap.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeSmapRes(
    JNIEnv* env, jclass /*cls*/, jlong handle, jobject flow_buffer,
    jlong s_res, jbyteArray spirv, jint ntx, jint nty, jint mt_bits,
    jint s1_bits, jint s2_bits) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || flow_buffer == nullptr || spirv == nullptr ||
      ntx <= 0 || nty <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const int64_t n = static_cast<int64_t>(ntx) * nty;
  VkBuffer s_vk = VK_NULL_HANDLE;
  if (srp::SrpResidentBuffer(ctx, s_res, static_cast<size_t>(n),
                             &s_vk) != srp::Status::kOk) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  float* flow_ptr =
      static_cast<float*>(env->GetDirectBufferAddress(flow_buffer));
  if (flow_ptr == nullptr ||
      env->GetDirectBufferCapacity(flow_buffer) < n * 2 * 4) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const jsize spirv_len = env->GetArrayLength(spirv);
  if (spirv_len <= 0 || spirv_len % 4 != 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  std::vector<uint8_t> spirv_bytes(static_cast<size_t>(spirv_len));
  env->GetByteArrayRegion(spirv, 0, spirv_len,
                          reinterpret_cast<jbyte*>(spirv_bytes.data()));
  if (env->ExceptionCheck() == JNI_TRUE) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkShaderModule module =
      srp::SrpInternModule(ctx, spirv_bytes.data(), spirv_bytes.size());
  if (module == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kDispatchFailed);
  }
  VkBuffer flow_vk = VK_NULL_HANDLE;
  VkDeviceMemory flow_mem = VK_NULL_HANDLE;
  srp::Status status = srp::Status::kDispatchFailed;
  if (MakeStagingBuffer(device, physical,
                        static_cast<VkDeviceSize>(n) * 2 * 4, flow_ptr,
                        &flow_vk, &flow_mem)) {
    status = srp::SrpSmapGpu(ctx, static_cast<uint32_t>(ntx),
                             static_cast<uint32_t>(nty), flow_vk, s_vk,
                             static_cast<uint32_t>(mt_bits),
                             static_cast<uint32_t>(s1_bits),
                             static_cast<uint32_t>(s2_bits), module);
  }
  if (flow_vk != VK_NULL_HANDLE) vkDestroyBuffer(device, flow_vk, nullptr);
  if (flow_mem != VK_NULL_HANDLE) vkFreeMemory(device, flow_mem, nullptr);
  // (module stays alive in the Context cache; the s resident stays on-device)
  return static_cast<jint>(status);
}

// Real GPU path: compute_robustness with a resident frame and resident s
// map. `comp_res` holds width*height raw floats (uploaded once per
// frame); `means_res`/`vars_res` are the init residents; `s_res` is the
// smap resident; flow/lut stage as before; `out_buffer` receives the
// gh*gw map (staged: the merge leg re-uploads it today). Bit-identical
// to nativeRobustCompute.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeRobustComputeFrameRes(
    JNIEnv* env, jclass /*cls*/, jlong handle, jlong comp_res,
    jlong means_res, jlong vars_res, jobject flow_buffer,
    jobject lut_buffer, jlong s_res, jobject out_buffer, jbyteArray spirv,
    jint width, jint height, jint tile_size, jint lut_n, jint noise_correction,
    jint t_bits, jint ftx, jint fty) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || flow_buffer == nullptr || lut_buffer == nullptr ||
      out_buffer == nullptr || spirv == nullptr || width < 2 || height < 2 ||
      tile_size < 2 || lut_n <= 0 || ftx <= 0 || fty <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const int64_t n = static_cast<int64_t>(width) / 2 * (height / 2);
  const int64_t nf = static_cast<int64_t>(ftx) * fty;
  VkBuffer comp_vk = VK_NULL_HANDLE, means_vk = VK_NULL_HANDLE,
           vars_vk = VK_NULL_HANDLE, s_vk = VK_NULL_HANDLE;
  if (srp::SrpResidentBuffer(ctx, comp_res,
                             static_cast<size_t>(width) * height,
                             &comp_vk) != srp::Status::kOk ||
      srp::SrpResidentBuffer(ctx, means_res, static_cast<size_t>(3 * n),
                             &means_vk) != srp::Status::kOk ||
      srp::SrpResidentBuffer(ctx, vars_res, static_cast<size_t>(3 * n),
                             &vars_vk) != srp::Status::kOk ||
      srp::SrpResidentBuffer(ctx, s_res, static_cast<size_t>(nf),
                             &s_vk) != srp::Status::kOk) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  float* flow_ptr =
      static_cast<float*>(env->GetDirectBufferAddress(flow_buffer));
  float* lut_ptr = static_cast<float*>(env->GetDirectBufferAddress(lut_buffer));
  float* out_ptr = static_cast<float*>(env->GetDirectBufferAddress(out_buffer));
  if (flow_ptr == nullptr || lut_ptr == nullptr || out_ptr == nullptr ||
      env->GetDirectBufferCapacity(flow_buffer) < nf * 2 * 4 ||
      env->GetDirectBufferCapacity(lut_buffer) <
          static_cast<int64_t>(lut_n) * 2 * 4 ||
      env->GetDirectBufferCapacity(out_buffer) < n * 4) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const jsize spirv_len = env->GetArrayLength(spirv);
  if (spirv_len <= 0 || spirv_len % 4 != 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  std::vector<uint8_t> spirv_bytes(static_cast<size_t>(spirv_len));
  env->GetByteArrayRegion(spirv, 0, spirv_len,
                          reinterpret_cast<jbyte*>(spirv_bytes.data()));
  if (env->ExceptionCheck() == JNI_TRUE) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkShaderModule module =
      srp::SrpInternModule(ctx, spirv_bytes.data(), spirv_bytes.size());
  if (module == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kDispatchFailed);
  }
  VkBuffer vks[3] = {};
  VkDeviceMemory mems[3] = {};
  float* ins[3] = {flow_ptr, lut_ptr, nullptr};
  const int64_t in_counts[3] = {nf * 2,
                                static_cast<int64_t>(lut_n) * 2, n};
  srp::Status status = srp::Status::kDispatchFailed;
  bool staged = true;
  for (int i = 0; i < 3; ++i) {
    if (!MakeStagingBuffer(device, physical,
                           static_cast<VkDeviceSize>(in_counts[i]) * 4, ins[i],
                           &vks[i], &mems[i])) {
      staged = false;
      break;
    }
  }
  if (staged) {
    status = srp::SrpRobustComputeGpu(
        ctx, static_cast<uint32_t>(width), static_cast<uint32_t>(height),
        comp_vk, means_vk, vars_vk, vks[0], vks[1], s_vk, vks[2],
        static_cast<uint32_t>(tile_size), static_cast<uint32_t>(lut_n),
        static_cast<uint32_t>(noise_correction),
        static_cast<uint32_t>(t_bits), static_cast<uint32_t>(ftx),
        static_cast<uint32_t>(fty), module);
    if (status == srp::Status::kOk) {
      void* mapped = nullptr;
      if (vkMapMemory(device, mems[2], 0, static_cast<VkDeviceSize>(n) * 4, 0,
                      &mapped) == VK_SUCCESS) {
        std::memcpy(out_ptr, mapped, static_cast<size_t>(n) * 4);
        vkUnmapMemory(device, mems[2]);
      } else {
        status = srp::Status::kDispatchFailed;
      }
    }
  }
  for (int i = 0; i < 3; ++i) {
    if (vks[i] != VK_NULL_HANDLE) vkDestroyBuffer(device, vks[i], nullptr);
    if (mems[i] != VK_NULL_HANDLE) vkFreeMemory(device, mems[i], nullptr);
  }
  // (module stays alive in the Context cache; residents stay on-device)
  return static_cast<jint>(status);
}

// Real GPU path: merge-splat with a resident frame and resident covs.
// `in_res` holds width*height raw floats, `cov_res` the guide covs;
// flow/r stage as before (tiny + guide-grid). Bit-identical to
// nativeMergeSplatRes.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeMergeSplatFrameRes(
    JNIEnv* env, jclass /*cls*/, jlong handle, jlong in_res,
    jobject flow_buffer, jlong cov_res, jobject r_buffer, jlong num_res,
    jlong den_res, jbyteArray spirv, jint lr_w, jint lr_h, jint hr_w,
    jint hr_h, jint cov_count, jint r_count, jint tile_size, jfloat scale,
    jint mode, jint ftx, jint fty) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || flow_buffer == nullptr || r_buffer == nullptr ||
      spirv == nullptr || lr_w <= 0 || lr_h <= 0 || hr_w <= 0 || hr_h <= 0 ||
      cov_count <= 0 || r_count <= 0 || tile_size <= 0 || scale <= 0 ||
      mode < 0 || mode > 1 || ftx <= 0 || fty <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const int64_t n_hr3 = static_cast<int64_t>(hr_w) * hr_h * 3;
  VkBuffer in_vk = VK_NULL_HANDLE, cov_vk = VK_NULL_HANDLE,
           num_vk = VK_NULL_HANDLE, den_vk = VK_NULL_HANDLE;
  if (srp::SrpResidentBuffer(ctx, in_res,
                             static_cast<size_t>(lr_w) * lr_h,
                             &in_vk) != srp::Status::kOk ||
      srp::SrpResidentBuffer(ctx, cov_res, static_cast<size_t>(cov_count),
                             &cov_vk) != srp::Status::kOk ||
      srp::SrpResidentBuffer(ctx, num_res, static_cast<size_t>(n_hr3),
                             &num_vk) != srp::Status::kOk ||
      srp::SrpResidentBuffer(ctx, den_res, static_cast<size_t>(n_hr3),
                             &den_vk) != srp::Status::kOk) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  float* flow_ptr =
      static_cast<float*>(env->GetDirectBufferAddress(flow_buffer));
  float* r_ptr = static_cast<float*>(env->GetDirectBufferAddress(r_buffer));
  if (flow_ptr == nullptr || r_ptr == nullptr ||
      env->GetDirectBufferCapacity(flow_buffer) <
          static_cast<int64_t>(ftx) * fty * 2 * 4 ||
      env->GetDirectBufferCapacity(r_buffer) <
          static_cast<int64_t>(r_count) * 4) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const jsize spirv_len = env->GetArrayLength(spirv);
  if (spirv_len <= 0 || spirv_len % 4 != 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  std::vector<uint8_t> spirv_bytes(static_cast<size_t>(spirv_len));
  env->GetByteArrayRegion(spirv, 0, spirv_len,
                          reinterpret_cast<jbyte*>(spirv_bytes.data()));
  if (env->ExceptionCheck() == JNI_TRUE) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkShaderModule module =
      srp::SrpInternModule(ctx, spirv_bytes.data(), spirv_bytes.size());
  if (module == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kDispatchFailed);
  }
  VkBuffer vks[2] = {};
  VkDeviceMemory mems[2] = {};
  float* ins[2] = {flow_ptr, r_ptr};
  const int64_t in_counts[2] = {static_cast<int64_t>(ftx) * fty * 2,
                                static_cast<int64_t>(r_count)};
  srp::Status status = srp::Status::kDispatchFailed;
  bool staged = true;
  for (int i = 0; i < 2; ++i) {
    if (!MakeStagingBuffer(device, physical,
                           static_cast<VkDeviceSize>(in_counts[i]) * 4, ins[i],
                           &vks[i], &mems[i])) {
      staged = false;
      break;
    }
  }
  if (staged) {
    status = srp::SrpMergeSplatGpu(
        ctx, static_cast<uint32_t>(lr_w), static_cast<uint32_t>(lr_h),
        static_cast<uint32_t>(hr_w), static_cast<uint32_t>(hr_h), in_vk,
        vks[0], cov_vk, vks[1], num_vk, den_vk,
        static_cast<uint32_t>(tile_size), scale,
        static_cast<uint32_t>(mode), static_cast<uint32_t>(ftx),
        static_cast<uint32_t>(fty), module);
  }
  for (int i = 0; i < 2; ++i) {
    if (vks[i] != VK_NULL_HANDLE) vkDestroyBuffer(device, vks[i], nullptr);
    if (mems[i] != VK_NULL_HANDLE) vkFreeMemory(device, mems[i], nullptr);
  }
  // (module stays alive in the Context cache; residents stay on-device)
  return static_cast<jint>(status);
}

// Real GPU path: compute_s (op 6) over caller-owned direct buffers.
// `flow_buffer` holds ntx*nty*2 interleaved-pair floats (finite only);
// `s_buffer` receives ntx*nty floats. Mt/s1/s2 ride by bit pattern.
// Returns srp::Status.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeSmap(
    JNIEnv* env, jclass /*cls*/, jlong handle, jobject flow_buffer,
    jobject s_buffer, jbyteArray spirv, jint ntx, jint nty, jint mt_bits,
    jint s1_bits, jint s2_bits) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || flow_buffer == nullptr || s_buffer == nullptr ||
      spirv == nullptr || ntx <= 0 || nty <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const int64_t n = static_cast<int64_t>(ntx) * nty;
  jobject ins[1] = {flow_buffer};
  const int64_t counts[1] = {n * 2};
  const srp::Status status = StageRobust(
      env, ctx, device, physical, ins, counts, 1, s_buffer, n, spirv,
      [&](VkBuffer* staged, VkBuffer out, VkShaderModule module) {
        return srp::SrpSmapGpu(ctx, static_cast<uint32_t>(ntx),
                               static_cast<uint32_t>(nty), staged[0], out,
                               static_cast<uint32_t>(mt_bits),
                               static_cast<uint32_t>(s1_bits),
                               static_cast<uint32_t>(s2_bits), module);
      });
  return static_cast<jint>(status);
}

// Real GPU path: merge splat (merge_splat.comp, Alg.4) over caller-owned
// direct buffers. `comp_buffer` holds lrW*lrH raw floats; `flow_buffer`
// holds ftx*fty*2 pairs; `cov_buffer` holds the merge-ready vec4
// covariances; `r_buffer` holds the guide-grid robustness map;
// `num_buffer`/`den_buffer` hold hrW*hrH*3 floats each and accumulate in
// place (staged with upload, downloaded after). `spirv` holds the
// compiled shaders/merge_splat.comp module. Manual staging (six
// buffers), like nativeGrad. Returns srp::Status.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeMergeSplat(
    JNIEnv* env, jclass /*cls*/, jlong handle, jobject comp_buffer,
    jobject flow_buffer, jobject cov_buffer, jobject r_buffer,
    jobject num_buffer, jobject den_buffer, jbyteArray spirv, jint lr_w,
    jint lr_h, jint hr_w, jint hr_h, jint cov_count, jint r_count,
    jint tile_size, jfloat scale, jint mode, jint ftx, jint fty) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || comp_buffer == nullptr || flow_buffer == nullptr ||
      cov_buffer == nullptr || r_buffer == nullptr || num_buffer == nullptr ||
      den_buffer == nullptr || spirv == nullptr || lr_w <= 0 || lr_h <= 0 ||
      hr_w <= 0 || hr_h <= 0 || cov_count <= 0 || r_count <= 0 ||
      tile_size <= 0 || scale <= 0 || mode < 0 || mode > 1 || ftx <= 0 ||
      fty <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const int64_t n_lr = static_cast<int64_t>(lr_w) * lr_h;
  const int64_t n_hr3 =
      static_cast<int64_t>(hr_w) * hr_h * 3;
  float* comp_ptr = static_cast<float*>(env->GetDirectBufferAddress(comp_buffer));
  float* flow_ptr = static_cast<float*>(env->GetDirectBufferAddress(flow_buffer));
  float* cov_ptr = static_cast<float*>(env->GetDirectBufferAddress(cov_buffer));
  float* r_ptr = static_cast<float*>(env->GetDirectBufferAddress(r_buffer));
  float* num_ptr = static_cast<float*>(env->GetDirectBufferAddress(num_buffer));
  float* den_ptr = static_cast<float*>(env->GetDirectBufferAddress(den_buffer));
  if (comp_ptr == nullptr || flow_ptr == nullptr || cov_ptr == nullptr ||
      r_ptr == nullptr || num_ptr == nullptr || den_ptr == nullptr ||
      env->GetDirectBufferCapacity(comp_buffer) < n_lr * 4 ||
      env->GetDirectBufferCapacity(flow_buffer) <
          static_cast<int64_t>(ftx) * fty * 2 * 4 ||
      env->GetDirectBufferCapacity(cov_buffer) <
          static_cast<int64_t>(cov_count) * 4 ||
      env->GetDirectBufferCapacity(r_buffer) <
          static_cast<int64_t>(r_count) * 4 ||
      env->GetDirectBufferCapacity(num_buffer) < n_hr3 * 4 ||
      env->GetDirectBufferCapacity(den_buffer) < n_hr3 * 4) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const jsize spirv_len = env->GetArrayLength(spirv);
  if (spirv_len <= 0 || spirv_len % 4 != 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  std::vector<uint8_t> spirv_bytes(static_cast<size_t>(spirv_len));
  env->GetByteArrayRegion(spirv, 0, spirv_len,
                          reinterpret_cast<jbyte*>(spirv_bytes.data()));
  if (env->ExceptionCheck() == JNI_TRUE) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  // Context-interned stage module (created once per process, owned by
  // the Context; replaces per-call create/destroy).
  VkShaderModule module =
      srp::SrpInternModule(ctx, spirv_bytes.data(), spirv_bytes.size());
  if (module == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kDispatchFailed);
  }
  VkBuffer vks[6] = {};
  VkDeviceMemory mems[6] = {};
  float* ins[4] = {comp_ptr, flow_ptr, cov_ptr, r_ptr};
  const int64_t in_counts[4] = {
      n_lr,
      static_cast<int64_t>(ftx) * fty * 2,
      static_cast<int64_t>(cov_count),
      static_cast<int64_t>(r_count)};
  srp::Status status = srp::Status::kDispatchFailed;
  bool staged = true;
  for (int i = 0; i < 4; ++i) {
    if (!MakeStagingBuffer(device, physical,
                           static_cast<VkDeviceSize>(in_counts[i]) * 4, ins[i],
                           &vks[i], &mems[i])) {
      staged = false;
      break;
    }
  }
  if (staged &&
      MakeStagingBuffer(device, physical,
                        static_cast<VkDeviceSize>(n_hr3) * 4, num_ptr, &vks[4],
                        &mems[4]) &&
      MakeStagingBuffer(device, physical,
                        static_cast<VkDeviceSize>(n_hr3) * 4, den_ptr, &vks[5],
                        &mems[5])) {
    status = srp::SrpMergeSplatGpu(
        ctx, static_cast<uint32_t>(lr_w), static_cast<uint32_t>(lr_h),
        static_cast<uint32_t>(hr_w), static_cast<uint32_t>(hr_h), vks[0],
        vks[1], vks[2], vks[3], vks[4], vks[5], static_cast<uint32_t>(tile_size),
        static_cast<float>(scale), static_cast<uint32_t>(mode),
        static_cast<uint32_t>(ftx), static_cast<uint32_t>(fty), module);
    if (status == srp::Status::kOk) {
      void* mapped = nullptr;
      if (vkMapMemory(device, mems[4], 0,
                      static_cast<VkDeviceSize>(n_hr3) * 4, 0, &mapped) ==
              VK_SUCCESS) {
        std::memcpy(num_ptr, mapped, static_cast<size_t>(n_hr3) * 4);
        vkUnmapMemory(device, mems[4]);
        mapped = nullptr;
      } else {
        status = srp::Status::kDispatchFailed;
      }
      if (status == srp::Status::kOk &&
          vkMapMemory(device, mems[5], 0,
                      static_cast<VkDeviceSize>(n_hr3) * 4, 0, &mapped) ==
              VK_SUCCESS) {
        std::memcpy(den_ptr, mapped, static_cast<size_t>(n_hr3) * 4);
        vkUnmapMemory(device, mems[5]);
      } else if (status == srp::Status::kOk) {
        status = srp::Status::kDispatchFailed;
      }
    }
  }
  for (int i = 0; i < 6; ++i) {
    if (vks[i] != VK_NULL_HANDLE) vkDestroyBuffer(device, vks[i], nullptr);
    if (mems[i] != VK_NULL_HANDLE) vkFreeMemory(device, mems[i], nullptr);
  }
  // (module stays alive in the Context cache)
  return static_cast<jint>(status);
}

// Resident device buffers (merge num/den across frames): create returns a
// positive handle (0 on failure) to a zero-filled buffer; free returns it
// to the pool; download copies it to a host direct buffer (with the
// MoltenVK invalidate pooled reads need). Handles are context-scoped
// small integers, never 0.
JNIEXPORT jlong JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeCreateResident(
    JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jlong count) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || count <= 0) return 0;
  return static_cast<jlong>(
      srp::SrpCreateResident(ctx, static_cast<size_t>(count)));
}

JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeFreeResident(
    JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jlong resident) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr) return static_cast<jint>(srp::Status::kInitFailed);
  return static_cast<jint>(srp::SrpFreeResident(ctx, resident));
}

JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeDownloadResident(
    JNIEnv* env, jclass /*cls*/, jlong handle, jlong resident,
    jobject out_buffer, jlong count) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || out_buffer == nullptr || count <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  float* out_ptr =
      static_cast<float*>(env->GetDirectBufferAddress(out_buffer));
  if (out_ptr == nullptr ||
      env->GetDirectBufferCapacity(out_buffer) < count * 4) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  return static_cast<jint>(srp::SrpDownloadResident(
      ctx, resident, out_ptr, static_cast<size_t>(count)));
}

JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeUploadResident(
    JNIEnv* env, jclass /*cls*/, jlong handle, jlong resident,
    jobject in_buffer, jlong count) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || in_buffer == nullptr || count <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  float* in_ptr =
      static_cast<float*>(env->GetDirectBufferAddress(in_buffer));
  if (in_ptr == nullptr ||
      env->GetDirectBufferCapacity(in_buffer) < count * 4) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  return static_cast<jint>(srp::SrpUploadResident(
      ctx, resident, in_ptr, static_cast<size_t>(count)));
}

// Merge-splat with device-resident accumulators: stages only the four
// small per-frame inputs (comp/flow/cov/r); num/den stay on-device across
// frames (same dispatches, same order, same zeros as the staged path, so
// bit-identical). No readback.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeMergeSplatRes(
    JNIEnv* env, jclass /*cls*/, jlong handle, jobject comp_buffer,
    jobject flow_buffer, jobject cov_buffer, jobject r_buffer, jlong num_res,
    jlong den_res, jbyteArray spirv, jint lr_w, jint lr_h, jint hr_w,
    jint hr_h, jint cov_count, jint r_count, jint tile_size, jfloat scale,
    jint mode, jint ftx, jint fty) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || comp_buffer == nullptr || flow_buffer == nullptr ||
      cov_buffer == nullptr || r_buffer == nullptr || spirv == nullptr ||
      lr_w <= 0 || lr_h <= 0 || hr_w <= 0 || hr_h <= 0 || cov_count <= 0 ||
      r_count <= 0 || tile_size <= 0 || scale <= 0 || mode < 0 || mode > 1 ||
      ftx <= 0 || fty <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const int64_t n_lr = static_cast<int64_t>(lr_w) * lr_h;
  const int64_t n_hr3 = static_cast<int64_t>(hr_w) * hr_h * 3;
  float* comp_ptr =
      static_cast<float*>(env->GetDirectBufferAddress(comp_buffer));
  float* flow_ptr =
      static_cast<float*>(env->GetDirectBufferAddress(flow_buffer));
  float* cov_ptr = static_cast<float*>(env->GetDirectBufferAddress(cov_buffer));
  float* r_ptr = static_cast<float*>(env->GetDirectBufferAddress(r_buffer));
  if (comp_ptr == nullptr || flow_ptr == nullptr || cov_ptr == nullptr ||
      r_ptr == nullptr ||
      env->GetDirectBufferCapacity(comp_buffer) < n_lr * 4 ||
      env->GetDirectBufferCapacity(flow_buffer) <
          static_cast<int64_t>(ftx) * fty * 2 * 4 ||
      env->GetDirectBufferCapacity(cov_buffer) <
          static_cast<int64_t>(cov_count) * 4 ||
      env->GetDirectBufferCapacity(r_buffer) <
          static_cast<int64_t>(r_count) * 4) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkBuffer num_vk = VK_NULL_HANDLE, den_vk = VK_NULL_HANDLE;
  if (srp::SrpResidentBuffer(ctx, num_res, static_cast<size_t>(n_hr3),
                             &num_vk) != srp::Status::kOk ||
      srp::SrpResidentBuffer(ctx, den_res, static_cast<size_t>(n_hr3),
                             &den_vk) != srp::Status::kOk) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const jsize spirv_len = env->GetArrayLength(spirv);
  if (spirv_len <= 0 || spirv_len % 4 != 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  std::vector<uint8_t> spirv_bytes(static_cast<size_t>(spirv_len));
  env->GetByteArrayRegion(spirv, 0, spirv_len,
                          reinterpret_cast<jbyte*>(spirv_bytes.data()));
  if (env->ExceptionCheck() == JNI_TRUE) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkShaderModule module =
      srp::SrpInternModule(ctx, spirv_bytes.data(), spirv_bytes.size());
  if (module == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kDispatchFailed);
  }
  VkBuffer vks[4] = {};
  VkDeviceMemory mems[4] = {};
  float* ins[4] = {comp_ptr, flow_ptr, cov_ptr, r_ptr};
  const int64_t in_counts[4] = {
      n_lr,
      static_cast<int64_t>(ftx) * fty * 2,
      static_cast<int64_t>(cov_count),
      static_cast<int64_t>(r_count)};
  srp::Status status = srp::Status::kDispatchFailed;
  bool staged = true;
  for (int i = 0; i < 4; ++i) {
    if (!MakeStagingBuffer(device, physical,
                           static_cast<VkDeviceSize>(in_counts[i]) * 4, ins[i],
                           &vks[i], &mems[i])) {
      staged = false;
      break;
    }
  }
  if (staged) {
    status = srp::SrpMergeSplatGpu(
        ctx, static_cast<uint32_t>(lr_w), static_cast<uint32_t>(lr_h),
        static_cast<uint32_t>(hr_w), static_cast<uint32_t>(hr_h), vks[0],
        vks[1], vks[2], vks[3], num_vk, den_vk,
        static_cast<uint32_t>(tile_size), static_cast<float>(scale),
        static_cast<uint32_t>(mode), static_cast<uint32_t>(ftx),
        static_cast<uint32_t>(fty), module);
  }
  for (int i = 0; i < 4; ++i) {
    if (vks[i] != VK_NULL_HANDLE) vkDestroyBuffer(device, vks[i], nullptr);
    if (mems[i] != VK_NULL_HANDLE) vkFreeMemory(device, mems[i], nullptr);
  }
  // (module stays alive in the Context cache; residents stay on-device)
  return static_cast<jint>(status);
}

// Divide with device-resident accumulators (in-place num=num/den, like
// the staged path). No staging at all: zero host traffic.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeDivideRes(
    JNIEnv* env, jclass /*cls*/, jlong handle, jlong num_res, jlong den_res,
    jbyteArray spirv, jint count) {
  VkDevice device = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  srp::Context* ctx = CorrContext(handle, &device, &physical);
  if (ctx == nullptr || spirv == nullptr || count <= 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkBuffer num_vk = VK_NULL_HANDLE, den_vk = VK_NULL_HANDLE;
  if (srp::SrpResidentBuffer(ctx, num_res, static_cast<size_t>(count),
                             &num_vk) != srp::Status::kOk ||
      srp::SrpResidentBuffer(ctx, den_res, static_cast<size_t>(count),
                             &den_vk) != srp::Status::kOk) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  const jsize spirv_len = env->GetArrayLength(spirv);
  if (spirv_len <= 0 || spirv_len % 4 != 0) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  std::vector<uint8_t> spirv_bytes(static_cast<size_t>(spirv_len));
  env->GetByteArrayRegion(spirv, 0, spirv_len,
                          reinterpret_cast<jbyte*>(spirv_bytes.data()));
  if (env->ExceptionCheck() == JNI_TRUE) {
    return static_cast<jint>(srp::Status::kInitFailed);
  }
  VkShaderModule module =
      srp::SrpInternModule(ctx, spirv_bytes.data(), spirv_bytes.size());
  if (module == VK_NULL_HANDLE) {
    return static_cast<jint>(srp::Status::kDispatchFailed);
  }
  return static_cast<jint>(srp::SrpDivideGpu(ctx,
                                             static_cast<uint32_t>(count),
                                             num_vk, den_vk, num_vk, module));
}

// Raw CFA reader (TinyDNG v3 loader, same sources as the writer).
// nativeDngMeta returns null on failure, else an int[12] with
// width,height,black[4],white,cfa[4],noiseCount. nativeDngNoise returns
// null on failure, else a double[8] with the NoiseProfile (S,O) pairs.
// nativeDngPixels decodes into the caller's direct uint16 buffer
// (2*w*h bytes for the full frame, 2*rw*rh for an even-aligned region)
// and returns the srp::DngReadStatus ordinal (0 = ok).
JNIEXPORT jintArray JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeDngMeta(
    JNIEnv* env, jclass /*cls*/, jstring path) {
  if (path == nullptr) return nullptr;
  const char* cpath = env->GetStringUTFChars(path, nullptr);
  if (cpath == nullptr) return nullptr;
  srp::SrpDngMeta meta{};
  const srp::DngReadStatus status = srp::SrpReadDngMeta(cpath, &meta);
  env->ReleaseStringUTFChars(path, cpath);
  if (status != srp::DngReadStatus::kOk) return nullptr;
  jint out[12] = {
      static_cast<jint>(meta.width), static_cast<jint>(meta.height),
      meta.black_level[0], meta.black_level[1], meta.black_level[2],
      meta.black_level[3], static_cast<jint>(meta.white_level), meta.cfa[0],
      meta.cfa[1], meta.cfa[2], meta.cfa[3],
      static_cast<jint>(meta.noise_count)};
  jintArray arr = env->NewIntArray(12);
  if (arr == nullptr) return nullptr;
  env->SetIntArrayRegion(arr, 0, 12, out);
  return arr;
}

JNIEXPORT jdoubleArray JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeDngNoise(
    JNIEnv* env, jclass /*cls*/, jstring path) {
  if (path == nullptr) return nullptr;
  const char* cpath = env->GetStringUTFChars(path, nullptr);
  if (cpath == nullptr) return nullptr;
  srp::SrpDngMeta meta{};
  const srp::DngReadStatus status = srp::SrpReadDngMeta(cpath, &meta);
  env->ReleaseStringUTFChars(path, cpath);
  if (status != srp::DngReadStatus::kOk) return nullptr;
  jdouble out[8];
  for (int i = 0; i < 8; ++i) out[i] = meta.noise[i];
  jdoubleArray arr = env->NewDoubleArray(8);
  if (arr == nullptr) return nullptr;
  env->SetDoubleArrayRegion(arr, 0, 8, out);
  return arr;
}

JNIEXPORT jdoubleArray JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeDngColorMeta(
    JNIEnv* env, jclass /*cls*/, jstring path) {
  if (path == nullptr) return nullptr;
  const char* cpath = env->GetStringUTFChars(path, nullptr);
  if (cpath == nullptr) return nullptr;
  srp::SrpDngMeta meta{};
  const srp::DngReadStatus status = srp::SrpReadDngColorMeta(cpath, &meta);
  env->ReleaseStringUTFChars(path, cpath);
  if (status != srp::DngReadStatus::kOk) return nullptr;
  // [hasMatrix, m0..m8, hasNeutral, n0..n2, illuminant] (15).
  jdouble out[15] = {0};
  out[0] = meta.color_matrix_present ? 1.0 : 0.0;
  for (int i = 0; i < 9; ++i) out[1 + i] = meta.color_matrix1[i];
  out[10] = meta.has_as_shot_neutral ? 1.0 : 0.0;
  for (int i = 0; i < 3; ++i) out[11 + i] = meta.as_shot_neutral[i];
  out[14] = static_cast<jdouble>(meta.calibration_illuminant1);
  jdoubleArray arr = env->NewDoubleArray(15);
  if (arr == nullptr) return nullptr;
  env->SetDoubleArrayRegion(arr, 0, 15, out);
  return arr;
}

JNIEXPORT jobjectArray JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeDngMakeModel(
    JNIEnv* env, jclass /*cls*/, jstring path) {
  if (path == nullptr) return nullptr;
  const char* cpath = env->GetStringUTFChars(path, nullptr);
  if (cpath == nullptr) return nullptr;
  srp::SrpDngMeta meta{};
  const srp::DngReadStatus status = srp::SrpReadDngColorMeta(cpath, &meta);
  env->ReleaseStringUTFChars(path, cpath);
  if (status != srp::DngReadStatus::kOk) return nullptr;
  jclass str_cls = env->FindClass("java/lang/String");
  if (str_cls == nullptr) return nullptr;
  jobjectArray arr = env->NewObjectArray(2, str_cls, nullptr);
  if (arr == nullptr) return nullptr;
  env->SetObjectArrayElement(arr, 0, env->NewStringUTF(meta.make));
  env->SetObjectArrayElement(arr, 1, env->NewStringUTF(meta.model));
  return arr;
}

JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeDngPixels(
    JNIEnv* env, jclass /*cls*/, jstring path, jobject pixels_buffer,
    jint width, jint height, jint reg_x, jint reg_y, jint reg_w, jint reg_h) {
  if (path == nullptr || pixels_buffer == nullptr || width <= 0 ||
      height <= 0) {
    return static_cast<jint>(srp::DngReadStatus::kBadArgument);
  }
  const bool full = (reg_w == 0 && reg_h == 0);
  const int64_t want =
      (full ? static_cast<int64_t>(width) * height
            : static_cast<int64_t>(reg_w) * reg_h) *
      2;
  uint16_t* dst = static_cast<uint16_t*>(
      env->GetDirectBufferAddress(pixels_buffer));
  if (dst == nullptr || env->GetDirectBufferCapacity(pixels_buffer) < want) {
    return static_cast<jint>(srp::DngReadStatus::kBadArgument);
  }
  const char* cpath = env->GetStringUTFChars(path, nullptr);
  if (cpath == nullptr) {
    return static_cast<jint>(srp::DngReadStatus::kBadArgument);
  }
  const srp::DngReadStatus status = srp::SrpReadDngPixels(
      cpath, dst, static_cast<uint32_t>(reg_x), static_cast<uint32_t>(reg_y),
      static_cast<uint32_t>(reg_w), static_cast<uint32_t>(reg_h));
  env->ReleaseStringUTFChars(path, cpath);
  return static_cast<jint>(status);
}

// nativeDngNormalized decodes + flips + normalizes into the caller's
// direct float buffer (4*w*h bytes, row-major) in one pass and returns
// the srp::DngReadStatus ordinal (0 = ok). Same bytes as decodeFull +
// toUnsigned + flipToRggb + normalize; unsupported CFA reports
// kBadArgument like the Kotlin require.
JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeDngNormalized(
    JNIEnv* env, jclass /*cls*/, jstring path, jobject out_buffer, jint width,
    jint height, jintArray black_array, jint white, jintArray cfa_array) {
  if (path == nullptr || out_buffer == nullptr || black_array == nullptr ||
      cfa_array == nullptr || width <= 0 || height <= 0) {
    return static_cast<jint>(srp::DngReadStatus::kBadArgument);
  }
  const int64_t want = static_cast<int64_t>(width) * height * 4;
  float* dst = static_cast<float*>(env->GetDirectBufferAddress(out_buffer));
  if (dst == nullptr || env->GetDirectBufferCapacity(out_buffer) < want) {
    return static_cast<jint>(srp::DngReadStatus::kBadArgument);
  }
  if (env->GetArrayLength(black_array) != 4 ||
      env->GetArrayLength(cfa_array) != 4) {
    return static_cast<jint>(srp::DngReadStatus::kBadArgument);
  }
  jint black[4];
  jint cfa[4];
  env->GetIntArrayRegion(black_array, 0, 4, black);
  env->GetIntArrayRegion(cfa_array, 0, 4, cfa);
  if (env->ExceptionCheck() == JNI_TRUE) {
    return static_cast<jint>(srp::DngReadStatus::kBadArgument);
  }
  const char* cpath = env->GetStringUTFChars(path, nullptr);
  if (cpath == nullptr) {
    return static_cast<jint>(srp::DngReadStatus::kBadArgument);
  }
  int32_t c_black[4];
  int32_t c_cfa[4];
  for (int i = 0; i < 4; ++i) {
    c_black[i] = static_cast<int32_t>(black[i]);
    c_cfa[i] = static_cast<int32_t>(cfa[i]);
  }
  const srp::DngReadStatus status = srp::SrpReadDngNormalized(
      cpath, dst, static_cast<uint32_t>(width), static_cast<uint32_t>(height),
      c_black, static_cast<int32_t>(white), c_cfa);
  env->ReleaseStringUTFChars(path, cpath);
  return static_cast<jint>(status);
}

}  // extern "C"
