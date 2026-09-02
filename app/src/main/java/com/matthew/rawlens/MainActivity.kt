// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Button
import android.widget.SeekBar
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.EditText
import android.widget.ScrollView
import android.text.InputType
import java.util.Locale

import android.view.WindowInsets
import android.view.WindowInsetsController
import android.os.Build

class MainActivity : Activity(), SensorEventListener {
    private lateinit var controller: RawCameraController
    private lateinit var status: TextView

    private lateinit var isoControl: TextView
    private lateinit var shutterControl: TextView
    private lateinit var wbControl: TextView
    private lateinit var focusControl: TextView
    private lateinit var evControl: TextView
    private lateinit var lensSwitcher: LinearLayout
    private lateinit var flashControl: TextView
    private lateinit var lensDiscovery: LensDiscovery
    private lateinit var dngMetadataOverrideStore: DngMetadataOverrideStore
    private lateinit var manualPanel: LinearLayout
    private lateinit var manualName: TextView
    private lateinit var manualValue: TextView
    private lateinit var manualMin: TextView
    private lateinit var manualMax: TextView
    private lateinit var manualSlider: SeekBar
    private lateinit var manualAuto: Button
    private var activeManualControl: ManualControl? = null
    private var pendingSliderUpdate: Runnable? = null
    private var manualPanelHide: Runnable? = null
    private lateinit var debugOverlay: TextView
    private lateinit var guideOverlay: CameraGuideOverlay
    private lateinit var histogramView: HistogramView
    private lateinit var meteringOverlay: FocusMeteringOverlay
    private lateinit var quickPanel: LinearLayout
    private lateinit var timerBadge: TextView
    private lateinit var modeButton: TextView
    private lateinit var flashButton: ImageButton
    private lateinit var rawBadge: TextView
    private lateinit var rawStatusGroup: View
    private var captureFormat = CaptureFormat.DNG_ONLY
    private var rawZslStatus = RawZslStatus(RawZslState.OFF, "Disabled in settings")
    private var rawZslSettingsStatus: TextView? = null
    private var gridEnabled = true
    private var levelEnabled = false
    private var histogramEnabled = true
    private var aeMeteringMode = AeMeteringMode.AUTO
    private var timerSeconds = 0
    private var burstRelease = false
    private var captureExposureMode = CaptureExposureMode.AUTO
    private var rawSuperResolutionSettings = RawSuperResolutionSettings()
    private var countdownRunnable: Runnable? = null
    private var histogramRunnable: Runnable? = null
    private lateinit var sensorManager: SensorManager
    private var levelRotationSensor: Sensor? = null
    private var levelGravitySensor: Sensor? = null
    private var levelSensorRegistered = false
    private var activityResumed = false
    /** Camera stream sizing must use the final viewport, not the full-screen XML placeholder. */
    private var cameraViewportReady = false
    private val filteredGravity = FloatArray(3)
    private var hasGravitySample = false
    private val levelRotationMatrix = FloatArray(9)
    private val levelRemappedMatrix = FloatArray(9)
    private val levelOrientationAngles = FloatArray(3)
    private var filteredLevelRoll = 0f
    private var filteredLevelPitch = 0f
    private var hasLevelAngles = false
    private lateinit var orientationListener: OrientationEventListener
    private var deviceOrientationDegrees = 0
    private var controlRotationDegrees = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        burstRelease = lensPreferences().getBoolean(KEY_BURST_RELEASE, false)
        rawSuperResolutionSettings = RawSuperResolutionSettings(
            enabled = lensPreferences().getBoolean(KEY_RAW_SR_ENABLED, false),
            dngMode = RawSrDngMode.fromPreference(
                lensPreferences().getString(KEY_RAW_SR_DNG_MODE, null)
            )
        )
        captureExposureMode = CaptureExposureMode.entries.getOrElse(
            lensPreferences().getInt(KEY_CAPTURE_EXPOSURE_MODE, CaptureExposureMode.AUTO.ordinal)
        ) { CaptureExposureMode.AUTO }
        // Older builds exposed ZSL as a separate setting.  Treat that saved switch as the
        // capture intent during migration instead of allowing the saved mode to immediately
        // turn it back off when the controller starts.
        if (lensPreferences().getBoolean(KEY_RAW_ZSL, false)) {
            captureExposureMode = CaptureExposureMode.ZSL
        }
        if (rawSuperResolutionSettings.enabled) captureExposureMode = CaptureExposureMode.ZSL
        // Capture mode is now the single source of truth for ZSL.  Keeping the legacy flag in
        // sync also makes a process restart reproduce exactly what the mode button shows.
        lensPreferences().edit()
            .putInt(KEY_CAPTURE_EXPOSURE_MODE, captureExposureMode.ordinal)
            .putBoolean(KEY_RAW_ZSL, captureExposureMode == CaptureExposureMode.ZSL)
            .apply()
        captureFormat = CaptureFormat.fromPreference(
            lensPreferences().getString(KEY_CAPTURE_FORMAT, null)
        )
        
        // Full screen immersive mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }

        status = findViewById(R.id.status)
        isoControl = findViewById(R.id.isoControl)
        shutterControl = findViewById(R.id.shutterControl)
        wbControl = findViewById(R.id.wbControl)
        focusControl = findViewById(R.id.focusControl)
        evControl = findViewById(R.id.evControl)
        lensSwitcher = findViewById(R.id.lensSwitcher)
        flashControl = findViewById(R.id.flashControl)
        val dngInfo = findViewById<TextView>(R.id.dngInfo)
        val sensorInfo = findViewById<TextView>(R.id.sensorInfo)

        val viewfinder = findViewById<AutoFitTextureView>(R.id.viewfinder)
        val shutter = findViewById<View>(R.id.shutter)
        lensDiscovery = LensDiscovery(this)
        dngMetadataOverrideStore = DngMetadataOverrideStore(this)
        manualPanel = findViewById(R.id.manualControlPanel)
        manualName = findViewById(R.id.manualControlName)
        manualValue = findViewById(R.id.manualControlValue)
        manualMin = findViewById(R.id.manualControlMin)
        manualMax = findViewById(R.id.manualControlMax)
        manualSlider = findViewById(R.id.manualControlSlider)
        manualAuto = findViewById(R.id.manualAutoButton)
        debugOverlay = findViewById(R.id.debugOverlay)
        debugOverlay.visibility = if (lensPreferences().getBoolean(KEY_DEBUG_OVERLAY, false)) View.VISIBLE else View.GONE
        meteringOverlay = findViewById(R.id.focusMeteringOverlay)
        guideOverlay = findViewById(R.id.guideOverlay)
        histogramView = findViewById(R.id.histogramView)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        // The rotation vector gives a stable horizon even while the activity remains portrait.
        // Gravity remains a fallback for devices that do not expose it.
        levelRotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        levelGravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        quickPanel = findViewById(R.id.quickSettingsPanel)
        timerBadge = findViewById(R.id.timerBadge)
        modeButton = findViewById(R.id.modeButton)
        flashButton = findViewById(R.id.flashButton)
        rawBadge = findViewById(R.id.rawBadge)
        rawStatusGroup = findViewById(R.id.rawStatusGroup)
        refreshCaptureFormatControl()
        gridEnabled = lensPreferences().getBoolean(KEY_GRID, true)
        levelEnabled = lensPreferences().getBoolean(KEY_LEVEL, false)
        histogramEnabled = lensPreferences().getBoolean(KEY_HISTOGRAM, true)
        aeMeteringMode = AeMeteringMode.fromPreference(
            lensPreferences().getInt(KEY_AE_METERING_MODE, AeMeteringMode.AUTO.preferenceValue)
        )
        guideOverlay.gridEnabled = gridEnabled
        guideOverlay.levelEnabled = levelEnabled
        histogramView.visibility = if (histogramEnabled) View.VISIBLE else View.GONE

        orientationListener = object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_UI) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val degrees = ((orientation + 45) / 90 * 90) % 360
                if (degrees != deviceOrientationDegrees) applyDeviceOrientation(degrees, animate = true)
            }
        }

        controller = RawCameraController(this, viewfinder, 
            { message -> runOnUiThread { status.text = message } },
            { iso, shutterSpeed, wb ->
                runOnUiThread {
                    isoControl.text = controlText("ISO", iso.toString())
                    shutterControl.text = controlText("S", formatShutter(shutterSpeed))
                    if (wb > 0) wbControl.text = controlText("WB", "${wb}K")
                    updateAutomaticPanelValue(iso, shutterSpeed, wb)
                }
            },
            { dng, sensor ->
                runOnUiThread {
                    dngInfo.text = dng.replace('\n', ' ').replace("-bit RAW", "-BIT")
                    sensorInfo.text = sensor.replace('\n', ' ')
                }
            },
            { enabled ->
                runOnUiThread {
                    shutter.isEnabled = enabled
                    shutter.alpha = if (enabled) 1f else 0.45f
                    rawStatusGroup.isEnabled = enabled
                    rawStatusGroup.alpha = if (enabled) 1f else 0.6f
                }
            },
            { iso, shutterSpeed, wb, focus, ev, _, flash, flashEnabled ->
                runOnUiThread {
                    // Automatic ISO/shutter/WB are driven by every TotalCaptureResult through
                    // onMetadata above. The initial/static controls publication can arrive after
                    // the first result; never let its "A" placeholders overwrite live values.
                    if (!iso.endsWith(" A")) isoControl.text = controlText("ISO", iso)
                    if (shutterSpeed != "S A" && shutterSpeed != "A") {
                        shutterControl.text = controlText("S", shutterSpeed)
                    }
                    if (wb != "WB A" && wb != "A") wbControl.text = controlText("WB", wb)
                    focusControl.text = controlText("AF", focus)
                    evControl.text = controlText("EV", ev)
                    flashControl.text = flash
                    flashControl.isEnabled = flashEnabled
                    flashControl.alpha = if (flashEnabled) 1f else 0.4f
                    flashButton.isEnabled = flashEnabled
                    flashButton.alpha = if (!flashEnabled) 0.35f else if (flash.endsWith("ON")) 1f else 0.72f
                    flashButton.setColorFilter(getColor(if (flash.endsWith("ON")) R.color.accent else R.color.text_primary))
                    refreshLensSwitcher()
                    updateQuickControls()
                }
            },
            { selectedLensIds() },
            { lensPreferences().getString(KEY_LAST_CAMERA_ID, null) },
            lensPreferences().getBoolean(KEY_OIS, true),
            lensPreferences().getBoolean(KEY_RAW_ZSL, false),
            lensPreferences().getInt(KEY_RAW_ZSL_FRAME_COUNT, DEFAULT_RAW_ZSL_FRAME_COUNT),
            rawSuperResolutionSettings,
            dynamicExposureSettings(),
            aeMeteringMode,
            histogramEnabled,
            { cameraId -> dngMetadataOverrideStore.get(cameraId) },
            { zslStatus ->
                rawZslStatus = zslStatus
                runOnUiThread {
                    refreshCaptureFormatControl()
                    updateQuickControls()
                    rawZslSettingsStatus?.text = rawZslSettingsText(zslStatus)
                }
            },
            { debugText ->
                if (lensPreferences().getBoolean(KEY_DEBUG_OVERLAY, false)) {
                    runOnUiThread { debugOverlay.text = debugText }
                }
            },
            {
                runOnUiThread { meteringOverlay.clearTargets() }
            },
            { histogram ->
                if (histogramEnabled) runOnUiThread { histogramView.update(histogram) }
            }
        )
        // The mode switcher owns the practical capture intent; Settings remain advanced defaults.
        controller.setCaptureExposureMode(captureExposureMode)
        controller.setCaptureFormat(captureFormat)
        controller.setJpegOutputSettings(jpegOutputSettings())
        controller.setDenoiseSettings(denoiseSettings())
        shutter.setOnClickListener { triggerCapture(shutter, forceBurst = false) }
        shutter.setOnLongClickListener {
            triggerCapture(shutter, forceBurst = true)
            true
        }
        isoControl.setOnClickListener { openIsoControl() }
        shutterControl.setOnClickListener { openShutterControl() }
        wbControl.setOnClickListener { showManualControl(ManualControl.WHITE_BALANCE) }
        focusControl.setOnClickListener { showManualControl(ManualControl.FOCUS_DISTANCE) }
        evControl.setOnClickListener { showManualControl(ManualControl.EXPOSURE_COMPENSATION) }
        flashControl.setOnClickListener {
            hideManualControl()
            controller.toggleTorch()
        }
        flashButton.setOnClickListener {
            closeFloatingPanels()
            controller.toggleTorch()
        }
        findViewById<View>(R.id.timerButton).setOnClickListener { cycleTimer() }
        rawStatusGroup.setOnClickListener { cycleCaptureFormat() }
        modeButton.setOnClickListener { cycleCaptureExposureMode() }
        findViewById<View>(R.id.quickButton).setOnClickListener { toggleQuickControls() }
        findViewById<View>(R.id.gridQuick).setOnClickListener {
            gridEnabled = !gridEnabled
            guideOverlay.gridEnabled = gridEnabled
            lensPreferences().edit().putBoolean(KEY_GRID, gridEnabled).apply()
            updateQuickControls()
        }
        findViewById<View>(R.id.levelQuick).setOnClickListener {
            levelEnabled = !levelEnabled
            guideOverlay.levelEnabled = levelEnabled
            if (levelEnabled) {
                if (!registerLevelSensor()) status.text = "LEVEL SENSOR NOT AVAILABLE"
            } else {
                unregisterLevelSensor()
            }
            lensPreferences().edit().putBoolean(KEY_LEVEL, levelEnabled).apply()
            updateQuickControls()
        }
        findViewById<View>(R.id.histogramQuick).setOnClickListener {
            histogramEnabled = !histogramEnabled
            histogramView.visibility = if (histogramEnabled) View.VISIBLE else View.GONE
            lensPreferences().edit().putBoolean(KEY_HISTOGRAM, histogramEnabled).apply()
            controller.setRawHistogramEnabled(histogramEnabled)
            updateQuickControls()
            scheduleHistogram()
        }
        findViewById<View>(R.id.aeMeteringQuick).setOnClickListener { cycleAeMeteringMode() }
        findViewById<View>(R.id.rawSrQuick).setOnClickListener { toggleRawSuperResolution() }
        findViewById<View>(R.id.oisQuick).setOnClickListener {
            if (controller.isOisSupported()) {
                controller.toggleOis()
                lensPreferences().edit().putBoolean(KEY_OIS, controller.isOisEnabled()).apply()
                updateQuickControls()
            } else {
                status.text = "OIS NOT AVAILABLE"
            }
        }
        findViewById<View>(R.id.timerQuick).setOnClickListener { cycleTimer() }
        findViewById<View>(R.id.releaseQuick).setOnClickListener { toggleReleaseMode() }
        findViewById<View>(R.id.resetTargetsQuick).setOnClickListener {
            controller.resetMeteringTargets()
            status.text = "AF / AE TARGETS RESET"
            hideQuickControls()
        }
        findViewById<View>(R.id.settingsButton).setOnClickListener {
            hideManualControl()
            showSettings()
        }
        meteringOverlay.onAfPointChanged = { x, y ->
            controller.setAfPoint(x, y)
        }
        meteringOverlay.onAePointChanged = { x, y ->
            controller.setAePoint(x, y)
        }
        meteringOverlay.onOverlayTouched = { closeFloatingPanels() }
        manualSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val control = activeManualControl ?: return
                val range = controller.manualControlRange(control) ?: return
                val value = sliderValue(control, range, progress)
                manualValue.text = formatManualValue(control, value)
                manualAuto.isEnabled = true
                pendingSliderUpdate?.let(manualSlider::removeCallbacks)
                pendingSliderUpdate = Runnable { controller.setManualControl(control, value) }.also {
                    manualSlider.postDelayed(it, SLIDER_UPDATE_DELAY_MS)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                cancelManualPanelHide()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                pendingSliderUpdate?.let(manualSlider::removeCallbacks)
                activeManualControl?.let { control ->
                    controller.manualControlRange(control)?.let { range ->
                        controller.setManualControl(control, sliderValue(control, range, seekBar.progress))
                    }
                }
                scheduleManualPanelHide()
            }
        })
        manualAuto.setOnClickListener {
            activeManualControl?.let { control ->
                controller.setManualControl(control, null)
                showManualControl(control, allowToggle = false)
            }
        }
        updateQuickControls()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
        }
        if (!lensPreferences().getBoolean(KEY_LENS_SETUP_COMPLETE, false)) {
            showLensDiscovery(firstRun = true)
        }
        viewfinder.addOnLayoutChangeListener { view, left, top, right, bottom,
                                               oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                syncMeteringOverlayToViewfinder(view)
                syncGuideOverlayToViewfinder(view)
            }
        }
        findViewById<View>(R.id.controlPanel).post {
            positionViewfinderOverlays()
            cameraViewportReady = true
            startCameraWhenReady()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCameraWhenReady()
        }
        else status.text = "CAMERA PERMISSION NEEDED"
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        startCameraWhenReady()
        if (levelEnabled) registerLevelSensor()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        scheduleHistogram()
    }

    private fun startCameraWhenReady() {
        if (activityResumed && cameraViewportReady &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) {
            controller.start()
        }
    }

    override fun onPause() {
        activityResumed = false
        unregisterLevelSensor()
        orientationListener.disable()
        countdownRunnable?.let(window.decorView::removeCallbacks)
        countdownRunnable = null
        histogramRunnable?.let(histogramView::removeCallbacks)
        histogramRunnable = null
        controller.stop()
        super.onPause()
    }

    private fun registerLevelSensor(): Boolean {
        if (!activityResumed) return levelRotationSensor != null || levelGravitySensor != null
        if (levelSensorRegistered) return true
        hasGravitySample = false
        hasLevelAngles = false
        val sensor = levelRotationSensor ?: levelGravitySensor ?: return false
        levelSensorRegistered = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        return levelSensorRegistered
    }

    private fun unregisterLevelSensor() {
        if (!levelSensorRegistered) return
        sensorManager.unregisterListener(this)
        levelSensorRegistered = false
        hasGravitySample = false
        hasLevelAngles = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!levelEnabled || event.values.size < 3) return
        if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR ||
            event.sensor.type == Sensor.TYPE_ROTATION_VECTOR
        ) {
            updateLevelFromRotationVector(event.values)
            return
        }

        // Fallback for devices without a rotation-vector sensor.
        val smoothing = if (event.sensor.type == Sensor.TYPE_GRAVITY) 0.22f else 0.12f
        for (index in 0..2) {
            filteredGravity[index] = if (hasGravitySample) {
                filteredGravity[index] + smoothing * (event.values[index] - filteredGravity[index])
            } else {
                event.values[index]
            }
        }
        hasGravitySample = true

        val x = filteredGravity[0]
        val y = filteredGravity[1]
        val (screenX, screenY) = when (deviceOrientationDegrees) {
            90 -> -y to x
            180 -> -x to -y
            270 -> y to -x
            else -> x to y
        }
        val roll = Math.toDegrees(kotlin.math.atan2(-screenX, screenY).toDouble()).toFloat()
        val pitch = Math.toDegrees(
            kotlin.math.atan2(-filteredGravity[2], kotlin.math.sqrt(screenX * screenX + screenY * screenY)).toDouble()
        ).toFloat()
        publishLevelAngles(roll, pitch)
    }

    /** Matches PhotonCamera's horizon approach: remap a rotation vector into the current UI axes. */
    private fun updateLevelFromRotationVector(rotationVector: FloatArray) {
        SensorManager.getRotationMatrixFromVector(levelRotationMatrix, rotationVector)
        val (xAxis, yAxis) = when (deviceOrientationDegrees) {
            90 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            270 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        if (!SensorManager.remapCoordinateSystem(
                levelRotationMatrix, xAxis, yAxis, levelRemappedMatrix
            )
        ) return
        SensorManager.getOrientation(levelRemappedMatrix, levelOrientationAngles)
        publishLevelAngles(
            Math.toDegrees(levelOrientationAngles[2].toDouble()).toFloat(),
            Math.toDegrees(levelOrientationAngles[1].toDouble()).toFloat()
        )
    }

    private fun publishLevelAngles(roll: Float, pitch: Float) {
        val smoothing = 0.18f
        if (hasLevelAngles) {
            filteredLevelRoll += smoothing * shortestAngleDelta(filteredLevelRoll, roll)
            filteredLevelPitch += smoothing * shortestAngleDelta(filteredLevelPitch, pitch)
        } else {
            filteredLevelRoll = roll
            filteredLevelPitch = pitch
            hasLevelAngles = true
        }
        guideOverlay.updateLevel(filteredLevelRoll, filteredLevelPitch)
    }

    private fun shortestAngleDelta(from: Float, to: Float): Float =
        ((to - from + 540f) % 360f) - 180f

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        lensDiscovery.close()
        controller.destroy()
        super.onDestroy()
    }

    private fun positionViewfinderOverlays() {
        val panel = findViewById<LinearLayout>(R.id.controlPanel)
        val topBar = findViewById<LinearLayout>(R.id.topBar)
        val viewfinder = findViewById<AutoFitTextureView>(R.id.viewfinder)
        val root = panel.parent as View
        val clearance = dp(16)
        findViewById<View>(R.id.captureInfoRow).visibility = View.VISIBLE
        viewfinder.fillViewport = false
        // Reserve real screen space for controls.  The preview and its overlays are measured
        // only in the remaining viewport, so neither the image nor the thirds grid continues
        // behind ISO/shutter controls.
        val panelHeight = panel.height.takeIf { it > 0 } ?: dp(247)
        val availablePreviewHeight = (root.height - topBar.height - panelHeight).coerceAtLeast(0)
        val viewfinderParams = (viewfinder.layoutParams as FrameLayout.LayoutParams).apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = availablePreviewHeight
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = topBar.height
        }
        viewfinder.layoutParams = viewfinderParams
        viewfinder.translationX = 0f
        viewfinder.translationY = 0f
        syncMeteringOverlayToViewfinder(viewfinder)
        syncGuideOverlayToViewfinder(viewfinder)
        lensSwitcher.layoutParams = FrameLayout.LayoutParams(wrapContent(), wrapContent(),
            Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = dp(18)
            bottomMargin = panelHeight + clearance
        }
        manualPanel.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            wrapContent(), Gravity.END or Gravity.BOTTOM
        ).apply {
            marginStart = dp(18)
            marginEnd = dp(18)
            bottomMargin = panelHeight + clearance
        }
        quickPanel.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            wrapContent(), Gravity.END or Gravity.BOTTOM
        ).apply {
            marginStart = dp(14)
            marginEnd = dp(14)
            bottomMargin = panelHeight + dp(12)
        }
        (histogramView.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            params.gravity = Gravity.START or Gravity.BOTTOM
            params.marginStart = dp(16)
            params.bottomMargin = panelHeight + dp(16)
            histogramView.layoutParams = params
        }
        guideOverlay.setContentInsets(top = 0, end = 0, bottom = 0)
        applyDeviceOrientation(deviceOrientationDegrees, animate = false)
    }

    private fun applyDeviceOrientation(degrees: Int, animate: Boolean) {
        deviceOrientationDegrees = degrees
        if (::controller.isInitialized) controller.onDeviceOrientationChanged(degrees)

        // Keep a single portrait activity/camera session.  Every foreground control rotates
        // around its own center, while its position and touch target remain stable.
        val desired = -degrees.toFloat()
        val delta = ((desired - controlRotationDegrees + 540f) % 360f) - 180f
        controlRotationDegrees += delta
        rotatingControls().forEach { view ->
            if (view is RotatingContent) {
                view.contentRotation = controlRotationDegrees
            } else {
                view.animate().cancel()
                if (animate) view.animate().rotation(controlRotationDegrees).setDuration(180L).start()
                else view.rotation = controlRotationDegrees
            }
        }
        wholeRotatingOverlays().forEach { view ->
            view.animate().cancel()
            if (animate) view.animate().rotation(controlRotationDegrees).setDuration(180L).start()
            else view.rotation = controlRotationDegrees
        }
        if (::lensSwitcher.isInitialized) {
            for (index in 0 until lensSwitcher.childCount) {
                (lensSwitcher.getChildAt(index) as? RotatingContent)?.contentRotation =
                    controlRotationDegrees
            }
        }
        positionWholeRotatedPanels()
    }

    /** Keep diagnostics and the histogram clear of the top controls and capture panel. */
    private fun positionWholeRotatedPanels() {
        val quarterTurn = deviceOrientationDegrees == 90 || deviceOrientationDegrees == 270
        val debugParams = (debugOverlay.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(wrapContent(), wrapContent())
        debugParams.gravity = Gravity.TOP or Gravity.START
        debugParams.leftMargin = dp(16)
        // A 90-degree rotation makes the visual height equal to the measured width. Add half
        // that difference so the rotated panel stays below the top bar instead of leaving text
        // outside the screen.
        val debugRotationInset = if (quarterTurn && debugOverlay.width > debugOverlay.height) {
            (debugOverlay.width - debugOverlay.height) / 2
        } else 0
        debugParams.topMargin = dp(84) + debugRotationInset
        debugOverlay.layoutParams = debugParams
        debugOverlay.translationX = 0f
        debugOverlay.translationY = 0f
        val histogram = histogramView
        if (histogram.width > 0 && histogram.height > 0) {
            (histogram.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                val panelHeight = findViewById<View>(R.id.controlPanel).height.takeIf { it > 0 } ?: dp(247)
                val rotationInset = if (quarterTurn && histogram.width > histogram.height) {
                    (histogram.width - histogram.height) / 2
                } else 0
                params.bottomMargin = panelHeight + dp(16) + rotationInset
                histogram.layoutParams = params
            }
            histogram.translationX = 0f
            histogram.translationY = 0f
        }
    }

    private fun rotatingControls(): List<View> = listOfNotNull(
        findViewById(R.id.settingsButton), findViewById(R.id.flashButton),
        findViewById(R.id.timerButton), findViewById(R.id.timerBadge),
        findViewById(R.id.isoControl),
        findViewById(R.id.shutterControl), findViewById(R.id.wbControl),
        findViewById(R.id.focusControl), findViewById(R.id.evControl), findViewById(R.id.flashControl),
        findViewById(R.id.dngInfo), findViewById(R.id.sensorInfo),
        findViewById(R.id.quickButton), findViewById(R.id.modeButton),
        findViewById(R.id.quickPanelTitle), findViewById(R.id.quickPanelHint),
        findViewById(R.id.gridQuick), findViewById(R.id.levelQuick),
        findViewById(R.id.histogramQuick), findViewById(R.id.aeMeteringQuick),
        findViewById(R.id.timerQuick), findViewById(R.id.releaseQuick),
        findViewById(R.id.resetTargetsQuick), findViewById(R.id.oisQuick), findViewById(R.id.rawSrQuick),
        findViewById(R.id.manualControlName), findViewById(R.id.manualControlValue),
        findViewById(R.id.manualControlMin), findViewById(R.id.manualControlMax),
        findViewById(R.id.manualAutoButton)
    )

    /** These overlays need their backgrounds/graphs rotated with their content. */
    private fun wholeRotatingOverlays(): List<View> = listOfNotNull(
        findViewById(R.id.rawBadge),
        findViewById(R.id.status),
        findViewById(R.id.debugOverlay),
        findViewById(R.id.histogramView)
    )

    private fun syncMeteringOverlayToViewfinder(viewfinder: View) {
        if (viewfinder.width <= 0 || viewfinder.height <= 0) return
        val params = (meteringOverlay.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(viewfinder.width, viewfinder.height, Gravity.TOP or Gravity.START)
        params.width = viewfinder.width
        params.height = viewfinder.height
        params.gravity = Gravity.TOP or Gravity.START
        params.leftMargin = viewfinder.left
        params.topMargin = viewfinder.top
        meteringOverlay.layoutParams = params
        meteringOverlay.translationX = 0f
        meteringOverlay.translationY = 0f
    }

    private fun syncGuideOverlayToViewfinder(viewfinder: View) {
        if (viewfinder.width <= 0 || viewfinder.height <= 0) return
        val params = (guideOverlay.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(viewfinder.width, viewfinder.height, Gravity.TOP or Gravity.START)
        params.width = viewfinder.width
        params.height = viewfinder.height
        params.gravity = Gravity.TOP or Gravity.START
        params.leftMargin = viewfinder.left
        params.topMargin = viewfinder.top
        guideOverlay.layoutParams = params
        guideOverlay.translationX = 0f
        guideOverlay.translationY = 0f
        guideOverlay.setContentInsets(0, 0, 0)
    }

    private fun triggerCapture(shutter: View, forceBurst: Boolean) {
        closeFloatingPanels()
        shutter.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        shutter.animate().cancel()
        shutter.animate().scaleX(0.9f).scaleY(0.9f).setDuration(70L).withEndAction {
            shutter.animate().scaleX(1f).scaleY(1f).setDuration(110L).start()
        }.start()
        if (countdownRunnable != null) {
            status.text = "TIMER ALREADY RUNNING"
            return
        }
        if (timerSeconds == 0) {
            captureNow(forceBurst)
        } else {
            startCountdown(timerSeconds, forceBurst)
        }
    }

    private fun startCountdown(seconds: Int, forceBurst: Boolean) {
        status.text = "TIMER • $seconds"
        timerBadge.text = seconds.toString()
        timerBadge.visibility = View.VISIBLE
        countdownRunnable = Runnable {
            if (seconds <= 1) {
                countdownRunnable = null
                updateTimerBadge()
                captureNow(forceBurst)
            } else {
                startCountdown(seconds - 1, forceBurst)
            }
        }.also { window.decorView.postDelayed(it, 1_000L) }
    }

    private fun captureNow(forceBurst: Boolean) {
        val useBurst = forceBurst || burstRelease
        Log.i(
            LOG_TAG,
            "Shutter mode=${if (useBurst) "BURST_6" else "SINGLE"} " +
                "forceBurst=$forceBurst selectedBurst=$burstRelease"
        )
        if (useBurst) controller.captureBurst() else controller.capture()
    }

    private fun cycleTimer() {
        timerSeconds = when (timerSeconds) {
            0 -> 2
            2 -> 5
            else -> 0
        }
        findViewById<View>(R.id.timerButton).performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        status.text = if (timerSeconds == 0) "TIMER OFF" else "TIMER • ${timerSeconds}S"
        updateQuickControls()
    }

    private fun toggleReleaseMode() {
        burstRelease = !burstRelease
        lensPreferences().edit().putBoolean(KEY_BURST_RELEASE, burstRelease).apply()
        Log.i(LOG_TAG, "Release mode=${if (burstRelease) "BURST_6" else "SINGLE"}")
        status.text = if (burstRelease) "BURST • 6 RAW FRAMES" else "SINGLE FRAME"
        updateQuickControls()
    }

    private fun toggleRawSuperResolution() {
        val enabled = !rawSuperResolutionSettings.enabled
        if (enabled && captureExposureMode != CaptureExposureMode.ZSL) {
            applyCaptureExposureMode(CaptureExposureMode.ZSL)
        }
        if (applyRawSuperResolutionSettings(rawSuperResolutionSettings.copy(enabled = enabled))) {
            status.text = if (enabled) {
                "RAW SR ${rawSuperResolutionSettings.dngMode.label} • ZSL WARMING"
            } else {
                "RAW SR OFF • ZSL FRAMES SAVED SEPARATELY"
            }
        }
    }

    private fun applyRawSuperResolutionSettings(settings: RawSuperResolutionSettings): Boolean {
        if (!controller.setRawSuperResolutionSettings(settings)) {
            status.text = "RAW SR LOCKED WHILE SAVING"
            return false
        }
        rawSuperResolutionSettings = settings
        lensPreferences().edit()
            .putBoolean(KEY_RAW_SR_ENABLED, settings.enabled)
            .putString(KEY_RAW_SR_DNG_MODE, settings.dngMode.preferenceValue)
            .apply()
        updateQuickControls()
        return true
    }

    private fun rawSuperResolutionQuickText(): String {
        if (!rawSuperResolutionSettings.enabled) return "RAW SR\nOFF"
        val detail = when (rawZslStatus.state) {
            RawZslState.OFF, RawZslState.WARMING_UP -> "WARMING"
            RawZslState.ACTIVE -> {
                val count = rawSuperResolutionSettings.activeFrameCount(
                    lensPreferences().getInt(KEY_RAW_ZSL_FRAME_COUNT, DEFAULT_RAW_ZSL_FRAME_COUNT)
                )
                "${rawSuperResolutionSettings.dngMode.label} ×$count"
            }
            RawZslState.FALLBACK -> "UNAVAILABLE"
        }
        return "RAW SR\n$detail"
    }

    private fun cycleCaptureExposureMode() {
        val modes = CaptureExposureMode.entries
        applyCaptureExposureMode(modes[(modes.indexOf(captureExposureMode) + 1) % modes.size])
    }

    private fun cycleCaptureFormat() {
        val selected = captureFormat.next()
        if (!controller.setCaptureFormat(selected)) {
            status.text = "CAPTURE FORMAT LOCKED WHILE SAVING"
            return
        }
        captureFormat = selected
        lensPreferences().edit().putString(KEY_CAPTURE_FORMAT, selected.name).apply()
        rawStatusGroup.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        refreshCaptureFormatControl()
        status.text = when (selected) {
            CaptureFormat.JPEG -> "JPEG • RAW DEVELOPMENT"
            CaptureFormat.JPEG_DNG -> "JPEG + DNG • RAW DEVELOPMENT"
            CaptureFormat.DNG_ONLY -> "DNG ONLY"
        }
    }

    private fun refreshCaptureFormatControl() {
        rawBadge.text = captureFormat.badgeLabel
        rawBadge.setTextColor(getColor(R.color.accent))
        val zslLabel = when (rawZslStatus.state) {
            RawZslState.OFF -> "ZSL off"
            RawZslState.WARMING_UP -> "ZSL warming"
            RawZslState.ACTIVE -> "ZSL active"
            RawZslState.FALLBACK -> "ZSL unavailable"
        }
        val description =
            "Capture format ${captureFormat.badgeLabel}. $zslLabel. Tap to change format. ${rawZslStatus.detail}"
        rawBadge.contentDescription = description
        rawStatusGroup.contentDescription = description
    }

    private fun applyCaptureExposureMode(mode: CaptureExposureMode) {
        if (mode != CaptureExposureMode.ZSL && rawSuperResolutionSettings.enabled) {
            applyRawSuperResolutionSettings(rawSuperResolutionSettings.copy(enabled = false))
        }
        captureExposureMode = mode
        val dynamic = dynamicExposureSettings().copy(enabled = mode == CaptureExposureMode.PROGRAM)
        lensPreferences().edit()
            .putInt(KEY_CAPTURE_EXPOSURE_MODE, mode.ordinal)
            .putBoolean(KEY_RAW_ZSL, mode == CaptureExposureMode.ZSL)
            .putBoolean(KEY_DYNAMIC_EXPOSURE, dynamic.enabled)
            .apply()
        closeFloatingPanels()
        controller.setCaptureExposureMode(mode)
        modeButton.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        status.text = when (mode) {
            CaptureExposureMode.AUTO -> "AUTO AE"
            CaptureExposureMode.PROGRAM -> "PROGRAM • SHUTTER-FIRST AE"
            CaptureExposureMode.ZSL -> "ZSL • RAW MOTION"
            CaptureExposureMode.MANUAL -> "MANUAL EXPOSURE"
        }
        updateQuickControls()
    }

    private fun openIsoControl() {
        if (captureExposureMode == CaptureExposureMode.PROGRAM) {
            val values = intArrayOf(0, 400, 800, 1600, 3200, 6400)
            val settings = dynamicExposureSettings()
            val current = values.indexOf(settings.isoLimit).takeIf { it >= 0 } ?: 0
            val updated = settings.copy(isoLimit = values[(current + 1) % values.size])
            saveDynamicExposureSettings(updated)
            status.text = "PROGRAM ISO CEILING • ${updated.isoLimit.takeIf { it > 0 } ?: "SENSOR MAX"}"
            return
        }
        openManualModeThen(ManualControl.ISO)
    }

    private fun openShutterControl() {
        if (captureExposureMode == CaptureExposureMode.PROGRAM) {
            val values = longArrayOf(0L, 1_000_000_000L / 15, 1_000_000_000L / 30,
                1_000_000_000L / 60, 1_000_000_000L / 125, 1_000_000_000L / 250)
            val settings = dynamicExposureSettings()
            val updated = if (settings.useAutoSafeShutter) {
                settings.copy(useAutoSafeShutter = false, shutterLimitNanos = 0L)
            } else {
                val current = values.indexOf(settings.shutterLimitNanos).takeIf { it >= 0 } ?: 0
                val next = values[(current + 1) % values.size]
                settings.copy(
                    shutterLimitNanos = next,
                    useAutoSafeShutter = next == 0L && current == values.lastIndex
                )
            }
            saveDynamicExposureSettings(updated)
            status.text = "PROGRAM SHUTTER CEILING • " + when {
                updated.shutterLimitNanos > 0L -> formatShutter(updated.shutterLimitNanos)
                updated.useAutoSafeShutter -> "AUTO HANDHELD"
                else -> "SENSOR MAX"
            }
            return
        }
        openManualModeThen(ManualControl.SHUTTER)
    }

    private fun openManualModeThen(control: ManualControl) {
        if (captureExposureMode != CaptureExposureMode.MANUAL) applyCaptureExposureMode(CaptureExposureMode.MANUAL)
        isoControl.post { showManualControl(control) }
    }

    private fun saveDynamicExposureSettings(settings: DynamicExposureSettings) {
        lensPreferences().edit()
            .putBoolean(KEY_DYNAMIC_EXPOSURE, settings.enabled)
            .putFloat(KEY_DYNAMIC_EXPOSURE_BALANCE, settings.balance)
            .putInt(KEY_DYNAMIC_EXPOSURE_ISO_LIMIT, settings.isoLimit)
            .putLong(KEY_DYNAMIC_EXPOSURE_SHUTTER_LIMIT, settings.shutterLimitNanos)
            .putBoolean(KEY_DYNAMIC_EXPOSURE_AUTO_SHUTTER, settings.useAutoSafeShutter)
            .apply()
        controller.setDynamicExposureSettings(settings)
    }

    private fun cycleAeMeteringMode() {
        val modes = AeMeteringMode.entries
        val next = modes[(modes.indexOf(aeMeteringMode) + 1) % modes.size]
        if (!controller.setAeMeteringMode(next)) return
        aeMeteringMode = next
        lensPreferences().edit().putInt(KEY_AE_METERING_MODE, next.preferenceValue).apply()
        updateQuickControls()
    }

    private fun toggleQuickControls() {
        if (quickPanel.visibility == View.VISIBLE) hideQuickControls() else showQuickControls()
    }

    private fun showQuickControls() {
        hideManualControl()
        updateQuickControls()
        lensSwitcher.visibility = View.INVISIBLE
        quickPanel.alpha = 0f
        quickPanel.translationY = dp(18).toFloat()
        quickPanel.visibility = View.VISIBLE
        quickPanel.animate().alpha(1f).translationY(0f).setDuration(180L).start()
    }

    private fun hideQuickControls() {
        if (!::quickPanel.isInitialized || quickPanel.visibility != View.VISIBLE) return
        quickPanel.animate().cancel()
        quickPanel.visibility = View.GONE
        quickPanel.alpha = 1f
        quickPanel.translationY = 0f
        lensSwitcher.visibility = View.VISIBLE
    }

    private fun closeFloatingPanels() {
        hideManualControl()
        hideQuickControls()
    }

    private fun updateQuickControls() {
        val grid = findViewById<TextView>(R.id.gridQuick)
        val level = findViewById<TextView>(R.id.levelQuick)
        val histogram = findViewById<TextView>(R.id.histogramQuick)
        val aeMetering = findViewById<TextView>(R.id.aeMeteringQuick)
        val ois = findViewById<TextView>(R.id.oisQuick)
        val timer = findViewById<TextView>(R.id.timerQuick)
        val release = findViewById<TextView>(R.id.releaseQuick)
        val rawSr = findViewById<TextView>(R.id.rawSrQuick)
        grid.text = "GRID\n${if (gridEnabled) "THIRDS" else "OFF"}"
        level.text = "LEVEL\n${if (levelEnabled) "ON" else "OFF"}"
        histogram.text = "HISTOGRAM\n${if (histogramEnabled) "ON" else "OFF"}"
        aeMeteringMode = controller.getAeMeteringMode()
        aeMetering.text = "AE METER\n${aeMeteringMode.label}"
        val aeMeteringSupported = controller.isAeMeteringSupported()
        aeMetering.isEnabled = aeMeteringSupported
        aeMetering.alpha = if (aeMeteringSupported) 1f else 0.4f
        val oisSupported = controller.isOisSupported()
        val oisEnabled = controller.isOisEnabled()
        ois.text = "OIS\n${if (oisSupported) if (oisEnabled) "ON" else "OFF" else "N/A"}"
        ois.isEnabled = oisSupported
        ois.alpha = if (oisSupported) 1f else 0.4f
        timer.text = "TIMER\n${if (timerSeconds == 0) "OFF" else "${timerSeconds}S"}"
        release.text = "RELEASE\n${if (burstRelease) "BURST 6" else "SINGLE"}"
        rawSr.text = rawSuperResolutionQuickText()
        val rawSrAvailable = rawZslStatus.state != RawZslState.FALLBACK
        rawSr.isEnabled = rawSrAvailable
        rawSr.alpha = if (rawSrAvailable) 1f else 0.4f
        setQuickTileState(grid, gridEnabled)
        setQuickTileState(level, levelEnabled)
        setQuickTileState(histogram, histogramEnabled)
        setQuickTileState(aeMetering, aeMeteringMode != AeMeteringMode.AUTO)
        setQuickTileState(ois, oisEnabled)
        setQuickTileState(timer, timerSeconds > 0)
        setQuickTileState(release, burstRelease)
        setQuickTileState(rawSr, rawSuperResolutionSettings.enabled)
        updateTimerBadge()
        modeButton.text = when (captureExposureMode) {
            CaptureExposureMode.AUTO -> "A\nAUTO"
            CaptureExposureMode.PROGRAM -> "P\nPROGRAM"
            CaptureExposureMode.ZSL -> "Z\nZSL"
            CaptureExposureMode.MANUAL -> "M\nMANUAL"
        }
        val exposureModeActive = captureExposureMode != CaptureExposureMode.AUTO
        modeButton.background = getDrawable(if (exposureModeActive) R.drawable.control_chip_active else R.drawable.control_chip)
        modeButton.setTextColor(getColor(if (exposureModeActive) R.color.accent_dark else R.color.text_primary))
    }

    private fun setQuickTileState(tile: TextView, active: Boolean) {
        tile.background = getDrawable(if (active) R.drawable.control_chip_active else R.drawable.control_chip)
        tile.setTextColor(getColor(if (active) R.color.accent_dark else R.color.text_primary))
    }

    private fun updateTimerBadge() {
        timerBadge.text = timerSeconds.toString()
        timerBadge.visibility = if (timerSeconds > 0) View.VISIBLE else View.GONE
    }

    private fun scheduleHistogram() {
        if (!::histogramView.isInitialized) return
        histogramRunnable?.let(histogramView::removeCallbacks)
        histogramRunnable = null
        if (!histogramEnabled) return
        histogramRunnable = object : Runnable {
            override fun run() {
                if (!isFinishing && !isDestroyed && histogramEnabled) {
                    val preview = findViewById<AutoFitTextureView>(R.id.viewfinder)
                    if (preview.isAvailable) histogramView.update(preview.getBitmap(96, 54))
                    histogramView.postDelayed(this, HISTOGRAM_INTERVAL_MS)
                }
            }
        }.also { histogramView.post(it) }
    }

    private fun controlText(label: String, rawValue: String): String {
        val value = rawValue.removePrefix(label).trim().ifEmpty { "--" }
        return "$label\n$value"
    }

    private fun refreshLensSwitcher() {
        val options = controller.lensOptions()
        if (options.isEmpty()) return
        lensSwitcher.removeAllViews()
        options.forEachIndexed { index, option ->
            val button = RotatingTextView(this).apply {
                text = option.label
                setTextColor(getColor(if (option.selected) R.color.accent else R.color.text_primary))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                background = getDrawable(R.drawable.lens_switcher)
                alpha = if (option.selected) 1f else 0.78f
                contentDescription = "Switch to ${option.label} lens"
                setOnClickListener {
                    hideManualControl()
                    lensPreferences().edit().putString(KEY_LAST_CAMERA_ID, option.cameraId).apply()
                    controller.selectLens(option.cameraId)
                }
            }
            lensSwitcher.addView(button, LinearLayout.LayoutParams(wrapContent(), wrapContent()).apply {
                if (index > 0) topMargin = dp(6)
            })
            button.contentRotation = controlRotationDegrees
        }
    }

    private fun showManualControl(control: ManualControl, allowToggle: Boolean = true) {
        hideQuickControls()
        if (allowToggle && activeManualControl == control && manualPanel.visibility == View.VISIBLE) {
            hideManualControl()
            return
        }
        val range = controller.manualControlRange(control)
        if (range == null) {
            status.text = "${controlName(control).uppercase(Locale.US)} NOT SUPPORTED"
            return
        }
        activeManualControl = control
        manualName.text = controlName(control)
        manualValue.text = formatManualValue(control, range.current)
        manualMin.text = formatManualValue(control, range.minimum)
        manualMax.text = formatManualValue(control, range.maximum)
        manualAuto.text = if (control == ManualControl.EXPOSURE_COMPENSATION) "RESET" else "AUTO"
        manualAuto.isEnabled = !range.automatic
        manualSlider.progress = sliderProgress(control, range)
        manualPanel.visibility = View.VISIBLE
        lensSwitcher.visibility = View.INVISIBLE
        scheduleManualPanelHide()
    }

    private fun scheduleManualPanelHide() {
        cancelManualPanelHide()
        manualPanelHide = Runnable { hideManualControl() }.also {
            manualPanel.postDelayed(it, MANUAL_PANEL_TIMEOUT_MS)
        }
    }

    private fun cancelManualPanelHide() {
        manualPanelHide?.let(manualPanel::removeCallbacks)
        manualPanelHide = null
    }

    private fun hideManualControl() {
        if (!::manualPanel.isInitialized) return
        cancelManualPanelHide()
        pendingSliderUpdate?.let(manualSlider::removeCallbacks)
        pendingSliderUpdate = null
        manualPanel.visibility = View.GONE
        lensSwitcher.visibility = View.VISIBLE
        activeManualControl = null
    }

    private fun sliderProgress(control: ManualControl, range: ManualControlRange): Int {
        if (range.maximum <= range.minimum) return 0
        val fraction = if (control == ManualControl.SHUTTER) {
            val minLog = kotlin.math.ln(range.minimum.toDouble())
            (kotlin.math.ln(range.current.toDouble()) - minLog) /
                (kotlin.math.ln(range.maximum.toDouble()) - minLog)
        } else {
            (range.current - range.minimum).toDouble() / (range.maximum - range.minimum)
        }
        return (fraction.coerceIn(0.0, 1.0) * SLIDER_STEPS).toInt()
    }

    private fun sliderValue(
        control: ManualControl,
        range: ManualControlRange,
        progress: Int
    ): Long {
        val fraction = progress.toDouble() / SLIDER_STEPS
        return if (control == ManualControl.SHUTTER) {
            kotlin.math.exp(
                kotlin.math.ln(range.minimum.toDouble()) + fraction *
                    (kotlin.math.ln(range.maximum.toDouble()) - kotlin.math.ln(range.minimum.toDouble()))
            ).toLong().coerceIn(range.minimum, range.maximum)
        } else {
            (range.minimum + fraction * (range.maximum - range.minimum)).toLong()
                .coerceIn(range.minimum, range.maximum)
        }
    }

    private fun updateAutomaticPanelValue(iso: Int, shutter: Long, wb: Int) {
        val control = activeManualControl ?: return
        val range = controller.manualControlRange(control) ?: return
        if (!range.automatic) return
        val value = when (control) {
            ManualControl.ISO -> iso.toLong()
            ManualControl.SHUTTER -> shutter
            ManualControl.WHITE_BALANCE -> wb.toLong()
            ManualControl.FOCUS_DISTANCE -> range.current
            ManualControl.EXPOSURE_COMPENSATION -> range.current
        }
        if (value > 0 || control == ManualControl.EXPOSURE_COMPENSATION) {
            manualValue.text = formatManualValue(control, value)
            manualSlider.progress = sliderProgress(control, range.copy(current = value.coerceIn(range.minimum, range.maximum)))
        }
    }

    private fun controlName(control: ManualControl): String = when (control) {
        ManualControl.ISO -> "ISO sensitivity"
        ManualControl.SHUTTER -> "Shutter speed"
        ManualControl.WHITE_BALANCE -> "White balance"
        ManualControl.FOCUS_DISTANCE -> "Manual focus"
        ManualControl.EXPOSURE_COMPENSATION -> "Exposure compensation"
    }

    private fun formatManualValue(control: ManualControl, value: Long): String = when (control) {
        ManualControl.ISO -> "ISO $value"
        ManualControl.SHUTTER -> formatShutter(value)
        ManualControl.WHITE_BALANCE -> "${value}K"
        ManualControl.FOCUS_DISTANCE -> formatFocusDistance(value)
        ManualControl.EXPOSURE_COMPENSATION -> String.format(
            Locale.US, "%+.1f EV", controller.exposureCompensationStops(value)
        )
    }

    private fun formatFocusDistance(scaledDiopters: Long): String {
        if (scaledDiopters <= 1L) return "∞"
        val meters = 1_000.0 / scaledDiopters
        return if (meters >= 10.0) String.format(Locale.US, "%.0f m", meters)
        else String.format(Locale.US, "%.1f m", meters)
    }

    private fun dynamicExposureSettings(): DynamicExposureSettings {
        val prefs = lensPreferences()
        return DynamicExposureSettings(
            enabled = prefs.getBoolean(KEY_DYNAMIC_EXPOSURE, false),
            balance = prefs.getFloat(KEY_DYNAMIC_EXPOSURE_BALANCE, 1f),
            isoLimit = prefs.getInt(KEY_DYNAMIC_EXPOSURE_ISO_LIMIT, 0),
            shutterLimitNanos = prefs.getLong(KEY_DYNAMIC_EXPOSURE_SHUTTER_LIMIT, 0L),
            useAutoSafeShutter = prefs.getBoolean(KEY_DYNAMIC_EXPOSURE_AUTO_SHUTTER, true)
        )
    }

    private fun jpegOutputSettings(): JpegOutputSettings = JpegOutputSettings(
        ultraHdr = lensPreferences().getBoolean(KEY_JPEG_ULTRA_HDR, false),
        displayP3 = lensPreferences().getBoolean(KEY_JPEG_DISPLAY_P3, false),
        agxPurityBoost = lensPreferences().getFloat(KEY_JPEG_AGX_PURITY, 1f),
        agxLook = runCatching {
            AgxLook.valueOf(lensPreferences().getString(KEY_JPEG_AGX_LOOK, AgxLook.BASE.name)!!)
        }.getOrDefault(AgxLook.BASE),
        agxContrast = lensPreferences().getFloat(KEY_JPEG_AGX_CONTRAST, 1f),
        agxSaturation = lensPreferences().getFloat(KEY_JPEG_AGX_SATURATION, 1f),
        agxHuePreservation = lensPreferences().getFloat(KEY_JPEG_AGX_HUE, 0f),
        agxShadowEv = lensPreferences().getFloat(KEY_JPEG_AGX_SHADOW_EV, 10f),
        agxHighlightEv = lensPreferences().getFloat(KEY_JPEG_AGX_HIGHLIGHT_EV, 6.5f),
        agxGamutCompression = lensPreferences().getFloat(KEY_JPEG_AGX_GAMUT, 0f),
        adaptiveExposureAuto = lensPreferences().getBoolean(KEY_JPEG_ADAPTIVE_EXPOSURE, true),
        adaptiveExposureProgramStrength = lensPreferences().let { preferences ->
            if (preferences.contains(KEY_JPEG_ADAPTIVE_PROGRAM)) {
                preferences.getFloat(KEY_JPEG_ADAPTIVE_PROGRAM, 0.5f)
            } else {
                preferences.getFloat(LEGACY_KEY_JPEG_ADAPTIVE_PHOTO, 0.5f)
            }
        }
    ).resolvedForPlatform()

    private fun denoiseSettings(): DenoiseSettings {
        val prefs = lensPreferences()
        return DenoiseSettings(
            enabled = prefs.getBoolean(KEY_DENOISE_ENABLED, false),
            rawPrefilterEnabled = prefs.getBoolean(KEY_DENOISE_RAW_ENABLED, true),
            rawPrefilterStrength = prefs.getFloat(KEY_DENOISE_RAW_STRENGTH, 0.20f).coerceIn(0f, 1f),
            chromaEnabled = prefs.getBoolean(KEY_DENOISE_CHROMA_ENABLED, true),
            chromaStrength = prefs.getFloat(KEY_DENOISE_CHROMA_STRENGTH, 1f).coerceIn(0f, 2f),
            lumaEnabled = prefs.getBoolean(KEY_DENOISE_LUMA_ENABLED, true),
            lumaCleanup = prefs.getFloat(KEY_DENOISE_LUMA_CLEANUP, 0.55f).coerceIn(0f, 1f),
            grainRetention = prefs.getFloat(KEY_DENOISE_GRAIN, 0.85f).coerceIn(0f, 1f),
            edgeProtection = prefs.getFloat(KEY_DENOISE_EDGE, 0.90f).coerceIn(0f, 1f),
            filmGrainEnabled = prefs.getBoolean(KEY_DENOISE_FILM_GRAIN_ENABLED, true),
            filmGrainAmount = prefs.getFloat(KEY_DENOISE_FILM_GRAIN_AMOUNT, 0.22f).coerceIn(0f, 1f),
            filmGrainSize = prefs.getFloat(KEY_DENOISE_FILM_GRAIN_SIZE, 0.35f).coerceIn(0f, 1f)
        )
    }

    private fun persistDenoiseSettings(settings: DenoiseSettings): Boolean {
        if (!controller.setDenoiseSettings(settings)) {
            status.text = "DENOISE SETTINGS APPLY AFTER SAVES FINISH"
            return false
        }
        lensPreferences().edit()
            .putBoolean(KEY_DENOISE_ENABLED, settings.enabled)
            .putBoolean(KEY_DENOISE_RAW_ENABLED, settings.rawPrefilterEnabled)
            .putFloat(KEY_DENOISE_RAW_STRENGTH, settings.rawPrefilterStrength)
            .putBoolean(KEY_DENOISE_CHROMA_ENABLED, settings.chromaEnabled)
            .putFloat(KEY_DENOISE_CHROMA_STRENGTH, settings.chromaStrength)
            .putBoolean(KEY_DENOISE_LUMA_ENABLED, settings.lumaEnabled)
            .putFloat(KEY_DENOISE_LUMA_CLEANUP, settings.lumaCleanup)
            .putFloat(KEY_DENOISE_GRAIN, settings.grainRetention)
            .putFloat(KEY_DENOISE_EDGE, settings.edgeProtection)
            .putBoolean(KEY_DENOISE_FILM_GRAIN_ENABLED, settings.filmGrainEnabled)
            .putFloat(KEY_DENOISE_FILM_GRAIN_AMOUNT, settings.filmGrainAmount)
            .putFloat(KEY_DENOISE_FILM_GRAIN_SIZE, settings.filmGrainSize)
            .apply()
        return true
    }

    private fun showSettings() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val generalTab = Button(this).apply { text = "General" }
        val denoiseTab = Button(this).apply { text = "Denoise" }
        val lensesTab = Button(this).apply { text = "Lens discovery" }
        val aboutTab = Button(this).apply { text = "About" }
        tabs.addView(generalTab, LinearLayout.LayoutParams(0, wrapContent(), 1f))
        tabs.addView(denoiseTab, LinearLayout.LayoutParams(0, wrapContent(), 1f))
        tabs.addView(lensesTab, LinearLayout.LayoutParams(0, wrapContent(), 1f))
        tabs.addView(aboutTab, LinearLayout.LayoutParams(0, wrapContent(), 1f))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, dp(8))
        }
        container.addView(tabs)
        container.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        lateinit var dialog: AlertDialog
        fun showGeneralTab() {
            content.removeAllViews()
            content.addView(TextView(this).apply {
                text = "RAW capture"
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
            })
            content.addView(Button(this).apply {
                text = "DNG sensor calibration (levels • noise • color)"
                setOnClickListener { showDngMetadataOverrideEditor() }
            })
            content.addView(TextView(this).apply {
                text = "RAW JPEG output"
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                setPadding(0, dp(16), 0, 0)
            })
            var currentJpegSettings = jpegOutputSettings()
            fun applyJpegOutputSettings(settings: JpegOutputSettings): Boolean {
                val resolved = settings.resolvedForPlatform()
                if (!controller.setJpegOutputSettings(resolved)) {
                    status.text = "OUTPUT SETTINGS APPLY AFTER SAVES FINISH"
                    return false
                }
                currentJpegSettings = resolved
                lensPreferences().edit()
                    .putBoolean(KEY_JPEG_ULTRA_HDR, resolved.ultraHdr)
                    .putBoolean(KEY_JPEG_DISPLAY_P3, resolved.displayP3)
                    .putFloat(KEY_JPEG_AGX_PURITY, resolved.agxPurityBoost)
                    .putString(KEY_JPEG_AGX_LOOK, resolved.agxLook.name)
                    .putFloat(KEY_JPEG_AGX_CONTRAST, resolved.agxContrast)
                    .putFloat(KEY_JPEG_AGX_SATURATION, resolved.agxSaturation)
                    .putFloat(KEY_JPEG_AGX_HUE, resolved.agxHuePreservation)
                    .putFloat(KEY_JPEG_AGX_SHADOW_EV, resolved.agxShadowEv)
                    .putFloat(KEY_JPEG_AGX_HIGHLIGHT_EV, resolved.agxHighlightEv)
                    .putFloat(KEY_JPEG_AGX_GAMUT, resolved.agxGamutCompression)
                    .putBoolean(KEY_JPEG_ADAPTIVE_EXPOSURE, resolved.adaptiveExposureAuto)
                    .putFloat(KEY_JPEG_ADAPTIVE_PROGRAM, resolved.adaptiveExposureProgramStrength)
                    .apply()
                return true
            }
            content.addView(CheckBox(this).apply {
                text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    "Ultra HDR JPEG (Android 14+ gainmap)"
                } else {
                    "Ultra HDR JPEG (requires Android 14+)"
                }
                setTextColor(getColor(R.color.text_primary))
                isEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                isChecked = currentJpegSettings.ultraHdr
                setOnCheckedChangeListener { button, enabled ->
                    if (enabled == currentJpegSettings.ultraHdr) return@setOnCheckedChangeListener
                    if (!applyJpegOutputSettings(currentJpegSettings.copy(ultraHdr = enabled))) {
                        button.isChecked = currentJpegSettings.ultraHdr
                    }
                }
            })
            content.addView(CheckBox(this).apply {
                text = "Display P3 JPEG"
                setTextColor(getColor(R.color.text_primary))
                isChecked = currentJpegSettings.displayP3
                setOnCheckedChangeListener { button, enabled ->
                    if (enabled == currentJpegSettings.displayP3) return@setOnCheckedChangeListener
                    if (!applyJpegOutputSettings(currentJpegSettings.copy(displayP3 = enabled))) {
                        button.isChecked = currentJpegSettings.displayP3
                    }
                }
            })
            content.addView(Button(this).apply {
                fun refresh() {
                    text = "AgX look: ${currentJpegSettings.agxLook.name.lowercase().replaceFirstChar(Char::uppercase)}"
                }
                refresh()
                setOnClickListener {
                    val looks = AgxLook.entries
                    val next = looks[(currentJpegSettings.agxLook.ordinal + 1) % looks.size]
                    if (applyJpegOutputSettings(currentJpegSettings.copy(agxLook = next))) refresh()
                }
            })
            fun addAgxSlider(
                title: String,
                maximum: Int,
                initial: Int,
                format: (Int) -> String,
                update: (JpegOutputSettings, Int) -> JpegOutputSettings
            ) {
                var selected = initial.coerceIn(0, maximum)
                val label = TextView(this).apply {
                    text = "$title: ${format(selected)}"
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 14f
                    setPadding(dp(12), dp(8), dp(12), 0)
                }
                content.addView(label)
                content.addView(SeekBar(this).apply {
                    max = maximum
                    progress = selected
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            seekBar: SeekBar,
                            progress: Int,
                            fromUser: Boolean
                        ) {
                            selected = progress
                            label.text = "$title: ${format(selected)}"
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                        override fun onStopTrackingTouch(seekBar: SeekBar) {
                            if (!applyJpegOutputSettings(update(currentJpegSettings, selected))) {
                                selected = initial.coerceIn(0, maximum)
                                seekBar.progress = selected
                            }
                        }
                    })
                })
            }
            var purityPercent = (currentJpegSettings.agxPurityBoost * 100f).toInt().coerceIn(0, 200)
            val purityLabel = TextView(this).apply {
                text = "AgX purity boost: $purityPercent%"
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                setPadding(dp(12), dp(8), dp(12), 0)
            }
            content.addView(purityLabel)
            content.addView(SeekBar(this).apply {
                max = 200
                progress = purityPercent
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        purityPercent = progress
                        purityLabel.text = "AgX purity boost: $purityPercent%"
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        if (applyJpegOutputSettings(
                            currentJpegSettings.copy(agxPurityBoost = purityPercent / 100f)
                        )) {
                            status.text = "AGX PURITY • $purityPercent%"
                        }
                    }
                })
            })
            addAgxSlider(
                "AgX contrast", 100,
                ((currentJpegSettings.agxContrast - 0.5f) * 100f).toInt(),
                { "${it + 50}%" },
                { settings, value -> settings.copy(agxContrast = 0.5f + value / 100f) }
            )
            addAgxSlider(
                "AgX saturation", 200,
                (currentJpegSettings.agxSaturation * 100f).toInt(),
                { "$it%" },
                { settings, value -> settings.copy(agxSaturation = value / 100f) }
            )
            addAgxSlider(
                "Preserve hue", 100,
                (currentJpegSettings.agxHuePreservation * 100f).toInt(),
                { "$it%" },
                { settings, value -> settings.copy(agxHuePreservation = value / 100f) }
            )
            addAgxSlider(
                "Highlight range", 70,
                ((currentJpegSettings.agxHighlightEv - 3f) * 10f).toInt(),
                { String.format(Locale.US, "+%.1f EV", 3f + it / 10f) },
                { settings, value -> settings.copy(agxHighlightEv = 3f + value / 10f) }
            )
            addAgxSlider(
                "Shadow range", 100,
                ((currentJpegSettings.agxShadowEv - 4f) * 10f).toInt(),
                { String.format(Locale.US, "%.1f EV", 4f + it / 10f) },
                { settings, value -> settings.copy(agxShadowEv = 4f + value / 10f) }
            )
            addAgxSlider(
                "Gamut compression", 100,
                (currentJpegSettings.agxGamutCompression * 100f).toInt(),
                { "$it%" },
                { settings, value -> settings.copy(agxGamutCompression = value / 100f) }
            )
            content.addView(CheckBox(this).apply {
                text = "Adaptive development exposure (AUTO and ZSL)"
                setTextColor(getColor(R.color.text_primary))
                isChecked = currentJpegSettings.adaptiveExposureAuto
                setOnCheckedChangeListener { button, enabled ->
                    if (enabled == currentJpegSettings.adaptiveExposureAuto) return@setOnCheckedChangeListener
                    if (!applyJpegOutputSettings(
                        currentJpegSettings.copy(adaptiveExposureAuto = enabled)
                    )) button.isChecked = currentJpegSettings.adaptiveExposureAuto
                }
            })
            addAgxSlider(
                "PROGRAM adaptive exposure", 100,
                (currentJpegSettings.adaptiveExposureProgramStrength * 100f).toInt(),
                { "$it%" },
                { settings, value -> settings.copy(adaptiveExposureProgramStrength = value / 100f) }
            )
            content.addView(Button(this).apply {
                text = "Reset official AgX Base"
                setOnClickListener {
                    val official = JpegOutputSettings(
                        ultraHdr = currentJpegSettings.ultraHdr,
                        displayP3 = currentJpegSettings.displayP3,
                        adaptiveExposureAuto = currentJpegSettings.adaptiveExposureAuto,
                        adaptiveExposureProgramStrength = currentJpegSettings.adaptiveExposureProgramStrength
                    )
                    if (applyJpegOutputSettings(official)) {
                        status.text = "AGX RESET • OFFICIAL BASE"
                        showGeneralTab()
                    }
                }
            })
            content.addView(CheckBox(this).apply {
                text = "RAW zero shutter lag"
                setTextColor(getColor(R.color.text_primary))
                isChecked = captureExposureMode == CaptureExposureMode.ZSL
                setOnCheckedChangeListener { _, enabled ->
                    // Do not update the old ZSL preference independently: doing so left the
                    // Settings UI enabled while the capture mode restored AUTO on reopen.
                    if (enabled != (captureExposureMode == CaptureExposureMode.ZSL)) {
                        applyCaptureExposureMode(
                            if (enabled) CaptureExposureMode.ZSL else CaptureExposureMode.AUTO
                        )
                    }
                }
            })
            content.addView(CheckBox(this).apply {
                text = "RAW super-resolution merge"
                setTextColor(getColor(R.color.text_primary))
                isChecked = rawSuperResolutionSettings.enabled
                setOnCheckedChangeListener { button, enabled ->
                    if (enabled && captureExposureMode != CaptureExposureMode.ZSL) {
                        applyCaptureExposureMode(CaptureExposureMode.ZSL)
                    }
                    val applied = applyRawSuperResolutionSettings(
                        rawSuperResolutionSettings.copy(enabled = enabled)
                    )
                    if (applied) {
                        status.text = if (enabled) "RAW SR • ZSL WARMING" else "RAW SR OFF"
                    } else if (button.isChecked != rawSuperResolutionSettings.enabled) {
                        button.isChecked = rawSuperResolutionSettings.enabled
                    }
                }
            })
            content.addView(Button(this).apply {
                fun refresh() {
                    text = "RAW SR DNG: " + when (rawSuperResolutionSettings.dngMode) {
                        RawSrDngMode.LINEAR_RGB -> "LINEAR RGB (RECOMMENDED)"
                        RawSrDngMode.MOSAIC_SR -> "MOSAIC SR • RAWTHERAPEE"
                    }
                }
                refresh()
                setOnClickListener {
                    val mode = when (rawSuperResolutionSettings.dngMode) {
                        RawSrDngMode.LINEAR_RGB -> RawSrDngMode.MOSAIC_SR
                        RawSrDngMode.MOSAIC_SR -> RawSrDngMode.LINEAR_RGB
                    }
                    val applied = applyRawSuperResolutionSettings(
                        rawSuperResolutionSettings.copy(dngMode = mode)
                    )
                    refresh()
                    if (applied) status.text = "RAW SR DNG • ${mode.label}"
                }
            })
            var dynamicSettings = dynamicExposureSettings()
            fun applyDynamicSettings() {
                lensPreferences().edit()
                    .putBoolean(KEY_DYNAMIC_EXPOSURE, dynamicSettings.enabled)
                    .putFloat(KEY_DYNAMIC_EXPOSURE_BALANCE, dynamicSettings.balance)
                    .putInt(KEY_DYNAMIC_EXPOSURE_ISO_LIMIT, dynamicSettings.isoLimit)
                    .putLong(KEY_DYNAMIC_EXPOSURE_SHUTTER_LIMIT, dynamicSettings.shutterLimitNanos)
                    .putBoolean(KEY_DYNAMIC_EXPOSURE_AUTO_SHUTTER, dynamicSettings.useAutoSafeShutter)
                    .apply()
                controller.setDynamicExposureSettings(dynamicSettings)
            }
            content.addView(CheckBox(this).apply {
                text = "Dynamic exposure balance"
                setTextColor(getColor(R.color.text_primary))
                isChecked = dynamicSettings.enabled
                setOnCheckedChangeListener { _, enabled ->
                    dynamicSettings = dynamicSettings.copy(enabled = enabled)
                    applyDynamicSettings()
                }
            })
            val balanceLabel = TextView(this).apply {
                text = dynamicExposureBalanceText(dynamicSettings.balance)
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                setPadding(dp(12), dp(4), dp(12), 0)
            }
            content.addView(balanceLabel)
            content.addView(SeekBar(this).apply {
                max = 150
                progress = ((dynamicSettings.balance - 0.5f) * 100f).toInt().coerceIn(0, max)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        dynamicSettings = dynamicSettings.copy(balance = 0.5f + progress / 100f)
                        balanceLabel.text = dynamicExposureBalanceText(dynamicSettings.balance)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = applyDynamicSettings()
                })
            })
            val isoLimitButton = Button(this)
            val shutterLimitButton = Button(this)
            fun refreshDynamicButtons() {
                isoLimitButton.text = "ISO ceiling: " +
                    (dynamicSettings.isoLimit.takeIf { it > 0 }?.toString() ?: "SENSOR MAX")
                shutterLimitButton.text = "Shutter ceiling: " + when {
                    dynamicSettings.shutterLimitNanos > 0L -> formatShutter(dynamicSettings.shutterLimitNanos)
                    dynamicSettings.useAutoSafeShutter -> "AUTO HANDHELD"
                    else -> "SENSOR MAX"
                }
            }
            isoLimitButton.setOnClickListener {
                val values = intArrayOf(0, 400, 800, 1600, 3200, 6400)
                val next = (values.indexOf(dynamicSettings.isoLimit).takeIf { it >= 0 } ?: 0)
                dynamicSettings = dynamicSettings.copy(isoLimit = values[(next + 1) % values.size])
                refreshDynamicButtons(); applyDynamicSettings()
            }
            shutterLimitButton.setOnClickListener {
                val values = longArrayOf(0L, 1_000_000_000L / 15, 1_000_000_000L / 30, 1_000_000_000L / 60,
                    1_000_000_000L / 125, 1_000_000_000L / 250)
                if (dynamicSettings.useAutoSafeShutter) {
                    dynamicSettings = dynamicSettings.copy(useAutoSafeShutter = false, shutterLimitNanos = 0L)
                } else {
                    val next = (values.indexOf(dynamicSettings.shutterLimitNanos).takeIf { it >= 0 } ?: 0)
                    dynamicSettings = dynamicSettings.copy(shutterLimitNanos = values[(next + 1) % values.size])
                    if (dynamicSettings.shutterLimitNanos == 0L && next == values.lastIndex) {
                        dynamicSettings = dynamicSettings.copy(useAutoSafeShutter = true)
                    }
                }
                refreshDynamicButtons(); applyDynamicSettings()
            }
            refreshDynamicButtons()
            content.addView(isoLimitButton)
            content.addView(shutterLimitButton)
            var selectedFrameCount = lensPreferences()
                .getInt(KEY_RAW_ZSL_FRAME_COUNT, DEFAULT_RAW_ZSL_FRAME_COUNT)
                .coerceIn(MIN_RAW_ZSL_FRAMES, MAX_RAW_ZSL_FRAMES)
            val frameCountLabel = TextView(this).apply {
                text = rawZslFrameCountText(selectedFrameCount)
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                setPadding(dp(12), dp(8), dp(12), 0)
            }
            content.addView(frameCountLabel)
            content.addView(SeekBar(this).apply {
                max = MAX_RAW_ZSL_FRAMES - MIN_RAW_ZSL_FRAMES
                progress = selectedFrameCount - MIN_RAW_ZSL_FRAMES
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        selectedFrameCount = progress + MIN_RAW_ZSL_FRAMES
                        frameCountLabel.text = rawZslFrameCountText(selectedFrameCount)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        lensPreferences().edit()
                            .putInt(KEY_RAW_ZSL_FRAME_COUNT, selectedFrameCount)
                            .apply()
                        controller.setRawZslFrameCount(selectedFrameCount)
                        status.text = "ZSL BUFFER • $selectedFrameCount FRAMES"
                    }
                })
            })
            rawZslSettingsStatus = TextView(this).apply {
                text = rawZslSettingsText(rawZslStatus)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(dp(12), 0, dp(12), dp(16))
            }.also(content::addView)
            content.addView(TextView(this).apply {
                text = "Camera diagnostics"
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
            })
            content.addView(CheckBox(this).apply {
                text = "Camera2 debug overlay"
                setTextColor(getColor(R.color.text_primary))
                isChecked = lensPreferences().getBoolean(KEY_DEBUG_OVERLAY, false)
                setOnCheckedChangeListener { _, enabled ->
                    lensPreferences().edit().putBoolean(KEY_DEBUG_OVERLAY, enabled).apply()
                    debugOverlay.visibility = if (enabled) View.VISIBLE else View.GONE
                    if (enabled) debugOverlay.post { positionWholeRotatedPanels() }
                }
            })
            generalTab.isEnabled = false
            denoiseTab.isEnabled = true
            lensesTab.isEnabled = true
            aboutTab.isEnabled = true
        }
        fun showDenoiseTab() {
            rawZslSettingsStatus = null
            content.removeAllViews()
            var settings = denoiseSettings()
            val subordinate = ArrayList<View>()

            fun heading(title: String, description: String) {
                content.addView(TextView(this).apply {
                    text = title
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 16f
                    setPadding(0, dp(14), 0, dp(2))
                })
                content.addView(TextView(this).apply {
                    text = description
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 12f
                    setPadding(dp(12), 0, dp(12), dp(6))
                })
            }

            fun switch(
                title: String,
                description: String,
                checked: () -> Boolean,
                update: (Boolean) -> DenoiseSettings
            ): CheckBox {
                val box = CheckBox(this).apply {
                    text = "$title\n$description"
                    setTextColor(getColor(R.color.text_primary))
                    isChecked = checked()
                    setOnCheckedChangeListener { button, value ->
                        val previous = settings
                        val proposed = update(value)
                        if (persistDenoiseSettings(proposed)) settings = proposed
                        else if (button.isChecked != checked()) button.isChecked = checked()
                        if (previous.enabled != settings.enabled) {
                            subordinate.forEach { it.isEnabled = settings.enabled }
                        }
                    }
                }
                content.addView(box)
                return box
            }

            fun slider(
                title: String,
                description: String,
                maximum: Int,
                initial: Int,
                suffix: String = "%",
                update: (Int) -> DenoiseSettings
            ) {
                var selected = initial
                val label = TextView(this).apply {
                    text = "$title: $selected$suffix"
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 14f
                    setPadding(dp(12), dp(6), dp(12), 0)
                }
                val explanation = TextView(this).apply {
                    text = description
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 12f
                    setPadding(dp(12), 0, dp(12), 0)
                }
                val bar = SeekBar(this).apply {
                    max = maximum
                    progress = initial.coerceIn(0, maximum)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                            selected = progress
                            label.text = "$title: $selected$suffix"
                        }
                        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                        override fun onStopTrackingTouch(seekBar: SeekBar) {
                            val proposed = update(selected)
                            if (persistDenoiseSettings(proposed)) settings = proposed
                        }
                    })
                }
                content.addView(label); content.addView(explanation); content.addView(bar)
                subordinate += label; subordinate += explanation; subordinate += bar
            }

            content.addView(TextView(this).apply {
                text = "Detail-preserving RAW denoise"
                setTextColor(getColor(R.color.text_primary)); textSize = 17f
            })
            content.addView(TextView(this).apply {
                text = "Noise-model-aware processing removes colored speckles, protects supported detail, and adds clean monochrome grain after tone mapping. Settings are frozen with each shutter press."
                setTextColor(getColor(R.color.text_secondary)); textSize = 12f
                setPadding(0, dp(4), 0, dp(8))
            })
            val master = switch(
                "Enable denoising",
                "Master bypass. Off runs the original AMaZE and JPEG pipeline without any denoise dispatches.",
                { settings.enabled }, { settings.copy(enabled = it) }
            )

            heading("RAW impulse correction", "A robust same-color CFA median/MAD detector removes isolated hot or pepper samples before AMaZE. Normal samples receive only slight stabilization at high modeled noise.")
            subordinate += switch(
                "Pre-demosaic stabilization",
                "Corrects isolated sensor impulses before they can spread through demosaicing. It does not perform ordinary spatial smoothing at low noise.",
                { settings.rawPrefilterEnabled }, { settings.copy(rawPrefilterEnabled = it) }
            )
            slider(
                "High-noise stabilization", "Impulse correction remains active. This controls only the capped stabilization of ordinary samples when the camera noise model reports severe noise.",
                100, (settings.rawPrefilterStrength * 100).toInt()
            ) { settings.copy(rawPrefilterStrength = it / 100f) }

            heading("Color noise", "Local luma-guided chroma regression removes color that is unsupported by neighboring pixels. Isoluminant consensus and luma edges preserve real colored detail.")
            subordinate += switch(
                "Chroma denoise", "Independent bypass for color cleanup while leaving luma controls active.",
                { settings.chromaEnabled }, { settings.copy(chromaEnabled = it) }
            )
            slider(
                "Color cleanup", "100% is the calibrated chroma-first default. Above 100% targets severe shadow color noise; edge guards still retain supported color boundaries.",
                200, (settings.chromaStrength * 100).toInt()
            ) { settings.copy(chromaStrength = it / 100f) }

            heading("Luma detail", "Two-pass overlapping 4x4 Walsh-Hadamard shrinkage uses a clean pilot to distinguish repeatable structure from noise. There is no Gaussian blur and no sensor residual is copied back as grain.")
            subordinate += switch(
                "Luma cleanup", "Independent bypass for monochromatic cleanup. Turn off to preserve the original AMaZE luma exactly apart from the optional RAW stabilizer.",
                { settings.lumaEnabled }, { settings.copy(lumaEnabled = it) }
            )
            slider(
                "Luma cleanup", "Controls noise-normalized coefficient shrinkage. The 55% default cleans blotches while the second-pass pilot preserves repeatable fine structure.",
                100, (settings.lumaCleanup * 100).toInt()
            ) { settings.copy(lumaCleanup = it / 100f) }
            slider(
                "Microdetail protection", "Lowers shrinkage for supported high-frequency structure. This no longer retains isolated sensor speckles, so increasing it does not deliberately create pepper noise.",
                100, (settings.grainRetention * 100).toInt()
            ) { settings.copy(grainRetention = it / 100f) }
            slider(
                "Edge protection", "Raises retention around both luminance and isoluminant color edges. Higher values preserve detail more aggressively but may retain noise along boundaries.",
                100, (settings.edgeProtection * 100).toInt()
            ) { settings.copy(edgeProtection = it / 100f) }

            heading("Film grain", "Grain is synthesized after AgX in display space, is exactly monochrome, and is strongest in midtones. It cannot introduce red/green/blue speckles or alter hue.")
            subordinate += switch(
                "Monochrome film grain", "Independent grain bypass. Denoising remains active when this is off.",
                { settings.filmGrainEnabled }, { settings.copy(filmGrainEnabled = it) }
            )
            slider("Grain amount", "Amplitude of neutral display grain. 22% is subtle; it does not restore removed sensor noise.", 100, (settings.filmGrainAmount * 100).toInt()) { settings.copy(filmGrainAmount = it / 100f) }
            slider("Grain clumping", "0% is fine pixel grain; higher values blend in small two-pixel clumps resembling film dye-cloud density variation.", 100, (settings.filmGrainSize * 100).toInt()) { settings.copy(filmGrainSize = it / 100f) }

            subordinate.forEach { it.isEnabled = settings.enabled }
            master.isEnabled = true
            generalTab.isEnabled = true
            denoiseTab.isEnabled = false
            lensesTab.isEnabled = true
            aboutTab.isEnabled = true
        }
        fun showLensesTab() {
            rawZslSettingsStatus = null
            content.removeAllViews()
            val count = selectedLensIds().size
            content.addView(TextView(this).apply {
                text = "$count RAW lens${if (count == 1) "" else "es"} enabled"
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
            })
            content.addView(Button(this).apply {
                text = "Discover and manage lenses"
                setOnClickListener {
                    dialog.dismiss()
                    showLensDiscovery(firstRun = false)
                }
            })
            generalTab.isEnabled = true
            denoiseTab.isEnabled = true
            lensesTab.isEnabled = false
            aboutTab.isEnabled = true
        }
        fun showAboutTab() {
            rawZslSettingsStatus = null
            content.removeAllViews()
            content.addView(TextView(this).apply {
                @Suppress("DEPRECATION")
                val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
                text = "RawLens $versionName\n\n" +
                    "Copyright © 2026 RawLens contributors\n\n" +
                    "RawLens is free software licensed under GNU GPL version 3 or later. " +
                    "You may copy, modify, and redistribute it under that license. " +
                    "It comes with absolutely no warranty.\n\n" +
                    "Exposure-policy design acknowledges PhotonCamera and contributor matthew777777. " +
                    "Source code and notices accompany official releases."
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
            })
            content.addView(Button(this).apply {
                text = "View GPLv3 license"
                setOnClickListener {
                    val licenseText = assets.open("LICENSE").bufferedReader().use { it.readText() }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("GNU General Public License v3")
                        .setMessage(licenseText)
                        .setPositiveButton("Close", null)
                        .show()
                }
            })
            generalTab.isEnabled = true
            denoiseTab.isEnabled = true
            lensesTab.isEnabled = true
            aboutTab.isEnabled = false
        }
        generalTab.setOnClickListener { showGeneralTab() }
        denoiseTab.setOnClickListener { showDenoiseTab() }
        lensesTab.setOnClickListener { showLensesTab() }
        aboutTab.setOnClickListener { showAboutTab() }
        showGeneralTab()
        dialog = AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(container)
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnDismissListener { rawZslSettingsStatus = null }
        dialog.show()
    }

    private fun rawZslSettingsText(status: RawZslStatus): String {
        val state = when (status.state) {
            RawZslState.OFF -> "OFF"
            RawZslState.WARMING_UP -> "WARMING UP"
            RawZslState.ACTIVE -> "ACTIVE"
            RawZslState.FALLBACK -> "UNAVAILABLE — NORMAL RAW FALLBACK"
        }
        return "Status: $state\n${status.detail}\n" +
            "Keeps the selected number of full-resolution RAW frames in camera memory. " +
            "Unsupported devices automatically use normal RAW capture."
    }

    private fun showDngMetadataOverrideEditor() {
        val cameraId = controller.activeCameraId()
        if (cameraId == null) {
            status.text = "WAIT FOR CAMERA BEFORE EDITING DNG METADATA"
            return
        }
        val current = dngMetadataOverrideStore.get(cameraId)
        val defaults = controller.dngMetadataDefaults()
        val configured = listOf(
            current.blackLevels != null || current.whiteLevel != null,
            current.noiseProfile != null,
            current.colorMatrix1 != null || current.colorMatrix2 != null,
            current.cameraCalibration1 != null || current.cameraCalibration2 != null,
            current.forwardMatrix1 != null || current.forwardMatrix2 != null
        ).count { it }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(TextView(this@MainActivity).apply {
                text = "Camera $cameraId • $configured override group${if (configured == 1) "" else "s"} active\n" +
                    "Device default preserves the DNG tags reported by this sensor. Custom replaces only the selected tag."
                setTextColor(getColor(R.color.text_secondary)); textSize = 12f
                setPadding(0, 0, 0, dp(8))
            })
        }
        fun row(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }.also(content::addView)
        row("Sensor levels\nBlack RGGB and white level") { showDngLevelsEditor(cameraId, defaults) }
        row("Noise model\nR, G and B scale / offset pairs") { showDngNoiseEditor(cameraId, defaults) }
        row("Color matrices\nColorMatrix 1 and 2") {
            showDngMatrixEditor(cameraId, "Color matrices", "ColorMatrix", defaults.colorMatrix1, defaults.colorMatrix2,
                { it.colorMatrix1 }, { it.colorMatrix2 }, { profile, first, second -> profile.copy(colorMatrix1 = first, colorMatrix2 = second) })
        }
        row("Camera calibration matrices") {
            showDngMatrixEditor(cameraId, "Camera calibration", "CameraCalibration", defaults.cameraCalibration1, defaults.cameraCalibration2,
                { it.cameraCalibration1 }, { it.cameraCalibration2 }, { profile, first, second -> profile.copy(cameraCalibration1 = first, cameraCalibration2 = second) })
        }
        row("Forward matrices") {
            showDngMatrixEditor(cameraId, "Forward matrices", "ForwardMatrix", defaults.forwardMatrix1, defaults.forwardMatrix2,
                { it.forwardMatrix1 }, { it.forwardMatrix2 }, { profile, first, second -> profile.copy(forwardMatrix1 = first, forwardMatrix2 = second) })
        }
        AlertDialog.Builder(this).setTitle("RAW DNG calibration")
            .setView(content).setNeutralButton("Reset all", null).setNegativeButton("Close", null)
            .show().also { dialog ->
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    dngMetadataOverrideStore.clear(cameraId)
                    dialog.dismiss()
                    status.text = "DNG CALIBRATION RESET FOR CAMERA $cameraId"
                }
            }
    }

    private fun showDngLevelsEditor(cameraId: String, defaults: DngMetadataDefaults) {
        val current = dngMetadataOverrideStore.get(cameraId)
        val black = current.blackLevels ?: defaults.blackLevels
        val white = current.whiteLevel ?: defaults.whiteLevel
        val available = black != null && white != null
        val inputs = listOf("Black R", "Black G1", "Black G2", "Black B").mapIndexed { index, label ->
            metadataField(label, black?.get(index) ?: 0.0)
        } + metadataField("White level", white ?: 0.0)
        showDngGroupDialog(
            "Sensor levels", cameraId, "Uses the camera's declared black RGGB pattern and white level. " +
                "Custom levels affect how RAW editors decode the file.", available,
            current.blackLevels != null || current.whiteLevel != null, inputs
        ) { custom ->
            if (custom) {
                val values = metadataValues(inputs) ?: return@showDngGroupDialog false
                val updated = current.copy(blackLevels = values.take(4), whiteLevel = values[4])
                saveDngMetadata(cameraId, updated)
            } else saveDngMetadata(cameraId, current.copy(blackLevels = null, whiteLevel = null))
        }
    }

    private fun showDngNoiseEditor(cameraId: String, defaults: DngMetadataDefaults) {
        val current = dngMetadataOverrideStore.get(cameraId)
        val values = current.noiseProfile ?: defaults.noiseProfile
        val available = values != null && values.size in listOf(6, 8)
        val labels = if (values?.size == 8) listOf("R scale", "R offset", "G1 scale", "G1 offset", "G2 scale", "G2 offset", "B scale", "B offset")
            else listOf("R scale", "R offset", "G scale", "G offset", "B scale", "B offset")
        val inputs = labels.mapIndexed { index, label -> metadataField(label, values?.get(index) ?: 0.0) }
        showDngGroupDialog(
            "Noise profile", cameraId, "Every channel has a scale and offset. Keep Device default unless you have a measured sensor noise profile.",
            available, current.noiseProfile != null, inputs
        ) { custom ->
            if (custom) saveDngMetadata(cameraId, current.copy(noiseProfile = metadataValues(inputs) ?: return@showDngGroupDialog false))
            else saveDngMetadata(cameraId, current.copy(noiseProfile = null))
        }
    }

    private fun showDngMatrixEditor(
        cameraId: String, title: String, tagName: String, defaultFirst: List<Double>?, defaultSecond: List<Double>?,
        firstOverride: (DngMetadataOverrides) -> List<Double>?, secondOverride: (DngMetadataOverrides) -> List<Double>?,
        update: (DngMetadataOverrides, List<Double>?, List<Double>?) -> DngMetadataOverrides
    ) {
        val current = dngMetadataOverrideStore.get(cameraId)
        val first = firstOverride(current) ?: defaultFirst
        val second = secondOverride(current) ?: defaultSecond
        val available = first?.size == 9 && second?.size == 9
        val inputs = (first ?: List(9) { 0.0 }).mapIndexed { index, value -> metadataField("$tagName 1 • ${index / 3 + 1},${index % 3 + 1}", value) } +
            (second ?: List(9) { 0.0 }).mapIndexed { index, value -> metadataField("$tagName 2 • ${index / 3 + 1},${index % 3 + 1}", value) }
        showDngGroupDialog(
            title, cameraId, "Two 3×3 matrices. Values are shown row by row, directly matching DNG $tagName tags.", available,
            firstOverride(current) != null || secondOverride(current) != null, inputs
        ) { custom ->
            if (custom) {
                val values = metadataValues(inputs) ?: return@showDngGroupDialog false
                saveDngMetadata(cameraId, update(current, values.take(9), values.drop(9)))
            } else saveDngMetadata(cameraId, update(current, null, null))
        }
    }

    private fun showDngGroupDialog(
        title: String, cameraId: String, description: String, available: Boolean, customInitially: Boolean,
        inputs: List<EditText>, save: (Boolean) -> Boolean
    ) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(TextView(this@MainActivity).apply {
                text = if (available) description else "This camera does not currently declare this DNG tag, so it cannot be overridden safely."
                setTextColor(getColor(R.color.text_secondary)); textSize = 12f
            })
        }
        val toggle = CheckBox(this).apply {
            text = "Use custom $title"
            isChecked = customInitially
            isEnabled = available
            setTextColor(getColor(R.color.text_primary))
        }
        container.addView(toggle)
        inputs.forEach { input -> container.addView(input) }
        fun updateEnabled() = inputs.forEach { it.isEnabled = toggle.isChecked && available }
        toggle.setOnCheckedChangeListener { _, _ -> updateEnabled() }
        updateEnabled()
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(container)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (save(toggle.isChecked)) dialog.dismiss()
        }
    }

    private fun metadataField(label: String, value: Double): EditText = EditText(this).apply {
        hint = label
        setText(formatMetadataNumber(value))
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        setSelectAllOnFocus(true)
    }

    private fun metadataValues(inputs: List<EditText>): List<Double>? {
        val values = inputs.map { input -> input.text.toString().trim().toDoubleOrNull()?.takeIf(Double::isFinite) }
        if (values.any { it == null }) {
            inputs.zip(values).firstOrNull { it.second == null }?.first?.error = "Enter a finite number"
            return null
        }
        return values.filterNotNull()
    }

    private fun formatMetadataNumber(value: Double): String = String.format(Locale.US, "%.8f", value)
        .trimEnd('0').trimEnd('.').ifEmpty { "0" }

    private fun saveDngMetadata(cameraId: String, overrides: DngMetadataOverrides): Boolean {
        try {
            dngMetadataOverrideStore.save(cameraId, overrides)
            status.text = if (overrides.isEmpty()) "DNG CALIBRATION USING DEVICE DEFAULTS" else "DNG CALIBRATION OVERRIDE SAVED"
            return true
        } catch (failure: Exception) {
            status.text = "DNG CALIBRATION NOT SAVED: ${failure.message ?: "invalid values"}"
            return false
        }
    }

    private fun rawZslFrameCountText(frameCount: Int): String =
        "ZSL frames saved: $frameCount\n" +
            "Approx. ${frameCount * 25} MB at 4080×3060; higher values need more camera memory."

    private fun dynamicExposureBalanceText(balance: Float): String = when {
        balance > 1.01f -> "Exposure balance: ${String.format(Locale.US, "%.2f", balance)}× • faster shutter"
        balance < 0.99f -> "Exposure balance: ${String.format(Locale.US, "%.2f", balance)}× • lower ISO"
        else -> "Exposure balance: 1.00× • neutral"
    }

    private fun showLensDiscovery(firstRun: Boolean) {
        val progress = AlertDialog.Builder(this)
            .setTitle("Lens discovery")
            .setMessage("Checking Camera2 IDs and RAW capabilities…")
            .setCancelable(!firstRun)
            .show()
        lensDiscovery.discover { lenses ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.dismiss()
                if (lenses.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("No RAW lenses found")
                        .setMessage("The camera service did not expose a RAW-capable camera ID.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@runOnUiThread
                }
                val missingSaved = selectedLensIds()
                    .filterNot { savedId -> lenses.any { it.id == savedId } }
                    .map { DiscoveredLens(it, "Camera $it • currently unavailable") }
                showLensSelection(lenses + missingSaved, firstRun)
            }
        }
    }

    private fun showLensSelection(lenses: List<DiscoveredLens>, firstRun: Boolean) {
        val selected = selectedLensIds()
        val checked = BooleanArray(lenses.size) { index ->
            if (firstRun && selected.isEmpty()) true else lenses[index].id in selected
        }
        AlertDialog.Builder(this)
            .setTitle(if (firstRun) "Add RAW lenses" else "Lens discovery")
            .setMultiChoiceItems(lenses.map { it.label }.toTypedArray(), checked) { _, which, enabled ->
                checked[which] = enabled
            }
            .setPositiveButton("Save") { _, _ ->
                val ids = lenses.indices.filter { checked[it] }.mapTo(mutableSetOf()) { lenses[it].id }
                if (ids.isEmpty()) {
                    status.text = "SELECT AT LEAST ONE RAW LENS"
                    return@setPositiveButton
                }
                lensPreferences().edit()
                    .putStringSet(KEY_SELECTED_LENSES, ids)
                    .putBoolean(KEY_LENS_SETUP_COMPLETE, true)
                    .apply()
                controller.reloadLenses()
                status.text = "${ids.size} RAW LENS${if (ids.size == 1) "" else "ES"} ADDED"
            }
            .setNegativeButton(if (firstRun) "Use default" else "Cancel") { _, _ ->
                if (firstRun) lensPreferences().edit().putBoolean(KEY_LENS_SETUP_COMPLETE, true).apply()
            }
            .show()
    }

    private fun lensPreferences() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun selectedLensIds(): Set<String> =
        lensPreferences().getStringSet(KEY_SELECTED_LENSES, emptySet())?.toSet().orEmpty()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun wrapContent(): Int = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun formatShutter(nanos: Long): String {
        if (nanos <= 0L) return "--"
        val seconds = nanos / 1_000_000_000.0
        return if (seconds >= 1.0) {
            String.format(Locale.US, "%.1fs", seconds)
        } else {
            val reciprocal = 1.0 / seconds
            String.format(Locale.US, "1/%d", reciprocal.toInt())
        }
    }

    private companion object {
        const val LOG_TAG = "RawLensCamera"
        const val CAMERA_PERMISSION = 42
        const val PREFS_NAME = "rawlens_settings"
        const val KEY_SELECTED_LENSES = "selected_lens_ids"
        const val KEY_LENS_SETUP_COMPLETE = "lens_setup_complete"
        const val KEY_LAST_CAMERA_ID = "last_camera_id"
        const val KEY_DEBUG_OVERLAY = "camera_debug_overlay"
        const val KEY_GRID = "viewfinder_grid"
        const val KEY_LEVEL = "viewfinder_level"
        const val KEY_HISTOGRAM = "viewfinder_histogram"
        const val KEY_OIS = "optical_image_stabilization"
        const val KEY_RAW_ZSL = "raw_zero_shutter_lag"
        const val KEY_RAW_ZSL_FRAME_COUNT = "raw_zsl_frame_count"
        const val KEY_RAW_SR_ENABLED = "raw_super_resolution_enabled"
        const val KEY_RAW_SR_DNG_MODE = "raw_super_resolution_dng_mode"
        const val KEY_DYNAMIC_EXPOSURE = "dynamic_exposure"
        const val KEY_DYNAMIC_EXPOSURE_BALANCE = "dynamic_exposure_balance"
        const val KEY_DYNAMIC_EXPOSURE_ISO_LIMIT = "dynamic_exposure_iso_limit"
        const val KEY_DYNAMIC_EXPOSURE_SHUTTER_LIMIT = "dynamic_exposure_shutter_limit"
        const val KEY_DYNAMIC_EXPOSURE_AUTO_SHUTTER = "dynamic_exposure_auto_shutter"
        const val KEY_CAPTURE_EXPOSURE_MODE = "capture_exposure_mode"
        const val KEY_CAPTURE_FORMAT = "capture_format"
        const val KEY_BURST_RELEASE = "burst_release"
        const val KEY_JPEG_ULTRA_HDR = "jpeg_ultra_hdr"
        const val KEY_JPEG_DISPLAY_P3 = "jpeg_display_p3"
        const val KEY_JPEG_AGX_PURITY = "jpeg_agx_purity"
        const val KEY_JPEG_AGX_LOOK = "jpeg_agx_look"
        const val KEY_JPEG_AGX_CONTRAST = "jpeg_agx_contrast"
        const val KEY_JPEG_AGX_SATURATION = "jpeg_agx_saturation"
        const val KEY_JPEG_AGX_HUE = "jpeg_agx_hue"
        const val KEY_JPEG_AGX_SHADOW_EV = "jpeg_agx_shadow_ev"
        const val KEY_JPEG_AGX_HIGHLIGHT_EV = "jpeg_agx_highlight_ev"
        const val KEY_JPEG_AGX_GAMUT = "jpeg_agx_gamut"
        const val KEY_JPEG_ADAPTIVE_EXPOSURE = "jpeg_adaptive_exposure"
        const val KEY_JPEG_ADAPTIVE_PROGRAM = "jpeg_adaptive_program"
        const val LEGACY_KEY_JPEG_ADAPTIVE_PHOTO = "jpeg_adaptive_photo"
        const val KEY_DENOISE_ENABLED = "denoise_enabled"
        const val KEY_DENOISE_RAW_ENABLED = "denoise_raw_enabled"
        const val KEY_DENOISE_RAW_STRENGTH = "denoise_raw_strength"
        const val KEY_DENOISE_CHROMA_ENABLED = "denoise_chroma_enabled"
        const val KEY_DENOISE_CHROMA_STRENGTH = "denoise_chroma_strength"
        const val KEY_DENOISE_LUMA_ENABLED = "denoise_luma_enabled"
        const val KEY_DENOISE_LUMA_CLEANUP = "denoise_luma_cleanup"
        const val KEY_DENOISE_GRAIN = "denoise_grain_retention"
        const val KEY_DENOISE_EDGE = "denoise_edge_protection"
        const val KEY_DENOISE_FILM_GRAIN_ENABLED = "denoise_film_grain_enabled"
        const val KEY_DENOISE_FILM_GRAIN_AMOUNT = "denoise_film_grain_amount"
        const val KEY_DENOISE_FILM_GRAIN_SIZE = "denoise_film_grain_size"
        const val KEY_AE_METERING_MODE = "ae_metering_mode"
        const val SLIDER_STEPS = 10_000
        const val SLIDER_UPDATE_DELAY_MS = 32L
        const val MANUAL_PANEL_TIMEOUT_MS = 4_000L
        const val HISTOGRAM_INTERVAL_MS = 700L
        const val MIN_RAW_ZSL_FRAMES = 1
        const val MAX_RAW_ZSL_FRAMES = 30
        const val DEFAULT_RAW_ZSL_FRAME_COUNT = 2
    }
}
