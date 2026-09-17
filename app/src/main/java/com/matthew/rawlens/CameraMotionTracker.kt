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

/** One gyroscope sample in the same boot-time domain used by Camera2 timestamps. */
internal data class GyroSample(
    val timestampNanos: Long,
    val xRadiansPerSecond: Float,
    val yRadiansPerSecond: Float,
    val zRadiansPerSecond: Float
)

/** Records a short gyroscope history in the same boot-time domain used by Camera2 timestamps. */
internal class CameraMotionTracker(context: Context) : SensorEventListener {
    private data class Sample(
        val timestampNanos: Long,
        val radiansPerSecond: Float,
        val x: Float,
        val y: Float,
        val z: Float
    )

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
    private val samples = ArrayDeque<Sample>()
    @Volatile private var running = false
    @Volatile private var filteredMotion = 0f

    fun start(handler: Handler) {
        if (running || gyroscope == null) return
        running = sensorManager?.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME, handler)
            ?: false
    }

    fun stop() {
        if (running) sensorManager?.unregisterListener(this)
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

    /**
     * Immutable xyz snapshot covering one frame exposure plus the
     * rolling-shutter readout interval, in boot-time nanos. Empty when
     * timestamps are not realtime (no live gyro domain exists) or when no
     * samples fall in the window. Never returns the live deque itself.
     */
    fun gyroWindowForFrame(
        timestampNanos: Long,
        exposureNanos: Long,
        rollingShutterSkewNanos: Long,
        realtimeTimestamps: Boolean
    ): List<GyroSample> {
        if (!realtimeTimestamps) return emptyList()
        val durationNanos = saturatingAdd(
            exposureNanos.coerceAtLeast(0L),
            rollingShutterSkewNanos.coerceAtLeast(0L)
        )
        val endNanos = saturatingAdd(timestampNanos, durationNanos)
        synchronized(samples) {
            return samples
                .filter { it.timestampNanos in timestampNanos..endNanos }
                .map { GyroSample(it.timestampNanos, it.x, it.y, it.z) }
        }
    }

    /** JVM test seam: injects one xyz sample without sensor hardware. */
    internal fun addSampleForTest(timestampNanos: Long, x: Float, y: Float, z: Float) {
        synchronized(samples) {
            val magnitude = sqrt(x * x + y * y + z * z)
            samples.addLast(Sample(timestampNanos, magnitude, x, y, z))
            val oldestAllowed = timestampNanos - HISTORY_NANOS
            while (samples.firstOrNull()?.timestampNanos?.let { it < oldestAllowed } == true) {
                samples.removeFirst()
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running || event.values.size < 3) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)
        filteredMotion += MOTION_SMOOTHING * (magnitude - filteredMotion)
        synchronized(samples) {
            samples.addLast(Sample(event.timestamp, magnitude, x, y, z))
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
