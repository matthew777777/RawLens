// Copyright 2026 flownet-v2-ncnn
// SPDX-License-Identifier: BSD-3-Clause

#include "flownetupsample.h"

#include <math.h>

#include "layer_type.h"
#include "mat.h"

namespace ncnn {

FlownetUpsample::FlownetUpsample()
{
    one_blob_only = false;
    support_inplace = false;
    support_packing = false;
    support_fp16_storage = false;
    support_bf16_storage = false;
    support_vulkan = true;

    scale = 8;
}

int FlownetUpsample::load_param(const ParamDict& pd)
{
    scale = pd.get(0, 8);
    return 0;
}

// convex upsampling used by FlowNet-v2 UpSample module
//
// bottom_blobs[0] : flow       [2, H, W]  (s8 optical flow, 2 channels)
// bottom_blobs[1] : mask       [9*K*K, H, W]  (pre-softmax convex weights
//                                                  layout: channel = n*K*K + k)
// top_blobs[0]    : up_flow    [2, K*H, K*W]  (output flow, already * K in value)
//
// for each coarse cell (i, j) and sub-pixel offset (ky, kx):
//   top[o, i*K+ky, j*K+kx] = K * sum_{n=0..8} softmax_n(mask)[n, ky*K+kx, i, j]
//                                      * flow[o, i+dy(n), j+dx(n)]
// (matches torch: mask.view(1,1,9,K,K,H,W).softmax(2) * unfold(flow,3,3).view(...))
int FlownetUpsample::forward(const std::vector<Mat>& bottom_blobs, std::vector<Mat>& top_blobs, const Option& opt) const
{
    const Mat& flow = bottom_blobs[0];
    const Mat& mask = bottom_blobs[1];

    const int K = scale;
    const int H = flow.h;
    const int W = flow.w;
    const int HW = H * W;
    const int kk = K * K;

    Mat& top = top_blobs[0];
    top.create(W * K, H * K, flow.c, 4u, opt.blob_allocator);
    if (top.empty())
        return -100;

    // ---- softmax mask over the 9 groups ----
    Mat sm_mask = mask.clone(opt.workspace_allocator);
    if (sm_mask.empty())
        return -100;

    float* sm = sm_mask;
    const float* mp = mask;

    for (int i = 0; i < H; i++)
    {
        for (int j = 0; j < W; j++)
        {
            const int idx = i * W + j;
            for (int k = 0; k < kk; k++)
            {
                // gather the 9 scores
                float m[9];
                float mx = -(1e30f);
                for (int n = 0; n < 9; n++)
                {
                    m[n] = mp[(n * kk + k) * HW + idx];
                    if (m[n] > mx)
                        mx = m[n];
                }

                // exp and normalize
                float s = 0.f;
                for (int n = 0; n < 9; n++)
                {
                    m[n] = (float)exp((double)(m[n] - mx));
                    s += m[n];
                }
                const float inv_s = 1.f / s;
                for (int n = 0; n < 9; n++)
                {
                    sm[(n * kk + k) * HW + idx] = m[n] * inv_s;
                }
            }
        }
    }

    // ---- convex combination + Kx upsample ----
    const int oh = H * K;
    const int ow = W * K;

    #pragma omp parallel for num_threads(opt.num_threads)
    for (int o = 0; o < flow.c; o++)
    {
        const float* flowptr = flow.channel(o);
        float* topptr = top.channel(o);

        for (int y = 0; y < oh; y++)
        {
            const int i = y / K;
            const int ky = y - i * K;
            const int ybase = y * ow;

            for (int x = 0; x < ow; x++)
            {
                const int j = x / K;
                const int kx = x - j * K;
                const int k = ky * K + kx;

                float acc = 0.f;
                for (int n = 0; n < 9; n++)
                {
                    const int dy = n / 3 - 1;
                    const int dx = n % 3 - 1;
                    const int iy = i + dy;
                    const int jx = j + dx;

                    float v = 0.f;
                    if (iy >= 0 && iy < H && jx >= 0 && jx < W)
                        v = flowptr[iy * W + jx];

                    const float wm = sm[(n * kk + k) * HW + i * W + j];
                    acc += wm * v;
                }

                topptr[ybase + x] = acc * (float)K;
            }
        }
    }

    return 0;
}

} // namespace ncnn