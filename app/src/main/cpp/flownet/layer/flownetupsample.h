// Copyright 2026 flownet-v2-ncnn
// SPDX-License-Identifier: BSD-3-Clause

#ifndef LAYER_FLOWNET_UPSAMPLE_H
#define LAYER_FLOWNET_UPSAMPLE_H

#include "layer.h"

namespace ncnn {

class FlownetUpsample : public Layer
{
public:
    FlownetUpsample();

    virtual int load_param(const ParamDict& pd) override;

    virtual int forward(const std::vector<Mat>& bottom_blobs, std::vector<Mat>& top_blobs, const Option& opt) const override;

public:
    // upsample_factor (8 for FlowNet-v2)
    int scale;
};

} // namespace ncnn

#endif // LAYER_FLOWNET_UPSAMPLE_H