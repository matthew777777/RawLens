// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.view.HapticFeedbackConstants
import android.view.View

internal const val KEY_SHUTTER_SOUND = "photo_shutter_sound"

/** Preloaded once per activity; one pulse/click per accepted photo or burst. */
internal class PhotoCaptureFeedback(context: Context) : AutoCloseable {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build())
        .build()
    @Volatile private var ready = false
    private val soundId: Int

    init {
        pool.setOnLoadCompleteListener { _, _, status -> ready = status == 0 }
        soundId = pool.load(context, R.raw.film_slr_shutter, 1)
    }

    fun play(shutter: View, soundEnabled: Boolean) {
        // A firmer pulse than CONTEXT_CLICK, respecting Android touch feedback settings.
        shutter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (soundEnabled && ready &&
            audioManager?.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM) > 0) {
            pool.play(soundId, 1f, 1f, 1, 0, 1f)
        }
    }

    override fun close() {
        ready = false
        pool.release()
    }
}
