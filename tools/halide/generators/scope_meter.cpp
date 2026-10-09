// SPDX-License-Identifier: GPL-3.0-or-later
// Fused scope+meter sampler: one native pass over a Bayer plane feeds the
// live histogram, waveform parade, ETTR/PROGRAM metering, and focus peaking.
//
// Replaces four Kotlin samplers that each re-read the same Bayer rows on the
// camera thread (~4 Hz): RawHistogramSampler, RawWaveformSampler,
// RawEttrSampler.sampleGrid (main region + full-frame guard), and
// RawFocusPeakingSampler (energy/mean grids only; threshold + despeckle +
// wash-guard stay in Kotlin: pure, tested, tiny). ETTR percentile levels,
// PROGRAM brightness/mean, and the AE solvers also stay Kotlin (scalar math
// on the bins this filter produces).
//
// Geometry contract (host owns every float->int decision; the filter does
// pure integer/pixel work):
//   raw: u16 Bayer plane, W x H, W >= 8, H >= 8, pixel stride 1 element
//     (packed 2-byte pixels; row stride arbitrary, in elements). Host falls
//     back to Kotlin for pixelStride != 2 or frames below 8x8 (matching the
//     Kotlin samplers' null cases).
//   lut: f32 AgX scope LUT, exactly 1024 entries (buildScopeLut output).
//     When use_lut == 0 the LUT selects away but must still be valid memory
//     (host passes a static dummy); vectorized selects evaluate both sides.
//   scope_step/ettr_step/guard_step: strided steps, each >= 1, computed by
//     the host with the existing Kotlin formulas (sqrt truncation).
//   ettr_l/t/r/b: metering region, left < right, top < bottom, inside frame
//     (host clamps with meteringScanRegion semantics).
//   do_guard: 1 runs the full-frame guard scan (cropped metering modes),
//     0 zero-fills guard_bins (caller must not read it).
//   cfa in 0..3 (else caller error; Kotlin nulls there). white/blacks are
//     raw ints; (white - black) <= 0 degrades to range 1 like Kotlin.
//   invd0..3/invf0..3: host-computed 1.0/(white-black) per phase, f64 +
//     f32, with the same coerceAtLeast(1) denominator. The filter
//     multiplies by these opaque params; it must never divide by the
//     range itself (see the reciprocal note on the declarations).
//   Focus grid: caller allocates 96 x rowsFor(W, H); cells center over the
//     allocated extent, so any extent gets correctly-centered cells.
//
// Outputs (bitwise gate: integer bins are order-free; every float sum is a
// flat SERIAL RDom in exact Kotlin visit order):
//   hist:  i32 64 x 4  (bin, channel R/G/B merged-greens/Lum row 3)
//   wave:  i32 48 x 96 x 3 (level, column, channel; flat index per channel
//     is col*48+level, matching RawWaveformSampler)
//   ettr_bins:   i32 256 x 4 (bin, canonical R/Gr/Gb/B channel)
//   ettr_counts: i32 4 x 2 (channel, row 0 = saturated, row 1 = totals)
//   ettr_green:  f64 4 {weightedGreenSum, weightedGreenWeight, spotGreenSum,
//     (double)spotGreenCount} (count < 2^53, exactly representable)
//   guard_bins:  i32 256 x 4 (guard-scan bins for the highlight guard)
//   focus_energy/focus_mean: f32 96 x R per-cell Tenengrad energy + green mean
//
// Fusion note (honest): one invocation, one input mapping, shared
// materialized per-pixel Funcs; per-consumer reductions are separate serial
// loops. A future schedule-only change may interleave them; outputs are
// unaffected, re-gate after.
//
// Bitwise method (same discipline as hdrplus_meter): unrolled Expr chains
// do NOT work for multi-term float sums (the simplifier re-nests left-deep
// sums right-deep, observed 1-ULP drift). Single-op float exprs (one add,
// one divide-by-2, one widening mult) are safe; every multi-term sum
// (luminance 3-term, green/spot sums, patch mean, Tenengrad) is a flat
// SERIAL RDom, one index, ascending, adding ONE term per iteration, in
// Kotlin visit order. Multi-term updates (ten += a+b) hand the simplifier
// a 3-operand tree it re-nests; worse, LLVM forms FMA for any bare
// fadd(fmul, .) even in pure Funcs (proven: FMA(gy,gy,gx^2) vs separate
// roundings). So every float add must provably see no multiply operand:
// precompute products in materialized Funcs (memory barriers LLVM cannot
// fuse through) and add loads. Reciprocals ride as opaque host params
// (d*(1/x) is rewritten to d/x). Only independent pure cells are
// parallelized/vectorized (GuardWithIf tails).
#include "Halide.h"

using namespace Halide;

namespace {

constexpr int kHistBins = 64;
constexpr int kWaveCols = 96;
constexpr int kWaveLevels = 48;
constexpr int kEttrBins = 256;
constexpr int kFocusCols = 96;
constexpr int kLutSize = 1024;

}  // namespace

class ScopeMeter : public Halide::Generator<ScopeMeter> {
public:
    Input<Buffer<uint16_t>> raw_{"raw", 2};
    Input<Buffer<float>> lut_{"lut", 1};
    Input<int> white_{"white"};
    Input<int> cfa_{"cfa"};
    Input<int> black0_{"black0"};
    Input<int> black1_{"black1"};
    Input<int> black2_{"black2"};
    Input<int> black3_{"black3"};
    // Host-computed reciprocals 1.0/(white-black) per phase (f64 + f32).
    // NEVER compute these in-filter as 1.0/x: Halide's simplifier rewrites
    // d*(1/x) into d/x (single rounding vs the double rounding Kotlin's
    // multiply-by-reciprocal performs — observed 1-2 ULP drift, caught by
    // this filter's gate). Opaque params cannot be rewritten.
    Input<double> invd0_{"invd0"};
    Input<double> invd1_{"invd1"};
    Input<double> invd2_{"invd2"};
    Input<double> invd3_{"invd3"};
    Input<float> invf0_{"invf0"};
    Input<float> invf1_{"invf1"};
    Input<float> invf2_{"invf2"};
    Input<float> invf3_{"invf3"};
    Input<int> use_lut_{"use_lut"};
    Input<int> scope_step_{"scope_step"};
    Input<int> ettr_step_{"ettr_step"};
    Input<int> ettr_l_{"ettr_l"};
    Input<int> ettr_t_{"ettr_t"};
    Input<int> ettr_r_{"ettr_r"};
    Input<int> ettr_b_{"ettr_b"};
    Input<int> guard_step_{"guard_step"};
    Input<int> do_guard_{"do_guard"};
    Output<Buffer<int32_t>> hist_{"hist", 2};
    Output<Buffer<int32_t>> wave_{"wave", 3};
    Output<Buffer<int32_t>> ettr_bins_{"ettr_bins", 2};
    Output<Buffer<int32_t>> ettr_counts_{"ettr_counts", 2};
    Output<Buffer<double>> ettr_green_{"ettr_green", 1};
    Output<Buffer<int32_t>> guard_bins_{"guard_bins", 2};
    Output<Buffer<float>> focus_energy_{"focus_energy", 2};
    Output<Buffer<float>> focus_mean_{"focus_mean", 2};

    void generate() {
        Expr W = raw_.dim(0).extent();
        Expr H = raw_.dim(1).extent();

        // Halide has no (Expr, double-literal) operator overloads, so
        // every float operand is wrapped explicitly (hdrplus_meter rule).
        const Expr kZeroD = Expr(0.0);
        const Expr kOneD = Expr(1.0);
        const Expr kZeroF = Expr(0.0f);
        const Expr kOneF = Expr(1.0f);

        // Clamped reads (hdrplus_meter Ag/Bg pattern): region/step params
        // are runtime values Halide cannot bound-prove, so every access is
        // clamped. Valid geometry never touches the clamp (all domains are
        // in-bounds by construction); the clamp only keeps the lowered IR
        // total.
        auto code = [&](Expr x, Expr y) {
            return cast<int>(raw_(clamp(x, 0, W - 1), clamp(y, 0, H - 1)));
        };
        auto phase = [&](Expr x, Expr y) { return ((y & 1) << 1) | (x & 1); };
        auto blackAt = [&](Expr p) {
            return select(p == 0, black0_, p == 1, black1_, p == 2, black2_,
                          black3_);
        };
        // Display color 0/1/2 (greens merged), transcribed from
        // RawHistogramSampler.colorAt / RawWaveformSampler.colorAt.
        auto colorOf = [&](Expr x, Expr y) {
            Expr ex = x & 1, ey = y & 1;
            return select(
                cfa_ == 0,
                select(ey == 0, select(ex == 0, 0, 1),
                       select(ex == 0, 1, 2)),
                cfa_ == 1,
                select(ey == 0, select(ex == 0, 1, 0),
                       select(ex == 0, 2, 1)),
                cfa_ == 2,
                select(ey == 0, select(ex == 0, 1, 2),
                       select(ex == 0, 0, 1)),
                select(ey == 0, select(ex == 0, 2, 1),
                       select(ex == 0, 1, 0)));
        };
        // Canonical channel R/Gr/Gb/B = 0/1/2/3, transcribed from
        // RawEttrSampler.channelAt (x/y parity only).
        auto channelOf = [&](Expr x, Expr y) {
            Expr ex = x & 1, ey = y & 1;
            return select(
                cfa_ == 0,
                select(ey == 0, select(ex == 0, 0, 1),
                       select(ex == 0, 2, 3)),
                cfa_ == 1,
                select(ey == 0, select(ex == 0, 1, 0),
                       select(ex == 0, 3, 2)),
                cfa_ == 2,
                select(ey == 0, select(ex == 0, 1, 3),
                       select(ex == 0, 0, 2)),
                select(ey == 0, select(ex == 0, 3, 1),
                       select(ex == 0, 2, 0)));
        };
        auto invDAt = [&](Expr p) {
            return select(p == 0, invd0_, p == 1, invd1_, p == 2, invd2_,
                          invd3_);
        };
        auto invFAt = [&](Expr p) {
            return select(p == 0, invf0_, p == 1, invf1_, p == 2, invf2_,
                          invf3_);
        };
        // f64 normalization exactly as the scope/focus samplers:
        // (nonneg Int) * host-reciprocal, clamped. Integer subtraction
        // first, then one widening multiply by the opaque param.
        auto normD = [&](Expr v, Expr p) {
            Expr b = blackAt(p);
            return clamp(cast<double>(max(v - b, 0)) * invDAt(p), kZeroD,
                         kOneD);
        };
        // f32 variant exactly as RawEttrSampler.sampleGrid.
        auto normF = [&](Expr v, Expr p) {
            Expr b = blackAt(p);
            return clamp(cast<float>(max(v - b, 0)) * invFAt(p), kZeroF,
                         kOneF);
        };
        // AgX LUT branch: scopeLutLookup widens Float->Double for hist/wave.
        auto curvedD = [&](Expr n) {
            Expr idx = clamp(cast<int>(n * Expr(1023.0)), 0, kLutSize - 1);
            return select(use_lut_ == 1, cast<double>(lut_(idx)), n);
        };

        // --- scope scan: block grid, 4 pixels per block, flat index q ---
        // Visit order matches Kotlin exactly: blockY outer, blockX inner,
        // dy outer, dx inner (q = ((byi*nbx + bxi)*4 + j)).
        Expr bw = W / 2, bh = H / 2;
        Expr sstep = scope_step_;
        Expr nbx = (bw + sstep - 1) / sstep;
        Expr nby = (bh + sstep - 1) / sstep;
        Expr nscope = nbx * nby * 4;
        Var q("q");
        Func sX("sX"), sY("sY");
        sX(q) = ((q / 4) % nbx) * sstep * 2 + (q % 2);
        sY(q) = ((q / 4) / nbx) * sstep * 2 + ((q % 4) / 2);
        // NOTE: dx = q%2 (j%2), dy = (q%4)/2 (j/2): j = q%4 in 0..3.
        Func sV("sV"), sN("sN"), sC("sC"), sBin("sBin"), sCol("sCol"),
            sLvl("sLvl"), sFCol("sFCol");
        sV(q) = code(sX(q), sY(q));
        sN(q) = normD(sV(q), phase(sX(q), sY(q)));
        sC(q) = curvedD(sN(q));
        sBin(q) = clamp(cast<int>(sC(q) * Expr(63.0)), 0, kHistBins - 1);
        sCol(q) = colorOf(sX(q), sY(q));
        sLvl(q) = clamp(cast<int>(sC(q) * Expr(47.0)), 0, kWaveLevels - 1);
        sFCol(q) = cast<int>((cast<int64_t>(sX(q)) * Expr((int64_t)kWaveCols)) /
                             cast<int64_t>(W));

        // --- per-block luminance bin (Rec.709 over the quad, f64) ---
        // g1+g2 in slot order via a 4-term serial RDom (select-adds of
        // non-negative terms; +0.0 is exact here), then one f64 add for
        // the /2 mean, then the 3-term 709 sum via a serial RDom in
        // Kotlin's left-assoc order. Bin truncation matches toInt().
        Var k("k");
        Func lumBin("lumBin");
        {
            RDom rg(0, 4);
            Expr j = rg.x;
            Expr chj = channelOf(j % 2, j / 2);
            Expr cj = sC(k * 4 + j);
            Func gsum("gsum");
            gsum(k) = kZeroD;
            gsum(k) += select(chj == 1 || chj == 2, cj, kZeroD);
            Expr c0 = sC(k * 4 + 0), c1 = sC(k * 4 + 1);
            Expr c2 = sC(k * 4 + 2), c3 = sC(k * 4 + 3);
            Expr ch0 = channelOf(0, 0), ch1 = channelOf(1, 0);
            Expr ch2 = channelOf(0, 1), ch3 = channelOf(1, 1);
            Expr r = select(ch0 == 0, c0,
                            select(ch1 == 0, c1, select(ch2 == 0, c2, c3)));
            Expr b = select(ch0 == 3, c0,
                            select(ch1 == 3, c1, select(ch2 == 3, c2, c3)));
            Expr g = gsum(k) / Expr(2.0);
            RDom rt(0, 3);
            Expr t = rt.x;
            Expr term = select(t == 0, Expr(0.2126) * r,
                               t == 1, Expr(0.7152) * g, Expr(0.0722) * b);
            Func lsum("lsum");
            lsum(k) = kZeroD;
            lsum(k) += term;
            lumBin(k) = clamp(cast<int>(lsum(k) * Expr(63.0)), 0, kHistBins - 1);
        }

        // --- scope reductions: serial scatter loops over q / k ---
        {
            Var b("b"), c("c");
            RDom rq(0, nscope);
            Expr qi = rq.x;
            Func h("h");
            h(b, c) = 0;
            h(sBin(qi), sCol(qi)) += 1;
            RDom rk(0, nbx * nby);
            h(lumBin(rk.x), 3) += 1;
            hist_(b, c) = h(b, c);

            Var l("l"), fc("fc"), wc("wc");
            RDom rw(0, nscope);
            Expr wi = rw.x;
            Func w("w");
            w(l, fc, wc) = 0;
            w(sLvl(wi), sFCol(wi), sCol(wi)) += 1;
            wave_(l, fc, wc) = w(l, fc, wc);
        }

        // --- ETTR main scan: pixel grid over the metering region ---
        // Flat row-major index p (y outer, x inner), exactly the Kotlin
        // while-loop order, so the f64 green sums accumulate identically.
        Expr rw = ettr_r_ - ettr_l_, rh = ettr_b_ - ettr_t_;
        Expr estep = ettr_step_;
        Expr enx = (rw + estep - 1) / estep;
        Expr eny = (rh + estep - 1) / estep;
        Expr nettr = enx * eny;
        auto zoneD = [&](Expr v, Expr s) {
            return abs((cast<double>(v) + Expr(0.5)) / cast<double>(s) *
                           Expr(2.0) -
                       kOneD);
        };
        Var p("p");
        Func eX("eX"), eY("eY");
        eX(p) = ettr_l_ + (p % enx) * estep;
        eY(p) = ettr_t_ + (p / enx) * estep;
        Func eV("eV"), eN("eN"), eBin("eBin"), eCh("eCh"), eZone("eZone"),
            eWt("eWt"), eGreen("eGreen"), eSpot("eSpot");
        eV(p) = code(eX(p), eY(p));
        eN(p) = normF(eV(p), phase(eX(p), eY(p)));
        eBin(p) = cast<int>(eN(p) * Expr(255.0f));
        eCh(p) = channelOf(eX(p), eY(p));
        eZone(p) = max(zoneD(clamp(eX(p), 0, W - 1), W), zoneD(eY(p), H));
        eWt(p) = select(eZone(p) <= Expr(0.20), Expr(500.0),
                        eZone(p) <= Expr(0.45), Expr(300.0),
                        eZone(p) <= Expr(0.70), Expr(200.0), Expr(50.0));
        eGreen(p) = eCh(p) == 1 || eCh(p) == 2;
        // SPOT_SCALE is a Float const: widen 0.158f, NOT the double 0.158.
        eSpot(p) = eZone(p) <= cast<double>(Expr(0.158f));
        {
            Var b("b"), c("c");
            RDom rp(0, nettr);
            Expr pi = rp.x;
            Func eb("eb");
            eb(b, c) = 0;
            eb(eBin(pi), eCh(pi)) += 1;
            ettr_bins_(b, c) = eb(b, c);

            Var cc("cc"), cr("cr");
            RDom rc(0, nettr);
            Expr ci = rc.x;
            Func ec("ec");
            ec(cc, cr) = 0;
            ec(eCh(ci), 0) += select(eV(ci) >= white_, 1, 0);
            RDom rt2(0, nettr);
            Expr ti = rt2.x;
            ec(eCh(ti), 1) += 1;
            ettr_counts_(cc, cr) = ec(cc, cr);

            // f64 green sums: one serial loop each, ascending visit order.
            RDom rs(0, nettr);
            Expr si = rs.x;
            Expr gn = eGreen(si);
            Func wsum("wsum"), www("www"), ssum("ssum");
            wsum() = kZeroD;
            wsum() += select(gn, eWt(si) * cast<double>(eN(si)), kZeroD);
            www() = kZeroD;
            www() += select(gn, eWt(si), kZeroD);
            ssum() = kZeroD;
            ssum() += select(gn && eSpot(si), cast<double>(eN(si)), kZeroD);
            Func scnt("scnt");
            scnt() = 0;
            scnt() += select(gn && eSpot(si), 1, 0);
            Var g("g");
            ettr_green_(g) = select(g == 0, wsum(), g == 1, www(), g == 2,
                                    ssum(), cast<double>(scnt()));
        }

        // --- guard scan: full-frame ultra-sparse bins (highlight guard) ---
        Var u("u");
        Func uX("uX"), uY("uY"), uBin("uBin"), uCh("uCh");
        {
            Expr gstep = guard_step_;
            Expr gnx = (W + gstep - 1) / gstep;
            Expr gny = (H + gstep - 1) / gstep;
            uX(u) = (u % gnx) * gstep;
            uY(u) = (u / gnx) * gstep;
            uBin(u) = cast<int>(normF(code(uX(u), uY(u)),
                                      phase(uX(u), uY(u))) *
                                 Expr(255.0f));
            uCh(u) = channelOf(uX(u), uY(u));
            Var b("b"), c("c");
            RDom ru(0, gnx * gny);
            Expr ui = ru.x;
            Func gb("gb");
            gb(b, c) = 0;
            gb(uBin(ui), uCh(ui)) += 1;
            guard_bins_(b, c) = select(do_guard_ == 1, gb(b, c), 0);
        }

        // --- focus grid: per-cell 4x4 green patch, Tenengrad + mean ---
        // Cells are independent (parallel over rows); within a cell the
        // 16-term patch mean is a serial RDom in Kotlin (ky outer, kx
        // inner) order and Tenengrad adds one combined (gx^2+gy^2) term
        // per serial iteration (a single add cannot re-nest).
        Var fc("fc"), fr("fr");
        {
            Expr rows = focus_energy_.dim(1).extent();
            Expr cxf = (cast<float>(fc) + Expr(0.5f)) * cast<float>(W) /
                       Expr(96.0f);
            Expr cyf = (cast<float>(fr) + Expr(0.5f)) * cast<float>(H) /
                       cast<float>(rows);
            Expr qx = clamp(cast<int>(cxf) / 2 * 2, 0, W - 8);
            Expr qy = clamp(cast<int>(cyf) / 2 * 2, 0, H - 8);
            Expr sameParity = cfa_ == 1 || cfa_ == 2;
            // Patch value Func over the flat 16-lattice (ky outer).
            Var s("s");
            Func pv("pv");
            {
                Expr ky = s / 4, kx = s % 4;
                Expr y = qy + ky * 2;
                Expr x0 = qx + ((y & 1) ^ select(sameParity, 0, 1));
                Expr x = x0 + kx * 2;
                pv(fc, fr, s) =
                    cast<float>(normD(code(x, y), phase(x, y)));
            }
            RDom rp(0, 16);
            Func pmean("pmean");
            pmean(fc, fr) = kZeroF;
            pmean(fc, fr) += pv(fc, fr, rp.x);
            // Combined per-iteration term (gx^2+gy^2) via materialized
            // squares: TWO hazards force the memory barriers. (1) Writing
            // ten += gx*gx+gy*gy inline gives the simplifier a 3-operand
            // tree it re-nests. (2) Even materialized as one Func, LLVM
            // forms FMA(gy,gy,gx^2) for the bare fadd(fmul,fmul) (observed
            // 1-ULP drift, mechanism proven against exact rationals).
            // Squares from loads + combined term from loads + single-term
            // update: every float add provably sees no multiply operand.
            Var ti("ti"), tk("tk");
            Func sq("sq"), tterm("tterm");
            {
                Expr ty = ti / 2 + 1, tx = ti % 2 + 1;
                Expr idx = ty * 4 + tx;
                Expr gx = pv(fc, fr, idx + 1) - pv(fc, fr, idx - 1);
                Expr gy = pv(fc, fr, idx + 4) - pv(fc, fr, idx - 4);
                sq(fc, fr, ti, tk) = select(tk == 0, gx * gx, gy * gy);
                tterm(fc, fr, ti) = sq(fc, fr, ti, 0) + sq(fc, fr, ti, 1);
            }
            sq.compute_root();
            tterm.compute_root();
            RDom rtg(0, 4);
            Func ten("ten");
            ten(fc, fr) = kZeroF;
            ten(fc, fr) += tterm(fc, fr, rtg.x);
            focus_energy_(fc, fr) = ten(fc, fr) / Expr(4.0f);
            focus_mean_(fc, fr) = pmean(fc, fr) / Expr(16.0f);
        }

        // Schedule: pure per-pixel Funcs materialize once (compute_root)
        // and parallelize/vectorize over their flat index; every inner
        // accumulation is a serial loop, so no schedule can reorder f64
        // ops. Focus parallelizes over independent cells only.
        const int veci = get_target().natural_vector_size<int>();
        const int vecf = get_target().natural_vector_size<float>();
        const int vecd = get_target().natural_vector_size<double>();
        sX.compute_root().parallel(q, 8, TailStrategy::GuardWithIf);
        sY.compute_root().parallel(q, 8, TailStrategy::GuardWithIf);
        sV.compute_root()
            .parallel(q, 8, TailStrategy::GuardWithIf)
            .vectorize(q, veci, TailStrategy::GuardWithIf);
        sN.compute_root()
            .parallel(q, 8, TailStrategy::GuardWithIf)
            .vectorize(q, vecd, TailStrategy::GuardWithIf);
        sC.compute_root()
            .parallel(q, 8, TailStrategy::GuardWithIf)
            .vectorize(q, vecd, TailStrategy::GuardWithIf);
        sBin.compute_root()
            .parallel(q, 8, TailStrategy::GuardWithIf)
            .vectorize(q, veci, TailStrategy::GuardWithIf);
        sCol.compute_root().parallel(q, 8, TailStrategy::GuardWithIf);
        sLvl.compute_root().parallel(q, 8, TailStrategy::GuardWithIf);
        sFCol.compute_root().parallel(q, 8, TailStrategy::GuardWithIf);
        lumBin.compute_root().parallel(k, 8, TailStrategy::GuardWithIf);
        eX.compute_root().parallel(p, 8, TailStrategy::GuardWithIf);
        eY.compute_root().parallel(p, 8, TailStrategy::GuardWithIf);
        eV.compute_root()
            .parallel(p, 8, TailStrategy::GuardWithIf)
            .vectorize(p, veci, TailStrategy::GuardWithIf);
        eN.compute_root()
            .parallel(p, 8, TailStrategy::GuardWithIf)
            .vectorize(p, vecf, TailStrategy::GuardWithIf);
        eBin.compute_root()
            .parallel(p, 8, TailStrategy::GuardWithIf)
            .vectorize(p, veci, TailStrategy::GuardWithIf);
        eCh.compute_root().parallel(p, 8, TailStrategy::GuardWithIf);
        eZone.compute_root()
            .parallel(p, 8, TailStrategy::GuardWithIf)
            .vectorize(p, vecd, TailStrategy::GuardWithIf);
        eWt.compute_root()
            .parallel(p, 8, TailStrategy::GuardWithIf)
            .vectorize(p, vecd, TailStrategy::GuardWithIf);
        eGreen.compute_root().parallel(p, 8, TailStrategy::GuardWithIf);
        eSpot.compute_root().parallel(p, 8, TailStrategy::GuardWithIf);
        uX.compute_root().parallel(u, 8, TailStrategy::GuardWithIf);
        uY.compute_root().parallel(u, 8, TailStrategy::GuardWithIf);
        uBin.compute_root()
            .parallel(u, 8, TailStrategy::GuardWithIf)
            .vectorize(u, veci, TailStrategy::GuardWithIf);
        uCh.compute_root().parallel(u, 8, TailStrategy::GuardWithIf);
        focus_energy_.parallel(fr, 8, TailStrategy::GuardWithIf)
            .vectorize(fc, vecf, TailStrategy::GuardWithIf);
        focus_mean_.parallel(fr, 8, TailStrategy::GuardWithIf)
            .vectorize(fc, vecf, TailStrategy::GuardWithIf);
    }
};

HALIDE_REGISTER_GENERATOR(ScopeMeter, scope_meter)
