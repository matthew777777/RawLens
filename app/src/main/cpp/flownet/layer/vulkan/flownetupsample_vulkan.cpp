// Copyright 2026 flownet-v2-ncnn
// SPDX-License-Identifier: BSD-3-Clause

#include "flownetupsample_vulkan.h"

#include "gpu.h"
#include "pipeline.h"

#include "flownetupsample_comp.h"

namespace ncnn {

FlownetUpsample_vulkan::FlownetUpsample_vulkan()
{
    support_vulkan = true;
    support_vulkan_packing = false;
    support_vulkan_any_packing = false;
    support_fp16_storage = true;
    support_bf16_storage = false;

    pipeline_flownet_upsample = 0;
}

int FlownetUpsample_vulkan::create_pipeline(const Option& opt)
{
    Option opt_fp16 = opt;
    opt_fp16.use_fp16_packed = false;
    opt_fp16.use_fp16_arithmetic = false;

    std::vector<vk_specialization_type> specializations(1);
    specializations[0].i = scale;

    std::vector<uint32_t> spirv;
    int ret = compile_spirv_module((const char*)flownetupsample_comp_data,
                                   flownetupsample_comp_data_size,
                                   opt_fp16, spirv);
    if (ret != 0 || spirv.empty())
        return -1;

    pipeline_flownet_upsample = new Pipeline(vkdev);
    pipeline_flownet_upsample->set_optimal_local_size_xyz(8, 8, 1);
    pipeline_flownet_upsample->create(spirv.data(), spirv.size() * 4, specializations);

    return 0;
}

int FlownetUpsample_vulkan::destroy_pipeline(const Option& /*opt*/)
{
    delete pipeline_flownet_upsample;
    pipeline_flownet_upsample = 0;

    return 0;
}

int FlownetUpsample_vulkan::forward(const std::vector<VkMat>& bottom_blobs, std::vector<VkMat>& top_blobs, VkCompute& cmd, const Option& opt) const
{
    const VkMat& flow = bottom_blobs[0];
    const VkMat& mask = bottom_blobs[1];

    const int K = scale;
    const int H = flow.h;
    const int W = flow.w;

    const size_t elemsize = flow.elemsize;

    VkMat& top = top_blobs[0];
    top.create(W * K, H * K, flow.c, elemsize, opt.blob_vkallocator);
    if (top.empty())
        return -100;

    std::vector<VkMat> bindings(3);
    bindings[0] = flow;
    bindings[1] = mask;
    bindings[2] = top;

    std::vector<vk_constant_type> constants(6);
    constants[0].i = H;
    constants[1].i = W;
    constants[2].i = (int)flow.cstep;
    constants[3].i = (int)mask.cstep;
    constants[4].i = (int)top.cstep;
    constants[5].i = K;

    VkMat dispatcher;
    dispatcher.w = W * K;
    dispatcher.h = H * K;
    dispatcher.c = 1;
    cmd.record_pipeline(pipeline_flownet_upsample, bindings, constants, dispatcher);

    return 0;
}

} // namespace ncnn
