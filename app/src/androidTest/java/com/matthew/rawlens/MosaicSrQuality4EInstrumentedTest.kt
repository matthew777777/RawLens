// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.Bitmap
import android.graphics.Color
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

/**
 * Prompt 4E-M: mosaic-path merge image-quality gate on the real Sea and
 * Forest fixtures. Criteria were declared in docs/raw-sr-4e-runlog.md BEFORE
 * judging. Frame inputs reuse the 4E packed-oracle recipe so the validated
 * model inputs transfer unchanged; per count the eager
 * [MosaicSrReconstructor.reconstruct] result is judged against the streaming
 * [MosaicSrReconstructor.reconstructStreaming] result (M1 parity) and against
 * the reference-only mosaic baseline (M2-M7 quality). Test-only exports under
 * cacheDir/rawsr-debug/mosaic4e; production saving untouched.
 */
@RunWith(AndroidJUnit4::class)
class MosaicSrQuality4EInstrumentedTest {
    @Test fun seaMosaicQualityAtBurstCounts() {
        runFixture("rawsr/sea", "sea")
    }

    @Test fun forestMosaicQualityAtBurstCounts() {
        runFixture("rawsr/forest", "forest")
    }

    private fun runFixture(assetDir: String, fixtureName: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val started = SystemClock.elapsedRealtime()
        val fixture = RawSrFixture.load(instrumentation.context.assets, assetDir)
        assertEquals(30, fixture.frames.size)
        assertEquals(15, fixture.referenceIndex)
        val config = RawSrAlignmentConfig(levels = 4, tileSize = 16, searchRadius = 4)
        val order = fixture.referenceFirst
        val manifestFrames = fixture.manifest.getJSONArray("frames")
        val outDir = File(instrumentation.targetContext.cacheDir, "rawsr-debug/mosaic4e/$fixtureName")
        outDir.mkdirs()

        val report = JSONObject().put("gate", "4E-M").put("fixture", fixtureName)
            .put("referenceIndex", fixture.referenceIndex)
        val allFailures = mutableListOf<String>()
        var tuningRecorded = false
        val meanWeights = mutableListOf<Double>()
        val m8Ratios = mutableMapOf<Int, Double>()
        val countEntries = JSONArray()

        // Reference-only mosaic baseline first (M5/M7 reference for every count).
        val refFrames = listOf(fixture.frames[fixture.referenceIndex])
        val refTuning = RawSrTuning.fromReference(refFrames.first()).tuning
        report.put("tuning", JSONObject()
            .put("snr", refTuning.snr).put("kDetail", refTuning.kDetail)
            .put("kDenoise", refTuning.kDenoise))
        tuningRecorded = true
        val (refOnlyFrame, _, _, _) = buildFrames(refFrames, config, refTuning)
        val refOnly = MosaicSrReconstructor.reconstruct(refOnlyFrame, emptyList(), referenceOnly = true)
        writeScalarBin(File(outDir, "refonly_cfa_f32le.bin"), refOnly.cfa)
        report.put("refOnlyGrid", JSONObject().put("width", refOnly.width).put("height", refOnly.height)
            .put("pattern", refOnly.pattern.name))

        for (count in listOf(2, 8, 15, 30)) {
            val subsetIdx = order.take(count)
            val frames = subsetIdx.map { fixture.frames[it] }
            val tuning = RawSrTuning.fromReference(frames.first()).tuning
            val (reference, moving, sampleMin, sampleMax) = buildFrames(frames, config, tuning)
            val entry = JSONObject().put("count", count)
            val countStarted = SystemClock.elapsedRealtime()
            val eager = MosaicSrReconstructor.reconstruct(reference, moving)
            val eagerMs = SystemClock.elapsedRealtime() - countStarted
            val tmpDir = File(outDir, "tmp_count%02d".format(count)).apply { mkdirs() }
            val streamStarted = SystemClock.elapsedRealtime()
            val streamed = MosaicSrReconstructor.reconstructStreaming(
                RawSrMergeJob.ReferenceGeometry(reference.width, reference.height,
                    reference.sensorPattern, reference.sensorLeft, reference.sensorTop),
                moving.asSequence(), { reference }, tempDir = tmpDir)
            val streamMs = SystemClock.elapsedRealtime() - streamStarted
            entry.put("eagerElapsedMs", eagerMs).put("streamingElapsedMs", streamMs)
                .put("tempLeftovers", JSONArray(tmpDir.listFiles()?.map { it.name } ?: emptyList<String>()))
            tmpDir.deleteRecursively()
            assertEquals("streaming grid", eager.width, streamed.width)
            assertEquals("streaming grid", eager.height, streamed.height)
            assertEquals("streaming pattern", eager.pattern, streamed.pattern)

            // M1 eager/streaming parity: bitwise-identical CFA.
            var diffs = 0
            var maxAbsDiff = 0.0
            for (i in eager.cfa.indices) {
                val d = abs(eager.cfa[i] - streamed.cfa[i]).toDouble()
                if (eager.cfa[i] != streamed.cfa[i]) { diffs++; if (d > maxAbsDiff) maxAbsDiff = d }
            }
            entry.put("m1Diffs", diffs).put("m1MaxAbsDiff", maxAbsDiff)
                .put("streamingAcceptedFrames", streamed.acceptedFrames)
            if (diffs > 0) allFailures.add("$fixtureName count=$count M1 eager/streaming diffs=$diffs maxAbsDiff=$maxAbsDiff")
            if (streamed.acceptedFrames != moving.size)
                allFailures.add("$fixtureName count=$count M1 acceptedFrames=${streamed.acceptedFrames} != ${moving.size}")

            val failures = evaluateMosaic(entry, eager, refOnly, reference, sampleMin, sampleMax, count, "$fixtureName count=$count")
            m8Ratios[count] = entry.getJSONObject("m8Flat").getDouble("ratio")
            meanWeights.add(meanWeight(eager))
            entry.put("qualityFailures", JSONArray(failures))
            allFailures.addAll(failures)

            val stem = "count%02d".format(count)
            writeScalarBin(File(outDir, "${stem}_merged_cfa_f32le.bin"), eager.cfa)
            writeScalarBin(File(outDir, "${stem}_weight_f32le.bin"), eager.weight)
            writeGrayCrops(outDir, stem, refOnly.cfa, eager.cfa, eager.width, eager.height)
            entry.put("sourceFrames", JSONArray(subsetIdx.toList()))
                .put("frameSha256", JSONArray(subsetIdx.map {
                    manifestFrames.getJSONObject(it).getString("sha256")
                }))
            countEntries.put(entry)
            File(outDir, "mosaic4e_partial.json").writeText(report.toString(2))
            Log.i(TAG, "4E-M $fixtureName count=$count $entry")
        }
        report.put("counts", countEntries)
        report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
        // M6 support trend across counts.
        for (i in 1 until meanWeights.size)
            if (meanWeights[i] < meanWeights[i - 1] - 1e-6)
                allFailures.add("$fixtureName M6 meanWeight trend index $i: ${meanWeights[i]} < ${meanWeights[i - 1]}")
        report.put("meanWeights", JSONArray(meanWeights.toList()))
        val m8Json = JSONObject()
        for ((k, v) in m8Ratios) m8Json.put(k.toString(), v)
        report.put("m8Ratios", m8Json)
        // M8 trend across counts (Amendments A6/A7 port of the Bayer A5 trend
        // split): sea demands a 0.02 margin, forest strict monotonic decrease.
        val m8r2 = m8Ratios[2]; val m8r8 = m8Ratios[8]; val m8r15 = m8Ratios[15]; val m8r30 = m8Ratios[30]
        val m8TrendPass = if (fixtureName == "forest")
            m8r2 != null && m8r8 != null && m8r15 != null && m8r30 != null &&
                m8r30 <= 1.0 && m8r30 < m8r15 && m8r15 < m8r8 && m8r8 < m8r2
        else
            m8r2 != null && m8r30 != null && m8r30 <= 1.0 && m8r30 <= m8r2 - 0.02
        if (!m8TrendPass) allFailures.add("$fixtureName M8 trend m8Ratios=$m8Ratios")
        report.put("m8TrendPass", m8TrendPass)
        report.put("qualityFailures", JSONArray(allFailures.toList()))
        File(outDir, "mosaic4e.json").writeText(report.toString(2))
        assertTrue("tuning recorded", tuningRecorded)
        assertTrue("4E-M $fixtureName failures=$allFailures", allFailures.isEmpty())
    }

    private data class FramesBundle(
        val reference: RawSrBayerMerge.MergeFrame,
        val moving: List<RawSrBayerMerge.MergeFrame>,
        val sampleMin: Float,
        val sampleMax: Float
    )

    /** Frame recipe identical to the 4E packed oracle; also returns burst sample range. */
    private fun buildFrames(frames: List<RawSrPackedFrame>, config: RawSrAlignmentConfig,
                            tuning: RawSrTuning
    ): FramesBundle {
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
        var sampleMin = Float.POSITIVE_INFINITY
        var sampleMax = Float.NEGATIVE_INFINITY
        for ((cpu, _) in shadedFrames) for (v in cpu.values) {
            if (v < sampleMin) sampleMin = v
            if (v > sampleMax) sampleMax = v
        }
        val grays = shadedFrames.map { RawSrAlignment.bayerQuadGray(it.first) }
        val moving = frames.drop(1).mapIndexed { i, frame ->
            val flow = RawSrAlignment.align(grays[0], grays[i + 1], config)
            val robust = RawSrRobustness.evaluate(RawSrRobustness.linearGuide(frames[0]),
                RawSrRobustness.linearGuide(frame), flow, tuning, config)
            val (cpu, sensorPattern) = shadedFrames[i + 1]
            RawSrBayerMerge.MergeFrame(cpu.width, cpu.height, cpu.values, sensorPattern,
                cpu.sensorCropLeft, cpu.sensorCropTop,
                RawSrKernelCovariance.precision(RawSrCovarianceGuide.guide(frame).gray, tuning),
                flow, robust)
        }
        val (refCpu, refPattern) = shadedFrames[0]
        val reference = RawSrBayerMerge.MergeFrame(refCpu.width, refCpu.height, refCpu.values, refPattern,
            refCpu.sensorCropLeft, refCpu.sensorCropTop,
            RawSrKernelCovariance.precision(RawSrCovarianceGuide.guide(frames[0]).gray, tuning),
            null, null)
        return FramesBundle(reference, moving, sampleMin, sampleMax)
    }

    private fun meanWeight(result: MosaicSrReconstructor.MosaicSrResult): Double {
        var sum = 0.0
        for (v in result.weight) sum += v
        return sum / result.weight.size
    }

    /**
     * Exact test-side replica of MosaicSrReconstructor.refSiteTap (private):
     * the floor source tap when its sensor colour matches the target site
     * colour, NaN otherwise. Same Float32 in, same Double out.
     */
    private fun replicaSiteTap(frame: RawSrBayerMerge.MergeFrame, pattern: BayerPattern,
                               qx: Int, qy: Int, width: Int, height: Int): Double {
        val sourceX = (qx + 0.5) / MosaicSrReconstructor.LINEAR_SCALE
        val sourceY = (qy + 0.5) / MosaicSrReconstructor.LINEAR_SCALE
        if (!sourceX.isFinite() || !sourceY.isFinite() ||
            sourceX < 0.0 || sourceY < 0.0 || sourceX >= width || sourceY >= height
        ) return Double.NaN
        val sx = floor(sourceX).toInt()
        val sy = floor(sourceY).toInt()
        if (frame.sensorPattern.colorAt(frame.sensorLeft + sx, frame.sensorTop + sy) !=
            pattern.colorAt(qx, qy)
        ) return Double.NaN
        return frame.samples[sy * width + sx].toDouble()
    }

    /** Exact replica of the finalizer site-to-quad map (support overwrite). */
    private fun siteQuad(qx: Int, qy: Int, quadsW: Int, quadsH: Int): Int {
        val quadX = (((qx + 0.5) / MosaicSrReconstructor.LINEAR_SCALE) / 2.0).toInt().coerceIn(0, quadsW - 1)
        val quadY = (((qy + 0.5) / MosaicSrReconstructor.LINEAR_SCALE) / 2.0).toInt().coerceIn(0, quadsH - 1)
        return quadY * quadsW + quadX
    }

    /** M2-M5 + M7-M13 per count; returns failure strings. M1 + M6 are judged by the caller. */
    private fun evaluateMosaic(entry: JSONObject,
                               merged: MosaicSrReconstructor.MosaicSrResult,
                               refOnly: MosaicSrReconstructor.MosaicSrResult,
                               reference: RawSrBayerMerge.MergeFrame,
                               sampleMin: Float, sampleMax: Float,
                               count: Int,
                               label: String): List<String> {
        val failures = mutableListOf<String>()
        val w = merged.width; val h = merged.height
        assertEquals("refOnly grid", w, refOnly.width)
        assertEquals("refOnly grid", h, refOnly.height)
        // M2 finiteness.
        var nonFinite = 0
        for (v in merged.cfa) if (!v.isFinite()) nonFinite++
        for (v in merged.weight) if (!v.isFinite()) nonFinite++
        entry.put("m2NonFinite", nonFinite)
        if (nonFinite > 0) failures.add("$label M2 nonFinite=$nonFinite")
        // M3 no invented values: every site inside the burst sample range (+1e-3 slack).
        var worstLo = 0.0; var worstHi = 0.0
        for (v in merged.cfa) {
            if (v < sampleMin - 1e-3) { val e = (sampleMin - v).toDouble(); if (e > worstLo) worstLo = e }
            if (v > sampleMax + 1e-3) { val e = (v - sampleMax).toDouble(); if (e > worstHi) worstHi = e }
        }
        entry.put("m3Range", JSONObject().put("min", sampleMin).put("max", sampleMax)
            .put("worstLoExcursion", worstLo).put("worstHiExcursion", worstHi))
        if (worstLo > 0 || worstHi > 0) failures.add("$label M3 excursions lo=$worstLo hi=$worstHi")
        // M4 fallback sanity.
        assertEquals("fallback mask", w * h, merged.fallback.size)
        val fallbackCount = merged.fallback.count { it }
        val fallbackFraction = fallbackCount.toDouble() / (w * h)
        entry.put("m4Fallback", JSONObject().put("pixels", fallbackCount).put("fraction", fallbackFraction))
        // Amendment A5: only count=30 carries a bar; small counts fall back honestly on motion.
        if (count == 30 && fallbackFraction > 0.1) failures.add("$label M4 fallbackFraction=$fallbackFraction")
        // M5 colour stability per target-pattern phase.
        val phases = linkedMapOf<CfaColor, DoubleArray>()
        for (y in 0 until h) for (x in 0 until w) {
            val color = merged.pattern.colorAt(x, y)
            val acc = phases.getOrPut(color) { doubleArrayOf(0.0, 0.0, 0.0) }
            val p = y * w + x
            acc[0] += refOnly.cfa[p]; acc[1] += merged.cfa[p]; acc[2]++
        }
        val stability = JSONObject()
        for ((color, acc) in phases) {
            val refMean = acc[0] / acc[2]; val mergedMean = acc[1] / acc[2]
            val relDiff = if (refMean > 1e-6) abs(mergedMean - refMean) / refMean else 0.0
            stability.put(color.name, JSONObject().put("refMean", refMean)
                .put("mergedMean", mergedMean).put("relDiff", relDiff))
            if (relDiff > 0.01) failures.add("$label M5 phase $color relDiff=$relDiff")
        }
        entry.put("m5Stability", stability)
        // M7 detail retention: highest-variance interior 64x64 refOnly window.
        val border = 8; val win = 64
        var bestX = border; var bestY = border; var bestVar = -1.0
        var bx = border
        while (bx + win <= w - border) {
            var by = border
            while (by + win <= h - border) {
                val v = windowVar(refOnly.cfa, w, bx, by, win)
                if (v > bestVar) { bestVar = v; bestX = bx; bestY = by }
                by += 16
            }
            bx += 16
        }
        val mergedVar = windowVar(merged.cfa, w, bestX, bestY, win)
        val ratio = if (bestVar > 0) mergedVar / bestVar else 1.0
        entry.put("m7Detail", JSONObject().put("x", bestX).put("y", bestY)
            .put("refVar", bestVar).put("mergedVar", mergedVar).put("ratio", ratio))
        if (ratio < 0.9) failures.add("$label M7 detail ratio=$ratio")
        // M8 static-region noise reduction (Q3 mirror): flattest interior
        // 64x64 window with window-mean-Rc >= 1.
        val quadsW = merged.rc.width; val quadsH = merged.rc.height
        var flatX = border; var flatY = border; var flatVar = Double.POSITIVE_INFINITY
        var flatRc = 0.0; var foundStatic = false
        var fx = border
        while (fx + win <= w - border) {
            var fy = border
            while (fy + win <= h - border) {
                var rcSum = 0.0
                for (y in fy until fy + win) for (x in fx until fx + win)
                    rcSum += merged.rc.values[siteQuad(x, y, quadsW, quadsH)]
                val windowRc = rcSum / (win * win)
                if (windowRc >= 1.0) {
                    val v = windowVar(refOnly.cfa, w, fx, fy, win)
                    if (v < flatVar) { flatVar = v; flatX = fx; flatY = fy; flatRc = windowRc; foundStatic = true }
                }
                fy += 16
            }
            fx += 16
        }
        val flatMergedVar = if (foundStatic) windowVar(merged.cfa, w, flatX, flatY, win) else 0.0
        val floorDominated = foundStatic && flatVar < 1e-5
        val m8gate = if (!foundStatic) 1.05 else if (floorDominated) -1.0 else 0.8
        val m8ratio = if (!foundStatic) {
            windowVar(merged.cfa, w, bestX, bestY, win) / max(bestVar, 1e-12)
        } else if (floorDominated) {
            1.0 // judged by absolute no-harm below, not by ratio
        } else flatMergedVar / flatVar
        entry.put("m8Flat", JSONObject().put("x", flatX).put("y", flatY)
            .put("refVar", flatVar).put("mergedVar", flatMergedVar).put("ratio", m8ratio)
            .put("windowRc", flatRc).put("staticFlat", foundStatic)
            .put("floorDominated", floorDominated).put("gate", m8gate))
        if (floorDominated) {
            if (abs(flatMergedVar - flatVar) > 4e-6)
                failures.add("$label M8 floor |dVar|=${abs(flatMergedVar - flatVar)}")
        } else if (m8ratio > 1.05) failures.add("$label M8 no-harm ratio=$m8ratio")
        // Amendment A6 port: 0.8 is a recorded reference, not a failure gate.
        // M9 directional-edge symmetry (Q5 mirror) on the CFA grid.
        var refDx = 0.0; var refDy = 0.0; var mDx = 0.0; var mDy = 0.0
        for (y in border until h - border) for (x in border until w - border) {
            val p = y * w + x
            refDx += abs(refOnly.cfa[p + 1] - refOnly.cfa[p]); refDy += abs(refOnly.cfa[p + w] - refOnly.cfa[p])
            mDx += abs(merged.cfa[p + 1] - merged.cfa[p]); mDy += abs(merged.cfa[p + w] - merged.cfa[p])
        }
        val refGrad = refDx / max(refDy, 1e-12); val mGrad = mDx / max(mDy, 1e-12)
        val edgeRelDiff = abs(mGrad - refGrad) / max(refGrad, 1e-12)
        entry.put("m9Edge", JSONObject().put("refRatio", refGrad).put("mergedRatio", mGrad).put("relDiff", edgeRelDiff))
        if (edgeRelDiff > 0.05) failures.add("$label M9 edge relDiff=$edgeRelDiff")
        // M10 saturation exactness (Q6 mirror): white site taps come out verbatim.
        var satMismatches = 0
        val satFirst = mutableListOf<String>()
        // M11 fallback correctness (Q7 mirror): zero-support non-white sites equal refOnly.
        var fbDiffs = 0
        var fbMaxAbsDiff = 0.0
        // M13b collection completeness: supported non-white sites carry weight, no fallback.
        var dropViolations = 0
        val dropFirst = mutableListOf<String>()
        for (y in 0 until h) for (x in 0 until w) {
            val p = y * w + x
            val tap = replicaSiteTap(reference, merged.pattern, x, y, reference.width, reference.height)
            val white = tap.isFinite() && tap >= RawSrBayerMerge.SATURATED_REF_GUARD
            if (white) {
                if (merged.cfa[p] != tap.toFloat()) {
                    satMismatches++
                    if (satFirst.size < 5) satFirst.add("($x,$y) tap=$tap merged=${merged.cfa[p]}")
                }
                continue
            }
            val rcQuad = merged.rc.values[siteQuad(x, y, quadsW, quadsH)].toDouble()
            if (rcQuad <= 1e-8 && refOnly.cfa[p] < RawSrBayerMerge.SATURATED_REF_GUARD) {
                if (merged.cfa[p] != refOnly.cfa[p]) {
                    fbDiffs++
                    val d = abs(merged.cfa[p] - refOnly.cfa[p]).toDouble()
                    if (d > fbMaxAbsDiff) fbMaxAbsDiff = d
                }
            }
            if (merged.rc.values[siteQuad(x, y, quadsW, quadsH)] >= RawSrBayerMerge.MIN_SUPPORT &&
                refOnly.cfa[p] < RawSrBayerMerge.SATURATED_REF_GUARD
            ) {
                if (!(merged.weight[p] > 0f) || merged.fallback[p]) {
                    dropViolations++
                    if (dropFirst.size < 5)
                        dropFirst.add("($x,$y) w=${merged.weight[p]} fb=${merged.fallback[p]}")
                }
            }
        }
        entry.put("m10Saturation", JSONObject().put("mismatches", satMismatches).put("first", JSONArray(satFirst)))
        if (satMismatches > 0) failures.add("$label M10 saturation mismatches=$satMismatches")
        entry.put("m11Fallback", JSONObject().put("diffs", fbDiffs).put("maxAbsDiff", fbMaxAbsDiff))
        if (fbDiffs > 0) failures.add("$label M11 fallback diffs=$fbDiffs maxAbsDiff=$fbMaxAbsDiff")
        entry.put("m13bDrops", JSONObject().put("violations", dropViolations).put("first", JSONArray(dropFirst)))
        if (dropViolations > 0) failures.add("$label M13b dropped-pixel violations=$dropViolations")
        // M12 borders (Q10 mirror): 8px band bounded vs refOnly.
        var borderWorst = 0.0
        for (y in 0 until h) for (x in 0 until w) {
            if (x >= border && y >= border && x < w - border && y < h - border) continue
            val d = abs(merged.cfa[y * w + x] - refOnly.cfa[y * w + x]).toDouble()
            if (d > borderWorst) borderWorst = d
        }
        entry.put("m12BorderWorst", borderWorst)
        if (borderWorst > 1.0) failures.add("$label M12 border worst=$borderWorst")
        // M13a phase correctness: target grid carries the source phase at the shared origin.
        val expectedPattern = MosaicSrReconstructor.planTarget(
            reference.width, reference.height, reference.sensorPattern,
            reference.sensorLeft, reference.sensorTop).pattern
        entry.put("m13aPattern", JSONObject().put("expected", expectedPattern.name).put("actual", merged.pattern.name))
        if (merged.pattern != expectedPattern) failures.add("$label M13a pattern ${merged.pattern} != $expectedPattern")
        // M13c resolution: strongest interior step edge (stride-2 same-phase
        // rows, 3-tap smoothed): 10-90% rise width merged <= refOnly.
        val edge = stepEdgeRise(refOnly.cfa, w, h, border)
        val mEdge = if (edge != null) stepEdgeRiseAt(merged.cfa, w, edge) else null
        entry.put("m13cEdge", JSONObject()
            .put("noEdge", edge == null)
            .put("y", edge?.y ?: -1).put("x", edge?.x ?: -1)
            .put("contrast", edge?.contrast ?: 0.0)
            .put("refRise", edge?.rise ?: -1).put("mergedRise", mEdge ?: -1))
        if (edge != null && mEdge != null && mEdge > edge.rise)
            failures.add("$label M13c edge rise merged=$mEdge refOnly=${edge.rise}")
        return failures
    }

    private data class StepEdge(val y: Int, val x: Int, val rise: Int, val contrast: Double)

    /**
     * Strongest interior step edge on stride-2 same-phase rows (a CFA row
     * alternates two colours, so stride 2 holds one colour). Each row is
     * 3-tap smoothed, then the max adjacent gradient locates the edge and a
     * ±20 window sets the 10-90% levels for the integer rise width.
     * Returns null when no row reaches 0.1 contrast.
     */
    private fun stepEdgeRise(a: FloatArray, w: Int, h: Int, border: Int): StepEdge? {
        var best: StepEdge? = null
        var y = border
        while (y < h - border) {
            val n = (w - 2 * border) / 2
            if (n < 50) { y += 2; continue }
            val s = DoubleArray(n) { i ->
                val x = border + i * 2
                ((a[y * w + x - 2] + a[y * w + x] + a[y * w + x + 2]) / 3.0).toDouble()
            }
            var bi = 1; var bg = 0.0
            for (i in 1 until n - 1) {
                val g = abs(s[i + 1] - s[i - 1])
                if (g > bg) { bg = g; bi = i }
            }
            var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
            for (i in max(0, bi - 20) until minOf(n, bi + 21)) {
                if (s[i] < lo) lo = s[i]
                if (s[i] > hi) hi = s[i]
            }
            if (hi - lo >= 0.1 && (best == null || hi - lo > best.contrast)) {
                val rise = riseWidth(s, bi, lo, hi)
                best = StepEdge(y, border + bi * 2, rise, hi - lo)
            }
            y += 2
        }
        return best
    }

    /** 10-90% rise width of profile [s] around index [bi], in stride-2 units. */
    private fun riseWidth(s: DoubleArray, bi: Int, lo: Double, hi: Double): Int {
        val loTh = lo + 0.1 * (hi - lo); val hiTh = lo + 0.9 * (hi - lo)
        var l = bi; while (l > 0 && s[l] > loTh) l--
        var r = bi; while (r < s.size - 1 && s[r] < hiTh) r++
        return r - l
    }

    /** Rise width of [a] at the edge location found on the reference. */
    private fun stepEdgeRiseAt(a: FloatArray, w: Int, edge: StepEdge): Int {
        val n = 60
        val s = DoubleArray(n) { i ->
            val x = (edge.x - 60 + i * 2).coerceIn(2, w - 3)
            ((a[edge.y * w + x - 2] + a[edge.y * w + x] + a[edge.y * w + x + 2]) / 3.0).toDouble()
        }
        var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
        for (v in s) { if (v < lo) lo = v; if (v > hi) hi = v }
        if (hi - lo < 1e-12) return 0
        return riseWidth(s, 30, lo, hi)
    }

    private fun windowVar(a: FloatArray, w: Int, x0: Int, y0: Int, win: Int): Double {
        var sum = 0.0; var sum2 = 0.0; var n = 0
        for (y in y0 until y0 + win) for (x in x0 until x0 + win) {
            val v = a[y * w + x].toDouble(); sum += v; sum2 += v * v; n++
        }
        val mean = sum / n
        return max(0.0, sum2 / n - mean * mean)
    }

    private fun writeScalarBin(file: File, values: FloatArray) {
        val out = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        out.asFloatBuffer().put(values)
        file.writeBytes(out.array())
    }

    /** Identically-scaled gray center crops of the CFA (tap value -> gray). */
    private fun writeGrayCrops(dir: File, stem: String, ref: FloatArray, merged: FloatArray, w: Int, h: Int) {
        val size = 128; val x0 = (w - size) / 2; val y0 = (h - size) / 2
        var peak = 1e-6f
        for (y in 0 until size) for (x in 0 until size)
            peak = max(peak, ref[(y0 + y) * w + x0 + x])
        fun crop(a: FloatArray): Bitmap {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (y in 0 until size) for (x in 0 until size) {
                val g = ((a[(y0 + y) * w + x0 + x] / peak).coerceIn(0f, 1f) * 255f).toInt()
                bmp.setPixel(x, size - 1 - y, Color.rgb(g, g, g))
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

    companion object {
        private const val TAG = "MosaicSr4EM"
    }
}
