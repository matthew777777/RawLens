// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Host orchestration for the Vulkan RT "Color propagation" highlight
// reconstruction (shaders/hl/hlrecon.glsl, spirv/hlrecon.spv).
// [buildPlan] emits the exact ordered dispatch list,
// [runPlanCpu] interprets it on JVM FloatArray planes (the durable parity
// twin: same staging, same buffer flow, windowed-average blurs exactly like
// the shader's BLUR modes), and [runVulkan] executes the identical order
// through the shared hub ([VkSession]), falling back to CPU on any failure.
//
// STAGE CONTRACT (matches RtHlEndToEndTest order line by line):
// CPU/host-resident: thresholds, ROI, variation, accumulation, gates,
// downsample, directional scans (sequential raster, unparallelizable), the
// rescale-nearest staging upload, and the gamma-table upload.
// GPU (the plan): GUIDE on the staged half-res originals, RECONSTRUCT on
// the full-res ROI, RESOLVE to half-res, four guided-filter passes
// (mask + RGB, guide statistics computed once per radius), MERGE back.
// Like RT, ROI dims must be even (RT pads to bufferW; odd widths are
// rejected rather than reconstructed differently).

package com.matthew.rawlens

import android.opengl.GLES30
import android.os.SystemClock
import android.util.Log
import kotlin.math.min

object HlVulkanRecon {
    const val SHADER = "hl/hlrecon.glsl"

    // ESSL binding slots (must match the binding table in hlrecon.glsl).
    const val B_PLANE_A = 0
    const val B_PLANE_B = 1
    const val B_PLANE_C = 2
    const val B_PLANE_OUT = 3
    const val B_RGB_IN = 4
    const val B_DIR0 = 5
    const val B_DIR_A = 6
    const val B_DIR_B = 7
    const val B_DIR4 = 8
    const val B_RGB_BUF = 9
    const val B_MASK_BUF = 10
    const val B_HALF_R = 11
    const val B_HALF_G = 12
    const val B_HALF_B = 13
    const val B_MASK_HALF = 14
    const val B_GUIDE = 15
    const val B_MERGED = 16

    // Shader modes (u_mode).
    const val M_BLUR_H = 0
    const val M_BLUR_V = 1
    const val M_RECONSTRUCT = 2
    const val M_RESOLVE = 3
    const val M_COMBINE = 4
    const val M_GUIDE = 5
    const val M_MERGE = 6

    // COMBINE ops (u_combineOp).
    const val OP_MUL = 0
    const val OP_SUBMUL = 1
    const val OP_DIVEPSILON = 2
    const val OP_SUBMUL2 = 3
    const val OP_ADDMUL = 4

    /** RT highlight guided pass channel epsilon: 0.01 * 65535 in double, narrowed to float. */
    val RGB_EPS = (0.01 * 65535.0).toFloat()

    /** RT EPSILON (hilite_recon.cc): the u_eps uniform and the twin's floor. */
    const val EPSILON = 0.00001f

    /**
     * One GPU dispatch. Plane names address the runner's image table
     * ([runPlanCpu]) or the session's VkImage table ([runVulkan]); unused
     * roles stay empty (host binds dummies, like the shader header says).
     */
    data class Op(
        val mode: Int,
        val w: Int,
        val h: Int,
        val a: String = "",
        val b: String = "",
        val c: String = "",
        val out: String = "",
        val radius: Int = 0,
        val combineOp: Int = 0,
        val eps: Float = 0f
    )

    // CPU-twin plane names (single channel each; triplets are 3 entries).
    const val P_IN_R = "inR"
    const val P_IN_G = "inG"
    const val P_IN_B = "inB"
    const val P_D0 = "d0_"
    const val P_DA = "dA_"
    const val P_DB = "dB_"
    const val P_D4 = "d4_"
    const val P_BUF_R = "bR"
    const val P_BUF_G = "bG"
    const val P_BUF_B = "bB"
    const val P_MASK_F = "mF"
    const val P_H_R = "hR"
    const val P_H_G = "hG"
    const val P_H_B = "hB"
    const val P_H_MASK = "hM"
    const val P_GUIDE = "g"
    const val P_S0 = "s0"
    const val P_MEAN_I = "mI"
    const val P_CORR_I = "cI"
    const val P_VAR_I = "vI"
    const val P_MEAN_P = "mP"
    const val P_CORR_IP = "cP"
    const val P_COV = "tC"
    const val P_A = "A"
    const val P_B = "B"
    const val P_OUT_R = "oR"
    const val P_OUT_G = "oG"
    const val P_OUT_B = "oB"

    /** Per-run scalars: thresholds plus the guided-pass parameters. */
    data class RunParams(
        val thresh: RtHlThresholds,
        val xyzRow: FloatArray,
        val gainThresh: Float,
        val radius1: Int,
        val radius2: Int
    )

    /**
     * RT boxMean radius clamp (boxMeanInPlace): radii arrive truncated
     * (r/sub).toInt() and clamp into [0, (min(w,h)-1)/2-1].
     */
    fun clampBoxMean(w: Int, h: Int, r: Int): Int {
        val lim = (min(w, h) - 1) / 2 - 1
        return r.coerceIn(0, lim)
    }

    /**
     * Plan builder with buffer aliasing: a radius-0 box mean is the exact
     * identity (single tap over 1.0, bit-exact per IEEE), so its H+V copy
     * dispatches are skipped and readers use the source buffer directly.
     * Every emitted role is canonicalized at construction, so the twin,
     * runVulkan, and the measurement bundle all see the same names.
     */
    private class PlanBuilder {
        val plan = mutableListOf<Op>()
        private val alias = mutableMapOf<String, String>()

        fun canon(name: String): String {
            var c = name
            while (true) {
                val next = alias[c] ?: return c
                c = next
            }
        }

        fun blurInto(w: Int, h: Int, src: String, dst: String, radius: Int) {
            val cs = canon(src)
            if (radius == 0) {
                alias[dst] = cs
                return
            }
            val cd = canon(dst)
            plan += Op(M_BLUR_H, w, h, a = cs, out = P_S0, radius = radius)
            plan += Op(M_BLUR_V, w, h, a = P_S0, out = cd, radius = radius)
        }

        fun blurInPlace(w: Int, h: Int, buf: String, radius: Int) {
            if (radius == 0) return
            val cb = canon(buf)
            plan += Op(M_BLUR_H, w, h, a = cb, out = P_S0, radius = radius)
            plan += Op(M_BLUR_V, w, h, a = P_S0, out = cb, radius = radius)
        }

        fun combine(
            w: Int, h: Int, a: String, b: String, c: String, out: String, op: Int, eps: Float = 0f
        ) {
            plan += Op(
                M_COMBINE, w, h, a = canon(a), b = canon(b), c = canon(c),
                out = canon(out), combineOp = op, eps = eps
            )
        }

        fun statsInto(w: Int, h: Int, radius: Int) {
            blurInto(w, h, P_GUIDE, P_MEAN_I, radius)
            combine(w, h, P_GUIDE, P_GUIDE, "", P_CORR_I, OP_MUL)
            blurInPlace(w, h, P_CORR_I, radius)
            combine(w, h, P_MEAN_I, P_MEAN_I, P_CORR_I, P_VAR_I, OP_SUBMUL)
        }

        fun passInto(w: Int, h: Int, radius: Int, src: String, dst: String, eps: Float) {
            blurInto(w, h, src, P_MEAN_P, radius)
            combine(w, h, P_GUIDE, src, "", P_CORR_IP, OP_MUL)
            blurInPlace(w, h, P_CORR_IP, radius)
            combine(w, h, P_MEAN_I, P_MEAN_P, P_CORR_IP, P_COV, OP_SUBMUL)
            combine(w, h, P_COV, P_VAR_I, "", P_A, OP_DIVEPSILON, eps)
            combine(w, h, P_A, P_MEAN_I, P_MEAN_P, P_B, OP_SUBMUL)
            blurInPlace(w, h, P_A, radius)
            blurInPlace(w, h, P_B, radius)
            combine(w, h, P_A, P_GUIDE, P_B, dst, OP_ADDMUL)
        }
    }

    /**
     * Ordered GPU dispatch list for one ROI: GUIDE, RECONSTRUCT, RESOLVE,
     * guide statistics per radius, mask pass, guide statistics, three
     * channel passes, MERGE. Guide statistics (meanI/corrI/varI) depend
     * only on the guide image and the radius, so they run once per radius
     * instead of once per pass — identical values, fewer dispatches.
     * Radius-0 means are exact identities and emit no dispatches (hlbl=1
     * shape: 40 ops instead of 68).
     */
    fun buildPlan(w: Int, h: Int, w2: Int, h2: Int, params: RunParams): List<Op> {
        require(w % 2 == 0 && h % 2 == 0) { "ROI dims must be even (RT bufferW padding)" }
        require(w2 == w / 2 && h2 == h / 2)
        val r1 = clampBoxMean(w2, h2, params.radius1)
        val r2 = clampBoxMean(w2, h2, params.radius2)
        val b = PlanBuilder()
        b.plan += Op(M_GUIDE, w2, h2)
        b.plan += Op(M_RECONSTRUCT, w, h)
        b.plan += Op(M_RESOLVE, w2, h2)
        b.statsInto(w2, h2, r1)
        b.passInto(w2, h2, r1, P_H_MASK, P_H_MASK, params.gainThresh)
        b.statsInto(w2, h2, r2)
        b.passInto(w2, h2, r2, P_H_R, P_H_R, RGB_EPS)
        b.passInto(w2, h2, r2, P_H_G, P_H_G, RGB_EPS)
        b.passInto(w2, h2, r2, P_H_B, P_H_B, RGB_EPS)
        b.plan += Op(M_MERGE, w, h)
        return b.plan
    }

    /** Interpreter context: everything an [Op] reads besides its planes. */
    class CpuContext(
        val w: Int,
        val h: Int,
        val hfw: Int,
        val hfh: Int,
        val params: RunParams,
        val maps: HlMaps,
        val gamma: FloatArray,
        val planes: MutableMap<String, Array<FloatArray>>
    ) {
        fun p(name: String): Array<FloatArray> = planes.getValue(name)
    }

    /**
     * JVM twin of the shader, op by op. BLUR uses the same windowed-average
     * summation order as blurH/blurV; every other mode mirrors the shader's
     * scalar lines, sharing only leaf math (blendEstimate, hueWeight,
     * lutSample, rgbLuminance, bilinearSample) with the reference chain.
     */
    fun executePlan(plan: List<Op>, ctx: CpuContext) {
        for (op in plan) {
            when (op.mode) {
                M_BLUR_H -> blurCpu(ctx.p(op.a), ctx.p(op.out), op.w, op.h, op.radius, horizontal = true)
                M_BLUR_V -> blurCpu(ctx.p(op.a), ctx.p(op.out), op.w, op.h, op.radius, horizontal = false)
                M_RECONSTRUCT -> reconstructCpuOp(ctx)
                M_RESOLVE -> resolveCpuOp(ctx)
                M_COMBINE -> combineCpuOp(ctx, op)
                M_GUIDE -> guideCpuOp(ctx)
                M_MERGE -> mergeCpuOp(ctx)
                else -> throw IllegalArgumentException("unknown mode ${op.mode}")
            }
        }
    }

    /** Windowed average with the shader's exact accumulation order. */
    fun blurCpu(
        src: Array<FloatArray>, dst: Array<FloatArray>, w: Int, h: Int, r: Int, horizontal: Boolean
    ) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                if (horizontal) {
                    val lo = maxOf(x - r, 0)
                    val hi = minOf(x + r, w - 1)
                    for (k in lo..hi) s += src[y][k]
                    dst[y][x] = s / (hi - lo + 1).toFloat()
                } else {
                    val lo = maxOf(y - r, 0)
                    val hi = minOf(y + r, h - 1)
                    for (k in lo..hi) s += src[k][x]
                    dst[y][x] = s / (hi - lo + 1).toFloat()
                }
            }
        }
    }

    private fun reconstructCpuOp(ctx: CpuContext) {
        val t = ctx.params.thresh
        val eps = EPSILON
        val inR = ctx.p(P_IN_R)
        val inG = ctx.p(P_IN_G)
        val inB = ctx.p(P_IN_B)
        val bR = ctx.p(P_BUF_R)
        val bG = ctx.p(P_BUF_G)
        val bB = ctx.p(P_BUF_B)
        val mF = ctx.p(P_MASK_F)
        for (y in 0 until ctx.h) {
            for (x in 0 until ctx.w) {
                val pixel = floatArrayOf(inR[y][x], inG[y][x], inB[y][x])
                bR[y][x] = pixel[0]
                bG[y][x] = pixel[1]
                bB[y][x] = pixel[2]
                mF[y][x] = 0f
                if (pixel[0] < t.maxF[0] && pixel[1] < t.maxF[1] && pixel[2] < t.maxF[2]) continue
                val i1 = min((y - y % 4) / 4, ctx.hfh - 1)
                val j1 = min((x - x % 4) / 4, ctx.hfw - 1)
                val blend = RtColorPropagation.blendEstimate(pixel, t)
                var yn = eps + blend[0] + blend[1] + blend[2]
                val norm = floatArrayOf(blend[0] / yn, blend[1] / yn, blend[2] / yn)
                val (hasWeight, clipfix) = RtHighlightMap.hueWeight(norm, ctx.maps, i1, j1)
                if (!hasWeight) continue
                var maskval = 1f
                if (pixel[0] > t.maxF[0] && pixel[1] > t.maxF[1] && pixel[2] > t.maxF[2]) {
                    val mult = t.whitePt / (0.299f * clipfix[0] + 0.587f * clipfix[1] + 0.114f * clipfix[2])
                    pixel[0] = clipfix[0] * mult
                    pixel[1] = clipfix[1] * mult
                    pixel[2] = clipfix[2] * mult
                } else {
                    val nc0 = if (pixel[0] <= t.maxF[0]) 1f else 0f
                    val nc1 = if (pixel[1] <= t.maxF[1]) 1f else 0f
                    val nc2 = if (pixel[2] <= t.maxF[2]) 1f else 0f
                    if (nc0 == 0f) {
                        pixel[0] = maxOf(
                            pixel[0],
                            clipfix[0] * ((nc1 * pixel[1] + nc2 * pixel[2]) /
                                (nc1 * clipfix[1] + nc2 * clipfix[2] + eps)))
                    }
                    if (nc1 == 0f) {
                        pixel[1] = maxOf(
                            pixel[1],
                            clipfix[1] * ((nc2 * pixel[2] + nc0 * pixel[0]) /
                                (nc2 * clipfix[2] + nc0 * clipfix[0] + eps))
                        )
                    }
                    if (nc2 == 0f) {
                        pixel[2] = maxOf(
                            pixel[2],
                            clipfix[2] * ((nc0 * pixel[0] + nc1 * pixel[1]) /
                                (nc0 * clipfix[0] + nc1 * clipfix[1] + eps))
                        )
                    }
                    maskval = 1f - (nc0 + nc1 + nc2) / 5f
                }
                yn = 0.299f * pixel[0] + 0.587f * pixel[1] + 0.114f * pixel[2]
                if (yn > t.whitePt) {
                    val mult = t.whitePt / yn
                    pixel[0] *= mult
                    pixel[1] *= mult
                    pixel[2] *= mult
                }
                bR[y][x] = pixel[0]
                bG[y][x] = pixel[1]
                bB[y][x] = pixel[2]
                mF[y][x] = maskval
            }
        }
    }

    private fun resolveCpuOp(ctx: CpuContext) {
        val bR = ctx.p(P_BUF_R)
        val bG = ctx.p(P_BUF_G)
        val bB = ctx.p(P_BUF_B)
        val mF = ctx.p(P_MASK_F)
        val hR = ctx.p(P_H_R)
        val hG = ctx.p(P_H_G)
        val hB = ctx.p(P_H_B)
        val hM = ctx.p(P_H_MASK)
        for (y in hR.indices) {
            for (x in hR[0].indices) {
                val by = 2 * y
                val bx = 2 * x
                var r = bR[by][bx]
                var g = bG[by][bx]
                var b = bB[by][bx]
                var m = mF[by][bx]
                for (i in 0..1) {
                    for (j in 0..1) {
                        if (mF[by + i][bx + j] > 0f) {
                            r = bR[by + i][bx + j]
                            g = bG[by + i][bx + j]
                            b = bB[by + i][bx + j]
                            m = mF[by + i][bx + j]
                        }
                    }
                }
                hR[y][x] = r
                hG[y][x] = g
                hB[y][x] = b
                hM[y][x] = m
            }
        }
    }

    private fun combineCpuOp(ctx: CpuContext, op: Op) {
        // Roles an op does not use stay empty (host binds dummies there).
        val a = if (op.a.isEmpty()) null else ctx.p(op.a)
        val b = if (op.b.isEmpty()) null else ctx.p(op.b)
        val c = if (op.c.isEmpty()) null else ctx.p(op.c)
        val out = ctx.p(op.out)
        for (y in 0 until op.h) {
            for (x in 0 until op.w) {
                out[y][x] = when (op.combineOp) {
                    OP_MUL -> a!![y][x] * b!![y][x]
                    OP_SUBMUL, OP_SUBMUL2 -> c!![y][x] - a!![y][x] * b!![y][x]
                    OP_DIVEPSILON -> a!![y][x] / (b!![y][x] + op.eps)
                    OP_ADDMUL -> c!![y][x] + a!![y][x] * b!![y][x]
                    else -> throw IllegalArgumentException("unknown combine op ${op.combineOp}")
                }
            }
        }
    }

    private fun guideCpuOp(ctx: CpuContext) {
        val hR = ctx.p(P_H_R)
        val hG = ctx.p(P_H_G)
        val hB = ctx.p(P_H_B)
        val g = ctx.p(P_GUIDE)
        val w = ctx.params.xyzRow
        for (y in g.indices) {
            for (x in g[0].indices) {
                g[y][x] = RtGuidedFilter.lutSample(
                    ctx.gamma,
                    RtGuidedFilter.rgbLuminance(hR[y][x], hG[y][x], hB[y][x], w)
                )
            }
        }
    }

    private fun mergeCpuOp(ctx: CpuContext) {
        val bR = ctx.p(P_BUF_R)
        val bG = ctx.p(P_BUF_G)
        val bB = ctx.p(P_BUF_B)
        val hR = ctx.p(P_H_R)
        val hG = ctx.p(P_H_G)
        val hB = ctx.p(P_H_B)
        val hM = ctx.p(P_H_MASK)
        val oR = ctx.p(P_OUT_R)
        val oG = ctx.p(P_OUT_G)
        val oB = ctx.p(P_OUT_B)
        for (y in 0 until ctx.h) {
            val yy = y / 2
            val fy = y * 0.5f
            for (x in 0 until ctx.w) {
                val xx = x / 2
                val m = hM[yy][xx]
                val origR = bR[y][x]
                val origG = bG[y][x]
                val origB = bB[y][x]
                if (m <= 0f) {
                    oR[y][x] = origR
                    oG[y][x] = origG
                    oB[y][x] = origB
                } else {
                    val fx = x * 0.5f
                    oR[y][x] = m * (RtGuidedFilter.bilinearSample(hR, fx, fy) - origR) + origR
                    oG[y][x] = m * (RtGuidedFilter.bilinearSample(hG, fx, fy) - origG) + origG
                    oB[y][x] = m * (RtGuidedFilter.bilinearSample(hB, fx, fy) - origB) + origB
                }
            }
        }
    }

    /** Full-chain CPU result: merged full frames plus the filtered mask and the gamma guide. */
    data class CpuResult(val mask: Array<FloatArray>, val guide: Array<FloatArray>)

    /**
     * Full highlight chain through the GPU plan, executed by the JVM twin.
     * Mutates [red]/[green]/[blue] in place like [RtHighlightMap.mergeBack].
     * CPU-resident stages (thresholds arrive ready; ROI, variation,
     * accumulation, gates, downsample, scans arrive as [maps]) stay shared
     * code; everything the GPU will do runs through [buildPlan].
     */
    fun reconstructCpu(
        red: Array<FloatArray>,
        green: Array<FloatArray>,
        blue: Array<FloatArray>,
        t: RtHlThresholds,
        maps: HlMaps,
        roi: HlRoi,
        xyzRow: FloatArray,
        gamma: FloatArray,
        guided: RtGuidedFilter.GuidedParams
    ): CpuResult {
        val w = roi.width
        val h = roi.height
        require(w % 2 == 0 && h % 2 == 0) { "ROI dims must be even (RT bufferW padding)" }
        val w2 = w / 2
        val h2 = h / 2
        val params = RunParams(t, xyzRow, guided.gainThresh, guided.radius1, guided.radius2)
        val planes = mutableMapOf<String, Array<FloatArray>>()
        fun full(): Array<FloatArray> = Array(h) { FloatArray(w) }
        fun half(): Array<FloatArray> = Array(h2) { FloatArray(w2) }
        planes[P_IN_R] = Array(h) { y -> FloatArray(w) { x -> red[y + roi.minY][x + roi.minX] } }
        planes[P_IN_G] = Array(h) { y -> FloatArray(w) { x -> green[y + roi.minY][x + roi.minX] } }
        planes[P_IN_B] = Array(h) { y -> FloatArray(w) { x -> blue[y + roi.minY][x + roi.minX] } }
        for (c in 0..3) {
            planes[P_D0 + c] = Array(maps.hfh) { y -> FloatArray(maps.hfw) { x -> maps.dir0[c][x][y] } }
            planes[P_DA + c] = Array(maps.hfh) { y -> FloatArray(maps.hfw) { x -> maps.dir[c][y][x] } }
            planes[P_DB + c] = Array(maps.hfh) { y -> FloatArray(maps.hfw) { x -> maps.dir[4 + c][y][x] } }
            planes[P_D4 + c] = Array(maps.hfh) { y -> FloatArray(maps.hfw) { x -> maps.dir4[c][x][y] } }
        }
        planes[P_BUF_R] = full()
        planes[P_BUF_G] = full()
        planes[P_BUF_B] = full()
        planes[P_MASK_F] = full()
        val hR = half()
        val hG = half()
        val hB = half()
        RtGuidedFilter.rescaleNearestInto(red, roi.minX, roi.minY, w, h, hR)
        RtGuidedFilter.rescaleNearestInto(green, roi.minX, roi.minY, w, h, hG)
        RtGuidedFilter.rescaleNearestInto(blue, roi.minX, roi.minY, w, h, hB)
        planes[P_H_R] = hR
        planes[P_H_G] = hG
        planes[P_H_B] = hB
        planes[P_H_MASK] = half()
        planes[P_GUIDE] = half()
        for (s in listOf(P_S0, P_MEAN_I, P_CORR_I, P_VAR_I, P_MEAN_P, P_CORR_IP, P_COV, P_A, P_B)) {
            planes[s] = half()
        }
        planes[P_OUT_R] = full()
        planes[P_OUT_G] = full()
        planes[P_OUT_B] = full()
        val ctx = CpuContext(w, h, maps.hfw, maps.hfh, params, maps, gamma, planes)
        executePlan(buildPlan(w, h, w2, h2, params), ctx)
        val oR = planes.getValue(P_OUT_R)
        val oG = planes.getValue(P_OUT_G)
        val oB = planes.getValue(P_OUT_B)
        for (y in 0 until h) {
            for (x in 0 until w) {
                red[y + roi.minY][x + roi.minX] = oR[y][x]
                green[y + roi.minY][x + roi.minX] = oG[y][x]
                blue[y + roi.minY][x + roi.minX] = oB[y][x]
            }
        }
        return CpuResult(planes.getValue(P_H_MASK), planes.getValue(P_GUIDE))
    }

    /**
     * Twin plane name -> bundle image key. Single source of truth shared by
     * [runVulkan] and the measurement bundle: both address the same images.
     */
    fun imageKeyForPlane(plane: String): String = when (plane) {
        P_IN_R, P_IN_G, P_IN_B -> "rgbIn"
        P_D0 + "0", P_D0 + "1", P_D0 + "2", P_D0 + "3" -> "dirs0"
        P_DA + "0", P_DA + "1", P_DA + "2", P_DA + "3" -> "dirsA"
        P_DB + "0", P_DB + "1", P_DB + "2", P_DB + "3" -> "dirsB"
        P_D4 + "0", P_D4 + "1", P_D4 + "2", P_D4 + "3" -> "dirs4"
        P_BUF_R, P_BUF_G, P_BUF_B -> "rgbBuf"
        P_MASK_F -> "maskBuf"
        P_H_R -> "hR"
        P_H_G -> "hG"
        P_H_B -> "hB"
        P_H_MASK -> "hM"
        P_GUIDE -> "g"
        P_S0 -> "s0"
        P_MEAN_I -> "mI"
        P_CORR_I -> "cI"
        P_VAR_I -> "vI"
        P_MEAN_P -> "mP"
        P_CORR_IP -> "cP"
        P_COV -> "tC"
        P_A -> "A"
        P_B -> "B"
        P_OUT_R, P_OUT_G, P_OUT_B -> "merged"
        else -> throw IllegalArgumentException("unknown plane $plane")
    }

    /**
     * ESSL binding slot -> bundle image key for one op. Fixed roles per
     * mode; plane triplets collapse because each mode touches fixed images
     * (RECONSTRUCT reads the interleaved rgbIn; RESOLVE/MERGE/GUIDE index
     * planes). Slots absent from the map take format-matched dummies.
     */
    fun slotRoles(op: Op): Map<Int, String> {
        val roles = mutableMapOf<Int, String>()
        when (op.mode) {
            M_BLUR_H, M_BLUR_V, M_COMBINE -> {
                if (op.a.isNotEmpty()) roles[B_PLANE_A] = imageKeyForPlane(op.a)
                if (op.b.isNotEmpty()) roles[B_PLANE_B] = imageKeyForPlane(op.b)
                if (op.c.isNotEmpty()) roles[B_PLANE_C] = imageKeyForPlane(op.c)
                roles[B_PLANE_OUT] = imageKeyForPlane(op.out)
            }
            M_RECONSTRUCT -> {
                roles[B_RGB_IN] = "rgbIn"
                roles[B_DIR0] = "dirs0"
                roles[B_DIR_A] = "dirsA"
                roles[B_DIR_B] = "dirsB"
                roles[B_DIR4] = "dirs4"
                roles[B_RGB_BUF] = "rgbBuf"
                roles[B_MASK_BUF] = "maskBuf"
            }
            M_RESOLVE -> {
                roles[B_RGB_BUF] = "rgbBuf"
                roles[B_MASK_BUF] = "maskBuf"
                roles[B_HALF_R] = "hR"
                roles[B_HALF_G] = "hG"
                roles[B_HALF_B] = "hB"
                roles[B_MASK_HALF] = "hM"
            }
            M_GUIDE -> {
                roles[B_HALF_R] = "hR"
                roles[B_HALF_G] = "hG"
                roles[B_HALF_B] = "hB"
                roles[B_GUIDE] = "g"
            }
            M_MERGE -> {
                roles[B_RGB_BUF] = "rgbBuf"
                roles[B_HALF_R] = "hR"
                roles[B_HALF_G] = "hG"
                roles[B_HALF_B] = "hB"
                roles[B_MASK_HALF] = "hM"
                roles[B_MERGED] = "merged"
            }
            else -> throw IllegalArgumentException("unknown mode ${op.mode}")
        }
        return roles
    }

    /** Bundle image table: key -> Triple(width, height, channels). */
    fun bundleImages(w: Int, h: Int, w2: Int, h2: Int, hfw: Int, hfh: Int): Map<String, Triple<Int, Int, Int>> {
        val table = mutableMapOf<String, Triple<Int, Int, Int>>()
        table["rgbIn"] = Triple(w, h, 4)
        for (d in listOf("dirs0", "dirsA", "dirsB", "dirs4")) table[d] = Triple(hfw, hfh, 4)
        table["rgbBuf"] = Triple(w, h, 4)
        table["maskBuf"] = Triple(w, h, 1)
        for (s in listOf("hR", "hG", "hB", "hM", "g", "s0", "mI", "cI", "vI", "mP", "cP", "tC", "A", "B")) {
            table[s] = Triple(w2, h2, 1)
        }
        table["gamma"] = Triple(256, 256, 1)
        table["merged"] = Triple(w, h, 4)
        table["dummyR32"] = Triple(1, 1, 1)
        table["dummyRgba"] = Triple(1, 1, 4)
        return table
    }

    /** Staged measurement bundle: everything a device run needs, plus the twin outputs. */
    data class MeasureBundle(
        val w: Int,
        val h: Int,
        val w2: Int,
        val h2: Int,
        val hfw: Int,
        val hfh: Int,
        val whitePt: Float,
        val clipPt: Float,
        val blendPt: Float,
        val maxF: FloatArray,
        val medF: FloatArray,
        val xyzRow: FloatArray,
        val plan: List<Op>,
        val inputs: Map<String, FloatArray>,
        val twinMerged: List<FloatArray>,
        val twinMask: Array<FloatArray>,
        val twinGuide: Array<FloatArray>
    )

    /**
     * Stages the exact device inputs (same uploads [runVulkan] performs),
     * runs the JVM twin for the expected outputs, and returns both. The
     * C++ harness in tools/hlmeasure executes [plan] over [inputs] and its
     * outputs are diffed against [twinMerged]/[twinMask]/[twinGuide].
     */
    fun measureBundle(
        red: Array<FloatArray>,
        green: Array<FloatArray>,
        blue: Array<FloatArray>,
        t: RtHlThresholds,
        maps: HlMaps,
        roi: HlRoi,
        xyzRow: FloatArray,
        gamma: FloatArray,
        guided: RtGuidedFilter.GuidedParams
    ): MeasureBundle {
        val w = roi.width
        val h = roi.height
        require(w % 2 == 0 && h % 2 == 0) { "ROI dims must be even (RT bufferW padding)" }
        val w2 = w / 2
        val h2 = h / 2
        val inputs = mutableMapOf<String, FloatArray>()
        inputs["rgbIn"] = interleaveRoi(red, green, blue, roi, w, h)
        inputs["dirs0"] = interleaveMaps(maps.dir0[0], maps.dir0[1], maps.dir0[2], maps.dir0[3], true)
        inputs["dirsA"] = interleaveMaps(maps.dir[0], maps.dir[1], maps.dir[2], maps.dir[3], false)
        inputs["dirsB"] = interleaveMaps(maps.dir[4], maps.dir[5], maps.dir[6], maps.dir[7], false)
        inputs["dirs4"] = interleaveMaps(maps.dir4[0], maps.dir4[1], maps.dir4[2], maps.dir4[3], true)
        val hR = Array(h2) { FloatArray(w2) }
        val hG = Array(h2) { FloatArray(w2) }
        val hB = Array(h2) { FloatArray(w2) }
        RtGuidedFilter.rescaleNearestInto(red, roi.minX, roi.minY, w, h, hR)
        RtGuidedFilter.rescaleNearestInto(green, roi.minX, roi.minY, w, h, hG)
        RtGuidedFilter.rescaleNearestInto(blue, roi.minX, roi.minY, w, h, hB)
        inputs["hR"] = hR.flat()
        inputs["hG"] = hG.flat()
        inputs["hB"] = hB.flat()
        inputs["gamma"] = gamma.copyOf()
        val params = RunParams(t, xyzRow, guided.gainThresh, guided.radius1, guided.radius2)
        val plan = buildPlan(w, h, w2, h2, params)
        // Full-frame twins: reconstructCpu writes the ROI window back.
        val fred = Array(red.size) { y -> red[y].copyOf() }
        val fgreen = Array(green.size) { y -> green[y].copyOf() }
        val fblue = Array(blue.size) { y -> blue[y].copyOf() }
        val result = reconstructCpu(fred, fgreen, fblue, t, maps, roi, xyzRow, gamma, guided)
        val merged = listOf(
            Array(h) { y -> FloatArray(w) { x -> fred[y + roi.minY][x + roi.minX] } }.flat(),
            Array(h) { y -> FloatArray(w) { x -> fgreen[y + roi.minY][x + roi.minX] } }.flat(),
            Array(h) { y -> FloatArray(w) { x -> fblue[y + roi.minY][x + roi.minX] } }.flat()
        )
        return MeasureBundle(
            w, h, w2, h2, maps.hfw, maps.hfh,
            t.whitePt, t.clipPt, t.blendPt,
            t.maxF.copyOf(), t.medFactor.copyOf(), xyzRow.copyOf(),
            plan, inputs, merged, result.mask, result.guide
        )
    }

    // ---- Vulkan path (the same plan through the shared hub) ----

    /**
     * Full highlight chain on Vulkan over the ROI. Identical stage order
     * and math to [reconstructCpu]; throws on dispatch failure (callers
     * fall back to [reconstructCpu]). Confine to one thread like the other
     * Vulkan orchestrators. When [downloadIntermediates] is set, the
     * filtered half-res mask and the gamma guide are also read back (the
     * on-device parity measurement vs the [CpuResult] twin needs them;
     * production leaves them on the device).
     */
    internal fun runVulkan(
        gpu: VulkanHub.GpuContext,
        red: Array<FloatArray>,
        green: Array<FloatArray>,
        blue: Array<FloatArray>,
        t: RtHlThresholds,
        maps: HlMaps,
        roi: HlRoi,
        xyzRow: FloatArray,
        gamma: FloatArray,
        guided: RtGuidedFilter.GuidedParams,
        downloadIntermediates: Boolean = false,
        /**
         * Interior to write back (tile interiors for the tiled GPU loop):
         * the download still covers [roi], but only rows/cols inside
         * [commitRoi] land in the channel planes, so halo pixels never
         * overwrite their neighbors' exact values. Null writes all of
         * [roi] (legacy behavior).
         */
        commitRoi: HlRoi? = null
    ): CpuResult {
        val w = roi.width
        val h = roi.height
        require(w % 2 == 0 && h % 2 == 0) { "ROI dims must be even (RT bufferW padding)" }
        val w2 = w / 2
        val h2 = h / 2
        val hfw = maps.hfw
        val hfh = maps.hfh
        val r32f = GLES30.GL_R32F
        val rgba32f = GLES30.GL_RGBA32F
        val pass = gpu.session
        fun tex(pw: Int, ph: Int, fmt: Int): VkImage = gpu.texture(pw, ph, fmt)
        // Plane images keyed by twin plane name (single-channel planes share
        // the R32F table; triplets are three entries).
        val imgs = mutableMapOf<String, VkImage>()
        val owned = mutableListOf<VkImage>()
        fun own(name: String, pw: Int, ph: Int, fmt: Int): VkImage {
            val img = tex(pw, ph, fmt)
            owned.add(img)
            imgs[name] = img
            return img
        }
        try {
            val tUp0 = SystemClock.elapsedRealtime()
            val rgbIn = own("rgbIn", w, h, rgba32f)
            rgbIn.uploadRgba32f(interleaveRoi(red, green, blue, roi, w, h), pass.uploads)
            own("dirs0", hfw, hfh, rgba32f).uploadRgba32f(
                interleaveMaps(maps.dir0[0], maps.dir0[1], maps.dir0[2], maps.dir0[3], true),
                pass.uploads
            )
            own("dirsA", hfw, hfh, rgba32f).uploadRgba32f(
                interleaveMaps(maps.dir[0], maps.dir[1], maps.dir[2], maps.dir[3], false),
                pass.uploads
            )
            own("dirsB", hfw, hfh, rgba32f).uploadRgba32f(
                interleaveMaps(maps.dir[4], maps.dir[5], maps.dir[6], maps.dir[7], false),
                pass.uploads
            )
            own("dirs4", hfw, hfh, rgba32f).uploadRgba32f(
                interleaveMaps(maps.dir4[0], maps.dir4[1], maps.dir4[2], maps.dir4[3], true),
                pass.uploads
            )
            own("rgbBuf", w, h, rgba32f)
            own("maskBuf", w, h, r32f)
            val hR = own("hR", w2, h2, r32f)
            val hG = own("hG", w2, h2, r32f)
            val hB = own("hB", w2, h2, r32f)
            val tmpR = Array(h2) { FloatArray(w2) }
            val tmpG = Array(h2) { FloatArray(w2) }
            val tmpB = Array(h2) { FloatArray(w2) }
            RtGuidedFilter.rescaleNearestInto(red, roi.minX, roi.minY, w, h, tmpR)
            RtGuidedFilter.rescaleNearestInto(green, roi.minX, roi.minY, w, h, tmpG)
            RtGuidedFilter.rescaleNearestInto(blue, roi.minX, roi.minY, w, h, tmpB)
            hR.uploadR32f(tmpR.flat(), pass.uploads)
            hG.uploadR32f(tmpG.flat(), pass.uploads)
            hB.uploadR32f(tmpB.flat(), pass.uploads)
            val hMask = own("hM", w2, h2, r32f)
            val guide = own("g", w2, h2, r32f)
            own("gamma", 256, 256, r32f).uploadR32f(gamma, pass.uploads)
            for (s in listOf("s0", "mI", "cI", "vI", "mP", "cP", "tC", "A", "B")) {
                own(s, w2, h2, r32f)
            }
            val merged = own("merged", w, h, rgba32f)
            val dummyR32 = own("dummyR32", 1, 1, r32f)
            val dummyRgba = own("dummyRgba", 1, 1, rgba32f)
            val tUp = SystemClock.elapsedRealtime() - tUp0
            val tDisp0 = SystemClock.elapsedRealtime()
            // Twin plane name -> role image for the current op.
            fun role(name: String): VkImage = imgs.getValue(imageKeyForPlane(name))
            val params = RunParams(t, xyzRow, guided.gainThresh, guided.radius1, guided.radius2)
            for (op in buildPlan(w, h, w2, h2, params)) {
                pass.pass(SHADER) {
                    sampler("u_gamma", imgs.getValue("gamma"))
                    val roles = slotRoles(op).mapValues { (_, key) -> imgs.getValue(key) }
                        .toMutableMap()
                    // The shader declares every binding; unused slots take
                    // dummies (Vulkan requires valid descriptors).
                    for (slot in B_PLANE_A..B_MERGED) {
                        val img = roles[slot] ?: when (slot) {
                            B_RGB_IN, B_DIR0, B_DIR_A, B_DIR_B, B_DIR4,
                            B_RGB_BUF, B_MERGED -> dummyRgba
                            else -> dummyR32
                        }
                        val fmt = if (img.internalFormat == rgba32f) rgba32f else r32f
                        image(slot, img, fmt)
                    }
                    integer("u_mode", op.mode)
                    integer("u_W", op.w)
                    integer("u_H", op.h)
                    integer("u_radius", op.radius)
                    integer("u_combineOp", op.combineOp)
                    integer("u_pitch", 4)
                    integer("u_hfw", hfw)
                    integer("u_hfh", hfh)
                    float("u_eps", if (op.mode == M_COMBINE) op.eps else EPSILON)
                    float("u_whitePt", t.whitePt)
                    float("u_clipPt", t.clipPt)
                    float("u_blendPt", t.blendPt)
                    vec4("u_maxF", floatArrayOf(t.maxF[0], t.maxF[1], t.maxF[2], 0f))
                    vec4("u_medF", floatArrayOf(t.medFactor[0], t.medFactor[1], t.medFactor[2], 0f))
                    vec4("u_xyzRow", floatArrayOf(xyzRow[0], xyzRow[1], xyzRow[2], 0f))
                    dispatch(op.w, op.h, 8, 8)
                }
            }
            // Writeback window: the commit interior when tiling, else roi.
            val wy0 = maxOf(0, (commitRoi?.minY ?: roi.minY) - roi.minY)
            val wy1 = minOf(h, (commitRoi?.maxY ?: (roi.minY + h - 1)) - roi.minY + 1)
            val wx0 = maxOf(0, (commitRoi?.minX ?: roi.minX) - roi.minX)
            val wx1 = minOf(w, (commitRoi?.maxX ?: (roi.minX + w - 1)) - roi.minX + 1)
            val tDisp = SystemClock.elapsedRealtime() - tDisp0
            val tDown0 = SystemClock.elapsedRealtime()
            val flat = merged.downloadRgba32f()
            for (y in wy0 until wy1) {
                for (x in wx0 until wx1) {
                    val b = (y * w + x) * 4
                    red[y + roi.minY][x + roi.minX] = flat[b]
                    green[y + roi.minY][x + roi.minX] = flat[b + 1]
                    blue[y + roi.minY][x + roi.minX] = flat[b + 2]
                }
            }
            Log.i(
                HlReconstructionStage.LOG_TAG,
                "hltile ${w}x$h up=${tUp}ms disp=${tDisp}ms down=${SystemClock.elapsedRealtime() - tDown0}ms"
            )
            val mask = if (downloadIntermediates) hMask.downloadR32f().toPlane(h2, w2)
            else Array(h2) { FloatArray(w2) }
            val gd = if (downloadIntermediates) guide.downloadR32f().toPlane(h2, w2)
            else Array(h2) { FloatArray(w2) }
            return CpuResult(mask, gd)
        } finally {
            for (img in owned.asReversed()) gpu.release(img)
        }
    }

    /**
     * Entry point: [gpu] selects the Vulkan dispatches; null runs the CPU
     * twin ([reconstructCpu]). Vulkan failures must be caught by the
     * caller, which falls back to [reconstructCpu].
     */
    internal fun run(
        gpu: VulkanHub.GpuContext?,
        red: Array<FloatArray>,
        green: Array<FloatArray>,
        blue: Array<FloatArray>,
        t: RtHlThresholds,
        maps: HlMaps,
        roi: HlRoi,
        xyzRow: FloatArray,
        gamma: FloatArray,
        guided: RtGuidedFilter.GuidedParams,
        downloadIntermediates: Boolean = false,
        commitRoi: HlRoi? = null
    ): CpuResult =
        if (gpu == null) reconstructCpu(red, green, blue, t, maps, roi, xyzRow, gamma, guided)
        else runVulkan(
            gpu, red, green, blue, t, maps, roi, xyzRow, gamma, guided,
            downloadIntermediates, commitRoi
        )

    private fun interleaveRoi(
        red: Array<FloatArray>, green: Array<FloatArray>, blue: Array<FloatArray>,
        roi: HlRoi, w: Int, h: Int
    ): FloatArray {
        val out = FloatArray(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val b = (y * w + x) * 4
                out[b] = red[y + roi.minY][x + roi.minX]
                out[b + 1] = green[y + roi.minY][x + roi.minX]
                out[b + 2] = blue[y + roi.minY][x + roi.minX]
                out[b + 3] = 1f
            }
        }
        return out
    }

    /**
     * Interleaves four channel planes into one RGBA upload. X-major maps
     * (dir0/dir4: plane [x][y]) transpose on upload so image (x, y) holds
     * channel c of map cell (x, y), exactly what RECONSTRUCT samples.
     */
    fun interleaveMaps(
        ch0: Array<FloatArray>,
        ch1: Array<FloatArray>,
        ch2: Array<FloatArray>,
        ch3: Array<FloatArray>,
        xMajor: Boolean
    ): FloatArray {
        val w = if (xMajor) ch0.size else ch0[0].size
        val h = if (xMajor) ch0[0].size else ch0.size
        val out = FloatArray(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val b = (y * w + x) * 4
                if (xMajor) {
                    out[b] = ch0[x][y]
                    out[b + 1] = ch1[x][y]
                    out[b + 2] = ch2[x][y]
                    out[b + 3] = ch3[x][y]
                } else {
                    out[b] = ch0[y][x]
                    out[b + 1] = ch1[y][x]
                    out[b + 2] = ch2[y][x]
                    out[b + 3] = ch3[y][x]
                }
            }
        }
        return out
    }

    private fun Array<FloatArray>.flat(): FloatArray {
        val out = FloatArray(size * this[0].size)
        for (y in indices) this[y].copyInto(out, y * this[0].size)
        return out
    }

    private fun FloatArray.toPlane(h: Int, w: Int): Array<FloatArray> {
        require(size == h * w)
        return Array(h) { y -> FloatArray(w) { x -> this[y * w + x] } }
    }
}
