// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in integration fixture; see tools/hdrplus-auto/README.md. Uses the
 * production estimator, fallback meter and selector, not a Python reimplementation. */
class HdrPlusLocalBurstTest {
    @Test fun evaluateDecodedLocalBursts() {
        val path = System.getenv("RAWLENS_HDR_FIXTURES")
        assumeTrue("Set RAWLENS_HDR_FIXTURES to decoded fixtures", path != null)
        val root = File(path!!)
        val folders = root.listFiles()!!.filter { File(it, "frames.tsv").isFile }.sortedBy { it.name }
        assertTrue(folders.isNotEmpty())
        for (folder in folders) evaluate(folder)
    }

    private fun evaluate(folder: File) {
        val rows = File(folder, "frames.tsv").readLines().map { it.split('\t') }
        val width = rows[0][1].toInt()
        val height = rows[0][2].toInt()
        val frames = rows.indices.map { i -> ByteBuffer.wrap(File(folder, "$i.raw").readBytes()).order(ByteOrder.nativeOrder()) }
        val range = rows.map { it[3].toDouble() - it.drop(4).map(String::toDouble).average() }
        val start = System.nanoTime()
        val noise = frames.indices.map { i -> HdrPlusSceneNoise.measure(frames[i], width, height,
            rows[i].drop(4).map(String::toFloat).toFloatArray(), rows[i][3].toFloat()) }
        val noiseMs = (System.nanoTime() - start) / 1e6
        val stats = frames.indices.map { i -> HdrPlusMotionMeter.frameStats(frames[i], width, height, normalizationRange = range[i]) }
        val deltas = (1 until frames.size).map { i -> HdrPlusMotionMeter.meanAbsDiffNormalized(frames[i-1], frames[i], width*height, normalizationRange = range[i]) }
        val hots = (1 until frames.size).map { i -> HdrPlusMotionMeter.blockHotFraction(frames[i-1], frames[i], width, height, normalizationRange = range[i]) }
        val ratios = (1 until frames.size).map { i -> HdrPlusMotionMeter.blockMismatchRatios(frames[i-1], frames[i], width, height, normalizationRange = range[i])!! }
        val dense = (0 until frames.size-1).map { i ->
            val buf = ByteBuffer.wrap(File(folder, "$i.mad").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer()
            DoubleArray(buf.remaining()).also { buf.get(it) }
        }
        val (mw, mh) = HdrPlusMotionMeter.mapCells(width, height)
        val m = HdrPlusMotionMeter.summarize(deltas, List(deltas.size) { 0L }, List(deltas.size) { 0L },
            List(deltas.size) { Double.NaN }, hots, stats.map { it.meanLevel }, stats.map { it.sharpness },
            ratios, mw, mh, dense, frameRelativeNoise = noise)
        val d = HdrPlusAutoSelect.select(m, frames.size, 0, null, null, frames.size/2)
        val maps = d.strengthMaps!!
        val out = ByteBuffer.allocate(maps.sumOf { it.size } * 4).order(ByteOrder.LITTLE_ENDIAN)
        maps.forEach { map -> map.forEach { assertTrue(it.isFinite()); out.putFloat(it) } }
        File(folder, "strengths.bin").writeBytes(out.array())
        File(folder, "decision.txt").writeText(listOf(
            "strength=${d.strength}", "hq=${d.highQuality}", "ref=${d.refIndex}",
            "kept=${d.keepIndices.joinToString(",")}", "frameStrengths=${d.frameStrengths!!.joinToString(",")}",
            "relativeNoise=${noise.joinToString(",")}", "noiseMs=$noiseMs", "hot=${hots.joinToString(",")}",
            "mapMean=${maps.map { it.average() }.joinToString(",")}",
            "mapMin=${maps.minOf { it.minOrNull()!! }}", "mapMax=${maps.maxOf { it.maxOrNull()!! }}",
            "log=${d.logLine(frames.size)}").joinToString("\n"))
        println("${folder.name}: ${d.logLine(frames.size)} noise=$noise meterMs=$noiseMs")
    }
}
