// SPDX-License-Identifier: Apache-2.0
#pragma once
// SPIR-V of shaders/*.comp, compiled at build time by glslc -mfmt=num
// (CMakeLists.txt) and embedded, so desktop and Android load identical
// modules without asset plumbing.
#include <cstdint>

namespace lkr {
namespace spv {
static const uint32_t dct_x_y[] = {
#include "dct_x_y.inc"
};
static const uint32_t dct_x_u[] = {
#include "dct_x_u.inc"
};
static const uint32_t dct_x_v[] = {
#include "dct_x_v.inc"
};
static const uint32_t dct_yuv[] = {
#include "dct_yuv.inc"
};
static const uint32_t resid_adj[] = {
#include "resid_adj.inc"
};
static const uint32_t zstep[] = {
#include "zstep.inc"
};
static const uint32_t grad[] = {
#include "grad.inc"
};
static const uint32_t jac[] = {
#include "jac.inc"
};
static const uint32_t resid[] = {
#include "resid.inc"
};
static const uint32_t zstep_gather[] = {
#include "zstep_gather.inc"
};
static const uint32_t weight[] = {
#include "weight.inc"
};
static const uint32_t weight_min[] = {
#include "weight_min.inc"
};
static const uint32_t weight_minh[] = {
#include "weight_minh.inc"
};
static const uint32_t resid_adj_w[] = {
#include "resid_adj_w.inc"
};
static const uint32_t weight_e[] = {
#include "weight_e.inc"
};
static const uint32_t weight_th[] = {
#include "weight_th.inc"
};
static const uint32_t zstep_gw[] = {
#include "zstep_gw.inc"
};
static const uint32_t resid_q[] = {
#include "resid_q.inc"
};
static const uint32_t resid_adj_w_q[] = {
#include "resid_adj_w_q.inc"
};
static const uint32_t scatter_q[] = {
#include "scatter_q.inc"
};
static const uint32_t zpack[] = {
#include "zpack.inc"
};
static const uint32_t resid_zq[] = {
#include "resid_zq.inc"
};
static const uint32_t resid_adj_w_zq[] = {
#include "resid_adj_w_zq.inc"
};
static const uint32_t weight_t[] = {
#include "weight_t.inc"
};
static const uint32_t weight_tv[] = {
#include "weight_tv.inc"
};
static const uint32_t weight_td[] = {
#include "weight_td.inc"
};
static const uint32_t scatter[] = {
#include "scatter.inc"
};
static const uint32_t jacblk[] = {
#include "jacblk.inc"
};
static const uint32_t solveblk[] = {
#include "solveblk.inc"
};
static const uint32_t srp_align[] = {   // tools/sr-pipeline/.../shaders/align.comp
#include "srp_align.inc"
};
static const uint32_t solve[] = {
#include "solve.inc"
};
}  // namespace spv
}  // namespace lkr
