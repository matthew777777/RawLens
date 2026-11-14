cmake_minimum_required(VERSION 3.22.1)
project(lkrlite C CXX)

# LK-R Lite: cheap LK-Reloaded for mobile GPUs. Standalone: no LK-R (lkr-pipeline)
# code is linked. sr-pipeline provides the Vulkan device/queue and DNG I/O
# (libsrpipeline), as for LK-R and MFSR.

set(LKRL_DIR "${CMAKE_CURRENT_SOURCE_DIR}")
set(SRP_DIR "${LKRL_DIR}/../../../../sr-pipeline/src/main/cpp")
if(NOT TARGET srpipeline)
  add_subdirectory("${SRP_DIR}" srpipeline)
endif()

if(ANDROID)
  string(TOLOWER "${CMAKE_HOST_SYSTEM_NAME}" _host)
  find_program(LKRL_GLSLC glslc HINTS "${ANDROID_NDK}/shader-tools/${_host}-x86_64" REQUIRED)
else()
  file(GLOB _ndk_glslc "$ENV{HOME}/Library/Android/sdk/ndk/*/shader-tools/darwin-x86_64"
                       "$ENV{ANDROID_NDK_HOME}/shader-tools/*")
  find_program(LKRL_GLSLC glslc HINTS ${_ndk_glslc} $ENV{VULKAN_SDK}/bin REQUIRED)
endif()
set(LKRL_GEN "${CMAKE_CURRENT_BINARY_DIR}/lkrl_gen")
file(MAKE_DIRECTORY "${LKRL_GEN}")
set(LKRL_SHADERS resid werr wavg wminh wminv adj zupd jac jacblk solveblk gstats wguide wvalid lkfit kn_conv kn_dn hyb adj_wg fin)
set(LKRL_INCS "")
foreach(sh ${LKRL_SHADERS})
  add_custom_command(
      OUTPUT "${LKRL_GEN}/${sh}.inc"
      COMMAND "${LKRL_GLSLC}" --target-env=vulkan1.1 -fshader-stage=compute -O -mfmt=num
              -I "${LKRL_DIR}/shaders" -o "${LKRL_GEN}/${sh}.inc" "${LKRL_DIR}/shaders/${sh}.comp"
      DEPENDS "${LKRL_DIR}/shaders/${sh}.comp" "${LKRL_DIR}/shaders/lite_common.glsl"
      VERBATIM)
  list(APPEND LKRL_INCS "${LKRL_GEN}/${sh}.inc")
endforeach()
# LK-R's weight shaders, verbatim copies in shaders/lkr (own include dir: lkr_common.glsl).
foreach(sh resid weight_tdet weight_th weight_t weight_tv weight_td weight_guide weight_minh weight_min dct_x_y dct_x_u dct_x_v dct_yuv)
  add_custom_command(
      OUTPUT "${LKRL_GEN}/lkr_${sh}.inc"
      COMMAND "${LKRL_GLSLC}" --target-env=vulkan1.1 -fshader-stage=compute -O -mfmt=num
              -I "${LKRL_DIR}/shaders/lkr" -o "${LKRL_GEN}/lkr_${sh}.inc" "${LKRL_DIR}/shaders/lkr/${sh}.comp"
      DEPENDS "${LKRL_DIR}/shaders/lkr/${sh}.comp" "${LKRL_DIR}/shaders/lkr/lkr_common.glsl" "${LKRL_DIR}/shaders/lkr/dct_x.glsl" VERBATIM)
  list(APPEND LKRL_INCS "${LKRL_GEN}/lkr_${sh}.inc")
endforeach()
add_custom_command(
    OUTPUT "${LKRL_GEN}/srp_align.inc"
    COMMAND "${LKRL_GLSLC}" --target-env=vulkan1.1 -fshader-stage=compute -O -mfmt=num
            -o "${LKRL_GEN}/srp_align.inc" "${SRP_DIR}/shaders/align.comp"
    DEPENDS "${SRP_DIR}/shaders/align.comp" VERBATIM)
list(APPEND LKRL_INCS "${LKRL_GEN}/srp_align.inc")
add_custom_target(lkrl_shaders DEPENDS ${LKRL_INCS})

find_library(LKRL_VULKAN_LIB vulkan REQUIRED)
if(NOT ANDROID)
  find_path(LKRL_VULKAN_INCLUDE vulkan/vulkan.h HINTS /opt/homebrew/include /usr/local/include REQUIRED)
endif()

add_library(lkrl_core STATIC lkrl_gpu.cpp lkrl_solver.cpp lkrl_host.cpp lkrl_mhc.cpp lkrl_image.cpp lkrl_finish.cpp lkrl_kernelnet.cpp lkrl_adapter.cpp lkrl_full.cpp lkrl_blocksearch.cpp lkrl_gpulk.cpp)
add_dependencies(lkrl_core lkrl_shaders)
target_include_directories(lkrl_core PUBLIC "${LKRL_DIR}" "${SRP_DIR}" "${LKRL_GEN}" ${LKRL_VULKAN_INCLUDE})
target_compile_features(lkrl_core PUBLIC cxx_std_17)
target_compile_definitions(lkrl_core PRIVATE LKRL_KN_DEFAULT_BIN="${LKRL_DIR}/../../../../sr-pipeline/src/main/resources/kernelnet/kernelnet_aniso_v2_2_params.ncnn.bin")
target_compile_options(lkrl_core PRIVATE -Wall -Wextra -Werror=return-type -O3 -ffp-contract=off -fno-fast-math)
set_target_properties(lkrl_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_link_libraries(lkrl_core PUBLIC srpipeline ${LKRL_VULKAN_LIB})

if(NOT ANDROID)
  add_executable(lkrl_run "${LKRL_DIR}/../../../host/lkrl_run.cpp")
  target_link_libraries(lkrl_run PRIVATE lkrl_core)
  add_executable(lkrl_full_run "${LKRL_DIR}/../../../host/lkrl_full_run.cpp")
  target_link_libraries(lkrl_full_run PRIVATE lkrl_core)
endif()
