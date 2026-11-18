// SPDX-License-Identifier: Apache-2.0
// Learned post-fit prior for LK-R Lite (research, opt-in): a small CNN (export_nnapi.py layout:
// CONV_2D + fused ReLU, identity 1x1 skip, ADD) that removes Lite's zipper / colour dots while keeping
// its grain (trained on grain-preserving synthetic pairs, tools/lkr-lite/research/prior). Applied per
// tile to Lite's merged image before FinishLook. On Android it runs through NNAPI on ONE accelerator
// (MediaTek MDLA or Qualcomm Hexagon, via the phone's own NNAPI driver, nothing proprietary bundled:
// compiled for that device only, every op verified supported: no GPU / CPU split), so
// a worker waits on the NPU while the other worker keeps the GPU busy. On the host: CPU reference.
#pragma once
#include <string>
#include <vector>

namespace lkrl {

struct PriorStats { double ms = 0; bool ran = false; std::string device; };

// App setting (Settings -> Merge -> "Network"); LKRL_PRIOR=0/1 (host) / debug.lkr.network (Android, set
// by the JNI layer as LKRL_PRIOR) override it for tests.
void PriorSetEnabled(bool on);
bool PriorEnabled();
// Build and compile the model for an H x W tile now (camera open), so the first merge does not pay it.
bool PriorPrewarm(int H, int W, std::string* err);
// z: planar 3 x H x W linear camera RGB (Lite's result before finishing), corrected in place.
// noise_a: Lite's signal-dependent noise coefficient (normalised units). delta (optional, 3 x H x W):
// receives the correction (new - old) so another copy of the image can be corrected the same way.
bool PriorApply(float* z, int H, int W, float noise_a, float* delta, PriorStats* st, std::string* err);

// A usable NPU for the prior (Android: an NNAPI NPU: MDLA, Hexagon (qti-htp / qti-dsp), or LKRL_PRIOR_DEVICE; host:
// false unless LKRL_PRIOR_CPU=1 asks for the CPU reference). Without one, Lite runs the Vulkan
// fallback (prior_conv.comp) inside the solver.
bool PriorNpuAvailable();
// Folded weights for the Vulkan fallback: wb = [OHWI weights, bias] per layer; info = per layer
// {cin, cout, k, relu, weight offset, bias offset} (floats into wb).
struct PriorLayerInfo { int cin, cout, k, relu, woff, boff; };
bool PriorPacked(std::vector<float>* wb, std::vector<PriorLayerInfo>* info);
// Colour-only correction (default): luma kept as Lite's. False with LKRL_PRIOR_FULL=1 (research).
bool PriorColourOnly();

}  // namespace lkrl
