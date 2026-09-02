// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import kotlin.math.sqrt

/** Records a short gyroscope history in the same boot-time domain used by Camera2 timestamps. */
internal class CameraMotionTracker(context: Context) : SensorEventListener {
    private data class Sample(val timestampNanos: Long, val radiansPerSecond: Float)

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
    private val samples = ArrayDeque<Sample>()
    @Volatile private var running = false
    @Volatile private var filteredMotion = 0f

    fun start(handler: Handler) {
        if (running || gyroscope == null) return
        running = sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME, handler)
    }

    fun stop() {
        if (running) sensorManager.unregisterListener(this)
        running = false
        synchronized(samples) { samples.clear() }
        filteredMotion = 0f
    }

    fun motionForFrame(
        timestampNanos: Long,
        exposureNanos: Long,
        rollingShutterSkewNanos: Long,
        realtimeTimestamps: Boolean
    ): Float {
        if (!running || !realtimeTimestamps) return filteredMotion
        val durationNanos = saturatingAdd(
            exposureNanos.coerceAtLeast(0L),
            rollingShutterSkewNanos.coerceAtLeast(0L)
        )
        val endNanos = saturatingAdd(timestampNanos, durationNanos)
        var total = 0f
        var count = 0
        synchronized(samples) {
            for (sample in samples) {
                if (sample.timestampNanos in timestampNanos..endNanos) {
                    total += sample.radiansPerSecond
                    count++
                }
            }
        }
        return if (count > 0) total / count else filteredMotion
    }

    fun currentMotion(): Float = filteredMotion

    override fun onSensorChanged(event: SensorEvent) {
        if (!running || event.values.size < 3) return
        val magnitude = sqrt(
            event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2]
        )
        filteredMotion += MOTION_SMOOTHING * (magnitude - filteredMotion)
        synchronized(samples) {
            samples.addLast(Sample(event.timestamp, magnitude))
            val oldestAllowed = event.timestamp - HISTORY_NANOS
            while (samples.firstOrNull()?.timestampNanos?.let { it < oldestAllowed } == true) {
                samples.removeFirst()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private companion object {
        const val MOTION_SMOOTHING = 0.18f
        const val HISTORY_NANOS = 3_000_000_000L
    }
}
