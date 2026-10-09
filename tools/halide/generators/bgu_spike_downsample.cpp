// SPDX-License-Identifier: GPL-3.0-or-later
// BGU viewfinder spike: box-downsample a u16 RGB image by an integer factor.
//
// Adapted from google/bgu src/halide/box_downsample_generator.cpp (Apache 2.0,
// see NOTICE.md) to the modern Halide Generator API. This exact stage becomes
// the BGU low-resolution input later; for the spike it proves host Halide ->
// AOT static archive per Android ABI -> JNI -> on-device execution.
#include "Halide.h"

using namespace Halide;

class BguSpikeDownsample : public Halide::Generator<BguSpikeDownsample> {
public:
    Input<Buffer<uint16_t>> input_{"input", 3};
    Input<int> factor_{"factor"};
    Output<Buffer<uint16_t>> output_{"output", 3};

    void generate() {
        Var x("x"), y("y"), c("c");
        Func clamped = BoundaryConditions::repeat_edge(input_);
        RDom r(0, factor_, 0, factor_);
        Expr area = factor_ * factor_;
        Expr patch_sum = sum(cast<int32_t>(clamped(x * factor_ + r.x, y * factor_ + r.y, c)));
        output_(x, y, c) = cast<uint16_t>((patch_sum + area / 2) / area);

        // Manual CPU schedule (no autoscheduler estimates needed).
        // GuardWithIf tails on both loops: split/parallel/vectorize with the
        // default ShiftInwards tail *require* extent >= factor (the lowered
        // IR asserts output.extent >= 8), which tiny test buffers violate.
        const int vec = get_target().natural_vector_size<uint16_t>();
        output_.parallel(y, 8, TailStrategy::GuardWithIf)
            .vectorize(x, vec, TailStrategy::GuardWithIf);
    }
};

HALIDE_REGISTER_GENERATOR(BguSpikeDownsample, bgu_spike_downsample)
