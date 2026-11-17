// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

/**
 * Offline check on a real phone take: `STAB_TAKE_DIR` holds `side.json`
 * (sidecar kept with debug.rawlens.stab_keep=1) and `shifts.csv`
 * (frame,dx,dy measured centre motion of the original, encode px).
 * Prints the predicted output jitter for the gyro model and the
 * picture-calibrated model. Skipped when the variable is unset.
 */
class StabRealTakeTest {
    @Test
    fun realTake() {
        val dir = System.getenv("STAB_TAKE_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val take = StabSidecar.parse(File(dir, "side.json").readText())!!
        val meas = File(dir, "shifts.csv").readLines().filter { it.isNotBlank() }.map { l ->
            val p = l.split(','); Triple(p[0].toInt(), p[1].toDouble(), p[2].toDouble())
        }
        val calibN = (System.getenv("STAB_CALIB_FRAMES") ?: "600").toInt()
        val luma = File(dir, "luma.raw")
        val pairs = if (luma.isFile) {
            // Same measurement as StabCalibPass on device (LK, 320x180, se filter).
            val lw = 320; val lh = 180; val bytes = luma.readBytes(); val nf = bytes.size / (lw * lh)
            fun frame(i: Int) = FloatArray(lw * lh) { (bytes[i * lw * lh + it].toInt() and 0xFF) / 255f }
            val scale = take.header.encodeW.toDouble() / lw
            (0 until minOf(nf - 1, calibN - 1)).mapNotNull { i ->
                val sh = StabCalib.GlobalShift.measure(frame(i), frame(i + 1), lw, lh)
                if (sh != null && sh.stdErr.isFinite() && sh.stdErr < 0.25)
                    StabCalib.PairShift(i, sh.dx * scale, sh.dy * scale, (0.0625 / (sh.stdErr * sh.stdErr).coerceAtLeast(1e-6)).coerceAtMost(100.0))
                else null
            }.also { println("LK pairs=${it.size}") }
        } else meas.filter { it.first < calibN }.map { StabCalib.PairShift(it.first, it.second, it.third, 1.0) }
        val r = StabCalib.calibrate(take, pairs)!!
        println("calib gain=${r.gainX},${r.gainY} matrix=${r.matrixAccepted} cv=${r.cvGyroPx}->${r.cvFitPx} " +
            "offsetOk=${r.offsetAccepted} rms=${r.rmsBeforePx}->${r.rmsAfterPx} M=${r.calibration.rateMatrix?.joinToString { "%.2f".format(it) }}")
        report("raw", meas, null, take)
        val opts = StabPlanner.Options(cropMargin = (System.getenv("STAB_MARGIN") ?: StabTrajectory.CROP_MARGIN.toString()).toDouble())
        report("gyro", meas, StabPlanner.plan(take, opts, StabPlanner.Calibration()), take)
        report("calib", meas, StabPlanner.plan(take, opts, r.calibration), take)
        r.calibration.rateMatrix?.let { m0 ->
            val m = m0.copyOf(); m[2] = 0.0; m[5] = 0.0
            report("noRollMix", meas, StabPlanner.plan(take, opts, r.calibration.copy(rateMatrix = m)), take)
            val d = m0.copyOf(); d[1] = 0.0; d[2] = 0.0; d[3] = 0.0; d[5] = 0.0
            report("diagOnly", meas, StabPlanner.plan(take, opts, r.calibration.copy(rateMatrix = d)), take)
            val z = r.calibration.copy(rateMatrix = m0, rateOffset = null)
            report("noOffset", meas, StabPlanner.plan(take, opts, z), take)
        }
        System.getenv("STAB_CONV")?.let {
            for (cap in listOf(3000, 10000, 30000)) for (m in listOf(0.08, 0.10, 0.125, 0.15)) {
                val t0 = System.nanoTime()
                val pl = StabPlanner.plan(take, opts.copy(cropMargin = m, maxIterations = cap), r.calibration)!!
                print("cap=$cap m=$m its=${pl.solverIterations} conv=${pl.solverConverged} ms=${(System.nanoTime() - t0) / 1_000_000} ")
                report("m$m", meas, pl, take)
            }
        }
        System.getenv("STAB_SWEEP")?.let {
            for (ms in listOf(-12, -8, -4, 0, 4, 8, 12)) {
                val c = r.calibration.copy(gyroOffsetNs = ms * 1_000_000L)
                report("off$ms", meas, StabPlanner.plan(take, opts, c), take)
            }
        }
    }

    private fun report(name: String, meas: List<Triple<Int, Double, Double>>, plan: StabPlanner.Plan?, take: StabSidecar.Take) {
        val w = take.header.encodeW / 2.0
        val h = take.header.encodeH / 2.0
        val p0 = DoubleArray(2)
        val p1 = DoubleArray(2)
        val out = meas.filter { it.first + 1 < take.frameTimestampsNs.size }.map { (i, dx, dy) ->
            if (plan == null) dx to dy else {
                plan.sourcePoint(i, w, h, p0); plan.sourcePoint(i + 1, w, h, p1)
                (dx - (p1[0] - p0[0])) to (dy - (p1[1] - p0[1]))
            }
        }
        if (plan != null) System.getenv("STAB_DUMP")?.let { d ->
            File(d, "pred_$name.csv").writeText((0 until take.frameTimestampsNs.size).joinToString("\n") { i ->
                plan.sourcePoint(i, w, h, p0); val c = "${p0[0]},${p0[1]}"
                plan.sourcePoint(i, w, h * 0.5, p0); val t = "${p0[0]},${p0[1]}"
                plan.sourcePoint(i, w, h * 1.5, p0); "$c,$t,${p0[0]},${p0[1]}" })
        }
        val px = DoubleArray(out.size + 1); val py = DoubleArray(out.size + 1)
        for (k in out.indices) { px[k + 1] = px[k] + out[k].first; py[k + 1] = py[k] + out[k].second }
        fun jitter(a: DoubleArray): Double {
            val k = 15; var ss = 0.0; var c = 0
            for (i in k until a.size - k) {
                var m = 0.0; for (j in -k / 2..k / 2) m += a[i + j]; m /= (k / 2 * 2 + 1)
                ss += (a[i] - m) * (a[i] - m); c++
            }
            return sqrt(ss / c)
        }
        var corr = 0.0; var cm = 0.0
        if (plan != null) for (i in 0 until take.frameTimestampsNs.size) {
            plan.sourcePoint(i, w, h, p0); val c = kotlin.math.hypot(p0[0] - w, p0[1] - h); corr += c * c; cm = maxOf(cm, c)
        }
        println("$name corr rms=%.1f max=%.1f fo=%s".format(sqrt(corr / take.frameTimestampsNs.size), cm, plan?.virtualFocal))
        val step = sqrt(out.sumOf { it.first * it.first + it.second * it.second } / out.size)
        fun jerk(a: DoubleArray): Double { var ss = 0.0; for (i in 2 until a.size) { val d = a[i] - 2 * a[i - 1] + a[i - 2]; ss += d * d }; return sqrt(ss / (a.size - 2)) }
        println("%-6s jitter x=%.1f y=%.1f  jerk x=%.2f y=%.2f  step=%.1f px".format(name, jitter(px), jitter(py), jerk(px), jerk(py), step))
    }
}
