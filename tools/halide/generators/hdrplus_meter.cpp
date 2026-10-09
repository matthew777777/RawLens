// SPDX-License-Identifier: GPL-3.0-or-later
// HDR+ motion meter: stride-16 mismatch maps plus dense per-cell maps.
//
// Outputs over the 32px cell grid (ceil(W/32) x ceil(H/32)):
//   ratio/mad16/tex16 replicate HdrPlusMotionMeter.blockMismatchRatios
//   (stride 16, 2-sample blocks) BIT-EXACTLY: f64 arithmetic in the same
//   op order. This is the parity anchor: the native meter must never
//   disagree with the Kotlin meter it replaces (integer-domain stage
//   => bitwise gate).
//   mad_dense/tex_dense are NEW: full-res mean |prev-curr| and mean
//   horizontal-neighbor texture per cell, same normalizations. Dense
//   sampling is the point: stride-16 skips over the 1-2px wires that
//   actually ghost (see rawlens-session/flownet-vs-meter-2026-10-08).
//   Dense texture is horizontal-pairs-only to match the shipped texture
//   definition (same blind spots, documented); vertical pairs are a
//   follow-up if horizontal-wire ghosts are ever demonstrated.
//   hot replicates blockHotFraction (8-sample blocks, full only).
//
// Bitwise method: every f64 accumulation is a flat SERIAL RDom (one
// index, ascending, row-major decode). Unrolled Expr chains do NOT
// work: Halide's simplifier re-nests ((a+b)+c)+d into right-deep form
// (observed 1-ULP drift in the host gate). A serial reduction loop is
// order-exact by construction; only the pure output cells are
// parallelized/vectorized (independent cells, order-free).
//
// Contracts: W,H >= 1; range finite and > 0 (white-black); the caller
// allocates every map at ceil(W/32) x ceil(H/32) and hot at extent 1.
// Bad range is caller error (the Kotlin side returns null/NaN there;
// this filter takes valid params, GCam-pipeline style).
#include "Halide.h"

using namespace Halide;

namespace {

constexpr int kStride = 16;
constexpr int kMapBlock = 2;
constexpr int kHotBlock = 8;

}  // namespace

class HdrplusMeter : public Halide::Generator<HdrplusMeter> {
public:
    Input<Buffer<uint16_t>> prev_{"prev", 2};
    Input<Buffer<uint16_t>> curr_{"curr", 2};
    Input<double> range_{"range"};
    Input<double> hot_ratio_{"hot_ratio"};
    Output<Buffer<double>> ratio_{"ratio", 2};
    Output<Buffer<double>> mad16_{"mad16", 2};
    Output<Buffer<double>> tex16_{"tex16", 2};
    Output<Buffer<double>> mad_dense_{"mad_dense", 2};
    Output<Buffer<double>> tex_dense_{"tex_dense", 2};
    Output<Buffer<double>> hot_{"hot", 1};

    void generate() {
        Var bx("bx"), by("by");
        Var cx("cx"), cy("cy");
        Var gx("gx"), gy("gy");
        Var c("c");

        // Halide has no (Expr, double-literal) operator overloads, so
        // every double operand is wrapped explicitly. Values are
        // unchanged: same IEEE ops in the same order as the Kotlin.
        const Expr kRng = range_;
        const Expr kHotRatio = hot_ratio_;
        const Expr kZero = Expr(0.0);
        const Expr kTwo = Expr(2.0);

        Expr W = prev_.dim(0).extent();
        Expr H = prev_.dim(1).extent();
        Expr gw = (W + kStride - 1) / kStride;
        Expr gh = (H + kStride - 1) / kStride;

        // Strided sample grid, clamped reads (edge blocks select the
        // contribution to zero; clamping keeps every lowered read in
        // bounds even where vectorized select evaluates both sides).
        Func Ag("Ag"), Bg("Bg");
        Ag(gx, gy) = cast<int>(prev_(clamp(gx, 0, gw - 1) * kStride,
                                      clamp(gy, 0, gh - 1) * kStride));
        Bg(gx, gy) = cast<int>(curr_(clamp(gx, 0, gw - 1) * kStride,
                                      clamp(gy, 0, gh - 1) * kStride));

        // --- stride-16 maps: flat serial RDom, i -> (sy=i/2, sx=i%2) ---
        {
            RDom r(0, kMapBlock * kMapBlock);
            Expr i = r.x;
            Expr sx = i % kMapBlock, sy = i / kMapBlock;
            Expr x = bx * kMapBlock + sx;
            Expr y = by * kMapBlock + sy;
            Expr in_b = x < gw && y < gh;
            Expr va = Ag(x, y), vb = Bg(x, y);
            Expr in_p = in_b && (sx + 1 < kMapBlock) && (x + 1 < gw);
            Func msum("msum"), tsum("tsum"), nsamp("nsamp"), npair("npair");
            msum(bx, by) = kZero;
            msum(bx, by) += select(in_b, cast<double>(abs(va - vb)) / kRng, kZero);
            // Texture interleaves prev/curr terms per sample (va0,vb0,
            // va1,vb1..): Halide runs multiple update stages
            // back-to-back (all va, then all vb), so one doubled RDom
            // with an even/odd phase keeps Kotlin's exact order.
            RDom rt(0, 2 * kMapBlock * kMapBlock);
            Expr kt = rt.x, it = kt / 2, ph = kt % 2;
            Expr sxt = it % kMapBlock, syt = it / kMapBlock;
            Expr xt = bx * kMapBlock + sxt;
            Expr yt = by * kMapBlock + syt;
            Expr in_bt = xt < gw && yt < gh;
            Expr in_pt = in_bt && (sxt + 1 < kMapBlock) && (xt + 1 < gw);
            Expr vat = Ag(xt, yt), vbt = Bg(xt, yt);
            tsum(bx, by) = kZero;
            tsum(bx, by) += select(in_pt,
                                   select(ph == 0,
                                          cast<double>(abs(vat - Ag(xt + 1, yt))) / kRng,
                                          cast<double>(abs(vbt - Bg(xt + 1, yt))) / kRng),
                                   kZero);
            nsamp(bx, by) = 0;
            nsamp(bx, by) += select(in_b, 1, 0);
            npair(bx, by) = 0;
            npair(bx, by) += select(in_p, 1, 0);
            // max(.,1): empty sums are exactly 0.0 (no terms added), so
            // 0/1 reproduces Kotlin's samples==0/pairs==0 branches.
            Expr mad = msum(bx, by) / cast<double>(max(nsamp(bx, by), 1));
            Expr tex = tsum(bx, by) / (kTwo * cast<double>(max(npair(bx, by), 1)));
            mad16_(bx, by) = mad;
            tex16_(bx, by) = tex;
            ratio_(bx, by) = mad / (tex + Expr(1e-9));
        }

        // --- dense maps: flat serial RDom, row-major over the cell ---
        {
            RDom r(0, 32 * 32);
            Expr i = r.x;
            Expr ix = i % 32, iy = i / 32;
            Expr x = cx * 32 + ix, y = cy * 32 + iy;
            Expr in_b = x < W && y < H;
            Expr xc = clamp(x, 0, W - 1), yc = clamp(y, 0, H - 1);
            Expr va = cast<int>(prev_(xc, yc));
            Expr vb = cast<int>(curr_(xc, yc));
            Expr in_p = in_b && (x + 1 < cx * 32 + 32) && (x + 1 < W);
            Expr xa = clamp(x + 1, 0, W - 1);
            Func dmad("dmad"), dtex("dtex"), dn("dn"), dpair("dpair");
            dmad(cx, cy) = kZero;
            dmad(cx, cy) += select(in_b, cast<double>(abs(va - vb)) / kRng, kZero);
            // Same interleave rule as tsum: one doubled RDom, even
            // phase takes the prev-frame term, odd the curr term.
            RDom rt(0, 2 * 32 * 32);
            Expr kt = rt.x, it = kt / 2, ph = kt % 2;
            Expr ixt = it % 32, iyt = it / 32;
            Expr xt = cx * 32 + ixt, yt = cy * 32 + iyt;
            Expr in_bt = xt < W && yt < H;
            Expr xct = clamp(xt, 0, W - 1), yct = clamp(yt, 0, H - 1);
            Expr vat = cast<int>(prev_(xct, yct));
            Expr vbt = cast<int>(curr_(xct, yct));
            Expr in_pt = in_bt && (xt + 1 < cx * 32 + 32) && (xt + 1 < W);
            Expr xat = clamp(xt + 1, 0, W - 1);
            dtex(cx, cy) = kZero;
            dtex(cx, cy) += select(in_pt,
                                   select(ph == 0,
                                          cast<double>(abs(vat - cast<int>(prev_(xat, yct)))) / kRng,
                                          cast<double>(abs(vbt - cast<int>(curr_(xat, yct)))) / kRng),
                                   kZero);
            dn(cx, cy) = 0;
            dn(cx, cy) += select(in_b, 1, 0);
            dpair(cx, cy) = 0;
            dpair(cx, cy) += select(in_p, 1, 0);
            mad_dense_(cx, cy) = dmad(cx, cy) / cast<double>(max(dn(cx, cy), 1));
            tex_dense_(cx, cy) = dtex(cx, cy) / (kTwo * cast<double>(max(dpair(cx, cy), 1)));
        }

        // --- hot fraction over full 8-sample blocks ---
        {
            Expr bnx = gw / kHotBlock, bny = gh / kHotBlock;
            Var hx("hx"), hy("hy");
            RDom r(0, kHotBlock * kHotBlock);
            Expr i = r.x;
            Expr sx = i % kHotBlock, sy = i / kHotBlock;
            Expr x = hx * kHotBlock + sx;
            Expr y = hy * kHotBlock + sy;
            Expr va = Ag(x, y), vb = Bg(x, y);
            Func hmad("hmad"), hsharp("hsharp");
            hmad(hx, hy) = kZero;
            hmad(hx, hy) += cast<double>(abs(va - vb)) / kRng;
            // Same interleave rule as tsum (see above).
            RDom rt(0, 2 * kHotBlock * kHotBlock);
            Expr kt = rt.x, it = kt / 2, ph = kt % 2;
            Expr sxt = it % kHotBlock, syt = it / kHotBlock;
            Expr xt = hx * kHotBlock + sxt;
            Expr yt = hy * kHotBlock + syt;
            Expr vat = Ag(xt, yt), vbt = Bg(xt, yt);
            hsharp(hx, hy) = kZero;
            hsharp(hx, hy) += select(sxt + 1 < kHotBlock,
                                     select(ph == 0,
                                            cast<double>(abs(vat - Ag(xt + 1, yt))) / kRng,
                                            cast<double>(abs(vbt - Bg(xt + 1, yt))) / kRng),
                                     kZero);
            Func is_hot("is_hot");
            is_hot(hx, hy) = hmad(hx, hy) / Expr(64.0) >
                kHotRatio * (hsharp(hx, hy) / Expr(112.0));  // 2*8*7
            RDom rb(0, bnx, 0, bny);
            Func cnt("cnt");
            cnt() = 0;
            cnt() += select(is_hot(rb.x, rb.y), 1, 0);
            hot_(c) = cast<double>(cnt()) / cast<double>(max(bnx * bny, 1));
        }

        // Parallelize over independent output cells only; every inner
        // accumulation is a serial loop, so no schedule can reorder
        // f64 ops.
        const int vec = get_target().natural_vector_size<double>();
        ratio_.parallel(by, 8, TailStrategy::GuardWithIf)
            .vectorize(bx, vec, TailStrategy::GuardWithIf);
        mad16_.parallel(by, 8, TailStrategy::GuardWithIf)
            .vectorize(bx, vec, TailStrategy::GuardWithIf);
        tex16_.parallel(by, 8, TailStrategy::GuardWithIf)
            .vectorize(bx, vec, TailStrategy::GuardWithIf);
        mad_dense_.parallel(cy, 8, TailStrategy::GuardWithIf)
            .vectorize(cx, vec, TailStrategy::GuardWithIf);
        tex_dense_.parallel(cy, 8, TailStrategy::GuardWithIf)
            .vectorize(cx, vec, TailStrategy::GuardWithIf);
    }
};

HALIDE_REGISTER_GENERATOR(HdrplusMeter, hdrplus_meter)
