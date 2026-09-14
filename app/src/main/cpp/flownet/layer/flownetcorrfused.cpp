// Copyright 2026 flownet-v2-ncnn
// SPDX-License-Identifier: BSD-3-Clause

#include "flownetcorrfused.h"

#include "mat.h"

namespace ncnn {

FlownetCorrFused::FlownetCorrFused()
{
    one_blob_only = false;
    support_inplace = false;
    support_packing = false;
    support_batch = false;
    support_fp16_storage = false;
    support_bf16_storage = false;
    support_vulkan = false;
}

// Fused CorrBlock (FlowNet_v2/FlowNet/corr.py): replaces
//   init_corr_pyr:  corr = (feat0.T @ feat1) / sqrt(C)        # [N,1,H,W]
//   __call__:       corr_feat = bilinear_sample(corr, coords+delta)  # [1,81,H,W]
// with a single pass that, per query, only computes the 81 dot-products it
// actually needs (4 taps each) instead of the full N*N correlation matrix.
//
// feat0/feat1 arrive already reshaped+transposed to [N, C] (h=N, w=C) so that
// each query's C-dim feature vector is contiguous -- critical for coalesced
// GPU access and cache-friendly CPU dot products.
//
// Inputs (3):
//   bottom_blobs[0] : feat0   [C, N]   (h=N=H*W, w=C)   channel-contiguous
//   bottom_blobs[1] : feat1   [C, N]   (h=N=H*W, w=C)
//   bottom_blobs[2] : coords  [2, H, W]                 (ch0=x, ch1=y)
// Output (1):
//   top_blobs[0]    : corr_feat [81, H, W]
// The 9x9 delta grid is the constant linspace(-4,4,9) cross product, computed
// inline (dx = i-4, dy = j-4) -- no delta input needed, which also sidesteps
// the ncnn 4D/3D VkMat fp16 cast corruption.
int FlownetCorrFused::forward(const std::vector<Mat>& bottom_blobs, std::vector<Mat>& top_blobs, const Option& opt) const
{
    const Mat& feat0 = bottom_blobs[0];
    const Mat& feat1 = bottom_blobs[1];
    const Mat& coords = bottom_blobs[2];

    const int N = feat0.h;
    const int C = feat0.w;
    const int H = coords.h;
    const int W = coords.w;
    const int KK = 9;
    const int K2 = 9;
    const int n_chan = KK * K2; // 81
    const int radius = 4;

    Mat& top = top_blobs[0];
    top.create(W, H, n_chan, 4u, opt.blob_allocator);
    if (top.empty())
        return -100;

    const float* f0p = (const float*)feat0.data;
    const float* f1p = (const float*)feat1.data;
    const float* cp = (const float*)coords.data;
    const size_t cstep = coords.cstep;

    const float scale = 1.f / sqrtf((float)C);

    // feat0[n, :] and feat1[p, :] are C contiguous values (row-major 2D)
    #pragma omp parallel for num_threads(opt.num_threads)
    for (int n = 0; n < N; n++)
    {
        const int qy = n / W;
        const int qx = n - qy * W;

        const float cx = cp[0 * cstep + (size_t)n];
        const float cy = cp[1 * cstep + (size_t)n];

        const float* f0 = f0p + (size_t)n * C;

        for (int i = 0; i < KK; i++)
        {
            const float dx = (float)(i - radius);
            for (int j = 0; j < K2; j++)
            {
                const float dy = (float)(j - radius);
                const int k = i * K2 + j;

                const float sx = cx + dx;
                const float sy = cy + dy;
                const int ix = (int)floorf(sx);
                const int iy = (int)floorf(sy);
                const float fx = sx - (float)ix;
                const float fy = sy - (float)iy;

                float v = 0.f;
                const int xs[4] = {ix, ix + 1, ix, ix + 1};
                const int ys[4] = {iy, iy, iy + 1, iy + 1};
                const float wx[4] = {1.f - fx, fx, 1.f - fx, fx};
                const float wy[4] = {1.f - fy, 1.f - fy, fy, fy};
                for (int t = 0; t < 4; t++)
                {
                    const int tx = xs[t];
                    const int ty = ys[t];
                    if (tx >= 0 && tx < W && ty >= 0 && ty < H)
                    {
                        const float* f1 = f1p + (size_t)(ty * W + tx) * C;
                        float dot = 0.f;
                        for (int c = 0; c < C; c++)
                            dot += f0[c] * f1[c];
                        v += wx[t] * wy[t] * dot;
                    }
                }

                top.channel(k).row(qy)[qx] = v * scale;
            }
        }
    }

    return 0;
}

} // namespace ncnn
