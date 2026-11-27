# Host-only proof harness for the sr-pipeline native backend.
#
# NOT part of the Android build: nothing in this directory is referenced by
# Gradle or by src/main/cpp/CMakeLists.txt. It builds the same srp_* and
# TinyDNG v3 sources the module ships and runs them on the desktop Vulkan
# loader (MoltenVK on macOS), proving:
#   srp_proof_dispatch  first real on-device dispatch (merge-accumulate over
#                       caller-owned buffers, fence-waited) vs a CPU oracle
#   srp_proof_dng       unified DNG writer roundtrip (write via srp_dng,
#                       read back via TinyDNG decode) with pixel comparison
cmake_minimum_required(VERSION 3.22)
project(srproof)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_C_STANDARD 11)

find_path(SRP_VK_INCLUDE vulkan/vulkan.h
    HINTS $ENV{VULKAN_SDK}/include /opt/homebrew/include /usr/local/include)
find_library(SRP_VK_LIB NAMES vulkan
    HINTS $ENV{VULKAN_SDK}/lib /opt/homebrew/lib /usr/local/lib)
if(NOT SRP_VK_INCLUDE OR NOT SRP_VK_LIB)
  message(FATAL_ERROR "srproof: Vulkan headers/library not found")
endif()
find_program(SRP_GLSLANG glslangValidator
    HINTS $ENV{VULKAN_SDK}/bin /opt/homebrew/bin /usr/local/bin)
if(NOT SRP_GLSLANG)
  message(FATAL_ERROR "srproof: glslangValidator not found")
endif()

set(SRP_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../src/main/cpp")
set(SRP_TINYDNG_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../app/src/main/cpp/deps/tinydng")
if(NOT EXISTS "${SRP_TINYDNG_DIR}/tinydng.h")
  message(FATAL_ERROR "srproof: vendored TinyDNG not found at ${SRP_TINYDNG_DIR}")
endif()

# Same vendored TinyDNG v3 sources the module builds (see
# src/main/cpp/CMakeLists.txt); one static lib shared by all host targets.
add_library(srp_tinydng_host STATIC
    ${SRP_TINYDNG_DIR}/tinydng_api.c ${SRP_TINYDNG_DIR}/tinydng_io.c
    ${SRP_TINYDNG_DIR}/tinydng_tiff.c ${SRP_TINYDNG_DIR}/tinydng_dng.c
    ${SRP_TINYDNG_DIR}/tinydng_codec.c ${SRP_TINYDNG_DIR}/tinydng_write.c
    ${SRP_TINYDNG_DIR}/tinydng_psd.c ${SRP_TINYDNG_DIR}/tinydng_psd_write.c
    ${SRP_TINYDNG_DIR}/tiny_dng_ljpeg92_v2.c)
target_include_directories(srp_tinydng_host PUBLIC ${SRP_TINYDNG_DIR})
target_compile_features(srp_tinydng_host PUBLIC c_std_11)
target_compile_definitions(srp_tinydng_host PRIVATE TINYDNG_NO_ZIP
    TINYDNG_NO_BASELINE_JPEG TINYDNG_NO_PSD TINYDNG_DISABLE_THREADS)

add_library(srp_core STATIC
    ${SRP_ROOT}/srp_context.cpp
    ${SRP_ROOT}/srp_pipeline.cpp
    ${SRP_ROOT}/srp_fft.cpp
    ${SRP_ROOT}/srp_dng.cpp)
target_include_directories(srp_core PUBLIC ${SRP_ROOT} ${SRP_TINYDNG_DIR} ${SRP_VK_INCLUDE})
target_compile_definitions(srp_core PRIVATE VK_ENABLE_BETA_EXTENSIONS)
target_compile_options(srp_core PRIVATE -Wall -Wextra)
target_link_libraries(srp_core PUBLIC srp_tinydng_host ${SRP_VK_LIB})

# Compile each checked-in kernel to SPIR-V at build time; the .comp stays
# the single source of truth (same file the NDK path loads at runtime).
function(srp_spv name)
  add_custom_command(
      OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/${name}.spv"
      COMMAND "${SRP_GLSLANG}" -V -o "${CMAKE_CURRENT_BINARY_DIR}/${name}.spv"
              "${SRP_ROOT}/shaders/${name}.comp"
      DEPENDS "${SRP_ROOT}/shaders/${name}.comp")
  add_custom_target("srp_${name}_spv" ALL
      DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/${name}.spv")
endfunction()
# fp16-storage twins (MobileMerge intermediates; shaders marked srp:half).
function(srp_spv_half name)
  add_custom_command(
      OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/${name}_h.spv"
      COMMAND "${SRP_GLSLANG}" -V -DSRP_HALF -o "${CMAKE_CURRENT_BINARY_DIR}/${name}_h.spv"
              "${SRP_ROOT}/shaders/${name}.comp"
      DEPENDS "${SRP_ROOT}/shaders/${name}.comp")
  add_custom_target("srp_${name}_h_spv" ALL
      DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/${name}_h.spv")
endfunction()
foreach(_h rob_means kern_cov ref_stats ref_hf rob_test rob_window kern_pre raw_image)
  srp_spv_half(${_h})
endforeach()
# Packed fp16 merge accumulators (shaders marked srp:halfacc).
foreach(_a merge_app merge_app_ref mfsr_divide)
  add_custom_command(
      OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/${_a}_a.spv"
      COMMAND "${SRP_GLSLANG}" -V -DSRP_HALF_ACC -o "${CMAKE_CURRENT_BINARY_DIR}/${_a}_a.spv"
              "${SRP_ROOT}/shaders/${_a}.comp"
      DEPENDS "${SRP_ROOT}/shaders/${_a}.comp")
  add_custom_target("srp_${_a}_a_spv" ALL DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/${_a}_a.spv")
endforeach()
srp_spv(merge_accumulate)
srp_spv(gat)
srp_spv(align)
srp_spv(corr)
srp_spv(fft)
srp_spv(ica)
srp_spv(demosaic)
srp_spv(grad)
srp_spv(kernels)
srp_spv(divide)
srp_spv(robustness)
srp_spv(merge_splat)
srp_spv(green_residual)
srp_spv(green_normal_solve)
srp_spv(green_normal_resident)
srp_spv(merge_polynomial)
srp_spv(grey_fft)
srp_spv(grey_fir)
srp_spv(align_wg)
srp_spv(corr_argmin_wg)
srp_spv(corr_l2_fused)
srp_spv(pyr_down)
srp_spv(corr_direct)
srp_spv(ica_wg)
srp_spv(noise_even)
srp_spv(noise_acc)
srp_spv(ca_warp)
srp_spv(flow_pick)
srp_spv(merge_twoband)
srp_spv(twoband_resolve)
srp_spv(twoband_dct)
srp_spv(twoband_tensor)
srp_spv(rob_feather)
srp_spv(raw_image)
srp_spv(colour_diff)
srp_spv(kern_pre)
srp_spv(kern_cov)
srp_spv(ref_stats)
srp_spv(ref_hf)
srp_spv(motion_field)
srp_spv(rob_means)
srp_spv(rob_test)
srp_spv(rob_window)
srp_spv(noise_acc_img)
srp_spv(grey_fir_img)
srp_spv(green_gather)
srp_spv(merge_app)
srp_spv(merge_app_ref)
srp_spv(mfsr_finish)
srp_spv(mfsr_divide)
srp_spv(img_dump)
srp_spv(pyr_img)
srp_spv(hess_img)
srp_spv(flow_up)
srp_spv(align_l2_img)
srp_spv(align_l2_tie)
srp_spv(align_l2_tie64)
srp_spv(align_l1_img)
srp_spv(align_l1_tie)
srp_spv(ica_img)

add_executable(srp_proof_dispatch proof_dispatch.cpp)
target_link_libraries(srp_proof_dispatch PRIVATE srp_core)
add_dependencies(srp_proof_dispatch srp_merge_accumulate_spv)

add_executable(srp_proof_gat proof_gat.cpp)
target_link_libraries(srp_proof_gat PRIVATE srp_core)
add_dependencies(srp_proof_gat srp_gat_spv)

add_executable(srp_proof_align proof_align.cpp)
target_link_libraries(srp_proof_align PRIVATE srp_core)
add_dependencies(srp_proof_align srp_align_spv)

add_executable(srp_proof_corr proof_corr.cpp)
target_link_libraries(srp_proof_corr PRIVATE srp_core)
add_dependencies(srp_proof_corr srp_corr_spv)

add_executable(srp_proof_fft proof_fft.cpp)
target_link_libraries(srp_proof_fft PRIVATE srp_core)
add_dependencies(srp_proof_fft srp_fft_spv srp_corr_spv)


add_executable(srp_proof_ica proof_ica.cpp)
target_link_libraries(srp_proof_ica PRIVATE srp_core)
add_dependencies(srp_proof_ica srp_ica_spv)

add_executable(srp_proof_dng proof_dng.cpp)
target_link_libraries(srp_proof_dng PRIVATE srp_core)

add_executable(srp_proof_demosaic proof_demosaic.cpp)
target_link_libraries(srp_proof_demosaic PRIVATE srp_core)
add_dependencies(srp_proof_demosaic srp_demosaic_spv)

add_executable(srp_proof_grad proof_grad.cpp)
target_link_libraries(srp_proof_grad PRIVATE srp_core)
add_dependencies(srp_proof_grad srp_grad_spv)

add_executable(srp_proof_kernels proof_kernels.cpp)
target_link_libraries(srp_proof_kernels PRIVATE srp_core)
add_dependencies(srp_proof_kernels srp_kernels_spv srp_gat_spv srp_grad_spv)

add_executable(srp_proof_divide proof_divide.cpp)
target_link_libraries(srp_proof_divide PRIVATE srp_core)
add_dependencies(srp_proof_divide srp_divide_spv)

add_executable(srp_proof_robust proof_robust.cpp)
target_link_libraries(srp_proof_robust PRIVATE srp_core)
add_dependencies(srp_proof_robust srp_robustness_spv)

add_executable(srp_proof_merge_splat proof_merge_splat.cpp)
target_link_libraries(srp_proof_merge_splat PRIVATE srp_core)
add_dependencies(srp_proof_merge_splat srp_merge_splat_spv)

add_executable(srp_proof_dngread proof_dngread.cpp)
target_link_libraries(srp_proof_dngread PRIVATE srp_core)

# Host JNI library: the same srp_jni.cpp the NDK builds, compiled for the
# desktop JVM so SrpBridgeTest can execute the real JNI entries (dispatch +
# DNG write) on MoltenVK. Proof-only; never shipped or referenced by Gradle.
if(NOT DEFINED SRP_JAVA_HOME)
  if(DEFINED ENV{JAVA_HOME})
    set(SRP_JAVA_HOME "$ENV{JAVA_HOME}")
  else()
    message(FATAL_ERROR "srproof: set -DSRP_JAVA_HOME=<jdk> or JAVA_HOME")
  endif()
endif()
add_library(srpipeline_jni SHARED
    ${SRP_ROOT}/srp_context.cpp
    ${SRP_ROOT}/srp_pipeline.cpp
    ${SRP_ROOT}/srp_fft.cpp
    ${SRP_ROOT}/srp_dng.cpp
    ${SRP_ROOT}/srp_host.cpp
    ${SRP_ROOT}/srp_jni.cpp)
target_include_directories(srpipeline_jni PRIVATE
    ${SRP_ROOT} ${SRP_TINYDNG_DIR} ${SRP_VK_INCLUDE}
    "${SRP_JAVA_HOME}/include" "${SRP_JAVA_HOME}/include/darwin"
    "${SRP_JAVA_HOME}/include/linux")
target_compile_definitions(srpipeline_jni PRIVATE VK_ENABLE_BETA_EXTENSIONS)
target_compile_options(srpipeline_jni PRIVATE -Wall -Wextra)
target_link_libraries(srpipeline_jni PRIVATE srp_tinydng_host ${SRP_VK_LIB})

