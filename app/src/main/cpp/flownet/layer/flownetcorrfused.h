// Copyright 2026 flownet-v2-ncnn
// SPDX-License-Identifier: BSD-3-Clause

#ifndef LAYER_FLOWNET_CORR_FUSED_H
#define LAYER_FLOWNET_CORR_FUSED_H

#include "layer.h"

namespace ncnn {

class FlownetCorrFused : public Layer
{
public:
    FlownetCorrFused();

    virtual int forward(const std::vector<Mat>& bottom_blobs, std::vector<Mat>& top_blobs, const Option& opt) const override;
};

} // namespace ncnn

#endif // LAYER_FLOWNET_CORR_FUSED_H
