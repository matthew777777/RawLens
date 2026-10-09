# Desktop parity runner for the HDR+ merge port: builds the SAME host +
# recorder sources the app ships (app/src/main/cpp/hdrplus/*, minus JNI)
# against desktop Vulkan and runs both merge paths on synthetic bursts.
#
#   macOS:  cmake -S tools/hdrplus-parity -B build/hdrplus-parity \
#               -DCMAKE_BUILD_TYPE=Release && cmake --build build/hdrplus-parity
#           (needs vulkan-headers + molten-vk; override with
#           -DHDRPLUS_VULKAN_INCLUDE=... -DHDRPLUS_MOLTENVK_LIB=...)
#   Linux:  same, with the Vulkan SDK installed (find_package Vulkan).
#
#   ./build/hdrplus-parity/hdrplus_parity app/src/main/assets
cmake_minimum_required(VERSION 3.22.1)
project(hdrplus_parity C CXX)

set(HDRPLUS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp/hdrplus")

add_executable(hdrplus_parity
    "${CMAKE_CURRENT_SOURCE_DIR}/parity.cpp"
    "${HDRPLUS_DIR}/hdrplus_host.cpp"
    "${HDRPLUS_DIR}/hdrplus_run.cpp"
    "${HDRPLUS_DIR}/../vulkan_pipeline_cache.cpp")
target_include_directories(hdrplus_parity PRIVATE "${HDRPLUS_DIR}" "${HDRPLUS_DIR}/..")
set_target_properties(hdrplus_parity PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
if(MSVC)
    target_compile_options(hdrplus_parity PRIVATE /W4)
else()
    target_compile_options(hdrplus_parity PRIVATE -Wall -Wextra -Werror=return-type)
endif()

if(APPLE)
    # Direct MoltenVK link (no loader needed): the dylib exports the full
    # Vulkan entry points. Homebrew layout by default, $VULKAN_SDK honored.
    if(NOT HDRPLUS_VULKAN_INCLUDE)
        if(DEFINED ENV{VULKAN_SDK})
            set(HDRPLUS_VULKAN_INCLUDE "$ENV{VULKAN_SDK}/include")
        else()
            set(HDRPLUS_VULKAN_INCLUDE "/opt/homebrew/include")
        endif()
    endif()
    if(NOT HDRPLUS_MOLTENVK_LIB)
        if(DEFINED ENV{VULKAN_SDK})
            set(HDRPLUS_MOLTENVK_LIB "$ENV{VULKAN_SDK}/lib/libMoltenVK.dylib")
        else()
            set(HDRPLUS_MOLTENVK_LIB "/opt/homebrew/lib/libMoltenVK.dylib")
        endif()
    endif()
    if(NOT EXISTS "${HDRPLUS_VULKAN_INCLUDE}/vulkan/vulkan.h")
        message(FATAL_ERROR "Vulkan headers not found at ${HDRPLUS_VULKAN_INCLUDE} "
            "(brew install vulkan-headers, or set HDRPLUS_VULKAN_INCLUDE)")
    endif()
    if(NOT EXISTS "${HDRPLUS_MOLTENVK_LIB}")
        message(FATAL_ERROR "MoltenVK not found at ${HDRPLUS_MOLTENVK_LIB} "
            "(brew install molten-vk, or set HDRPLUS_MOLTENVK_LIB)")
    endif()
    target_include_directories(hdrplus_parity PRIVATE ${HDRPLUS_VULKAN_INCLUDE})
    target_link_libraries(hdrplus_parity PRIVATE ${HDRPLUS_MOLTENVK_LIB})
else()
    find_package(Vulkan REQUIRED)
    target_link_libraries(hdrplus_parity PRIVATE Vulkan::Vulkan)
endif()
