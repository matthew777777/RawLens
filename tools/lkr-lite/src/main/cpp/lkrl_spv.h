// SPDX-License-Identifier: Apache-2.0
#pragma once
// sr-pipeline's MFSR block search (align.comp), compiled from ITS source at build time, used by
// the copied block search (lkrl_blocksearch) like LK-R does.
#include <cstdint>
namespace lkrl {
namespace spv {
static const uint32_t srp_align[] = {
#include "srp_align.inc"
};
}  // namespace spv
}  // namespace lkrl
