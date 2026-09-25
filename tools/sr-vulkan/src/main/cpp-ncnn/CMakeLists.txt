# SPDX-FileCopyrightText: 2026 RawLens contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Host ncnnMl build: compiles the SAME app JNI bridge + FlowNet custom layers
# (app/src/main/cpp, referenced by path so the file is literally shared)
# against upstream ncnn for Linux/macOS. Produces libncnnMl.{dylib,so} with
# the same JNI entry points the Android build exports, so the ported
# KernelNetNcnnProcessor / FlowNetNcnnProcessor / RawNindNcnnProcessor load
# it through their unchanged `loadLibrary("ncnnMl")` lines.
#
# Upstream ncnn pin: exact master SHA (2026-09-25). The newest release tag
# (20260526) predates the VkMat::nstep API the shared FlowNet Vulkan layers
# use, so the pin tracks master at a verified commit instead. Note
# 1.0.20260817 in the vendored platform.h is a BUILD DATE stamp, not an
# upstream release tag (ncnn stamps the build date into the version).
#
#   cmake -S tools/sr-vulkan/src/main/cpp-ncnn -B build/ncnn-host -G Ninja \
#     -DCMAKE_BUILD_TYPE=Release && cmake --build build/ncnn-host
cmake_minimum_required(VERSION 3.22)
project(ncnnMlHost C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# System glslang: the pinned source archive excludes the glslang submodule,
# so ncnn compiles its shaders against the system library (brew install
# glslang on macOS; distro package on Linux).
if(APPLE)
    list(APPEND CMAKE_PREFIX_PATH "/opt/homebrew" "/usr/local")
endif()
find_package(glslang REQUIRED)

include(FetchContent)
set(NCNN_SHA "c6b351b56fbe32e0381ae00331e3df649b20d7b7")
set(NCNN_URL "https://github.com/Tencent/ncnn/archive/${NCNN_SHA}.tar.gz")
set(NCNN_SHA256 "a48593360583c333ffb70f7167845a29c491e37248fb6bb6a6f5f7db01b7c241")
set(NCNN_VULKAN ON CACHE BOOL "" FORCE)
set(NCNN_SYSTEM_GLSLANG ON CACHE BOOL "" FORCE)
set(NCNN_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(NCNN_SHARED_LIB OFF CACHE BOOL "" FORCE)
FetchContent_Declare(ncnn URL ${NCNN_URL} URL_HASH SHA256=${NCNN_SHA256})
FetchContent_MakeAvailable(ncnn)

set(APP_CPP "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../app/src/main/cpp")
if(NOT EXISTS "${APP_CPP}/ncnnMl.cpp")
    message(FATAL_ERROR "shared app sources not found at ${APP_CPP} (repo layout changed?)")
endif()

# FlowNet Vulkan shader headers: same generator the app build runs at
# configure time (same script, same outputs).
find_package(Python3 REQUIRED COMPONENTS Interpreter)
set(FLOW_GEN_DIR "${CMAKE_CURRENT_BINARY_DIR}/flownet-generated")
file(MAKE_DIRECTORY "${FLOW_GEN_DIR}")
foreach(shader flownetcorrfused flownetcorrlookup flownetupsample)
    execute_process(
        COMMAND "${Python3_EXECUTABLE}" "${APP_CPP}/flownet/gen_shader_header.py"
            "${APP_CPP}/flownet/layer/vulkan/shader/${shader}.comp" "${shader}_comp_data"
        OUTPUT_FILE "${FLOW_GEN_DIR}/${shader}_comp.h"
        COMMAND_ERROR_IS_FATAL ANY)
endforeach()

add_library(ncnnMl SHARED
    "${APP_CPP}/ncnnMl.cpp"
    "${APP_CPP}/flownet/layer/flownetupsample.cpp"
    "${APP_CPP}/flownet/layer/flownetcorrlookup.cpp"
    "${APP_CPP}/flownet/layer/flownetcorrfused.cpp"
    "${APP_CPP}/flownet/layer/vulkan/flownetupsample_vulkan.cpp"
    "${APP_CPP}/flownet/layer/vulkan/flownetcorrlookup_vulkan.cpp"
    "${APP_CPP}/flownet/layer/vulkan/flownetcorrfused_vulkan.cpp")
target_include_directories(ncnnMl PRIVATE
    "${ncnn_SOURCE_DIR}/src"
    "${ncnn_BINARY_DIR}/src"
    "${FLOW_GEN_DIR}"
    "${APP_CPP}/flownet"
    "${APP_CPP}/flownet/layer"
    "${APP_CPP}/flownet/layer/vulkan")
# Mirror the app target flags (-fno-rtti -fno-exceptions) plus warnings.
target_compile_options(ncnnMl PRIVATE -fno-rtti -fno-exceptions -Wall)
target_link_libraries(ncnnMl PRIVATE ncnn)

find_package(JNI REQUIRED)
target_include_directories(ncnnMl PRIVATE ${JNI_INCLUDE_DIRS})

if(APPLE)
    # Direct MoltenVK link (same discovery as ../cpp): ncnn's Apple driver
    # fallback (load_vulkan_apple_moltenvk) picks up the statically linked
    # entry points, so no loader install or env config is needed at runtime.
    if(NOT SRVK_VULKAN_INCLUDE)
        if(DEFINED ENV{VULKAN_SDK})
            set(SRVK_VULKAN_INCLUDE "$ENV{VULKAN_SDK}/include")
        else()
            set(SRVK_VULKAN_INCLUDE "/opt/homebrew/include")
        endif()
    endif()
    if(NOT SRVK_MOLTENVK_LIB)
        if(DEFINED ENV{VULKAN_SDK})
            set(SRVK_MOLTENVK_LIB "$ENV{VULKAN_SDK}/lib/libMoltenVK.dylib")
        else()
            set(SRVK_MOLTENVK_LIB "/opt/homebrew/lib/libMoltenVK.dylib")
        endif()
    endif()
    if(NOT EXISTS "${SRVK_MOLTENVK_LIB}")
        message(FATAL_ERROR "MoltenVK not found at ${SRVK_MOLTENVK_LIB} "
            "(brew install molten-vk, or set SRVK_MOLTENVK_LIB)")
    endif()
    target_link_libraries(ncnnMl PRIVATE ${SRVK_MOLTENVK_LIB})
else()
    find_package(Vulkan REQUIRED)
    target_link_libraries(ncnnMl PRIVATE Vulkan::Vulkan)
endif()
