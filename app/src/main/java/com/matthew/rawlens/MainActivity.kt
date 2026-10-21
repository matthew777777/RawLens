// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.PowerManager
import android.net.Uri
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.particlesdevs.photoncamera.processing.ml.FlowNetNcnnProcessor
import com.particlesdevs.photoncamera.processing.ml.RawNindNcnnProcessor
import java.io.File
import java.util.Locale

import android.os.Build
import android.os.SystemClock

class MainActivity : Activity() {
    internal lateinit var controller: RawCameraController
    private lateinit var status: TextView

    internal lateinit var isoControl: TextView
    internal lateinit var shutterControl: TextView
    internal lateinit var wbControl: TextView
    internal lateinit var focusControl: TextView
    internal lateinit var evControl: TextView
    internal lateinit var lensSwitcher: LinearLayout
    private lateinit var lastCapture: LastCaptureThumbnail
    private lateinit var flashControl: TextView
    internal lateinit var lensDiscovery: LensDiscovery
    internal lateinit var lensVerdictStore: LensVerdictStore
    internal lateinit var rawSizePreferenceStore: RawSizePreferenceStore
    internal lateinit var rawFormatPreferenceStore: RawFormatPreferenceStore
    internal lateinit var dngMetadataOverrideStore: DngMetadataOverrideStore
    internal lateinit var programAeProfileStore: ProgramAeProfileStore
    internal lateinit var vendorKeyStore: VendorKeyStore
    internal var gpsProvider: GpsLocationProvider? = null
    internal lateinit var manualPanel: LinearLayout
    internal lateinit var manualSlider: RuleSliderView
    internal lateinit var manualAuto: Button
    internal lateinit var manualLimits: Button
    internal var activeManualControl: ManualControl? = null
    /** PROGRAM axis lock slider reuses the bottom ruler; null when the ruler drives MANUAL. */
    internal var activeProgramAxis: ManualControl? = null
    internal var pendingSliderUpdate: Runnable? = null
    internal var manualPanelHide: Runnable? = null
    internal lateinit var debugOverlay: TextView
    internal lateinit var rawVfDebugOverlay: TextView
    private lateinit var guideOverlay: CameraGuideOverlay
    internal lateinit var histogramView: HistogramView
    internal lateinit var waveformView: WaveformView
    private lateinit var meteringOverlay: FocusMeteringOverlay
    internal lateinit var focusPeakingOverlay: FocusPeakingOverlayView
    internal lateinit var quickPanel: LinearLayout
    internal lateinit var gridQuick: TextView
    internal lateinit var histogramQuick: TextView
    internal lateinit var levelQuick: TextView
    internal lateinit var aeMeteringQuick: TextView
    internal lateinit var hdrQuick: TextView
    internal lateinit var timerQuick: TextView
    internal lateinit var releaseQuick: TextView
    // Future SR hook: rawSrQuick tile field here.
    internal lateinit var hdrPlusQuick: TextView
    internal lateinit var ettrQuick: TextView
    internal var dualRawEnabled = false // Experimental, deliberately session-only.
    internal var lastLensSwitcherSignature: String? = null
    internal lateinit var featureTags: TextView
    private lateinit var lightQuick: TextView
    internal lateinit var modeButton: TextView
    internal lateinit var rawBadge: TextView
    private lateinit var rawStatusGroup: View
    private var captureFormat = CaptureFormat.DNG_ONLY
    internal var developedFormat = DevelopedFormat.JPEG
    private var vfPreviewMode = VfPreviewMode.FOLLOW
    internal var vfResolution = VfResolution.MAX
    private var vfPreviewButton: TextView? = null
    private var vfEngineButton: TextView? = null
    internal var rawZslStatus = RawZslStatus(RawZslState.OFF, "Disabled in settings")
    internal var rawZslSettingsStatus: TextView? = null
    internal var gridEnabled = true
    internal var histogramEnabled = true
    internal var levelEnabled = false
    internal lateinit var levelOverlay: LevelOverlayView
    internal var levelTracker: LevelTracker? = null
    /** Focus-peaking master switch (default on); the overlay auto-shows on manual/tap focus. */
    internal var focusPeakingEnabled = true
    internal var focusPeakingColor = FocusPeakingColor.GREEN
    /** Histogram vs waveform parade, persisted. Tapping the scope toggles it. */
    internal var scopeMode = ScopeMode.HISTOGRAM
    // True once a RAW_SENSOR scope frame arrived. Freshness is reported separately.
    internal var rawScopeLive = false
    internal var lastRawScopeMs = Long.MIN_VALUE
    /** Scope data source is automatic: AgX-curved JPEG preview with a JPEG format,
     * linear RAW with DNG only. */
    internal fun scopeAgxApplied(): Boolean = captureFormat.includesJpeg
    internal var aeMeteringMode = AeMeteringMode.AUTO
    internal var timerSeconds = 0
    internal var releaseMode = 0 // 0 single, 1 burst
    internal var hdrEnabled = false
    internal var captureExposureMode = CaptureExposureMode.AUTO
    internal var hdrPlusSettings = HdrPlusSettings()
    internal var mfsrSettings = MfsrSettings()
    internal lateinit var mfsrQuick: TextView
    private var modeBeforeMfsr: CaptureExposureMode? = null
    /** Offline-merge switch (Settings → Burst): when on, the shutter dumps the ZSL ring to _BURST.crs. */
    internal var burstCrsEnabled = false
    private var modeBeforeBurst: CaptureExposureMode? = null
    private val burstDumpInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    internal var programHintShown = false
    internal var sidecarSettingsStatus: TextView? = null
    private var countdownRunnable: Runnable? = null
    internal var scopeRunnable: Runnable? = null
    internal val quickTileStates = java.util.IdentityHashMap<TextView, Boolean>()
    private lateinit var orientationListener: OrientationEventListener
    internal var activityResumed = false
    /** Camera stream sizing must use the final viewport, not the full-screen XML placeholder. */
    private var cameraViewportReady = false
    /**
     * Last presented frame geometry (sensor pixels + sensor orientation) from
     * the active viewfinder engine. The renderers aspect-fit the frame into
     * their taller-than-3:4 surface; overlays align to that fitted picture
     * (not the surface) and layout changes refit from this synchronously.
     * Zero dimensions = no frame yet, overlays span the full box.
     */
    private var vfFrameW = 0
    private var vfFrameH = 0
    private var vfFrameRot = 0
    private var deviceOrientationDegrees = 0
    internal var controlRotationDegrees = 0f
    // RAW Video mode (orthogonal to the stills CaptureExposureMode enums so no
    // stills state machine is touched): the stills controller keeps framing
    // until REC, when the standalone RawVideoRecorder takes the camera.
    internal var isVideoMode = false
    private var lastPhotoExposureMode = CaptureExposureMode.AUTO
    /** Photo mode before HDR+ pulled the camera into ZSL; restored on toggle-off. */
    private var modeBeforeHdrPlus: CaptureExposureMode? = null
    internal var videoRecorder: RawVideoRecorder? = null
    internal var videoRecording = false
    private var videoOutputFile: File? = null
    /** Direct-Log take state. Only one of [videoRecorder]/[logRecorder] is ever active. */
    internal var logRecorder: DirectLogRecorder? = null
    /**
     * Last closed Direct-Log take's stats (null while rolling): lets
     * device tests assert take health (drops, over-budget frames,
     * shading) without parsing logcat. Written on the UI thread.
     */
    var lastDirectLogStats: DirectLogRecorder.Stats? = null
        private set
    internal var directLogEnabled = false
    /** True when the shared folder picker was opened to rescue staged video. */
    private var folderPickerForVideo = false
    /** Settings JSON awaiting the export picker result; cleared once written or cancelled. */
    internal val settingsTransfer =
        SettingsTransfer(this, SETTINGS_EXPORT_REQUEST, SETTINGS_IMPORT_REQUEST) { setStatus(it) }
    /** Update consent, release checks, download and install ([UpdateFlow]). */
    internal val updateFlow = UpdateFlow(this, UPDATE_INSTALL_SOURCES) { setStatus(it) }
    internal var videoCrop = VideoCrop.OPEN_GATE
    private var videoStartPending = false
    private var videoDebugRunnable: Runnable? = null
    private lateinit var videoDebugOverlay: TextView
    /** Peak-held mic level feeding the stats-box audio line (HUD tick decays it). */
    private var videoAudioPeak = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreLaunchState()
        
        // Full-screen immersive mode (re-applied on resume/focus: permission
        // dialogs and pickers clear it, which used to leave the status bar
        // over the camera until the app was restarted).
        ImmersiveMode.apply(window)

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
        bindViewsAndOverlays()
        createController(dngInfo, sensorInfo, viewfinder, shutter)
        applyControllerSettings(shutter)
        wireModeAndQuickControls(shutter)
        wireTouchAndManualControls(viewfinder)
        finishStartup()
    }

    /** onCreate stage: Logcat, crash/Vulkan notices, wake lock, and the saved capture/merge mode migration. */
    private fun restoreLaunchState() {
        // App logcat streams to a session file from the first line of every
        // launch (no permission needed); the crash handler below flushes the
        // fatal trace before the process dies, so the file survives crashes.
        if (lensPreferences().getBoolean(KEY_LOGCAT_FILE, true)) LogcatFileWriter.start(this)
        MemoryLeakDiagnostics.startPeriodic()
        MemoryLeakDiagnostics.sampleAsync("activity-created")
        DeviceImpactProbe.install(this)
        DeviceImpactProbe.startHeartbeat()
        offerCrashedLogIfAny()
        // Older devices that fail the Vulkan gate keep working (CPU
        // viewfinder, DNG stills) but deserve an explanation, not a
        // silent fallback: one notice per release, then out of the way.
        maybeShowVulkanGateNotice()
        // Viewfinder must never let the phone auto-lock mid-shoot.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        val storedReleaseMode = lensPreferences().getInt(KEY_RELEASE_MODE,
            if (lensPreferences().getBoolean(KEY_BURST_RELEASE, false)) 1 else 0)
        hdrEnabled = lensPreferences().getBoolean(KEY_HDR_ENABLED, storedReleaseMode == 2)
        releaseMode = when (storedReleaseMode) {
            1 -> 1
            else -> 0
        }
        if (storedReleaseMode == 2 || storedReleaseMode !in 0..1) {
            // Legacy HDR-bracket release mode is now the separate HDR tile.
            lensPreferences().edit()
                .putInt(KEY_RELEASE_MODE, releaseMode)
                .putBoolean(KEY_HDR_ENABLED, hdrEnabled)
                .apply()
        }
        preloadFlowNetForMergedHdr()
        preloadRawNindIfEnabled()
        // Future SR hook: KernelNet preload + RawSuperResolutionSettings load here.
        hdrPlusSettings = HdrPlusSettings.fromPreferences(lensPreferences().all)
        captureExposureMode = CaptureExposureMode.entries.getOrElse(
            lensPreferences().getInt(KEY_CAPTURE_EXPOSURE_MODE, CaptureExposureMode.AUTO.ordinal)
        ) { CaptureExposureMode.AUTO }
        // Older builds exposed ZSL as a separate setting.  Treat that saved switch as the
        // capture intent during migration instead of allowing the saved mode to immediately
        // turn it back off when the controller starts.
        if (lensPreferences().getBoolean(KEY_RAW_ZSL, false)) {
            captureExposureMode = CaptureExposureMode.ZSL
        }
        // Removed burst fusion: a stale enabled fusion switch keeps ZSL
        // (fusion's capture mode) and its keys are cleared below.
        if (lensPreferences().getBoolean(KEY_BURST_FUSION_ENABLED, false)) {
            captureExposureMode = CaptureExposureMode.ZSL
        }
        if (hdrPlusSettings.enabled) {
            captureExposureMode = CaptureExposureMode.ZSL
        }
        // HDR bracket and HDR+ both merge one shutter press into one DNG, so
        // a stale both-on pair cannot survive: it resolves to HDR+.
        hdrEnabled = HdrShutterExclusivity.resolveStale(
            hdrEnabled, hdrPlusSettings.enabled).first
        mfsrSettings = MfsrSettings.fromPreferences(lensPreferences().all)
        // The sharp merger owns the shutter like HDR+ does: a stale
        // sharp-on pair turns HDR+ and the bracket off and forces ZSL.
        if (mfsrSettings.enabled) {
            if (hdrPlusSettings.enabled) {
                hdrPlusSettings = hdrPlusSettings.copy(enabled = false)
                lensPreferences().edit().putBoolean("hdr_plus_enabled", false).apply()
            }
            hdrEnabled = false
            captureExposureMode = CaptureExposureMode.ZSL
        }
        // Offline-merge burst switch survives restarts; like the mergers it
        // needs the ZSL ring, so a stale on-switch pulls the mode into ZSL.
        burstCrsEnabled = lensPreferences().getBoolean(KEY_BURST_CRS_ENABLED, false)
        if (burstCrsEnabled && captureExposureMode != CaptureExposureMode.ZSL) {
            modeBeforeBurst = captureExposureMode
            captureExposureMode = CaptureExposureMode.ZSL
        }
        // Capture mode is now the single source of truth for ZSL.  Keeping the legacy flag in
        // sync also makes a process restart reproduce exactly what the mode button shows.
        lensPreferences().edit()
            .putInt(KEY_CAPTURE_EXPOSURE_MODE, captureExposureMode.ordinal)
            .putBoolean(KEY_RAW_ZSL, captureExposureMode == CaptureExposureMode.ZSL)
            .putBoolean(KEY_HDR_ENABLED, hdrEnabled)
            .remove(KEY_BURST_FUSION_ENABLED)
            .remove(KEY_BURST_FUSION_TAU)
            .remove(KEY_BURST_FUSION_KEEP_SOURCE_BURST)
            .apply()
        captureFormat = CaptureFormat.fromPreference(
            lensPreferences().getString(KEY_CAPTURE_FORMAT, null)
        )
        developedFormat = DevelopedFormat.fromPreference(
            lensPreferences().getString(KEY_DEVELOPED_FORMAT, null)
        )
        vfPreviewMode = VfPreviewMode.fromPreference(
            lensPreferences().getString(KEY_VF_PREVIEW_MODE, null)
        )
        vfResolution = VfResolution.validated(
            lensPreferences().getInt(KEY_VF_RESOLUTION, VfResolution.MAX)
        )
    }

    /** onCreate stage: Stores, panels, overlays, scope gestures and the orientation listener. */
    private fun bindViewsAndOverlays() {
        lensDiscovery = LensDiscovery(this)
        lensVerdictStore = LensVerdictStore(this)
        rawSizePreferenceStore = RawSizePreferenceStore(this)
        rawFormatPreferenceStore = RawFormatPreferenceStore(this)
        dngMetadataOverrideStore = DngMetadataOverrideStore(this)
        programAeProfileStore = ProgramAeProfileStore(this)
        vendorKeyStore = VendorKeyStore(this)
        gpsProvider = GpsLocationProvider(this)
        manualPanel = findViewById(R.id.manualControlPanel)
        manualSlider = findViewById(R.id.manualControlSlider)
        manualAuto = findViewById(R.id.manualAutoButton)
        manualLimits = findViewById(R.id.manualLimitsButton)
        debugOverlay = findViewById(R.id.debugOverlay)
        rawVfDebugOverlay = findViewById(R.id.rawVfDebugOverlay)
        // The two debug overlays share one corner and are mutually
        // exclusive (see showDebugTab). Reconcile stale prefs the same
        // way, keeping the RAW VF overlay (the interactive engine switch).
        if (lensPreferences().getBoolean(KEY_DEBUG_OVERLAY, false) &&
            lensPreferences().getBoolean(KEY_RAW_VF_DEBUG_OVERLAY, false)
        ) {
            lensPreferences().edit().putBoolean(KEY_DEBUG_OVERLAY, false).apply()
        }
        debugOverlay.visibility = if (lensPreferences().getBoolean(KEY_DEBUG_OVERLAY, false)) View.VISIBLE else View.GONE
        rawVfDebugOverlay.visibility = if (lensPreferences().getBoolean(KEY_RAW_VF_DEBUG_OVERLAY, false)) View.VISIBLE else View.GONE
        meteringOverlay = findViewById(R.id.focusMeteringOverlay)
        focusPeakingOverlay = findViewById(R.id.focusPeakingOverlay)
        guideOverlay = findViewById(R.id.guideOverlay)
        levelOverlay = findViewById(R.id.levelOverlay)
        // WYSIWYG aspect-fit: the engines letterbox the frame into their
        // surface and report the fitted picture; overlays align to it.
        // Only the visible engine applies (the hidden one sends nothing, but
        // the guard makes a route-switch race harmless).
        val rawVfView = findViewById<RawViewfinder>(R.id.rawViewfinder)
        val bguVfView = findViewById<BguViewfinder>(R.id.bguViewfinder)
        rawVfView?.onContentRectChanged = { geo -> onVfContentGeometry(rawVfView, geo) }
        bguVfView?.onContentRectChanged = { geo -> onVfContentGeometry(bguVfView, geo) }
        histogramView = findViewById(R.id.histogramView)
        waveformView = findViewById(R.id.waveformView)
        quickPanel = findViewById(R.id.quickSettingsPanel)
        gridQuick = findViewById(R.id.gridQuick)
        histogramQuick = findViewById(R.id.histogramQuick)
        levelQuick = findViewById(R.id.levelQuick)
        aeMeteringQuick = findViewById(R.id.aeMeteringQuick)
        hdrQuick = findViewById(R.id.hdrQuick)
        timerQuick = findViewById(R.id.timerQuick)
        releaseQuick = findViewById(R.id.releaseQuick)
        hdrPlusQuick = findViewById(R.id.hdrPlusQuick)
        mfsrQuick = findViewById(R.id.mfsrQuick)
        ettrQuick = findViewById(R.id.ettrQuick)
        featureTags = findViewById(R.id.featureTags)
        lightQuick = findViewById(R.id.lightQuick)
        modeButton = findViewById(R.id.modeButton)
        rawBadge = findViewById(R.id.rawBadge)
        rawStatusGroup = findViewById(R.id.rawStatusGroup)
        refreshCaptureFormatControl()
        gridEnabled = lensPreferences().getBoolean(KEY_GRID, true)
        histogramEnabled = lensPreferences().getBoolean(KEY_HISTOGRAM, true)
        levelEnabled = lensPreferences().getBoolean(KEY_LEVEL, false)
        focusPeakingEnabled = lensPreferences().getBoolean(KEY_FOCUS_PEAKING, true)
        focusPeakingColor = FocusPeakingColor.fromPreference(
            lensPreferences().getString(KEY_FOCUS_PEAKING_COLOR, null)
        )
        focusPeakingOverlay.peakingColor = focusPeakingColor
        focusPeakingOverlay.visibility = View.GONE
        scopeMode = ScopeMode.fromPreference(lensPreferences().getString(KEY_SCOPE_MODE, null))
        histogramView.setAgxApplied(scopeAgxApplied())
        waveformView.setAgxApplied(scopeAgxApplied())
        aeMeteringMode = AeMeteringMode.fromPreference(
            lensPreferences().getInt(KEY_AE_METERING_MODE, AeMeteringMode.AUTO.preferenceValue)
        )
        guideOverlay.gridEnabled = gridEnabled
        levelTracker = LevelTracker(this) { state ->
            runOnUiThread {
                if (levelEnabled && ::levelOverlay.isInitialized) {
                    // Flat (steeply up/down) states still carry pitch, so the
                    // overlay can show the bubble pinned at its track end.
                    levelOverlay.setState(state)
                }
            }
        }
        syncLevelVisibility()
        syncScopeVisibility()
        // Tap the scope to switch between histogram and waveform. The mode is
        // remembered; the data source (linear RAW vs AgX JPEG preview) follows
        // the capture format automatically.
        histogramView.isClickable = true
        histogramView.isFocusable = true
        histogramView.setOnClickListener { toggleScopeMode() }
        // Long-press: linear (default) <-> logarithmic vertical scale.
        histogramView.logScale = lensPreferences().getBoolean(KEY_HISTOGRAM_LOG, false)
        histogramView.setOnLongClickListener { view ->
            histogramView.logScale = !histogramView.logScale
            lensPreferences().edit().putBoolean(KEY_HISTOGRAM_LOG, histogramView.logScale).apply()
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            setStatus(if (histogramView.logScale) "HISTO LOG" else "HISTO LINEAR")
            true
        }
        waveformView.isClickable = true
        waveformView.isFocusable = true
        waveformView.setOnClickListener { toggleScopeMode() }
        refreshScopeContentDescription()

        orientationListener = object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_UI) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val degrees = ((orientation + 45) / 90 * 90) % 360
                if (degrees != deviceOrientationDegrees) applyDeviceOrientation(degrees, animate = true)
            }
        }
    }

    /** onCreate stage: The camera controller and its UI callbacks (status, scopes, focus, route). */
    private fun createController(dngInfo: TextView, sensorInfo: TextView, viewfinder: AutoFitTextureView, shutter: View) {
        controller = RawCameraController(this, viewfinder, 
            { message -> runOnUiThread { setStatus(message) } },
            { iso, shutterSpeed, wb ->
                runOnUiThread {
                    val lock = if (::controller.isInitialized &&
                        captureExposureMode == CaptureExposureMode.PROGRAM
                    ) {
                        runCatching { controller.getProgramAeProfile().lockMode }
                            .getOrDefault(ProgramLockMode.NONE)
                    } else ProgramLockMode.NONE
                    val isoLabel = if (lock == ProgramLockMode.ISO_LOCK) "ISO🔒" else "ISO"
                    val shutterLabel = if (lock == ProgramLockMode.SHUTTER_LOCK) "S🔒" else "S"
                    isoControl.setTextIfChanged(controlText(isoLabel, iso.toString()))
                    shutterControl.setTextIfChanged(controlText(shutterLabel, formatShutter(shutterSpeed)))
                    if (wb > 0) wbControl.setTextIfChanged(controlText("WB", "${wb}K"))
                    updateAutomaticPanelValue(iso, shutterSpeed, wb)
                }
            },
            { dng, sensor ->
                runOnUiThread {
                    // Video chrome is authoritative in VIDEO mode: stills info
                    // publications (which keep arriving until the recorder
                    // takes the camera) must not stomp the CRS labels.
                    if (isVideoMode) {
                        refreshVideoSensorInfo()
                        return@runOnUiThread
                    }
                    // Top status line next to the DNG badge: details only.
                    dngInfo.text = dng.substringAfter('\n').replace("-bit RAW", "-BIT")
                    sensorInfo.text = sensor.substringAfter('\n')
                }
            },
            { enabled ->
                runOnUiThread {
                    // Video transport owns the shutter while rolling: the
                    // stills session is stopped by design, so its disabled
                    // state must never grey out (and deactivate) the record
                    // toggle — a greyed shutter during a take means STOP IS
                    // UNREACHABLE.
                    val effective = enabled || isVideoMode
                    shutter.isEnabled = effective
                    shutter.alpha = if (effective) 1f else 0.45f
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
                    lightQuick.text = flash.replaceFirst(' ', '\n')
                    lightQuick.isEnabled = flashEnabled
                    lightQuick.alpha = if (flashEnabled) 1f else 0.4f
                    setQuickTileState(lightQuick, flash.endsWith("ON"))
                    refreshManualTileAccents()
                    refreshLensSwitcher()
                    updateQuickControls()
                }
            },
            { selectedLensIds() },
            { lensPreferences().getString(KEY_LAST_CAMERA_ID, null) },
            true,
            lensPreferences().getBoolean(KEY_RAW_ZSL, false),
            lensPreferences().getInt(KEY_RAW_ZSL_FRAME_COUNT, DEFAULT_RAW_ZSL_FRAME_COUNT),
            dynamicExposureSettings(),
            ettrSettings(),
            aeMeteringMode,
            histogramEnabled,
            scopeAgxApplied(),
            programProfile(),
            { dngWriterBackend() },
            { dngCompression() },
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
            { locked, indefinite, deadlineMs ->
                runOnUiThread { meteringOverlay.setFocusLock(locked, indefinite, deadlineMs) }
            },
            { histogram ->
                // The RAW scope is live in every capture mode (repeating scope-only
                // stream plus saved-frame snapshots). Mode/source gates drop frames
                // queued before the user's last switch.
                runOnUiThread {
                    if (!histogramEnabled || scopeMode != ScopeMode.HISTOGRAM) return@runOnUiThread
                    rawScopeLive = true
                    lastRawScopeMs = SystemClock.elapsedRealtime()
                    histogramView.update(histogram)
                    refreshScopeContentDescription()
                }
            },
            { currentGpsLocation() },
            { cameraId ->
                runOnUiThread {
                    // Single authoritative hook: whenever the active camera resolves
                    // (startup, lens switch, configuration change), load that lens's
                    // PROGRAM profile synchronously instead of timed re-pushes.
                    if (cameraId != null) {
                        lensPreferences().edit().putString(KEY_LAST_CAMERA_ID, cameraId).apply()
                        controller.setProgramAeProfile(programProfile())
                        refreshLensSwitcher()
                        updateProgramChipStates()
                        updateQuickControls()
                    }
                }
            },
            findViewById<RawViewfinder>(R.id.rawViewfinder),
            { rawVfText ->
                if (lensPreferences().getBoolean(KEY_RAW_VF_DEBUG_OVERLAY, false)) {
                    runOnUiThread { rawVfDebugOverlay.text = rawVfText }
                }
            },
            lensPreferences().getBoolean(KEY_RAW_STREAM_COMPAT_MODE, false),
            {
                // A stillborn repeating RAW stream engaged the compat session
                // (DEFAULT plan, HAL-default frame rate). Persist it so the
                // next cold start skips the stillborn attempt entirely.
                lensPreferences().edit().putBoolean(KEY_RAW_STREAM_COMPAT_MODE, true).apply()
            },
            initialHdrPlusSettings = hdrPlusSettings,
            initialMfsrSettings = mfsrSettings,
            initialScopeMode = scopeMode,
            onRawWaveform = { waveform ->
                runOnUiThread {
                    if (!histogramEnabled || scopeMode != ScopeMode.WAVEFORM) return@runOnUiThread
                    rawScopeLive = true
                    lastRawScopeMs = SystemClock.elapsedRealtime()
                    waveformView.update(waveform)
                    refreshScopeContentDescription()
                }
            },
            initialFocusPeakingEnabled = focusPeakingEnabled,
            onFocusPeaking = { frame ->
                runOnUiThread { focusPeakingOverlay.update(frame) }
            },
            onFocusPeakingActive = { active ->
                runOnUiThread { setFocusPeakingVisible(active) }
            },
            bguViewfinder = findViewById(R.id.bguViewfinder),
            initialFacePriorityEnabled = lensPreferences().getBoolean(KEY_FACE_PRIORITY, false),
            onFaces = { boxes ->
                runOnUiThread {
                    // Boxes arrive as full-box fractions scaled to box pixels;
                    // the overlay spans the box while the picture is the
                    // cover rect in the same space (negative origin on the
                    // cropped axis), so map through it.
                    val bw = viewfinder.width
                    val bh = viewfinder.height
                    val cover = vfContentRectFor(bw, bh)
                    val sx = if (bw > 0) cover.width().toFloat() / bw else 1f
                    val sy = if (bh > 0) cover.height().toFloat() / bh else 1f
                    meteringOverlay.setFaces(
                        boxes.map {
                            RectF(
                                it.left * sx + cover.left, it.top * sy + cover.top,
                                it.right * sx + cover.left, it.bottom * sy + cover.top
                            )
                        },
                        boxes.indexOfFirst { it.tracked }
                    )
                }
            },
            vendorKeys = { cameraId -> vendorKeyStore.get(cameraId) }
        )
        controller.onVfRouteChanged = { isBgu ->
            runOnUiThread {
                applyVfRoute(isBgu)
                videoRecorder?.attachViewfinder(recorderViewfinder(isBgu))
            }
        }
    }

    /** onCreate stage: Saved settings pushed into the controller, plus the debug/stats overlays. */
    private fun applyControllerSettings(shutter: View) {
        if (gpsEnabled()) gpsProvider?.start()
        // The mode switcher owns the practical capture intent; Settings remain advanced defaults.
        controller.setCaptureExposureMode(captureExposureMode)
        controller.setZslHybridTopup(lensPreferences().getBoolean(KEY_ZSL_HYBRID_TOPUP, true))
        controller.setCaptureFormat(captureFormat)
        controller.setJpegOutputSettings(jpegOutputSettings())
        controller.setJxlOutputSettings(jxlOutputSettings())
        controller.setDevelopedFormat(developedFormat)
        controller.setDenoiseSettings(denoiseSettings())
        controller.setGaloshSettings(galoshSettings())
        controller.setVfPreviewMode(vfPreviewMode)
        controller.setVfTargetLongEdge(vfResolution)
        controller.setVfEngineMode(
            VfEngineMode.fromPreference(lensPreferences().getString(KEY_VF_ENGINE_MODE, null))
        )
        vfPreviewButton = findViewById<TextView?>(R.id.vfPreviewButton)?.apply {
            setOnClickListener { cycleVfPreviewMode() }
        }
        refreshVfPreviewButton()
        // The RAW VF debug overlay is the engine switch: tap to cycle AUTO/BGU/BGU_CPU/GPU/CPU.
        rawVfDebugOverlay.isClickable = true
        rawVfDebugOverlay.isFocusable = true
        rawVfDebugOverlay.setOnClickListener { cycleVfEngineMode() }
        vfEngineButton = findViewById<TextView?>(R.id.vfEngineButton)?.apply {
            setOnClickListener { cycleVfEngineMode() }
        }
        refreshVfEngineContentDescription()
        refreshVfEngineContentDescription()
        applyVfRoute(controller.isBguActive())
        videoDebugOverlay = findViewById(R.id.videoDebugOverlay)
        // First-rotation race (same class the scope's deferred seat fixes):
        // the stats text (and its measured size) lands after the
        // entering-rotation seat, parking a landscape strip computed from
        // stale 1-line bounds over the black bar until the next rotation.
        // Re-seat whenever the measured size changes in video landscape
        // (size-guarded, so margin-only layouts never recurse).
        videoDebugOverlay.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if ((r - l) != (orr - ol) || (b - t) != (ob - ot)) {
                if (isVideoMode && (deviceOrientationDegrees == 90 || deviceOrientationDegrees == 270)) {
                    positionWholeRotatedPanels()
                }
            }
        }
        videoCrop = runCatching {
            VideoCrop.valueOf(lensPreferences().getString(KEY_VIDEO_CROP, null) ?: "")
        }.getOrDefault(VideoCrop.OPEN_GATE)
        directLogEnabled = lensPreferences().getBoolean(KEY_DIRECT_LOG, false)
        // The audio line lives inside the stats box now: tap the box to mute.
        videoDebugOverlay.isClickable = true
        videoDebugOverlay.isFocusable = true
        videoDebugOverlay.setOnClickListener { toggleVideoSound() }
        // No top text in video mode: fps/profile switches live in the
        // Settings Video tab (one tap on the video settings button).
        shutter.setOnClickListener {
            if (isVideoMode) toggleVideoRecording()
            else triggerCapture(shutter, forceBurst = false)
        }
        shutter.setOnLongClickListener {
            if (isVideoMode) toggleVideoRecording()
            else triggerCapture(shutter, forceBurst = true)
            true
        }
        isoControl.setOnClickListener { openIsoControl() }
        isoControl.setOnLongClickListener { toggleProgramAxisLock(isIso = true); true }
        shutterControl.setOnClickListener { openShutterControl() }
        shutterControl.setOnLongClickListener { toggleProgramAxisLock(isIso = false); true }
        wbControl.setOnClickListener { showManualControl(ManualControl.WHITE_BALANCE) }
        focusControl.setOnClickListener { showManualControl(ManualControl.FOCUS_DISTANCE) }
        evControl.setOnClickListener { showManualControl(ManualControl.EXPOSURE_COMPENSATION) }
        flashControl.setOnClickListener {
            hideManualControl()
            controller.toggleTorch()
        }
        lightQuick.setOnClickListener { controller.toggleTorch() }
        featureTags.setOnClickListener { toggleQuickControls() }
        rawStatusGroup.setOnClickListener { cycleCaptureFormat() }
        modeButton.setOnClickListener { cycleCaptureExposureMode() }
    }

    /** onCreate stage: Photo/video mode toggle, gallery thumbnail and the quick panel. */
    private fun wireModeAndQuickControls(shutter: View) {
        // Dedicated red dot below the quick-panel access: a MODE toggle, never
        // part of the switcher. Tap = photo <-> video, long-press in video =
        // exit back to photo too. Recording itself lives on the shutter ring.
        findViewById<View>(R.id.videoRecordButton).apply {
            setOnClickListener {
                if (!isVideoMode) enterVideoMode() else exitVideoMode()
            }
            setOnLongClickListener {
                if (isVideoMode && !videoRecording) exitVideoMode()
                true
            }
        }
        findViewById<View>(R.id.quickButton).setOnClickListener { toggleQuickControls() }
        val galleryThumb = findViewById<android.widget.ImageView>(R.id.galleryThumb)
        galleryThumb.clipToOutline = true
        lastCapture = LastCaptureThumbnail(this) { _, _, thumb ->
            galleryThumb.setImageBitmap(thumb)
        }
        galleryThumb.setOnClickListener {
            if (videoRecording) return@setOnClickListener
            closeFloatingPanels()
            if (!lastCapture.open(this)) setStatus("NO CAPTURES YET")
        }
        gridQuick.setOnClickListener {
            gridEnabled = !gridEnabled
            guideOverlay.gridEnabled = gridEnabled
            lensPreferences().edit().putBoolean(KEY_GRID, gridEnabled).apply()
            updateQuickControls()
        }
        histogramQuick.setOnClickListener {
            // Tap toggles the selected graph on/off; the mode is untouched.
            val next = ScopeUiState(histogramEnabled, scopeMode).onTap()
            histogramEnabled = next.enabled
            scopeMode = next.mode
            persistScopeSelection()
            controller.setRawHistogramEnabled(histogramEnabled)
            syncScopeVisibility()
            updateQuickControls()
            scheduleScope()
        }
        histogramQuick.setOnLongClickListener { tile ->
            // Long-press switches graphs, waking the scope when it was off.
            tile.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val next = ScopeUiState(histogramEnabled, scopeMode).onLongPress()
            histogramEnabled = next.enabled
            scopeMode = next.mode
            persistScopeSelection()
            controller.setScopeMode(scopeMode)
            controller.setRawHistogramEnabled(histogramEnabled)
            rawScopeLive = false
            lastRawScopeMs = Long.MIN_VALUE
            syncScopeVisibility()
            updateQuickControls()
            scheduleScope()
            setStatus(scopeStatusLabel())
            true
        }
        levelQuick.setOnClickListener { toggleLevel() }
        aeMeteringQuick.setOnClickListener { cycleAeMeteringMode() }
        hdrPlusQuick.setOnClickListener { toggleHdrPlus() }
        hdrPlusQuick.setOnLongClickListener { cycleHdrPlusQuality(); true }
        mfsrQuick.setOnClickListener { toggleMfsr() }
        ettrQuick.setOnClickListener { toggleEttr() }
        hdrQuick.setOnClickListener { toggleHdrEnabled() }
        timerQuick.setOnClickListener { cycleTimer() }
        releaseQuick.setOnClickListener {
            if (isVideoMode) {
                // LOG takes are full-sensor (crop N/A): the tile switches
                // the output profile instead, like CROP on the CRS path.
                if (directLogEnabled) cycleDirectLogProfile() else cycleVideoCrop()
            } else toggleReleaseMode()
        }
        findViewById<View>(R.id.resetTargetsQuick).setOnClickListener {
            // Releases the AE/AF hold (timed or indefinite padlock) and clears
            // the reticle immediately; the controller re-asserts unlocked AE/AF
            // on the camera thread and confirms via onMeteringReleased.
            meteringOverlay.clearTargets()
            controller.resetMeteringTargets()
            setStatus("AE/AF RESET")
            hideQuickControls()
        }
        findViewById<View>(R.id.settingsButton).setOnClickListener {
            hideManualControl()
            // Video keeps the photo top island: its gear lands on the Video tab.
            if (isVideoMode) showSettings(SETTINGS_VIDEO_TAB) else showSettings()
        }
    }

    /** onCreate stage: Tap-to-focus/meter and the manual slider row. */
    private fun wireTouchAndManualControls(viewfinder: AutoFitTextureView) {
        // Single tap (or joint drag) installs AF + AE together: one repeating
        // update + one AF START, retargeting instantly even while locked.
        // Dragging one square moves only that subsystem (AF rescans, AE re-meters).
        // Press-and-hold freezes AE + AF indefinitely (padlock badge) until a
        // same-spot double-tap or the AE/AF reset button releases it.
        // Overlay taps arrive in fitted-picture coords; the controller
        // normalizes by the full box, so warp them back first.
        meteringOverlay.onTapBoth = { x, y ->
            val (bx, by) = warpTapToBox(x, y, viewfinder)
            controller.setFocusAndMeteringPoint(bx, by)
        }
        meteringOverlay.onBothDragged = { x, y ->
            val (bx, by) = warpTapToBox(x, y, viewfinder)
            controller.setFocusAndMeteringPoint(bx, by)
        }
        meteringOverlay.onAfDragged = { x, y ->
            val (bx, by) = warpTapToBox(x, y, viewfinder)
            controller.setAfPoint(bx, by)
        }
        meteringOverlay.onAeDragged = { x, y ->
            val (bx, by) = warpTapToBox(x, y, viewfinder)
            controller.setAePoint(bx, by)
        }
        meteringOverlay.onAfLockHold = { x, y ->
            val (bx, by) = warpTapToBox(x, y, viewfinder)
            controller.setFocusAndMeteringHold(bx, by)
        }
        meteringOverlay.onTargetsCleared = controller::resetMeteringTargets
        meteringOverlay.onOverlayTouched = { closeFloatingPanels() }
        manualSlider.max = SLIDER_STEPS
        manualSlider.onProgressChanged = { progress, fromUser ->
            if (fromUser) {
                // PROGRAM lock slider and MANUAL slider share the bottom ruler.
                activeProgramAxis?.let { axis ->
                    onProgramAxisScrubbed(axis, progress)
                } ?: run {
                    val control = activeManualControl
                    val range = control?.let { manualRangeFor(it) }
                    if (control != null && range != null) {
                        manualAuto.isEnabled = true
                        pendingSliderUpdate?.let(manualSlider::removeCallbacks)
                        val value = sliderValue(control, range, progress)
                        pendingSliderUpdate = Runnable { setManualValueFor(control, value) }.also {
                            manualSlider.postDelayed(it, SLIDER_UPDATE_DELAY_MS)
                        }
                    }
                }
                refreshRuleSliderAccessibility()
            }
        }
        manualSlider.onStartTracking = { cancelManualPanelHide() }
        manualSlider.onStopTracking = { progress ->
            pendingSliderUpdate?.let(manualSlider::removeCallbacks)
            activeProgramAxis?.let { axis ->
                commitProgramAxis(axis, progress)
            } ?: run {
                activeManualControl?.let { control ->
                    manualRangeFor(control)?.let { range ->
                        setManualValueFor(control, sliderValue(control, range, progress))
                    }
                }
            }
            scheduleManualPanelHide()
        }
        manualAuto.setOnClickListener {
            activeProgramAxis?.let {
                saveProgramProfile(programProfile().copy(lockMode = ProgramLockMode.NONE))
                updateProgramChipStates()
                setStatus("PROGRAM • BOTH AUTO")
                hideManualControl()
                return@setOnClickListener
            }
            activeManualControl?.let { control ->
                setManualValueFor(control, null)
                showManualControl(control, allowToggle = false)
            }
        }
        // PROGRAM ruler limits/metering editor shortcut; visible only while a PROGRAM axis is up.
        manualLimits.setOnClickListener {
            if (activeProgramAxis != null) showProgramPanel()
        }
        updateQuickControls()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
        }
        if (WelcomeFlow.shouldDeferUpdateConsent(
                lensPreferences().getBoolean(KEY_LENS_SETUP_COMPLETE, false)
            )
        ) {
            // First run: lens discovery owns the screen, so the
            // keep-up-to-date ask waits until it is off screen instead of
            // layering over it (fired from showLensSelection's dismiss
            // paths and the no-lenses alert below).
            updateFlow.pendingConsent = true
            showLensDiscovery(firstRun = true)
        } else {
            updateFlow.showConsent()
        }
        viewfinder.addOnLayoutChangeListener { view, left, top, right, bottom,
                                               oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                syncMeteringOverlayToViewfinder(view)
                syncGuideOverlayToViewfinder(view)
            }
        }
    }

    /** onCreate stage: Photo box seating, update consent and the startup check. */
    private fun finishStartup() {
        // Photo 3:4 box seats between the islands: an island or panel size
        // change (first layout, font scale, panel growth) re-seats the box.
        // Size-guarded, so settled layouts never recurse.
        findViewById<View>(R.id.topBar).addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (!isVideoMode && (r - l != orr - ol || b - t != ob - ot)) {
                positionViewfinderOverlays()
            }
        }
        findViewById<View>(R.id.controlPanel).addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) {
                // Video keeps its viewport, but the scope seat still follows
                // the panel or it ends up behind it.
                if (isVideoMode) seatScopeSlot() else positionViewfinderOverlays()
            }
        }
        findViewById<View>(R.id.controlPanel).post {
            positionViewfinderOverlays()
            cameraViewportReady = true
            startCameraWhenReady()
        }
        // The startup check below stays fully offline until the user opts
        // in. Consent itself is asked either above (returning users) or
        // once first-run lens discovery leaves the screen.
        updateFlow.maybeCheckOnStartup()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_PERMISSION) {
            // Lazy mic grant for RAW video: granted -> start with sound,
            // denied -> record silent (legal .crs), never block video.
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            if (videoStartPending) {
                videoStartPending = false
                if (directLogEnabled) doStartDirectLog(withAudio = granted)
                else doStartVideo(withAudio = granted)
            } else {
                setStatus(if (granted) "MIC ON" else "MIC OFF • SILENT")
            }
            return
        }
        if (requestCode == LOCATION_PERMISSION) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                setGpsEnabled(true)
            } else {
                setGpsEnabled(false)
                setStatus("LOCATION DENIED • GPS OFF")
            }
            return
        }
        if (requestCode == CAMERA_PERMISSION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCameraWhenReady()
            // First-install path: WelcomeActivity only forwards here once camera access
            // is granted, but a direct MainActivity launch can reach this callback with
            // lens setup still pending (permission was missing when onCreate ran
            // showLensDiscovery and found nothing). Re-run discovery now that IDs are queryable.
            if (!lensPreferences().getBoolean(KEY_LENS_SETUP_COMPLETE, false)) {
                showLensDiscovery(firstRun = true)
            }
        }
        else setStatus("CAMERA PERMISSION NEEDED")
    }

    @Deprecated("Use picker intent result for the sidecar folder grant")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SETTINGS_EXPORT_REQUEST) {
            settingsTransfer.onExportResult(resultCode, data)
            return
        } else if (requestCode == SETTINGS_IMPORT_REQUEST) {
            settingsTransfer.onImportResult(resultCode, data)
            return
        }
        if (requestCode == UPDATE_INSTALL_SOURCES) {
            updateFlow.onInstallSourcesResult()
            return
        }
        if (requestCode == SIDECAR_TREE_REQUEST && resultCode == RESULT_OK) {
            val tree: Uri? = data?.data
            if (tree != null) {
                SidecarTreeAccess.saveTreeUri(this, tree)
                sidecarSettingsStatus?.text = sidecarFolderText()
                setStatus("SIDECARS • SAME FOLDER")
            } else {
                setStatus("SIDECAR FOLDER NOT CHOSEN")
            }
            folderPickerForVideo = false
            runVideoExportRetry()
        } else if (requestCode == SIDECAR_TREE_REQUEST && folderPickerForVideo) {
            folderPickerForVideo = false
            setStatus("EXPORT FAILED • LOCAL FILE KEPT")
        }
    }

    internal fun sidecarFolderText(): String {
        val tree = SidecarTreeAccess.savedTreeUri(this)
        return if (tree != null && SidecarTreeAccess.hasWriteAccess(this, tree)) {
            "Gyro sidecars: same folder as DNGs " +
                "(${(SidecarTreeAccess.displayPath(tree) ?: "granted folder")}/<burst>)"
        } else {
            "Gyro sidecars: Download/RawLens/<burst> " +
                "(grant the photo folder to save next to DNGs)"
        }
    }

    internal fun pickSidecarFolder() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(
                SidecarTreeAccess.pickerIntent(this, contentResolver),
                SIDECAR_TREE_REQUEST
            )
        } catch (_: Exception) {
            setStatus("FOLDER PICKER UNAVAILABLE")
        }
    }

    override fun onResume() {
        super.onResume()
        // Dialogs/pickers shown while paused (permissions, folder grant, lens
        // discovery alerts) pop the system bars back; hide them again every
        // time we come back so first install is fullscreen without a restart.
        ImmersiveMode.apply(window)
        activityResumed = true
        if (::lastCapture.isInitialized) lastCapture.start()
        MemoryLeakDiagnostics.sampleAsync("activity-resumed")
        DeviceImpactProbe.setForeground(true)
        DeviceImpactProbe.startFrameTracking(window)
        startCameraWhenReady()
        if (gpsEnabled()) gpsProvider?.start()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        if (levelEnabled) levelTracker?.start()
        scheduleScope()
        applyPowerSave()
        registerReceiver(powerSaveReceiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // AlertDialogs (lens discovery, errors) clear immersive flags when
        // they take focus; restore fullscreen when focus comes back.
        if (hasFocus) ImmersiveMode.apply(window)
    }

    /**
     * Volume up/down act as a shutter button. The event is consumed so the system
     * volume never changes and the volume panel never appears. Repeats from a held
     * key are ignored: one press is one capture, like tapping the shutter.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            event.repeatCount == 0
        ) {
            triggerCapture(findViewById(R.id.shutter), forceBurst = false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startCameraWhenReady() {
        // The recorder owns the camera while video state exists (rolling or
        // stopping): a stills start here would STEAL the session and kill the
        // take silently (no frames, audio keeps running). Never touch it.
        if (videoRecording || videoRecorder != null || logRecorder != null) {
            Log.i(LOG_TAG, "startCameraWhenReady suppressed: video owns camera")
            return
        }
        if (activityResumed && cameraViewportReady &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) {
            controller.start()
        }
    }

    /** System battery saver throttles the RAW viewfinder (15 fps, <=640p). */
    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) applyPowerSave()
        }
    }

    private fun applyPowerSave() {
        val saver = getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        findViewById<RawViewfinder>(R.id.rawViewfinder)?.setPowerSave(saver)
        findViewById<BguViewfinder>(R.id.bguViewfinder)?.setPowerSave(saver)
    }

    override fun onPause() {
        activityResumed = false
        if (::lastCapture.isInitialized) lastCapture.stop()
        DeviceImpactProbe.setForeground(false)
        DeviceImpactProbe.stopFrameTracking(window)
        runCatching { unregisterReceiver(powerSaveReceiver) }
        gpsProvider?.stop()
        orientationListener.disable()
        levelTracker?.stop()
        countdownRunnable?.let(window.decorView::removeCallbacks)
        countdownRunnable = null
        // A recording must be finalized before the stills session tears down;
        // no preview rearm while the activity is going away.
        if (videoRecording) stopVideoRecording(rearmPreview = false)
        scopeRunnable?.let(histogramView::removeCallbacks)
        scopeRunnable = null
        controller.stop()
        // PSS sample off-thread (jank); the report itself is counter-only and UI-safe.
        MemoryLeakDiagnostics.sampleAsync("activity-paused")
        MemoryLeakDiagnostics.dumpReport()
        // Citizenship proof: camera/GPS/video state now, threads again in 8 s.
        DeviceImpactProbe.auditBackground(citizenshipExtra())
        DeviceImpactProbe.auditStorage(this)
        super.onPause()
    }

    /** Resource state for background/shutdown audits; never throws. */
    private fun citizenshipExtra(): String = runCatching {
        "camera=${if (controller.isRunning()) "open" else "closed"} " +
            "gps=${if (gpsProvider?.isRunning() == true) "on" else "off"} " +
            "video=$videoRecording dlog=${logRecorder != null} rawvid=${videoRecorder != null}"
    }.getOrDefault("camera=? gps=?")

    override fun onDestroy() {
        lensDiscovery.close()
        if (::lastCapture.isInitialized) lastCapture.release()
        // Safety net only (onPause already stops cleanly): never leak the
        // container handle; the file stays valid, just shorter.
        try {
            videoDebugRunnable?.let { videoDebugOverlay.removeCallbacks(it) }
            videoDebugRunnable = null
            videoRecorder?.stop()
        } catch (_: Exception) {
        }
        try {
            logRecorder?.stop()
        } catch (_: Exception) {
        }
        videoRecorder = null
        logRecorder = null
        controller.destroy()
        findViewById<RawViewfinder>(R.id.rawViewfinder).dispose()
        findViewById<BguViewfinder>(R.id.bguViewfinder)?.dispose()
        findViewById<DirectLogPreviewView>(R.id.recordPreview)?.dispose()
        MemoryLeakDiagnostics.stopPeriodic()
        MemoryLeakDiagnostics.dumpReport()
        MemoryLeakDiagnostics.sample("activity-destroyed")
        DeviceImpactProbe.auditShutdown(citizenshipExtra())
        DeviceImpactProbe.auditStorage(this)
        DeviceImpactProbe.stopHeartbeat()
        super.onDestroy()
    }

    private fun positionViewfinderOverlays() {
        val panel = findViewById<LinearLayout>(R.id.controlPanel)
        val viewfinder = findViewById<AutoFitTextureView>(R.id.viewfinder)
        val topBar = findViewById<ViewGroup>(R.id.topBar)
        val root = panel.parent as View
        findViewById<View>(R.id.captureInfoRow).visibility = View.VISIBLE
        // System preview is a stream target only: keep it attached and flowing for the HAL
        // while fully transparent so ISP pixels never reach the eye. VISIBLE (not INVISIBLE)
        // is load-bearing — some HALs never create the SurfaceTexture otherwise.
        viewfinder.alpha = 0f
        // Fill the exact viewport: letterboxing (fillViewport=false + 3:4) leaves a
        // black strip between the preview and the bottom panel on tall screens.
        // Both islands tuck over the preview by exactly their corner radius so
        // the joints never show a hairline, on any aspect ratio.
        viewfinder.fillViewport = true
        // The camera UI is portrait-locked. Establish the 3:4 viewbox before the camera session
        // is opened so a cold start (including phone flat/upside-down) never gets measured first
        // as an unconstrained tall TextureView. Camera stream selection can then use stable 4:3
        // portrait geometry from its very first frame.
        viewfinder.setAspectRatio(3, 4)
        // Reserve real screen space for controls.  The preview and its overlays are measured
        // only in the remaining viewport, so neither the image nor the thirds grid continues
        // behind ISO/shutter controls.
        val panelHeight = panel.height.takeIf { it > 0 } ?: dp(232)
        // Both islands overlap the preview by exactly their corner radius
        // (16dp, shared top and bottom): the minimum that fills the curves
        // with picture instead of root black.
        val sealOverlap = dp(16)
        // Photo, RAW video and Direct-Log share one 3:4 viewport between the
        // same two islands, so the frame never moves on the mode switch.
        val noTopIsland = topBar.visibility != View.VISIBLE
        val viewfinderParams = (viewfinder.layoutParams as FrameLayout.LayoutParams).apply {
            if (noTopIsland) {
                // No top island to seat against: full-bleed viewport, only
                // the bottom island reserves space.
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = (root.height - panelHeight + sealOverlap).coerceAtLeast(0)
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = 0
            } else {
                // Exact 3:4 viewport between the islands so the
                // preview matches the saved frame (cover == fit, no side
                // crop). Full-width on tall portrait screens, bottom-aligned
                // so the panel overlaps the picture; height-constrained
                // otherwise. Missing measurements keep full-bleed until
                // layout settles.
                val topBarHeight = topBar.height.takeIf { it > 0 } ?: dp(72)
                val box = PhotoViewfinderBox.compute(
                    root.width, root.height, topBarHeight, panelHeight, sealOverlap
                )
                if (box != null) {
                    width = box.width
                    height = box.height
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    topMargin = box.topMargin
                    // Balanced chrome: the bottom panel grows by exactly
                    // the spare so the picture touches both islands with no
                    // void anywhere. Idempotent: with no spare left the
                    // next measure keeps every size and the size guards in
                    // the island/panel listeners stop the loop. The info
                    // line stays in the panel in both modes (1:1 structure,
                    // no jump on switch).
                    panel.minimumHeight = panelHeight + box.spare
                } else {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = (root.height - panelHeight + sealOverlap).coerceAtLeast(0)
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    topMargin = 0
                }
            }
        }
        viewfinder.layoutParams = viewfinderParams
        viewfinder.translationX = 0f
        viewfinder.translationY = 0f
        syncMeteringOverlayToViewfinder(viewfinder)
        syncGuideOverlayToViewfinder(viewfinder)
        manualPanel.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            wrapContent(), Gravity.END or Gravity.BOTTOM
        ).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
            bottomMargin = panelHeight + dp(16)
        }
        quickPanel.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            wrapContent(), Gravity.END or Gravity.BOTTOM
        ).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
            bottomMargin = panelHeight + dp(16)
        }
        updateOverlayStack()
        guideOverlay.setContentInsets(top = 0, end = 0, bottom = 0)
        applyDeviceOrientation(deviceOrientationDegrees, animate = false)
    }

    /**
     * Single stacking authority: scope/lens row always sits above the rule-slider
     * row or quick panel, never behind them. Called after every visibility change.
     * Both scope views share one slot; only the active mode is visible.
     */
    /**
     * Height the scope row must clear above the panel: the manual rule
     * slider or the quick panel (the end-side audio meter never overlaps
     * the start-side scope, so it needs no lift).
     */
    private fun floatingLiftPx(): Int {
        val floatingHeight = when {
            ::manualPanel.isInitialized && manualPanel.visibility == View.VISIBLE ->
                (manualPanel.height.takeIf { it > 0 } ?: dp(124))
            ::quickPanel.isInitialized && quickPanel.visibility == View.VISIBLE ->
                (quickPanel.height.takeIf { it > 0 } ?: dp(200))
            else -> 0
        }
        return if (floatingHeight > 0) floatingHeight + dp(12) else 0
    }

    // Every caller (panels, shutter, crop, rotation) seats through the one
    // rotation-aware path: a separate margin writer here used to drop the
    // quarter-turn lift and park the landscape scope behind the panel.
    internal fun updateOverlayStack() = seatScopeSlot()

    private fun applyDeviceOrientation(degrees: Int, animate: Boolean) {
        deviceOrientationDegrees = degrees
        if (::controller.isInitialized) controller.onDeviceOrientationChanged(degrees)
        // World-horizontal video letterbox (the overlay canvas is
        // portrait-locked, so landscape draws side bars).
        if (::guideOverlay.isInitialized) guideOverlay.videoOrientationDegrees = degrees

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
            // Scopes snap: mid-turn their diagonal footprint outgrows the
            // seat (portrait or quarter-turn lift) and dips behind the panel.
            if (animate && view !in scopeViews()) {
                view.animate().rotation(controlRotationDegrees).setDuration(180L).start()
            } else {
                view.rotation = controlRotationDegrees
            }
        }
        // Fixed labels must never carry rotation from a previous state: pin to 0 so
        // topBar / info-row text always stays inside its bar and visible.
        fixedOrientationViews().forEach { view ->
            view.animate().cancel()
            view.rotation = 0f
            (view as? RotatingContent)?.contentRotation = 0f
        }
        if (::lensSwitcher.isInitialized) {
            for (index in 0 until lensSwitcher.childCount) {
                (lensSwitcher.getChildAt(index) as? RotatingContent)?.contentRotation =
                    controlRotationDegrees
            }
        }
        positionWholeRotatedPanels()
    }

    /** Keep diagnostics and the scope clear of the top controls and capture panel. */
    internal fun positionWholeRotatedPanels() {
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
        debugParams.topMargin = dp(96) + debugRotationInset
        debugOverlay.layoutParams = debugParams
        debugOverlay.translationX = 0f
        debugOverlay.translationY = 0f
        if (::rawVfDebugOverlay.isInitialized) {
            val vfParams = (rawVfDebugOverlay.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(wrapContent(), wrapContent())
            vfParams.gravity = Gravity.TOP or Gravity.START
            vfParams.leftMargin = dp(16)
            val vfRotationInset = if (quarterTurn && rawVfDebugOverlay.width > rawVfDebugOverlay.height) {
                (rawVfDebugOverlay.width - rawVfDebugOverlay.height) / 2
            } else 0
            vfParams.topMargin = dp(96) + vfRotationInset
            rawVfDebugOverlay.layoutParams = vfParams
            rawVfDebugOverlay.translationX = 0f
            rawVfDebugOverlay.translationY = 0f
        }
        if (::videoDebugOverlay.isInitialized) {
            val vdParams = (videoDebugOverlay.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(wrapContent(), wrapContent())
            if (quarterTurn) {
                // Landscape: ride the user's top edge (the portrait side edge
                // the user holding the rotated phone sees as top), over the
                // viewfinder just below the black top band: START for 90, END
                // for 270. The strip is nearly full-width, so plain edge
                // gravity would still pivot near screen center: pull the
                // bounds off-screen by half the width/height difference so
                // the rotated bar lands exactly on the edge.
                val onStart = deviceOrientationDegrees == 90
                vdParams.gravity = Gravity.TOP or
                    (if (onStart) Gravity.START else Gravity.END)
                val vdSideInset =
                    if (videoDebugOverlay.width > videoDebugOverlay.height) {
                        (videoDebugOverlay.width - videoDebugOverlay.height) / 2
                    } else 0
                val vfTop =
                    if (::guideOverlay.isInitialized && guideOverlay.height > 0) guideOverlay.top
                    else dp(96)
                // Fully below the black top band with breathing room (the
                // guide rect starts slightly above the visible image).
                vdParams.topMargin = vfTop + dp(20) + vdSideInset
                vdParams.bottomMargin = 0
                vdParams.marginStart = if (onStart) dp(16) - vdSideInset else 0
                vdParams.marginEnd = if (onStart) 0 else dp(16) - vdSideInset
            } else {
                vdParams.gravity = Gravity.TOP or Gravity.END
                vdParams.topMargin = dp(96)
                vdParams.bottomMargin = 0
                vdParams.marginStart = 0
                vdParams.marginEnd = dp(16)
            }
            videoDebugOverlay.layoutParams = vdParams
            videoDebugOverlay.translationX = 0f
            videoDebugOverlay.translationY = 0f
        }
        // The audio line is part of the stats text now: no separate meter seat.
        if (!::waveformView.isInitialized) return
        seatScopeSlot()
    }

    /**
     * Seats the shared histogram/waveform slot above the capture panel.
     * Unconditional: orientation events can land before layout (or right
     * after GONE -> VISIBLE) when width/height are still 0, and skipping the
     * seat then parks a quarter-turned scope behind the panel until the next
     * rotation. Pre-layout passes fall back to the fixed layout slot, and a
     * single deferred pass corrects with real measurements after layout.
     */
    internal fun seatScopeSlot(deferred: Boolean = false) {
        if (!::histogramView.isInitialized || !::waveformView.isInitialized ||
            !::manualPanel.isInitialized || !::quickPanel.isInitialized) return
        val quarterTurn = deviceOrientationDegrees == 90 || deviceOrientationDegrees == 270
        // One seat in every mode: above the measured panel (whose 232dp
        // minimum the old fixed video override never cleared — the scope hid
        // behind the opaque panel in video and peeked out "cut" in landscape).
        val panelHeight =
            findViewById<View>(R.id.controlPanel).height.takeIf { it > 0 } ?: dp(232)
        val baseBottomMargin = panelHeight + dp(16) + floatingLiftPx()
        val scope = activeScopeView()
        val widthPx = ScopeSlot.sizePx(scope.width, scope.layoutParams.width)
        val heightPx = ScopeSlot.sizePx(scope.height, scope.layoutParams.height)
        // Both scope views share one slot and size: compute once, apply to both.
        // Idempotent: recompute from the base every time instead of adding to
        // the previous margin, so repeated rotations never drift.
        val bottomMargin = ScopeSlot.bottomMarginPx(quarterTurn, widthPx, heightPx, baseBottomMargin)
        // Video landscape: the stats strip owns the user-top edge, so the
        // scope yields to the opposite edge (90° only — at 270° the strip is
        // already opposite). Photo mode always keeps START.
        val yieldEnd = isVideoMode && deviceOrientationDegrees == 90
        scopeViews().forEach { view ->
            (view.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.gravity = Gravity.BOTTOM or (if (yieldEnd) Gravity.END else Gravity.START)
                params.marginStart = if (yieldEnd) 0 else dp(16)
                params.marginEnd = if (yieldEnd) dp(16) else 0
                params.bottomMargin = bottomMargin
                view.layoutParams = params
            }
            view.translationX = 0f
            view.translationY = 0f
        }
        if (!deferred && scope.visibility == View.VISIBLE &&
            (scope.width <= 0 || scope.height <= 0)
        ) {
            scope.post { if (!isFinishing && !isDestroyed) seatScopeSlot(deferred = true) }
        }
    }

    private fun rotatingControls(): List<View> = listOfNotNull(
        findViewById(R.id.settingsButton),
        findViewById(R.id.isoControl),
        findViewById(R.id.shutterControl), findViewById(R.id.wbControl),
        findViewById(R.id.focusControl), findViewById(R.id.evControl),
        // dngInfo/sensorInfo intentionally fixed: wide thin singleLine labels
        // cannot fit 90-degree canvas-rotated text in their bounds and would
        // clip to invisible. Keep horizontal so they stay visible in the top bar.
        // flashControl is GONE (torch lives on the top bar) so it never rotates.
        findViewById(R.id.galleryThumb),
        findViewById(R.id.quickButton), findViewById(R.id.modeButton),
        findViewById(R.id.vfPreviewButton),
        findViewById(R.id.vfEngineButton),
        findViewById(R.id.quickPanelTitle), findViewById(R.id.quickPanelHint),
        findViewById(R.id.gridQuick),
        findViewById(R.id.histogramQuick), findViewById(R.id.levelQuick),
        findViewById(R.id.aeMeteringQuick),
        findViewById(R.id.timerQuick), findViewById(R.id.releaseQuick),
        findViewById(R.id.resetTargetsQuick), findViewById(R.id.hdrQuick),
        findViewById(R.id.lightQuick),
        findViewById(R.id.ettrQuick),
        findViewById(R.id.manualLimitsButton),
        findViewById(R.id.manualControlSlider),
        findViewById(R.id.manualAutoButton)
    )

    /** Debug overlays + scope rotate with the device; top/bottom status labels
     * stay fixed horizontal so wide thin text never clips off-screen. */
    private fun wholeRotatingOverlays(): List<View> = listOfNotNull(
        findViewById(R.id.debugOverlay),
        findViewById(R.id.rawVfDebugOverlay),
        findViewById(R.id.videoDebugOverlay),
        findViewById(R.id.histogramView),
        findViewById(R.id.waveformView)
    )

    /** Wide thin status labels stay fixed horizontal in the top bar so
     * rotated text can never clip them to invisible. */
    private fun fixedOrientationViews(): List<View> = listOfNotNull(
        findViewById(R.id.rawBadge),
        findViewById(R.id.status),
        findViewById(R.id.dngInfo),
        findViewById(R.id.sensorInfo)
    )

    /**
     * Cover picture rect in [boxW]x[boxH] box coords: the same aspect-fill
     * the renderers present, recomputed from the last presented frame so a
     * layout change aligns overlays synchronously. Spans the box and
     * overflows it symmetrically (negative origin) on the cropped axis.
     * Full box until the first frame arrives.
     */
    private fun vfContentRectFor(boxW: Int, boxH: Int): Rect {
        if (vfFrameW > 0 && vfFrameH > 0 && boxW > 0 && boxH > 0) {
            try {
                val f = VfAspectFit.fill(boxW, boxH, vfFrameW, vfFrameH, vfFrameRot)
                return Rect(f[0], f[1], f[0] + f[2], f[1] + f[3])
            } catch (_: IllegalArgumentException) {
            }
        }
        return Rect(0, 0, boxW.coerceAtLeast(0), boxH.coerceAtLeast(0))
    }

    /** Sizes an overlay to the fitted picture inside the viewfinder box. */
    private fun placeOverlayInContent(view: View, box: View, content: Rect) {
        val params = (view.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(content.width(), content.height(), Gravity.TOP or Gravity.START)
        params.width = content.width()
        params.height = content.height()
        params.gravity = Gravity.TOP or Gravity.START
        params.leftMargin = box.left + content.left
        params.topMargin = box.top + content.top
        view.layoutParams = params
        view.translationX = 0f
        view.translationY = 0f
    }

    /**
     * Fitted-geometry arrival from a viewfinder engine (GL/render worker
     * thread): remembers the frame and aligns every overlay to the picture.
     */
    private fun onVfContentGeometry(vfView: View, geo: VfContentGeometry) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (vfView.visibility != View.VISIBLE) return@runOnUiThread
            vfFrameW = geo.frameW
            vfFrameH = geo.frameH
            vfFrameRot = geo.rotation
            val box = findViewById<View>(R.id.viewfinder) ?: return@runOnUiThread
            syncOverlaysToBox(box)
        }
    }

    /**
     * Positions grid, metering, peaking, and level overlays on the full
     * [box] (the system viewfinder rect): the aspect-fill picture spans it
     * edge to edge. The cover rect feeds the tap/face/peaking mappings,
     * which account for the cropped overflow.
     */
    private fun syncOverlaysToBox(box: View) {
        if (box.width <= 0 || box.height <= 0) return
        val full = Rect(0, 0, box.width, box.height)
        if (::guideOverlay.isInitialized) {
            placeOverlayInContent(guideOverlay, box, full)
            guideOverlay.setContentInsets(0, 0, 0)
        }
        if (::meteringOverlay.isInitialized) placeOverlayInContent(meteringOverlay, box, full)
        if (::focusPeakingOverlay.isInitialized) {
            placeOverlayInContent(focusPeakingOverlay, box, full)
            val cover = vfContentRectFor(box.width, box.height)
            focusPeakingOverlay.contentTransform = RectF(
                cover.left.toFloat(), cover.top.toFloat(),
                cover.right.toFloat(), cover.bottom.toFloat()
            )
        }
        if (::levelOverlay.isInitialized) placeOverlayInContent(levelOverlay, box, full)
    }

    /**
     * Maps an overlay tap (box coords) into the full-box coords the
     * controller's tap/metering path normalizes by. The cover rect sits in
     * the same space with a negative origin on the cropped axis, so the tap
     * meters the sensor point actually shown under the finger.
     */
    private fun warpTapToBox(x: Float, y: Float, box: View): Pair<Float, Float> {
        val cover = vfContentRectFor(box.width, box.height)
        if (cover.width() <= 0 || cover.height() <= 0 || box.width <= 0 || box.height <= 0) {
            return x to y
        }
        return (((x - cover.left) * box.width / cover.width()) to
            ((y - cover.top) * box.height / cover.height()))
    }



    private fun syncMeteringOverlayToViewfinder(viewfinder: View) {
        syncOverlaysToBox(viewfinder)
    }

    private fun syncGuideOverlayToViewfinder(viewfinder: View) {
        if (viewfinder.width <= 0 || viewfinder.height <= 0) return
        // The VF surfaces always keep the full box (built fresh: the guide
        // overlay itself is content-sized below, so its params are not a
        // valid template here). Only the overlays shrink to the picture.
        val boxParams = FrameLayout.LayoutParams(
            viewfinder.width, viewfinder.height, Gravity.TOP or Gravity.START
        ).apply {
            leftMargin = viewfinder.left
            topMargin = viewfinder.top
        }
        findViewById<RawViewfinder>(R.id.rawViewfinder).layoutParams = FrameLayout.LayoutParams(boxParams)
        findViewById<BguViewfinder>(R.id.bguViewfinder)?.layoutParams = FrameLayout.LayoutParams(boxParams)
        syncOverlaysToBox(viewfinder)
    }

    /** Top-right status line: always short, never a file name (see [StatusText]). */
    internal fun setStatus(message: String) {
        if (::status.isInitialized) status.text = StatusText.compact(message)
    }

    private fun triggerCapture(shutter: View, forceBurst: Boolean) {
        closeFloatingPanels()
        shutter.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        shutter.animate().cancel()
        shutter.animate().scaleX(0.9f).scaleY(0.9f).setDuration(70L).withEndAction {
            shutter.animate().scaleX(1f).scaleY(1f).setDuration(110L).start()
        }.start()
        // Volume keys land here too: in VIDEO mode they toggle recording.
        if (isVideoMode) {
            toggleVideoRecording()
            return
        }
        if (countdownRunnable != null) {
            setStatus("TIMER ALREADY RUNNING")
            return
        }
        if (timerSeconds == 0) {
            captureNow(forceBurst)
        } else {
            startCountdown(timerSeconds, forceBurst)
        }
    }

    private fun startCountdown(seconds: Int, forceBurst: Boolean) {
        setStatus("TIMER • $seconds")
        countdownRunnable = Runnable {
            if (seconds <= 1) {
                countdownRunnable = null
                captureNow(forceBurst)
            } else {
                startCountdown(seconds - 1, forceBurst)
            }
        }.also { window.decorView.postDelayed(it, 1_000L) }
    }

    private fun captureNow(forceBurst: Boolean) {
        if (dualRawEnabled) {
            controller.captureDualRaw()
            return
        }
        // Offline-merge switch owns the shutter: one tap dumps the whole
        // ZSL ring to _BURST.crs instead of any immediate merge or save.
        if (burstCrsEnabled) {
            captureBurstCrs()
            return
        }
        // Long-press always forces a burst; otherwise HDR is an independent
        // toggle and release only selects SINGLE vs BURST 6.
        val useHdr = !forceBurst && hdrEnabled
        val useBurst = forceBurst || releaseMode == 1
        Log.i(
            LOG_TAG,
            "Shutter mode=${captureModeLabel(useHdr, useBurst)} forceBurst=$forceBurst"
        )
        when {
            useHdr && seqTrioEnabled() -> controller.captureSeqTrio(
                saveEachBracket = lensPreferences().getBoolean(KEY_HDR_SAVE_EACH_BRACKET, false),
                saveDebugFrames = lensPreferences().getBoolean(KEY_HDR_SAVE_DEBUG_FRAMES, false)
            )
            useHdr -> controller.captureHdrBracket(
                saveEachBracket = lensPreferences().getBoolean(KEY_HDR_SAVE_EACH_BRACKET, false),
                bracketStops = hdrBracketStops(),
                saveDebugFrames = lensPreferences().getBoolean(KEY_HDR_SAVE_DEBUG_FRAMES, false)
            )
            useBurst -> controller.captureBurst()
            else -> controller.capture()
        }
    }

    // ------------------------------------------------------------------
    // Offline-merge burst switch (Settings → Burst): ON dumps the whole
    // ZSL ring to IMG_<ts>_BURST.crs (recordingType=BURST, staged under
    // raw-bursts, published to DCIM/RawLens/Bursts); OFF restores normal
    // merging. Like the merger tiles it needs ZSL, so enabling pulls the
    // mode into ZSL and disabling restores the previous mode.
    // ------------------------------------------------------------------
    @JvmName("applyBurstCrsEnabled")
    internal fun setBurstCrsEnabled(enabled: Boolean) {
        if (enabled == burstCrsEnabled) return
        burstCrsEnabled = enabled
        lensPreferences().edit().putBoolean(KEY_BURST_CRS_ENABLED, enabled).apply()
        if (enabled) {
            if (captureExposureMode != CaptureExposureMode.ZSL) {
                modeBeforeBurst = captureExposureMode
                applyCaptureExposureMode(CaptureExposureMode.ZSL)
            }
            setStatus("BURST CRS • WARMING")
        } else {
            val restore = modeBeforeBurst
            modeBeforeBurst = null
            if (restore != null && restore != captureExposureMode &&
                captureExposureMode == CaptureExposureMode.ZSL
            ) {
                applyCaptureExposureMode(restore)
            }
            setStatus("BURST CRS OFF")
        }
        android.util.Log.i(LOG_TAG, "BurstCrs enabled=$enabled")
    }

    /** Offline burst-merge screen: staged takes only, never RAW video. */
    internal fun openMerges() {
        try {
            startActivity(android.content.Intent(this, MergesActivity::class.java))
        } catch (failure: Exception) {
            android.util.Log.w(LOG_TAG, "open merges failed: ${failure.message}")
            setStatus("MERGES UNAVAILABLE")
        }
    }

    /** Single-tap dump: whole ZSL ring to one _BURST.crs, then publish. */
    private fun captureBurstCrs() {
        if (!burstDumpInFlight.compareAndSet(false, true)) {
            setStatus("BURST BUSY")
            return
        }
        val dir = burstStageDir()
        if (!dir.exists() && !dir.mkdirs()) {
            burstDumpInFlight.set(false)
            setStatus("BURST DIR FAILED")
            return
        }
        val file = File(dir, BurstCrsStore.burstFileName(System.currentTimeMillis()))
        setStatus("BURST • SAVING")
        Thread({
            val stats = try {
                controller.dumpZslRingToBurstCrs(file)
            } catch (e: Exception) {
                android.util.Log.w(LOG_TAG, "burst dump failed: ${e.message}")
                null
            }
            if (stats == null || stats.framesWritten <= 0) {
                runOnUiThread {
                    setStatus("BURST EMPTY")
                    burstDumpInFlight.set(false)
                }
                try {
                    if (file.isFile && file.length() == 0L) file.delete()
                } catch (_: Exception) {
                }
                return@Thread
            }
            runOnUiThread {
                val mb = stats.fileBytes / 1e6
                setStatus("BURST ×${stats.framesWritten} • ${"%.1f".format(mb)}MB")
            }
            val export = tryExportBurst(file)
            runOnUiThread {
                burstDumpInFlight.set(false)
                if (export != null) {
                    val mb = stats.fileBytes / 1e6
                    setStatus("BURST ×${stats.framesWritten} • ${"%.1f".format(mb)}MB SAVED")
                } else {
                    setStatus("BURST KEPT • EXPORT FAILED")
                }
            }
        }, "BurstDump").start()
    }

    private fun burstStageDir(): File =
        getExternalFilesDir(BurstCrsStore.STAGE_SUBDIR) ?: File(filesDir, BurstCrsStore.STAGE_SUBDIR)

    /**
     * Publishes one closed burst take: MediaStore first
     * (`DCIM/RawLens/Bursts`), granted-folder SAF fallback when the custom
     * MIME is rejected. The staged source survives any failure.
     */
    private fun tryExportBurst(source: File): RawVideoSaver.Saved? {
        try {
            val saved = BurstCrsStore.publishToBursts(applicationContext.contentResolver, source)
            android.util.Log.i(LOG_TAG, "burst exported uri=${saved.uri} sourceRemoved=${saved.sourceRemoved}")
            return saved
        } catch (mediaStoreFailure: Exception) {
            android.util.Log.w(LOG_TAG, "burst MediaStore export failed, trying granted folder", mediaStoreFailure)
            val tree = SidecarTreeAccess.savedTreeUri(applicationContext)
            if (tree != null && SidecarTreeAccess.hasWriteAccess(applicationContext, tree)) {
                try {
                    val saved = BurstCrsStore.publishViaTree(applicationContext, tree, source)
                    android.util.Log.i(LOG_TAG, "burst exported via tree uri=${saved.uri} sourceRemoved=${saved.sourceRemoved}")
                    return saved
                } catch (treeFailure: Exception) {
                    mediaStoreFailure.addSuppressed(treeFailure)
                    android.util.Log.e(LOG_TAG, "burst export failed; original retained at ${source.absolutePath}", mediaStoreFailure)
                }
            } else {
                android.util.Log.e(LOG_TAG, "burst export failed; original retained at ${source.absolutePath} (grant photo folder under Settings for SAF fallback)", mediaStoreFailure)
            }
            return null
        }
    }

    // ------------------------------------------------------------------
    // RAW Video mode: Blackmagic-style full-bleed HUD (stats strip with
    // the record timecode, settings button, bottom bar with crop + audio
    // meter) over the live RAW viewfinder. The red dot under the quick
    // access toggles photo <-> video, the shutter ring toggles recording,
    // and the photo switcher exits back to photo. Orthogonal to
    // CaptureExposureMode so no stills state machine is touched.
    // ------------------------------------------------------------------

    private fun enterVideoMode() {
        lastPhotoExposureMode = captureExposureMode
        isVideoMode = true
        Log.i(LOG_TAG, "video enter from $lastPhotoExposureMode")
        closeFloatingPanels()
        guideOverlay.videoCrop = videoCrop
        guideOverlay.videoActive = true
        // Same chrome as photo: the top island stays (its gear opens the
        // Video tab) and the viewport keeps the photo 3:4 box. Exposure chips (SS/ISO/WB/focus/EV) stay visible
        // with the same live photo-tile settings.
        findViewById<View>(R.id.controlPanel)?.post { positionViewfinderOverlays() }
        findViewById<View>(R.id.exposureControls)?.visibility = View.VISIBLE
        videoDebugOverlay.visibility = View.VISIBLE
        repositionScopeForVideo()
        refreshVideoAudioDescription()
        findViewById<View>(R.id.shutter).background = getDrawable(R.drawable.record_ready)
        findViewById<View>(R.id.shutter).contentDescription =
            if (directLogEnabled) "Record Direct Log video" else "Record RAW video"
        refreshVideoButton()
        rawBadge.text = if (directLogEnabled) logBadgeLabel() else "CRS"
        rawBadge.setTextColor(getColor(R.color.danger))
        findViewById<TextView>(R.id.dngInfo)?.text =
            if (directLogEnabled) "${directLogProfile().hudLabel} • HEVC10" else "CRS • TYPE-7"
        refreshVideoSensorInfo()
        setStatus("VIDEO READY")
        updateQuickControls()
        scheduleVideoHud()
        // Newly visible HUD views need the current rotation: entering video
        // while already in landscape would otherwise show them unrotated
        // until the next orientation event (widths are valid after layout).
        applyDeviceOrientation(deviceOrientationDegrees, animate = false)
        videoDebugOverlay.post { positionWholeRotatedPanels() }
    }

    private fun exitVideoMode() {
        // A rolling take is finalized first; the actual exit runs after the
        // container closes so the stills session never races the recorder.
        // Rearm is true: by finish time the recorder is dead, and exiting
        // must land on a LIVE stills preview, not a black viewfinder.
        if (videoRecording) {
            stopVideoRecording(rearmPreview = true, afterStop = { exitVideoMode() })
            return
        }
        Log.i(LOG_TAG, "video exit to $lastPhotoExposureMode")
        isVideoMode = false
        guideOverlay.videoActive = false
        findViewById<View>(R.id.shutter).background = getDrawable(R.drawable.shutter_core)
        findViewById<View>(R.id.shutter).contentDescription = "Capture RAW photo"
        videoDebugOverlay.visibility = View.GONE
        videoDebugRunnable?.let { videoDebugOverlay.removeCallbacks(it) }
        videoDebugRunnable = null
        videoAudioPeak = 0f
        findViewById<View>(R.id.controlPanel)?.post { positionViewfinderOverlays() }
        findViewById<View>(R.id.exposureControls)?.visibility = View.VISIBLE
        repositionScopeForVideo()
        refreshVideoButton()
        applyCaptureExposureMode(lastPhotoExposureMode)
        refreshCaptureFormatControl()
        // Restore the stills info chips (DNG bit depth / sensor): video mode
        // overwrote them and refreshVideoSensorInfo() is a no-op once
        // isVideoMode is false, so without this they stay stuck on
        // CRS/BT709 labels in photo mode.
        if (::controller.isInitialized) controller.republishInfo()
        // Restore the photo scope edge (video landscape may have yielded it).
        updateOverlayStack()
    }

    /** Floating scope re-seats above the capture panel on video transitions. */
    private fun repositionScopeForVideo() {
        // One seat in every mode (see seatScopeSlot): the old fixed 150dp
        // video override sat inside the 232dp-minimum panel, hiding the scope
        // behind it. The panel height settles async after the chrome flips,
        // so re-seat again after layout in both directions.
        seatScopeSlot()
        activeScopeView().post { if (!isFinishing && !isDestroyed) seatScopeSlot() }
    }

    // Dedicated red dot under the quick-panel access: a MODE toggle with the
    // app's chip language (dim entry dot / ready ring / rolling red).
    // Tap toggles photo <-> video; shutter ring toggles recording.
    private fun refreshVideoButton() {
        val button = findViewById<View>(R.id.videoRecordButton) ?: return
        when {
            videoRecording -> {
                button.background = getDrawable(R.drawable.record_active)
                button.contentDescription = "Recording. Tap to go back to photo after stopping."
            }
            isVideoMode -> {
                button.background = getDrawable(R.drawable.record_ready)
                button.contentDescription =
                    "Video mode on. Tap to go back to photo."
            }
            else -> {
                button.background = getDrawable(R.drawable.video_enter)
                button.contentDescription = "Enter RAW video mode"
            }
        }
    }

    private fun toggleVideoRecording() {
        if (videoRecording) stopVideoRecording() else startVideoRecording()
    }

    private fun startVideoRecording() {
        if (videoRecording) return
        // A stop drains async (up to ~15s); starting over it would race the
        // container close. LOUD reject, never silent: the user must see why
        // the tap did nothing.
        if (videoRecorder != null || logRecorder != null) {
            setStatus("STOPPING…")
            return
        }
        // Lazy mic grant shared by both video paths; denial records silent
        // video instead of blocking.
        val wantAudio = lensPreferences().getBoolean(KEY_VIDEO_AUDIO, true)
        if (wantAudio &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            videoStartPending = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION)
            setStatus("MIC PERMISSION…")
            return
        }
        if (directLogEnabled) {
            doStartDirectLog(withAudio = wantAudio)
            return
        }
        doStartVideo(withAudio = wantAudio)
    }

    /**
     * Staged takes live here until export publishes (then deletes) them.
     * Keep takes out of cache: a failed export must not be evicted.
     */
    private fun videoStageDir(): File =
        getExternalFilesDir("raw-video") ?: File(filesDir, "raw-video")

    private fun doStartVideo(withAudio: Boolean) {
        val manager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val (cameraId, size) = videoCameraInfo(manager) ?: run {
            setStatus("NO RAW VIDEO CAMERA")
            return
        }
        // Snapshot the preview lens-shading grid before the video session
        // takes over the camera; each recorded frame resamples it to its
        // own crop as the per-frame `lensShadingMap` triplet (null records
        // without the map).
        val lensShadingSnapshot = try {
            controller.snapshotPreviewLensShading(cameraId)
        } catch (_: Exception) {
            null
        }
        // Photo-tile handoff: manual selections + metered fallback frozen per locks.
        val videoSnapshot = runCatching { controller.snapshotVideoManual() }
            .getOrDefault(VideoManualSnapshot())
        val meteredIso = runCatching { controller.snapshotVideoMeteredIso() }.getOrDefault(100)
        val meteredShutter = runCatching { controller.snapshotVideoMeteredShutter() }
            .getOrDefault(10_000_000L)
        val meteredFocus = runCatching { controller.snapshotVideoMeteredFocus() }.getOrNull()
        val recordLocks = videoRecordLocks()
        hideManualControl()
        controller.stop()
        // Stage in android/data (external files dir): visible under
        // Android/data/<pkg>/files/raw-video, no MediaStore churn during
        // capture. Keep takes out of cache: a failed export must not be evicted.
        // RawVideoSaver copies to DCIM/RawLens and deletes the stage on success.
        val dir = videoStageDir()
        if (!dir.exists() && !dir.mkdirs()) {
            setStatus("VIDEO DIR FAILED")
            controller.start()
            return
        }
        val file = File(dir, "${CaptureFileNames.stem(System.currentTimeMillis())}_VID.crs")
        val recorder = RawVideoRecorder(manager, cameraId, size, this).apply {
            crop = videoCrop
            binning = lensPreferences().getBoolean(KEY_VIDEO_BINNING, false)
            lensShading = lensShadingSnapshot
            audioEnabled = withAudio
            configureVideoManual(videoSnapshot, meteredIso, meteredShutter, meteredFocus, recordLocks)
            // Live RAW VF during the take: frames fan out to the same routed
            // engine visibility shows (Vulkan zero-copy lease) while the
            // encode thread encodes.
            attachViewfinder(recorderViewfinder(controller.isBguActive()))
        }
        // V4: storage below the reserve mid-take stops it the normal way, so
        // the footer and index land and the take stays readable.
        recorder.onStorageLow = {
            runOnUiThread {
                if (videoRecording && videoRecorder === recorder) {
                    Log.w(LOG_TAG, "video stop: storage low")
                    stopVideoRecording()
                    setStatus("STORAGE FULL • STOPPED")
                }
            }
        }
        try {
            recorder.start(file, videoContainerMeta(manager, cameraId, size))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "video start failed: ${e.message}")
            setStatus(if (e is RawVideoRecorder.StorageFullException) "STORAGE FULL" else "REC FAILED")
            controller.start()
            return
        }
        videoRecorder = recorder
        videoOutputFile = file
        videoRecording = true
        Log.i(
            LOG_TAG, "video start ${file.name} ${size.width}x${size.height} " +
                "audio=$withAudio bin=${recorder.binActive}"
        )
        findViewById<View>(R.id.shutter).background = getDrawable(R.drawable.record_active)
        findViewById<View>(R.id.shutter).contentDescription = "Stop RAW video"
        refreshVideoButton()
        rawBadge.text = "● REC"
        rawBadge.setTextColor(getColor(R.color.danger))
        refreshVideoAudioDescription()
        val binTag = if (recorder.binActive) " • BIN" else ""
        setStatus("REC • ${videoCrop.label.uppercase(Locale.US)}$binTag")
        scheduleVideoHud()
        // Silent session death (stolen camera, dead HAL stream) looks
        // identical to a healthy take with no UI feedback. Fail LOUD: no
        // acquired frame within 2.5s aborts the take instead of recording
        // minutes of audio-only .crs.
        window.decorView.postDelayed({
            if (videoRecording && videoRecorder === recorder &&
                recorder.snapshot().framesAcquired == 0
            ) {
                Log.w(LOG_TAG, "video abort: no frames acquired in 2.5s")
                stopVideoRecording()
                setStatus("NO FRAMES • STOPPED")
            }
        }, 2_500L)
    }

    /**
     * Direct-Log take: sensor RAW -> Vulkan superpixel + log grade ->
     * HW HEVC Main10 -> MP4. Video-only (no mic prompt), staged like
     * .crs takes and published to DCIM/RawLens on stop.
     *
     * Live RAW VF during the take, like .crs: frames fan out to the
     * viewfinder while the camera thread records. Safe because the shared
     * native Vulkan context is mutex-serialized across the two threads.
     */
    private fun doStartDirectLog(withAudio: Boolean) {
        val manager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val cameraId = videoCameraInfo(manager)?.first ?: run {
            setStatus("NO RAW VIDEO CAMERA")
            return
        }
        val videoSnapshot = runCatching { controller.snapshotVideoManual() }
            .getOrDefault(VideoManualSnapshot())
        val meteredIso = runCatching { controller.snapshotVideoMeteredIso() }.getOrDefault(100)
        val meteredShutter = runCatching { controller.snapshotVideoMeteredShutter() }
            .getOrDefault(10_000_000L)
        val meteredFocus = runCatching { controller.snapshotVideoMeteredFocus() }.getOrNull()
        val recordLocks = videoRecordLocks()
        hideManualControl()
        controller.stop()
        val dir = videoStageDir()
        if (!dir.exists() && !dir.mkdirs()) {
            setStatus("VIDEO DIR FAILED")
            controller.start()
            return
        }
        val profile = directLogProfile()
        val file = File(dir, "${CaptureFileNames.stem(System.currentTimeMillis())}${profile.fileSuffix}.mp4")
        val recorder = DirectLogRecorder(manager, cameraId, applicationContext).apply {
            audioEnabled = withAudio
            stabEnabled = stabOutputMode() != StabOutputMode.OG_ONLY
            exposureEv = directLogGainPref()
            fps = directLogFpsPref()
            val res = directLogResPref()
            width = res.width
            height = res.height
            bitrate = LogVideoProbe.defaultBitrateFor(res.width, res.height)
            logProfile = profile
            videoDemosaic = videoDemosaic()
            configureVideoManual(videoSnapshot, meteredIso, meteredShutter, meteredFocus, recordLocks)
            attachPreviewDisplay(findViewById(R.id.recordPreview))
        }
        // Take monitor: overlay the staged encode pixels (aspect-fit)
        // instead of a separately-demosaiced viewfinder; the RAW VF idles
        // untouched underneath. Hidden in finishDirectLogStop / on failure.
        showRecordPreview()
        try {
            recorder.start(file)
        } catch (e: Exception) {
            hideRecordPreview()
            Log.w(LOG_TAG, "direct-log start failed: ${e.message}")
            setStatus("LOG FAILED")
            controller.start()
            return
        }
        logRecorder = recorder
        videoOutputFile = file
        videoRecording = true
        lastDirectLogStats = null
        Log.i(LOG_TAG, "direct-log start ${file.name}")
        findViewById<View>(R.id.shutter).background = getDrawable(R.drawable.record_active)
        findViewById<View>(R.id.shutter).contentDescription = "Stop Direct Log video"
        refreshVideoButton()
        rawBadge.text = "● LOG • ${profile.hudLabel}"
        rawBadge.setTextColor(getColor(R.color.danger))
        refreshVideoAudioDescription()
        setStatus("REC • ${profile.hudLabel} • HEVC10")
        scheduleVideoHud()
        window.decorView.postDelayed({
            if (videoRecording && logRecorder === recorder &&
                recorder.snapshot().framesGraded == 0
            ) {
                Log.w(LOG_TAG, "direct-log abort: no frames graded in 2.5s")
                stopVideoRecording()
                setStatus("NO FRAMES • STOPPED")
            }
        }, 2_500L)
    }

    /**
     * Overlay the Direct-Log take monitor on the RAW viewfinder's rect
     * (same size/position, so overlays stay aligned; the monitor
     * aspect-fits the 16:9 encode inside it). The RAW VF is never
     * hidden or resized — it simply idles without offers underneath.
     */
    private fun showRecordPreview() {
        val preview = findViewById<DirectLogPreviewView>(R.id.recordPreview) ?: return
        val vf = findViewById<RawViewfinder>(R.id.rawViewfinder)
        if (vf != null && vf.width > 0 && vf.height > 0) {
            val params = FrameLayout.LayoutParams(vf.width, vf.height, Gravity.TOP or Gravity.START)
            params.leftMargin = vf.left
            params.topMargin = vf.top
            preview.layoutParams = params
        }
        preview.visibility = View.VISIBLE
    }

    private fun hideRecordPreview() {
        findViewById<DirectLogPreviewView>(R.id.recordPreview)?.visibility = View.GONE
    }

    /**
     * Publishes one closed take: MediaStore first, granted-folder SAF
     * fallback when MediaStore.Video rejects the custom MIME (some OEMs).
     * Returns null when nothing could publish; the staged source is always
     * retained on failure. Never throws for copy failures.
     */
    private fun tryExportVideo(source: File): RawVideoSaver.Saved? {
        try {
            val saved = RawVideoSaver.save(applicationContext.contentResolver, source)
            Log.i(LOG_TAG, "video exported uri=${saved.uri} sourceRemoved=${saved.sourceRemoved}")
            return saved
        } catch (mediaStoreFailure: Exception) {
            Log.w(LOG_TAG, "video MediaStore export failed, trying granted folder", mediaStoreFailure)
            // Reuse the burst sidecar tree grant (Settings → sidecars) when
            // MediaStore.Video rejects the custom MIME on some OEMs.
            val tree = SidecarTreeAccess.savedTreeUri(applicationContext)
            if (tree != null && SidecarTreeAccess.hasWriteAccess(applicationContext, tree)) {
                try {
                    val saved = RawVideoSaver.saveViaTree(applicationContext, tree, source)
                    Log.i(LOG_TAG, "video exported via tree uri=${saved.uri} sourceRemoved=${saved.sourceRemoved}")
                    return saved
                } catch (treeFailure: Exception) {
                    mediaStoreFailure.addSuppressed(treeFailure)
                    Log.e(LOG_TAG, "video export failed; original retained at ${source.absolutePath}", mediaStoreFailure)
                }
            } else {
                Log.e(LOG_TAG, "video export failed; original retained at ${source.absolutePath} (grant photo folder under Settings for SAF fallback)", mediaStoreFailure)
            }
            return null
        }
    }

    private fun stopVideoRecording(rearmPreview: Boolean = true, afterStop: (() -> Unit)? = null) {
        if (logRecorder != null) {
            stopDirectLogRecording(rearmPreview, afterStop)
            return
        }
        val recorder = videoRecorder ?: return
        val source = videoOutputFile
        if (!videoRecording) return
        videoRecording = false
        Log.i(LOG_TAG, "video stop requested rearm=$rearmPreview")
        setStatus("STOPPING…")
        videoDebugRunnable?.let { videoDebugOverlay.removeCallbacks(it) }
        videoDebugRunnable = null
        // Snapshot the viewfinder while still rolling: after the drain there
        // are no offers for seconds, so a later snapshot would only show the
        // watchdog-cleared state, not the rolling zero-copy path.
        val rollingVf = controller.vfSnapshot()
        // Container close blocks on the encode drain: off the UI thread, then
        // restore on it (mirrors the stills save/rearm split).
        Thread({
            val stats = try {
                recorder.stop()
            } catch (e: Exception) {
                Log.w(LOG_TAG, "video stop failed: ${e.message}")
                null
            }
            var export: RawVideoSaver.Saved? = null
            val commited = stats?.containerFrames ?: 0L
            if (stats != null && source != null && commited > 0 && source.isFile && source.length() > 0) {
                runOnUiThread { setStatus("SAVING TO DCIM/RAWLENS…") }
                export = tryExportVideo(source)
            } else if (stats != null && source != null) {
                Log.w(LOG_TAG, "video export skipped: committed=$commited file=${source.absolutePath} bytes=${runCatching { source.length() }.getOrDefault(-1L)}")
            }
            val saved = export
            runOnUiThread { finishVideoStop(stats, saved, rollingVf, rearmPreview, afterStop) }
        }, "VideoStop").start()
    }

    private fun tryExportDirectLog(source: File): DirectLogSaver.Saved? {
        try {
            val saved = DirectLogSaver.save(applicationContext.contentResolver, source)
            Log.i(LOG_TAG, "direct-log exported uri=${saved.uri} sourceRemoved=${saved.sourceRemoved}")
            return saved
        } catch (mediaStoreFailure: Exception) {
            Log.w(LOG_TAG, "direct-log MediaStore export failed, trying granted folder", mediaStoreFailure)
            val tree = SidecarTreeAccess.savedTreeUri(applicationContext)
            if (tree != null && SidecarTreeAccess.hasWriteAccess(applicationContext, tree)) {
                try {
                    val saved = DirectLogSaver.saveViaTree(applicationContext, tree, source)
                    Log.i(LOG_TAG, "direct-log exported via tree uri=${saved.uri} sourceRemoved=${saved.sourceRemoved}")
                    return saved
                } catch (treeFailure: Exception) {
                    mediaStoreFailure.addSuppressed(treeFailure)
                    Log.e(LOG_TAG, "direct-log export failed; original retained at ${source.absolutePath}", mediaStoreFailure)
                }
            } else {
                Log.e(LOG_TAG, "direct-log export failed; original retained at ${source.absolutePath} (grant photo folder under Settings for SAF fallback)", mediaStoreFailure)
            }
            return null
        }
    }

    private fun stopDirectLogRecording(rearmPreview: Boolean = true, afterStop: (() -> Unit)? = null) {
        val recorder = logRecorder ?: return
        val source = videoOutputFile
        if (!videoRecording) return
        videoRecording = false
        Log.i(LOG_TAG, "direct-log stop requested rearm=$rearmPreview")
        setStatus("STOPPING…")
        videoDebugRunnable?.let { videoDebugOverlay.removeCallbacks(it) }
        videoDebugRunnable = null
        Thread({
            val stats = try {
                recorder.stop()
            } catch (e: Exception) {
                Log.w(LOG_TAG, "direct-log stop failed: ${e.message}")
                null
            }
            var export: DirectLogSaver.Saved? = null
            var stabExport: DirectLogSaver.Saved? = null
            var stabNote: String? = null
            val mode = stabOutputMode()
            if (stats != null && source != null && stats.framesGraded > 0 &&
                source.isFile && source.length() > 0
            ) {
                // Post-record warp pass (opt-in): OG + gyro sidecar ->
                // stabilized MP4, still on the stop thread. Any failure
                // falls through to the OG export below (fail-closed).
                if (mode != StabOutputMode.OG_ONLY) {
                    val sidecar = stabSidecarFor(source)
                    if (stats.hasStabGyro && sidecar.isFile) {
                        runOnUiThread { setStatus("STABILIZING…") }
                        val stabFile = stabOutputFor(source)
                        val report = DirectLogStabilizer(applicationContext).stabilize(
                            source, sidecar, stabFile, directLogProfile()
                        ) { done, total ->
                            runOnUiThread { setStatus("STABILIZING $done/$total") }
                        }
                        try {
                            sidecar.delete()
                        } catch (_: Exception) {
                        }
                        if (report.ok && stabFile.isFile && stabFile.length() > 0) {
                            runOnUiThread { setStatus("SAVING TO DCIM/RAWLENS…") }
                            stabExport = tryExportDirectLog(stabFile)
                            stabNote = "STAB ${report.framesWarped}F"
                        } else {
                            stabNote = "STAB FAILED • OG KEPT"
                            try {
                                stabFile.delete()
                            } catch (_: Exception) {
                            }
                        }
                    } else {
                        stabNote = "NO GYRO • OG KEPT"
                        try {
                            sidecar.delete()
                        } catch (_: Exception) {
                        }
                    }
                }
                // Stabilized-only success drops the staged OG; every other
                // path exports it (including the stab-failure fallback).
                if (mode == StabOutputMode.STABILIZED_ONLY && stabExport != null) {
                    try {
                        source.delete()
                    } catch (_: Exception) {
                        Log.w(LOG_TAG, "staged OG delete failed; local copy kept")
                    }
                } else {
                    runOnUiThread { setStatus("SAVING TO DCIM/RAWLENS…") }
                    export = tryExportDirectLog(source)
                }
            } else if (stats != null && source != null) {
                Log.w(LOG_TAG, "direct-log export skipped: graded=${stats.framesGraded} file=${source.absolutePath} bytes=${runCatching { source.length() }.getOrDefault(-1L)}")
                try {
                    stabSidecarFor(source).delete()
                } catch (_: Exception) {
                }
            }
            val saved = export
            val savedStab = stabExport
            val note = stabNote
            runOnUiThread { finishDirectLogStop(stats, saved, savedStab, note, rearmPreview, afterStop) }
        }, "DirectLogStop").start()
    }

    /** Warp sidecar next to a staged take (written by the recorder). */
    private fun stabSidecarFor(source: File): File =
        File(source.parent, source.nameWithoutExtension + StabSidecar.FILE_SUFFIX)

    /** Stabilized re-encode next to a staged take (`<stem>_LOG709_STAB.mp4`). */
    private fun stabOutputFor(source: File): File =
        File(source.parent, "${source.nameWithoutExtension}_STAB.mp4")

    private fun finishDirectLogStop(
        stats: DirectLogRecorder.Stats?,
        export: DirectLogSaver.Saved?,
        stabExport: DirectLogSaver.Saved?,
        stabNote: String?,
        rearmPreview: Boolean,
        afterStop: (() -> Unit)?
    ) {
        logRecorder = null
        videoOutputFile = null
        lastDirectLogStats = stats
        hideRecordPreview()
        val mb = (stats?.fileBytes ?: 0L) / 1e6
        // Stabilized-only success never exports the OG: either publish
        // counts, and the stab note rides the status either way.
        val primary = export ?: stabExport
        if (isVideoMode) {
            findViewById<View>(R.id.shutter).background = getDrawable(R.drawable.record_ready)
            findViewById<View>(R.id.shutter).contentDescription = "Record Direct Log video"
            refreshVideoButton()
            refreshVideoAudioDescription()
            refreshCaptureFormatControl()
            rawBadge.text = logBadgeLabel()
            rawBadge.setTextColor(getColor(R.color.danger))
            setStatus(
                if (stats == null) "REC FAILED • LOCAL FILE KEPT"
                else if (primary == null) "EXPORT FAILED • LOCAL FILE KEPT"
                else if (!primary.sourceRemoved) "SAVED TO DCIM • LOCAL COPY KEPT"
                else "SAVED TO DCIM • ${stats.framesGraded}F • ${"%.0f".format(mb)}MB" +
                    " • ${stats.profile}" +
                    (if (stats.hasAudio) "" else " • SILENT") +
                    (if (stats.framesDropped > 0) " • D${stats.framesDropped}" else "") +
                    (if (stats.fallbackWaits > 0) " • FB${stats.fallbackWaits}" else "") +
                    (if (stabNote != null) " • $stabNote" else "")
            )
            if (stats != null && primary == null) maybePromptVideoFolderGrant()
            Log.i(
                LOG_TAG, "direct-log saved sensor=${stats?.sensorSize} " +
                    "graded=${stats?.framesGraded} dropped=${stats?.framesDropped} " +
                    "waitFree=${stats?.waitFreeFrames} fallback=${stats?.fallbackWaits} " +
                    "cpuChain=${"%.1f".format(stats?.cpuChainMsAvg ?: 0f)}ms " +
                    "cpuMax=${"%.1f".format(stats?.cpuChainMsMax ?: 0f)}ms " +
                    "overBudget=${stats?.overBudgetFrames} " +
                    "cycleMax=${"%.1f".format(stats?.cycleMsMax ?: 0f)}ms " +
                    "cycleOver=${stats?.cycleOverBudget} " +
                    "head=${stats?.headDroppedFrames} " +
                    "preview=${stats?.previewFrames} previewSkipped=${stats?.previewSkipped} " +
                    "maxDepth=${stats?.maxBacklogDepth} " +
                    "starved=${stats?.inputStarved} degraded=${stats?.degradedFrames} " +
                    "errors=${stats?.frameErrors} resultMiss=${stats?.resultMiss} " +
                    "fused=${stats?.fusedFrames} copyAvg=${"%.1f".format(stats?.copyMsAvg ?: 0f)}ms " +
                    "copyMax=${"%.1f".format(stats?.copyMsMax ?: 0f)}ms " +
                    "fenceAvg=${"%.1f".format(stats?.fenceWaitMsAvg ?: 0f)}ms " +
                    "fenceMax=${"%.1f".format(stats?.fenceWaitMsMax ?: 0f)}ms " +
                    "shaded=${stats?.shadedFrames} " +
                    "stabGyro=${stats?.stabGyroSamples ?: 0} " +
                    "stab=${stabNote ?: "-"} " +
                    "file=${"%.1f".format(mb)}MB"
            )
        }
        if (rearmPreview) startCameraWhenReady()
        afterStop?.invoke()
    }

    private fun finishVideoStop(
        stats: RawVideoRecorder.Stats?,
        export: RawVideoSaver.Saved?,
        rollingVf: RawVfStats?,
        rearmPreview: Boolean,
        afterStop: (() -> Unit)?
    ) {
        videoRecorder = null
        videoOutputFile = null
        val mb = (stats?.fileBytes ?: 0L) / 1e6
        // Viewfinder proof: fps + GPU (Vulkan zero-copy) vs CPU path while rolling.
        val vf = rollingVf
        if (isVideoMode) {
            findViewById<View>(R.id.shutter).background = getDrawable(R.drawable.record_ready)
            findViewById<View>(R.id.shutter).contentDescription = "Record RAW video"
            refreshVideoButton()
            refreshVideoAudioDescription()
            refreshCaptureFormatControl()
            rawBadge.text = "CRS"
            rawBadge.setTextColor(getColor(R.color.danger))
            setStatus(
                if (stats == null) "REC FAILED • LOCAL FILE KEPT"
                else if (export == null) "EXPORT FAILED • LOCAL FILE KEPT"
                else if (!export.sourceRemoved) "SAVED TO DCIM • LOCAL COPY KEPT"
                else "SAVED TO DCIM • ${stats.containerFrames}F • ${"%.0f".format(mb)}MB" +
                    (if (stats.hasAudio) "" else " • SILENT") +
                    (if (stats.framesDropped > 0) " • D${stats.framesDropped}" else "")
            )
            if (stats != null && export == null) maybePromptVideoFolderGrant()
            Log.i(
                LOG_TAG, "video saved frames=${stats?.framesEncoded} " +
                    "dropped=${stats?.framesDropped} skipped=${stats?.commitSkipped} " +
                    "audio=${stats?.audioFrames}f " +
                    "gyro=${stats?.gyroSamples} accel=${stats?.accelSamples} " +
                    "vf=${"%.1f".format(vf?.fps ?: 0f)}fps gpu=${vf?.gpu} " +
                    "file=${"%.1f".format(mb)}MB"
            )
        }
        if (rearmPreview) controller.start()
        // onPause stops with rearm=false; if the user already came back
        // while the drain was in flight, nobody else rearms — do it here.
        // While still paused, starting the camera would run it in background.
        else if (afterStop == null && activityResumed) controller.start()
        afterStop?.invoke()
    }

    /**
     * Opens the shared photo-folder picker when a take failed to export for
     * lack of a SAF grant (MediaStore rejects the custom MIME on some OEMs).
     * The grant result retries every staged take (see onActivityResult), so
     * one grant rescues the whole backlog. Never prompts when a usable grant
     * already exists — a failed tree copy must not picker-loop.
     */
    private fun maybePromptVideoFolderGrant() {
        if (isFinishing || isDestroyed) return
        val tree = SidecarTreeAccess.savedTreeUri(this)
        if (tree != null && SidecarTreeAccess.hasWriteAccess(this, tree)) return
        folderPickerForVideo = true
        setStatus("EXPORT FAILED • GRANT FOLDER TO SAVE TAKE")
        pickSidecarFolder()
    }

    /**
     * Exports every staged take (oldest first) after a folder grant arrives.
     * The actively recording file, if any, is excluded by identity; the
     * current take's own stop-export handles it at stop time.
     */
    private fun runVideoExportRetry() {
        Thread({
            val staged = RawVideoSaver.stagedTakes(videoStageDir(), videoOutputFile)
            if (staged.isEmpty()) return@Thread
            runOnUiThread { setStatus("SAVING ${staged.size} STAGED TAKE(S)…") }
            var saved = 0
            for (file in staged) {
                if (tryExportVideo(file) != null) saved++
            }
            runOnUiThread {
                setStatus(
                    if (saved == staged.size) "SAVED TO DCIM • $saved TAKE(S)"
                    else "SAVED $saved/${staged.size} • REST STAGED"
                )
            }
        }, "VideoExportRetry").start()
    }

    private fun scheduleVideoHud() {
        videoDebugRunnable?.let { videoDebugOverlay.removeCallbacks(it) }
        val tick = object : Runnable {
            override fun run() {
                if (!isVideoMode) return
                val log = logRecorder
                val rec = videoRecorder
                when {
                    log != null -> {
                        val snap = log.snapshot()
                        renderVideoStatsBox(
                            videoTimecode(snap.elapsedMs),
                            String.format(
                                Locale.US, "%s %s %.1fFPS D%d %.0fMB\nMEM %d%% IO %d%% ENC %d%% WF%d FB%d P%d %.1fms",
                                snap.profile, snap.demosaic, snap.outputFps, snap.framesDropped,
                                snap.fileBytes / 1e6, snap.memPct, snap.ioBusyPct, snap.encBusyPct,
                                snap.waitFreeFrames, snap.fallbackWaits, snap.previewFrames,
                                snap.cpuChainMsAvg
                            )
                        )
                    }
                    rec != null -> {
                        val snap = rec.snapshot()
                        // Viewfinder proof, live: fps + GPU (Vulkan zero-copy) vs CPU.
                        val vf = controller.vfSnapshot()
                        renderVideoStatsBox(
                            videoTimecode(snap.elapsedMs),
                            formatVideoDebug(snap, vf?.fps ?: 0f, vf?.gpu == true)
                        )
                    }
                    else -> renderIdleVideoStats()
                }
                videoDebugRunnable?.let { videoDebugOverlay.postDelayed(it, 500L) }
            }
        }
        videoDebugRunnable = tick
        videoDebugOverlay.post(tick)
    }

    /**
     * Idle stats box: the same lines a take shows, zeroed, with live
     * viewfinder proof — the rectangle always carries full info plus the
     * counter, recording or not.
     */
    private fun renderIdleVideoStats() {
        if (directLogEnabled) {
            renderVideoStatsBox(
                videoTimecode(0),
                String.format(
                    Locale.US, "%s %s %dFPS D0 0MB\nMEM 0%% IO 0%% ENC 0%% WF0 FB0 P0 0.0ms",
                    directLogProfile().name, videoDemosaic().name, directLogFpsPref()
                )
            )
            return
        }
        val vf = controller.vfSnapshot()
        renderVideoStatsBox(
            videoTimecode(0),
            formatVideoDebug(
                RawVideoRecorder.VideoStats(
                    recording = false, elapsedMs = 0L, fps = 0f, frameIntervalMs = 0f,
                    encodeMsAvg = 0f, encodeMsMax = 0f, resultWaitMsAvg = 0f, resultWaitMsMax = 0f,
                    closeMsAvg = 0f, closeMsMax = 0f, writeMsAvg = 0f, writeMsMax = 0f,
                    framesAcquired = 0, framesEncoded = 0, framesDropped = 0,
                    commitSkipped = 0, latePurged = 0, queueDepth = 0, reorderDepth = 0,
                    fileBytes = 0L, audioChunks = 0, audioFrames = 0L,
                    gyroSamples = 0L, accelSamples = 0L, cropLabel = videoCrop.label
                ),
                vf?.fps ?: 0f, vf?.gpu == true
            )
        )
    }

    /**
     * Stats box content: the record timecode as the first line (red while
     * rolling, plain in idle), then the stats body. Both video paths share
     * this for takes and idle alike.
     */
    private fun renderVideoStatsBox(timecode: String, body: String) {
        val head = (if (videoRecording) "● " else "") + timecode
        val full = "$head\n$body\n${audioMeterLine()}"
        val spanned = SpannableString(full)
        spanned.setSpan(
            ForegroundColorSpan(getColor(if (videoRecording) R.color.danger else R.color.text_primary)),
            0, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        videoDebugOverlay.text = spanned
    }

    /** Audio description follows the sound toggle; the stats box is the tap target. */
    internal fun refreshVideoAudioDescription() {
        if (!::videoDebugOverlay.isInitialized) return
        val sound = videoSoundOn()
        videoDebugOverlay.contentDescription =
            "Video stats. " + if (sound) "Sound on. Tap to mute next takes."
            else "Sound off. Tap to record sound next takes."
    }

    /** Stats-box audio line with peak hold (HUD tick decays it between takes). */
    private fun audioMeterLine(): String {
        if (!videoSoundOn()) {
            videoAudioPeak = 0f
            return "AUD MUTED"
        }
        val level = (videoRecorder?.audioLevel() ?: logRecorder?.audioLevel() ?: 0f)
            .coerceIn(0f, 1f)
        videoAudioPeak = maxOf(level, videoAudioPeak * 0.85f)
        val filled = (videoAudioPeak * 10f).toInt().coerceIn(0, 10)
        return "AUD [" + "#".repeat(filled) + "-".repeat(10 - filled) + "]"
    }

    /** Persisted Direct-Log grade gain in EV stops, 0 (= ×1.0 neutral) out of
     * the box. Digital gain folded into the grade — the sensor keeps
     * auto-exposing; the recorder applies 2^value per frame. */
    internal fun directLogGainPref(): Float =
        lensPreferences().getFloat(KEY_DIRECT_LOG_GAIN, 0f).coerceIn(-2f, 4f)

    /** Persisted Direct-Log frame rate: 24 or 30, 30 out of the box. Any
     * other stored value heals to 30. Applies to the next take (encoder
     * format + AE range are fixed at take start). */
    internal fun directLogFpsPref(): Int =
        if (lensPreferences().getInt(KEY_DIRECT_LOG_FPS, LogVideoProbe.FPS) == 24) 24 else 30

    internal fun cycleDirectLogFps() {
        val next = if (directLogFpsPref() == 24) 30 else 24
        lensPreferences().edit().putInt(KEY_DIRECT_LOG_FPS, next).apply()
        refreshVideoAudioDescription()
        refreshVideoSensorInfo()
        setStatus("LOG ${next}FPS — NEXT TAKE")
    }

    /** Persisted Direct-Log encode size: 1080p out of the box (RAWR parity:
     * the widest-supported rung), 1440p/4K opt-in. Applies to the next take. */
    internal fun directLogResPref(): LogVideoProbe.EncodeSize =
        LogVideoProbe.encodeSizeForRes(lensPreferences().getString(KEY_DIRECT_LOG_RES, null))

    internal fun cycleDirectLogRes() {
        val w = directLogResPref().width
        val next = when {
            w > 2560 -> "HD1080"
            w > 1920 -> "UHD4K"
            else -> "QHD1440"
        }
        lensPreferences().edit().putString(KEY_DIRECT_LOG_RES, next).apply()
        refreshVideoAudioDescription()
        refreshVideoSensorInfo()
        val label = LogVideoProbe.encodeSizeForRes(next)
        setStatus("LOG ${label.width}x${label.height} — NEXT TAKE")
    }

    /** Persisted Direct-Log output profile: BT709 out of the box. Applies to the next take. */
    internal fun directLogProfile(): DirectLogProfile =
        DirectLogProfile.fromName(lensPreferences().getString(KEY_DIRECT_LOG_PROFILE, null))

    /** Persisted stab output mode: OG only out of the box. Applies to the next take. */
    internal fun stabOutputMode(): StabOutputMode =
        StabOutputMode.fromName(lensPreferences().getString(KEY_VIDEO_STAB_OUTPUT, null))

    /** Persisted Direct-Log demosaic backend: MHC out of the box. Applies to the next take. */
    internal fun videoDemosaic(): VideoDemosaic =
        VideoDemosaic.fromPreference(lensPreferences().getString(KEY_VIDEO_DEMOSAIC, null))

    /** LOG-mode badge: mode + live output profile (e.g. `LOG • SLOG3`). */
    internal fun logBadgeLabel(recording: Boolean = false): String =
        (if (recording) "● " else "") + "LOG • ${directLogProfile().hudLabel}"

    internal fun cycleDirectLogProfile() {
        if (videoRecording) return // encoder format is fixed at take start
        val next = directLogProfile().next()
        lensPreferences().edit().putString(KEY_DIRECT_LOG_PROFILE, next.name).apply()
        rawBadge.text = logBadgeLabel()
        refreshVideoAudioDescription()
        refreshVideoSensorInfo()
        updateQuickControls()
        setStatus("LOG ${next.hudLabel} — NEXT TAKE")
    }

    internal fun formatDirectLogGain(ev: Float): String =
        "×%.1f".format(Locale.US, Math.pow(2.0, ev.toDouble()))

    internal fun logGainText(ev: Float): String = "LOG gain: ${formatDirectLogGain(ev)}"

    private fun videoSoundOn(): Boolean =
        lensPreferences().getBoolean(KEY_VIDEO_AUDIO, true) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    internal fun toggleVideoSound() {
        val next = !lensPreferences().getBoolean(KEY_VIDEO_AUDIO, true)
        lensPreferences().edit().putBoolean(KEY_VIDEO_AUDIO, next).apply()
        refreshVideoAudioDescription()
        setStatus(if (next) "SOUND ON • NEXT TAKE" else "SOUND OFF • NEXT TAKE")
    }

    /** Blackmagic-style timecode HH:MM:SS:FF at the record rate. */
    private fun videoTimecode(elapsedMs: Long): String {
        val fps = RawVideoRecorder.FPS
        val frames = elapsedMs * fps / 1000
        val ff = (frames % fps).toInt()
        val totalSeconds = frames / fps
        val ss = (totalSeconds % 60).toInt()
        val mm = ((totalSeconds / 60) % 60).toInt()
        val hh = (totalSeconds / 3600).toInt()
        return String.format(Locale.US, "%02d:%02d:%02d:%02d", hh, mm, ss, ff)
    }

    /**
     * Video debug overlay: the same mono contract as the RAW VF overlay —
     * cadence, encode cost, drops, queue, file growth, stream rates, plus
     * the live viewfinder path (GPU = Vulkan zero-copy, CPU = NEON).
     */
    private fun formatVideoDebug(s: RawVideoRecorder.VideoStats, vfFps: Float, vfGpu: Boolean): String {
        val rec = videoRecorder
        val w = rec?.frameSize?.width ?: 0
        // No recorder in idle: skip the resolve entirely so dims stays empty
        // (resolving the 0x0 fallback would print a bogus "0x…" size).
        val resolved = if (rec == null) null else try {
            videoCrop.resolve(w.coerceAtLeast(2), rec.frameSize.height)
        } catch (_: Exception) {
            null
        }
        val dims = if (resolved != null) "${w}x${resolved.height}" else ""
        val mb = s.fileBytes / 1e6
        val secs = (s.elapsedMs / 1000.0).coerceAtLeast(0.5)
        val mbs = mb / secs
        val audioKf = s.audioFrames / 1000
        val gyroHz = (s.gyroSamples / secs).toInt()
        // Idle (no recorder yet) has no frame size: crop short stands alone
        // instead of leaving a leading blank.
        val head = if (dims.isEmpty()) videoCropShort() else "$dims ${videoCropShort()}"
        // Strip layout: two dense lines under the timecode, not a tall box.
        return String.format(
            Locale.US, "%.1fFPS %.1fms %s Q%d D%d F%d S%d\nA%dkf G%dHz VF %.0fFPS %s %.0fMB %.0fMB/s",
            s.fps, s.encodeMsAvg, head,
            s.queueDepth, s.framesDropped, s.framesEncoded, s.commitSkipped,
            audioKf, gyroHz, vfFps, if (vfGpu) "GPU" else "CPU", mb, mbs
        )
    }

    internal fun videoCropShort(): String = when (videoCrop) {
        VideoCrop.OPEN_GATE -> "GATE"
        VideoCrop.WIDE_16_9 -> "16:9"
        VideoCrop.SCOPE_2_39 -> "2.39"
        VideoCrop.UNIVISIUM_2_00 -> "2.00"
    }

    internal fun cycleVideoCrop() {
        if (directLogEnabled) {
            setStatus("CROP N/A IN LOG • FULL SENSOR")
            return
        }
        val values = VideoCrop.entries
        videoCrop = values[(values.indexOf(videoCrop) + 1) % values.size]
        lensPreferences().edit().putString(KEY_VIDEO_CROP, videoCrop.name).apply()
        videoRecorder?.crop = videoCrop // mid-record safe, no session reconfig
        guideOverlay.videoCrop = videoCrop
        refreshVideoSensorInfo()
        refreshVideoAudioDescription()
        setStatus("CROP • ${videoCrop.label.uppercase(Locale.US)}")
        updateQuickControls()
    }

    internal fun refreshVideoSensorInfo() {
        if (!isVideoMode) return
        if (directLogEnabled && videoRecorder == null) {
            findViewById<TextView>(R.id.sensorInfo)?.text = "FULL SENSOR • ${directLogFpsPref()}FPS"
            findViewById<TextView>(R.id.dngInfo)?.text = "${directLogProfile().hudLabel} • HEVC10"
            return
        }
        val rec = videoRecorder
        val idlePreview = if (rec == null) videoBinPreview() else null
        val binned = rec?.binActive == true || idlePreview != null
        val dims = when {
            rec != null -> {
                val r = runCatching {
                    videoCrop.resolve(rec.frameSize.width, rec.frameSize.height, binned)
                }.getOrNull()
                if (r != null) {
                    val w = if (binned) r.width / 2 else r.width
                    val h = if (binned) r.height / 2 else r.height
                    "${w}x$h"
                } else "${rec.frameSize.width}x?"
            }
            idlePreview != null -> idlePreview
            else -> "FULL SENSOR"
        }
        val binTag = if (binned) " • BIN" else ""
        findViewById<TextView>(R.id.sensorInfo)?.text = "$dims • ${RawVideoRecorder.FPS}FPS"
        findViewById<TextView>(R.id.dngInfo)?.text =
            "CRS • ${videoCrop.label.uppercase(Locale.US)}$binTag"
    }

    /**
     * Idle-mode encoded-dims preview (`"2040x1528 BIN"` style) when the next
     * take would bin, else null. Best-effort: any camera failure falls back
     * to the plain full-sensor line.
     */
    private fun videoBinPreview(): String? {
        if (!lensPreferences().getBoolean(KEY_VIDEO_BINNING, false)) return null
        val manager =
            getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
                ?: return null
        val (cameraId, size) = runCatching { videoCameraInfo(manager) }.getOrNull() ?: return null
        val chars = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()
            ?: return null
        val cfa = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
        if (!RawVideoRecorder.binningActive(true, cfa, size.width)) return null
        val r = runCatching { videoCrop.resolve(size.width, size.height, true) }.getOrNull()
            ?: return null
        return "${size.width / 2}x${r.height / 2} BIN"
    }

    internal fun videoCameraInfo(
        manager: android.hardware.camera2.CameraManager
    ): Pair<String, android.util.Size>? {
        // Record on the lens the lens row selected (the one the idle preview
        // shows); fall back to the first RAW-capable back camera.
        val selected = runCatching { controller.lensOptions().firstOrNull { it.selected }?.cameraId }
            .getOrNull()
        val ids = listOfNotNull(selected) + manager.cameraIdList.filter { it != selected }
        for (id in ids) {
            val chars = manager.getCameraCharacteristics(id)
            if (chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) !=
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            ) continue
            val size = chars.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)
                ?.maxByOrNull { it.width * it.height }
                ?: continue
            return id to size
        }
        return null
    }

    private fun videoContainerMeta(
        manager: android.hardware.camera2.CameraManager,
        cameraId: String,
        size: android.util.Size
    ): String {
        val chars = manager.getCameraCharacteristics(cameraId)
        val orientation =
            chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val cfa =
            chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
        val bin = RawVideoRecorder.binningActive(
            lensPreferences().getBoolean(KEY_VIDEO_BINNING, false), cfa, size.width
        )
        return "{\"UniqueCameraModel\":\"${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\"," +
            "\"width\":${size.width},\"height\":${size.height}," +
            "\"crop\":\"${videoCrop.name}\",\"fps\":${RawVideoRecorder.FPS}," +
            (if (bin) "\"binning\":\"diamond2x2\"," else "") +
            "\"sensorOrientation\":$orientation,\"encoder\":\"RawLens-Video\"}"
    }

    private fun cycleTimer() {
        timerSeconds = when (timerSeconds) {
            0 -> 2
            2 -> 5
            else -> 0
        }
        timerQuick.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        setStatus(if (timerSeconds == 0) "TIMER OFF" else "TIMER • ${timerSeconds}S")
        updateQuickControls()
    }

    private fun toggleReleaseMode() {
        releaseMode = (releaseMode + 1) % 2
        lensPreferences().edit().putInt(KEY_RELEASE_MODE, releaseMode).apply()
        Log.i(LOG_TAG, "Release mode=${releaseModeLabel(releaseMode)}")
        setStatus(if (releaseMode == 1) "BURST ×6" else "SINGLE")
        updateQuickControls()
    }

    private fun toggleHdrEnabled() {
        val target = HdrShutterExclusivity.bracketToggled(
            hdrEnabled, hdrPlusSettings.enabled)
        // HDR bracket and HDR+ both merge one shutter press into one DNG,
        // so they cannot run together: enabling the bracket turns HDR+ off.
        // While a merge is saving it stays locked on, and the bracket stays
        // off with it, so two tiles never show on.
        if (target.first) turnMfsrOff()
        if (target.second != hdrPlusSettings.enabled &&
            !applyHdrPlusSettings(hdrPlusSettings.copy(enabled = target.second))) {
            return
        }
        // The bracket resolves its own ZSL exit below; forget HDR+'s entry
        // mode so a later HDR+ toggle-off cannot restore over it.
        if (!target.second) modeBeforeHdrPlus = null
        hdrEnabled = target.first
        lensPreferences().edit().putBoolean(KEY_HDR_ENABLED, hdrEnabled).apply()
        if (hdrEnabled) {
            // Leaving ZSL with the bracket: its frames are forward
            // captures, so the AUTO metering takes over again.
            val nextMode = HdrShutterExclusivity.modeAfterBracketEnabled(captureExposureMode)
            if (nextMode != captureExposureMode) applyCaptureExposureMode(nextMode)
        }
        preloadFlowNetForMergedHdr()
        Log.i(LOG_TAG, "HDR enabled=$hdrEnabled")
        setStatus(if (hdrEnabled) "HDR ON" else "HDR OFF")
        updateQuickControls()
    }

    /** A merger tile took the shutter: the HDR bracket turns off. */
    private fun turnHdrBracketOff() {
        if (!hdrEnabled) return
        hdrEnabled = false
        lensPreferences().edit().putBoolean(KEY_HDR_ENABLED, false).apply()
        Log.i(LOG_TAG, "HDR enabled=false")
        updateQuickControls()
    }

    internal fun preloadFlowNetForMergedHdr() {
        if (hdrEnabled &&
            (!lensPreferences().getBoolean(KEY_HDR_SAVE_EACH_BRACKET, false) ||
                lensPreferences().getBoolean(KEY_HDR_SAVE_DEBUG_FRAMES, false))) {
            // Model loading and Vulkan pipeline creation are asynchronous and can be slow on
            // first use. Start as soon as HDR is enabled instead of after capture.
            FlowNetNcnnProcessor.start(applicationContext)
        }
    }

    private fun releaseModeLabel(mode: Int) = when (mode) {
        1 -> "BURST_6"; else -> "SINGLE"
    }

    private fun captureModeLabel(useHdr: Boolean, useBurst: Boolean) = when {
        useHdr -> "HDR_3"
        useBurst -> "BURST_6"
        else -> "SINGLE"
    }

    /**
     * RAW-based ETTR single exposure: OFF → ON → OFF.
     * AUTO mode only: PROGRAM owns exposure through its own RAW loop.
     */
    private fun toggleEttr() {
        if (captureExposureMode != CaptureExposureMode.AUTO) {
            setStatus("ETTR NEEDS AUTO")
            return
        }
        val current = ettrSettings()
        val next = current.copy(enabled = !current.enabled)
        saveEttrSettings(next)
        setStatus(if (next.enabled) "ETTR ON" else "ETTR OFF")
        findViewById<View>(R.id.ettrQuick)
            .performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    // Future SR hook: toggleRawSuperResolution() here (exclusive tile: SR takes
    // the ring from HDR+, plus the HDR bracket shutter).

    private fun toggleHdrPlus() {
        val pair = HdrShutterExclusivity.plusToggled(
            hdrEnabled, hdrPlusSettings.enabled)
        val enabled = pair.second
        if (enabled) turnMfsrOff()
        if (applyHdrPlusSettings(hdrPlusSettings.copy(enabled = enabled))) {
            // HDR+ takes the shutter from the HDR bracket: both merge one
            // shutter press into one DNG, so the bracket turns off.
            if (pair.first != hdrEnabled) turnHdrBracketOff()
            if (enabled) {
                // Switch modes only on success: enabling first keeps a LOCKED
                // toggle from stranding the camera in ZSL with HDR+ still off.
                // Remember where we came from so disabling HDR+ restores it
                // instead of stranding the user in ZSL burst output.
                if (captureExposureMode != CaptureExposureMode.ZSL) {
                    modeBeforeHdrPlus = captureExposureMode
                    applyCaptureExposureMode(CaptureExposureMode.ZSL)
                }
            } else {
                // Back to the mode from before HDR+ forced ZSL. A manual
                // mode change while HDR+ was on is the new intent, so only
                // a still-ZSL camera moves.
                val restore = HdrShutterExclusivity.modeAfterPlusDisabled(
                    captureExposureMode, modeBeforeHdrPlus)
                modeBeforeHdrPlus = null
                if (restore != captureExposureMode) applyCaptureExposureMode(restore)
            }
            setStatus(if (enabled) "HDR+ • WARMING" else "HDR+ OFF")
        }
    }

    private fun toggleMfsr() {
        val enabled = !mfsrSettings.enabled
        if (applyMfsrSettings(mfsrSettings.copy(enabled = enabled))) {
            // The sharp merger takes the shutter: HDR+ and the HDR
            // bracket both merge one press into one artifact, so they
            // turn off (same rule as HDR+ taking it from the bracket).
            if (enabled) {
                if (hdrPlusSettings.enabled) {
                    applyHdrPlusSettings(hdrPlusSettings.copy(enabled = false))
                }
                turnHdrBracketOff()
                if (captureExposureMode != CaptureExposureMode.ZSL) {
                    modeBeforeMfsr = captureExposureMode
                    applyCaptureExposureMode(CaptureExposureMode.ZSL)
                }
            } else {
                val restore = modeBeforeMfsr
                modeBeforeMfsr = null
                if (restore != null && restore != captureExposureMode &&
                    captureExposureMode == CaptureExposureMode.ZSL) {
                    applyCaptureExposureMode(restore)
                }
            }
            setStatus(if (enabled) "MFSR • WARMING" else "MFSR OFF")
        }
    }

    /** A merger tile took the shutter: the sharp merger turns off. */
    private fun turnMfsrOff() {
        if (!mfsrSettings.enabled) return
        // Controller may be saving (LOCKED): keep the tile honest by
        // only clearing on success.
        if (applyMfsrSettings(mfsrSettings.copy(enabled = false))) {
            modeBeforeMfsr = null
            Log.i(LOG_TAG, "MFSR enabled=false")
            updateQuickControls()
        }
    }

    private fun applyMfsrSettings(settings: MfsrSettings): Boolean {
        if (!controller.setMfsrSettings(settings)) {
            setStatus("MFSR LOCKED • SAVING")
            return false
        }
        mfsrSettings = settings
        lensPreferences().edit().apply {
            settings.toPreferences().forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                }
            }
        }.apply()
        updateQuickControls()
        return true
    }

    /**
     * Long-press the HDR+ tile: cycle the merge quality (Fast spatial ↔
     * HQ frequency). Works whether HDR+ is on or off; the choice persists
     * for the next capture.
     */
    private fun cycleHdrPlusQuality() {
        val settings = when {
            hdrPlusSettings.autoQuality ->
                hdrPlusSettings.copy(autoQuality = false, highQuality = true)
            hdrPlusSettings.highQuality -> hdrPlusSettings.copy(highQuality = false)
            else -> hdrPlusSettings.copy(autoQuality = true)
        }
        if (applyHdrPlusSettings(settings)) {
            setStatus(
                when {
                    settings.autoQuality -> "HDR+ QUALITY • AUTO"
                    settings.highQuality -> "HDR+ QUALITY • HQ"
                    else -> "HDR+ QUALITY • FAST"
                }
            )
        }
    }

    internal fun applyHdrPlusSettings(settings: HdrPlusSettings): Boolean {
        if (!controller.setHdrPlusSettings(settings)) {
            setStatus("HDR+ LOCKED • SAVING")
            return false
        }
        hdrPlusSettings = settings
        lensPreferences().edit().apply {
            settings.toPreferences().forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                    is Float -> putFloat(key, value)
                }
            }
        }.apply()
        updateQuickControls()
        return true
    }

    internal fun hdrPlusQuickText(): String {
        val available = !dualRawEnabled && rawZslStatus.state != RawZslState.FALLBACK
        return hdrPlusSettings.quickText(
            lensPreferences().getInt(KEY_RAW_ZSL_FRAME_COUNT, DEFAULT_RAW_ZSL_FRAME_COUNT),
            rawZslStatus.bufferedFrames, available, rawZslStatus.hdrPlusBusy
        )
    }

    internal fun saveEttrSettings(settings: EttrSettings) {
        lensPreferences().edit()
            .putBoolean(KEY_ETTR_ENABLED, settings.enabled)
            .putFloat(KEY_ETTR_HEADROOM_EV, settings.headroomEv)
            .putInt(KEY_ETTR_ISO_LIMIT, settings.isoLimit)
            .apply()
        controller.setEttrSettings(settings)
        updateQuickControls()
    }

    internal fun ettrHeadroomText(headroomEv: Float): String =
        String.format(java.util.Locale.US, "Highlight headroom: -%.1f EV", headroomEv)

    internal fun ettrIsoLimitText(isoLimit: Int): String =
        "ISO ceiling: " + (isoLimit.takeIf { it > 0 }?.toString() ?: "SENSOR MAX")

    private fun cycleCaptureExposureMode() {
        // The photo switcher doubles as the video exit: in VIDEO mode it
        // returns straight to the last photo mode instead of cycling.
        if (isVideoMode) {
            exitVideoMode()
            return
        }
        val modes = CaptureExposureMode.entries
        applyCaptureExposureMode(modes[(modes.indexOf(captureExposureMode) + 1) % modes.size])
    }

    private fun cycleCaptureFormat() {
        val selected = captureFormat.next()
        if (!controller.setCaptureFormat(selected)) {
            setStatus("LOCKED • SAVING")
            return
        }
        captureFormat = selected
        lensPreferences().edit().putString(KEY_CAPTURE_FORMAT, selected.name).apply()
        rawStatusGroup.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        applyScopeSource()
        refreshCaptureFormatControl()
        // FOLLOW tonemap follows the format inside the controller (setCaptureFormat pushes
        // the render state); just refresh the badge label here.
        refreshVfPreviewButton()
        setStatus(captureStatusLabel())
    }

    /** Badge-style pairing label: developed container plus DNG when paired. */
    private fun captureBadgeLabel(): String =
        if (captureFormat.includesJpeg) developedFormat.badgeLabel(captureFormat.includesDng)
        else "DNG"

    /** Short status tokens for the format switch; JPEG default matches legacy. */
    private fun captureStatusLabel(): String = when {
        !captureFormat.includesJpeg -> "DNG ONLY"
        captureFormat.includesDng -> "${developedFormat.token}+DNG"
        else -> developedFormat.token
    }

    /** Shutter-side WYSIWYG switch: FOLLOW -> RAW -> JPEG -> FOLLOW. */
    internal fun cycleVfPreviewMode() {
        vfPreviewMode = when (vfPreviewMode) {
            VfPreviewMode.FOLLOW -> VfPreviewMode.RAW
            VfPreviewMode.RAW -> VfPreviewMode.JPEG
            VfPreviewMode.JPEG -> VfPreviewMode.FOLLOW
        }
        lensPreferences().edit().putString(KEY_VF_PREVIEW_MODE, vfPreviewMode.name).apply()
        controller.setVfPreviewMode(vfPreviewMode)
        vfPreviewButton?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        refreshVfPreviewButton()
        setStatus("VF • ${vfPreviewButton?.text ?: vfPreviewMode.name}")
    }

    private fun refreshVfPreviewButton() {
        vfPreviewButton?.apply {
            text = when (vfPreviewMode) {
                VfPreviewMode.FOLLOW -> if (vfPreviewMode.resolve(captureFormat)) "RAW•A" else "JPG•A"
                VfPreviewMode.RAW -> "RAW"
                VfPreviewMode.JPEG -> "JPG"
            }
            contentDescription = "Viewfinder preview ${vfPreviewMode.name}. Tap to change. " +
                "RAW shows the raw-clean render, JPG shows the cheap AgX preview matching the saved JPEG."
        }
    }

    /** RAW VF debug-overlay tap: cycle the engine AUTO -> GPU -> CPU -> BGU -> BGU_CPU. */
    private fun cycleVfEngineMode() {
        val next = controller.cycleVfEngineMode()
        lensPreferences().edit().putString(KEY_VF_ENGINE_MODE, next.name).apply()
        rawVfDebugOverlay.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        refreshVfEngineContentDescription()
        setStatus("VF • ${next.name}")
    }

    private fun refreshVfEngineContentDescription() {
        val mode = runCatching { controller.vfEngineMode() }.getOrDefault(VfEngineMode.AUTO)
        rawVfDebugOverlay.contentDescription =
            "RAW viewfinder engine ${mode.name}. Tap to switch the GPU / CPU / BGU / BGU-CPU engine."
        vfEngineButton?.apply {
            text = if (mode == VfEngineMode.BGU_CPU) "BGU\nCPU" else mode.name
            contentDescription = rawVfDebugOverlay.contentDescription
        }
    }

    /** Shows exactly one VF surface: BGU or legacy, per the controller route. */
    private fun applyVfRoute(isBgu: Boolean) {
        findViewById<BguViewfinder>(R.id.bguViewfinder)?.visibility =
            if (isBgu) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<RawViewfinder>(R.id.rawViewfinder)?.visibility =
            if (isBgu) android.view.View.GONE else android.view.View.VISIBLE
    }

    /** Recording fan-out target: the same routed engine [applyVfRoute] shows. */
    private fun recorderViewfinder(isBgu: Boolean): VfEngine? =
        selectRoutedViewfinder<VfEngine>(
            isBgu,
            findViewById<BguViewfinder>(R.id.bguViewfinder),
            findViewById<RawViewfinder>(R.id.rawViewfinder)
        )

    internal fun cycleVfResolution() {
        val next = when (vfResolution) {
            VfResolution.MIN -> VfResolution.MID
            VfResolution.MID -> VfResolution.HIGH
            VfResolution.HIGH -> VfResolution.MAX
            else -> VfResolution.MIN
        }
        vfResolution = next
        lensPreferences().edit().putInt(KEY_VF_RESOLUTION, next).apply()
        controller.setVfTargetLongEdge(next)
        setStatus("VF • ${next}PX")
    }

    internal fun refreshCaptureFormatControl() {
        // Same authority rule as the info labels: in VIDEO mode the badge
        // stays CRS/●REC no matter what the stills state machine reports.
        if (isVideoMode) {
            rawBadge.text = if (videoRecording) "● REC" else "CRS"
            rawBadge.setTextColor(getColor(R.color.danger))
            return
        }
        rawBadge.text = captureBadgeLabel()
        rawBadge.setTextColor(getColor(R.color.text_secondary))
        val zslLabel = when (rawZslStatus.state) {
            RawZslState.OFF -> "ZSL off"
            RawZslState.WARMING_UP -> "ZSL warming"
            RawZslState.ACTIVE -> "ZSL active"
            RawZslState.FALLBACK -> "ZSL unavailable"
        }
        val description =
            "Capture format ${captureBadgeLabel()}. $zslLabel. Tap to change format. ${rawZslStatus.detail}"
        rawBadge.contentDescription = description
        rawStatusGroup.contentDescription = description
    }

    internal fun applyCaptureExposureMode(mode: CaptureExposureMode) {
        // A RAW gap holds the last scope frame until new sensor samples arrive.
        if (::histogramView.isInitialized && histogramEnabled) {
            rawScopeLive = false
            refreshScopeContentDescription()
        }
        captureExposureMode = mode
        if (mode != CaptureExposureMode.ZSL && hdrPlusSettings.enabled) {
            applyHdrPlusSettings(hdrPlusSettings.copy(enabled = false))
            // An explicit mode change out of ZSL is the new intent: forget
            // where HDR+ came from so a later toggle-off cannot yank it back.
            modeBeforeHdrPlus = null
        }
        if (mode != CaptureExposureMode.ZSL && mfsrSettings.enabled) {
            applyMfsrSettings(mfsrSettings.copy(enabled = false))
            modeBeforeMfsr = null
        }
        if (mode != CaptureExposureMode.ZSL && burstCrsEnabled) {
            // An explicit mode change out of ZSL is the new intent: the
            // burst switch turns off instead of stranding the shutter.
            burstCrsEnabled = false
            lensPreferences().edit().putBoolean(KEY_BURST_CRS_ENABLED, false).apply()
            modeBeforeBurst = null
        }
        val dynamic = dynamicExposureSettings().copy(enabled = mode == CaptureExposureMode.PROGRAM)
        lensPreferences().edit()
            .putInt(KEY_CAPTURE_EXPOSURE_MODE, mode.ordinal)
            .putBoolean(KEY_RAW_ZSL, mode == CaptureExposureMode.ZSL)
            .putBoolean(KEY_DYNAMIC_EXPOSURE, dynamic.enabled)
            .apply()
        closeFloatingPanels()
        controller.setCaptureExposureMode(mode)
        if (mode == CaptureExposureMode.PROGRAM) {
            // Ensure the live loop uses this lens's profile (migrated on first entry).
            controller.setProgramAeProfile(programProfile())
            isoControl.postDelayed({ showProgramPanelHintOnce() }, 400L)
        }
        modeButton.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        setStatus(when (mode) {
            CaptureExposureMode.AUTO -> "AUTO AE"
            CaptureExposureMode.PROGRAM -> "PROGRAM"
            CaptureExposureMode.ZSL -> "ZSL"
            CaptureExposureMode.MANUAL -> "MANUAL"
        })
        updateQuickControls()
        updateProgramChipStates()
    }

    private fun openIsoControl() {
        // While rolling the recorder owns the sensor: same tile, live take request.
        if (videoRecording) {
            showManualControl(ManualControl.ISO)
            return
        }
        if (captureExposureMode == CaptureExposureMode.PROGRAM) {
            showProgramAxisSlider(isIso = true)
            return
        }
        openManualModeThen(ManualControl.ISO)
    }

    private fun openShutterControl() {
        // While rolling the recorder owns the sensor: same tile, live take request.
        if (videoRecording) {
            showManualControl(ManualControl.SHUTTER)
            return
        }
        if (captureExposureMode == CaptureExposureMode.PROGRAM) {
            showProgramAxisSlider(isIso = false)
            return
        }
        openManualModeThen(ManualControl.SHUTTER)
    }

    /**
     * Spec chip contract: hold ISO/SHUTTER to lock that axis (the other keeps
     * adjusting); hold the second locked axis to lock both, which enters MANUAL
     * seeded from the live pair. Holding a locked axis releases it.
     */
    private fun toggleProgramAxisLock(isIso: Boolean) {
        // PROGRAM locks need the live preview loop; while rolling use the take request instead.
        if (videoRecording) {
            setStatus("STOP TO EDIT PROGRAM • TAKE IS ROLLING")
            return
        }
        if (captureExposureMode != CaptureExposureMode.PROGRAM) {
            setStatus(if (isIso) "ISO HOLD IN PROGRAM" else "S HOLD IN PROGRAM")
            return
        }
        if (!controller.hasManualSensorControl()) {
            setStatus("ANDROID AE • NO MANUAL SENSOR")
            return
        }
        isoControl.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        var profile = programProfile()
        val want = if (isIso) ProgramLockMode.ISO_LOCK else ProgramLockMode.SHUTTER_LOCK
        when {
            profile.lockMode == ProgramLockMode.NONE -> {
                profile = if (isIso) {
                    val range = controller.manualControlRange(ManualControl.ISO)
                    profile.copy(
                        lockMode = ProgramLockMode.ISO_LOCK,
                        lockedIso = profile.lockedIso.takeIf { it > 0 }
                            ?: (range?.current?.toInt() ?: 0)
                    )
                } else {
                    val range = controller.manualControlRange(ManualControl.SHUTTER)
                    profile.copy(
                        lockMode = ProgramLockMode.SHUTTER_LOCK,
                        lockedShutterNanos = profile.lockedShutterNanos.takeIf { it > 0L }
                            ?: (range?.current ?: 0L)
                    )
                }
                saveProgramProfile(profile)
                setStatus(if (isIso) "ISO LOCK • SHUTTER AUTO" else "SHUTTER LOCK • ISO AUTO")
            }
            profile.lockMode == want -> {
                saveProgramProfile(profile.copy(lockMode = ProgramLockMode.NONE))
                setStatus("PROGRAM • BOTH AUTO")
            }
            else -> {
                // Second axis held while the other is locked: manual exposure,
                // seeded from the live PROGRAM pair by the controller.
                saveProgramProfile(profile.copy(lockMode = ProgramLockMode.NONE))
                applyCaptureExposureMode(CaptureExposureMode.MANUAL)
                setStatus("MANUAL • ISO+SHUTTER")
            }
        }
        updateProgramChipStates()
    }

    internal fun ettrSettings(): EttrSettings {
        val prefs = lensPreferences()
        return EttrSettings(
            enabled = prefs.getBoolean(KEY_ETTR_ENABLED, false),
            headroomEv = prefs.getFloat(KEY_ETTR_HEADROOM_EV, 0.3f).coerceIn(0f, 1f),
            isoLimit = prefs.getInt(KEY_ETTR_ISO_LIMIT, 0)
        )
    }

    internal fun dynamicExposureSettings(): DynamicExposureSettings {
        val prefs = lensPreferences()
        return DynamicExposureSettings(
            enabled = prefs.getBoolean(KEY_DYNAMIC_EXPOSURE, false),
            balance = prefs.getFloat(KEY_DYNAMIC_EXPOSURE_BALANCE, 1f),
            isoLimit = prefs.getInt(KEY_DYNAMIC_EXPOSURE_ISO_LIMIT, 0),
            shutterLimitNanos = prefs.getLong(KEY_DYNAMIC_EXPOSURE_SHUTTER_LIMIT, 0L),
            useAutoSafeShutter = prefs.getBoolean(KEY_DYNAMIC_EXPOSURE_AUTO_SHUTTER, true)
        )
    }

    internal fun activeProfileLensId(): String? =
        controllerIfReady?.activeCameraId()
            ?: lensPreferences().getString(KEY_LAST_CAMERA_ID, null)

    internal val controllerIfReady: RawCameraController?
        get() = if (::controller.isInitialized) controller else null

    /** Per-lens PROGRAM profile with one-time migration from legacy global keys. */
    internal fun programProfile(): ProgramAeProfile {
        val lensId = activeProfileLensId()
        if (lensId != null && programAeProfileStore.hasProfile(lensId)) {
            return programAeProfileStore.get(lensId)
        }
        val legacy = dynamicExposureSettings()
        val migrated = ProgramAeProfile.fromLegacy(
            legacy.balance, legacy.isoLimit, legacy.shutterLimitNanos, legacy.useAutoSafeShutter
        )
        if (lensId != null) programAeProfileStore.save(lensId, migrated)
        return migrated
    }

    internal fun saveProgramProfile(profile: ProgramAeProfile) {
        val validated = profile.validated()
        activeProfileLensId()?.let { programAeProfileStore.save(it, validated) }
        // Keep legacy globals in sync so older builds / debug tooling still see ceilings.
        lensPreferences().edit()
            .putFloat(KEY_DYNAMIC_EXPOSURE_BALANCE, ProgramAeProfile.balanceToMultiplier(validated.balance))
            .putInt(KEY_DYNAMIC_EXPOSURE_ISO_LIMIT, validated.isoMax)
            .putLong(KEY_DYNAMIC_EXPOSURE_SHUTTER_LIMIT, validated.shutterMaxNanos)
            .putBoolean(KEY_DYNAMIC_EXPOSURE_AUTO_SHUTTER, validated.useAutoSafeShutter)
            .apply()
        controller.setProgramAeProfile(validated)
        updateProgramChipStates()
        // The quick panel may be open above the editor dialog: refresh it now so tiles
        // (AE metering, and any future PROGRAM state) never wait for collapse/reopen.
        updateQuickControls()
    }

    internal fun jpegOutputSettings(): JpegOutputSettings = JpegOutputSettings(
        ultraHdr = lensPreferences().getBoolean(KEY_JPEG_ULTRA_HDR, false),
        displayP3 = lensPreferences().getBoolean(KEY_JPEG_DISPLAY_P3, false),
        jpegQuality = lensPreferences().getInt(KEY_JPEG_QUALITY, 100),
        chromaSubsampling = JpegChromaSubsampling.fromPreference(
            lensPreferences().getString(KEY_JPEG_CHROMA_SUBSAMPLING, null)
        ),
        demosaic = JpegDemosaic.fromPreference(
            lensPreferences().getString(KEY_JPEG_DEMOSAIC, null)
        ),
        agxPurityBoost = lensPreferences().getFloat(KEY_JPEG_AGX_PURITY, 1f),
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
        },
        highlightHeadroom = lensPreferences().getFloat(KEY_JPEG_HIGHLIGHT_HEADROOM, 1f),
        highlightSoftHeadroom = lensPreferences().getFloat(KEY_JPEG_SKY_PROTECTION, 0.85f),
        highlightShoulder = lensPreferences().getFloat(KEY_JPEG_HIGHLIGHT_SHOULDER, 1f),
        hlReconstruction = lensPreferences().getBoolean(KEY_JPEG_HL_RECONSTRUCTION, true)
    ).resolvedForPlatform()

    /** JPEG XL container options (darktable parity); defaults resolve to 16-bit lossless. */
    internal fun jxlOutputSettings(): JxlOutputSettings = JxlOutputSettings.fromPreferences(
        bitDepthName = lensPreferences().getString(KEY_JXL_BIT_DEPTH, null),
        pixelTypeName = lensPreferences().getString(KEY_JXL_PIXEL_TYPE, null),
        quality = lensPreferences().getInt(KEY_JXL_QUALITY, 100),
        effort = lensPreferences().getInt(KEY_JXL_EFFORT, 7),
        decodingSpeed = lensPreferences().getInt(KEY_JXL_DECODING_SPEED, 0),
        displayP3 = lensPreferences().getBoolean(KEY_JXL_DISPLAY_P3, false),
        hdrTransferName = lensPreferences().getString(KEY_JXL_HDR_TRANSFER, null)
    )

    internal fun denoiseSettings(): DenoiseSettings {
        val prefs = lensPreferences()
        return DenoiseSettings(
            aiEnabled = prefs.getBoolean(KEY_AI_DENOISE_ENABLED, false),
            saveOriginalDng = prefs.getBoolean(KEY_SAVE_ORIGINAL_DNG, true),
            aiStrength = prefs.getInt(KEY_AI_STRENGTH_PCT, 100).coerceIn(0, 100) / 100f,
            wavelet = WaveletSettings(
                enabled = prefs.getBoolean(KEY_WAVELET_ENABLED, false),
                strength = prefs.getInt(KEY_WAVELET_STRENGTH_PCT, 100).coerceIn(0, 200) / 100f,
                luma = prefs.getInt(KEY_WAVELET_LUMA_PCT, 25).coerceIn(0, 100) / 100f,
                maxScale = prefs.getInt(KEY_WAVELET_SCALES, 7).coerceIn(1, 7)
            )
        )
    }

    internal fun persistDenoiseSettings(settings: DenoiseSettings): Boolean {
        if (!controller.setDenoiseSettings(settings)) {
            setStatus("DENOISE AFTER SAVES")
            return false
        }
        lensPreferences().edit()
            .putBoolean(KEY_AI_DENOISE_ENABLED, settings.aiEnabled)
            .putBoolean(KEY_SAVE_ORIGINAL_DNG, settings.saveOriginalDng)
            .putInt(KEY_AI_STRENGTH_PCT, (settings.aiStrength * 100 + 0.5f).toInt().coerceIn(0, 100))
            .putBoolean(KEY_WAVELET_ENABLED, settings.wavelet.enabled)
            .putInt(KEY_WAVELET_STRENGTH_PCT, (settings.wavelet.strength * 100 + 0.5f).toInt().coerceIn(0, 200))
            .putInt(KEY_WAVELET_LUMA_PCT, (settings.wavelet.luma * 100 + 0.5f).toInt().coerceIn(0, 100))
            .putInt(KEY_WAVELET_SCALES, settings.wavelet.maxScale.coerceIn(1, 7))
            .apply()
        return true
    }

    internal fun galoshSettings(): GaloshSettings {
        val prefs = lensPreferences()
        return GaloshSettings(
            enabled = prefs.getBoolean(KEY_GALOSH_ENABLED, false),
            saveOriginalDng = prefs.getBoolean(KEY_GALOSH_SAVE_ORIGINAL_DNG, true),
            strength = prefs.getInt(KEY_GALOSH_STRENGTH_PCT, 100).coerceIn(0, 100) / 100f,
            luma = prefs.getInt(KEY_GALOSH_LUMA_PCT, 100).coerceIn(0, 200) / 100f,
            chroma = prefs.getInt(KEY_GALOSH_CHROMA_PCT, 100).coerceIn(0, 200) / 100f,
            fastUpsample = prefs.getBoolean(KEY_GALOSH_FAST_UPSAMPLE, false),
            fastMode = prefs.getBoolean(KEY_GALOSH_FAST_MODE, false)
        )
    }

    internal fun persistGaloshSettings(settings: GaloshSettings): Boolean {
        if (!controller.setGaloshSettings(settings)) {
            setStatus("DENOISE AFTER SAVES")
            return false
        }
        lensPreferences().edit()
            .putBoolean(KEY_GALOSH_ENABLED, settings.enabled)
            .putBoolean(KEY_GALOSH_SAVE_ORIGINAL_DNG, settings.saveOriginalDng)
            .putInt(KEY_GALOSH_STRENGTH_PCT, (settings.strength * 100 + 0.5f).toInt().coerceIn(0, 100))
            .putInt(KEY_GALOSH_LUMA_PCT, (settings.luma * 100 + 0.5f).toInt().coerceIn(0, 200))
            .putInt(KEY_GALOSH_CHROMA_PCT, (settings.chroma * 100 + 0.5f).toInt().coerceIn(0, 200))
            .putBoolean(KEY_GALOSH_FAST_UPSAMPLE, settings.fastUpsample)
            .putBoolean(KEY_GALOSH_FAST_MODE, settings.fastMode)
            .apply()
        return true
    }

    /**
     * Warm-starts the available RawNIND models when AI denoise is enabled
     * (process-wide singleton, background init): model load plus Vulkan
     * pipeline creation are slow on first use and must never block the
     * save thread. No-op when AI is off so non-AI users pay nothing.
     */
    internal fun preloadRawNindIfEnabled() {
        if (lensPreferences().getBoolean(KEY_AI_DENOISE_ENABLED, false)) {
            runCatching { RawNindNcnnProcessor.start(applicationContext) }
        }
    }

    /** One-line AI model state for the Denoise tab; never blocks (no waitReady). */
    internal fun aiModelStatus(): String {
        val proc = RawNindNcnnProcessor.getInstance()
        if (proc == null) return "AI denoise: starts when enabled"
        return when {
            proc.isReady -> "AI denoise: ready"
            proc.isBayerReady -> "AI denoise: Bayer model ready"
            proc.isLoading || proc.isBayerLoading -> "AI denoise: loading…"
            else -> "AI denoise: unavailable; captures use standard processing"
        }
    }

    /**
     * Crash-on-launch recovery: the dying run left a marker naming its session.
     * Auto-export it to Downloads (reachable in the Files app with no taps) and
     * offer to share it, so a log is obtainable even when Settings is
     * unreachable. Runs off the main thread; the dialog lands when ready.
     */
    /**
     * One-per-release Vulkan notice for devices that fail the gate (older
     * Android, no Vulkan 1.1, missing native library): explains what still
     * works instead of silently degrading. Never blocks the camera.
     */
    private fun maybeShowVulkanGateNotice() {
        val verdict = VulkanSupport.check(packageManager) {
            if (VfVulkan.available) "Vulkan native library loaded"
            else "unsupported: Vulkan native library missing"
        }
        val message = VulkanSupport.userMessage(verdict) ?: return
        val noticedKey = KEY_VULKAN_GATE_NOTICED_VERSION
        val versionCode = packageManager.getPackageInfo(packageName, 0).longVersionCode
        if (lensPreferences().getLong(noticedKey, -1L) == versionCode) return
        lensPreferences().edit().putLong(noticedKey, versionCode).apply()
        Log.w(LOG_TAG, "Vulkan gate failed: $verdict")
        val fullMessage = if (!VulkanSupport.isFp16BufferSupported()) {
            "$message GPU video record additionally needs Android 13+ " +
                "for half-float buffers."
        } else {
            message
        }
        AlertDialog.Builder(this)
            .setTitle("Vulkan GPU unavailable")
            .setMessage(fullMessage)
            .setPositiveButton("Continue", null)
            .show()
    }

    private fun offerCrashedLogIfAny() {
        val crashedSession = LogcatFileWriter.consumeCrashMarker(this) ?: return
        Thread {
            val uri = runCatching {
                LogcatFileWriter.exportSessionToDownloads(this, crashedSession)
            }.getOrNull()
            runOnUiThread { showCrashLogPrompt(crashedSession, uri?.toString()) }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun showCrashLogPrompt(sessionName: String, exportedUri: String?) {
        val exportedNote = if (exportedUri != null) {
            "A copy was saved to Download/RawLens/logs/$sessionName."
        } else {
            "Auto-export failed; the session is still in the app's private log folder."
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("RawLens crashed last run")
            .setMessage("The crash log ($sessionName) was captured. $exportedNote")
            .setPositiveButton(if (exportedUri != null) "Share log" else "OK", null)
            .apply {
                if (exportedUri != null) {
                    setNeutralButton("Later", null)
                }
            }
            .show()
        if (exportedUri != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                shareLogUri(android.net.Uri.parse(exportedUri))
                dialog.dismiss()
            }
        }
    }

    private fun shareLogUri(uri: android.net.Uri) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share RawLens log"))
    }

    internal fun shareLatestLog() {
        val uri = try {
            LogcatFileWriter.exportLatestToDownloads(this)
        } catch (failure: Exception) {
            setStatus("LOG SHARE FAILED")
            return
        }
        if (uri == null) {
            setStatus("NO LOG YET")
            return
        }
        shareLogUri(uri)
    }

    internal fun rawZslFrameCountText(frameCount: Int): String =
        "ZSL frames saved: $frameCount\n" +
            "Approx. ${frameCount * 25} MB at 4080×3060; higher values need more camera memory."

    internal fun zslHybridTopupText(enabled: Boolean): String =
        if (enabled) "Top-up: ON • thin ring completes with fresh frames"
        else "Top-up: OFF • thin ring waits for refill"

    internal fun vfResolutionText(res: Int): String =
        "VF resolution: ${res}px long edge (tap: 480→640→960→1080)"

    internal fun vfPreviewModeText(): String =
        "VF preview: ${vfPreviewMode.name} (${vfPreviewMode.label(captureFormat)})"

    internal fun dngWriterBackend(): DngWriterBackend = DngWriterBackend.fromPreference(
        lensPreferences().getString(KEY_DNG_WRITER_BACKEND, DngWriterBackend.ANDROID.preferenceValue)
    )

    internal fun dngCompression(): DngCompression = DngCompression.fromPreference(
        lensPreferences().getString(KEY_DNG_COMPRESSION, DngCompression.UNCOMPRESSED.preferenceValue)
    )

    internal fun gpsEnabled(): Boolean =
        lensPreferences().getBoolean(KEY_SAVE_LOCATION, false)

    internal fun setGpsEnabled(enabled: Boolean) {
        lensPreferences().edit().putBoolean(KEY_SAVE_LOCATION, enabled).apply()
        if (enabled) {
            gpsProvider?.start()
            setStatus(
                if (gpsProvider?.hasPermission() == true) "GPS ON" else "GPS WAITING FOR FIX"
            )
        } else {
            gpsProvider?.stop()
            setStatus("GPS OFF")
        }
    }

    /** Capture-time fix, or null when the option is off, unpermitted, or fixless. */
    private fun currentGpsLocation(): GpsLocation? {
        if (!gpsEnabled()) return null
        return gpsProvider?.snapshot()
    }

    internal fun lensPreferences() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    internal fun hdrBracketStops(): Int =
        if (lensPreferences().getInt(KEY_HDR_BRACKET_STOPS, 2) == 4) 4 else 2

    internal fun seqTrioEnabled(): Boolean =
        lensPreferences().getBoolean(KEY_SEQ_TRIO_ENABLED, false)

    internal fun selectedLensIds(): Set<String> =
        lensPreferences().getStringSet(KEY_SELECTED_LENSES, emptySet())?.toSet().orEmpty()

    internal fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    internal fun wrapContent(): Int = ViewGroup.LayoutParams.WRAP_CONTENT

    internal fun formatShutter(nanos: Long): String {
        if (nanos <= 0L) return "--"
        val seconds = nanos / 1_000_000_000.0
        return if (seconds >= 1.0) {
            String.format(Locale.US, "%.1fs", seconds)
        } else {
            val reciprocal = 1.0 / seconds
            String.format(Locale.US, "1/%d", reciprocal.toInt())
        }
    }

    internal fun updateProgramChipStates() {
        if (!::isoControl.isInitialized) return
        val lock = runCatching { controller.getProgramAeProfile().lockMode }
            .getOrDefault(ProgramLockMode.NONE)
        val inProgram = captureExposureMode == CaptureExposureMode.PROGRAM
        isoControl.contentDescription = when {
            !inProgram -> "ISO control"
            lock == ProgramLockMode.ISO_LOCK -> "ISO locked. Tap for slider, hold to release. Auto in slider releases both locks."
            else -> "ISO auto. Tap for slider, hold to lock ISO and let shutter adjust."
        }
        shutterControl.contentDescription = when {
            !inProgram -> "Shutter control"
            lock == ProgramLockMode.SHUTTER_LOCK -> "Shutter locked. Tap for slider, hold to release. Auto in slider releases both locks."
            else -> "Shutter auto. Tap for slider, hold to lock shutter and let ISO adjust."
        }
    }

    /** Scope source follows the capture format: AgX-curved JPEG preview with a JPEG
     * format, linear RAW with DNG only. */
    internal fun applyScopeSource() {
        if (!::histogramView.isInitialized || !::waveformView.isInitialized) return
        val agx = scopeAgxApplied()
        histogramView.setAgxApplied(agx)
        waveformView.setAgxApplied(agx)
        if (::controller.isInitialized) controller.setScopeAgxApplied(agx)
        rawScopeLive = false
        lastRawScopeMs = Long.MIN_VALUE
        refreshScopeContentDescription()
    }

    internal fun hideManualControl() {
        if (!::manualPanel.isInitialized) return
        cancelManualPanelHide()
        if (::manualSlider.isInitialized) {
            pendingSliderUpdate?.let(manualSlider::removeCallbacks)
        }
        pendingSliderUpdate = null
        manualPanel.visibility = View.GONE
        activeManualControl = null
        activeProgramAxis = null
        updateOverlayStack()
    }

    internal fun hideQuickControls() {
        if (!::quickPanel.isInitialized || quickPanel.visibility != View.VISIBLE) return
        quickPanel.animate().cancel()
        quickPanel.visibility = View.GONE
        quickPanel.alpha = 1f
        quickPanel.translationY = 0f
        updateOverlayStack()
    }

    internal fun refreshScopeContentDescription() {
        if (!::histogramView.isInitialized || !::waveformView.isInitialized) return
        val source = if (scopeAgxApplied()) "AgX JPEG preview" else "linear RAW sensor"
        val live = rawScopeLive && !rawScopeStalled()
        val freshness = if (live) "live" else "waiting for a fresh RAW frame"
        histogramView.contentDescription =
            "Histogram, $source, $freshness. Tap to switch to waveform."
        waveformView.contentDescription =
            "Waveform, $source, $freshness. Tap to switch to histogram."
    }

    /** RAW frames arrive push-driven; this only refreshes the stall status. */
    internal fun scheduleScope() {
        if (!::histogramView.isInitialized) return
        scopeRunnable?.let(histogramView::removeCallbacks)
        scopeRunnable = null
        if (!histogramEnabled) return
        scopeRunnable = object : Runnable {
            override fun run() {
                if (!isFinishing && !isDestroyed && histogramEnabled) {
                    refreshScopeContentDescription()
                    histogramView.postDelayed(this, HISTOGRAM_INTERVAL_MS)
                }
            }
        }.also { histogramView.post(it) }
    }

    /** Edge-triggered by the controller's manual/tap-focus auto-show policy. */
    internal fun setFocusPeakingVisible(visible: Boolean) {
        if (!::focusPeakingOverlay.isInitialized) return
        focusPeakingOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) focusPeakingOverlay.clear()
    }

    internal fun syncLevelVisibility() {
        if (!::levelOverlay.isInitialized) return
        levelOverlay.visibility = if (levelEnabled) View.VISIBLE else View.GONE
        if (levelEnabled) {
            if (activityResumed) levelTracker?.start()
        } else {
            levelTracker?.stop()
            levelOverlay.setFlat()
            levelOverlay.contentDescription = "Virtual horizon off"
        }
    }

    /** Tap the scope to switch between histogram and waveform. The mode is applied
     * immediately and remembered across restarts. */
    internal fun toggleScopeMode() {
        if (!histogramEnabled || !::histogramView.isInitialized || !::waveformView.isInitialized) return
        scopeMode = scopeMode.next()
        persistScopeSelection()
        controller.setScopeMode(scopeMode)
        activeScopeView().performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        rawScopeLive = false
        lastRawScopeMs = Long.MIN_VALUE
        syncScopeVisibility()
        refreshScopeContentDescription()
        updateQuickControls()
        setStatus(scopeStatusLabel())
    }

    internal fun updateQuickControls() {
        if (!::gridQuick.isInitialized || !::levelQuick.isInitialized) return
        val grid = gridQuick
        val histogram = histogramQuick
        val level = levelQuick
        val aeMetering = aeMeteringQuick
        val hdr = hdrQuick
        val timer = timerQuick
        val release = releaseQuick
        val sharp = mfsrQuick
        val hdrPlus = hdrPlusQuick
        val ettr = ettrQuick
        if (dualRawEnabled && controller.activeCameraId() != "0") dualRawEnabled = false
        release.isEnabled = !dualRawEnabled
        release.alpha = if (dualRawEnabled) .4f else 1f
        grid.text = "GRID\n${if (gridEnabled) "THIRDS" else "OFF"}"
        histogram.text = "SCOPE\n${scopeTileLabel()}"
        val graph = if (scopeMode == ScopeMode.WAVEFORM) "waveform" else "histogram"
        val power = if (histogramEnabled) "on" else "off"
        histogram.contentDescription =
            "Exposure scope, $graph, $power. Tap to turn ${if (histogramEnabled) "off" else "on"}, long-press to switch graphs."
        level.text = "LEVEL\n${if (levelEnabled) "ON" else "OFF"}"
        level.contentDescription =
            "Virtual horizon ${if (levelEnabled) "on" else "off"}. Tap to turn ${if (levelEnabled) "off" else "on"}."
        aeMeteringMode = controller.getAeMeteringMode()
        if (captureExposureMode == CaptureExposureMode.PROGRAM) {
            val rawMetering = runCatching { controller.getProgramAeProfile().metering }
                .getOrDefault(ProgramMetering.CENTER_WEIGHTED)
            aeMetering.text = "AE METER\n${programMeteringLabel(rawMetering)}"
            val manualSensor = controller.hasManualSensorControl()
            aeMetering.isEnabled = manualSensor
            aeMetering.alpha = if (manualSensor) 1f else 0.4f
            aeMetering.contentDescription =
                "RAW exposure metering ${programMeteringLabel(rawMetering)}. Tap to switch center, average, spot."
            setQuickTileState(aeMetering, rawMetering != ProgramMetering.CENTER_WEIGHTED)
        } else {
            aeMetering.text = "AE METER\n${aeMeteringMode.label}"
            val aeMeteringSupported = controller.isAeMeteringSupported()
            aeMetering.isEnabled = aeMeteringSupported
            aeMetering.alpha = if (aeMeteringSupported) 1f else 0.4f
            aeMetering.contentDescription =
                "Hardware AE metering ${aeMeteringMode.label}. Tap to switch."
            setQuickTileState(aeMetering, aeMeteringMode != AeMeteringMode.AUTO)
        }
        hdr.text = "HDR\n${if (hdrEnabled) "ON" else "OFF"}"
        hdr.contentDescription = "HDR bracket ${if (hdrEnabled) "on" else "off"}. Tap to toggle."
        timer.text = "TIMER\n${if (timerSeconds == 0) "OFF" else "${timerSeconds}S"}"
        release.text = if (dualRawEnabled) "RELEASE\nRAW HDR TEST" else "RELEASE\n${if (releaseMode == 1) "BURST 6" else "SINGLE"}"
        val sharpAvailable = !dualRawEnabled && rawZslStatus.state != RawZslState.FALLBACK
        sharp.text = mfsrSettings.quickText(
            rawZslStatus.bufferedFrames, sharpAvailable, rawZslStatus.mfsrBusy)
        sharp.isEnabled = sharpAvailable
        sharp.alpha = if (sharpAvailable) 1f else 0.4f
        hdrPlus.text = hdrPlusQuickText()
        val hdrPlusAvailable = !dualRawEnabled && rawZslStatus.state != RawZslState.FALLBACK
        hdrPlus.isEnabled = hdrPlusAvailable
        hdrPlus.alpha = if (hdrPlusAvailable) 1f else 0.4f
        val ettrMode = controller.getEttrSettings()
        val ettrAvailable = captureExposureMode == CaptureExposureMode.AUTO
        val ettrEnabled = ettrMode.enabled && ettrAvailable
        ettr.text = "ETTR\n" + if (ettrEnabled) "ON" else "OFF"
        ettr.isEnabled = ettrAvailable
        ettr.alpha = if (ettrAvailable) 1f else 0.4f
        setQuickTileState(grid, gridEnabled)
        setQuickTileState(histogram, histogramEnabled)
        setQuickTileState(level, levelEnabled)
        setQuickTileState(hdr, hdrEnabled)
        setQuickTileState(timer, timerSeconds > 0)
        setQuickTileState(release, releaseMode != 0)
        setQuickTileState(sharp, mfsrSettings.enabled)
        setQuickTileState(hdrPlus, hdrPlusSettings.enabled)
        setQuickTileState(ettr, ettrEnabled)
        refreshFeatureTags(ettrEnabled)
        // Video crop lives on the RELEASE tile in VIDEO mode only; the mode
        // button itself stays photo-only (video is a separate red button).
        // LOG takes are full-sensor, so the tile shows the output profile.
        release.contentDescription = null // else the LOG text lingers after mode switches
        if (isVideoMode) {
            if (directLogEnabled) {
                val profile = directLogProfile()
                release.text = "LOG\n${profile.hudLabel}"
                release.contentDescription =
                    "Direct Log output profile ${profile.hudLabel}. Tap to switch."
                setQuickTileState(release, profile != DirectLogProfile.BT709)
            } else {
                release.text = "CROP\n${videoCropShort()}"
                setQuickTileState(release, videoCrop != VideoCrop.OPEN_GATE)
            }
        }
        modeButton.text = when (captureExposureMode) {
            CaptureExposureMode.AUTO -> "A\nAUTO"
            CaptureExposureMode.PROGRAM -> "P\nPROGRAM"
            CaptureExposureMode.ZSL -> "Z\nZSL"
            CaptureExposureMode.MANUAL -> "M\nMANUAL"
        }
        val exposureModeActive = captureExposureMode != CaptureExposureMode.AUTO
        setQuickTileState(modeButton, exposureModeActive)
    }

    companion object {
        const val LOG_TAG = "RawLensCamera"
        const val CAMERA_PERMISSION = 42
        const val LOCATION_PERMISSION = 43
        const val SIDECAR_TREE_REQUEST = 44
        const val SETTINGS_EXPORT_REQUEST = 45
        const val SETTINGS_IMPORT_REQUEST = 46
        const val UPDATE_INSTALL_SOURCES = 47
        const val AUDIO_PERMISSION = 45
        const val KEY_VIDEO_CROP = "video_crop"
        const val KEY_VIDEO_BINNING = "video_binning"
        const val KEY_VIDEO_AUDIO = "video_audio"
        const val KEY_VIDEO_STAB_OUTPUT = "video_stab_output"
        const val KEY_VIDEO_DEMOSAIC = "video_demosaic"
        // v2: the v1 default (+1.5) compensated for the old lift-everything
        // chain; the corrected chain meters at 0, so stored v1 values are
        // not carried over.
        // Stored key keeps the legacy name so existing values survive the LOG EV -> LOG gain rename.
        const val KEY_DIRECT_LOG_GAIN = "direct_log_ev_v2"
        const val KEY_DIRECT_LOG_FPS = "direct_log_fps"
        const val KEY_DIRECT_LOG_RES = "direct_log_res"
        const val KEY_DIRECT_LOG = "direct_log_video"
        const val KEY_DIRECT_LOG_PROFILE = "direct_log_profile"
        const val KEY_SAVE_LOCATION = "save_location_gps"
        const val PREFS_NAME = SettingsBackup.STORE_MAIN
        /** Settings sheet page index of the Video tab (keep in sync with tabPages order). */
        const val SETTINGS_VIDEO_TAB = 2
        const val KEY_SELECTED_LENSES = "selected_lens_ids"
        const val KEY_LENS_SETUP_COMPLETE = "lens_setup_complete"
        const val KEY_LAST_CAMERA_ID = "last_camera_id"
        /** Last versionCode shown the Vulkan-unavailable notice (once per release). */
        const val KEY_VULKAN_GATE_NOTICED_VERSION = "vulkan_gate_noticed_version"
        /** Row cap for the advertised vendor-key picker so tag-heavy HALs stay usable. */
        const val MAX_VENDOR_DISCOVERY_ROWS = 400
        const val KEY_DEBUG_OVERLAY = "camera_debug_overlay"
        const val KEY_LOGCAT_FILE = "logcat_file_enabled"
        const val KEY_RAW_VF_DEBUG_OVERLAY = "raw_vf_debug_overlay"
        const val KEY_VF_ENGINE_MODE = "vf_engine_mode"
        const val KEY_GRID = "viewfinder_grid"
        const val KEY_HISTOGRAM = "viewfinder_histogram"
        const val KEY_LEVEL = "viewfinder_level"
        const val KEY_SCOPE_MODE = "scope_mode"
        const val KEY_HISTOGRAM_LOG = "histogram_log_scale"
        const val KEY_FOCUS_PEAKING = "focus_peaking"
        const val KEY_FOCUS_PEAKING_COLOR = "focus_peaking_color"
        const val KEY_OIS = "optical_image_stabilization"
        const val KEY_RAW_ZSL = "raw_zero_shutter_lag"
        const val KEY_RAW_ZSL_FRAME_COUNT = "raw_zsl_frame_count"
        // Removed burst fusion: stale per-lens keys are migrated off once
        // (see onCreate) and never written again.
        const val KEY_BURST_FUSION_ENABLED = "burst_fusion_enabled"
        const val KEY_BURST_FUSION_TAU = "burst_fusion_tau"
        const val KEY_BURST_FUSION_KEEP_SOURCE_BURST = "burst_fusion_keep_source_burst"
        // Future SR hook: KEY_RAW_SR_ENABLED / KEY_RAW_SR_DNG_MODE here.
        const val KEY_ZSL_HYBRID_TOPUP = "zsl_hybrid_topup"
        const val KEY_DYNAMIC_EXPOSURE = "dynamic_exposure"
        const val KEY_DYNAMIC_EXPOSURE_BALANCE = "dynamic_exposure_balance"
        const val KEY_DYNAMIC_EXPOSURE_ISO_LIMIT = "dynamic_exposure_iso_limit"
        const val KEY_DYNAMIC_EXPOSURE_SHUTTER_LIMIT = "dynamic_exposure_shutter_limit"
        const val KEY_DYNAMIC_EXPOSURE_AUTO_SHUTTER = "dynamic_exposure_auto_shutter"
        const val KEY_ETTR_ENABLED = "ettr_enabled"
        const val KEY_FACE_PRIORITY = "face_priority"
        const val KEY_ETTR_HEADROOM_EV = "ettr_headroom_ev"
        const val KEY_ETTR_ISO_LIMIT = "ettr_iso_limit"
        const val KEY_CAPTURE_EXPOSURE_MODE = "capture_exposure_mode"
        const val KEY_CAPTURE_FORMAT = "capture_format"
        const val KEY_VF_PREVIEW_MODE = "vf_preview_mode"
        const val KEY_VF_RESOLUTION = "vf_resolution"
        const val KEY_RAW_STREAM_COMPAT_MODE = "raw_stream_compat_mode"
        const val KEY_DNG_WRITER_BACKEND = "dng_writer_backend"
        const val KEY_DNG_COMPRESSION = "dng_compression"
        const val KEY_BURST_RELEASE = "burst_release"
        const val KEY_BURST_CRS_ENABLED = "burst_crs_enabled"
        const val KEY_RELEASE_MODE = "release_mode"
        const val KEY_HDR_ENABLED = "hdr_enabled"
        const val KEY_HDR_SAVE_EACH_BRACKET = "hdr_save_each_bracket"
        const val KEY_HDR_SAVE_DEBUG_FRAMES = "hdr_save_debug_frames"
        const val KEY_HDR_BRACKET_STOPS = "hdr_bracket_stops"
        const val KEY_SEQ_TRIO_ENABLED = "seq_trio_enabled"
        const val KEY_JPEG_ULTRA_HDR = "jpeg_ultra_hdr"
        const val KEY_JPEG_DISPLAY_P3 = "jpeg_display_p3"
        const val KEY_JPEG_QUALITY = "jpeg_quality"
        const val KEY_JPEG_CHROMA_SUBSAMPLING = "jpeg_chroma_subsampling"
        const val KEY_JPEG_DEMOSAIC = "jpeg_demosaic"
        const val KEY_JPEG_AGX_PURITY = "jpeg_agx_purity"
        const val KEY_JPEG_AGX_CONTRAST = "jpeg_agx_contrast"
        const val KEY_JPEG_AGX_SATURATION = "jpeg_agx_saturation"
        const val KEY_JPEG_AGX_HUE = "jpeg_agx_hue"
        const val KEY_JPEG_AGX_SHADOW_EV = "jpeg_agx_shadow_ev"
        const val KEY_JPEG_AGX_HIGHLIGHT_EV = "jpeg_agx_highlight_ev"
        const val KEY_JPEG_AGX_GAMUT = "jpeg_agx_gamut"
        const val KEY_JPEG_ADAPTIVE_EXPOSURE = "jpeg_adaptive_exposure"
        const val KEY_JPEG_ADAPTIVE_PROGRAM = "jpeg_adaptive_program"
        const val KEY_JPEG_HIGHLIGHT_HEADROOM = "jpeg_highlight_headroom"
        const val KEY_JPEG_SKY_PROTECTION = "jpeg_sky_protection"
        const val KEY_JPEG_HIGHLIGHT_SHOULDER = "jpeg_highlight_shoulder"
        const val KEY_JPEG_HL_RECONSTRUCTION = "jpeg_hl_reconstruction"
        const val KEY_JXL_BIT_DEPTH = "jxl_bit_depth"
        const val KEY_JXL_PIXEL_TYPE = "jxl_pixel_type"
        const val KEY_JXL_QUALITY = "jxl_quality"
        const val KEY_JXL_EFFORT = "jxl_effort"
        const val KEY_JXL_DECODING_SPEED = "jxl_decoding_speed"
        const val KEY_JXL_DISPLAY_P3 = "jxl_display_p3"
        const val KEY_JXL_HDR_TRANSFER = "jxl_hdr_transfer"
        const val KEY_DEVELOPED_FORMAT = "developed_format"
        const val LEGACY_KEY_JPEG_ADAPTIVE_PHOTO = "jpeg_adaptive_photo"
        const val KEY_AI_DENOISE_ENABLED = "ai_denoise_enabled"
        const val KEY_SAVE_ORIGINAL_DNG = "save_original_dng"
        const val KEY_AI_STRENGTH_PCT = "ai_denoise_strength_pct"
        const val KEY_GALOSH_ENABLED = "galosh_enabled"
        const val KEY_WAVELET_ENABLED = "wavelet_enabled"
        const val KEY_WAVELET_STRENGTH_PCT = "wavelet_strength_pct"
        const val KEY_WAVELET_LUMA_PCT = "wavelet_luma_pct"
        const val KEY_WAVELET_SCALES = "wavelet_scales"
        const val KEY_GALOSH_SAVE_ORIGINAL_DNG = "galosh_save_original_dng"
        const val KEY_GALOSH_STRENGTH_PCT = "galosh_strength_pct"
        const val KEY_GALOSH_LUMA_PCT = "galosh_luma_pct"
        const val KEY_GALOSH_CHROMA_PCT = "galosh_chroma_pct"
        const val KEY_GALOSH_FAST_UPSAMPLE = "galosh_fast_upsample"
        const val KEY_GALOSH_FAST_MODE = "galosh_fast_mode"
        const val KEY_AE_METERING_MODE = "ae_metering_mode"
        const val SLIDER_STEPS = 10_000
        const val SLIDER_UPDATE_DELAY_MS = 32L
        const val MANUAL_PANEL_TIMEOUT_MS = 4_000L
        const val HISTOGRAM_INTERVAL_MS = 700L
        /** Mirrors HistogramView RAW-hold: YUV resumes once the view stops preferring RAW. */
        const val HISTOGRAM_RAW_HOLD_MS = 2_000L
        const val MIN_RAW_ZSL_FRAMES = 1
        const val MAX_RAW_ZSL_FRAMES = 59
        const val DEFAULT_RAW_ZSL_FRAME_COUNT = 8
    }
}
