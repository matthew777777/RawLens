// SPDX-License-Identifier: GPL-3.0-or-later
// BGU viewfinder: bilateral-grid affine fit (fit_only). Given a low-res guide
// RGB pair (input) and developed RGB pair (output), solves per grid cell for
// the 3x4 matrix mapping input colors to output colors, with membrane
// smoothness via blurred Gram matrices (paper section 4, "Fast approximation").
//
// Adapted from google/bgu src/halide/fit_and_slice_affine_grid_halide.cpp
// (Apache 2.0, see NOTICE.md): f32 I/O instead of int16 (matches bgu_look),
// fit-only output (slicing runs on the GPU in step 4), BGU luma weights
// (0.25, 0.5, 0.25) hardcoded to match the slice shader's z-coordinate, and
// GuardWithIf tails for tiny VF grids.
//
// Grid geometry contract (shared with the slice shader and BguFit.kt):
//   gw = ceil(lowW / s), gh = ceil(lowH / s), gz = round(1 / r) + 1
//   cell (x, y, z) centers on low-res (x*s, y*s) and luma z*r.
//   Storage is the default Halide output layout (x stride 1, c outermost):
//   index = x + gw * (y + gh * (z + gz * ch)), ch = row*4 + col.
#include "Halide.h"

using namespace Halide;

namespace {

// A class to hold a matrix of Halide Exprs (from BGU fit_and_slice_3x4.cpp).
template <int rows, int cols>
struct Matrix {
    Expr exprs[rows][cols];

    Expr operator()(int i, int j) const { return exprs[i][j]; }
    Expr &operator()(int i, int j) { return exprs[i][j]; }
};

template <int R, int S, int T>
Matrix<R, T> mat_mul(const Matrix<R, S> &A, const Matrix<S, T> &B) {
    Matrix<R, T> result;
    for (int r = 0; r < R; r++) {
        for (int t = 0; t < T; t++) {
            result(r, t) = 0.0f;
            for (int s = 0; s < S; s++) {
                result(r, t) += A(r, s) * B(s, t);
            }
        }
    }
    return result;
}

// Solve Ax = b at each x, y, z via Gauss-Jordan on a staged Func.
// Compute the elimination at the given Func and Var.
template <int M, int N>
Matrix<M, N> solve(Matrix<M, M> A, Matrix<M, N> b, Func compute, Var at) {
    Var x("x"), y("y"), z("z");
    Var vi, vj;
    Func f;
    f(x, y, z, vi, vj) = undef<float>();
    for (int i = 0; i < M; i++) {
        for (int j = 0; j < M; j++) {
            f(x, y, z, i, j) = A(i, j);
        }
        for (int j = 0; j < N; j++) {
            f(x, y, z, i, j + M) = b(i, j);
        }
    }

    // Eliminate lower left.
    for (int k = 0; k < M - 1; k++) {
        for (int i = k + 1; i < M; i++) {
            f(x, y, z, -1, 0) = f(x, y, z, i, k) / f(x, y, z, k, k);
            for (int j = k + 1; j < M + N; j++) {
                f(x, y, z, i, j) -= f(x, y, z, k, j) * f(x, y, z, -1, 0);
            }
            f(x, y, z, i, k) = 0.0f;
        }
    }

    // Eliminate upper right.
    for (int k = M - 1; k > 0; k--) {
        for (int i = 0; i < k; i++) {
            f(x, y, z, -1, 0) = f(x, y, z, i, k) / f(x, y, z, k, k);
            for (int j = k + 1; j < M + N; j++) {
                f(x, y, z, i, j) -= f(x, y, z, k, j) * f(x, y, z, -1, 0);
            }
            f(x, y, z, i, k) = 0.0f;
        }
    }

    // Divide by diagonal and put it in the output matrix.
    for (int i = 0; i < M; i++) {
        f(x, y, z, i, i) = 1.0f / f(x, y, z, i, i);
        for (int j = 0; j < N; j++) {
            b(i, j) = f(x, y, z, i, j + M) * f(x, y, z, i, i);
        }
    }

    for (int i = 0; i < f.num_update_definitions(); i++) {
        f.update(i).vectorize(x);
    }
    f.compute_at(compute, at);
    return b;
}

template <int N, int M>
Matrix<M, N> transpose(const Matrix<N, M> &in) {
    Matrix<M, N> out;
    for (int i = 0; i < N; i++) {
        for (int j = 0; j < M; j++) {
            out(j, i) = in(i, j);
        }
    }
    return out;
}

Expr pack_channels(Var c, std::vector<Expr> exprs) {
    Expr e = exprs.back();
    for (int i = static_cast<int>(exprs.size()) - 2; i >= 0; i--) {
        e = select(c == i, exprs[i], e);
    }
    return e;
}

}  // namespace

class BguFit : public Halide::Generator<BguFit> {
public:
    // Low-res guide RGB (fit input) and developed RGB (fit output), f32.
    Input<Buffer<float>> guide_{"guide", 3};
    Input<Buffer<float>> developed_{"developed", 3};
    // Spatial bin size in low-res pixels (typically 16) and luma bin size
    // (typically 1/8). Regularization strength (paper default 1e-6).
    Input<int> s_sigma_{"s_sigma"};
    Input<float> r_sigma_{"r_sigma"};
    Input<float> lambda_{"lambda"};

    // Fitted grid: gw x gh x gz x 12 (3x4 row-major per cell).
    Output<Buffer<float>> grid_{"grid", 4};

    void generate() {
        Var x("x"), y("y"), z("z"), c("c");

        Func clampedGuide = BoundaryConditions::repeat_edge(guide_);
        Func clampedDev = BoundaryConditions::repeat_edge(developed_);

        // Luma bin coordinate. Weights MUST match the slice shader, which
        // hardcodes dot(color, (0.25, 0.5, 0.25)) for the z lookup.
        Func gray("gray");
        gray(x, y) = 0.25f * clampedGuide(x, y, 0) + 0.5f * clampedGuide(x, y, 1) +
                     0.25f * clampedGuide(x, y, 2);

        // Splat: accumulate Gram (ααᵀ, symmetric 4x4 -> 10) + rhs (βαᵀ -> 12)
        // into the cell covering each low-res pixel. Cell (x, y) covers
        // [x*s - s/2, x*s + s/2), hence the -s/2 origin shift.
        Func histogram("histogram");
        RDom r(0, s_sigma_, 0, s_sigma_);
        histogram(x, y, z, c) = 0.0f;
        {
            Expr sx = x * s_sigma_ + r.x - s_sigma_ / 2;
            Expr sy = y * s_sigma_ + r.y - s_sigma_ / 2;
            Expr pos = clamp(gray(sx, sy), 0.0f, 1.0f);
            Expr zi = cast<int>(round(pos * (1.0f / r_sigma_)));
            Expr vr = clampedDev(sx, sy, 0);
            Expr vg = clampedDev(sx, sy, 1);
            Expr vb = clampedDev(sx, sy, 2);
            Expr sr = clampedGuide(sx, sy, 0);
            Expr sg = clampedGuide(sx, sy, 1);
            Expr sb = clampedGuide(sx, sy, 2);
            histogram(x, y, zi, c) += pack_channels(
                c, {sr * sr, sr * sg, sr * sb, sr, sg * sg, sg * sb, sg, sb * sb, sb,
                    1.0f, vr * sr, vr * sg, vr * sb, vr, vg * sr, vg * sg, vg * sb, vg,
                    vb * sr, vb * sg, vb * sb, vb});
        }

        // Membrane smoothness: separable 7-tap 1/(d+1)^3 blur (paper sec 4).
        Expr t0 = 1.0f, t1 = 1.0f / 8, t2 = 1.0f / 27, t3 = 1.0f / 64;
        Func blurz("blurz"), blury("blury"), blurx("blurx");
        blurz(x, y, z, c) =
            t0 * histogram(x, y, z, c) +
            t1 * (histogram(x, y, z - 1, c) + histogram(x, y, z + 1, c)) +
            t2 * (histogram(x, y, z - 2, c) + histogram(x, y, z + 2, c)) +
            t3 * (histogram(x, y, z - 3, c) + histogram(x, y, z + 3, c));
        blury(x, y, z, c) =
            t0 * blurz(x, y, z, c) + t1 * (blurz(x, y - 1, z, c) + blurz(x, y + 1, z, c)) +
            t2 * (blurz(x, y - 2, z, c) + blurz(x, y + 2, z, c)) +
            t3 * (blurz(x, y - 3, z, c) + blurz(x, y + 3, z, c));
        blurx(x, y, z, c) =
            t0 * blury(x, y, z, c) + t1 * (blury(x - 1, y, z, c) + blury(x + 1, y, z, c)) +
            t2 * (blury(x - 2, y, z, c) + blury(x + 2, y, z, c)) +
            t3 * (blury(x - 3, y, z, c) + blury(x + 3, y, z, c));

        // Solve: blurred moments -> per-cell 3x4 affine matrix.
        Func matrix("matrix");
        {
            Matrix<4, 4> A;
            A(0, 0) = blurx(x, y, z, 0);
            A(0, 1) = blurx(x, y, z, 1);
            A(0, 2) = blurx(x, y, z, 2);
            A(0, 3) = blurx(x, y, z, 3);
            A(1, 0) = A(0, 1);
            A(1, 1) = blurx(x, y, z, 4);
            A(1, 2) = blurx(x, y, z, 5);
            A(1, 3) = blurx(x, y, z, 6);
            A(2, 0) = A(0, 2);
            A(2, 1) = A(1, 2);
            A(2, 2) = blurx(x, y, z, 7);
            A(2, 3) = blurx(x, y, z, 8);
            A(3, 0) = A(0, 3);
            A(3, 1) = A(1, 3);
            A(3, 2) = A(2, 3);
            A(3, 3) = blurx(x, y, z, 9);

            Matrix<4, 3> b;
            for (int j = 0; j < 3; ++j) {
                for (int i = 0; i < 4; ++i) {
                    b(i, j) = blurx(x, y, z, 10 + 4 * j + i);
                }
            }

            // Regularize toward the cell's mean output/input luma ratio
            // (paper eq. 2): under-constrained cells degrade to a gain map.
            const float epsilon = 1e-6f;
            Expr n = A(3, 3);
            Expr outLuma = b(3, 0) + 2.0f * b(3, 1) + b(3, 2) + epsilon * (n + 1.0f);
            Expr inLuma = A(3, 0) + 2.0f * A(3, 1) + A(3, 2) + epsilon * (n + 1.0f);
            Expr gain = outLuma / inLuma;
            Expr wLambda = lambda_ * (n + 1.0f);
            A(0, 0) += wLambda;
            A(1, 1) += wLambda;
            A(2, 2) += wLambda;
            A(3, 3) += wLambda;
            b(0, 0) += wLambda * gain;
            b(1, 1) += wLambda * gain;
            b(2, 2) += wLambda * gain;

            Matrix<3, 4> result = transpose(solve(A, b, matrix, x));
            matrix(x, y, z, c) = pack_channels(
                c, {result(0, 0), result(0, 1), result(0, 2), result(0, 3),
                    result(1, 0), result(1, 1), result(1, 2), result(1, 3),
                    result(2, 0), result(2, 1), result(2, 2), result(2, 3)});
        }

        grid_(x, y, z, c) = matrix(x, y, z, c);

        // Schedule (BGU fit schedule + small-extent-safe tails; plain
        // parallel(y) never splits, so no ShiftInwards extent requirement).
        const int vec = get_target().natural_vector_size<float>();
        histogram.compute_at(blurz, y);
        histogram.update().reorder(c, r.x, r.y, x, y).unroll(c);
        gray.compute_at(blurz, y).vectorize(x, vec, TailStrategy::GuardWithIf);
        blurz.compute_root()
            .reorder(c, z, x, y)
            .parallel(y)
            .vectorize(x, vec, TailStrategy::GuardWithIf);
        blury.compute_at(matrix, z).vectorize(x, vec, TailStrategy::GuardWithIf);
        blurx.compute_at(matrix, x).vectorize(x, vec, TailStrategy::GuardWithIf);
        matrix.compute_root()
            .reorder_storage(c, x, y, z)
            .reorder(c, x, z, y)
            .parallel(y)
            .vectorize(x, vec, TailStrategy::GuardWithIf)
            .bound(c, 0, 12)
            .unroll(c);
        grid_.compute_root().parallel(y);
    }
};

HALIDE_REGISTER_GENERATOR(BguFit, bgu_fit)
