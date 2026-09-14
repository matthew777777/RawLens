// Copyright 2026 flownet-v2-ncnn
// SPDX-License-Identifier: BSD-3-Clause

#include "flownetcorrlookup.h"

#include "mat.h"

namespace ncnn {

FlownetCorrLookup::FlownetCorrLookup()
{
    one_blob_only = false;
    support_inplace = false;
    support_packing = false;
    support_batch = true;
    support_fp16_storage = false;
    support_bf16_storage = false;
    support_vulkan = false;
}

// Replaces the broken pnnx-emitted correlation-lookup tail that ncnn cannot run
// because BinaryOp lacks 5D broadcasting (coords+delta) and the downstream
// split / cat / GridSample axes do not line up with ncnn's NCDHW convention.
//
// Mirrors FlowNet_v2/FlowNet/corr.py CorrBlock.__call__ for levels=1, radius=4:
//
//   curr    = coords[:,None,None,:] + delta        # [N, 9, 9, 2]
//   sampled = bilinear_sample(corr, curr)          # grid_sample, align_corners=True, zeros pad
//   out     = sampled.view(1, H, W, 81).permute(0,3,1,2)  # [1, 81, H, W]
//
// Inputs (3):
//   bottom_blobs[0] : corr    [N, 1, H, W]   (n=N, c=1, h=H, w=W)   N == H*W
//   bottom_blobs[1] : coords  [N, ..., 2]    (c=N, ..., w=2)        per-query (x,y)
//   bottom_blobs[2] : delta   [N, 9, 9, 2]   (c=N, d=9, h=9, w=2)
// Output (1):
//   top_blobs[0]    : corr_feat [1, 81, H, W] (c=81, h=H, w=W)
int FlownetCorrLookup::forward(const std::vector<Mat>& bottom_blobs, std::vector<Mat>& top_blobs, const Option& opt) const
{
    const Mat& corr = bottom_blobs[0];
    const Mat& coords = bottom_blobs[1];
    const Mat& delta = bottom_blobs[2];

    const int N = corr.n;
    const int H = corr.h;
    const int W = corr.w;
    const int KK = delta.d;     // 9
    const int K2 = delta.h;     // 9
    const int n_chan = KK * K2; // 81

    Mat& top = top_blobs[0];
    top.create(W, H, n_chan, 4u, opt.blob_allocator);
    if (top.empty())
        return -100;

    const float* corrp = (const float*)corr.data;
    // ncnn batched Mats (n>1) pad nstep to a 4K boundary, so the per-query stride
    // is nstep, NOT cstep. delta is laid out as c=N (n=1), so its per-query
    // stride is the channel cstep.
    const size_t corr_qstep = corr.n > 1 ? corr.nstep : corr.cstep;
    const float* cp = (const float*)coords.data;
    const size_t coords_qstep = coords.n > 1 ? coords.nstep : coords.cstep;
    const float* dp = (const float*)delta.data;
    const size_t delta_qstep = delta.n > 1 ? delta.nstep : delta.cstep;

    #pragma omp parallel for num_threads(opt.num_threads)
    for (int n = 0; n < N; n++)
    {
        const int qh = n / W;
        const int qw = n - qh * W;

        // coords[n] = (x, y) stored as the last axis of size 2
        const float* cptr = cp + (size_t)n * coords_qstep;
        const float cx = cptr[0];
        const float cy = cptr[1];

        const float* corr_chan = corrp + (size_t)n * corr_qstep;     // corr[n, 0, :, :]
        const float* dptr = dp + (size_t)n * delta_qstep;            // delta[n, :, :, :]

        for (int i = 0; i < KK; i++)
        {
            for (int j = 0; j < K2; j++)
            {
                const float* drow = dptr + (size_t)(i * K2 + j) * 2; // delta[n, i, j, :]
                const float x = cx + drow[0];
                const float y = cy + drow[1];

                // bilinear sample corr[n,0] at pixel (x, y), align_corners=true, zeros pad
                const int ix = (int)floorf(x);
                const int iy = (int)floorf(y);
                const float fx = x - (float)ix;
                const float fy = y - (float)iy;
                const int x0 = ix, x1 = ix + 1, y0 = iy, y1 = iy + 1;

                float v = 0.f;
                if (x0 >= 0 && x0 < W && y0 >= 0 && y0 < H)
                    v += corr_chan[y0 * W + x0] * (1.f - fx) * (1.f - fy);
                if (x1 >= 0 && x1 < W && y0 >= 0 && y0 < H)
                    v += corr_chan[y0 * W + x1] * fx * (1.f - fy);
                if (x0 >= 0 && x0 < W && y1 >= 0 && y1 < H)
                    v += corr_chan[y1 * W + x0] * (1.f - fx) * fy;
                if (x1 >= 0 && x1 < W && y1 >= 0 && y1 < H)
                    v += corr_chan[y1 * W + x1] * fx * fy;

                const int k = i * K2 + j;
                top.channel(k).row(qh)[qw] = v;
            }
        }
    }

    return 0;
}

} // namespace ncnn
