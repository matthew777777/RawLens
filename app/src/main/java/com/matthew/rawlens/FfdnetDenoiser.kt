// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.content.SharedPreferences
import java.nio.ByteBuffer

/** Single-frame FFDNet plus stride-separated chroma cleanup; replaces wavelet, leaves sensor DNGs. */
data class FfdnetSettings(val enabled: Boolean = false, val strength: Float = 1f) {
    init { require(strength.isFinite() && strength in 0f..1f) }
    val active: Boolean get() = enabled && strength > 0f
    companion object {
        const val ENABLED_KEY = "ffdnet_enabled"
        const val STRENGTH_KEY = "ffdnet_strength_pct"
        fun read(prefs: SharedPreferences) = FfdnetSettings(
            prefs.getBoolean(ENABLED_KEY, false),
            prefs.getInt(STRENGTH_KEY, 100).coerceIn(0, 100) / 100f
        )
    }
}

internal class FfdnetDenoiser(private val context: Context) {
    /** Native commits only after every tile succeeds; failure preserves the source. */
    fun process(rgba: ByteBuffer, width: Int, height: Int, pattern: BayerPattern,
                wb: FloatArray, strength: Float): Boolean {
        if (strength == 0f) return true
        return try {
            val param = LkrMerge.modelFile(context, "models/lkr_ffdnet.ncnn.param")
            val bin = LkrMerge.modelFile(context, "models/lkr_ffdnet.ncnn.bin")
            val npu = runCatching {
                LkrMerge.modelFile(context, "models/lkr_ffdnet_512.tflite").path
            }.getOrNull()
            val cfa = IntArray(4) {
                when (pattern.colorAt(it and 1, it shr 1)) {
                    CfaColor.RED -> 0
                    CfaColor.GREEN -> 1
                    CfaColor.BLUE -> 2
                }
            }
            val success = LkrBridge.nativeDenoise(rgba, width, height, cfa, wb,
                strength, param.path, bin.path, context.applicationInfo.nativeLibraryDir, npu)
            if (!success) Log.w("RawLensFfdnet", "FFDNet unavailable; developing source")
            success
        } catch (failure: Exception) {
            Log.w("RawLensFfdnet", "FFDNet failed; developing source", failure)
            false
        } catch (failure: LinkageError) {
            Log.w("RawLensFfdnet", "FFDNet library unavailable; developing source", failure)
            false
        } catch (failure: OutOfMemoryError) {
            Log.w("RawLensFfdnet", "FFDNet memory unavailable; developing source", failure)
            false
        }
    }
}
