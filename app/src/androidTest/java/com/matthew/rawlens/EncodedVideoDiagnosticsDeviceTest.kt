// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class EncodedVideoDiagnosticsDeviceTest {
    /** Opt-in real-camera headless proof that samples survive into the exported session log. */
    @Test fun headlessRecordingLogsComparedSamples() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Pass rawlensEncodedVideo=true, rcdOnly=true, gpuWriter=false",
            args.getString("rawlensEncodedVideo") == "true" && args.getString("rcdOnly") == "true" &&
                args.getString("gpuWriter") == "false")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LogcatFileWriter.start(context)
        try { EncodedVideoRecordDeviceTest().recordMain10Mp4() }
        finally { LogcatFileWriter.stop() }
        val log = File(context.getExternalFilesDir(null) ?: context.filesDir, LogcatFileWriter.DIR_NAME)
            .listFiles().orEmpty().filter { LogcatFileWriter.isSessionName(it.name) }
            .maxByOrNull { it.lastModified() } ?: error("No diagnostic session log")
        val lines = log.readLines().filter { "EVdiag seq=" in it }
        assertTrue("No diagnostic samples in session file", lines.isNotEmpty())
        for (seq in 0..1) {
            assertTrue("Missing RAW samples for $seq", lines.any { "seq=$seq stage=raw timestamp=" in it && "codes=" in it })
            assertTrue("Missing staging samples for $seq", lines.any { "seq=$seq stage=linear-p010 timestamp=" in it && "codes10=" in it })
            assertTrue("Copy samples must match for $seq", lines.any {
                "seq=$seq stage=" in it && "after-copy" in it && "checked=96 mismatches=0 maxWordDelta=0" in it
            })
        }
        assertFalse("Diagnostics must stop after frame 1", lines.any { !it.contains("seq=0 ") && !it.contains("seq=1 ") })
        File(context.getExternalFilesDir(null), "encoded_video-diagnostics-proof.txt").writeText(lines.joinToString("\n") + "\n")
    }

    @Test fun nativeGridMatchesCpuPlaneSamples() {
        assumeTrue(VfEglImport.available)
        val w = 64; val h = 32
        val byteCount = w * h * 3
        val data = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until h) for (x in 0 until w)
            data.putShort(y * w * 2 + x * 2, (((x + y * 3) % 1024) shl 6).toShort())
        val uvBase = w * h * 2
        for (y in 0 until h / 2) for (x in 0 until w / 2) {
            data.putShort(uvBase + y * w * 2 + x * 4, ((200 + x + y) shl 6).toShort())
            data.putShort(uvBase + y * w * 2 + x * 4 + 2, ((600 - x - y) shl 6).toShort())
        }
        val raw = requireNotNull(VfEglImport.createBlobBayerInput(byteCount / 2, 1))
        try {
            assertEquals(0, VfEglImport.blitBytesToBlob(raw, data, byteCount))
            val descriptor = requireNotNull(VfEglImport.describeBuffer(raw))
            assertEquals(6, descriptor.size)
            assertEquals(byteCount.toLong(), descriptor[0]); assertEquals(1L, descriptor[1])
            val planes = listOf(
                EncodedVideoDiagnostics.Plane(data, w * 2, 2),
                EncodedVideoDiagnostics.Plane(data.duplicate().apply { position(uvBase) }, w * 2, 4),
                EncodedVideoDiagnostics.Plane(data.duplicate().apply { position(uvBase + 2) }, w * 2, 4))
            val actual = requireNotNull(VfEglImport.sampleLinearP010Grid(raw, w, h, 8, 4))
            assertArrayEquals(EncodedVideoDiagnostics.p010(planes, w, h), actual)
            assertNull(VfEglImport.sampleLinearP010Grid(raw, w * 2, h, 8, 4))
        } finally { raw.close() }
    }

    @Test fun nativeGridRefusesToGuessCodecImageLayout() {
        assumeTrue(VfEglImport.available)
        val image = requireNotNull(VfEglImport.createBayerInput(64, 32))
        try { assertNull(VfEglImport.sampleLinearP010Grid(image, 64, 32, 8, 4)) }
        finally { image.close() }
    }
}
