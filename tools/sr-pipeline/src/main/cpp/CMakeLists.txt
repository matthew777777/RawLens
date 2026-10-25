cmake_minimum_required(VERSION 3.22.1)
project(srpipeline)

# Super-resolution pipeline scaffold (Phase 1): context + trivial dispatch stub.
# Follows the app pattern (app/src/main/cpp/CMakeLists.txt): NDK-provided
# <vulkan/vulkan.h> with find_library(vulkan), C++17, 16 KB page size.
add_library(srpipeline SHARED
    srp_context.cpp
    srp_pipeline.cpp
    srp_fft.cpp
    srp_dng.cpp
    srp_jni.cpp)

# Pinned TinyDNG v3 writer: the SAME vendored sources the app builds
# (app/src/main/cpp/deps/tinydng), so desktop and Android emit identical
# files. Source list and compile definitions mirror the app's tinydng_v3
# target; keep them in sync when the app updates.
set(SRP_TINYDNG_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../app/src/main/cpp/deps/tinydng")
if(NOT EXISTS "${SRP_TINYDNG_DIR}/tinydng.h")
  message(FATAL_ERROR "srpipeline: vendored TinyDNG not found at ${SRP_TINYDNG_DIR}")
endif()
add_library(srp_tinydng_v3 STATIC
    ${SRP_TINYDNG_DIR}/tinydng_api.c ${SRP_TINYDNG_DIR}/tinydng_io.c
    ${SRP_TINYDNG_DIR}/tinydng_tiff.c ${SRP_TINYDNG_DIR}/tinydng_dng.c
    ${SRP_TINYDNG_DIR}/tinydng_codec.c ${SRP_TINYDNG_DIR}/tinydng_write.c
    ${SRP_TINYDNG_DIR}/tinydng_psd.c ${SRP_TINYDNG_DIR}/tinydng_psd_write.c
    ${SRP_TINYDNG_DIR}/tiny_dng_ljpeg92_v2.c)
target_include_directories(srp_tinydng_v3 PUBLIC ${SRP_TINYDNG_DIR})
target_compile_features(srp_tinydng_v3 PUBLIC c_std_11)
target_compile_definitions(srp_tinydng_v3 PRIVATE TINYDNG_NO_ZIP
    TINYDNG_NO_BASELINE_JPEG TINYDNG_NO_PSD TINYDNG_DISABLE_THREADS)
target_include_directories(srpipeline PRIVATE ${SRP_TINYDNG_DIR})
# VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR is beta-guarded in
# vulkan_core.h: without this, the flag macro is missing while the extension
# name macro is present, and instance creation enables the extension without
# the enumerate bit — MoltenVK loaders reject that with
# VK_ERROR_INCOMPATIBLE_DRIVER. Harmless on NDK headers.
target_compile_definitions(srpipeline PRIVATE VK_ENABLE_BETA_EXTENSIONS)

set_target_properties(srpipeline PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF)

target_include_directories(srpipeline PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
# Strict FP contract: the C++ normalize loop (SrpReadDngNormalized) must
# stay bit-identical to the Kotlin formula on every ABI (desktop x86_64,
# Android arm64-v8a + armeabi-v7a), so contraction and fast-math are off
# here — matching the app's other parity-sensitive targets.
target_compile_options(srpipeline PRIVATE
    -Wall -Wextra -Werror=return-type -O3 -ffp-contract=off -fno-fast-math)
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    # Explicit thin-LTO flags (CMake's IPO probe needs the gold
    # linker, removed from NDK r27; clang/LLD thin LTO works).
    target_compile_options(srpipeline PRIVATE -flto=thin)
    target_link_options(srpipeline PRIVATE -flto=thin)
endif()

find_library(SRP_VULKAN_LIB vulkan REQUIRED)
find_library(SRP_LOG_LIB log REQUIRED)
target_link_libraries(srpipeline PRIVATE srp_tinydng_v3 ${SRP_VULKAN_LIB} ${SRP_LOG_LIB})
target_link_options(srpipeline PRIVATE "-Wl,-z,max-page-size=16384")
