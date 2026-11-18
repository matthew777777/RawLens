// SPDX-License-Identifier: Apache-2.0
// Learned post-fit prior for LK-R Lite (research, opt-in): a small CNN (export_nnapi.py layout:
// CONV_2D + fused ReLU, identity 1x1 skip, ADD) that removes Lite's zipper / colour dots while keeping
// its grain (trained on grain-preserving synthetic pairs, tools/lkr-lite/research/prior). Applied per
// tile to Lite's merged image before FinishLook. On Android it runs through NNAPI on ONE accelerator
// (MediaTek MDLA: compiled for that device only, every op verified supported: no GPU / CPU split), so
// a worker waits on the NPU while the other worker keeps the GPU busy. On the host: CPU reference.
#pragma once
#include <string>

namespace lkrl {

struct PriorStats { double ms = 0; bool ran = false; std::string device; };

// App setting (Settings -> Merge -> "Network"); LKRL_PRIOR=0/1 (host) / debug.lkr.prior (Android, set
// by the JNI layer as LKRL_PRIOR) override it for tests.
void PriorSetEnabled(bool on);
bool PriorEnabled();
// Build and compile the model for an H x W tile now (camera open), so the first merge does not pay it.
bool PriorPrewarm(int H, int W, std::string* err);
// z: planar 3 x H x W linear camera RGB (Lite's result before finishing), corrected in place.
// noise_a: Lite's signal-dependent noise coefficient (normalised units). delta (optional, 3 x H x W):
// receives the correction (new - old) so another copy of the image can be corrected the same way.
bool PriorApply(float* z, int H, int W, float noise_a, float* delta, PriorStats* st, std::string* err);

}  // namespace lkrl
