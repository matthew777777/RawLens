cmake_minimum_required(VERSION 3.22.1)
project(lkrpipeline C CXX)

# LK-Reloaded burst super-resolution (third burst method next to MFSR and
# HDR+). Shares sr-pipeline's Vulkan context and DNG I/O by LINKING
# libsrpipeline (no copied sources). Same CMake for the Android app
# (add_subdirectory from app/src/main/cpp) and desktop (MoltenVK).

set(LKR_DIR "${CMAKE_CURRENT_SOURCE_DIR}")
set(SRP_DIR "${LKR_DIR}/../../../../sr-pipeline/src/main/cpp")
if(NOT TARGET srpipeline)
  add_subdirectory("${SRP_DIR}" srpipeline)
endif()

# ---- shaders -> embedded SPIR-V (glslc, the NDK's on every host) ----------
if(ANDROID)
  string(TOLOWER "${CMAKE_HOST_SYSTEM_NAME}" _host)
  find_program(LKR_GLSLC glslc HINTS "${ANDROID_NDK}/shader-tools/${_host}-x86_64" REQUIRED)
else()
  file(GLOB _ndk_glslc "$ENV{HOME}/Library/Android/sdk/ndk/*/shader-tools/darwin-x86_64"
                       "$ENV{ANDROID_NDK_HOME}/shader-tools/*")
  find_program(LKR_GLSLC glslc HINTS ${_ndk_glslc} $ENV{VULKAN_SDK}/bin REQUIRED)
endif()
set(LKR_GEN "${CMAKE_CURRENT_BINARY_DIR}/lkr_gen")
file(MAKE_DIRECTORY "${LKR_GEN}")
set(LKR_SHADERS resid_adj zstep resid zstep_gather weight weight_e weight_th weight_min weight_minh resid_adj_w zstep_gw resid_q resid_adj_w_q scatter_q zpack resid_zq resid_adj_w_zq weight_t scatter grad jac solve jacblk solveblk)
set(LKR_INCS "")
foreach(sh ${LKR_SHADERS})
  add_custom_command(
      OUTPUT "${LKR_GEN}/${sh}.inc"
      COMMAND "${LKR_GLSLC}" --target-env=vulkan1.1 -fshader-stage=compute -O -mfmt=num
              -I "${LKR_DIR}/shaders" -o "${LKR_GEN}/${sh}.inc" "${LKR_DIR}/shaders/${sh}.comp"
      DEPENDS "${LKR_DIR}/shaders/${sh}.comp" "${LKR_DIR}/shaders/lkr_common.glsl"
      VERBATIM)
  list(APPEND LKR_INCS "${LKR_GEN}/${sh}.inc")
endforeach()
# sr-pipeline's MFSR block search, compiled from ITS source (shared shader):
# LK-SR uses it to initialise the local motion field.
add_custom_command(
    OUTPUT "${LKR_GEN}/srp_align.inc"
    COMMAND "${LKR_GLSLC}" --target-env=vulkan1.1 -fshader-stage=compute -O -mfmt=num
            -o "${LKR_GEN}/srp_align.inc" "${SRP_DIR}/shaders/align.comp"
    DEPENDS "${SRP_DIR}/shaders/align.comp" VERBATIM)
list(APPEND LKR_INCS "${LKR_GEN}/srp_align.inc")
add_custom_target(lkr_shaders DEPENDS ${LKR_INCS})

find_library(LKR_VULKAN_LIB vulkan REQUIRED)
if(NOT ANDROID)
  find_path(LKR_VULKAN_INCLUDE vulkan/vulkan.h HINTS /opt/homebrew/include /usr/local/include REQUIRED)
endif()

# ---- core (static): Vulkan layer + solver ---------------------------------
add_library(lkr_core STATIC lkr_gpu.cpp lkr_solver.cpp lkr_host.cpp lkr_full.cpp lkr_blocksearch.cpp lkr_mhc.cpp)
add_dependencies(lkr_core lkr_shaders)
target_include_directories(lkr_core PUBLIC "${LKR_DIR}" "${SRP_DIR}" "${LKR_GEN}" ${LKR_VULKAN_INCLUDE})
target_compile_features(lkr_core PUBLIC cxx_std_17)
target_compile_options(lkr_core PRIVATE -Wall -Wextra -Werror=return-type -O3 -ffp-contract=off -fno-fast-math)
set_target_properties(lkr_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_link_libraries(lkr_core PUBLIC srpipeline ${LKR_VULKAN_LIB})

if(NOT ANDROID)
  # Desktop parity / benchmark executable (python/lkr is the reference).
  add_executable(lkr_parity "${LKR_DIR}/../../../host/lkr_parity.cpp")
  target_include_directories(lkr_parity PRIVATE "${LKR_DIR}/../../../../sr-pipeline/host")
  target_link_libraries(lkr_parity PRIVATE lkr_core)
endif()
if(NOT ANDROID)
  add_executable(lkr_mhc_check "${LKR_DIR}/../../../host/lkr_mhc_check.cpp")
  target_link_libraries(lkr_mhc_check PRIVATE lkr_core)
  add_executable(lkr_run "${LKR_DIR}/../../../host/lkr_run.cpp")
  target_link_libraries(lkr_run PRIVATE lkr_core)
endif()

# ---- FFDNet prior (ncnn) ----------------------------------------------------
# Android: the app's vendored ncnn (app/src/main/cpp/ncnn/<abi>), wired by the
# app CMake. Desktop: ncnn macOS-vulkan release frameworks in
# tools/lkr-pipeline/build/deps/ncnn (see README; not committed).
if(NOT ANDROID)
  set(LKR_NCNN_DIR "${LKR_DIR}/../../../build/deps/ncnn" CACHE PATH "ncnn macOS frameworks")
  if(EXISTS "${LKR_NCNN_DIR}/ncnn.framework")
    add_library(lkr_prior STATIC lkr_prior.cpp)
    target_include_directories(lkr_prior PUBLIC "${LKR_DIR}" "${LKR_NCNN_DIR}/ncnn.framework/Headers/ncnn")
    target_compile_features(lkr_prior PUBLIC cxx_std_17)
    target_compile_options(lkr_prior PRIVATE -O3 -Wall)
    target_link_options(lkr_prior PUBLIC "-F${LKR_NCNN_DIR}")
    target_link_libraries(lkr_prior PUBLIC "-framework ncnn" "-framework glslang" "-framework openmp" ${LKR_VULKAN_LIB})
    target_compile_definitions(lkr_core PUBLIC LKR_WITH_NCNN=1)
    target_link_libraries(lkr_run PRIVATE lkr_prior)
    target_link_libraries(lkr_parity PRIVATE lkr_prior)
    add_executable(lkr_full_run "${LKR_DIR}/../../../host/lkr_full_run.cpp")
    target_link_libraries(lkr_full_run PRIVATE lkr_core lkr_prior)
    add_executable(lkr_prior_check "${LKR_DIR}/../../../host/lkr_prior_check.cpp")
    target_include_directories(lkr_prior_check PRIVATE "${LKR_DIR}/../../../../sr-pipeline/host")
    target_link_libraries(lkr_prior_check PRIVATE lkr_prior)
  else()
    message(STATUS "lkr: ncnn frameworks not found, prior disabled (${LKR_NCNN_DIR})")
  endif()
endif()

# ---- Android: liblkrpipeline.so (JNI + FFDNet prior on the app's ncnn) ------
if(ANDROID)
  set(LKR_NCNN_DIR "${LKR_DIR}/../../../../../app/src/main/cpp/ncnn/${ANDROID_ABI}")
  add_library(lkrpipeline SHARED lkr_jni.cpp lkr_prior.cpp lkr_litert.cpp)
  target_include_directories(lkrpipeline PRIVATE "${LKR_NCNN_DIR}/include/ncnn")
  target_compile_options(lkrpipeline PRIVATE -O3 -Wall -fno-rtti)
  set(LKR_NCNN_LIBS "${LKR_NCNN_DIR}/lib/libncnn.a")
  foreach(gl glslang MachineIndependent GenericCodeGen glslang-default-resource-limits OSDependent SPIRV)
    if(EXISTS "${LKR_NCNN_DIR}/lib/lib${gl}.a")
      list(APPEND LKR_NCNN_LIBS "${LKR_NCNN_DIR}/lib/lib${gl}.a")
    endif()
  endforeach()
  find_library(LKR_LOG_LIB log REQUIRED)
  target_link_libraries(lkrpipeline PRIVATE lkr_core ${LKR_NCNN_LIBS} android ${LKR_LOG_LIB} dl)
  # ncnn is also statically inside libncnnMl.so: keep ours private.
  target_link_options(lkrpipeline PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL"
                      -fopenmp -static-openmp)
  set_target_properties(lkrpipeline PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
endif()
