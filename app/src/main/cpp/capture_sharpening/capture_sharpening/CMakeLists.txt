cmake_minimum_required(VERSION 3.22)
project(capture_sharpening LANGUAGES CXX)

# RawTherapee Capture Sharpening on the GPU (see include/capture_sharpening/CaptureSharpening.h).
if(ANDROID)
  find_library(VULKAN_LIB vulkan REQUIRED)
else()
  find_package(Vulkan REQUIRED)
  set(VULKAN_LIB Vulkan::Vulkan)
endif()
if(NOT GLSLC)
  find_program(GLSLC glslc REQUIRED)
endif()
if(NOT COMMAND embed_spirv)
  include(${CMAKE_CURRENT_LIST_DIR}/../vk_common/cmake/EmbedSpirv.cmake)
endif()
if(NOT TARGET rawr::vk_common)
  add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../vk_common ${CMAKE_CURRENT_BINARY_DIR}/vk_common)
endif()

set(CS_GEN ${CMAKE_CURRENT_BINARY_DIR}/generated)
file(MAKE_DIRECTORY ${CS_GEN})
set(CS_SHADERS ${CMAKE_CURRENT_LIST_DIR}/shaders)
set(SC ${CMAKE_CURRENT_LIST_DIR}/../shader_common/shaders)
set(CS_HEADERS)
foreach(NAME cs_radius cs_apron cs_luma_blocks cs_tile_stats cs_select cs_contrast cs_blend_raw cs_iir cs_transpose cs_yold cs_deconv)
  embed_spirv(${NAME} ${CS_SHADERS}/${NAME}.comp ${NAME}_spv OUTPUT_DIR ${CS_GEN} HEADERS_VAR CS_HEADERS
    FLAGS --target-env=vulkan1.1 -O -I${CS_SHADERS} -I${SC}
    DEPENDS ${CS_SHADERS}/cs_common.glsl ${SC}/cfa_common.glsl)
endforeach()

add_library(capture_sharpening STATIC src/CaptureSharpening.cpp ${CS_HEADERS})
add_library(rawr::capture_sharpening ALIAS capture_sharpening)
target_include_directories(capture_sharpening PUBLIC include PRIVATE ${CS_GEN})
target_link_libraries(capture_sharpening PUBLIC rawr::vk_common ${VULKAN_LIB})
target_compile_features(capture_sharpening PUBLIC cxx_std_17)
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
  target_compile_options(capture_sharpening PRIVATE -Wall -Wextra -Wpedantic -Werror)
endif()

# Host (MoltenVK) validator: runs the library on float32 planes and writes the
# result for tools/compare_gpu.py (NumPy RT port comparison).
option(CAPTURE_SHARPENING_BUILD_TESTS "Build the host Vulkan validation tool" OFF)
if(CAPTURE_SHARPENING_BUILD_TESTS)
  add_executable(capture_sharpening_validate tests/capture_sharpening_validate.cpp)
  target_include_directories(capture_sharpening_validate PRIVATE ${CMAKE_CURRENT_LIST_DIR}/../vk_common/testing)
  target_link_libraries(capture_sharpening_validate PRIVATE rawr::capture_sharpening)
endif()
