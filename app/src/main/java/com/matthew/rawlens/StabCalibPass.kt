// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlin.math.sqrt

/**
 * Stage-3 measurement pass: decodes the first [MAX_FRAMES] frames of the
 * staged MP4 into small luma images and measures the global centre
 * translation between consecutive frames ([StabCalib.GlobalShift]) for
 * [StabCalib.calibrate]. It runs before the warp pass, so it costs one
 * extra partial decode (about 10 s of footage).
 *
 * Fail-soft: any problem returns what was measured so far (possibly none),
 * and the planner then runs uncalibrated.
 */
internal object StabCalibPass {
    private const val TAG = "StabCalib"
    const val MAX_FRAMES = 300
    private const val TARGET_W = 320
    /** Max standard error of an accepted pair shift, luma px (≈2 encode px at 4K/320). */
    private const val MAX_SE_LUMA_PX = 0.25

    fun measure(context: android.content.Context, source: android.net.Uri, take: StabSidecar.Take): List<StabCalib.PairShift> {
        val out = ArrayList<StabCalib.PairShift>()
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, source, null)
            var track = -1
            for (i in 0 until extractor.trackCount) {
                if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    track = i
                    break
                }
            }
            if (track < 0) return out
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return out
            val w = format.getInteger(MediaFormat.KEY_WIDTH)
            val h = format.getInteger(MediaFormat.KEY_HEIGHT)
            val name = EncodedVideoProbe.preferredHwDecoder(mime, w, h)
            decoder = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            extractor.selectTrack(track)

            val step = maxOf(1, w / TARGET_W)
            val lw = w / step
            val lh = h / step
            val scale = w.toDouble() / lw // encode px per luma px
            val fps = take.header.fps
            val frames = take.frameTimestampsNs.size
            var rejected = 0
            var prev: FloatArray? = null
            var prevIdx = -1
            var decoded = 0
            var inEos = false
            val info = MediaCodec.BufferInfo()
            var stall = 0
            while (decoded < MAX_FRAMES && stall < 2000) {
                var progress = false
                if (!inEos) {
                    val inIdx = decoder.dequeueInputBuffer(1_000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, extractor.sampleFlags)
                            extractor.advance()
                        }
                        progress = true
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(info, 1_000)
                if (outIdx >= 0) {
                    progress = true
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0) {
                        val img = decoder.getOutputImage(outIdx)
                        val luma = img?.let { lumaOf(it, lw, lh, step) }
                        img?.close()
                        val idx = EncodedVideoStabilizer.frameIndexForPts(info.presentationTimeUs, fps, frames)
                        decoded++
                        val p = prev
                        if (luma != null && p != null && idx == prevIdx + 1) {
                            val s = StabCalib.GlobalShift.measure(p, luma, lw, lh)
                            // Keep pairs whose shift is known to < MAX_SE_LUMA_PX
                            // (≈2 encode px at 320 wide); weight 1/se² (normalised).
                            if (s != null && s.stdErr.isFinite() && s.stdErr < MAX_SE_LUMA_PX) {
                                val w = (MAX_SE_LUMA_PX * MAX_SE_LUMA_PX) / (s.stdErr * s.stdErr).coerceAtLeast(1e-6)
                                out.add(StabCalib.PairShift(prevIdx, s.dx * scale, s.dy * scale, w.coerceAtMost(100.0)))
                            } else {
                                rejected++
                            }
                        }
                        prev = luma
                        prevIdx = idx
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (eos) break
                }
                stall = if (progress) 0 else stall + 1
            }
            Log.i(TAG, "measured pairs=${out.size} rejected=$rejected decoded=$decoded luma=${lw}x$lh " +
                "seMedianPx=${out.map { MAX_SE_LUMA_PX / sqrt(it.weight) }.sorted().getOrNull(out.size / 2)}")
        } catch (e: Exception) {
            Log.w(TAG, "calibration pass stopped (${e.message}); pairs=${out.size}")
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
        return out
    }

    /** Box-filtered (3x3 samples per cell) luma at 1/[step] scale, codes normalised to 0..1. */
    private fun lumaOf(img: android.media.Image, lw: Int, lh: Int, step: Int): FloatArray? {
        val plane = img.planes.firstOrNull() ?: return null
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val tenBit = EncodedVideoStabilizer.isTenBitOutput(ps)
        val o = FloatArray(lw * lh)
        val offs = intArrayOf(step / 6, step / 2, (5 * step) / 6)
        val norm = if (tenBit) 1f / (1023f * 9f) else 1f / (255f * 9f)
        for (y in 0 until lh) for (x in 0 until lw) {
            var acc = 0
            for (oy in offs) for (ox in offs) {
                val p = (y * step + oy) * rs + (x * step + ox) * ps
                acc += if (tenBit) {
                    ((buf.get(p).toInt() and 0xFF) or ((buf.get(p + 1).toInt() and 0xFF) shl 8)) ushr 6
                } else {
                    buf.get(p).toInt() and 0xFF
                }
            }
            o[y * lw + x] = acc * norm
        }
        return o
    }
}
