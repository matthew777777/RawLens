cmake_minimum_required(VERSION 3.22.1)
project(dngCreator)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

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

# Compile PhotonCamera's checked-in implementation directly. RawLens does not carry a modified
# copy of dngCreator.cpp or tiny_dng_writer.h.
set(PHOTON_DNG_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../references/PhotonCamera/app/src/main/cpp")
get_filename_component(PHOTON_DNG_DIR "${PHOTON_DNG_DIR}" ABSOLUTE)
set(PHOTON_DEPS_DIR "${CMAKE_CURRENT_BINARY_DIR}/deps")
file(MAKE_DIRECTORY "${PHOTON_DEPS_DIR}")

# Vendor the TinyDNG header used by RawLens so GainMap interoperability fixes are
# deterministic and cannot disappear when upstream master changes. dngCreator.cpp
# includes it as "deps/tiny_dng_writer.h" from the CMake binary include tree.
foreach(DEP_HEADER tiny_dng_writer.h archive.h archive_entry.h)
    if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/deps/${DEP_HEADER}")
        message(FATAL_ERROR "Required header ${DEP_HEADER} is missing from ${CMAKE_CURRENT_SOURCE_DIR}/deps/")
    endif()
    configure_file(
        "${CMAKE_CURRENT_SOURCE_DIR}/deps/${DEP_HEADER}"
        "${PHOTON_DEPS_DIR}/${DEP_HEADER}"
        COPYONLY)
endforeach()

add_library(dngCreator SHARED
    "${PHOTON_DNG_DIR}/dngCreator.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/native_jpeg_encoder.cpp")
add_dependencies(dngCreator libjpeg_turbo_external)
target_include_directories(dngCreator PRIVATE
    "${CMAKE_CURRENT_BINARY_DIR}"
    "${PHOTON_DEPS_DIR}"
    "${PHOTON_DNG_DIR}"
    "${LIBJPEG_TURBO_INCLUDE_DIR}"
    "${LIBJPEG_TURBO_BINARY_DIR}")
target_link_libraries(dngCreator log dl m jnigraphics jpeg_turbo_static)
target_link_options(dngCreator PRIVATE "-Wl,-z,max-page-size=16384")

# PhotonCamera FlowNet-v2/ncnn port. The pinned reference checkout supplies the model runtime,
# custom correlation layers, and ABI-specific ncnn static libraries without duplicating 179 MB.
set(PHOTON_FLOW_DIR "${PHOTON_DNG_DIR}/flownet")
set(PHOTON_NCNN_DIR "${PHOTON_DNG_DIR}/ncnn/${ANDROID_ABI}")
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
        "${PHOTON_DNG_DIR}/ncnnMl.cpp"
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
