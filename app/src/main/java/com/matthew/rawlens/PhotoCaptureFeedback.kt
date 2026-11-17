// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.View

internal const val KEY_SHUTTER_SOUND = "photo_shutter_sound"
internal const val KEY_SHUTTER_HAPTICS = "photo_shutter_haptics"
internal const val KEY_SHUTTER_HAPTICS_STRENGTH = "photo_shutter_haptics_strength"
internal const val DEFAULT_SHUTTER_HAPTICS_STRENGTH = 75

/** Preloaded once per activity; one pulse/click per accepted photo or burst. */
internal class PhotoCaptureFeedback(context: Context) : AutoCloseable {
    private val resolver = context.contentResolver
    private val vibrator = context.getSystemService(Vibrator::class.java)
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

    fun play(shutter: View, soundEnabled: Boolean, hapticsEnabled: Boolean, strength: Int) {
        val percent = strength.coerceIn(0, 100)
        if (hapticsEnabled && percent > 0 && shutter.isHapticFeedbackEnabled &&
            Settings.System.getInt(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0 &&
            vibrator?.hasVibrator() == true) {
            // Fixed short pulse with adjustable amplitude. Older motors vary duration instead.
            val amplitude = if (vibrator.hasAmplitudeControl()) (percent * 255 / 100).coerceAtLeast(1)
                else VibrationEffect.DEFAULT_AMPLITUDE
            val duration = if (vibrator.hasAmplitudeControl()) 40L else 12L + percent * 40L / 100
            vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
        }
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
