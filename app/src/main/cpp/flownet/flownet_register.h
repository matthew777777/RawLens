// Copyright 2026 flownet-v2-ncnn
// SPDX-License-Identifier: BSD-3-Clause
//
// Custom layer registration for FlowNet-v2 ncnn port.
// Call flownet_register_custom_layers(net) before load_param().
//
// When NCNN_VULKAN is enabled (arm64-v8a), registers the Vulkan subclasses
// so the corr/upsample/corrlookup layers run on GPU. When NCNN_VULKAN is off
// (armeabi-v7a CPU-only), registers the CPU base classes instead.
#pragma once

#include "net.h"
#include "layer.h"

#include "flownetupsample.h"
#include "flownetcorrlookup.h"
#include "flownetcorrfused.h"

#if NCNN_VULKAN
#include "flownetupsample_vulkan.h"
#include "flownetcorrlookup_vulkan.h"
#include "flownetcorrfused_vulkan.h"

namespace ncnn {
DEFINE_LAYER_CREATOR(FlownetUpsample_vulkan)
DEFINE_LAYER_CREATOR(FlownetCorrLookup_vulkan)
DEFINE_LAYER_CREATOR(FlownetCorrFused_vulkan)
} // namespace ncnn

static inline int flownet_register_custom_layers(ncnn::Net& net)
{
    net.register_custom_layer("FlownetUpsample",  ncnn::FlownetUpsample_vulkan_layer_creator);
    net.register_custom_layer("FlownetCorrLookup", ncnn::FlownetCorrLookup_vulkan_layer_creator);
    net.register_custom_layer("FlownetCorrFused",  ncnn::FlownetCorrFused_vulkan_layer_creator);
    return 0;
}
#else
namespace ncnn {
DEFINE_LAYER_CREATOR(FlownetUpsample)
DEFINE_LAYER_CREATOR(FlownetCorrLookup)
DEFINE_LAYER_CREATOR(FlownetCorrFused)
} // namespace ncnn

static inline int flownet_register_custom_layers(ncnn::Net& net)
{
    net.register_custom_layer("FlownetUpsample",  ncnn::FlownetUpsample_layer_creator);
    net.register_custom_layer("FlownetCorrLookup", ncnn::FlownetCorrLookup_layer_creator);
    net.register_custom_layer("FlownetCorrFused",  ncnn::FlownetCorrFused_layer_creator);
    return 0;
}
#endif
