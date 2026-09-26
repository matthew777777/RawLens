// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P2 record test: records a real `.mcraw` per crop (Open Gate / 16:9 /
 * 2.39:1 / 2.00:1) with [RawVideoRecorder] and validates the container
 * end-to-end without the decoder:
 * - 8-byte magic `MOTION 3`
 * - footer item (type 0, len 16): magic `0x8A905612`, frame count, index offset
 * - frame index timestamps strictly increasing, count matches footer,
 *   recording-relative (first frame ~0, never boot-time)
 * - achieved fps in a sane record-mode band
 *
 * Opt-in: `-e rawlensVideoRecord true [-e seconds 3]`. Writes app-private
 * files (no MediaStore), deleted after validation.
 *
 * P4 streams: with mic + motion granted, every clip must also carry an
 * audio index (item 4) and a gyro index (item 8) whose sample timestamps
 * sit on the video frame timeline (loosely: within the frame span ±1s,
 * not sample-exact). Accel (12) is asserted only when present. Motion
 * data chunks (9/13) must number <= frames + 1 — motioncam-decoder
 * rejects more ("Invalid gyro index" aborts the open, hiding audio too).
 */
@RunWith(AndroidJUnit4::class)
class RawVideoRecordTest {
    @Test fun recordAllCropsProduceValidMcraw() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensVideoRecord true to open the camera and record",
            args.getString("rawlensVideoRecord") == "true"
        )
        assumeTrue("cinemaraw native library unavailable", CinemaRawWriter.available)
        val seconds = args.getString("seconds", "3").toInt().coerceIn(2, 10)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation
            .executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA").close()
        instrumentation.uiAutomation
            .executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO").close()

        val manager = context.getSystemService(CameraManager::class.java)
        val cameraId = manager.cameraIdList.first { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        val chars = manager.getCameraCharacteristics(cameraId)
        val size = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.maxByOrNull { it.width * it.height }
            ?: error("No RAW_SENSOR sizes")
        val model = "${Build.MANUFACTURER} ${Build.MODEL}"

        for (crop in VideoCrop.entries) {
            val file = File(context.cacheDir, "p2_${crop.name.lowercase()}.mcraw")
            if (file.exists()) file.delete()
            val recorder = RawVideoRecorder(manager, cameraId, size, context)
            recorder.crop = crop
            val resolved = crop.resolve(size.width, size.height)
            val containerMeta =
                "{\"UniqueCameraModel\":\"$model\",\"width\":${size.width}," +
                    "\"height\":${size.height},\"crop\":\"${crop.name}\"," +
                    "\"fps\":${RawVideoRecorder.FPS},\"sensorOrientation\":" +
                    "${chars.get(CameraCharacteristics.SENSOR_ORIENTATION)}," +
                    "\"encoder\":\"RawLens-P2\"}"
            val t0 = SystemClock.elapsedRealtime()
            recorder.start(file, containerMeta)
            repeat(seconds * 2) {
                SystemClock.sleep(500)
                val snap = recorder.snapshot()
                Log.i(
                    TAG, "SPIKE sample ${crop.name} t=${snap.elapsedMs}ms " +
                        "acq=${snap.framesAcquired} enc=${snap.framesEncoded} " +
                        "drop=${snap.framesDropped} skip=${snap.commitSkipped} " +
                        "late=${snap.latePurged} " +
                        "fps=${"%.1f".format(snap.fps)} " +
                        "encAvg=${"%.1f".format(snap.encodeMsAvg)}ms " +
                        "encMax=${"%.1f".format(snap.encodeMsMax)}ms " +
                        "waitAvg=${"%.1f".format(snap.resultWaitMsAvg)}ms " +
                        "closeAvg=${"%.1f".format(snap.closeMsAvg)}ms " +
                        "wrAvg=${"%.1f".format(snap.writeMsAvg)}ms " +
                        "wrMax=${"%.1f".format(snap.writeMsMax)}ms " +
                        "q=${snap.queueDepth} r=${snap.reorderDepth} " +
                        "file=${"%.1f".format(snap.fileBytes / 1e6)}MB"
                )
            }
            val stats = recorder.stop()
            val wallMs = SystemClock.elapsedRealtime() - t0

            assertTrue("No frames acquired for $crop", stats.framesAcquired > 0)
            assertTrue("No frames encoded for $crop", stats.framesEncoded > 0)
            assertTrue("File missing for $crop", file.exists() && file.length() > 0)

            validateMetadata(file)
            val footer = validateMcraw(file)
            // Recording-relative, not boot-time: readers do 32-bit ms
            // math that absolute timestamps overflow, breaking audio
            // sync entirely. First committed frame is ~0 (skipped heads
            // shift it up by ~33ms each, never anywhere near 60s).
            assertTrue(
                "Frame timestamps not recording-relative for $crop " +
                    "(first=${footer.firstTs})",
                footer.firstTs < 60_000_000_000L
            )
            assertEquals(
                "Footer count != encoded - latePurged for $crop",
                stats.framesEncoded - stats.latePurged, footer.frameCount
            )
            assertTrue("No audio chunks for $crop: $stats", stats.hasAudio && stats.audioFrames > 0)
            assertTrue("No gyro samples for $crop: $stats", stats.gyroSamples > 0)
            validateStreams(file, footer, crop.name)
            val fps = footer.frameCount * 1000.0 / wallMs
            // Container-truth cadence: (n-1) frames over the committed
            // timestamp span. Gaps stretch the span, so this only screens
            // the rate when nothing was dropped or skipped.
            val spanFps = if (footer.frameCount > 1 && footer.lastTs > footer.firstTs)
                (footer.frameCount - 1) * 1e9 / (footer.lastTs - footer.firstTs)
            else Double.NaN
            Log.i(
                TAG, "SPIKE rec ${crop.label} ${size.width}x${resolved.height} " +
                    "acq=${stats.framesAcquired} enc=${stats.framesEncoded} " +
                    "drop=${stats.framesDropped} skip=${stats.commitSkipped} " +
                    "late=${stats.latePurged} " +
                    "file=${"%.1f".format(file.length() / 1e6)}MB " +
                    "audio=${stats.audioFrames}f/${stats.audioChunks}ch " +
                    "gyro=${stats.gyroSamples} accel=${stats.accelSamples} " +
                    "fps=${"%.1f".format(fps)} spanFps=${"%.1f".format(spanFps)} " +
                    "tsOK=${footer.timestampsIncreasing}"
            )
            if (stats.framesDropped == 0 && stats.commitSkipped == 0 &&
                stats.latePurged == 0 && !spanFps.isNaN()
            ) {
                assertTrue(
                    "Committed cadence $spanFps fps != 30 for $crop",
                    spanFps in 25.0..35.0
                )
            }
            assertTrue("Timestamps not increasing for $crop", footer.timestampsIncreasing)
            // Debuggable (-O0) builds encode ~40x slower; the test then
            // validates the pipeline (no wedge, valid container) at reduced
            // fps, while release (-O3) builds must hold record-mode cadence.
            val debuggable =
                (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (debuggable) {
                assertTrue(
                    "Pipeline stalled in debug for $crop (fps=$fps)",
                    fps > 1.0
                )
            } else {
                assertTrue(
                    "Suspicious fps $fps for $crop (expected 15..35)",
                    fps in 15.0..35.0
                )
            }
            file.delete()
        }
    }

    private fun validateMetadata(file: File) {
        RandomAccessFile(file, "r").use { raf ->
            fun readIntLe() = Integer.reverseBytes(raf.readInt())
            fun readJson(size: Int): JSONObject {
                val bytes = ByteArray(size)
                raf.readFully(bytes)
                return JSONObject(String(bytes, Charsets.UTF_8))
            }
            raf.seek(8)
            assertEquals(3, readIntLe())
            val container = readJson(readIntLe())
            assertTrue(container.getString("sensorArrangment") in listOf("rggb", "grbg", "gbrg", "bggr"))
            assertEquals(4, container.getJSONArray("blackLevel").length())
            assertTrue(container.getDouble("whiteLevel") > container.getJSONArray("blackLevel").getDouble(0))
            assertEquals(9, container.getJSONArray("colorMatrix1").length())
            val audio = container.getJSONObject("extraData")
            assertEquals(48000, audio.getInt("audioSampleRate"))
            // Stereo-first like the audible reference; mono when the
            // device refuses a stereo stream. Either is a real recording.
            assertTrue(
                "Bad audioChannels ${audio.getInt("audioChannels")}",
                audio.getInt("audioChannels") in 1..2
            )
            while (raf.filePointer < raf.length()) {
                val type = readIntLe()
                val size = readIntLe()
                if (type == 2) {
                    raf.seek(raf.filePointer + size)
                    assertEquals(3, readIntLe())
                    val frame = readJson(readIntLe())
                    assertEquals(3, frame.getJSONArray("asShotNeutral").length())
                    assertTrue(frame.getBoolean("metadataMatched"))
                    assertEquals(frame.getLong("timestamp"), frame.getLong("metadataTimestamp"))
                    assertTrue(frame.getLong("exposureTime") > 0)
                    assertTrue(frame.getInt("iso") > 0)
                    return
                }
                raf.seek(raf.filePointer + size)
            }
            error("No frame metadata")
        }
    }

    private data class Footer(
        val frameCount: Int,
        val timestampsIncreasing: Boolean,
        val firstTs: Long,
        val lastTs: Long,
    )

    private fun validateMcraw(file: File): Footer {
        RandomAccessFile(file, "r").use { raf ->
            assertTrue("File too small", raf.length() >= 32)
            val head = ByteArray(8)
            raf.readFully(head)
            // Magic is "MOTION " + version byte 0x03 (not ASCII '3').
            assertEquals("MOTION ", String(head, 0, 7, Charsets.US_ASCII))
            assertEquals(3, head[7].toInt())
            // Footer: [u32 type=0][u32 len=16][u32 magic][u32 count][i64 indexOff]
            raf.seek(raf.length() - 24)
            val tail = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            raf.channel.read(tail)
            tail.flip()
            assertEquals(0, tail.int)
            assertEquals(16, tail.int)
            assertEquals(0x8A905612.toInt(), tail.int)
            val count = tail.int
            assertTrue("Empty container", count > 0)
            val indexOff = tail.long
            assertTrue("Bad index offset", indexOff > 0 && indexOff < raf.length() - 24)
            // Frame index: count x [i64 offset][i64 timestamp]
            raf.seek(indexOff)
            val idx = ByteBuffer.allocate(count * 16).order(ByteOrder.LITTLE_ENDIAN)
            var read = 0
            while (read < idx.capacity()) {
                val n = raf.channel.read(idx)
                if (n < 0) break
                read += n
            }
            assertEquals(count * 16, read)
            idx.flip()
            var prev = Long.MIN_VALUE
            var increasing = true
            var first = 0L
            var last = 0L
            repeat(count) { i ->
                idx.long // offset
                val ts = idx.long
                if (i == 0) first = ts
                last = ts
                if (ts <= prev) increasing = false
                prev = ts
            }
            return Footer(count, increasing, first, last)
        }
    }

    /**
     * P4 stream validation: walk every container item, require audio (4/5)
     * and gyro (8/9) indexes + data, and pin their sample timestamps to the
     * video frame span ±1s. Accel (12/13) is optional hardware.
     */
    private fun validateStreams(file: File, footer: Footer, label: String) {
        val items = mutableMapOf<Int, MutableList<Pair<Long, Int>>>()
        RandomAccessFile(file, "r").use { raf ->
            var off = 8L // past magic
            val end = raf.length() - 24 // footer starts here
            val hdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            while (off + 8 <= end) {
                raf.seek(off)
                hdr.clear()
                if (raf.channel.read(hdr) < 8) break
                hdr.flip()
                val type = hdr.int
                val len = hdr.int
                require(len >= 0) { "Negative item len for type $type in $label" }
                items.getOrPut(type) { mutableListOf() }.add(off + 8 to len)
                off += 8 + (len.toLong() and 0xffffffffL)
            }
        }
        assertTrue("No audio index in $label (types=${items.keys})", items.containsKey(4))
        assertTrue("No audio data in $label", items.containsKey(5))
        assertTrue("No gyro index in $label (types=${items.keys})", items.containsKey(8))
        assertTrue("No gyro data in $label", items.containsKey(9))
        // motioncam-decoder bound: more motion chunks than frames + 1
        // aborts the open ("Invalid gyro/accelerometer index"), hiding
        // audio too. The writer coalesces sensor drains to <= 1 chunk
        // per frame plus a trailing chunk at close.
        val gyroChunks = items.getValue(9).size
        assertTrue(
            "Gyro chunks $gyroChunks exceed frames+1 in $label",
            gyroChunks <= footer.frameCount + 1
        )
        val accelChunks = items[13]?.size ?: 0
        assertTrue(
            "Accel chunks $accelChunks exceed frames+1 in $label",
            accelChunks <= footer.frameCount + 1
        )
        // Tail order for pre-gyro readers (MotionCam Tools v1.0): their
        // scan stops at the first motion item, so no motion data may sit
        // between the last frame and the audio index — else they report
        // no audio chunks and play silent video.
        val lastFrameItem = items.getValue(2).maxOf { it.first - 8 }
        val audioIndexItem = items.getValue(4).first().first - 8
        for (kind in listOf(9, 13)) {
            for ((pos, _) in items[kind] ?: emptyList()) {
                val head = pos - 8
                assertTrue(
                    "Motion data between last frame and audio index in $label",
                    head < lastFrameItem || head > audioIndexItem
                )
            }
        }

        val lo = footer.firstTs - 1_000_000_000L
        val hi = footer.lastTs + 1_000_000_000L
        RandomAccessFile(file, "r").use { raf ->
            // Audio chunk ts: item 6 payload is one i64 per chunk, in order.
            val audioTs = items.getValue(6)
            assertTrue("Audio chunks missing ts in $label", audioTs.isNotEmpty())
            val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            for ((pos, len) in audioTs) {
                assertEquals("Bad audio ts len in $label", 8, len)
                raf.seek(pos)
                buf.clear()
                raf.channel.read(buf)
                buf.flip()
                val ts = buf.long
                assertTrue("Audio ts $ts outside frame span in $label", ts in lo..hi)
            }
            // Audio index (item 4): [u64 count][u64 origin][entries]. The
            // origin is the first chunk's FULL NANOSECOND timestamp —
            // decoders use it as the audio timeline origin.
            val audioIndex = items.getValue(4)
            assertEquals("Audio index split in $label", 1, audioIndex.size)
            raf.seek(audioIndex[0].first)
            val head = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            raf.channel.read(head)
            head.flip()
            assertEquals(
                "Audio index count != data chunks in $label",
                items.getValue(5).size.toLong(), head.long
            )
            raf.seek(audioTs[0].first)
            buf.clear()
            raf.channel.read(buf)
            buf.flip()
            assertEquals(
                "Audio index origin != first chunk ts in $label",
                buf.long, head.long
            )
            // Gyro data: [u32 ver=1][u32 n][n x 24B: i64 ts + 3xf32 + u32 0].
            var checked = 0
            for ((pos, _) in items.getValue(9)) {
                raf.seek(pos)
                val h = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                h.clear()
                raf.channel.read(h)
                h.flip()
                assertEquals("Bad gyro version in $label", 1, h.int)
                val n = h.int
                assertTrue("Empty gyro chunk in $label", n > 0)
                val samples = ByteBuffer.allocate(n * 24).order(ByteOrder.LITTLE_ENDIAN)
                var read = 0
                while (read < samples.capacity()) {
                    val r = raf.channel.read(samples)
                    if (r < 0) break
                    read += r
                }
                assertEquals("Truncated gyro chunk in $label", n * 24, read)
                samples.flip()
                var prev = Long.MIN_VALUE
                repeat(n) {
                    val ts = samples.long
                    samples.float
                    samples.float
                    samples.float
                    assertEquals("Gyro reserved != 0 in $label", 0, samples.int)
                    assertTrue("Gyro ts $ts outside frame span in $label", ts in lo..hi)
                    assertTrue("Gyro ts not increasing in $label", ts >= prev)
                    prev = ts
                    checked++
                }
            }
            assertTrue("No gyro samples checked in $label", checked > 0)
            Log.i(TAG, "SPIKE streams $label audioChunks=${audioTs.size} gyroSamples=$checked")
        }
    }

    companion object {
        private const val TAG = "CinemaRawSpike"
    }
}
