// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Measures what the microphone path really delivers per sample format, to
 * decide whether 32-bit float (or 24-bit) audio is worth offering. Opt-in:
 *
 *   adb shell am instrument -w -e class com.matthew.rawlens.AudioFormatProbeDeviceTest \
 *       -e rawlensAudioProbe true com.matthew.rawlens.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -s AudioProbe
 *
 * Each source x format records [SECONDS] s; clap loudly when the log says
 * "CLAP NOW" (half way) and keep the room quiet otherwise. Per config it logs
 * one JSON line: whether it records, the effective bit depth (a float or
 * 24-bit stream whose samples are all multiples of 2^-15 is 16-bit audio in a
 * bigger container), peaks above full scale (the only case where float beats
 * integer PCM), the clipped fraction, overall RMS and the quiet noise floor.
 */
@RunWith(AndroidJUnit4::class)
class AudioFormatProbeDeviceTest {
    private val args = InstrumentationRegistry.getArguments()

    @Test fun probeAudioFormats() {
        assumeTrue("opt-in: -e rawlensAudioProbe true", args.getString("rawlensAudioProbe") == "true")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant ${ctx.packageName} android.permission.RECORD_AUDIO").close()
        val sources = listOf("CAMCORDER" to MediaRecorder.AudioSource.CAMCORDER,
            "UNPROCESSED" to MediaRecorder.AudioSource.UNPROCESSED)
        val formats = buildList {
            add("PCM16" to AudioFormat.ENCODING_PCM_16BIT)
            add("FLOAT" to AudioFormat.ENCODING_PCM_FLOAT)
            if (Build.VERSION.SDK_INT >= 31) {
                add("PCM24" to AudioFormat.ENCODING_PCM_24BIT_PACKED)
                add("PCM32" to AudioFormat.ENCODING_PCM_32BIT)
            }
        }
        Log.i(TAG, "device ${Build.MANUFACTURER} ${Build.MODEL} (${Build.HARDWARE}), SDK ${Build.VERSION.SDK_INT}")
        for ((sourceName, source) in sources) for ((formatName, encoding) in formats) {
            val result = probe(source, encoding)
            result.put("source", sourceName).put("format", formatName)
            Log.i(TAG, result.toString())
        }
    }

    private fun probe(source: Int, encoding: Int): JSONObject {
        val out = JSONObject()
        for ((mask, channels) in listOf(AudioFormat.CHANNEL_IN_STEREO to 2, AudioFormat.CHANNEL_IN_MONO to 1)) {
            val minBuf = AudioRecord.getMinBufferSize(RATE, mask, encoding)
            if (minBuf <= 0) continue
            val rec = try {
                AudioRecord(source, RATE, mask, encoding, minBuf * 4)
            } catch (_: Exception) {
                continue
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); continue }
            try {
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) continue
                val samples = capture(rec, encoding, channels)
                out.put("records", true).put("channels", channels)
                analyse(samples, encoding, out)
                return out
            } catch (e: Exception) {
                out.put("error", e.toString())
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
            }
        }
        return out.put("records", false)
    }

    /** Normalised samples (full scale = 1.0; float may exceed it), interleaved. */
    private fun capture(rec: AudioRecord, encoding: Int, channels: Int): FloatArray {
        val total = RATE * SECONDS * channels
        val out = FloatArray(total)
        var n = 0
        var clapAnnounced = false
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            else -> 4
        }
        val chunk = 4800 * channels
        val shorts = ShortArray(chunk)
        val floats = FloatArray(chunk)
        val bytes = ByteArray(chunk * bytesPerSample)
        while (n < total) {
            if (!clapAnnounced && n >= total / 2) { Log.i(TAG, ">>> CLAP NOW <<<"); clapAnnounced = true }
            val want = min(chunk, total - n)
            when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT -> {
                    val r = rec.read(shorts, 0, want); if (r <= 0) break
                    for (i in 0 until r) out[n + i] = shorts[i] / 32768f
                    n += r
                }
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    val r = rec.read(floats, 0, want, AudioRecord.READ_BLOCKING); if (r <= 0) break
                    System.arraycopy(floats, 0, out, n, r)
                    n += r
                }
                else -> {
                    val r = rec.read(bytes, 0, want * bytesPerSample); if (r <= 0) break
                    val bb = ByteBuffer.wrap(bytes, 0, r).order(ByteOrder.LITTLE_ENDIAN)
                    val count = r / bytesPerSample
                    for (i in 0 until count) {
                        out[n + i] = if (bytesPerSample == 3) {
                            val v = (bb.get().toInt() and 0xff) or ((bb.get().toInt() and 0xff) shl 8) or (bb.get().toInt() shl 16)
                            v / 8388608f
                        } else bb.int / 2147483648f
                    }
                    n += count
                }
            }
        }
        return out.copyOf(n)
    }

    private fun analyse(s: FloatArray, encoding: Int, out: JSONObject) {
        var peak = 0f
        var above = 0
        var atFull = 0
        var sum2 = 0.0
        for (v in s) {
            val a = abs(v)
            peak = max(peak, a)
            if (a > 1.0f) above++
            if (a >= 0.999f) atFull++
            sum2 += v.toDouble() * v
        }
        // Quiet floor: lowest RMS over 100 ms windows (skips the clap).
        val win = RATE / 10 * (s.size / (RATE * SECONDS)).coerceAtLeast(1)
        var floor = Double.MAX_VALUE
        var i = 0
        while (i + win <= s.size) {
            var w2 = 0.0
            for (k in i until i + win) w2 += s[k].toDouble() * s[k]
            floor = min(floor, sqrt(w2 / win))
            i += win
        }
        fun db(x: Double) = if (x <= 0) -200.0 else 20 * log10(x)
        // Effective bits: the finest power-of-two grid all non-zero samples sit on.
        var bits = 0
        for (b in 8..24) {
            val q = (1 shl (b - 1)).toDouble()
            if (s.all { v -> val x = v * q; abs(x - Math.rint(x)) < 1e-6 }) { bits = b; break }
        }
        out.put("frames", s.size)
            .put("peak", peak.toDouble())
            .put("peakDbfs", db(peak.toDouble()))
            .put("samplesAboveFullScale", above)
            .put("clippedFraction", atFull.toDouble() / s.size)
            .put("rmsDbfs", db(sqrt(sum2 / s.size)))
            .put("noiseFloorDbfs", db(floor))
            .put("effectiveBits", if (bits == 0) ">24" else bits.toString())
            .put("encoding", encoding)
    }

    private companion object {
        const val TAG = "AudioProbe"
        const val RATE = 48000
        const val SECONDS = 6
    }
}
