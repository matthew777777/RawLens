# SPDX-FileCopyrightText: 2026 RawLens contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Unified srvulkan native build: the SAME CMakeLists builds the desktop
# library and the Android NDK library.
#
#   Desktop macOS:  cmake -S tools/sr-vulkan/src/main/cpp -B build/srvk-native \
#                     -G Ninja -DCMAKE_BUILD_TYPE=Release && cmake --build build/srvk-native
#                   (needs vulkan-headers + molten-vk; override with
#                   -DSRVK_VULKAN_INCLUDE=... -DSRVK_MOLTENVK_LIB=...)
#   Desktop Linux:  same, with the Vulkan SDK installed (find_package Vulkan).
#   Android:        cmake ... -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
#                     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29
cmake_minimum_required(VERSION 3.22)
project(srvulkan C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(srvulkan SHARED
    srvk_compute.cpp
    srvk_resources.cpp
    srvk_jni.cpp
)
target_include_directories(srvulkan PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

if(MSVC)
    target_compile_options(srvulkan PRIVATE /W4)
else()
    target_compile_options(srvulkan PRIVATE -Wall -Wextra -Werror=return-type)
endif()

if(ANDROID)
    # NDK sysroot already carries the Vulkan headers; link the platform libs.
    find_library(VULKAN_LIB vulkan REQUIRED)
    find_library(LOG_LIB log REQUIRED)
    find_package(JNI REQUIRED)
    target_include_directories(srvulkan PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(srvulkan PRIVATE ${VULKAN_LIB} ${LOG_LIB})
elseif(APPLE)
    # Direct MoltenVK link (no loader needed): the dylib exports the full
    # Vulkan entry points. Homebrew layout by default, $VULKAN_SDK honored.
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
    if(NOT EXISTS "${SRVK_VULKAN_INCLUDE}/vulkan/vulkan.h")
        message(FATAL_ERROR "Vulkan headers not found at ${SRVK_VULKAN_INCLUDE} "
            "(brew install vulkan-headers, or set SRVK_VULKAN_INCLUDE)")
    endif()
    if(NOT EXISTS "${SRVK_MOLTENVK_LIB}")
        message(FATAL_ERROR "MoltenVK not found at ${SRVK_MOLTENVK_LIB} "
            "(brew install molten-vk, or set SRVK_MOLTENVK_LIB)")
    endif()
    find_package(JNI REQUIRED)
    target_include_directories(srvulkan PRIVATE
        ${SRVK_VULKAN_INCLUDE} ${JNI_INCLUDE_DIRS})
    target_link_libraries(srvulkan PRIVATE ${SRVK_MOLTENVK_LIB})
else()
    find_package(Vulkan REQUIRED)
    find_package(JNI REQUIRED)
    target_include_directories(srvulkan PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(srvulkan PRIVATE Vulkan::Vulkan)
endif()
