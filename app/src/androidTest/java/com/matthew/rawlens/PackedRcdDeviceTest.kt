// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.HardwareBuffer
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PackedRcdDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext

    private fun initialize(local: Int) {
        assumeTrue("Pass -e rawlensPackedRcd true", InstrumentationRegistry.getArguments()
            .getString("rawlensPackedRcd") == "true")
        val rawShader = context.assets.open("shaders/vf/vf_superpixel.spv").use { it.readBytes() }
        assertEquals(VfVulkan.OK, VfVulkan.initNative(rawShader))
        assumeTrue("Device lacks float16 arithmetic", VfVulkan.supportsF16MathNative())
        val outputOverride = InstrumentationRegistry.getArguments().getString("packedOutputShader")
        val greenOverride = InstrumentationRegistry.getArguments().getString("packedGreenShader")
        if (outputOverride == null && greenOverride == null) {
            assertEquals("Packed RCD init", VfVulkan.OK, VfPackedRcd.init(context, local, local, tuned = false))
        } else {
            val stages = arrayOf("guide", "green", "median", "output").map {
                if (it == "output" && outputOverride != null) java.io.File(outputOverride).readBytes()
                else if (it == "green" && greenOverride != null) java.io.File(greenOverride).readBytes()
                else context.assets.open("shaders/rcd_packed/rcd_$it.spv").use { stream -> stream.readBytes() }
            }.toTypedArray()
            assertEquals("Packed RCD override init", VfVulkan.OK, VfPackedRcd.initNative(stages, local, local))
        }
    }

    @Test fun flatColorsAcrossPatternsAndPartialTiles() {
        initialize(8)
        for (pattern in BayerPattern.entries) for ((w, h) in listOf(64 to 64, 67 to 71, 9 to 11)) {
            val values = floatArrayOf(0.12f, 0.18f, 0.27f)
            val raw = VfEglImport.createBlobBayerInput(w * h, 1)
            assertNotNull(raw)
            val bytes = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder())
            for (y in 0 until h) for (x in 0 until w) {
                bytes.putShort((values[pattern.colorOrdinalAt(x, y)] * 1023f + 0.5f).toInt().toShort())
            }
            bytes.rewind()
            val output = HardwareBufferRef.create(w, h)
            try {
                assertEquals(0, VfEglImport.blitBytesToBlob(raw!!, bytes, w * h * 2))
                val (ip, fp) = VfRcd.packRcd(w, h, 0, 0, VfRcd.channelsFromPattern(pattern),
                    w, 0, floatArrayOf(0f, 0f, 0f, 0f), 1023f)
                repeat(2) { assertEquals("Packed RCD $pattern ${w}x$h", VfVulkan.OK,
                    VfPackedRcd.processNative(raw, output.buffer, ip, fp)) }
                val rgb = VfEglImport.dumpFp16(output.buffer, w, h)
                assertNotNull(rgb)
                assertTrue("nonfinite $pattern", rgb!!.all { it.isFinite() })
                var worst = 0f
                for (y in 4 until h - 4) for (x in 4 until w - 4) for (c in 0..2) {
                    val expected = (values[c] * 1023f + 0.5f).toInt() / 1023f
                    worst = maxOf(worst, kotlin.math.abs(rgb[(y * w + x) * 4 + c] - expected))
                }
                Log.i("PackedRcd", "flat $pattern ${w}x$h worst=$worst")
                assertTrue("flat color error $pattern ${w}x$h: $worst", worst < 0.002f)
            } finally { raw?.close(); output.close() }
        }
    }

    @Test fun fullResolutionStageTimings() {
        for (local in listOf(8, 16)) {
            initialize(local)
            val w = 3840; val h = 2160
            val raw = VfEglImport.createBayerInput(w, h)
            assertNotNull(raw)
            val output = HardwareBufferRef.create(w, h)
            try {
                val pitch = VfEglImport.fillBayerPattern(raw!!, w, h, 0)
                assertTrue(pitch > 0)
                val (ip, fp) = VfRcd.packRcd(w, h, 0, 0, intArrayOf(0, 1, 2, 3), pitch,
                    0, floatArrayOf(0f, 0f, 0f, 0f), 1023f)
                repeat(5) { round ->
                    val start = System.nanoTime()
                    assertEquals("Packed RCD full-res round $round", VfVulkan.OK,
                        VfPackedRcd.processNative(raw, output.buffer, ip, fp))
                    val elapsed = (System.nanoTime() - start) / 1e6
                    Log.i("PackedRcd", "4K local=$local round=$round totalMs=$elapsed stageMs=${VfPackedRcd.timingsNative()?.toList()}")
                }
            } finally { raw?.close(); output.close() }
        }
    }

    @Test fun optimizedOutputMatchesSourcePixels() {
        val path = InstrumentationRegistry.getArguments().getString("packedOutputShader")
        val greenPath = InstrumentationRegistry.getArguments().getString("packedGreenShader")
        initialize(16)
        val original = arrayOf("guide", "green", "median", "output").map {
            inst.context.assets.open("rcd_packed_reference/rcd_$it.spv").use { stream -> stream.readBytes() }
        }.toTypedArray()
        val optimized = arrayOf("guide", "green", "median", "output").map {
            when {
                it == "output" && path != null -> java.io.File(path).readBytes()
                it == "green" && greenPath != null -> java.io.File(greenPath).readBytes()
                else -> context.assets.open("shaders/rcd_packed/rcd_$it.spv").use { stream -> stream.readBytes() }
            }
        }.toTypedArray()
        for (pattern in BayerPattern.entries) {
            val w = 130; val h = 98
            val raw = VfEglImport.createBlobBayerInput(w * h, 1)!!
            val output = HardwareBufferRef.create(w, h)
            try {
                val bytes = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder())
                for (y in 0 until h) for (x in 0 until w) {
                    val c = pattern.colorOrdinalAt(x, y)
                    val code = when {
                        x < 4 -> 1023
                        x in 40..80 -> if ((x / 3 + y / 3 + c) % 2 == 0) 900 else 30
                        c == 0 -> (x * 613 + y * 103) % 1024
                        c == 1 -> (y * 347 + x * 71) % 1024
                        else -> ((x + y) * 57) % 1024
                    }
                    bytes.putShort(code.toShort())
                }
                bytes.rewind()
                assertEquals(0, VfEglImport.blitBytesToBlob(raw, bytes, w * h * 2))
                val (ip, fp) = VfRcd.packRcd(w, h, 0, 0, VfRcd.channelsFromPattern(pattern),
                    w, 0, floatArrayOf(0f, 0f, 0f, 0f), 1023f)
                fun run(stages: Array<ByteArray>, localX: Int = 16, localY: Int = 16): FloatArray {
                    assertEquals(VfVulkan.OK, VfPackedRcd.initNative(stages, localX, localY, tuned = localX == 32))
                    repeat(2) { assertEquals(VfVulkan.OK, VfPackedRcd.processNative(raw, output.buffer, ip, fp)) }
                    return requireNotNull(VfEglImport.dumpFp16(output.buffer, w, h))
                }
                val expected = run(original)
                val actual = run(optimized, 32, 32)
                var worst = 0f; var changed = 0
                for (i in expected.indices) {
                    assertTrue("nonfinite $pattern at $i", actual[i].isFinite())
                    val delta = kotlin.math.abs(expected[i] - actual[i])
                    worst = maxOf(worst, delta)
                    if (delta != 0f) changed++
                }
                Log.i("PackedRcd", "output parity $pattern changed=$changed/${actual.size} max=$worst")
                assertEquals("Optimized output must preserve source pixels for $pattern", 0f, worst, 0f)
            } finally { raw.close(); output.close() }
        }
    }

    @Test fun fusedP010MatchesSeparateGradeAcrossProfiles() = assertFusedP010(128, 96)

    @Test fun scaledP010MatchesSeparateGradeAcrossProfiles() {
        assertFusedP010(64, 48)
        assertFusedP010(96, 54)
        assertFusedP010(256, 192)
    }

    private fun assertFusedP010(outW: Int, outH: Int) {
        initialize(16)
        assertEquals(VfVulkan.OK, VfLogGrade.initGradeNative(context.assets.open("shaders/vf/vf_loggrade.spv").use { it.readBytes() }))
        assertEquals(VfVulkan.OK, VfLogGrade.initGradeYuvNative(context.assets.open("shaders/vf/vf_gradeyuv.spv").use { it.readBytes() }))
        assertEquals(VfVulkan.OK, VfLogGrade.gradeLutUploadNative(VfLogGrade.bakeLut(VfLogGrade.DEFAULT_CONTRAST)))
        val w = 128; val h = 96
        val raw = VfEglImport.createBayerInput(w, h)!!
        val rgb = HardwareBufferRef.create(w, h)
        val split = VfEglImport.createP010(outW, outH)!!
        val fused = VfEglImport.createP010(outW, outH)!!
        try {
            val pitch = VfEglImport.fillBayerPattern(raw, w, h, 0)
            assertTrue(pitch > 0)
            val (ip, fp) = VfRcd.packRcd(w, h, 0, 0, intArrayOf(0, 1, 2, 3), pitch,
                0, floatArrayOf(0f, 0f, 0f, 0f), 1023f)
            repeat(2) { assertEquals(VfVulkan.OK, VfPackedRcd.processNative(raw, rgb.buffer, ip, fp)) }
            val layout = requireNotNull(VfEglImport.describeP010(split))
            val strides = intArrayOf(layout[0], layout[1], outW, outH)
            for (profile in DirectLogProfile.entries) {
                val (dims, grade) = VfLogGrade.packGrade(intArrayOf(outW, outH),
                    floatArrayOf(1.8f, 1f, 1f, 1.3f),
                    floatArrayOf(1.2f, -0.1f, -0.1f, -0.1f, 1.2f, -0.1f, 0f, -0.1f, 1.1f),
                    0.35f, false, profile = profile)
                val fd = VfLogGrade.gradeYuvSubmitNative(rgb.buffer, split, dims, intArrayOf(w, h),
                    grade, strides, 0, 1)
                assertTrue("Separate grade submit $profile: $fd", fd >= 0)
                try {
                    val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                    while (!VfEglImport.pollSyncFd(fd) && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(2)
                    assertTrue("Separate grade completion", VfEglImport.pollSyncFd(fd))
                } finally { VfEglImport.closeSyncFd(fd) }
                assertEquals(VfVulkan.OK, VfPackedRcd.init(context, fused = true))
                assertEquals(VfVulkan.OK, VfPackedRcd.processYuvNative(raw, fused, ip, fp, grade, strides))
                var worst = 0
                for (y in 0 until outH step 7) for (x in 0 until outW step 7) {
                    val expected = requireNotNull(VfEglImport.sampleP010Center(split, strides[0], strides[1], outW, outH, x, y))
                    val actual = requireNotNull(VfEglImport.sampleP010Center(fused, strides[0], strides[1], outW, outH, x, y))
                    for (c in 0..2) worst = maxOf(worst, kotlin.math.abs(expected[c] - actual[c]))
                }
                Log.i("PackedRcd", "fused P010 parity ${outW}x$outH $profile maxRawUnits=$worst")
                assertEquals("Fused output must preserve the separate grade for $profile", 0, worst)
            }
        } finally { raw.close(); rgb.close(); split.close(); fused.close() }
    }

    @Test fun asynchronousSlotsPreservePixelsAndGrades() {
        initialize(16)
        assertEquals(VfVulkan.OK, VfPackedRcd.init(context, fused = true))
        val w = 128; val h = 96
        val inputs = List(3) { VfEglImport.createBayerInput(w, h)!! }
        val outputs = List(3) { VfEglImport.createP010(w, h)!! }
        val reference = VfEglImport.createP010(w, h)!!
        try {
            val layout = requireNotNull(VfEglImport.describeP010(reference))
            val strides = intArrayOf(layout[0], layout[1], w, h)
            val params = inputs.mapIndexed { slot, raw ->
                val pitch = VfEglImport.fillBayerPattern(raw, w, h, slot)
                assertTrue(pitch > 0)
                VfRcd.packRcd(w, h, 0, 0, intArrayOf(0, 1, 2, 3), pitch,
                    0, floatArrayOf(0f, 0f, 0f, 0f), 1023f)
            }
            fun samples(buffer: HardwareBuffer): List<Int> = buildList {
                for (y in 0 until h step 7) for (x in 0 until w step 7)
                    addAll(requireNotNull(VfEglImport.sampleP010Center(buffer, strides[0], strides[1], w, h, x, y)).toList())
            }
            repeat(3) { round ->
                val grades = List(3) { slot -> VfLogGrade.packGrade(intArrayOf(w, h),
                    floatArrayOf(1.8f, 1f, 1f, 1.3f), VfLogGrade.IDENTITY_CCM,
                    (slot - round) * 0.65f, false, profile = DirectLogProfile.ACES).second }
                val shadeDims = List(3) { slot -> intArrayOf(2 + slot, 2 + slot, 0, 0, w, h) }
                val shadeMaps = List(3) { slot -> ShortArray((2 + slot) * (2 + slot) * 4) { c ->
                    DirectLogRecorder.floatToHalfBits(1f + ((slot + round + c % 4) % 5) * .125f)
                } }
                val expected = List(3) { slot ->
                    assertEquals(VfVulkan.OK, VfPackedRcd.processYuvNative(inputs[slot], reference,
                        params[slot].first, params[slot].second, grades[slot], strides, shadeDims[slot], shadeMaps[slot]))
                    samples(reference)
                }
                val fds = List(3) { slot ->
                    VfPackedRcd.submitYuvNative(inputs[slot], outputs[slot], params[slot].first,
                        params[slot].second, grades[slot], strides, slot, shadeDims[slot], shadeMaps[slot]).also {
                        assertTrue("Async slot $slot submit failed: $it", it >= -1)
                    }
                }
                for (slot in 0..2) {
                    val fd = fds[slot]
                    if (fd >= 0) try {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                        while (!VfEglImport.pollSyncFd(fd) && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(1)
                        assertTrue("Async completion slot $slot", VfEglImport.pollSyncFd(fd))
                    } finally { VfEglImport.closeSyncFd(fd) }
                    assertEquals("Async round $round slot $slot changed pixels or grade", expected[slot], samples(outputs[slot]))
                }
            }
        } finally { inputs.forEach { it.close() }; outputs.forEach { it.close() }; reference.close() }
    }

    @Test fun lensShadingUsesSensorCoordinatesBeforeGrade() {
        initialize(16)
        val w = 128; val h = 96; val pitch = 256; val sensorH = 192
        val raw = VfEglImport.createBlobBayerInput(pitch * sensorH, 1)!!
        val rgb = HardwareBufferRef.create(w, h)
        val corrected = HardwareBufferRef.create(w, h)
        val values = floatArrayOf(.12f, .18f, .27f)
        val bytes = ByteBuffer.allocateDirect(pitch * sensorH * 2).order(ByteOrder.nativeOrder())
        for (y in 0 until sensorH) for (x in 0 until pitch)
            bytes.putShort((values[BayerPattern.RGGB.colorOrdinalAt(x, y)] * 1023 + .5f).toInt().toShort())
        bytes.rewind()
        try {
            assertEquals(0, VfEglImport.blitBytesToBlob(raw, bytes, pitch * sensorH * 2))
            val originX = 40; val originY = 24
            val (ip, fp) = VfRcd.packRcd(w, h, originX, originY, intArrayOf(0, 1, 2, 3), pitch,
                0, floatArrayOf(0f, 0f, 0f, 0f), 1023f)
            assertEquals(VfVulkan.OK, VfPackedRcd.processNative(raw, rgb.buffer, ip, fp))
            val baseline = requireNotNull(VfEglImport.dumpFp16(rgb.buffer, w, h))
            val gains = FloatArray(3 * 3 * 4)
            for (y in 0..2) for (x in 0..2) {
                val i = (y * 3 + x) * 4
                gains[i] = 1f + x * .5f + y * .125f
                gains[i + 1] = 1f + y * .25f
                gains[i + 2] = 1.25f + x * .125f
                gains[i + 3] = 1f + y * .5f + x * .125f
            }
            val dims = intArrayOf(3, 3, 16, 12, 240, 180)
            val halves = ShortArray(gains.size) { DirectLogRecorder.floatToHalfBits(gains[it]) }
            val ones = ShortArray(gains.size) { DirectLogRecorder.floatToHalfBits(1f) }
            assertEquals(VfVulkan.OK, VfPackedRcd.processNative(raw, corrected.buffer, ip, fp, dims, ones))
            assertArrayEquals("Identity shading changed source pixels", baseline,
                requireNotNull(VfEglImport.dumpFp16(corrected.buffer, w, h)), 0f)
            assertEquals(VfVulkan.OK, VfPackedRcd.processNative(raw, corrected.buffer, ip, fp, dims, halves))
            val shaded = requireNotNull(VfEglImport.dumpFp16(corrected.buffer, w, h))
            val model = LensShadingModel(3, 3, gains, IntRectSnapshot(16, 12, 240, 180))
            fun gain(x: Int, y: Int): FloatArray {
                val sx = x + originX; val sy = y + originY
                return floatArrayOf(model.gainAt(sx, sy, CfaColor.RED),
                    .5f * (model.gainAt(sx, sy, CfaColor.GREEN, 0) + model.gainAt(sx, sy, CfaColor.GREEN, 1)),
                    model.gainAt(sx, sy, CfaColor.BLUE))
            }
            val points = listOf(0 to 0, 12 to 18, 64 to 48, 100 to 72, 126 to 94)
            for ((x, y) in points) for (c in 0..2) {
                val i = (y * w + x) * 4 + c
                assertEquals("RGB shading ($x,$y) channel $c", baseline[i] * gain(x, y)[c], shaded[i], .002f)
            }
            assertTrue("Map must produce a visible spatial change", shaded[(94 * w + 126) * 4] - baseline[(94 * w + 126) * 4] > .05f)
            val wb = floatArrayOf(1.6f, 1f, 1.2f, 1f)
            val ccm = floatArrayOf(1.15f, -.1f, -.05f, -.05f, 1.1f, -.05f, -.1f, -.05f, 1.15f)
            fun yuv(x: Int, y: Int, profile: DirectLogProfile): FloatArray {
                val g = gain(x, y)
                val neutral = FloatArray(3) { c -> baseline[(y * w + x) * 4 + c] * g[c] * wb[c] }
                val developed = FloatArray(3) { r -> (0..2).sumOf { c -> (ccm[r * 3 + c] * neutral[c]).toDouble() }.toFloat() }
                val encoded = when (profile) {
                    DirectLogProfile.BT709 -> VfLogGrade.encodePixel709(developed)
                    DirectLogProfile.ACES -> VfLogGrade.encodePixelAces(developed)
                    DirectLogProfile.SLOG3 -> VfLogGrade.encodePixelSlog3(developed)
                    DirectLogProfile.HLG -> VfLogGrade.encodePixelHlg(developed)
                }
                val kr = if (profile == DirectLogProfile.HLG) .2627f else .2126f
                val kb = if (profile == DirectLogProfile.HLG) .0593f else .0722f
                val luma = kr * encoded[0] + (1 - kr - kb) * encoded[1] + kb * encoded[2]
                return floatArrayOf(luma, (encoded[2] - luma) / (2 * (1 - kb)) + .5f,
                    (encoded[0] - luma) / (2 * (1 - kr)) + .5f)
            }
            assertEquals(VfVulkan.OK, VfPackedRcd.init(context, fused = true))
            for ((ow, oh) in listOf(w to h, 64 to 48)) {
                val output = VfEglImport.createP010(ow, oh)!!
                try {
                    val layout = requireNotNull(VfEglImport.describeP010(output))
                    val strides = intArrayOf(layout[0], layout[1], ow, oh)
                    for (profile in DirectLogProfile.entries) {
                        val (_, grade) = VfLogGrade.packGrade(intArrayOf(ow, oh), wb, ccm, 0f, false, profile = profile)
                        assertEquals(VfVulkan.OK, VfPackedRcd.processYuvNative(raw, output, ip, fp, grade, strides, dims, halves))
                        var worst = 0
                        for ((px, py) in points) {
                            val x = (px * ow / w) and -2; val y = (py * oh / h) and -2
                            fun source(q: Int, extent: Int, out: Int) = ((q + .5f) * extent / out).toInt()
                            val a = yuv(source(x, w, ow), source(y, h, oh), profile)
                            val b = yuv(source(x + 1, w, ow), source(y, h, oh), profile)
                            val expected = floatArrayOf(a[0], .5f * (a[1] + b[1]), .5f * (a[2] + b[2]))
                            val actual = requireNotNull(VfEglImport.sampleP010Center(output, strides[0], strides[1], ow, oh, x, y))
                            for (c in 0..2) {
                                val code = Math.round(expected[c].coerceIn(0f, 1f) * (if (c == 0) 876 else 896) + 64)
                                worst = maxOf(worst, kotlin.math.abs((actual[c] shr 6) - code))
                            }
                        }
                        Log.i("PackedRcd", "LSC $ow x $oh $profile maxCodeError=$worst")
                        // Bilinear sampler interpolation and CPU libm may differ by a final code.
                        assertTrue("Shading/coordinate/grade mismatch: $profile error=$worst", worst <= 2)
                    }
                } finally { output.close() }
            }
        } finally { raw.close(); rgb.close(); corrected.close() }
    }

    @Test fun fusedFullResolutionStageTimings() {
        initialize(16)
        for ((localX, localY) in listOf(16 to 16, 32 to 32)) {
            val init = VfPackedRcd.init(context, localX, localY, fused = true)
            if (init == VfVulkan.BAD_ARGUMENT) { Log.i("PackedRcd", "unsupported workgroup ${localX}x$localY"); continue }
            assertEquals(VfVulkan.OK, init)
            val w = 3840; val h = 2160
            val raw = VfEglImport.createBayerInput(w, h)!!
            val output = VfEglImport.createP010(w, h)!!
            try {
                val pitch = VfEglImport.fillBayerPattern(raw, w, h, 0)
                assertTrue(pitch > 0)
                val (ip, fp) = VfRcd.packRcd(w, h, 0, 0, intArrayOf(0, 1, 2, 3), pitch,
                    0, floatArrayOf(0f, 0f, 0f, 0f), 1023f)
                val layout = requireNotNull(VfEglImport.describeP010(output))
                val strides = intArrayOf(layout[0], layout[1], w, h)
                val (_, grade) = VfLogGrade.packGrade(intArrayOf(w, h),
                    floatArrayOf(1f, 1f, 1f, 1f), VfLogGrade.IDENTITY_CCM, 0f, false,
                    profile = DirectLogProfile.ACES)
                repeat(7) { round ->
                    val start = System.nanoTime()
                    assertEquals(VfVulkan.OK, VfPackedRcd.processYuvNative(raw, output, ip, fp, grade, strides))
                    val ms = (System.nanoTime() - start) / 1e6
                    Log.i("PackedRcd", "4K ACES/P010 tuned ${localX}x$localY round=$round totalMs=$ms stageMs=${VfPackedRcd.timingsNative()?.toList()}")
                }
            } finally { raw.close(); output.close() }
        }
    }
}
