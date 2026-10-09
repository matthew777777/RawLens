# Desktop HDR+ burst runner: merges real DNG bursts through the SAME host +
# recorder sources the app ships (app/src/main/cpp/hdrplus/*, minus JNI)
# against desktop Vulkan, with DNG I/O via the vendored tinydng the app
# uses natively.
#
#   macOS:  cmake -S tools/hdrplus-burst -B build/hdrplus-burst \
#               -DCMAKE_BUILD_TYPE=Release && cmake --build build/hdrplus-burst
#           (needs vulkan-headers + molten-vk; override with
#           -DHDRPLUS_VULKAN_INCLUDE=... -DHDRPLUS_MOLTENVK_LIB=...)
#   Linux:  same, with the Vulkan SDK installed (find_package Vulkan).
#
#   ./build/hdrplus-burst/hdrplus_burst app/src/main/assets out.dng frame*.dng
cmake_minimum_required(VERSION 3.22.1)
project(hdrplus_burst C CXX)

set(HDRPLUS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp/hdrplus")
set(TINYDNG_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp/deps/tinydng")

add_executable(hdrplus_burst
    "${CMAKE_CURRENT_SOURCE_DIR}/burst.cpp"
    "${HDRPLUS_DIR}/hdrplus_host.cpp"
    "${HDRPLUS_DIR}/hdrplus_run.cpp"
    "${HDRPLUS_DIR}/../vulkan_pipeline_cache.cpp"
    "${TINYDNG_DIR}/tinydng_api.c"
    "${TINYDNG_DIR}/tinydng_io.c"
    "${TINYDNG_DIR}/tinydng_tiff.c"
    "${TINYDNG_DIR}/tinydng_dng.c"
    "${TINYDNG_DIR}/tinydng_codec.c"
    "${TINYDNG_DIR}/tinydng_write.c"
    "${TINYDNG_DIR}/tinydng_psd.c"
    "${TINYDNG_DIR}/tinydng_psd_write.c"
    "${TINYDNG_DIR}/tiny_dng_ljpeg92_v2.c")
target_include_directories(hdrplus_burst PRIVATE "${HDRPLUS_DIR}" "${HDRPLUS_DIR}/.." "${TINYDNG_DIR}")
set_target_properties(hdrplus_burst PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
# Same tinydng recipe as the app (app/src/main/cpp/CMakeLists.txt).
target_compile_definitions(hdrplus_burst PRIVATE TINYDNG_NO_ZIP TINYDNG_NO_BASELINE_JPEG
    TINYDNG_NO_PSD TINYDNG_DISABLE_THREADS)
if(MSVC)
    target_compile_options(hdrplus_burst PRIVATE /W4)
else()
    target_compile_options(hdrplus_burst PRIVATE -Wall -Wextra -Werror=return-type)
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
    target_include_directories(hdrplus_burst PRIVATE ${HDRPLUS_VULKAN_INCLUDE})
    target_link_libraries(hdrplus_burst PRIVATE ${HDRPLUS_MOLTENVK_LIB})
else()
    find_package(Vulkan REQUIRED)
    target_link_libraries(hdrplus_burst PRIVATE Vulkan::Vulkan)
endif()
