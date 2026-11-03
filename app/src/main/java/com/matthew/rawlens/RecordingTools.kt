// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.app.AlertDialog
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors

/** Activity-owned recording tools. All UI and focus requests run on the main thread. */
internal class RecordingTools(private val activity: MainActivity) : AutoCloseable {
    private val health = RecordingHealth()
    private val durations = longArrayOf(500, 1200, 2500, 5000)
    private val sleepDelays = intArrayOf(0, 5, 10, 30, 60)
    private var durationIndex = durations.indexOf(activity.lensPreferences().getLong(KEY_DURATION, 1200L))
        .takeIf { it >= 0 } ?: 1
    private var lensId: String? = null
    private var manualFocusSupported = false
    private var pointA: Long? = null
    private var pointB: Long? = null
    private var focusPull: Runnable? = null
    private var lastInteractionMs = 0L
    private var sleeping = false
    val isPreviewSleeping: Boolean get() = sleeping
    private var bound = false
    private var lastStoragePollMs = 0L
    private var generation = 0
    private val storageWorker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RecordingHealthStorage").apply { isDaemon = true }
    }
    @Volatile private var storagePending = false
    private var freeBytes: Long? = null
    var healthLine: String = "HEALTH STARTING"
        private set

    private fun view(id: Int): View = activity.findViewById(id)
    private fun text(id: Int): TextView = activity.findViewById(id)
    private fun now() = SystemClock.elapsedRealtime()
    private fun sleepDelay(): Int = activity.lensPreferences().getInt(KEY_SLEEP, 0)
        .takeIf { it in sleepDelays } ?: 0

    fun bind() {
        bound = true
        view(R.id.recordingSleepOverlay).setOnClickListener { wakePreview() }
        view(R.id.recordingPreviewSleep).setOnClickListener {
            if (activity.videoRecording) {
                setSleeping(!sleeping)
                activity.hideQuickControls()
            } else chooseSleepDelay()
        }
        view(R.id.recordingPreviewSleep).setOnLongClickListener { chooseSleepDelay(); true }
        for ((id, isA) in listOf(R.id.focusMarkA to true, R.id.focusMarkB to false)) {
            view(id).setOnClickListener {
                checkLens()
                val target = if (isA) pointA else pointB
                if (target == null) saveMark(isA) else pullTo(target)
            }
            view(id).setOnLongClickListener { saveMark(isA); true }
        }
        view(R.id.focusPullDuration).setOnClickListener {
            cancelFocusPull()
            durationIndex = (durationIndex + 1) % durations.size
            activity.lensPreferences().edit().putLong(KEY_DURATION, durations[durationIndex]).apply()
            activity.scheduleManualPanelHide()
            refreshButtons()
        }
        view(R.id.focusPullDuration).setOnLongClickListener {
            cancelFocusPull()
            pointA = null
            pointB = null
            refreshButtons()
            activity.setStatus("FOCUS MARKS CLEARED")
            true
        }
        refreshButtons()
    }

    /** Focus tools share the existing slider header; the main capture panel never grows. */
    fun show() {
        checkLens()
        if (bound) {
            view(R.id.recordingToolsRow).visibility = if (activity.isVideoMode &&
                activity.activeManualControl == ManualControl.FOCUS_DISTANCE &&
                activity.manualPanel.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            refreshButtons()
        }
    }
    fun hide() { if (bound) view(R.id.recordingToolsRow).visibility = View.GONE }

    fun start() {
        stop()
        health.reset()
        freeBytes = null
        healthLine = "HEALTH STARTING"
        lastInteractionMs = now()
        lastStoragePollMs = 0L
    }

    fun stop() {
        generation++
        cancelFocusPull()
        wakePreview()
    }

    fun onInteraction() {
        // A touch interrupts a scheduled sleep, including a long focus drag.
        lastInteractionMs = now()
    }

    fun tick() {
        if (!bound) return
        checkLens()
        refreshButtons()
        if (!activity.videoRecording) return
        val delay = sleepDelay()
        if (!sleeping && delay > 0 && now() - lastInteractionMs >= delay * 1000L) setSleeping(true)
        if (!storagePending && now() - lastStoragePollMs >= 2_000) {
            val probe = activity.recordingStorageProbe() ?: return
            lastStoragePollMs = now()
            val epoch = generation
            storagePending = true
            storageWorker.execute {
                val available = runCatching { probe() }.getOrNull()
                activity.runOnUiThread {
                    storagePending = false
                    if (epoch == generation) freeBytes = available
                }
            }
        }
    }

    fun updateHealth(s: RawVideoRecorder.VideoStats) {
        updateHealth(s.elapsedMs, s.framesDropped, s.fileBytes, s.fps, RawVideoRecorder.FPS,
            s.queueDepth >= 4 || s.encodeMsAvg >
                1000f / RawVideoRecorder.FPS * (activity.videoRecorder?.encodeWorkers ?: 1) ||
                s.writeMsAvg > 1000f / RawVideoRecorder.FPS)
    }

    fun updateHealth(s: EncodedVideoRecorder.VideoStats) {
        updateHealth(s.elapsedMs, s.framesDropped, s.fileBytes, s.outputFps, activity.encodedVideoFpsPref(),
            s.ioBusyPct >= 85 || s.encBusyPct >= 85 ||
                s.cpuChainMsAvg > 1000f / activity.encodedVideoFpsPref())
    }

    private fun updateHealth(elapsed: Long, drops: Int, bytes: Long, fps: Float, target: Int, pressure: Boolean) {
        val thermal = runCatching {
            activity.getSystemService(PowerManager::class.java).currentThermalStatus
        }.getOrDefault(0)
        healthLine = health.update(elapsed, drops, bytes, fps, target, pressure, thermal, freeBytes)
        freeBytes?.let { healthLine += String.format(Locale.US, " · %.1fGB free", it / 1e9) }
    }

    fun wakePreview() {
        lastInteractionMs = now()
        if (bound && sleeping) setSleeping(false)
    }

    private fun setSleeping(value: Boolean) {
        if (value && !activity.videoRecording) return
        sleeping = value
        activity.videoRecorder?.monitoringSuspended = value
        activity.encodedVideoRecorder?.monitoringSuspended = value
        view(R.id.recordingSleepOverlay).visibility = if (value) View.VISIBLE else View.GONE
        if (value) {
            activity.hideManualControl()
            activity.hideQuickControls()
            activity.findViewById<RawExposureOverlayView>(R.id.rawExposureOverlay).clear()
            activity.setFocusPeakingVisible(false)
        }
        activity.syncScopeVisibility()
        if (!value) lastInteractionMs = now()
        refreshButtons()
    }

    fun sleepSettingLabel(): String = "Preview sleep: " +
        sleepDelay().let { if (it == 0) "Never" else "after ${it}s idle" }

    fun chooseSleepDelay(after: () -> Unit = {}) {
        AlertDialog.Builder(activity).setTitle("Preview sleep during recording")
            .setSingleChoiceItems(arrayOf("Never", "After 5 seconds", "After 10 seconds", "After 30 seconds", "After 60 seconds"),
                sleepDelays.indexOf(sleepDelay())) { dialog, index ->
                activity.lensPreferences().edit().putInt(KEY_SLEEP, sleepDelays[index]).apply()
                wakePreview()
                after()
                dialog.dismiss()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun checkLens() {
        val selected = activity.controller.lensOptions().firstOrNull { it.selected }?.cameraId
        if (selected != lensId) {
            cancelFocusPull()
            lensId = selected
            manualFocusSupported = selected?.let { id ->
                val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                runCatching { resolveVideoCameraRoute(manager, id)?.characteristics
                    ?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                    ?.contains(CaptureRequest.CONTROL_AF_MODE_OFF) == true }.getOrDefault(false)
            } ?: false
            pointA = null
            pointB = null
        }
    }

    private fun focusRange(): ManualControlRange? {
        if (!manualFocusSupported) return null
        return activity.manualRangeFor(ManualControl.FOCUS_DISTANCE)?.takeIf { it.maximum > it.minimum }
    }

    private fun saveMark(isA: Boolean) {
        checkLens()
        cancelFocusPull()
        val range = focusRange() ?: return
        activity.scheduleManualPanelHide()
        if (isA) pointA = range.current else pointB = range.current
        refreshButtons()
        activity.setStatus("FOCUS ${if (isA) "A" else "B"} SAVED · ${activity.formatFocusDistance(range.current)}")
    }

    private fun pullTo(target: Long) {
        checkLens()
        val range = focusRange() ?: return
        cancelFocusPull()
        wakePreview()
        activity.pendingSliderUpdate?.let(activity.manualSlider::removeCallbacks)
        activity.pendingSliderUpdate = null
        activity.cancelManualPanelHide()
        val started = now()
        val duration = durations[durationIndex]
        val initialLens = lensId
        val destination = target.coerceIn(range.minimum, range.maximum)
        val pull = object : Runnable {
            override fun run() {
                checkLens()
                if (focusPull !== this || !activity.isVideoMode || lensId != initialLens) return
                val currentRange = focusRange() ?: run { cancelFocusPull(); return }
                val elapsed = now() - started
                val value = FocusPull.value(range.current, destination, elapsed, duration)
                    .coerceIn(currentRange.minimum, currentRange.maximum)
                activity.setManualValueFor(ManualControl.FOCUS_DISTANCE, value, fromFocusPull = true)
                if (elapsed < duration) view(R.id.focusMarkA).postDelayed(this, 33L)
                else { focusPull = null; refreshButtons(); activity.scheduleManualPanelHide() }
            }
        }
        focusPull = pull
        view(R.id.focusMarkA).post(pull)
        refreshButtons()
    }

    fun cancelFocusPull() {
        focusPull?.let { if (bound) view(R.id.focusMarkA).removeCallbacks(it) }
        focusPull = null
    }

    private fun refreshButtons() {
        if (!bound) return
        val enabled = activity.isVideoMode && focusRange() != null
        for ((id, mark, name) in listOf(Triple(R.id.focusMarkA, pointA, "A"), Triple(R.id.focusMarkB, pointB, "B"))) {
            text(id).apply {
                text = if (mark == null) "Set $name" else "→ $name"
                isEnabled = enabled
                alpha = if (enabled) 1f else .4f
                contentDescription = if (mark == null) "Save focus mark $name at current focus" else
                    "Pull to focus $name, ${activity.formatFocusDistance(mark)}. Hold to replace with current focus."
            }
        }
        text(R.id.focusPullDuration).apply {
            text = String.format(Locale.US, "%s%.1fs", if (focusPull != null) "■ " else "", durations[durationIndex] / 1000f)
            isEnabled = enabled
            alpha = if (enabled) 1f else .4f
            contentDescription = "Focus pull duration $text. Tap to change duration and stop pull. Hold to clear marks."
        }
        text(R.id.recordingPreviewSleep).apply {
            text = if (sleeping) "Wake" else "Preview"
            contentDescription = if (sleeping) "Wake recording preview" else if (activity.videoRecording)
                "Sleep recording preview now. Hold for sleep timer." else sleepSettingLabel()
        }
    }

    override fun close() {
        stop()
        storageWorker.shutdownNow()
    }

    companion object {
        const val KEY_SLEEP = "video_preview_sleep_seconds"
        private const val KEY_DURATION = "video_focus_pull_duration_ms"
    }
}
