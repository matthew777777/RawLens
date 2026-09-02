cmake_minimum_required(VERSION 3.22.1)
project(dngCreator)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Compile PhotonCamera's checked-in implementation directly. RawLens does not carry a modified
# copy of dngCreator.cpp or tiny_dng_writer.h.
set(PHOTON_DNG_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../references/PhotonCamera/app/src/main/cpp")
get_filename_component(PHOTON_DNG_DIR "${PHOTON_DNG_DIR}" ABSOLUTE)
set(PHOTON_DEPS_DIR "${CMAKE_CURRENT_BINARY_DIR}/deps")
file(MAKE_DIRECTORY "${PHOTON_DEPS_DIR}")

# These are the same unpinned upstream dependencies requested by PhotonCamera's CMake project.
file(DOWNLOAD
    "https://raw.githubusercontent.com/ParticlesDevs/tinydng/refs/heads/master/tiny_dng_writer.h"
    "${PHOTON_DEPS_DIR}/tiny_dng_writer.h"
    TLS_VERIFY ON
    STATUS TINY_DNG_DOWNLOAD_STATUS)
list(GET TINY_DNG_DOWNLOAD_STATUS 0 TINY_DNG_DOWNLOAD_CODE)
if(NOT TINY_DNG_DOWNLOAD_CODE EQUAL 0)
    message(FATAL_ERROR "Could not fetch PhotonCamera TinyDNG: ${TINY_DNG_DOWNLOAD_STATUS}")
endif()

foreach(ARCHIVE_HEADER archive.h archive_entry.h)
    file(DOWNLOAD
        "https://raw.githubusercontent.com/libarchive/libarchive/master/libarchive/${ARCHIVE_HEADER}"
        "${PHOTON_DEPS_DIR}/${ARCHIVE_HEADER}"
        TLS_VERIFY ON
        STATUS ARCHIVE_DOWNLOAD_STATUS)
    list(GET ARCHIVE_DOWNLOAD_STATUS 0 ARCHIVE_DOWNLOAD_CODE)
    if(NOT ARCHIVE_DOWNLOAD_CODE EQUAL 0)
        message(FATAL_ERROR "Could not fetch ${ARCHIVE_HEADER}: ${ARCHIVE_DOWNLOAD_STATUS}")
    endif()
endforeach()

add_library(dngCreator SHARED
    "${PHOTON_DNG_DIR}/dngCreator.cpp")
target_include_directories(dngCreator PRIVATE
    "${CMAKE_CURRENT_BINARY_DIR}"
    "${PHOTON_DEPS_DIR}"
    "${PHOTON_DNG_DIR}")
target_link_libraries(dngCreator log dl)
target_link_options(dngCreator PRIVATE "-Wl,-z,max-page-size=16384")
