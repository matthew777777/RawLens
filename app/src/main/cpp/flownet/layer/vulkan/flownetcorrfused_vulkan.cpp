// Copyright 2026 flownet-v2-ncnn
// SPDX-License-Identifier: BSD-3-Clause

#include "flownetcorrfused_vulkan.h"

#include "gpu.h"      // compile_spirv_module
#include "pipeline.h"  // Pipeline

#include "flownetcorrfused_comp.h"  // generated shader text

namespace ncnn {

FlownetCorrFused_vulkan::FlownetCorrFused_vulkan()
{
    support_vulkan = true;
    support_vulkan_packing = false;
    support_vulkan_any_packing = false;
    support_fp16_storage = true;
    support_bf16_storage = false;

    pipeline_flownet_corr_fused = 0;
}

int FlownetCorrFused_vulkan::create_pipeline(const Option& opt)
{
    Option opt_fp16 = opt;
    opt_fp16.use_fp16_packed = false;
    opt_fp16.use_fp16_arithmetic = false;

    std::vector<vk_specialization_type> specializations(0);

    std::vector<uint32_t> spirv;
    int ret = compile_spirv_module((const char*)flownetcorrfused_comp_data,
                                   flownetcorrfused_comp_data_size,
                                   opt_fp16, spirv);
    if (ret != 0 || spirv.empty())
        return -1;

    pipeline_flownet_corr_fused = new Pipeline(vkdev);
    pipeline_flownet_corr_fused->set_optimal_local_size_xyz(8, 8, 1);
    pipeline_flownet_corr_fused->create(spirv.data(), spirv.size() * 4, specializations);

    return 0;
}

int FlownetCorrFused_vulkan::destroy_pipeline(const Option& /*opt*/)
{
    delete pipeline_flownet_corr_fused;
    pipeline_flownet_corr_fused = 0;

    return 0;
}

int FlownetCorrFused_vulkan::forward(const std::vector<VkMat>& bottom_blobs, std::vector<VkMat>& top_blobs, VkCompute& cmd, const Option& opt) const
{
    const VkMat& feat0 = bottom_blobs[0];
    const VkMat& feat1 = bottom_blobs[1];
    const VkMat& coords = bottom_blobs[2];

    const int C = feat0.w;
    const int H = coords.h;
    const int W = coords.w;
    const int KK = 9;
    const int K2 = 9;
    const int n_chan = KK * K2;

    const size_t elemsize = feat0.elemsize;

    VkMat& top = top_blobs[0];
    top.create(W, H, n_chan, elemsize, opt.blob_vkallocator);
    if (top.empty())
        return -100;

    std::vector<VkMat> bindings(4);
    bindings[0] = feat0;
    bindings[1] = feat1;
    bindings[2] = coords;
    bindings[3] = top;

    std::vector<vk_constant_type> constants(8);
    constants[0].i = H;
    constants[1].i = W;
    constants[2].i = C;
    constants[3].i = (int)coords.cstep;
    constants[4].i = (int)top.cstep;
    constants[5].i = KK;
    constants[6].i = K2;
    constants[7].i = 4; // radius

    VkMat dispatcher;
    dispatcher.w = W * 8;
    dispatcher.h = H * 8;
    dispatcher.c = 1;
    cmd.record_pipeline(pipeline_flownet_corr_fused, bindings, constants, dispatcher);

    return 0;
}

} // namespace ncnn
