// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** Shared RAW noise identity, persistent prior and normalized variance utilities.
 * Independent of the denoiser backend; store directory is retained for existing training data. */
object RawNoiseModel {
    data class Source(
        val iso: Int, val rawModeKey: String, val maxAnalogIso: Int = 0, val captureId: Long = 0L
    ) {
        companion object {
            /** One identity shared by every denoiser and merge backend. */
            fun of(metadata: RawFrameMetadata): Source? {
                // A merged frame has a different noise law from one sensor
                // exposure; it must not train or use the single-frame prior.
                if ((metadata.compositeUsedCount ?: 1) > 1) return null
                return metadata.sensitivityIso?.let {
                    Source(it, "${metadata.cameraId}/${metadata.activePhysicalCameraId}/" +
                        "${metadata.sensorPixelMode}/${metadata.quadBayer}/${metadata.rawBinningFactorUsed}/" +
                        "${metadata.imageWidth}x${metadata.imageHeight}" +
                        (metadata.sensorReadoutProfile?.let { mode -> "/readout:$mode" } ?: ""),
                        metadata.maxAnalogSensitivityIso ?: 0, metadata.timestampNanos)
                }
            }
        }
    }

    /** Learned-model store file for [source] under [dir] (per raw-mode key + build fingerprint). */
    internal fun storePath(dir: File, source: Source): String? {
        if (source.iso <= 0) return null
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${source.rawModeKey}|${Build.FINGERPRINT}".toByteArray())
        val name = digest.take(12).joinToString("") { "%02x".format(it) }
        return File(dir, "$name.txt").absolutePath
    }

    /** Historical directory name retained so existing trained models remain available. */
    internal fun noiseDir(context: Context): File = File(context.applicationContext.filesDir, "wavelet-noise")

    /** Explicit HAL-profile conversion for callers that still need a fallback; not a measurement. */
    fun noiseAB(
        noiseProfile: DoubleArray?, pattern: BayerPattern, black: Float, white: Float
    ): Pair<Float, Float>? {
        if (noiseProfile == null || noiseProfile.size < 8 ||
            !(white > black) || !(black >= 0f)) return null
        val g0 = if (pattern == BayerPattern.GRBG || pattern == BayerPattern.GBRG) 0 else 1
        val g1 = if (g0 == 0) 3 else 2
        val slope = (noiseProfile[g0 * 2] + noiseProfile[g1 * 2]).toFloat() * 0.5f
        val offset = (noiseProfile[g0 * 2 + 1] + noiseProfile[g1 * 2 + 1]).toFloat() * 0.5f
        if (!slope.isFinite() || !offset.isFinite() || !(slope >= 0f)) return null
        val range = white - black
        val a = if (range > 0f) slope / range else 0f
        val b = if (range > 0f) (slope * black + offset) / (range * range) else 0f
        return a to b
    }


    @Volatile private var loaded = false
    @Synchronized private fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try { System.loadLibrary("rawLensNoise"); loaded = true; true }
        catch (_: UnsatisfiedLinkError) { false }
    }
    fun predictNoise(storePath: String, iso: Float): FloatArray? =
        if (!ensureLoaded()) null else try { nativePredictNoise(storePath, iso) } catch (_: RuntimeException) { null }
    fun combineNoise(storePath: String, iso: Float, estimateA: Float, estimateB: Float, bins: Int): FloatArray? =
        if (!ensureLoaded()) null else try { nativeCombineNoise(storePath, iso, estimateA, estimateB, bins) }
        catch (_: RuntimeException) { null }
    /** Persist a trustworthy single-frame estimate; callers must not pass merged/derived noise. */
    fun record(context: Context, source: Source, estimate: RawNoiseEstimate): Boolean {
        val path = storePath(noiseDir(context), source) ?: return false
        if (!ensureLoaded()) return false
        return try { nativeRecordNoise(path, source.iso.toFloat(), estimate.a.toFloat(), estimate.b.toFloat(),
            estimate.bins, source.maxAnalogIso.toFloat(), source.captureId) } catch (_: RuntimeException) { false }
    }
    private external fun nativeRecordNoise(path: String, iso: Float, a: Float, b: Float,
                                           bins: Int, maxAnalogIso: Float, captureId: Long): Boolean
    private external fun nativePredictNoise(storePath: String, iso: Float): FloatArray?
    private external fun nativeCombineNoise(storePath: String, iso: Float, estimateA: Float,
                                            estimateB: Float, bins: Int): FloatArray?
}
