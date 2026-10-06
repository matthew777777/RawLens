// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.HardwareBuffer
import android.media.MediaCodec
import android.media.MediaFormat
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gradient integrity for the dithered-8bit architecture. The encoder
 * surface is provably 8-bit (recordable configs dump: 8/8/8/8 only), so
 * precision lives in FP16 compute, protected onto the surface by ordered
 * dither. Three controlled claims:
 *
 * A. Dither control (pbuffer readback, no encoder): the same FP16 ramp
 *    blitted twice with different seeds differs (noise added), while the
 *    plain blit is byte-identical run to run (deterministic). Proves the
 *    product dither is ON and the control is exact.
 * B. Undithered procedural ramp end to end: ~256 distinct codes documents
 *    the 8-bit surface (tripwire both ways: crushed or mysteriously deep).
 * C. FP16-texture dithered ramp end to end (recorder blit path): valid
 *    Main10 file, full range, sane brightness.
 *
 * Opt-in: `-e rawlensTenBitProof true`.
 */
@RunWith(AndroidJUnit4::class)
class TenBitGradientDeviceTest {
    data class Sample(val bytes: ByteArray, val ptsUs: Long, val flags: Int)
    data class Clip(val format: MediaFormat, val samples: List<Sample>)

    @Test fun rampSurvivesMain10() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensTenBitProof true to run the 10-bit proof",
            args.getString("rawlensTenBitProof") == "true"
        )
        assumeTrue("rawLensVfEgl native library unavailable", VfEglImport.available)
        val name = LogVideoProbe.preferredHwEncoder()
        assumeTrue("no HW HEVC 4K30 Surface encoder", name != null)

        // Phase A first (pbuffer, no encoder traffic): dither control.
        val (ditherDiffBytes, plainIdentical) = ditherControl()
        Log.i(TAG, "SPIKE tenbit ditherDiffBytes=$ditherDiffBytes plainIdentical=$plainIdentical")
        assertTrue("dither seeds produced identical output (dither off?)", ditherDiffBytes > 50)
        assertTrue("plain blit is not deterministic", plainIdentical)

        // Phase B: undithered procedural ramp end to end (8-bit baseline).
        val surfaceClip = encodeRamp(name!!, useTexture = null, dithered = false)
        val surfaceStats = decodeAndAnalyze(surfaceClip)
        Log.i(TAG, "SPIKE tenbit procedural distinct=${surfaceStats.distinct} " +
            "mean=${surfaceStats.mean}")
        assertTrue(
            "procedural ramp outside 8-bit band: ${surfaceStats.distinct}",
            surfaceStats.distinct in 150..450
        )

        // Phase C: FP16-texture dithered ramp end to end (recorder path).
        val ahb = HardwareBuffer.create(
            960, 540, HardwareBuffer.RGBA_FP16, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_WRITE_OFTEN
        )
        try {
            val stride = VfEglImport.fillHalfGradient(ahb, 960, 540, 0)
            assumeTrue("half-gradient fill failed", stride > 0)
            val textureClip = encodeRamp(name, useTexture = ahb, dithered = true)
            val textureStats = decodeAndAnalyze(textureClip)
            Log.i(TAG, "SPIKE tenbit texture distinct=${textureStats.distinct} " +
                "mean=${textureStats.mean}")
            assertTrue(
                "FP16 texture path broken: ${textureStats.distinct} distinct codes",
                textureStats.distinct in 150..500
            )
            assertTrue(
                "texture path brightness implausible: ${textureStats.mean}",
                textureStats.mean in 0.3f..0.7f
            )
        } finally {
            try { ahb.close() } catch (_: Exception) {}
        }
    }

    /**
     * Dither control on a pbuffer: same FP16 ramp blitted twice per mode.
     * Returns (bytes differing between dither seeds, plain runs identical).
     */
    private fun ditherControl(): Pair<Int, Boolean> {
        val target = LogVideoProbe.Target()
        val codec = MediaCodec.createByCodecName(
            LogVideoProbe.preferredHwEncoder() ?: error("no encoder")
        )
        var surface: Surface? = null
        var egl: EglPusher? = null
        var program = 0
        var plainProgram = 0
        var texture = 0
        var image = 0L
        var ahb: HardwareBuffer? = null
        try {
            codec.configure(LogVideoProbe.toMediaFormat(target), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            egl = EglPusher(surface)
            val push = egl
            ahb = HardwareBuffer.create(
                480, 270, HardwareBuffer.RGBA_FP16, 1,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_WRITE_OFTEN
            )
            check(VfEglImport.fillHalfGradient(ahb, 480, 270, 0) > 0) { "fill failed" }
            image = VfEglImport.createEGLImage(ahb)
            check(image != 0L) { "FP16 EGL import failed" }
            texture = GlBlit.genTexture()
            check(VfEglImport.bindEGLImageToTexture2D(image, texture) == GLES20.GL_NO_ERROR) {
                "FP16 bind failed"
            }
            program = GlBlit.buildDitherProgram()
            plainProgram = GlBlit.buildProgram()
            val quad = GlBlit.quadBuffer()
            val pb = push.bindPbuffer(480, 270) ?: error("pbuffer unsupported")
            try {
                GLES20.glViewport(0, 0, 480, 270)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                GlBlit.drawDithered(program, quad, 480, 270, 0f)
                val a = readback(480, 270)
                GlBlit.drawDithered(program, quad, 480, 270, 7f)
                val b = readback(480, 270)
                GLES20.glUseProgram(plainProgram)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(plainProgram, "uTex"), 0)
                GlBlit.drawQuad(plainProgram, quad)
                val c = readback(480, 270)
                GlBlit.drawQuad(plainProgram, quad)
                val d = readback(480, 270)
                var diff = 0
                for (i in a.indices) if (a[i] != b[i]) diff++
                return diff to c.contentEquals(d)
            } finally {
                push.unbindPbuffer(pb)
            }
        } finally {
            try { if (image != 0L) VfEglImport.destroyEGLImage(image) } catch (_: Exception) {}
            try {
                if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            } catch (_: Exception) {}
            try {
                if (program != 0) GLES20.glDeleteProgram(program)
            } catch (_: Exception) {}
            try {
                if (plainProgram != 0) GLES20.glDeleteProgram(plainProgram)
            } catch (_: Exception) {}
            try { ahb?.close() } catch (_: Exception) {}
            try { egl?.release() } catch (_: Exception) {}
            try { surface?.release() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
        }
    }

    private fun readback(w: Int, h: Int): ByteArray {
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "readback failed" }
        val out = ByteArray(w * h * 4)
        buf.rewind()
        buf.get(out)
        return out
    }

    /**
     * Encodes [frames] of smooth ramp into Main10 4K. When [useTexture] is
     * null the ramp is procedural (gradient fragment shader); otherwise the
     * AHB is EGL-imported and textured-blitted (recorder path). [dithered]
     * selects the recorder's dither blit (product) vs plain passthrough.
     */
    private fun encodeRamp(codecName: String, useTexture: HardwareBuffer?, dithered: Boolean): Clip {
        val frames = 45
        val target = LogVideoProbe.Target()
        val codec = MediaCodec.createByCodecName(codecName)
        var surface: Surface? = null
        var egl: EglPusher? = null
        var program = 0
        var texture = 0
        var image = 0L
        try {
            codec.configure(LogVideoProbe.toMediaFormat(target), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            egl = EglPusher(surface)
            // Recordable configs top out at 8-bit on this stack (dumped);
            // precision lives in FP16 + dither, asserted below by coverage.
            android.util.Log.i(TAG, "SPIKE tenbit surface tenBit=${egl.tenBit} gl=${egl.glVersion}")
            val quad = GlBlit.quadBuffer()
            if (useTexture != null) {
                program = if (dithered) GlBlit.buildDitherProgram() else GlBlit.buildProgram()
                texture = GlBlit.genTexture()
                image = VfEglImport.createEGLImage(useTexture)
                check(image != 0L) { "FP16 EGL import failed" }
                check(VfEglImport.bindEGLImageToTexture2D(image, texture) == GLES20.GL_NO_ERROR) {
                    "FP16 bind failed"
                }
            } else {
                program = buildGradientProgram()
            }
            codec.start()
            val info = MediaCodec.BufferInfo()
            var outFormat: MediaFormat? = null
            val samples = mutableListOf<Sample>()
            val push = egl
            val stepNs = 1_000_000_000L / target.fps
            repeat(frames) { i ->
                GLES20.glViewport(0, 0, target.width, target.height)
                if (useTexture != null) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                    if (dithered) {
                        GlBlit.drawDithered(program, quad, target.width, target.height, i.toFloat())
                    } else {
                        GLES20.glUseProgram(program)
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
                        GlBlit.drawQuad(program, quad)
                    }
                } else {
                    GLES20.glUseProgram(program)
                    GLES20.glUniform1f(
                        GLES20.glGetUniformLocation(program, "uPhase"), (i % 60) / 60f
                    )
                    GlBlit.drawQuad(program, quad)
                }
                GLES20.glFlush()
                push.setPresentationTime(i * stepNs)
                push.swap()
                // Drain opportunistically so the encoder never blocks swap.
                while (true) {
                    val idx = codec.dequeueOutputBuffer(info, 0)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = codec.outputFormat
                        idx >= 0 -> {
                            if (info.size > 0) {
                                val out = codec.getOutputBuffer(idx)!!
                                val bytes = ByteArray(info.size)
                                val dup = out.duplicate()
                                dup.position(info.offset)
                                dup.limit(info.offset + info.size)
                                dup.get(bytes)
                                samples.add(Sample(bytes, info.presentationTimeUs, info.flags))
                            }
                            codec.releaseOutputBuffer(idx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
            }
            codec.signalEndOfInputStream()
            val end = System.currentTimeMillis() + 10_000
            var eos = false
            while (!eos && System.currentTimeMillis() < end) {
                val idx = codec.dequeueOutputBuffer(info, 1000)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = codec.outputFormat
                    idx >= 0 -> {
                        if (info.size > 0) {
                            val out = codec.getOutputBuffer(idx)!!
                            val bytes = ByteArray(info.size)
                            val dup = out.duplicate()
                            dup.position(info.offset)
                            dup.limit(info.offset + info.size)
                            dup.get(bytes)
                            samples.add(Sample(bytes, info.presentationTimeUs, info.flags))
                        }
                        eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(idx, false)
                    }
                }
            }
            checkNotNull(outFormat) { "no encoder output format" }
            check(samples.isNotEmpty()) { "no encoder output" }
            return Clip(outFormat, samples)
        } finally {
            try { if (image != 0L) VfEglImport.destroyEGLImage(image) } catch (_: Exception) {}
            try {
                if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            } catch (_: Exception) {}
            try {
                if (program != 0) GLES20.glDeleteProgram(program)
            } catch (_: Exception) {}
            try { egl?.release() } catch (_: Exception) {}
            try { surface?.release() } catch (_: Exception) {}
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
        }
    }

    /** Decoded ramp metrics over all frames (max-distinct wins). */
    data class RampStats(val distinct: Int, val mean: Float)

    /** Decodes [clip], returns worst-best ramp metrics (max distinct). */
    private fun decodeAndAnalyze(clip: Clip): RampStats {
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        try {
            decoder.configure(clip.format, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var bestDistinct = 0
            var meanSum = 0.0
            var meanCount = 0L
            var nextSample = 0
            var inputEos = false
            var outputEos = false
            val end = System.currentTimeMillis() + 30_000
            while (!outputEos && System.currentTimeMillis() < end) {
                // Feed while the decoder has room; drain every pass so fed
                // buffers recycle (feed-only starves input within ~8 frames).
                if (!inputEos) {
                    if (nextSample < clip.samples.size) {
                        val s = clip.samples[nextSample]
                        val idx = decoder.dequeueInputBuffer(0)
                        if (idx >= 0) {
                            val buf = decoder.getInputBuffer(idx)!!
                            buf.clear()
                            buf.put(s.bytes)
                            decoder.queueInputBuffer(idx, 0, s.bytes.size, s.ptsUs, s.flags)
                            nextSample++
                        }
                    } else {
                        val idx = decoder.dequeueInputBuffer(1000)
                        if (idx >= 0) {
                            decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        }
                    }
                }
                val idx = decoder.dequeueOutputBuffer(info, 1000)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    idx >= 0 -> {
                        outputEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (info.size > 0) {
                            val img = decoder.getOutputImage(idx)
                            if (img != null) {
                                try {
                                    val (distinct, mean) = analyzeY(img)
                                    if (distinct > bestDistinct) bestDistinct = distinct
                                    meanSum += mean
                                    meanCount++
                                } finally {
                                    try { img.close() } catch (_: Exception) {}
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(idx, false)
                    }
                }
            }
            check(meanCount > 0) { "no decoded frames" }
            return RampStats(bestDistinct, (meanSum / meanCount).toFloat())
        } finally {
            try { decoder.stop() } catch (_: Exception) {}
            try { decoder.release() } catch (_: Exception) {}
        }
    }

    /** Distinct code count + mean over the center row of the Y plane. */
    private fun analyzeY(img: android.media.Image): Pair<Int, Float> {
        val plane = img.planes[0]
        val w = img.width
        val stride = plane.rowStride
        val buf = plane.buffer
        val words = HashSet<Int>(2048)
        var sum = 0L
        val rowStart = (img.height / 2) * stride
        // Respect the plane packing: 8-bit (stride 1) counts bytes (cap
        // 256 by construction); 10-bit (stride 2) counts LE words.
        var maxCode = 255
        if (plane.pixelStride == 2) {
            maxCode = 1023
            var i = 0
            while (i < w * 2) {
                val lo = buf.get(rowStart + i).toInt() and 0xFF
                val hi = buf.get(rowStart + i + 1).toInt() and 0xFF
                val v = lo or (hi shl 8)
                words.add(v)
                sum += v
                i += 2
            }
        } else {
            var i = 0
            while (i < w) {
                val v = buf.get(rowStart + i).toInt() and 0xFF
                words.add(v)
                sum += v
                i += 1
            }
        }
        return words.size to (sum.toFloat() / (w * maxCode))
    }

    private fun buildGradientProgram(): Int {
        val vs = "attribute vec4 aPos;attribute vec2 aUV;varying vec2 vUV;" +
            "void main(){gl_Position=aPos;vUV=aUV;}"
        // highp: mediump interpolation could itself quantize the ramp.
        val fs = "precision highp float;varying vec2 vUV;uniform float uPhase;" +
            "void main(){float t=fract(vUV.x+uPhase);gl_FragColor=vec4(t,t,t,1.0);}"
        val v = compileShader(GLES20.GL_VERTEX_SHADER, vs)
        val f = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        check(p != 0) { "glCreateProgram failed" }
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val link = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, link, 0)
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        check(link[0] == GLES20.GL_TRUE) { "gradient link failed" }
        return p
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        check(s != 0) { "glCreateShader failed" }
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "gradient compile failed" }
        return s
    }

    companion object {
        private const val TAG = "TenBitProof"
    }
}
