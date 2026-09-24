// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round

/**
 * Prompt 4E: real-scene scale-1 merge image-quality gate on the Sea fixture.
 * Criteria and method were declared in docs/raw-sr-4e-runlog.md BEFORE judging.
 * Test-only exports under cacheDir/rawsr-debug/merge4e; production saving untouched.
 */
@RunWith(AndroidJUnit4::class)
class RawSrMergeQuality4EInstrumentedTest {
    @Test fun realSeaMergeQualityAtBurstCounts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val started = SystemClock.elapsedRealtime()
        val fixture = RawSrFixture.load(instrumentation.context.assets)
        assertEquals(30, fixture.frames.size)
        assertEquals(15, fixture.referenceIndex)
        val config = RawSrAlignmentConfig(levels = 4, tileSize = 16, searchRadius = 4)
        val order = fixture.referenceFirst
        val manifestFrames = fixture.manifest.getJSONArray("frames")
        val outDir = File(instrumentation.targetContext.cacheDir, "rawsr-debug/merge4e")
        outDir.mkdirs()

        val report = JSONObject().put("gate", "4E").put("fixture", "sea")
            .put("referenceIndex", fixture.referenceIndex).put("adreno", "UNTESTED")
        val allFailures = mutableListOf<String>()
        var tuningRecorded = false
        var glVendor = "unknown"; var glRenderer = "unknown"
        var refOnlyRgb: FloatArray? = null
        var refOnlyW = 0; var refOnlyH = 0
        val rcMeans = mutableListOf<Double>()
        val q3Ratios = mutableMapOf<Int, Double>()
        val countEntries = JSONArray()

        Gles31RawSrProcessor(instrumentation.targetContext).use { processor ->
            // Reference-only export first: matched baseline for every count.
            val refFrames = listOf(fixture.frames[fixture.referenceIndex])
            val refTuning = RawSrTuning.fromReference(refFrames.first()).tuning
            recordTuning(report, refTuning)
            tuningRecorded = true
            processor.processPacked(refFrames, config, referenceOnly = true) { output ->
                glVendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "unknown"
                glRenderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "unknown"
                val rgb = readTexture(output.mergedTextureId, output.width, output.height, GLES30.GL_RGBA)
                refOnlyRgb = rgb; refOnlyW = output.width; refOnlyH = output.height
                writeRgbBin(File(outDir, "refonly_rgb_f32le.bin"), rgb, output.width, output.height)
                report.put("refOnlyPeakTextureBytes", output.peakTextureBytes)
                val expected = packedOracle(refFrames, config, refTuning, referenceOnly = true)
                val refEntry = JSONObject()
                val (err, refFailures) = agreement(refEntry, output, expected, "4E refOnly")
                if (err > RGB_TOL) refFailures.add("4E refOnly agreement maxError=$err")
                allFailures.addAll(refFailures)
                report.put("refOnlyAgreement", refEntry)
            }

            for (count in listOf(2, 8, 15, 30)) {
                val subsetIdx = order.take(count)
                val frames = subsetIdx.map { fixture.frames[it] }
                val tuning = RawSrTuning.fromReference(frames.first()).tuning
                val countStarted = SystemClock.elapsedRealtime()
                lateinit var merged: FloatArray
                lateinit var den: FloatArray
                lateinit var num: FloatArray
                lateinit var rc: FloatArray
                lateinit var fallback: FloatArray
                lateinit var oob: FloatArray
                var width = 0; var height = 0; var accepted = 0; var peak = 0L
                val robustMeans = JSONArray()
                processor.processPacked(frames, config,
                    onRobustness = { index, rTex, flagsTex, w, h ->
                        val r = readTexture(rTex, w, h, GLES30.GL_RED)
                        var sum = 0.0; for (v in r) sum += v
                        robustMeans.put(JSONObject().put("frame", order[index])
                            .put("meanR", sum / r.size).put("width", w).put("height", h))
                    }) { output ->
                    width = output.width; height = output.height
                    accepted = output.acceptedFrames; peak = output.peakTextureBytes
                    merged = readTexture(output.mergedTextureId, width, height, GLES30.GL_RGBA)
                    val refNum = readTexture(output.refNumeratorTextureId, width, height, GLES30.GL_RGBA)
                    val refDen = readTexture(output.refDenominatorTextureId, width, height, GLES30.GL_RGBA)
                    num = readTexture(output.numeratorTextureId, width, height, GLES30.GL_RGBA)
                    den = readTexture(output.denominatorTextureId, width, height, GLES30.GL_RGBA)
                    for (i in num.indices) { num[i] += refNum[i]; den[i] += refDen[i] }
                    rc = readTexture(output.rcTextureId, width / 2, height / 2, GLES30.GL_RED)
                    fallback = readTexture(output.fallbackTextureId, width, height, GLES30.GL_RED)
                    oob = readTexture(output.oobTextureId, width, height, GLES30.GL_RED)
                }
                val oracleStarted = SystemClock.elapsedRealtime()
                val expected = packedOracle(frames, config, tuning)
                val oracleMs = SystemClock.elapsedRealtime() - oracleStarted
                // Q1 numerical agreement (distinct from image quality).
                val entry = JSONObject().put("count", count)
                val gpuOnly = GpuArrays(merged, num, den, rc, fallback, oob, width, height)
                val (agreeErr, agreeFailures) = agreementArrays(entry, gpuOnly, expected, "4E count=$count",
                    lastOracleFrames)
                    .let { (e, f) -> e to f.toMutableList() }
                if (agreeErr > RGB_TOL) agreeFailures.add("4E count=$count agreement maxError=$agreeErr")
                allFailures.addAll(agreeFailures)
                entry.put("sourceFrames", JSONArray(subsetIdx.toList()))
                    .put("acceptedFrames", accepted)
                    .put("agreementMaxError", agreeErr.toDouble())
                    .put("gpuElapsedMs", SystemClock.elapsedRealtime() - countStarted - oracleMs)
                    .put("cpuOracleElapsedMs", oracleMs)
                    .put("peakTextureBytes", peak)
                    .put("robustness", robustMeans)
                // Exports: numerical linear data preserved as float32-LE.
                val stem = "count%02d".format(count)
                writeRgbBin(File(outDir, "${stem}_merged_rgb_f32le.bin"), merged, width, height)
                writeRgbBin(File(outDir, "${stem}_denominator_f32le.bin"), den, width, height)
                writeScalarBin(File(outDir, "${stem}_rc_f32le.bin"), rc)
                writeScalarBin(File(outDir, "${stem}_fallback_f32le.bin"), fallback)
                writeScalarBin(File(outDir, "${stem}_oob_f32le.bin"), oob)
                writeCrops(outDir, stem, requireNotNull(refOnlyRgb), merged, width, height)
                val (rcMean, failures) = evaluateQuality(entry, requireNotNull(refOnlyRgb),
                    merged, den, rc, fallback, expected.rgb, width, height, q3Ratios)
                rcMeans.add(rcMean)
                entry.put("qualityFailures", JSONArray(failures))
                File(outDir, "${stem}.json").writeText(entry.toString(2))
                File(outDir, "merge4e_partial.json").writeText(report.toString(2))
                Log.i(TAG, "4E count=$count $entry")
                allFailures.addAll(failures.map { "count=$count $it" })
                entry.put("frameSha256", JSONArray(subsetIdx.map {
                    manifestFrames.getJSONObject(it).getString("sha256")
                }))
                countEntries.put(entry)
                Log.i(TAG, "4E $entry")
            }
        }
        report.put("counts", countEntries)
        report.put("vendor", glVendor)
        report.put("renderer", glRenderer)
        report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
        // Q9 support trend across counts.
        for (i in 1 until rcMeans.size)
            assertTrue("4E Rc trend count index $i: ${rcMeans[i]} < ${rcMeans[i - 1]}",
                rcMeans[i] >= rcMeans[i - 1] - 1e-6)
        report.put("rcMeansInterior", JSONArray(rcMeans.toList()))
        report.put("rcTrendPass", true)
        // Q3 trend across counts (amendment 2026-09-11 — see runlog): burst merging
        // must strictly reduce the noise component. count30 ratio must show net
        // denoise vs the single-frame ref (< 1.0) and beat count2 by >= 0.02.
        val q3r2 = q3Ratios[2]; val q3r30 = q3Ratios[30]
        val q3TrendPass = q3r2 != null && q3r30 != null && q3r30 <= 1.0 && q3r30 <= q3r2 - 0.02
        if (!q3TrendPass) allFailures.add("4E Q3 trend q3Ratios=$q3Ratios")
        val q3Json = JSONObject()
        for ((k, v) in q3Ratios) q3Json.put(k.toString(), v)
        report.put("q3Ratios", q3Json)
        report.put("q3TrendPass", q3TrendPass)
        report.put("qualityFailures", JSONArray(allFailures.toList()))
        File(outDir, "merge4e.json").writeText(report.toString(2))
        assertTrue("tuning recorded", tuningRecorded)
        assertTrue("4E failures=$allFailures", allFailures.isEmpty())
    }

    /** Packed-path CPU oracle (same construction as the 4D parity tests). */
    private fun packedOracle(frames: List<RawSrPackedFrame>, config: RawSrAlignmentConfig,
                             tuning: RawSrTuning,
                             referenceOnly: Boolean = false): RawSrBayerMerge.MergeResult {
        fun shaded(frame: RawSrPackedFrame): Pair<UnpackedRawCfa, BayerPattern> {
            val input = frame.uploadInput()
            val cpu = RawSensorUnpacker.unpackNormalized(input.buffer, input.layout, input.normalization, input.crop)
            val lens = input.lensShading
            if (lens != null) for (y in 0 until cpu.height) for (x in 0 until cpu.width)
                cpu.values[y * cpu.width + x] *= lens.gainAt(cpu.sensorCropLeft + x, cpu.sensorCropTop + y,
                    cpu.pattern.colorAt(x, y))
            return cpu to input.normalization.sensorPattern
        }
        val shadedFrames = frames.map(::shaded)
        val grays = shadedFrames.map { RawSrAlignment.bayerQuadGray(it.first) }
        val moving = if (referenceOnly) emptyList() else frames.drop(1).mapIndexed { i, frame ->
            val flow = RawSrAlignment.align(grays[0], grays[i + 1], config)
            val robust = RawSrRobustness.evaluate(RawSrRobustness.linearGuide(frames[0]),
                RawSrRobustness.linearGuide(frame), flow, tuning, config)
            val (cpu, sensorPattern) = shadedFrames[i + 1]
            RawSrBayerMerge.MergeFrame(cpu.width, cpu.height, cpu.values, sensorPattern,
                cpu.sensorCropLeft, cpu.sensorCropTop,
                RawSrKernelCovariance.precision(RawSrCovarianceGuide.guide(frame).gray, tuning),
                flow, robust)
        }
        lastOracleFrames = moving
        val (refCpu, refPattern) = shadedFrames[0]
        return RawSrBayerMerge.merge(
            RawSrBayerMerge.MergeFrame(refCpu.width, refCpu.height, refCpu.values, refPattern,
                refCpu.sensorCropLeft, refCpu.sensorCropTop,
                RawSrKernelCovariance.precision(RawSrCovarianceGuide.guide(frames[0]).gray, tuning),
                null, null),
            moving, referenceOnly)
    }

    private fun recordTuning(report: JSONObject, tuning: RawSrTuning) {
        report.put("tuning", JSONObject()
            .put("snr", tuning.snr).put("rawTileSize", tuning.rawTileSize)
            .put("kDetail", tuning.kDetail).put("kDenoise", tuning.kDenoise)
            .put("dTh", tuning.dTh).put("dTr", tuning.dTr)
            .put("kStretch", tuning.kStretch).put("kShrink", tuning.kShrink)
            .put("t", tuning.t).put("s1", tuning.s1).put("s2", tuning.s2)
            .put("mTh", tuning.mTh))
    }

    private data class GpuArrays(val merged: FloatArray, val num: FloatArray, val den: FloatArray,
                                 val rc: FloatArray, val fallback: FloatArray, val oob: FloatArray,
                                 val width: Int, val height: Int)

    private var lastOracleFrames: List<RawSrBayerMerge.MergeFrame> = emptyList()

    /** True when any moving frame's oracle-projected source at raw pixel (x, y)
     * sits within [STRADDLE_BAND] of an integer in x or y, i.e. the float64 CPU
     * source and the float32 GPU source may floor the 3x3 tap-window center to
     * adjacent pixels. Mirrors the source projection in
     * [RawSrBayerMerge.accumulateFrame] exactly (reference frames project to
     * x + 0.5 and never straddle). */
    private fun isTapWindowStraddle(x: Int, y: Int, frames: List<RawSrBayerMerge.MergeFrame>): Boolean {
        if (frames.isEmpty()) return false
        val qx = x / 2
        val qy = y / 2
        for (frame in frames) {
            val flow = frame.flow ?: continue
            val tile = flow.flowAtSmooth(qx.toFloat(), qy.toFloat())
            if (!tile.dx.isFinite() || !tile.dy.isFinite()) continue
            val sx = x + 0.5 + 2.0 * tile.dx
            val sy = y + 0.5 + 2.0 * tile.dy
            if (!sx.isFinite() || !sy.isFinite()) continue
            if (abs(sx - round(sx)) < STRADDLE_BAND || abs(sy - round(sy)) < STRADDLE_BAND) return true
        }
        return false
    }

    /** True when any moving frame's burst-nearest pick for channel [c] at raw
     * pixel (x, y) is unstable: the best and second-best same-colour tap
     * distances differ by less than [TIE_BAND], so the float64 CPU source and
     * the float32 GPU source may pick different taps. Winner-takes-all
     * nearest is inherently discontinuous (unlike the smooth kernel
     * weights), so near-ties flip far more often than tap-window straddles.
     * Only meaningful where rgb derives from the nearest totals, i.e. at
     * fallback pixels — callers must check that separately. */
    private fun isNearTie(x: Int, y: Int, c: Int, frames: List<RawSrBayerMerge.MergeFrame>): Boolean {
        if (frames.isEmpty()) return false
        val qx = x / 2
        val qy = y / 2
        for (frame in frames) {
            val flow = frame.flow ?: continue
            val tile = flow.flowAtSmooth(qx.toFloat(), qy.toFloat())
            if (!tile.dx.isFinite() || !tile.dy.isFinite()) continue
            val sx = x + 0.5 + 2.0 * tile.dx
            val sy = y + 0.5 + 2.0 * tile.dy
            if (!sx.isFinite() || !sy.isFinite()) continue
            val cx = floor(sx).toInt()
            val cy = floor(sy).toInt()
            var best = Double.POSITIVE_INFINITY
            var second = Double.POSITIVE_INFINITY
            for (oy in -1..1) for (ox in -1..1) {
                val tx = cx + ox
                val ty = cy + oy
                if (tx < 0 || ty < 0 || tx >= frame.width || ty >= frame.height) continue
                val channel = when (frame.sensorPattern.colorAt(
                    frame.sensorLeft + tx, frame.sensorTop + ty)) {
                    CfaColor.RED -> 0
                    CfaColor.GREEN -> 1
                    CfaColor.BLUE -> 2
                }
                if (channel != c) continue
                val sample = frame.samples[ty * frame.width + tx].toDouble()
                if (!sample.isFinite()) continue
                val dx = tx + 0.5 - sx
                val dy = ty + 0.5 - sy
                val d2 = dx * dx + dy * dy
                if (d2 < best) {
                    second = best
                    best = d2
                } else if (d2 < second) {
                    second = d2
                }
            }
            if (second - best < TIE_BAND) return true
        }
        return false
    }

    /** Q1: per-sample merge-contract tolerance; collects violations (footprint for
     * diagnosis) instead of throwing at the first pixel. Returns the rgb max
     * error for the image-level gate; the JSON report keeps the accumulator
     * max error too (unnormalized accumulators legitimately exceed RGB_TOL). */
    private fun agreementArrays(entry: JSONObject, gpu: GpuArrays, expected: RawSrBayerMerge.MergeResult,
                                label: String,
                                frames: List<RawSrBayerMerge.MergeFrame> = emptyList()): Pair<Float, List<String>> {
        val failures = mutableListOf<String>()
        var maxError = 0f
        var maxRgbError = 0f
        var violations = 0
        var excusedFlips = 0
        var excusedStraddles = 0
        var excusedGuard = 0
        val first = mutableListOf<String>()
        val firstExcused = mutableListOf<String>()
        var nonFinite = 0
        for (y in 4 until gpu.height - 4) for (x in 4 until gpu.width - 4) {
            val p = y * gpu.width + x
            for (c in 0..2) {
                // num/den first: a straddle-excused accumulator excuses the rgb
                // sample derived from it below, since both shifts share the one
                // physical cause (adjacent tap windows). An rgb sample whose
                // accumulators agree stays strictly gated, so finalize-path bugs
                // cannot hide behind this provision.
                var accExcused = false
                for ((name, g, cpu) in listOf(
                        Triple("num", gpu.num[p * 4 + c], expected.numerator[p * 3 + c]),
                        Triple("den", gpu.den[p * 4 + c], expected.denominator[p * 3 + c]))) {
                    if (!g.isFinite()) {
                        nonFinite++
                        if (first.size < 5) first.add("$label $name ($x,$y) ch$c non-finite gpu=$g cpu=$cpu")
                        continue
                    }
                    val err = abs(cpu - g)
                    val over = err > MERGE_TOL_ABS + MERGE_TOL_REL * abs(cpu)
                    // Tap-window straddle provision: the oracle merges in
                    // float64 with CPU flow while the GPU merges in float32
                    // with GPU flow (on-device CPU/GPU flow agreement <=
                    // ~2e-5 quad-px). Where a frame's projected source sits
                    // within STRADDLE_BAND of an integer, the two sides may
                    // floor the 3x3 tap-window center to adjacent pixels and
                    // the per-channel accumulators differ by whole tap
                    // weights. Excuse exactly those samples (counted for
                    // diagnosis); fallback, oob, rc and non-straddle samples
                    // stay strictly gated.
                    if (over && isTapWindowStraddle(x, y, frames)) {
                        accExcused = true
                        excusedStraddles++
                        if (firstExcused.size < 5)
                            firstExcused.add("$label $name ($x,$y) ch$c gpu=$g cpu=$cpu")
                        continue
                    }
                    maxError = max(maxError, err)
                    if (over) {
                        violations++
                        if (first.size < 5) first.add("$label $name ($x,$y) ch$c gpu=$g cpu=$cpu")
                    }
                }
                run {
                    val g = gpu.merged[p * 4 + c]
                    val cpu = expected.rgb[p * 3 + c]
                    if (!g.isFinite()) {
                        nonFinite++
                        if (first.size < 5) first.add("$label rgb ($x,$y) ch$c non-finite gpu=$g cpu=$cpu")
                        return@run
                    }
                    val err = abs(cpu - g)
                    val over = err > MERGE_TOL_ABS + MERGE_TOL_REL * abs(cpu)
                    if (over && accExcused) {
                        excusedStraddles++
                        if (firstExcused.size < 5)
                            firstExcused.add("$label rgb ($x,$y) ch$c gpu=$g cpu=$cpu (accumulators straddled)")
                        return@run
                    }
                    // At fallback pixels rgb derives from the burst-nearest
                    // totals, whose winner-takes-all pick flips wherever the
                    // source straddles a tap-center boundary or sits near a
                    // distance tie. Excuse exactly those rgb samples (the
                    // pick itself is equally valid on both sides); rgb with
                    // agreeing kernel accumulators stays strict.
                    val isFb = gpu.fallback[p] == 1f || expected.fallback[p]
                    if (over && isFb &&
                        (isTapWindowStraddle(x, y, frames) || isNearTie(x, y, c, frames))) {
                        excusedStraddles++
                        if (firstExcused.size < 5)
                            firstExcused.add("$label rgb ($x,$y) ch$c gpu=$g cpu=$cpu (nearest unstable)")
                        return@run
                    }
                    // Saturation-guard boundary provision: the oracle decides
                    // ref >= 0.99 in float64, the GPU in float32, so a
                    // quotient within 1e-4 of the boundary can flip the guard
                    // on one side only and move the value by the full
                    // nearest-vs-ref distance. Excuse exactly those samples
                    // (counted); the guard itself is verified by Q6/Q7.
                    if (over && isFb &&
                        abs(expected.refQuotient[p * 3 + c] - 0.99f) < 1e-4f) {
                        excusedGuard++
                        if (firstExcused.size < 5)
                            firstExcused.add("$label rgb ($x,$y) ch$c gpu=$g cpu=$cpu (guard boundary)")
                        return@run
                    }
                    maxError = max(maxError, err)
                    maxRgbError = max(maxRgbError, err)
                    if (over) {
                        violations++
                        if (first.size < 5) {
                            first.add("$label rgb ($x,$y) ch$c gpu=$g cpu=$cpu" +
                                " num(gpu=${gpu.num[p * 4 + c]},cpu=${expected.numerator[p * 3 + c]})" +
                                " den(gpu=${gpu.den[p * 4 + c]},cpu=${expected.denominator[p * 3 + c]})" +
                                " fb=${gpu.fallback[p]}/${expected.fallback[p]}")
                        }
                    }
                }
            }
            if (gpu.fallback[p] != (if (expected.fallback[p]) 1f else 0f)) {
                // Razor provision (§9 support overwrite): the overwrite
                // threshold fires on Rc, and Rc agrees only within tolerance,
                // so a firing rule can flip the mask where both sides sit
                // inside the accepted band around MIN_SUPPORT. Excuse exactly
                // those flips (counted for diagnosis); RGB at these pixels is
                // still gated by the tolerance checks above, and flips outside
                // the band stay violations.
                val q = (y / 2) * (expected.width / 2) + (x / 2)
                val grc = gpu.rc[q]; val crc = expected.rc.values[q]
                val band = MERGE_TOL_ABS + MERGE_TOL_REL * max(abs(grc), abs(crc))
                if (abs(grc - RawSrBayerMerge.MIN_SUPPORT) <= band &&
                    abs(crc - RawSrBayerMerge.MIN_SUPPORT) <= band) {
                    excusedFlips++
                } else {
                    violations++
                    if (first.size < 5) first.add("$label fallback ($x,$y)")
                }
            }
            if (gpu.oob[p] != expected.oobCount[p].toFloat()) {
                violations++
                if (first.size < 5) first.add("$label oob ($x,$y)")
            }
        }
        var rcViolations = 0
        for (i in expected.rc.values.indices) {
            val g = gpu.rc[i]; val cpu = expected.rc.values[i]
            if (!g.isFinite() || abs(cpu - g) > MERGE_TOL_ABS + MERGE_TOL_REL * abs(cpu)) rcViolations++
            if (g.isFinite()) maxError = max(maxError, abs(cpu - g))
        }
        entry.put("agreement", JSONObject().put("maxError", maxError.toDouble())
            .put("maxRgbError", maxRgbError.toDouble())
            .put("violations", violations).put("nonFinite", nonFinite)
            .put("rcViolations", rcViolations).put("excusedFlips", excusedFlips)
            .put("excusedStraddles", excusedStraddles)
            .put("excusedGuard", excusedGuard)
            .put("firstViolations", JSONArray(first))
            .put("firstExcusedStraddles", JSONArray(firstExcused)))
        if (nonFinite > 0) failures.add("$label agreement non-finite=$nonFinite")
        if (violations > 0) failures.add("$label agreement violations=$violations maxError=$maxError")
        if (rcViolations > 0) failures.add("$label agreement rcViolations=$rcViolations")
        return maxRgbError to failures
    }

    private fun agreement(into: JSONObject, output: RawSrGpuOutput, expected: RawSrBayerMerge.MergeResult,
                        label: String,
                        frames: List<RawSrBayerMerge.MergeFrame> = emptyList()): Pair<Float, MutableList<String>> {
        val w = output.width; val h = output.height
        val refNum = readTexture(output.refNumeratorTextureId, w, h, GLES30.GL_RGBA)
        val refDen = readTexture(output.refDenominatorTextureId, w, h, GLES30.GL_RGBA)
        val num = readTexture(output.numeratorTextureId, w, h, GLES30.GL_RGBA)
        val den = readTexture(output.denominatorTextureId, w, h, GLES30.GL_RGBA)
        for (i in num.indices) { num[i] += refNum[i]; den[i] += refDen[i] }
        return agreementArrays(into, GpuArrays(
            readTexture(output.mergedTextureId, w, h, GLES30.GL_RGBA), num, den,
            readTexture(output.rcTextureId, w / 2, h / 2, GLES30.GL_RED),
            readTexture(output.fallbackTextureId, w, h, GLES30.GL_RED),
            readTexture(output.oobTextureId, w, h, GLES30.GL_RED), w, h), expected, label, frames)
            .let { (e, f) -> e to f.toMutableList() }
    }

    /** Q2-Q10 on GPU arrays (valid after the Q1 agreement gate).
     * Measurements are always recorded; failures collected, never thrown mid-way,
     * so the JSON export survives a red gate. Returns interior mean Rc + failures. */
    private fun evaluateQuality(entry: JSONObject, ref: FloatArray, merged: FloatArray,
                                den: FloatArray, rc: FloatArray, fallback: FloatArray,
                                oracleRgb: FloatArray,
                                w: Int, h: Int,
                                q3Ratios: MutableMap<Int, Double>): Pair<Double, List<String>> {
        val failures = mutableListOf<String>()
        val b = 8
        fun luma(a: FloatArray, x: Int, y: Int): Float {
            val p = (y * w + x) * 4
            return (a[p] + a[p + 1] + a[p + 2]) / 3f
        }
        for ((name, a) in listOf("merged" to merged, "ref" to ref, "fallback" to fallback))
            if (!a.all(Float::isFinite)) failures.add("4E Q8 $name non-finite")
        if (!den.all(Float::isFinite)) failures.add("4E Q8 den non-finite")
        if (!rc.all(Float::isFinite)) failures.add("4E Q8 rc non-finite")
        // Q2 colour stability.
        val refMean = DoubleArray(3); val mgMean = DoubleArray(3)
        var n = 0
        for (y in b until h - b) for (x in b until w - b) {
            val p = (y * w + x) * 4
            for (c in 0..2) { refMean[c] += ref[p + c]; mgMean[c] += merged[p + c] }
            n++
        }
        val colour = JSONArray()
        for (c in 0..2) {
            val rm = refMean[c] / n; val mm = mgMean[c] / n
            val ratio = abs(mm - rm) / max(rm, 1e-6)
            colour.put(JSONObject().put("channel", c).put("refMean", rm).put("mergedMean", mm).put("relDiff", ratio))
            if (ratio > 0.01) failures.add("4E Q2 channel $c relDiff=$ratio")
        }
        entry.put("colourStability", colour)
        // Q3/Q4 flat/detail 64x64 windows on ref luma. The Q3 window must be a
        // STATIC region: flattest among windows with window-mean-Rc >= 1 (A3).
        val qw0 = w / 2
        fun windowRc(x0: Int, y0: Int): Double {
            var s = 0.0; var nq = 0
            for (qy in y0 / 2 until (y0 + 64) / 2 + 1) for (qx in x0 / 2 until (x0 + 64) / 2 + 1) {
                if (qx in 0 until qw0 && qy in 0 until rc.size / qw0) { s += rc[qy * qw0 + qx]; nq++ }
            }
            return if (nq > 0) s / nq else 0.0
        }
        var flatVar = Double.MAX_VALUE; var flatVarM = 0.0
        var detVar = -1.0; var detVarM = 0.0
        var wx = b; var wy = b
        var fx = b; var fy = b
        var flatRc = 0.0
        var y = b
        while (y + 64 <= h - b) {
            var x = b
            while (x + 64 <= w - b) {
                var s = 0.0; var s2 = 0.0
                for (dy in 0 until 64) for (dx in 0 until 64) {
                    val v = luma(ref, x + dx, y + dy).toDouble(); s += v; s2 += v * v
                }
                val v = (s2 - s * s / 4096.0) / 4096.0
                if (v < flatVar && windowRc(x, y) >= 1.0) { flatVar = v; fx = x; fy = y; flatRc = windowRc(x, y) }
                if (v > detVar) { detVar = v; wx = x; wy = y }
                x += 32
            }
            y += 32
        }
        // No static flat region at all: fall back to global flattest + no-harm gate.
        var staticFlat = flatVar < Double.MAX_VALUE
        if (!staticFlat) {
            y = b
            while (y + 64 <= h - b) {
                var x = b
                while (x + 64 <= w - b) {
                    var s = 0.0; var s2 = 0.0
                    for (dy in 0 until 64) for (dx in 0 until 64) {
                        val v = luma(ref, x + dx, y + dy).toDouble(); s += v; s2 += v * v
                    }
                    val v = (s2 - s * s / 4096.0) / 4096.0
                    if (v < flatVar) { flatVar = v; fx = x; fy = y; flatRc = windowRc(x, y) }
                    x += 32
                }
                y += 32
            }
        }
        fun windowVar(a: FloatArray, x0: Int, y0: Int): Double {
            var s = 0.0; var s2 = 0.0
            for (dy in 0 until 64) for (dx in 0 until 64) {
                val v = luma(a, x0 + dx, y0 + dy).toDouble(); s += v; s2 += v * v
            }
            return (s2 - s * s / 4096.0) / 4096.0
        }
        flatVarM = windowVar(merged, fx, fy); detVarM = windowVar(merged, wx, wy)
        // Q9 support first: Q3 is support-conditioned (amendment 2026-09-11 — see runlog).
        val qw = w / 2; val qh = h / 2; val qb = 4
        var rs = 0.0; var rn = 0
        for (qy in qb until qh - qb) for (qx in qb until qw - qb) { rs += rc[qy * qw + qx]; rn++ }
        val rcMean = rs / rn
        val noiseRatio = flatVarM / max(flatVar, 1e-12)
        // A4: below the measurable-noise floor (1e-5 ~= 3-code std at 10 bit),
        // ratios are sub-code arithmetic; judge absolute no-harm (4e-6 ~= 2 codes).
        val floorDominated = staticFlat && flatVar < 1e-5
        // Amendment 2026-09-11 (Q3 trend): on natural scenes the flattest static
        // window is texture-dominated, so its variance cannot average down and the
        // flat 0.8 bar is unachievable (sea: 1.03/1.00/0.94/0.91 at 2/8/15/30).
        // Per count enforce no-harm (1.05, as for non-static windows); the real
        // denoise bar is the cross-count trend asserted after the loop (Q9-style).
        // 0.8 is kept as a recorded reference, not a failure gate.
        val noiseGate = if (!staticFlat) 1.05 else if (floorDominated) -1.0 else 0.8
        val noisePass = if (!staticFlat) noiseRatio <= 1.05
            else if (floorDominated) abs(flatVarM - flatVar) <= 4e-6
            else noiseRatio <= 1.05
        if (staticFlat && !floorDominated) q3Ratios[entry.getInt("count")] = noiseRatio
        entry.put("flatWindow", JSONObject().put("x", fx).put("y", fy)
            .put("refVar", flatVar).put("mergedVar", flatVarM).put("ratio", noiseRatio)
            .put("windowRc", flatRc).put("staticFlat", staticFlat)
            .put("floorDominated", floorDominated)
            .put("rcMean", rcMean).put("gate", noiseGate))
        if (!noisePass) failures.add("4E Q3 noise ratio=$noiseRatio gate=$noiseGate rcMean=$rcMean refVar=$flatVar")
        entry.put("detailWindow", JSONObject().put("x", wx).put("y", wy)
            .put("refVar", detVar).put("mergedVar", detVarM).put("ratio", detVarM / max(detVar, 1e-12)))
        if (detVarM < 0.9 * detVar) failures.add("4E Q4 detail ratio=${detVarM / max(detVar, 1e-12)}")
        // Q5 directional-edge symmetry on the kernel-merged
        // (non-fallback) region. Fallback pixels carry burst-nearest
        // neighbor samples by design (see Q7); replacing half the frame
        // with neighbor samples shifts the full-frame gradient ratio even
        // for random picks (device-measured control: relDiff ~0.09 at 53%
        // fallback, vs 0.074 for the distance-based picks), so the
        // full-frame ratio cannot gate kernel directionality. The
        // non-fallback region keeps the gate's power where the robust
        // kernel operates (sea count02 non-fallback relDiff = 0.012).
        var rdx = 0.0; var rdy = 0.0; var mdx = 0.0; var mdy = 0.0
        var symN = 0
        for (yy in b until h - b) for (xx in b + 1 until w - b) {
            if (fallback[yy * w + xx] > 0.5f) continue
            symN++
            rdx += abs(luma(ref, xx, yy) - luma(ref, xx - 1, yy)).toDouble()
            mdx += abs(luma(merged, xx, yy) - luma(merged, xx - 1, yy)).toDouble()
        }
        for (yy in b + 1 until h - b) for (xx in b until w - b) {
            if (fallback[yy * w + xx] > 0.5f) continue
            rdy += abs(luma(ref, xx, yy) - luma(ref, xx, yy - 1)).toDouble()
            mdy += abs(luma(merged, xx, yy) - luma(merged, xx, yy - 1)).toDouble()
        }
        val rr = rdx / max(rdy, 1e-12); val mr = mdx / max(mdy, 1e-12)
        entry.put("edgeSymmetry", JSONObject().put("refRatio", rr).put("mergedRatio", mr)
            .put("relDiff", abs(mr - rr) / max(rr, 1e-12)).put("pixels", symN))
        if (symN > 0 && abs(mr - rr) / max(rr, 1e-12) > 0.05) failures.add("4E Q5 edge symmetry relDiff=${abs(mr - rr) / max(rr, 1e-12)}")
        // Q6 saturation exactness on the r==0 (fallback) path only; averaged
        // saturated pixels are genuine merges, not reference copies (A2).
        var sat = 0; var satFb = 0; var satBad = 0
        for (yy in b until h - b) for (xx in b until w - b) {
            val p = (yy * w + xx) * 4
            for (c in 0..2) if (ref[p + c] >= 0.99f) {
                sat++
                if (fallback[yy * w + xx] > 0.5f) {
                    satFb++
                    if (merged[p + c].toRawBits() != ref[p + c].toRawBits()) satBad++
                }
            }
        }
        entry.put("saturation", JSONObject().put("pixels", sat).put("fallbackPixels", satFb).put("mismatches", satBad))
        if (satBad != 0) failures.add("4E Q6 saturation mismatches=$satBad")
        // Q7 fallback pixels must equal the oracle burst-nearest render.
        // Amendment (burst-nearest fallback): fallback no longer copies the
        // reference — it takes the per-channel robustness-weighted nearest
        // blend, so equality with ref is obsolete by design. Q1's rgb gate
        // already pins every fallback pixel to the oracle under the same
        // straddle/tie excuses; Q7 intentionally mirrors that verdict as a
        // dedicated fallback-path view with fraction/worstDiff diagnostics.
        var fb = 0; var fbExcused = 0; var fbWorst = 0f
        for (yy in b until h - b) for (xx in b until w - b) {
            val p = yy * w + xx
            if (fallback[p] > 0.5f) {
                fb++
                for (c in 0..2) {
                    val cpu = oracleRgb[p * 3 + c]
                    val d = abs(merged[p * 4 + c] - cpu)
                    val tol = MERGE_TOL_ABS + MERGE_TOL_REL * abs(cpu)
                    if (d > tol && (isTapWindowStraddle(xx, yy, lastOracleFrames) ||
                            isNearTie(xx, yy, c, lastOracleFrames))) {
                        fbExcused++
                        continue
                    }
                    fbWorst = max(fbWorst, d)
                    if (d > tol)
                        failures.add("4E Q7 fallback ($xx,$yy) ch$c d=$d")
                }
            }
        }
        entry.put("fallback", JSONObject().put("pixels", fb).put("fraction", fb.toDouble() / n).put("worstDiff", fbWorst.toDouble()).put("excused", fbExcused))
        // Q10 borders.
        var borderWorst = 0f
        for (yy in 0 until h) for (xx in 0 until w) {
            if (xx in b until w - b && yy in b until h - b) continue
            val p = (yy * w + xx) * 4
            for (c in 0..2) borderWorst = max(borderWorst, abs(merged[p + c] - ref[p + c]))
        }
        entry.put("borderWorstDiff", borderWorst.toDouble())
        if (borderWorst > 1.0f) failures.add("4E Q10 border worst=$borderWorst")
        entry.put("rcMeanInterior", rcMean)
        return rcMean to failures
    }

    private fun writeRgbBin(file: File, rgba: FloatArray, w: Int, h: Int) {
        require(rgba.size == w * h * 4)
        val out = ByteBuffer.allocate(w * h * 3 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until w * h) { out.putFloat(rgba[i * 4]); out.putFloat(rgba[i * 4 + 1]); out.putFloat(rgba[i * 4 + 2]) }
        file.writeBytes(out.array())
    }

    private fun writeScalarBin(file: File, values: FloatArray) {
        val out = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        out.asFloatBuffer().put(values)
        file.writeBytes(out.array())
    }

    /** Identically-scaled center crops: scale fixed from refOnly, applied to both. */
    private fun writeCrops(dir: File, stem: String, ref: FloatArray, merged: FloatArray, w: Int, h: Int) {
        val size = 128; val x0 = (w - size) / 2; val y0 = (h - size) / 2
        var peak = 1e-6f
        for (y in 0 until size) for (x in 0 until size) {
            val p = ((y0 + y) * w + x0 + x) * 4
            for (c in 0..2) peak = max(peak, ref[p + c])
        }
        fun crop(a: FloatArray): Bitmap {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (y in 0 until size) for (x in 0 until size) {
                val p = ((y0 + y) * w + x0 + x) * 4
                // glReadPixels origin is bottom-left; flip rows for viewing.
                bmp.setPixel(x, size - 1 - y, Color.rgb(
                    ((a[p] / peak).coerceIn(0f, 1f) * 255f).toInt(),
                    ((a[p + 1] / peak).coerceIn(0f, 1f) * 255f).toInt(),
                    ((a[p + 2] / peak).coerceIn(0f, 1f) * 255f).toInt()))
            }
            return bmp
        }
        for ((name, a) in listOf("refonly" to ref, "merged" to merged)) {
            val bmp = crop(a)
            try {
                File(dir, "${stem}_crop_$name.png").outputStream().use { check(bmp.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bmp.recycle() }
        }
    }

    private fun readTexture(texture: Int, width: Int, height: Int, format: Int): FloatArray {
        val framebuffer = IntArray(1)
        GLES30.glGenFramebuffers(1, framebuffer, 0)
        try {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texture, 0)
            assertEquals(GLES30.GL_FRAMEBUFFER_COMPLETE, GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER))
            val channels = if (format == GLES30.GL_RED) 1 else 4
            val bytes = ByteBuffer.allocateDirect(width * height * channels * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, width, height, format, GLES30.GL_FLOAT, bytes)
            assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
            return FloatArray(width * height * channels).also { bytes.asFloatBuffer().get(it) }
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, framebuffer, 0)
        }
    }

    private companion object {
        const val TAG = "RawLensRawSr4E"
        const val MERGE_TOL_ABS = 2e-3f
        const val MERGE_TOL_REL = 2e-3f
        const val RGB_TOL = 0.015f
        /** CPU-double vs GPU-float source may straddle an integer tap-center
         * boundary: on-device CPU/GPU flow agreement is <= ~2e-5 quad-px
         * (2x in source pixels) plus float32 rounding; 1e-4 bands it with margin. */
        const val STRADDLE_BAND = 1e-4
        /** Burst-nearest tie band (squared pixels): source divergence moves
         * tap distance gaps by <= ~1.2e-4 (2x source delta over taps within
         * 1.5px) plus float32 rounding of the dot product; 2e-4 bands it
         * with margin. */
        const val TIE_BAND = 2e-4
    }
}
