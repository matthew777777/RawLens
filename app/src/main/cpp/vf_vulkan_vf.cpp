// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Vulkan viewfinder + Direct-Log compute module. One translation unit: the
// parts below are textually included in order (they share the anonymous
// namespace and the global Context), and dual_raw_vulkan.cpp includes this
// file the same way. Split for navigation only; the preprocessed source is
// unchanged (docs/refactor/vf-native-study.md).

#include "vf_vulkan_vf_types.inc"  // Includes, logging, push-constant layouts and the shared Vulkan Context.
#include "vf_vulkan_vf_resources.inc"  // Context-owned buffers: LUT, staging, bridge, and AHardwareBuffer imports.
#include "vf_vulkan_vf_pipelines.inc"  // Compute pipeline creation for every stage (superpixel, grade, YUV, preview, RGB8, RCD, MHC, fused).
#include "vf_vulkan_vf_packed_rcd_resources.inc"  // Independent packed-ratio RCD resources.
#include "vf_vulkan_vf_shade_lut.inc"  // Lens-shading images, stage teardown and the grade LUT; closes the anonymous namespace.
#include "vf_vulkan_vf_viewfinder.inc"  // Context lifecycle and the RAW viewfinder superpixel dispatch (buffer, image, bridge).
#include "vf_vulkan_vf_grade_twins.inc"  // Phase-B grade stage and the Direct-Log wait-free submit twins.
#include "vf_vulkan_vf_p010_preview.inc"  // True-10-bit P010 stage and the record-preview monitor.
#include "vf_vulkan_vf_rcd.inc"  // RCD demosaic and the fused RCD finalize + grade.
#include "vf_vulkan_vf_mhc_stab.inc"  // Malvar-He-Cutler demosaic, fused MHC+grade, and the stabilization warp.

#include "vf_vulkan_vf_packed_rcd.inc"  // User-provided Packed-ratio RCD backend.
