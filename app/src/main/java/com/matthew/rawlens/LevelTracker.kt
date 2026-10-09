// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Gravity feed for the virtual horizon. Prefers the fused gravity sensor
 * (linear acceleration removed); falls back to the raw accelerometer with the
 * same low-pass so every device gets a stable tilt. UI rate is plenty for a
 * horizon line and keeps power negligible.
 */
internal class LevelTracker(
    context: Context,
    private val onState: (VirtualHorizon.State) -> Unit
) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val gravity: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile private var running = false
    private var filteredX = 0f
    private var filteredY = 0f
    private var filteredZ = 0f
    private var hasSample = false
    private var lastState: VirtualHorizon.State? = null

    fun start() {
        val sensor = gravity ?: return
        if (running) return
        running = sensorManager?.registerListener(
            this, sensor, SensorManager.SENSOR_DELAY_UI
        ) ?: false
    }

    fun stop() {
        if (running) sensorManager?.unregisterListener(this)
        running = false
        hasSample = false
        lastState = null
    }

    fun isRunning(): Boolean = running

    /** JVM test seam: runs the filter + math path without sensor hardware. */
    internal fun stateForTest(gx: Float, gy: Float, gz: Float): VirtualHorizon.State {
        val state = if (!hasSample) {
            filteredX = gx
            filteredY = gy
            filteredZ = gz
            hasSample = true
            VirtualHorizon.compute(gx, gy, gz)
        } else {
            filteredX += SMOOTHING * (gx - filteredX)
            filteredY += SMOOTHING * (gy - filteredY)
            filteredZ += SMOOTHING * (gz - filteredZ)
            VirtualHorizon.compute(filteredX, filteredY, filteredZ)
        }
        lastState = state
        return state
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running || event.values.size < 3) return
        val previous = lastState
        val state = stateForTest(event.values[0], event.values[1], event.values[2])
        // Always deliver the first sample; afterwards skip duplicates that would
        // only burn an invalidate (angle stable within 0.05° and flags same).
        if (previous == null || shouldDeliver(previous, state)) onState(state)
    }

    private fun shouldDeliver(previous: VirtualHorizon.State, next: VirtualHorizon.State): Boolean {
        if (previous.isLevel != next.isLevel || previous.isFlat != next.isFlat) return true
        if (next.isFlat) return false
        return kotlin.math.abs(next.tiltDegrees - previous.tiltDegrees) >= 0.05f ||
            kotlin.math.abs(next.errorDegrees - previous.errorDegrees) >= 0.05f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val SMOOTHING = 0.25f
    }
}
