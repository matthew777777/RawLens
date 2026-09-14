// Copyright 2026 flownet-v2-ncnn
// SPDX-License-Identifier: BSD-3-Clause

#ifndef LAYER_FLOWNET_UPSAMPLE_VULKAN_H
#define LAYER_FLOWNET_UPSAMPLE_VULKAN_H

#include "flownetupsample.h"

namespace ncnn {

class FlownetUpsample_vulkan : public FlownetUpsample
{
public:
    FlownetUpsample_vulkan();

    virtual int create_pipeline(const Option& opt) override;
    virtual int destroy_pipeline(const Option& opt) override;

    virtual int forward(const std::vector<VkMat>& bottom_blobs, std::vector<VkMat>& top_blobs, VkCompute& cmd, const Option& opt) const override;

private:
    Pipeline* pipeline_flownet_upsample;
};

} // namespace ncnn

#endif // LAYER_FLOWNET_UPSAMPLE_VULKAN_H
