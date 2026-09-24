cmake_minimum_required(VERSION 3.22.1)
project(dngCreator)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Reference kernel: checksum-verified upstream body, scalar arithmetic on all ABIs.
find_package(Python3 REQUIRED COMPONENTS Interpreter)
set(AMAZE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../..")
set(AMAZE_GENERATED "${CMAKE_CURRENT_BINARY_DIR}/amaze_reference.cpp")
add_custom_command(OUTPUT "${AMAZE_GENERATED}"
    COMMAND "${Python3_EXECUTABLE}" "${AMAZE_ROOT}/tools/build_amaze_reference.py"
        "${AMAZE_ROOT}/references/RawTherapee/amaze_demosaic_RT.cc" "${AMAZE_GENERATED}"
    DEPENDS "${AMAZE_ROOT}/tools/build_amaze_reference.py"
        "${AMAZE_ROOT}/references/RawTherapee/amaze_demosaic_RT.cc")
add_library(rawTherapeeAmaze SHARED "${AMAZE_GENERATED}"
    amaze_reference_api.cpp amaze_reference_jni.cpp)
target_include_directories(rawTherapeeAmaze PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
target_compile_options(rawTherapeeAmaze PRIVATE -O2 -ffp-contract=off -fno-fast-math)
target_link_options(rawTherapeeAmaze PRIVATE "-Wl,-z,max-page-size=16384")

# libjpeg-turbo 3.x deliberately refuses add_subdirectory()/FetchContent_MakeAvailable().
# Keep this compatible with Android SDK CMake 3.22.1: DOWNLOAD_EXTRACT_TIMESTAMP was
# introduced later and CMake 3.22 mis-parses it as part of URL_HASH.
# Populate its source tree during *configure* so jpeglib.h already exists when Android/NDK
# generates dngCreator's compile rules, then build the library as an independent project.
# This keeps libjpeg-turbo isolated while avoiding the race where ExternalProject downloads
# jpeglib.h only after native_jpeg_encoder.cpp has already been scheduled for compilation.
include(FetchContent)
include(ExternalProject)
set(LIBJPEG_TURBO_VERSION "3.2.0")

FetchContent_Declare(libjpeg_turbo_source
    URL "https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/${LIBJPEG_TURBO_VERSION}/libjpeg-turbo-${LIBJPEG_TURBO_VERSION}.tar.gz"
    URL_HASH "SHA256=6f30092cef9fb839779646608f4ee14ae3cbac989c47fa05e841b0841f09878e"
)
FetchContent_GetProperties(libjpeg_turbo_source)
if(NOT libjpeg_turbo_source_POPULATED)
    FetchContent_Populate(libjpeg_turbo_source)
endif()

set(LIBJPEG_TURBO_SOURCE_DIR "${libjpeg_turbo_source_SOURCE_DIR}")
set(LIBJPEG_TURBO_INCLUDE_DIR "${LIBJPEG_TURBO_SOURCE_DIR}/src")
if(NOT EXISTS "${LIBJPEG_TURBO_INCLUDE_DIR}/jpeglib.h")
    message(FATAL_ERROR "libjpeg-turbo source was populated, but ${LIBJPEG_TURBO_INCLUDE_DIR}/jpeglib.h is missing")
endif()
set(LIBJPEG_TURBO_ROOT "${CMAKE_CURRENT_BINARY_DIR}/libjpeg-turbo")
set(LIBJPEG_TURBO_BINARY_DIR "${LIBJPEG_TURBO_ROOT}/build")
set(LIBJPEG_TURBO_STATIC_LIB "${LIBJPEG_TURBO_BINARY_DIR}/libjpeg.a")
file(MAKE_DIRECTORY "${LIBJPEG_TURBO_BINARY_DIR}")

ExternalProject_Add(libjpeg_turbo_external
    SOURCE_DIR "${LIBJPEG_TURBO_SOURCE_DIR}"
    BINARY_DIR "${LIBJPEG_TURBO_BINARY_DIR}"
    DOWNLOAD_COMMAND ""
    UPDATE_COMMAND ""
    PATCH_COMMAND ""
    CMAKE_GENERATOR "${CMAKE_GENERATOR}"
    CMAKE_ARGS
        "-DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}"
        "-DANDROID_ABI=${ANDROID_ABI}"
        "-DANDROID_PLATFORM=${ANDROID_PLATFORM}"
        "-DANDROID_NDK=${ANDROID_NDK}"
        "-DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}"
        "-DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}"
        "-DCMAKE_POSITION_INDEPENDENT_CODE=ON"
        "-DENABLE_SHARED=OFF"
        "-DENABLE_STATIC=ON"
        "-DWITH_TURBOJPEG=OFF"
        "-DWITH_TOOLS=OFF"
        "-DWITH_TESTS=OFF"
        "-DWITH_SIMD=ON"
    BUILD_COMMAND "${CMAKE_COMMAND}" --build <BINARY_DIR> --target jpeg-static
    INSTALL_COMMAND ""
    BUILD_BYPRODUCTS "${LIBJPEG_TURBO_STATIC_LIB}"
)

add_library(jpeg_turbo_static STATIC IMPORTED GLOBAL)
set_target_properties(jpeg_turbo_static PROPERTIES
    IMPORTED_LOCATION "${LIBJPEG_TURBO_STATIC_LIB}"
    INTERFACE_INCLUDE_DIRECTORIES "${LIBJPEG_TURBO_INCLUDE_DIR};${LIBJPEG_TURBO_BINARY_DIR}"
)
add_dependencies(jpeg_turbo_static libjpeg_turbo_external)

# JPEG JNI keeps its existing library name; RAW DNG uses rawLensDng below.
add_library(dngCreator SHARED "${CMAKE_CURRENT_SOURCE_DIR}/native_jpeg_encoder.cpp")
add_dependencies(dngCreator libjpeg_turbo_external)
target_include_directories(dngCreator PRIVATE
    "${CMAKE_CURRENT_BINARY_DIR}"
    "${LIBJPEG_TURBO_INCLUDE_DIR}"
    "${LIBJPEG_TURBO_BINARY_DIR}")
target_link_libraries(dngCreator log dl m jnigraphics jpeg_turbo_static)
target_link_options(dngCreator PRIVATE "-Wl,-z,max-page-size=16384")

# Vendored PhotonCamera FlowNet-v2/ncnn port: model runtime, custom correlation layers,
# and ABI-specific ncnn static libraries. See app/src/main/cpp/flownet/UPSTREAM.md.
set(PHOTON_FLOW_DIR "${CMAKE_CURRENT_SOURCE_DIR}/flownet")
set(PHOTON_NCNN_DIR "${CMAKE_CURRENT_SOURCE_DIR}/ncnn/${ANDROID_ABI}")
set(PHOTON_NCNN_LIB "${PHOTON_NCNN_DIR}/lib")
if(EXISTS "${PHOTON_NCNN_LIB}/libncnn.a")
    set(FLOW_GEN_DIR "${CMAKE_CURRENT_BINARY_DIR}/flownet-generated")
    file(MAKE_DIRECTORY "${FLOW_GEN_DIR}")
    find_program(PYTHON3_EXECUTABLE python3 REQUIRED)
    set(FLOW_VULKAN_SOURCES "")
    set(FLOW_VULKAN_LIBS "")
    if(EXISTS "${PHOTON_NCNN_LIB}/libSPIRV.a")
        foreach(shader flownetcorrfused flownetcorrlookup flownetupsample)
            execute_process(
                COMMAND "${PYTHON3_EXECUTABLE}" "${PHOTON_FLOW_DIR}/gen_shader_header.py"
                    "${PHOTON_FLOW_DIR}/layer/vulkan/shader/${shader}.comp" "${shader}_comp_data"
                OUTPUT_FILE "${FLOW_GEN_DIR}/${shader}_comp.h"
                COMMAND_ERROR_IS_FATAL ANY)
        endforeach()
        set(FLOW_VULKAN_SOURCES
            "${PHOTON_FLOW_DIR}/layer/vulkan/flownetupsample_vulkan.cpp"
            "${PHOTON_FLOW_DIR}/layer/vulkan/flownetcorrlookup_vulkan.cpp"
            "${PHOTON_FLOW_DIR}/layer/vulkan/flownetcorrfused_vulkan.cpp")
        set(FLOW_VULKAN_LIBS
            "${PHOTON_NCNN_LIB}/libglslang.a"
            "${PHOTON_NCNN_LIB}/libMachineIndependent.a"
            "${PHOTON_NCNN_LIB}/libGenericCodeGen.a"
            "${PHOTON_NCNN_LIB}/libglslang-default-resource-limits.a"
            "${PHOTON_NCNN_LIB}/libOSDependent.a"
            "${PHOTON_NCNN_LIB}/libSPIRV.a")
    endif()
    add_library(ncnnMl SHARED
        "${CMAKE_CURRENT_SOURCE_DIR}/ncnnMl.cpp"
        "${PHOTON_FLOW_DIR}/layer/flownetupsample.cpp"
        "${PHOTON_FLOW_DIR}/layer/flownetcorrlookup.cpp"
        "${PHOTON_FLOW_DIR}/layer/flownetcorrfused.cpp"
        ${FLOW_VULKAN_SOURCES})
    target_include_directories(ncnnMl PRIVATE
        "${PHOTON_NCNN_DIR}/include/ncnn" "${FLOW_GEN_DIR}"
        "${PHOTON_FLOW_DIR}" "${PHOTON_FLOW_DIR}/layer" "${PHOTON_FLOW_DIR}/layer/vulkan")
    target_compile_options(ncnnMl PRIVATE -fno-rtti -fno-exceptions)
    target_link_libraries(ncnnMl PRIVATE "${PHOTON_NCNN_LIB}/libncnn.a" ${FLOW_VULKAN_LIBS} android log)
    set_target_properties(ncnnMl PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
    target_link_options(ncnnMl PRIVATE "-Wl,-z,max-page-size=16384")
else()
    # Emulator ABIs do not have PhotonCamera's prebuilt ncnn archive. Keep the app loadable;
    # HdrFlowNetAligner will receive null and use identity alignment for those builds.
    add_library(ncnnMl SHARED "${CMAKE_CURRENT_SOURCE_DIR}/flownet_stub.cpp")
    target_link_libraries(ncnnMl PRIVATE log)
endif()

# Pinned TinyDNG v3 writer. The app supplies Camera2 metadata as checked TIFF fields.
set(TINYDNG_DIR "${CMAKE_CURRENT_SOURCE_DIR}/deps/tinydng")
add_library(tinydng_v3 STATIC
    ${TINYDNG_DIR}/tinydng_api.c ${TINYDNG_DIR}/tinydng_io.c
    ${TINYDNG_DIR}/tinydng_tiff.c ${TINYDNG_DIR}/tinydng_dng.c
    ${TINYDNG_DIR}/tinydng_codec.c ${TINYDNG_DIR}/tinydng_write.c
    ${TINYDNG_DIR}/tinydng_psd.c ${TINYDNG_DIR}/tinydng_psd_write.c
    ${TINYDNG_DIR}/tiny_dng_ljpeg92_v2.c)
target_include_directories(tinydng_v3 PUBLIC ${TINYDNG_DIR})
target_compile_features(tinydng_v3 PUBLIC c_std_11)
target_compile_definitions(tinydng_v3 PRIVATE TINYDNG_NO_ZIP
    TINYDNG_NO_BASELINE_JPEG TINYDNG_NO_PSD TINYDNG_DISABLE_THREADS)
add_library(rawLensDng SHARED tinydng_jni.cpp)
target_link_libraries(rawLensDng PRIVATE tinydng_v3)
target_link_options(rawLensDng PRIVATE "-Wl,-z,max-page-size=16384")

# Zero-copy viewfinder: EGL import bridge, the Vulkan superpixel compute
# module (import HAL RAW -> RGBA8 superpixel export buffer), and the NEON CPU
# fallback sampler (fast native replacement for the old Kotlin copy).
add_library(rawLensVfEgl SHARED vf_egl_jni.cpp vf_vulkan_vf.cpp vf_cpu_neon.cpp)
add_library(rawLensDualRaw SHARED dual_raw_vulkan.cpp)
target_link_libraries(rawLensDualRaw PRIVATE android log vulkan)
target_link_options(rawLensDualRaw PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(rawLensVfEgl PRIVATE EGL GLESv2 android log vulkan)
target_link_options(rawLensVfEgl PRIVATE "-Wl,-z,max-page-size=16384")
# armeabi-v7a needs an explicit NEON FPU for the arm_neon.h path; arm64 has
# NEON unconditionally and x86 emulators take the scalar tier in the same file.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(rawLensVfEgl PRIVATE -mfpu=neon)
endif()
