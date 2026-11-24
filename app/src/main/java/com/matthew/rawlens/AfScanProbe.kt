// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * What an active AF scan proved for one route. HAL characteristics are not
 * trusted: the AF state machine (logical evidence) and the picture actually
 * changing when the lens is driven (physical evidence) are probed
 * separately, because some routes run one without the other.
 */
enum class AfVerdict(val note: String?) {
    UNKNOWN(null),
    /** Minimum focus distance 0: nothing to drive. */
    FIXED_FOCUS("Fixed focus"),
    /** State machine scans and the lens physically moves. */
    WORKING("AF verified"),
    /** AF state reports scans/locks but the image never changes: HAL AF is a stub. */
    LOGICAL_ONLY("AF reports focus but lens does not move · use manual/RawLens AF"),
    /** Lens moves when driven but the HAL AF state machine never runs. */
    PHYSICAL_ONLY("Lens moves but HAL AF never scans · manual focus works"),
    /** Neither the state machine nor the lens responded. */
    DEAD("AF and manual focus both unresponsive")
}

/** Raw evidence from one probe; pure so [afVerdictOf] is unit-testable. */
data class AfProbeEvidence(
    val minFocusDiopters: Float,
    /** AF_STATE reached ACTIVE_SCAN, FOCUSED_LOCKED or NOT_FOCUSED_LOCKED after a trigger. */
    val stateMachineScanned: Boolean,
    /** Relative Y-plane sharpness change between near and far manual focus. */
    val sharpnessChange: Double,
    /** Relative sharpness difference between the two far-focus passes: the measurement noise. */
    val sharpnessJitter: Double,
    /** Reported LENS_FOCUS_DISTANCE span over the whole probe (diopters). */
    val reportedDistanceSpan: Float,
    /** LENS_STATE MOVING was reported at least once. */
    val reportedMoving: Boolean
)

/** Near/far change must stand this many times above the far-vs-far repeat jitter. */
internal const val AF_CHANGE_OVER_JITTER = 4.0
/** Below this relative change the picture is treated as unchanged (8-bit quantisation floor). */
internal const val AF_CHANGE_FLOOR = 0.02

fun afVerdictOf(e: AfProbeEvidence): AfVerdict {
    if (e.minFocusDiopters <= 0f) return AfVerdict.FIXED_FOCUS
    // Image evidence: metadata can be an echo of the request, the picture cannot.
    val imageMoved = e.sharpnessChange > AF_CHANGE_FLOOR &&
        e.sharpnessChange > AF_CHANGE_OVER_JITTER * e.sharpnessJitter
    // A noisy or flat scene makes "unchanged" inconclusive rather than negative.
    val imageConclusive = imageMoved || AF_CHANGE_OVER_JITTER * e.sharpnessJitter < 0.5
    val metadataMoved = e.reportedMoving && e.reportedDistanceSpan > 0f
    val physical = imageMoved || (!imageConclusive && metadataMoved)
    return when {
        e.stateMachineScanned && physical -> AfVerdict.WORKING
        e.stateMachineScanned -> if (imageConclusive) AfVerdict.LOGICAL_ONLY else AfVerdict.UNKNOWN
        physical -> AfVerdict.PHYSICAL_ONLY
        imageConclusive -> AfVerdict.DEAD
        else -> AfVerdict.UNKNOWN
    }
}

/**
 * Opens each route the way [RawCameraController] would (logical device with
 * outputs bound to the physical member, or the ID directly) on a tiny YUV
 * stream and probes AF. Blocking; run off the main thread with the main
 * camera closed.
 */
class AfScanProbe(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)

    fun probe(lens: DiscoveredLens): AfVerdict {
        if (lens.kind == LensRouteKind.UNAVAILABLE) return AfVerdict.UNKNOWN
        val (openId, physicalId) = when (lens.kind) {
            LensRouteKind.LOGICAL_PHYSICAL ->
                (lens.logicalId ?: lens.id.substringBefore('/')) to (lens.physicalId ?: lens.id.substringAfter('/'))
            else -> lens.id to null
        }
        val thread = HandlerThread("af-probe").apply { start() }
        val handler = Handler(thread.looper)
        return try {
            val evidence = runProbe(openId, physicalId, handler)
            val verdict = evidence?.let(::afVerdictOf) ?: AfVerdict.UNKNOWN
            Log.i(LOG_TAG, "AF probe ${lens.id}: $verdict $evidence")
            verdict
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "AF probe ${lens.id} failed", t)
            AfVerdict.UNKNOWN
        } finally {
            thread.quitSafely()
        }
    }

    @SuppressLint("MissingPermission")
    private fun runProbe(openId: String, physicalId: String?, handler: Handler): AfProbeEvidence? {
        val chars = manager.getCameraCharacteristics(physicalId ?: openId)
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        if (minFocus <= 0f) return AfProbeEvidence(0f, false, 0.0, 1.0, 0f, false)
        val afModes = (manager.getCameraCharacteristics(openId)
            .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()).toSet()

        val device = openDevice(openId, handler) ?: return null
        val size = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.filter { it.width >= 320 }
            ?.minByOrNull { it.width.toLong() * it.height }
        val reader = ImageReader.newInstance(size?.width ?: 640, size?.height ?: 480, ImageFormat.YUV_420_888, 3)
        try {
            val output = OutputConfiguration(reader.surface).apply { physicalId?.let(::setPhysicalCameraId) }
            val session = createSession(device, output, handler) ?: return null

            // Written on the probe handler thread, read on the calling thread.
            val latestSharpness = AtomicReference(0.0)
            reader.setOnImageAvailableListener({ r ->
                r.acquireLatestImage()?.use { latestSharpness.set(ySharpness(it)) }
            }, handler)

            val scanned = AtomicBoolean(false)
            val moving = AtomicBoolean(false)
            val dMin = AtomicReference(Float.MAX_VALUE)
            val dMax = AtomicReference(-Float.MAX_VALUE)
            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    val phys = physicalId?.let {
                        if (android.os.Build.VERSION.SDK_INT >= 31) result.physicalCameraTotalResults[it]
                        else {
                            @Suppress("DEPRECATION")
                            result.physicalCameraResults[it]
                        }
                    }
                    val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    if (afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN ||
                        afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ||
                        afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN
                    ) scanned.set(true)
                    val src = phys ?: result
                    if (src.get(CaptureResult.LENS_STATE) == CameraMetadata.LENS_STATE_MOVING) moving.set(true)
                    src.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { d ->
                        if (d < dMin.get()) dMin.set(d)
                        if (d > dMax.get()) dMax.set(d)
                    }
                }
            }

            fun request(build: CaptureRequest.Builder.() -> Unit): CaptureRequest =
                device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    build()
                }.build()

            // Phase 1: logical — AF trigger scan through the HAL state machine.
            val afMode = when {
                CameraMetadata.CONTROL_AF_MODE_AUTO in afModes -> CameraMetadata.CONTROL_AF_MODE_AUTO
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                else -> CameraMetadata.CONTROL_AF_MODE_AUTO // advertised OFF only: try anyway
            }
            val scanRequest = request { set(CaptureRequest.CONTROL_AF_MODE, afMode) }
            session.setRepeatingRequest(scanRequest, callback, handler)
            Thread.sleep(WARMUP_MS)
            session.capture(request {
                set(CaptureRequest.CONTROL_AF_MODE, afMode)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            }, callback, handler)
            Thread.sleep(SCAN_MS)
            session.capture(request {
                set(CaptureRequest.CONTROL_AF_MODE, afMode)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            }, callback, handler)
            val stateScanned = scanned.get()

            // Phase 2: physical — drive the lens manually and look at the picture.
            fun sharpnessAt(diopters: Float): Double {
                session.setRepeatingRequest(request {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
                }, callback, handler)
                Thread.sleep(SETTLE_MS)
                val samples = DoubleArray(SHARPNESS_SAMPLES) { Thread.sleep(SAMPLE_GAP_MS); latestSharpness.get() }
                return samples.average()
            }
            val far = sharpnessAt(0f)
            val near = sharpnessAt(minFocus)
            val farAgain = sharpnessAt(0f)
            session.stopRepeating()
            session.close()

            val farMean = (far + farAgain) / 2
            val maxS = maxOf(farMean, near)
            val change = if (maxS > 0) abs(farMean - near) / maxS else 0.0
            val jitter = if (maxS > 0) abs(far - farAgain) / maxS else 1.0
            return AfProbeEvidence(
                minFocusDiopters = minFocus,
                stateMachineScanned = stateScanned,
                sharpnessChange = change,
                sharpnessJitter = jitter,
                reportedDistanceSpan = if (dMax.get() >= dMin.get()) dMax.get() - dMin.get() else 0f,
                reportedMoving = moving.get()
            )
        } finally {
            reader.close()
            device.close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openDevice(id: String, handler: Handler): CameraDevice? {
        // The main camera may still be closing: retry briefly on in-use errors.
        repeat(OPEN_ATTEMPTS) { attempt ->
            val latch = CountDownLatch(1)
            var opened: CameraDevice? = null
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { opened = camera; latch.countDown() }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); latch.countDown() }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w(LOG_TAG, "AF probe open $id error $error (attempt $attempt)")
                    camera.close(); latch.countDown()
                }
            }, handler)
            latch.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            opened?.let { return it }
            Thread.sleep(OPEN_RETRY_MS)
        }
        return null
    }

    private fun createSession(device: CameraDevice, output: OutputConfiguration, handler: Handler): CameraCaptureSession? {
        val latch = CountDownLatch(1)
        var result: CameraCaptureSession? = null
        val executor = Executor { handler.post(it) }
        device.createCaptureSession(SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR, listOf(output), executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) { result = session; latch.countDown() }
                override fun onConfigureFailed(session: CameraCaptureSession) { latch.countDown() }
            }
        ))
        latch.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return result
    }

    /** Mean |dx|+|dy| over a centre crop of the Y plane, subsampled by 2. */
    private fun ySharpness(image: android.media.Image): Double {
        val plane = image.planes[0]
        val buf = plane.buffer
        val stride = plane.rowStride
        val px = plane.pixelStride
        val w = image.width
        val h = image.height
        val x0 = w / 4; val x1 = 3 * w / 4 - 2
        val y0 = h / 4; val y1 = 3 * h / 4 - 2
        var sum = 0L
        var n = 0L
        var y = y0
        while (y < y1) {
            var x = x0
            val row = y * stride
            while (x < x1) {
                val c = buf.get(row + x * px).toInt() and 0xFF
                val r = buf.get(row + (x + 1) * px).toInt() and 0xFF
                val d = buf.get(row + stride + x * px).toInt() and 0xFF
                sum += abs(c - r) + abs(c - d)
                n++
                x += 2
            }
            y += 2
        }
        return if (n > 0) sum.toDouble() / n else 0.0
    }

    private companion object {
        const val LOG_TAG = "AfScanProbe"
        const val OPEN_ATTEMPTS = 4
        const val OPEN_TIMEOUT_MS = 3000L
        const val OPEN_RETRY_MS = 400L
        const val WARMUP_MS = 600L
        const val SCAN_MS = 1500L
        const val SETTLE_MS = 500L
        const val SHARPNESS_SAMPLES = 4
        const val SAMPLE_GAP_MS = 50L
    }
}
