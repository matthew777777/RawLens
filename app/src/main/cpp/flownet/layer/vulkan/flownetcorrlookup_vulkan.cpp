// Copyright 2026 flownet-v2-ncnn
// SPDX-License-Identifier: BSD-3-Clause

#include "flownetcorrlookup_vulkan.h"

#include "gpu.h"
#include "pipeline.h"

#include "flownetcorrlookup_comp.h"

namespace ncnn {

FlownetCorrLookup_vulkan::FlownetCorrLookup_vulkan()
{
    support_vulkan = true;
    support_vulkan_packing = false;
    support_fp16_storage = false;
    support_bf16_storage = false;

    pipeline_flownet_corr_lookup = 0;
}

int FlownetCorrLookup_vulkan::create_pipeline(const Option& opt)
{
    std::vector<vk_specialization_type> specializations(0);

    std::vector<uint32_t> spirv;
    int ret = compile_spirv_module((const char*)flownetcorrlookup_comp_data,
                                   flownetcorrlookup_comp_data_size,
                                   opt, spirv);
    if (ret != 0 || spirv.empty())
        return -1;

    pipeline_flownet_corr_lookup = new Pipeline(vkdev);
    pipeline_flownet_corr_lookup->set_optimal_local_size_xyz(8, 8, 1);
    pipeline_flownet_corr_lookup->create(spirv.data(), spirv.size() * 4, specializations);

    return 0;
}

int FlownetCorrLookup_vulkan::destroy_pipeline(const Option& /*opt*/)
{
    delete pipeline_flownet_corr_lookup;
    pipeline_flownet_corr_lookup = 0;

    return 0;
}

int FlownetCorrLookup_vulkan::forward(const std::vector<VkMat>& bottom_blobs, std::vector<VkMat>& top_blobs, VkCompute& cmd, const Option& opt) const
{
    const VkMat& corr = bottom_blobs[0];
    const VkMat& coords = bottom_blobs[1];
    const VkMat& delta = bottom_blobs[2];

    const int H = corr.h;
    const int W = corr.w;
    const int KK = delta.d;     // 9
    const int K2 = delta.h;     // 9
    const int n_chan = KK * K2; // 81

    VkMat& top = top_blobs[0];
    top.create(W, H, n_chan, 4u, opt.blob_vkallocator);
    if (top.empty())
        return -100;

    std::vector<VkMat> bindings(4);
    bindings[0] = corr;
    bindings[1] = coords;
    bindings[2] = delta;
    bindings[3] = top;

    std::vector<vk_constant_type> constants(8);
    constants[0].i = H;
    constants[1].i = W;
    constants[2].i = (int)(corr.n > 1 ? corr.nstep : corr.cstep);
    constants[3].i = (int)(coords.n > 1 ? coords.nstep : coords.cstep);
    constants[4].i = (int)(delta.n > 1 ? delta.nstep : delta.cstep);
    constants[5].i = (int)top.cstep;
    constants[6].i = KK;
    constants[7].i = K2;

    VkMat dispatcher;
    dispatcher.w = W;
    dispatcher.h = H;
    dispatcher.c = 1;
    cmd.record_pipeline(pipeline_flownet_corr_lookup, bindings, constants, dispatcher);

    return 0;
}

} // namespace ncnn
