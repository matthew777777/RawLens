// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import android.media.Image
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * F14 recording handoff: same RAW detector as LiveScopes, at its 250 ms cadence.
 * One borrowed image at a time, no queue/copy and no sampling on the camera or
 * encoder thread. The recorder must adopt the image before offer and close this
 * sampler before closing its ImageReader. ML differences remain in FocusPeaking.kt.
 */
internal class RecordingFocusPeaking : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "record-peaking") }
    private val busy = AtomicBoolean(false)
    @Volatile private var closed = false
    private var lastSampleMs = Long.MIN_VALUE
    private var shown = false
    @Volatile private var epoch = 0L

    @Synchronized fun offer(
        image: Image,
        characteristics: CameraCharacteristics,
        active: Boolean,
        publish: ((FocusPeakingFrame?) -> Unit)?,
        monitorCrop: IntArray? = null
    ) {
        if (closed || publish == null) return
        if (!active) {
            if (shown) { epoch++; publish(null) }
            shown = false
            lastSampleMs = Long.MIN_VALUE
            return
        }
        shown = true
        val now = SystemClock.elapsedRealtime()
        if (!RawHistogramThrottle.shouldSample(now, lastSampleMs, LiveScopes.FOCUS_PEAKING_INTERVAL_MS)) return
        if (!busy.compareAndSet(false, true)) return
        val lease = RawImageOwnership.borrow(image)
        if (lease == null) { busy.set(false); return }
        lastSampleMs = now
        val sampleEpoch = epoch
        worker.execute {
            try {
                val frame = RawFocusPeakingSampler.sample(image, characteristics)
                if (!closed && epoch == sampleEpoch && frame != null) publish(frame.copy(monitorCrop = monitorCrop))
            } catch (e: Exception) {
                Log.w("RawLensCamera", "record peaking sample failed", e)
            } finally {
                try { lease.close() } finally { busy.set(false) }
            }
        }
    }

    @Synchronized override fun close() {
        closed = true
        worker.shutdown()
        // stop/start-failure teardown runs off the UI thread and must drain
        // the outstanding CPU reader before the recorder closes ImageReader.
        if (!worker.awaitTermination(3, TimeUnit.SECONDS)) {
            Log.w("RawLensCamera", "record peaking worker still draining")
        }
    }
}
