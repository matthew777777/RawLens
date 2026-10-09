package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * End-to-end HDR develop placement on a real merged float DNG (CPU path:
 * [AdaptiveDevelopmentExposure.analyzeHdr] + [AgxDisplayTransform.scopeCurve]).
 * The GPU demosaic/WB/color chain is gain-exact on neutral content (verified
 * by inverting the shipped JPEG: implied gain x4.04 = +2.01 EV on the
 * ±2EV fixture), so placing the compensated green median through the
 * achromatic AgX curve predicts the shipped JPEG's brightness.
 *
 * Needs HDR_DNG=/path/to/merged_float.dng (skips silently when absent, like
 * [HdrRealBracketValidationTest]); HDR_DISPLAY_EV overrides the fixture's
 * display compensation when the file predates the BaselineExposure tag.
 */
class HdrDevelopValidationTest {
    @Test fun developHdrMergeToNaturalBrightness() {
        val path = System.getenv("HDR_DNG") ?: return
        val file = File(path)
        if (!file.isFile || !file.canRead()) return
        val dng = readFloatDng(file) ?: return
        val baseline = dng.baselineExposureEv
            ?: System.getenv("HDR_DISPLAY_EV")?.toDoubleOrNull()
            ?: 2.0

        val adaptive = AdaptiveDevelopmentExposure.analyzeHdr(dng.cfa, Math.pow(2.0, baseline))
        println("hdr-develop: ${file.name} ${dng.cfa.width}x${dng.cfa.height} " +
            "displayEV=${"%.2f".format(baseline)} adaptiveEV=${"%.2f".format(adaptive.correctionEv)} " +
            "lowKey=${adaptive.lowKey}")
        // The ±2EV office fixture (IMG_20261008_142321_830) meters ~3 stops
        // dark at reference brightness; HDR tuning must lift well past the
        // +1.5 LDR budget without pinning on the clipped window spikes.
        assertTrue("no HDR lift: $adaptive", adaptive.correctionEv > 2.0)
        assertTrue("runaway lift: $adaptive", adaptive.correctionEv < 3.4)
        assertTrue("interior keyed as night: $adaptive", !adaptive.lowKey)

        // Achromatic develop check: compensated green median through the
        // pinned AgX curve (sRGB-encoded) must land in the natural band
        // around middle gray — the shipped JPEG renders this pixel 0.165.
        val totalGain = Math.pow(2.0, baseline + adaptive.correctionEv)
        val greenMedian = dng.greenMedian * totalGain
        val developed = AgxDisplayTransform.scopeCurve(greenMedian.toFloat()).toDouble()
        println("hdr-develop: greenMedianScene=${"%.4f".format(greenMedian)} " +
            "srgb=${"%.4f".format(developed)}")
        writePreview(dng, totalGain)
        assertTrue("developed median too dark: $developed", developed > 0.40)
        assertTrue("developed median blown: $developed", developed < 0.60)

        // Rescued-highlight gradation: the outdoor trees (p99..p99.5 green,
        // several scene-linear units after the lift) must keep visible steps
        // through the HDR shoulder — full strength flattens them white.
        val hdrSettings = JpegOutputSettings()
            .copy(highlightShoulder = RawDevelopmentCoordinator.HDR_HIGHLIGHT_SHOULDER)
        val treeLow = AgxDisplayTransform.scopeCurve(
            (dng.greenP99 * totalGain).toFloat(), hdrSettings)
        val treeHigh = AgxDisplayTransform.scopeCurve(
            (dng.greenP995 * totalGain).toFloat(), hdrSettings)
        println("hdr-develop: treeSteps=${"%.4f".format(treeLow)}..${"%.4f".format(treeHigh)}")
        assertTrue("tree texture flattened: $treeLow..$treeHigh", treeHigh - treeLow >= 0.02f)
        val flatLow = AgxDisplayTransform.scopeCurve((dng.greenP99 * totalGain).toFloat())
        val flatHigh = AgxDisplayTransform.scopeCurve((dng.greenP995 * totalGain).toFloat())
        assertTrue("full shoulder unexpectedly keeps steps (retune HDR ceiling?)",
            flatHigh - flatLow < 0.02f)
    }

    private class FloatDng(
        val cfa: UnpackedRawCfa,
        val greenMedian: Double,
        val greenP99: Double,
        val greenP995: Double,
        val baselineExposureEv: Double?
    )

    private fun readFloatDng(file: File): FloatDng? {
        val bytes = file.readBytes()
        if (bytes.size < 10 || bytes[0] != 'I'.code.toByte() || bytes[1] != 'I'.code.toByte()) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.getShort(2).toInt() != 42) return null
        val ifd = buffer.getInt(4)
        if (ifd <= 0 || ifd + 2 > bytes.size) return null
        val count = buffer.getShort(ifd).toInt() and 0xffff
        var width = 0
        var height = 0
        var stripOffset = -1
        var stripBytes = -1L
        var baseline: Double? = null
        var cfaPattern = byteArrayOf(0, 1, 1, 2)
        for (i in 0 until count) {
            val p = ifd + 2 + i * 12
            if (p + 12 > bytes.size) return null
            val tag = buffer.getShort(p).toInt() and 0xffff
            val type = buffer.getShort(p + 2).toInt() and 0xffff
            val n = buffer.getInt(p + 4)
            val unit = when (type) {
                1, 2, 6, 7 -> 1
                3, 8 -> 2
                4, 9, 11 -> 4
                else -> 8
            }
            val at = if (n * unit <= 4) p + 8 else buffer.getInt(p + 8)
            when (tag) {
                256 -> width = buffer.getInt(if (n * 4 <= 4) p + 8 else buffer.getInt(p + 8))
                257 -> height = buffer.getInt(if (n * 4 <= 4) p + 8 else buffer.getInt(p + 8))
                273 -> stripOffset = buffer.getInt(if (n * 4 <= 4) p + 8 else buffer.getInt(p + 8))
                279 -> stripBytes = buffer.getInt(if (n * 4 <= 4) p + 8 else buffer.getInt(p + 8))
                    .toLong() and 0xffffffffL
                33422 -> {
                    cfaPattern = ByteArray(4) { buffer.get(at + it) }
                }
                50730 -> {
                    baseline = buffer.getInt(at).toDouble() / buffer.getInt(at + 4)
                }
            }
        }
        if (width <= 0 || height <= 0 || stripOffset < 0 || stripBytes <= 0) return null
        if (stripOffset + stripBytes > bytes.size) return null
        if (stripBytes != width.toLong() * height * 4) return null
        val values = FloatArray(width * height)
        buffer.position(stripOffset)
        buffer.asFloatBuffer().get(values)
        val pattern = when (cfaPattern.toList()) {
            listOf<Byte>(0, 1, 1, 2) -> BayerPattern.RGGB
            listOf<Byte>(1, 0, 2, 1) -> BayerPattern.GRBG
            listOf<Byte>(1, 2, 0, 1) -> BayerPattern.GBRG
            listOf<Byte>(2, 1, 1, 0) -> BayerPattern.BGGR
            else -> return null
        }
        val green = ArrayList<Float>(values.size / 2)
        for (y in 0 until height) for (x in 0 until width) {
            val code = cfaPattern[(y % 2) * 2 + (x % 2)].toInt()
            if (code == 1) green.add(values[y * width + x])
        }
        green.sort()
        fun percentile(fraction: Double) =
            if (green.isEmpty()) 0.0 else green[(green.size * fraction).toInt()].toDouble()
        return FloatDng(
            UnpackedRawCfa(width, height, pattern, values, RawCrop(0, 0, width, height)),
            percentile(0.5), percentile(0.99), percentile(0.995), baseline
        )
    }

    private fun writePreview(dng: FloatDng, totalGain: Double) {
        val width = dng.cfa.width
        val height = dng.cfa.height
        // Production HDR shoulder, mirroring developMergedJpeg's resolve.
        val hdrSettings = JpegOutputSettings()
            .copy(highlightShoulder = RawDevelopmentCoordinator.HDR_HIGHLIGHT_SHOULDER)
        val step = 4
        val previewWidth = width / step
        val previewHeight = height / step
        val dir = File("build/hdr-validation").also { it.mkdirs() }
        val out = File(dir, "develop-hdr-preview.pgm")
        FileOutputStream(out).use { stream ->
            stream.write("P5\n$previewWidth $previewHeight\n255\n".toByteArray())
            val row = ByteArray(previewWidth)
            for (py in 0 until previewHeight) {
                // Even-row green phase per Bayer pattern (preview rows are
            // multiples of 4, hence even).
            val greenDx = when (dng.cfa.pattern) {
                BayerPattern.RGGB, BayerPattern.BGGR -> 1
                else -> 0
            }
            for (px in 0 until previewWidth) {
                val x = px * step
                val y = py * step
                val gx = (x + greenDx).coerceAtMost(width - 1)
                val linear = (dng.cfa.values[y * width + gx] *
                    totalGain).toFloat().coerceAtLeast(0f)
                    // scopeCurve already returns sRGB-encoded values.
                    val encoded = AgxDisplayTransform.scopeCurve(linear, hdrSettings)
                    row[px] = (encoded.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                        .coerceIn(0, 255).toByte()
                }
                stream.write(row)
            }
        }
        println("hdr-develop: pgm=${out.absolutePath}")
    }
}
