// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * P4 continuous motion log for RAW video: full-rate gyro (+accelerometer)
 * from record start to stop — not the 3 s preview ring. Deliberately
 * separate from [CameraMotionTracker] (preview/CAF/ZSL lifetime, gyro-only,
 * `SENSOR_DELAY_GAME`): this owns its sensors, its thread, and its history
 * for exactly one recording.
 *
 * Timestamp domain: `event.timestamp` (boot-time ns, `CLOCK_BOOTTIME`) —
 * the same domain as `Image.timestamp` when
 * `SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME`. The recorder checks that flag
 * (passed in from [RawVideoRecorder]) and refuses motion when timestamps
 * aren't realtime rather than writing a shifted timeline.
 *
 * Gyro axes are mapped to the camera frame with [GyroCameraFrameMapper]
 * before chunking; accel keeps platform axes with gravity, per the
 * container contract (rad/s vs m/s^2).
 */
internal class MotionRecorder(
    context: Context,
    private val sensorOrientationDeg: Int,
    private val frontFacing: Boolean,
    private val realtimeTimestamps: Boolean,
) {
    data class Chunk(
        val timestampsNs: LongArray, // ascending, boot-time ns
        val axes: FloatArray, // count*3 x/y/z triplets
        val count: Int,
    )

    data class Stats(val gyroSamples: Long, val accelSamples: Long)

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var thread: HandlerThread? = null
    private val gyro = ArrayDeque<MotionSample>()
    private val accel = ArrayDeque<MotionSample>()
    private val gyroCount = AtomicLong(0)
    private val accelCount = AtomicLong(0)
    @Volatile private var running = false

    private data class MotionSample(val ts: Long, val x: Float, val y: Float, val z: Float)

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!running) return
            val v = event.values
            if (event.sensor.type == Sensor.TYPE_GYROSCOPE ||
                event.sensor.type == Sensor.TYPE_GYROSCOPE_UNCALIBRATED
            ) {
                val (x, y, z) = try {
                    GyroCameraFrameMapper.map(
                        v[0], v[1], v[2], sensorOrientationDeg, frontFacing
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "gyro map failed: ${e.message}")
                    return
                }
                synchronized(gyro) { gyro.addLast(MotionSample(event.timestamp, x, y, z)) }
                gyroCount.incrementAndGet()
            } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                synchronized(accel) {
                    accel.addLast(MotionSample(event.timestamp, v[0], v[1], v[2]))
                }
                accelCount.incrementAndGet()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    val hasGyro: Boolean get() = gyroscope != null
    val hasAccel: Boolean get() = accelerometer != null

    /** @return false when motion must be skipped (no sensors / non-realtime ts). */
    fun start(): Boolean {
        check(thread == null) { "Already started" }
        if (!realtimeTimestamps) {
            Log.w(TAG, "non-realtime sensor timestamps: motion skipped")
            return false
        }
        if (gyroscope == null && accelerometer == null) {
            Log.w(TAG, "no gyro/accel sensors")
            return false
        }
        val t = HandlerThread("RawVideoMotion").apply { start() }
        thread = t
        val handler = Handler(t.looper)
        running = true
        var ok = false
        // FASTEST needs HIGH_SAMPLING_RATE_SENSORS on API 31+ (declared in
        // the manifest); some stacks still throw, so fall back to GAME rate
        // (~50 Hz — plenty for video motion tracks) instead of failing.
        var rate = SensorManager.SENSOR_DELAY_FASTEST
        try {
            ok = registerAll(handler, rate)
        } catch (e: SecurityException) {
            Log.w(TAG, "fast sensor rate denied, falling back to game rate")
            rate = SensorManager.SENSOR_DELAY_GAME
            ok = registerAll(handler, rate)
        }
        if (!ok) {
            stop()
            return false
        }
        return true
    }

    private fun registerAll(handler: Handler, rate: Int): Boolean {
        var ok = false
        if (gyroscope != null) {
            ok = sensorManager?.registerListener(
                listener, gyroscope, rate, handler
            ) == true || ok
        }
        if (accelerometer != null) {
            ok = sensorManager?.registerListener(
                listener, accelerometer, rate, handler
            ) == true || ok
        }
        return ok
    }

    /** Drain up to [maxSamples] oldest gyro samples as one chunk, or null. */
    fun drainGyro(maxSamples: Int = 512): Chunk? = drain(gyro, maxSamples)

    fun drainAccel(maxSamples: Int = 512): Chunk? = drain(accel, maxSamples)

    fun stop(): Stats {
        running = false
        try {
            sensorManager?.unregisterListener(listener)
        } catch (_: Exception) {
        }
        try {
            thread?.quitSafely()
            thread?.join(2000)
        } catch (_: Exception) {
        }
        thread = null
        return Stats(gyroCount.get(), accelCount.get())
    }

    private fun drain(
        deque: ArrayDeque<MotionSample>, maxSamples: Int
    ): Chunk? {
        val ts: LongArray
        val ax: FloatArray
        val n: Int
        synchronized(deque) {
            if (deque.isEmpty()) return null
            n = minOf(deque.size, maxSamples)
            ts = LongArray(n)
            ax = FloatArray(n * 3)
            for (i in 0 until n) {
                val s = deque.removeFirst()
                ts[i] = s.ts
                ax[i * 3] = s.x
                ax[i * 3 + 1] = s.y
                ax[i * 3 + 2] = s.z
            }
        }
        return Chunk(ts, ax, n)
    }

    companion object {
        private const val TAG = "MotionRecorder"
    }
}
