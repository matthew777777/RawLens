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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import android.os.Build
import android.os.SystemClock

class MainActivity : Activity() {
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
    private lateinit var lensVerdictStore: LensVerdictStore
    private lateinit var rawSizePreferenceStore: RawSizePreferenceStore
    private lateinit var rawFormatPreferenceStore: RawFormatPreferenceStore
    private lateinit var dngMetadataOverrideStore: DngMetadataOverrideStore
    private lateinit var programAeProfileStore: ProgramAeProfileStore
    private lateinit var vendorKeyStore: VendorKeyStore
    private var gpsProvider: GpsLocationProvider? = null
    private lateinit var manualPanel: LinearLayout
    private lateinit var manualSlider: RuleSliderView
    private lateinit var manualAuto: Button
    private lateinit var manualLimits: Button
    private var activeManualControl: ManualControl? = null
    /** PROGRAM axis lock slider reuses the bottom ruler; null when the ruler drives MANUAL. */
    private var activeProgramAxis: ManualControl? = null
    private var pendingSliderUpdate: Runnable? = null
    private var manualPanelHide: Runnable? = null
    private lateinit var debugOverlay: TextView
    private lateinit var rawVfDebugOverlay: TextView
    private lateinit var guideOverlay: CameraGuideOverlay
    private lateinit var histogramView: HistogramView
    private lateinit var waveformView: WaveformView
    private lateinit var meteringOverlay: FocusMeteringOverlay
    private lateinit var focusPeakingOverlay: FocusPeakingOverlayView
    private lateinit var quickPanel: LinearLayout
    private lateinit var gridQuick: TextView
    private lateinit var histogramQuick: TextView
    private lateinit var levelQuick: TextView
    private lateinit var aeMeteringQuick: TextView
    private lateinit var hdrQuick: TextView
    private lateinit var timerQuick: TextView
    private lateinit var releaseQuick: TextView
    // Future SR hook: rawSrQuick tile field here.
    private lateinit var hdrPlusQuick: TextView
    private lateinit var ettrQuick: TextView
    private var dualRawEnabled = false // Experimental, deliberately session-only.
    private var lastLensSwitcherSignature: String? = null
    private lateinit var timerBadge: TextView
    private lateinit var modeButton: TextView
    private lateinit var flashButton: ImageButton
    private lateinit var rawBadge: TextView
    private lateinit var rawStatusGroup: View
    private var captureFormat = CaptureFormat.DNG_ONLY
    private var developedFormat = DevelopedFormat.JPEG
    private var vfPreviewMode = VfPreviewMode.FOLLOW
    private var vfResolution = VfResolution.MAX
    private var vfPreviewButton: TextView? = null
    private var rawZslStatus = RawZslStatus(RawZslState.OFF, "Disabled in settings")
    private var rawZslSettingsStatus: TextView? = null
    private var gridEnabled = true
    private var histogramEnabled = true
    private var levelEnabled = false
    private lateinit var levelOverlay: LevelOverlayView
    private var levelTracker: LevelTracker? = null
    /** Focus-peaking master switch (default on); the overlay auto-shows on manual/tap focus. */
    private var focusPeakingEnabled = true
    private var focusPeakingColor = FocusPeakingColor.GREEN
    /** Histogram vs waveform parade, persisted. Tapping the scope toggles it. */
    private var scopeMode = ScopeMode.HISTOGRAM
    // True once a RAW_SENSOR scope frame arrived. Freshness is reported separately.
    private var rawScopeLive = false
    private var lastRawScopeMs = Long.MIN_VALUE
    /** Scope data source is automatic: AgX-curved JPEG preview with a JPEG format,
     * linear RAW with DNG only. */
    private fun scopeAgxApplied(): Boolean = captureFormat.includesJpeg
    private var aeMeteringMode = AeMeteringMode.AUTO
    private var timerSeconds = 0
    private var releaseMode = 0 // 0 single, 1 burst
    private var hdrEnabled = false
    private var captureExposureMode = CaptureExposureMode.AUTO
    private var hdrPlusSettings = HdrPlusSettings()
    private var mfsrSettings = MfsrSettings()
    private lateinit var mfsrQuick: TextView
    private var modeBeforeMfsr: CaptureExposureMode? = null
    /** Offline-merge switch (Settings → Burst): when on, the shutter dumps the ZSL ring to _BURST.crs. */
    private var burstCrsEnabled = false
    private var modeBeforeBurst: CaptureExposureMode? = null
    private val burstDumpInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private var programHintShown = false
    private var sidecarSettingsStatus: TextView? = null
    private var countdownRunnable: Runnable? = null
    private var scopeRunnable: Runnable? = null
    private val quickTileStates = java.util.IdentityHashMap<TextView, Boolean>()
    private lateinit var orientationListener: OrientationEventListener
    private var activityResumed = false
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
    private var controlRotationDegrees = 0f
    // RAW Video mode (orthogonal to the stills CaptureExposureMode enums so no
    // stills state machine is touched): the stills controller keeps framing
    // until REC, when the standalone RawVideoRecorder takes the camera.
    private var isVideoMode = false
    private var lastPhotoExposureMode = CaptureExposureMode.AUTO
    /** Photo mode before HDR+ pulled the camera into ZSL; restored on toggle-off. */
    private var modeBeforeHdrPlus: CaptureExposureMode? = null
    private var videoRecorder: RawVideoRecorder? = null
    private var videoRecording = false
    private var videoOutputFile: File? = null
    /** Direct-Log take state. Only one of [videoRecorder]/[logRecorder] is ever active. */
    private var logRecorder: DirectLogRecorder? = null
    /**
     * Last closed Direct-Log take's stats (null while rolling): lets
     * device tests assert take health (drops, over-budget frames,
     * shading) without parsing logcat. Written on the UI thread.
     */
    var lastDirectLogStats: DirectLogRecorder.Stats? = null
        private set
    private var directLogEnabled = false
    /** True when the shared folder picker was opened to rescue staged video. */
    private var folderPickerForVideo = false
    /** Settings JSON awaiting the export picker result; cleared once written or cancelled. */
    private var pendingSettingsJson: String? = null
    /** Update consent, release checks, download and install ([UpdateFlow]). */
    private val updateFlow = UpdateFlow(this, UPDATE_INSTALL_SOURCES) { setStatus(it) }
    private var videoCrop = VideoCrop.OPEN_GATE
    private var videoStartPending = false
    private var videoDebugRunnable: Runnable? = null
    private lateinit var videoDebugOverlay: TextView
    /** Peak-held mic level feeding the stats-box audio line (HUD tick decays it). */
    private var videoAudioPeak = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        timerBadge = findViewById(R.id.timerBadge)
        modeButton = findViewById(R.id.modeButton)
        flashButton = findViewById(R.id.flashButton)
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
                    dngInfo.text = dng.replace('\n', ' ').replace("-bit RAW", "-BIT")
                    sensorInfo.text = sensor.replace('\n', ' ')
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
                    flashButton.isEnabled = flashEnabled
                    flashButton.alpha = if (!flashEnabled) 0.35f else if (flash.endsWith("ON")) 1f else 0.72f
                    flashButton.setColorFilter(getColor(if (flash.endsWith("ON")) R.color.accent else R.color.text_primary))
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
        flashButton.setOnClickListener {
            closeFloatingPanels()
            controller.toggleTorch()
        }
        findViewById<View>(R.id.timerButton).setOnClickListener { cycleTimer() }
        rawStatusGroup.setOnClickListener { cycleCaptureFormat() }
        modeButton.setOnClickListener { cycleCaptureExposureMode() }
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
            showSettings()
        }
        // Video-mode settings entry: identical dialog, but always lands on
        // the Video tab. The photo entry above keeps the default tab.
        findViewById<View>(R.id.videoSettingsButton).setOnClickListener {
            closeFloatingPanels()
            showSettings(SETTINGS_VIDEO_TAB)
        }
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
        // Photo 3:4 box seats between the islands: an island or panel size
        // change (first layout, font scale, panel growth) re-seats the box.
        // Size-guarded, so settled layouts never recurse.
        findViewById<View>(R.id.topBar).addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (!isVideoMode && (r - l != orr - ol || b - t != ob - ot)) {
                positionViewfinderOverlays()
            }
        }
        findViewById<View>(R.id.controlPanel).addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (!isVideoMode && (r - l != orr - ol || b - t != ob - ot)) {
                positionViewfinderOverlays()
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
            val json = pendingSettingsJson
            pendingSettingsJson = null
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null && json != null) {
                try {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    } ?: throw IOException("cannot open export file")
                    setStatus("SETTINGS EXPORTED")
                } catch (_: Exception) {
                    setStatus("SETTINGS EXPORT FAILED")
                }
            } else if (resultCode == RESULT_OK) {
                setStatus("SETTINGS EXPORT FAILED")
            }
            return
        } else if (requestCode == SETTINGS_IMPORT_REQUEST) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                try {
                    val count = applySettingsImport(SettingsBackup.fromJson(readSettingsImport(uri)))
                    promptSettingsRestart(count)
                } catch (_: Exception) {
                    setStatus("SETTINGS IMPORT FAILED")
                }
            }
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

    private fun sidecarFolderText(): String {
        val tree = SidecarTreeAccess.savedTreeUri(this)
        return if (tree != null && SidecarTreeAccess.hasWriteAccess(this, tree)) {
            "Gyro sidecars: same folder as DNGs " +
                "(${(SidecarTreeAccess.displayPath(tree) ?: "granted folder")}/<burst>)"
        } else {
            "Gyro sidecars: Download/RawLens/<burst> " +
                "(grant the photo folder to save next to DNGs)"
        }
    }

    private fun pickSidecarFolder() {
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

    private fun exportSettings() {
        val json = try {
            SettingsBackup.toJson(SettingsBackup.KNOWN_STORES.associateWith { name ->
                getSharedPreferences(name, Context.MODE_PRIVATE).all
            })
        } catch (_: IllegalArgumentException) {
            setStatus("SETTINGS EXPORT FAILED")
            return
        }
        pendingSettingsJson = json
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "rawlens-settings-$stamp.json")
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, SETTINGS_EXPORT_REQUEST)
        } catch (_: Exception) {
            pendingSettingsJson = null
            setStatus("FILE PICKER UNAVAILABLE")
        }
    }

    private fun importSettings() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain"))
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, SETTINGS_IMPORT_REQUEST)
        } catch (_: Exception) {
            setStatus("FILE PICKER UNAVAILABLE")
        }
    }

    @Throws(IOException::class)
    private fun readSettingsImport(uri: Uri): String {
        contentResolver.openInputStream(uri)?.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val read = stream.read(buf)
                if (read < 0) break
                total += read
                if (total > MAX_SETTINGS_IMPORT_BYTES) throw IOException("settings file too large")
                out.write(buf, 0, read)
            }
            return out.toString(Charsets.UTF_8.name())
        } ?: throw IOException("cannot open settings file")
    }

    /**
     * Replaces every known store with the backup contents. Synchronous commit:
     * the restart prompt must never outrun the write. Returns the restored
     * value count. Names come from [SettingsBackup], never the file, so no
     * crafted backup can touch another preferences file.
     */
    private fun applySettingsImport(stores: Map<String, Map<String, Any>>): Int {
        var count = 0
        for ((name, entries) in stores) {
            if (name !in SettingsBackup.KNOWN_STORES) continue
            val editor = getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for ((key, value) in entries) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        editor.putStringSet(key, value as Set<String>)
                    }
                    else -> throw IllegalArgumentException("cannot restore \"$key\"")
                }
                count++
            }
            if (!editor.commit()) throw IOException("cannot save settings")
        }
        return count
    }

    private fun promptSettingsRestart(count: Int) {
        AlertDialog.Builder(this)
            .setTitle("Settings imported")
            .setMessage("$count values restored. Restart to apply them?")
            .setPositiveButton("Restart") { _, _ -> recreate() }
            .setNegativeButton("Later") { _, _ -> setStatus("SETTINGS IMPORTED • RESTART TO APPLY") }
            .setCancelable(false)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Dialogs/pickers shown while paused (permissions, folder grant, lens
        // discovery alerts) pop the system bars back; hide them again every
        // time we come back so first install is fullscreen without a restart.
        ImmersiveMode.apply(window)
        activityResumed = true
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
        val videoFullBleed = isVideoMode || topBar.visibility != View.VISIBLE
        val viewfinderParams = (viewfinder.layoutParams as FrameLayout.LayoutParams).apply {
            if (videoFullBleed) {
                // RAW video / Direct-Log: full-bleed viewport, only the
                // bottom island reserves space. The panel keeps its photo
                // structure exactly (chips + info line + fixed shutter
                // rows): minimumHeight is left at the grown photo value so
                // the panel never changes size across the mode switch and
                // no button jumps.
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = (root.height - panelHeight + sealOverlap).coerceAtLeast(0)
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = 0
            } else {
                // Photo modes: exact 3:4 viewport between the islands so the
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
        // Slim focal stack: right edge opposite the histogram, riding the
        // slider lift. Opposite sides never collide, so the scope seat stays
        // untouched.
        lensSwitcher.layoutParams = FrameLayout.LayoutParams(wrapContent(), wrapContent(),
            Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = dp(16)
            bottomMargin = panelHeight + dp(16) + floatingLiftPx()
        }
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

    private fun updateOverlayStack() {
        if (!::histogramView.isInitialized || !::waveformView.isInitialized ||
            !::manualPanel.isInitialized || !::quickPanel.isInitialized) return
        val panelHeight = findViewById<View>(R.id.controlPanel).height.takeIf { it > 0 } ?: dp(232)
        val lift = floatingLiftPx()
        scopeViews().forEach { scope ->
            (scope.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                // Video landscape: the stats strip owns the user-top edge, so
                // the scope yields to the opposite edge (90° only — at 270°
                // the strip is already opposite and the scope never sits
                // under it). Photo mode always keeps START.
                val yieldEnd = isVideoMode && deviceOrientationDegrees == 90
                params.gravity = Gravity.BOTTOM or (if (yieldEnd) Gravity.END else Gravity.START)
                params.marginStart = if (yieldEnd) 0 else dp(16)
                params.marginEnd = if (yieldEnd) dp(16) else 0
                params.bottomMargin = panelHeight + dp(16) + lift
                scope.layoutParams = params
            }
        }
        // The focal stack seat lives in positionViewfinderOverlays; the scope
        // stacks above it via scopeBaseMargin.
    }

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
            if (animate) view.animate().rotation(controlRotationDegrees).setDuration(180L).start()
            else view.rotation = controlRotationDegrees
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
    private fun seatScopeSlot(deferred: Boolean = false) {
        updateOverlayStack()
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
        scopeViews().forEach { view ->
            (view.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
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
        findViewById(R.id.settingsButton), findViewById(R.id.flashButton),
        findViewById(R.id.timerButton), findViewById(R.id.timerBadge),
        findViewById(R.id.isoControl),
        findViewById(R.id.shutterControl), findViewById(R.id.wbControl),
        findViewById(R.id.focusControl), findViewById(R.id.evControl),
        // dngInfo/sensorInfo intentionally fixed: wide thin labels (0dp weight=1,
        // singleLine) cannot fit 90-degree canvas-rotated text in their bounds and
        // would clip to invisible. Keep horizontal so they stay visible above panel.
        // flashControl is GONE (torch lives on the top bar) so it never rotates.
        findViewById(R.id.quickButton), findViewById(R.id.modeButton),
        findViewById(R.id.vfPreviewButton),
        findViewById(R.id.quickPanelTitle), findViewById(R.id.quickPanelHint),
        findViewById(R.id.gridQuick),
        findViewById(R.id.histogramQuick), findViewById(R.id.levelQuick),
        findViewById(R.id.aeMeteringQuick),
        findViewById(R.id.timerQuick), findViewById(R.id.releaseQuick),
        findViewById(R.id.resetTargetsQuick), findViewById(R.id.hdrQuick),
        findViewById(R.id.ettrQuick),
        findViewById(R.id.manualLimitsButton),
        findViewById(R.id.manualControlSlider),
        findViewById(R.id.manualAutoButton),
        findViewById(R.id.videoSettingsButton)
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

    /** Wide thin status labels stay fixed horizontal in their bars (topBar,
     * info row) so rotated text can never clip them to invisible. */
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
    private fun setStatus(message: String) {
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
    private fun setBurstCrsEnabled(enabled: Boolean) {
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
    private fun openMerges() {
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
        // Cinema chrome: photo top bar and lens pill hide for full-bleed
        // framing; the capture panel (red dot / shutter / switcher) stays so
        // transport and mode exit keep working. Exposure chips (SS/ISO/WB/
        // focus/EV) stay visible with the same live photo-tile settings.
        findViewById<View>(R.id.topBar)?.visibility = View.GONE
        findViewById<View>(R.id.controlPanel)?.post { positionViewfinderOverlays() }
        findViewById<View>(R.id.exposureControls)?.visibility = View.VISIBLE
        findViewById<View>(R.id.lensSwitcher)?.visibility = View.GONE
        findViewById<View>(R.id.videoSettingsButton)?.visibility = View.VISIBLE
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
        findViewById<View>(R.id.videoSettingsButton)?.visibility = View.GONE
        videoDebugOverlay.visibility = View.GONE
        videoDebugRunnable?.let { videoDebugOverlay.removeCallbacks(it) }
        videoDebugRunnable = null
        videoAudioPeak = 0f
        findViewById<View>(R.id.topBar)?.visibility = View.VISIBLE
        findViewById<View>(R.id.controlPanel)?.post { positionViewfinderOverlays() }
        findViewById<View>(R.id.exposureControls)?.visibility = View.VISIBLE
        findViewById<View>(R.id.lensSwitcher)?.visibility = View.VISIBLE
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
        try {
            recorder.start(file, videoContainerMeta(manager, cameraId, size))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "video start failed: ${e.message}")
            setStatus("REC FAILED")
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
    private fun refreshVideoAudioDescription() {
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
    private fun directLogGainPref(): Float =
        lensPreferences().getFloat(KEY_DIRECT_LOG_GAIN, 0f).coerceIn(-2f, 4f)

    /** Persisted Direct-Log frame rate: 24 or 30, 30 out of the box. Any
     * other stored value heals to 30. Applies to the next take (encoder
     * format + AE range are fixed at take start). */
    private fun directLogFpsPref(): Int =
        if (lensPreferences().getInt(KEY_DIRECT_LOG_FPS, LogVideoProbe.FPS) == 24) 24 else 30

    private fun cycleDirectLogFps() {
        val next = if (directLogFpsPref() == 24) 30 else 24
        lensPreferences().edit().putInt(KEY_DIRECT_LOG_FPS, next).apply()
        refreshVideoAudioDescription()
        refreshVideoSensorInfo()
        setStatus("LOG ${next}FPS — NEXT TAKE")
    }

    /** Persisted Direct-Log encode size: 1080p out of the box (RAWR parity:
     * the widest-supported rung), 1440p/4K opt-in. Applies to the next take. */
    private fun directLogResPref(): LogVideoProbe.EncodeSize =
        LogVideoProbe.encodeSizeForRes(lensPreferences().getString(KEY_DIRECT_LOG_RES, null))

    private fun cycleDirectLogRes() {
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
    private fun directLogProfile(): DirectLogProfile =
        DirectLogProfile.fromName(lensPreferences().getString(KEY_DIRECT_LOG_PROFILE, null))

    /** Persisted stab output mode: OG only out of the box. Applies to the next take. */
    private fun stabOutputMode(): StabOutputMode =
        StabOutputMode.fromName(lensPreferences().getString(KEY_VIDEO_STAB_OUTPUT, null))

    /** Persisted Direct-Log demosaic backend: MHC out of the box. Applies to the next take. */
    private fun videoDemosaic(): VideoDemosaic =
        VideoDemosaic.fromPreference(lensPreferences().getString(KEY_VIDEO_DEMOSAIC, null))

    /** LOG-mode badge: mode + live output profile (e.g. `LOG • SLOG3`). */
    private fun logBadgeLabel(recording: Boolean = false): String =
        (if (recording) "● " else "") + "LOG • ${directLogProfile().hudLabel}"

    private fun cycleDirectLogProfile() {
        if (videoRecording) return // encoder format is fixed at take start
        val next = directLogProfile().next()
        lensPreferences().edit().putString(KEY_DIRECT_LOG_PROFILE, next.name).apply()
        rawBadge.text = logBadgeLabel()
        refreshVideoAudioDescription()
        refreshVideoSensorInfo()
        updateQuickControls()
        setStatus("LOG ${next.hudLabel} — NEXT TAKE")
    }

    private fun formatDirectLogGain(ev: Float): String =
        "×%.1f".format(Locale.US, Math.pow(2.0, ev.toDouble()))

    private fun logGainText(ev: Float): String = "LOG gain: ${formatDirectLogGain(ev)}"

    private fun videoSoundOn(): Boolean =
        lensPreferences().getBoolean(KEY_VIDEO_AUDIO, true) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun toggleVideoSound() {
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

    private fun videoCropShort(): String = when (videoCrop) {
        VideoCrop.OPEN_GATE -> "GATE"
        VideoCrop.WIDE_16_9 -> "16:9"
        VideoCrop.SCOPE_2_39 -> "2.39"
        VideoCrop.UNIVISIUM_2_00 -> "2.00"
    }

    private fun cycleVideoCrop() {
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

    private fun refreshVideoSensorInfo() {
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

    private fun videoCameraInfo(
        manager: android.hardware.camera2.CameraManager
    ): Pair<String, android.util.Size>? {
        for (id in manager.cameraIdList) {
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
        findViewById<View>(R.id.timerButton).performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
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

    private fun preloadFlowNetForMergedHdr() {
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

    private fun applyHdrPlusSettings(settings: HdrPlusSettings): Boolean {
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

    private fun hdrPlusQuickText(): String {
        val available = !dualRawEnabled && rawZslStatus.state != RawZslState.FALLBACK
        return hdrPlusSettings.quickText(
            lensPreferences().getInt(KEY_RAW_ZSL_FRAME_COUNT, DEFAULT_RAW_ZSL_FRAME_COUNT),
            rawZslStatus.bufferedFrames, available, rawZslStatus.hdrPlusBusy
        )
    }

    private fun saveEttrSettings(settings: EttrSettings) {
        lensPreferences().edit()
            .putBoolean(KEY_ETTR_ENABLED, settings.enabled)
            .putFloat(KEY_ETTR_HEADROOM_EV, settings.headroomEv)
            .putInt(KEY_ETTR_ISO_LIMIT, settings.isoLimit)
            .apply()
        controller.setEttrSettings(settings)
        updateQuickControls()
    }

    private fun ettrHeadroomText(headroomEv: Float): String =
        String.format(java.util.Locale.US, "Highlight headroom: -%.1f EV", headroomEv)

    private fun ettrIsoLimitText(isoLimit: Int): String =
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
    private fun cycleVfPreviewMode() {
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

    private fun cycleVfResolution() {
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

    private fun refreshCaptureFormatControl() {
        // Same authority rule as the info labels: in VIDEO mode the badge
        // stays CRS/●REC no matter what the stills state machine reports.
        if (isVideoMode) {
            rawBadge.text = if (videoRecording) "● REC" else "CRS"
            rawBadge.setTextColor(getColor(R.color.danger))
            return
        }
        rawBadge.text = captureBadgeLabel()
        rawBadge.setTextColor(getColor(R.color.accent))
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

    private fun applyCaptureExposureMode(mode: CaptureExposureMode) {
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

    /**
     * Spec chip contract: tap ISO/SHUTTER for that axis's slider. Moving the slider
     * engages the axis lock; the slider's Auto button releases both locks.
     * Uses the same bottom transparent ruler as MANUAL (tick-only; values in chips).
     */
    private fun showProgramAxisSlider(isIso: Boolean) {
        hideQuickControls()
        // While rolling the take request owns the sensor: PROGRAM editor needs preview.
        if (videoRecording) {
            showManualControl(if (isIso) ManualControl.ISO else ManualControl.SHUTTER)
            return
        }
        if (!controller.hasManualSensorControl()) {
            setStatus("ANDROID AE • NO MANUAL SENSOR")
            return
        }
        val axis = if (isIso) ManualControl.ISO else ManualControl.SHUTTER
        val range = manualRangeFor(axis)
        if (range == null) {
            setStatus("${if (isIso) "ISO" else "S"} N/A")
            return
        }
        if (activeProgramAxis == axis && manualPanel.visibility == View.VISIBLE) {
            hideManualControl()
            return
        }
        val profile = programProfile()
        val current = if (isIso) {
            (profile.lockedIso.takeIf { it > 0 }?.toLong() ?: range.current)
                .coerceIn(range.minimum, range.maximum)
        } else {
            (profile.lockedShutterNanos.takeIf { it > 0L } ?: range.current)
                .coerceIn(range.minimum, range.maximum)
        }
        activeManualControl = null
        activeProgramAxis = axis
        // No text label under the histogram: the ruler tick code (ISO/S) plus the
        // exposure chips carry the values. LIMITS opens the full PROGRAM editor.
        manualLimits.visibility = View.VISIBLE
        manualLimits.contentDescription = if (isIso) {
            "ISO lock limits and metering editor."
        } else {
            "Shutter lock limits and metering editor."
        }
        manualSlider.label = shortControlName(axis)
        manualSlider.max = SLIDER_STEPS
        manualSlider.setProgressFromUser(sliderProgress(axis, range.copy(current = current)), fromUser = false)
        manualAuto.text = "AUTO"
        manualAuto.isEnabled = true
        refreshRuleSliderAccessibility()
        manualPanel.visibility = View.VISIBLE
        if (!isVideoMode) lensSwitcher.visibility = View.INVISIBLE
        updateOverlayStack()
        manualPanel.post { updateOverlayStack() }
        scheduleManualPanelHide()
    }

    /** Live-scrub a PROGRAM axis lock: engage the lock immediately, persist on release. */
    private fun onProgramAxisScrubbed(axis: ManualControl, progress: Int) {
        val range = manualRangeFor(axis) ?: return
        val value = sliderValue(axis, range, progress)
        val current = programProfile()
        val next = when (axis) {
            ManualControl.ISO -> current.copy(
                lockMode = ProgramLockMode.ISO_LOCK, lockedIso = value.toInt()
            )
            else -> current.copy(
                lockMode = ProgramLockMode.SHUTTER_LOCK, lockedShutterNanos = value
            )
        }
        saveProgramProfile(next)
        manualAuto.isEnabled = true
    }

    private fun commitProgramAxis(axis: ManualControl, progress: Int) {
        onProgramAxisScrubbed(axis, progress)
        updateProgramChipStates()
        setStatus(
            if (axis == ManualControl.ISO) "ISO LOCK • SHUTTER AUTO"
            else "SHUTTER LOCK • ISO AUTO"
        )
    }

    private fun updateProgramChipStates() {
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

    private fun openManualModeThen(control: ManualControl) {
        // While rolling the stills mode switch would only affect the next preview;
        // the take request already owns the sensor, so open the tile directly.
        if (videoRecording) {
            isoControl.post { showManualControl(control) }
            return
        }
        if (captureExposureMode != CaptureExposureMode.MANUAL) applyCaptureExposureMode(CaptureExposureMode.MANUAL)
        isoControl.post { showManualControl(control) }
    }

    private fun showProgramPanelHintOnce() {
        if (programHintShown) return
        programHintShown = true
        val profile = programProfile()
        setStatus("P ${programLockText(profile.lockMode).substringAfter("Lock: ")} " +
            "${String.format(Locale.US, "%.2f", profile.balance)} " +
            "${programEvBiasText(profile.evBias)} • ISO/S edits")
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

    private fun programBalanceText(balance: Float): String = when {
        balance > 0.55f -> "Priority: ${String.format(Locale.US, "%.2f", balance)} • faster shutter"
        balance < 0.45f -> "Priority: ${String.format(Locale.US, "%.2f", balance)} • lower ISO"
        else -> "Priority: ${String.format(Locale.US, "%.2f", balance)} • balanced"
    }

    private fun programIsoBoundText(value: Int, isMin: Boolean): String =
        (if (isMin) "ISO min: " else "ISO max: ") +
            (value.takeIf { it > 0 }?.toString() ?: if (isMin) "SENSOR MIN" else "SENSOR MAX")

    private fun programShutterBoundText(value: Long, isMin: Boolean, autoSafe: Boolean): String =
        (if (isMin) "Shutter min: " else "Shutter max: ") + when {
            value > 0L -> formatShutter(value)
            !isMin && autoSafe -> "AUTO HANDHELD"
            isMin -> "SENSOR MIN"
            else -> "SENSOR MAX"
        }

    private fun programLockText(mode: ProgramLockMode): String = when (mode) {
        ProgramLockMode.NONE -> "Lock: NONE (both auto)"
        ProgramLockMode.ISO_LOCK -> "Lock: ISO (shutter auto)"
        ProgramLockMode.SHUTTER_LOCK -> "Lock: SHUTTER (ISO auto)"
    }

    /**
     * Seeds a freshly engaged lock from the live sensor values so it holds a real
     * exposure instead of 0 (which would make the "locked" axis follow the scene).
     */
    private fun seedLockedValuesFor(profile: ProgramAeProfile): ProgramAeProfile {
        var seeded = profile
        if (profile.lockMode == ProgramLockMode.ISO_LOCK && profile.lockedIso <= 0) {
            val range = controller.manualControlRange(ManualControl.ISO)
            seeded = seeded.copy(lockedIso = (range?.current?.toInt() ?: 0))
        }
        if (profile.lockMode == ProgramLockMode.SHUTTER_LOCK && profile.lockedShutterNanos <= 0L) {
            val range = controller.manualControlRange(ManualControl.SHUTTER)
            seeded = seeded.copy(lockedShutterNanos = range?.current ?: 0L)
        }
        return seeded
    }

    private fun programMeteringLabel(metering: ProgramMetering): String = when (metering) {
        ProgramMetering.CENTER_WEIGHTED -> "CENTER"
        ProgramMetering.AVERAGE -> "AVERAGE"
        ProgramMetering.SPOT -> "SPOT"
    }

    private fun nextProgramMetering(metering: ProgramMetering): ProgramMetering = when (metering) {
        ProgramMetering.CENTER_WEIGHTED -> ProgramMetering.AVERAGE
        ProgramMetering.AVERAGE -> ProgramMetering.SPOT
        ProgramMetering.SPOT -> ProgramMetering.CENTER_WEIGHTED
    }

    private fun programEvBiasText(bias: Float): String =
        String.format(Locale.US, "Brightness bias: %+.1f EV", bias)

    /** Viewfinder PROGRAM editor: priority slider, locks, per-lens min/max, brightness bias. */
    private fun showProgramPanel() {
        closeFloatingPanels()
        var profile = programProfile()
        fun apply() = saveProgramProfile(profile)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val lensLabel = TextView(this).apply {
            text = "PROGRAM • per-lens ${activeProfileLensId() ?: "default"} • RAW-driven (sensor ISO + shutter)"
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
        }
        container.addView(lensLabel)
        if (!controller.hasManualSensorControl()) {
            container.addView(TextView(this).apply {
                text = "Android AE • this camera has no manual sensor control; " +
                    "locks and limits are unavailable."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, dp(8), 0, 0)
            })
        }
        container.addView(TextView(this).apply {
            text = "Hold ISO / SHUTTER chips to lock • tap for slider • Auto releases both • " +
                "lock both for manual"
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
            setPadding(0, dp(4), 0, 0)
        })
        fun styleDialogButton(button: Button, active: Boolean = false) {
            button.background = getDrawable(
                if (active) R.drawable.control_chip_active else R.drawable.control_chip
            )
            button.setTextColor(
                getColor(if (active) R.color.accent_dark else R.color.text_primary)
            )
            button.textSize = 12f
            button.setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val balanceLabel = TextView(this).apply {
            text = programBalanceText(profile.balance)
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(12), 0, 0)
        }
        container.addView(balanceLabel)
        container.addView(TextView(this).apply {
            text = "← ISO priority   |   balanced   |   shutter priority →"
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
        })
        val balanceSlider = RuleSliderView(this).apply {
            label = "BAL"
            contentRotation = controlRotationDegrees
            max = 100
            setProgressFromUser((profile.balance * 100).toInt().coerceIn(0, max), fromUser = false)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (64f * resources.displayMetrics.density).toInt()
            )
        }
        container.addView(balanceSlider)
        balanceSlider.onProgressChanged = { progress, fromUser ->
            if (fromUser) {
                profile = profile.copy(balance = progress / 100f)
                balanceLabel.text = programBalanceText(profile.balance)
            }
        }
        balanceSlider.onStopTracking = { apply() }
        val lockButton = Button(this).also { styleDialogButton(it) }
        val lockedIsoButton = Button(this).also { styleDialogButton(it) }
        val lockedShutterButton = Button(this).also { styleDialogButton(it) }
        fun refreshLock() {
            lockButton.text = programLockText(profile.lockMode) + " • tap to cycle"
            styleDialogButton(lockButton, active = profile.lockMode != ProgramLockMode.NONE)
        }
        fun refreshLockedButtons() {
            val isoRange = controller.manualControlRange(ManualControl.ISO)
            val shutterRange = controller.manualControlRange(ManualControl.SHUTTER)
            lockedIsoButton.text = "Locked ISO: " + when {
                profile.lockMode != ProgramLockMode.ISO_LOCK -> "-- (ISO lock off)"
                profile.lockedIso > 0 -> "ISO ${profile.lockedIso}"
                isoRange != null -> "ISO ${isoRange.current} (live)"
                else -> "--"
            }
            lockedIsoButton.isEnabled = profile.lockMode == ProgramLockMode.ISO_LOCK
            styleDialogButton(lockedIsoButton, active = profile.lockMode == ProgramLockMode.ISO_LOCK)
            lockedIsoButton.alpha = if (profile.lockMode == ProgramLockMode.ISO_LOCK) 1f else 0.6f
            lockedShutterButton.text = "Locked shutter: " + when {
                profile.lockMode != ProgramLockMode.SHUTTER_LOCK -> "-- (shutter lock off)"
                profile.lockedShutterNanos > 0L -> formatShutter(profile.lockedShutterNanos)
                shutterRange != null -> "${formatShutter(shutterRange.current)} (live)"
                else -> "--"
            }
            lockedShutterButton.isEnabled = profile.lockMode == ProgramLockMode.SHUTTER_LOCK
            styleDialogButton(lockedShutterButton, active = profile.lockMode == ProgramLockMode.SHUTTER_LOCK)
            lockedShutterButton.alpha = if (profile.lockMode == ProgramLockMode.SHUTTER_LOCK) 1f else 0.6f
        }
        refreshLock()
        refreshLockedButtons()
        lockButton.setOnClickListener {
            profile = seedLockedValuesFor(profile.copy(lockMode = when (profile.lockMode) {
                ProgramLockMode.NONE -> ProgramLockMode.ISO_LOCK
                ProgramLockMode.ISO_LOCK -> ProgramLockMode.SHUTTER_LOCK
                ProgramLockMode.SHUTTER_LOCK -> ProgramLockMode.NONE
            }))
            refreshLock(); refreshLockedButtons(); apply()
            setStatus("PROGRAM ${profile.lockMode.name}")
        }
        container.addView(lockButton)
        lockedIsoButton.setOnClickListener {
            val range = controller.manualControlRange(ManualControl.ISO) ?: return@setOnClickListener
            val steps = listOf(100, 200, 400, 800, 1600, 3200, 6400)
                .filter { it in range.minimum..range.maximum }
            if (steps.isEmpty()) return@setOnClickListener
            val current = profile.lockedIso.takeIf { it > 0 } ?: range.current.toInt()
            val next = steps[(steps.indexOfClosest(current) + 1) % steps.size]
            profile = profile.copy(lockedIso = next)
            refreshLockedButtons(); apply()
        }
        lockedShutterButton.setOnClickListener {
            val range = controller.manualControlRange(ManualControl.SHUTTER) ?: return@setOnClickListener
            val steps = listOf(
                1_000_000_000L / 1000, 1_000_000_000L / 500, 1_000_000_000L / 250,
                1_000_000_000L / 125, 1_000_000_000L / 60, 1_000_000_000L / 30,
                1_000_000_000L / 15, 1_000_000_000L / 8, 1_000_000_000L / 4
            ).filter { it in range.minimum..range.maximum }
            if (steps.isEmpty()) return@setOnClickListener
            val current = profile.lockedShutterNanos.takeIf { it > 0L } ?: range.current
            val next = steps[(steps.indexOfClosest(current) + 1) % steps.size]
            profile = profile.copy(lockedShutterNanos = next)
            refreshLockedButtons(); apply()
        }
        refreshLockedButtons()
        container.addView(lockedIsoButton)
        container.addView(lockedShutterButton)
        val isoMinButton = Button(this).also { styleDialogButton(it) }
        val isoMaxButton = Button(this).also { styleDialogButton(it) }
        val shutterMinButton = Button(this).also { styleDialogButton(it) }
        val shutterMaxButton = Button(this).also { styleDialogButton(it) }
        fun refreshBounds() {
            isoMinButton.text = programIsoBoundText(profile.isoMin, isMin = true)
            isoMaxButton.text = programIsoBoundText(profile.isoMax, isMin = false)
            shutterMinButton.text = programShutterBoundText(profile.shutterMinNanos, isMin = true, autoSafe = false)
            shutterMaxButton.text = programShutterBoundText(
                profile.shutterMaxNanos, isMin = false, autoSafe = profile.useAutoSafeShutter
            )
            styleDialogButton(isoMinButton, active = profile.isoMin > 0)
            styleDialogButton(isoMaxButton, active = profile.isoMax > 0)
            styleDialogButton(shutterMinButton, active = profile.shutterMinNanos > 0L)
            styleDialogButton(
                shutterMaxButton,
                active = profile.shutterMaxNanos > 0L || profile.useAutoSafeShutter
            )
        }
        isoMinButton.setOnClickListener {
            val range = controller.manualControlRange(ManualControl.ISO)
            val sensorMin = range?.minimum?.toInt() ?: 100
            val values = listOf(0, sensorMin, 100, 200, 400, 800).distinct()
            profile = profile.copy(isoMin = values[(values.indexOf(profile.isoMin).takeIf { it >= 0 } ?: 0)
                .let { (it + 1) % values.size }])
            if (profile.isoMax > 0 && profile.isoMin > profile.isoMax) profile = profile.copy(isoMax = 0)
            refreshBounds(); apply()
        }
        isoMaxButton.setOnClickListener {
            val values = listOf(0, 400, 800, 1600, 3200, 6400)
            profile = profile.copy(isoMax = values[(values.indexOf(profile.isoMax).takeIf { it >= 0 } ?: 0)
                .let { (it + 1) % values.size }])
            if (profile.isoMin > 0 && profile.isoMax > 0 && profile.isoMin > profile.isoMax) {
                profile = profile.copy(isoMin = 0)
            }
            refreshBounds(); apply()
        }
        shutterMinButton.setOnClickListener {
            val values = listOf(0L, 1_000_000_000L / 1000, 1_000_000_000L / 500,
                1_000_000_000L / 250, 1_000_000_000L / 125, 1_000_000_000L / 60)
            profile = profile.copy(shutterMinNanos = values[
                (values.indexOf(profile.shutterMinNanos).takeIf { it >= 0 } ?: 0).let { (it + 1) % values.size }])
            refreshBounds(); apply()
        }
        shutterMaxButton.setOnClickListener {
            val values = listOf(0L, 1_000_000_000L / 15, 1_000_000_000L / 30,
                1_000_000_000L / 60, 1_000_000_000L / 125, 1_000_000_000L / 250)
            if (profile.useAutoSafeShutter) {
                profile = profile.copy(useAutoSafeShutter = false, shutterMaxNanos = 0L)
            } else {
                val next = values[(values.indexOf(profile.shutterMaxNanos).takeIf { it >= 0 } ?: 0)
                    .let { (it + 1) % values.size }]
                profile = profile.copy(
                    shutterMaxNanos = next,
                    useAutoSafeShutter = next == 0L
                )
            }
            refreshBounds(); apply()
        }
        refreshBounds()
        container.addView(isoMinButton)
        container.addView(isoMaxButton)
        container.addView(shutterMinButton)
        container.addView(shutterMaxButton)
        val biasLabel = TextView(this).apply {
            text = programEvBiasText(profile.evBias)
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(12), 0, 0)
        }
        container.addView(biasLabel)
        val biasSlider = RuleSliderView(this).apply {
            label = "EV"
            max = 60
            setProgressFromUser(
                ((profile.evBias - ProgramAeProfile.MIN_EV_BIAS) * 20f).toInt().coerceIn(0, max),
                fromUser = false
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (64f * resources.displayMetrics.density).toInt()
            )
        }
        container.addView(biasSlider)
        biasSlider.onProgressChanged = { progress, fromUser ->
            if (fromUser) {
                profile = profile.copy(evBias = ProgramAeProfile.MIN_EV_BIAS + progress / 20f)
                biasLabel.text = programEvBiasText(profile.evBias)
            }
        }
        biasSlider.onStopTracking = { apply() }
        container.addView(TextView(this).apply {
            text = "ETTR overrides PROGRAM still exposure when converged."
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
            setPadding(0, dp(8), 0, 0)
        })
        val meteringButton = Button(this).also { styleDialogButton(it) }
        fun refreshMetering() {
            meteringButton.text = "Metering: ${programMeteringLabel(profile.metering)} • tap to switch"
        }
        refreshMetering()
        meteringButton.setOnClickListener {
            profile = profile.copy(metering = nextProgramMetering(profile.metering))
            refreshMetering(); apply()
            setStatus("PROGRAM METER ${profile.metering.name}")
        }
        container.addView(meteringButton)
        container.addView(Button(this).also { styleDialogButton(it) }.apply {
            text = "Reset per-lens defaults"
            setOnClickListener {
                profile = ProgramAeProfile()
                balanceSlider.setProgressFromUser((profile.balance * 100).toInt(), fromUser = false)
                biasSlider.setProgressFromUser(
                    ((profile.evBias - ProgramAeProfile.MIN_EV_BIAS) * 20f).toInt().coerceIn(0, 60),
                    fromUser = false
                )
                balanceLabel.text = programBalanceText(profile.balance)
                biasLabel.text = programEvBiasText(profile.evBias)
                refreshLock(); refreshLockedButtons(); refreshBounds(); refreshMetering(); apply()
            }
        })
        AlertDialog.Builder(this)
            .setTitle("PROGRAM AE • custom")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Done", null)
            .show()
    }

    private fun List<Int>.indexOfClosest(value: Int): Int {
        if (isEmpty()) return 0
        return indices.minByOrNull { kotlin.math.abs(this[it] - value) } ?: 0
    }

    private fun List<Long>.indexOfClosest(value: Long): Int {
        if (isEmpty()) return 0
        return indices.minByOrNull { kotlin.math.abs(this[it] - value) } ?: 0
    }

    /**
     * Dual-function AE metering button: in PROGRAM it cycles the RAW exposure
     * metering (center → average → spot); in every other mode it cycles the
     * hardware AE metering exactly as before.
     */
    private fun cycleAeMeteringMode() {
        if (captureExposureMode == CaptureExposureMode.PROGRAM) {
            if (!controller.hasManualSensorControl()) {
                setStatus("ANDROID AE • NO MANUAL SENSOR")
                return
            }
            val next = nextProgramMetering(programProfile().metering)
            saveProgramProfile(programProfile().copy(metering = next))
            setStatus("PROGRAM METER ${next.name}")
            updateQuickControls()
            return
        }
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
        if (!isVideoMode) lensSwitcher.visibility = View.INVISIBLE
        quickPanel.alpha = 0f
        quickPanel.translationY = dp(18).toFloat()
        quickPanel.visibility = View.VISIBLE
        quickPanel.animate().alpha(1f).translationY(0f).setDuration(180L).start()
        quickPanel.post { updateOverlayStack() }
    }

    private fun hideQuickControls() {
        if (!::quickPanel.isInitialized || quickPanel.visibility != View.VISIBLE) return
        quickPanel.animate().cancel()
        quickPanel.visibility = View.GONE
        quickPanel.alpha = 1f
        quickPanel.translationY = 0f
        lensSwitcher.visibility = if (isVideoMode) View.GONE else View.VISIBLE
        updateOverlayStack()
    }

    private fun closeFloatingPanels() {
        hideManualControl()
        hideQuickControls()
    }

    private fun updateQuickControls() {
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
        updateTimerBadge()
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

    private fun setQuickTileState(tile: TextView, active: Boolean) {
        if (quickTileStates.put(tile, active) == active) return
        tile.background = getDrawable(if (active) R.drawable.control_chip_active else R.drawable.control_chip)
        tile.setTextColor(getColor(if (active) R.color.accent_dark else R.color.text_primary))
    }

    private fun updateTimerBadge() {
        timerBadge.text = timerSeconds.toString()
        timerBadge.visibility = if (timerSeconds > 0) View.VISIBLE else View.GONE
    }

    /** Both scope views share one bottom slot; only the active mode is visible. */
    private fun scopeViews(): List<View> = listOf(histogramView, waveformView)

    private fun activeScopeView(): View =
        if (scopeMode == ScopeMode.WAVEFORM) waveformView else histogramView

    private fun syncScopeVisibility() {
        histogramView.visibility =
            if (histogramEnabled && scopeMode == ScopeMode.HISTOGRAM) View.VISIBLE else View.GONE
        waveformView.visibility =
            if (histogramEnabled && scopeMode == ScopeMode.WAVEFORM) View.VISIBLE else View.GONE
        // A GONE -> VISIBLE scope has no measured size yet: seat it now from
        // the fixed slot (plus a deferred measured correction) so showing it
        // in landscape never parks it behind the panel until next rotation.
        if (activeScopeView().visibility == View.VISIBLE) seatScopeSlot()
    }

    private fun toggleLevel() {
        levelEnabled = !levelEnabled
        lensPreferences().edit().putBoolean(KEY_LEVEL, levelEnabled).apply()
        syncLevelVisibility()
        updateQuickControls()
        setStatus(if (levelEnabled) "LEVEL ON" else "LEVEL OFF")
    }

    private fun syncLevelVisibility() {
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

    /** Edge-triggered by the controller's manual/tap-focus auto-show policy. */
    private fun setFocusPeakingVisible(visible: Boolean) {
        if (!::focusPeakingOverlay.isInitialized) return
        focusPeakingOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) focusPeakingOverlay.clear()
    }

    private fun scopeTileLabel(): String = when {
        !histogramEnabled -> "OFF"
        scopeMode == ScopeMode.WAVEFORM -> "WAVE"
        else -> "HISTO"
    }

    private fun persistScopeSelection() {
        lensPreferences().edit()
            .putBoolean(KEY_HISTOGRAM, histogramEnabled)
            .putString(KEY_SCOPE_MODE, scopeMode.name)
            .apply()
    }

    /** Tap the scope to switch between histogram and waveform. The mode is applied
     * immediately and remembered across restarts. */
    private fun toggleScopeMode() {
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

    /** Scope source follows the capture format: AgX-curved JPEG preview with a JPEG
     * format, linear RAW with DNG only. */
    private fun applyScopeSource() {
        if (!::histogramView.isInitialized || !::waveformView.isInitialized) return
        val agx = scopeAgxApplied()
        histogramView.setAgxApplied(agx)
        waveformView.setAgxApplied(agx)
        if (::controller.isInitialized) controller.setScopeAgxApplied(agx)
        rawScopeLive = false
        lastRawScopeMs = Long.MIN_VALUE
        refreshScopeContentDescription()
    }

    private fun scopeStatusLabel(): String {
        val mode = if (scopeMode == ScopeMode.WAVEFORM) "WAVE" else "HISTO"
        val source = if (scopeAgxApplied()) "JPEG" else "RAW"
        return "$mode $source"
    }

    private fun refreshScopeContentDescription() {
        if (!::histogramView.isInitialized || !::waveformView.isInitialized) return
        val source = if (scopeAgxApplied()) "AgX JPEG preview" else "linear RAW sensor"
        val live = rawScopeLive && !rawScopeStalled()
        val freshness = if (live) "live" else "waiting for a fresh RAW frame"
        histogramView.contentDescription =
            "Histogram, $source, $freshness. Tap to switch to waveform."
        waveformView.contentDescription =
            "Waveform, $source, $freshness. Tap to switch to histogram."
    }

    /** Freshness changes the accessibility status, never the selected mode or source. */
    private fun rawScopeStalled(): Boolean =
        RawHistogramThrottle.isStalled(SystemClock.elapsedRealtime(), lastRawScopeMs, HISTOGRAM_RAW_HOLD_MS)

    /** RAW frames arrive push-driven; this only refreshes the stall status. */
    private fun scheduleScope() {
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

    private fun controlText(label: String, rawValue: String): String {
        val value = rawValue.removePrefix(label).trim().ifEmpty { "--" }
        return "$label\n$value"
    }

    private fun TextView.setTextIfChanged(value: String) {
        if (!android.text.TextUtils.equals(text, value)) text = value
    }

    private fun refreshLensSwitcher() {
        val options = controller.lensOptions()
        if (options.isEmpty()) return
        // Rebuilding the switcher view hierarchy on every controls publication is
        // wasted layout work; only rebuild when the lens set or selection changed.
        val signature = options.joinToString("|") { "${it.cameraId}:${it.selected}" }
        if (signature == lastLensSwitcherSignature && lensSwitcher.childCount > 0) return
        lastLensSwitcherSignature = signature
        lensSwitcher.removeAllViews()
        options.forEachIndexed { index, option ->
            val button = RotatingTextView(this).apply {
                text = option.label
                setTextColor(getColor(if (option.selected) R.color.accent else R.color.text_primary))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                // Strip segments are transparent; the selected lens gets the
                // ticked active pill (backgrounds never rotate, so the tick
                // always stays on top in screen space).
                background = getDrawable(
                    if (option.selected) R.drawable.focal_active else android.R.color.transparent
                )
                alpha = if (option.selected) 1f else 0.78f
                minWidth = dp(48)
                minimumHeight = dp(34)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                contentDescription = "Switch to ${option.label} lens"
                setOnClickListener {
                    hideManualControl()
                    lensPreferences().edit().putString(KEY_LAST_CAMERA_ID, option.cameraId).apply()
                    controller.selectLens(option.cameraId)
                }
            }
            lensSwitcher.addView(button, LinearLayout.LayoutParams(wrapContent(), wrapContent()).apply {
                if (index > 0) topMargin = dp(4)
            })
            button.contentRotation = controlRotationDegrees
        }
    }

    /** Video lock-on-record switches (default off: continuous AE/AWB/AF). */
    private fun videoRecordLocks(): VideoRecordLocks =
        VideoRecordLocks.load { key -> lensPreferences().getBoolean(key, false) }

    /** Range source follows the camera owner: recorder while rolling, controller otherwise. */
    private fun manualRangeFor(control: ManualControl): ManualControlRange? {
        if (videoRecording) {
            runCatching { videoRecorder?.videoControlRange(control) }.getOrNull()?.let { return it }
            runCatching { logRecorder?.videoControlRange(control) }.getOrNull()?.let { return it }
            return null
        }
        return runCatching { controller.manualControlRange(control) }.getOrNull()
    }

    private fun setManualValueFor(control: ManualControl, value: Long?) {
        if (videoRecording) {
            if (videoRecorder != null) {
                videoRecorder?.setVideoManualControl(control, value)
                refreshVideoChip(control)
                return
            }
            if (logRecorder != null) {
                logRecorder?.setVideoManualControl(control, value)
                refreshVideoChip(control)
                return
            }
        }
        controller.setManualControl(control, value)
    }

    /** Optimistic chip refresh while rolling (the stills controller is stopped). */
    private fun refreshVideoChip(control: ManualControl) {
        val range = manualRangeFor(control) ?: return
        when (control) {
            ManualControl.ISO -> isoControl.text = controlText("ISO", range.current.toString())
            ManualControl.SHUTTER -> shutterControl.text =
                controlText("S", formatShutter(range.current))
            ManualControl.WHITE_BALANCE -> wbControl.text =
                controlText("WB", "${range.current}K")
            ManualControl.FOCUS_DISTANCE -> focusControl.text =
                controlText("AF", if (range.automatic) "AF" else formatFocusDistance(range.current))
            ManualControl.EXPOSURE_COMPENSATION -> {
                val isManualExposure = runCatching {
                    (videoRecorder?.videoManual ?: logRecorder?.videoManual)?.let {
                        VideoManualPolicy.wantsManualExposure(it)
                    } ?: false
                }.getOrDefault(false)
                evControl.text = if (isManualExposure) {
                    controlText("EV", "EV --")
                } else {
                    controlText("EV", formatVideoEvStops(range.current))
                }
            }
        }
    }

    private fun formatVideoEvStops(index: Long): String {
        val step = runCatching {
            val manager = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = videoCameraInfo(manager)?.first ?: return@runCatching null
            manager.getCameraCharacteristics(cameraId)
                .get(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                ?.toFloat()
        }.getOrNull() ?: return "EV $index"
        if (step <= 0f) return "EV $index"
        return String.format(Locale.US, "EV %+.1f", index * step)
    }

    private fun showManualControl(control: ManualControl, allowToggle: Boolean = true) {
        hideQuickControls()
        if (allowToggle && activeManualControl == control && manualPanel.visibility == View.VISIBLE) {
            hideManualControl()
            return
        }
        val range = manualRangeFor(control)
        if (range == null) {
            setStatus("${shortControlName(control)} N/A")
            return
        }
        activeManualControl = control
        activeProgramAxis = null
        // Tick-only ruler with no text label: values live in the exposure chips, never
        // in the slider strip, so landscape stays compact. Full values remain in
        // accessibility descriptions.
        manualLimits.visibility = View.GONE
        manualSlider.label = shortControlName(control)
        manualSlider.max = SLIDER_STEPS
        manualSlider.setProgressFromUser(sliderProgress(control, range), fromUser = false)
        manualAuto.text = if (control == ManualControl.EXPOSURE_COMPENSATION) "RESET" else "AUTO"
        manualAuto.isEnabled = !range.automatic
        refreshRuleSliderAccessibility()
        manualPanel.visibility = View.VISIBLE
        // Video mode keeps the lens pill hidden; photo mode parks it.
        if (!isVideoMode) lensSwitcher.visibility = View.INVISIBLE
        updateOverlayStack()
        manualPanel.post { updateOverlayStack() }
        scheduleManualPanelHide()
    }

    /** Full value stays available to accessibility even though the ruler draws ticks only. */
    private fun refreshRuleSliderAccessibility() {
        activeProgramAxis?.let { axis ->
            val range = manualRangeFor(axis) ?: return
            val value = sliderValue(axis, range, manualSlider.progress)
            val valueText = formatManualValue(axis, value)
            manualSlider.valueText = rulerValueText(axis, value)
            manualSlider.minText = rulerValueText(axis, range.minimum)
            manualSlider.maxText = rulerValueText(axis, range.maximum)
            manualSlider.contentDescription =
                "${controlName(axis)} lock slider, $valueText. Current value also shown in the ${shortControlName(axis)} chip."
            return
        }
        val control = activeManualControl ?: return
        val range = manualRangeFor(control) ?: return
        val value = sliderValue(control, range, manualSlider.progress)
        val valueText = formatManualValue(control, value)
        manualSlider.valueText = rulerValueText(control, value)
        manualSlider.minText = rulerValueText(control, range.minimum)
        manualSlider.maxText = rulerValueText(control, range.maximum)
        manualSlider.contentDescription =
            "${controlName(control)} slider, $valueText. Current value also shown in the ${shortControlName(control)} chip."
    }

    /**
     * Compact value for the floating ruler: the axis code already sits above it,
     * so "ISO 7964" becomes "7964" and "+0.2 EV" becomes "+0.2".
     */
    private fun rulerValueText(control: ManualControl, value: Long): String {
        val full = formatManualValue(control, value)
        return when (control) {
            ManualControl.ISO -> full.removePrefix("ISO ").trim()
            ManualControl.EXPOSURE_COMPENSATION -> full.removeSuffix(" EV").trim()
            else -> full
        }.ifEmpty { "--" }
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
        if (::manualSlider.isInitialized) {
            pendingSliderUpdate?.let(manualSlider::removeCallbacks)
        }
        pendingSliderUpdate = null
        manualPanel.visibility = View.GONE
        // Video mode keeps the lens pill hidden; photo mode restores it.
        if (::lensSwitcher.isInitialized) {
            lensSwitcher.visibility = if (isVideoMode) View.GONE else View.VISIBLE
        }
        activeManualControl = null
        activeProgramAxis = null
        updateOverlayStack()
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
        if (manualPanel.visibility != View.VISIBLE) return
        val range = manualRangeFor(control) ?: return
        if (!range.automatic) return
        val value = when (control) {
            ManualControl.ISO -> iso.toLong()
            ManualControl.SHUTTER -> shutter
            ManualControl.WHITE_BALANCE -> wb.toLong()
            ManualControl.FOCUS_DISTANCE -> range.current
            ManualControl.EXPOSURE_COMPENSATION -> range.current
        }
        if (value > 0 || control == ManualControl.EXPOSURE_COMPENSATION) {
            manualSlider.setProgressFromUser(
                sliderProgress(control, range.copy(current = value.coerceIn(range.minimum, range.maximum))),
                fromUser = false
            )
            refreshRuleSliderAccessibility()
        }
    }

    private fun controlName(control: ManualControl): String = when (control) {
        ManualControl.ISO -> "ISO sensitivity"
        ManualControl.SHUTTER -> "Shutter speed"
        ManualControl.WHITE_BALANCE -> "White balance"
        ManualControl.FOCUS_DISTANCE -> "Manual focus"
        ManualControl.EXPOSURE_COMPENSATION -> "Exposure compensation"
    }

    /** Abbreviated chip label for the top-right status line. */
    private fun shortControlName(control: ManualControl): String = when (control) {
        ManualControl.ISO -> "ISO"
        ManualControl.SHUTTER -> "S"
        ManualControl.WHITE_BALANCE -> "WB"
        ManualControl.FOCUS_DISTANCE -> "MF"
        ManualControl.EXPOSURE_COMPENSATION -> "EV"
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

    private fun ettrSettings(): EttrSettings {
        val prefs = lensPreferences()
        return EttrSettings(
            enabled = prefs.getBoolean(KEY_ETTR_ENABLED, false),
            headroomEv = prefs.getFloat(KEY_ETTR_HEADROOM_EV, 0.3f).coerceIn(0f, 1f),
            isoLimit = prefs.getInt(KEY_ETTR_ISO_LIMIT, 0)
        )
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

    private fun activeProfileLensId(): String? =
        controllerIfReady?.activeCameraId()
            ?: lensPreferences().getString(KEY_LAST_CAMERA_ID, null)

    private val controllerIfReady: RawCameraController?
        get() = if (::controller.isInitialized) controller else null

    /** Per-lens PROGRAM profile with one-time migration from legacy global keys. */
    private fun programProfile(): ProgramAeProfile {
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

    private fun saveProgramProfile(profile: ProgramAeProfile) {
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

    private fun jpegOutputSettings(): JpegOutputSettings = JpegOutputSettings(
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
    private fun jxlOutputSettings(): JxlOutputSettings = JxlOutputSettings.fromPreferences(
        bitDepthName = lensPreferences().getString(KEY_JXL_BIT_DEPTH, null),
        pixelTypeName = lensPreferences().getString(KEY_JXL_PIXEL_TYPE, null),
        quality = lensPreferences().getInt(KEY_JXL_QUALITY, 100),
        effort = lensPreferences().getInt(KEY_JXL_EFFORT, 7),
        decodingSpeed = lensPreferences().getInt(KEY_JXL_DECODING_SPEED, 0),
        displayP3 = lensPreferences().getBoolean(KEY_JXL_DISPLAY_P3, false),
        hdrTransferName = lensPreferences().getString(KEY_JXL_HDR_TRANSFER, null)
    )

    private fun denoiseSettings(): DenoiseSettings {
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

    private fun persistDenoiseSettings(settings: DenoiseSettings): Boolean {
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

    private fun galoshSettings(): GaloshSettings {
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

    private fun persistGaloshSettings(settings: GaloshSettings): Boolean {
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
    private fun preloadRawNindIfEnabled() {
        if (lensPreferences().getBoolean(KEY_AI_DENOISE_ENABLED, false)) {
            runCatching { RawNindNcnnProcessor.start(applicationContext) }
        }
    }

    /** One-line AI model state for the Denoise tab; never blocks (no waitReady). */
    private fun aiModelStatus(): String {
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

    private fun shareLatestLog() {
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

    /**
     * Settings sheet. [initialTab] selects the opening page (see
     * [SETTINGS_VIDEO_TAB]); every entry point except the video-mode
     * settings button uses the default General tab.
     */
    private fun showSettings(initialTab: Int = 0) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        // Modern chip tab row (mirrors PROGRAM AE panel styling): scrollable so
        // seven tabs fit without squeezing. Active tab uses the accent chip.
        fun sectionTitle(text: String): TextView = TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(0, dp(16), 0, dp(4))
        }
        fun sectionDesc(text: String): TextView = TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
            setPadding(0, 0, 0, dp(4))
        }
        fun settingsButton(label: String, active: Boolean = false): Button =
            Button(this).apply {
                text = label
                styleSettingsButton(this, active)
            }
        fun settingsCheck(label: String, checked: Boolean): CheckBox =
            CheckBox(this).apply {
                text = label
                setTextColor(getColor(R.color.text_primary))
                isChecked = checked
                setPadding(0, dp(2), 0, dp(2))
            }
        fun sliderLabel(text: String): TextView = TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(8), dp(4), 0)
        }
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            showDividers = LinearLayout.SHOW_DIVIDER_NONE
        }
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        fun chipTab(label: String): Button = Button(this).apply {
            text = label
            styleSettingsButton(this, active = false)
        }.also { button ->
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.marginEnd = dp(8)
            tabRow.addView(button, params)
        }
        val generalTab = chipTab("General")
        val jpegTab = chipTab("Photo")
        val videoTab = chipTab("Video")
        val exposureTab = chipTab("Exposure")
        val burstTab = chipTab("Burst")
        val denoiseTab = chipTab("Denoise")
        val lensesTab = chipTab("Lenses")
        val debugTab = chipTab("Debug")
        val aboutTab = chipTab("About")
        val allTabs = listOf(generalTab, jpegTab, videoTab, exposureTab, burstTab, denoiseTab, lensesTab, debugTab, aboutTab)
        fun markActive(active: Button) {
            allTabs.forEach { styleSettingsButton(it, active = it === active) }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(8))
        }
        fun polish() {
            for (index in 0 until content.childCount) {
                val child = content.getChildAt(index)
                if (child is Button) styleSettingsButton(child, active = false)
            }
        }
        // MATCH_PARENT so the full chip row scrolls inside the dialog instead of
        // stretching the window beyond the screen edges.
        container.addView(tabScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(TextView(this).apply {
            text = "PROGRAM • per-lens editor lives under Lenses; RAW DNG calibration under General; overlays, logs, and HDR debug frames under Debug"
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
            setPadding(0, dp(6), 0, 0)
        })
        val settingsScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        container.addView(settingsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        lateinit var dialog: AlertDialog
        var currentTab = 0
        fun showGeneralTab() {
            content.removeAllViews()
            content.addView(sectionTitle("RAW capture"))
            content.addView(CheckBox(this).apply {
                text = "Experimental two-exposure RAW HDR"
                setTextColor(getColor(R.color.text_primary))
                isChecked = dualRawEnabled
                isEnabled = controller.supportsDualRaw() || dualRawEnabled
                setOnCheckedChangeListener { button, enabled ->
                    if (enabled && !controller.supportsDualRaw()) {
                        button.isChecked = false
                        setStatus("RAW HDR TEST NEEDS CAMERA 0")
                    } else {
                        dualRawEnabled = enabled
                        setStatus(if (enabled) "TWO-EXPOSURE RAW HDR ON" else "TWO-EXPOSURE RAW HDR OFF")
                        updateQuickControls()
                    }
                }
            })
            content.addView(TextView(this).apply {
                text = "Camera 0 only. Saves a 16-bit DNG from two sequential exposures, " +
                    "using the short exposure to recover clipped highlights. " +
                    "Does not reduce shadow noise. No alignment or motion correction; " +
                    "keep the phone and scene still. Overrides the release mode " +
                    "while enabled. Resets when the app restarts or you change cameras."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
            })
            content.addView(Button(this).apply {
                text = "DNG sensor calibration (levels • noise • color)"
                setOnClickListener { showDngMetadataOverrideEditor() }
            })
            content.addView(Button(this).apply {
                text = "Vendor keys (per lens • session + request)"
                setOnClickListener { showVendorKeysEditor() }
            })
            content.addView(TextView(this).apply {
                text = "DNG writer backend"
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
                setPadding(0, dp(12), 0, 0)
            })
            content.addView(Button(this).apply {
                fun refresh() { text = dngWriterBackend().label }
                refresh()
                setOnClickListener {
                    val entries = DngWriterBackend.entries.toTypedArray()
                    val current = dngWriterBackend()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("DNG writer backend")
                        .setSingleChoiceItems(entries.map { it.label }.toTypedArray(), current.ordinal) { dialog, which ->
                            val selected = entries[which]
                            lensPreferences().edit().putString(KEY_DNG_WRITER_BACKEND, selected.preferenceValue).apply()
                            setStatus("WRITER • ${selected.name}")
                            refresh()
                            dialog.dismiss()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            })
            content.addView(TextView(this).apply {
                text = "Android DngCreator is the default. AUTO falls back to patched TinyDNG only if the platform writer fails."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
            })
            content.addView(TextView(this).apply {
                text = "DNG compression"
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
                setPadding(0, dp(12), 0, 0)
            })
            content.addView(Button(this).apply {
                fun refresh() { text = dngCompression().label }
                refresh()
                setOnClickListener {
                    val entries = DngCompression.entries.toTypedArray()
                    val current = dngCompression()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("DNG compression")
                        .setSingleChoiceItems(entries.map { it.label }.toTypedArray(), current.ordinal) { dialog, which ->
                            val selected = entries[which]
                            lensPreferences().edit().putString(KEY_DNG_COMPRESSION, selected.preferenceValue).apply()
                            setStatus("DNG • ${selected.name}")
                            refresh()
                            dialog.dismiss()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            })
            content.addView(TextView(this).apply {
                text = "JXL stores the mosaic losslessly (DNG 1.7, like iPhone ProRAW) at roughly half the size. " +
                    "Needs Lightroom / Adobe DNG Converter 16+ or Apple Photos — darktable and most LibRaw tools cannot read JXL DNGs yet. " +
                    "Single-frame and HDR+ merged DNGs; packed and float/linear merges stay uncompressed. " +
                    "Single-frame GPS files stay uncompressed to preserve location; merged JXL keeps GPS."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
            })
            content.addView(sectionTitle("Location"))
            var suppressGpsToggle = false
            content.addView(CheckBox(this).apply {
                text = "Save GPS location in photos (DNG + JPEG)"
                setTextColor(getColor(R.color.text_primary))
                isChecked = gpsEnabled()
                setOnCheckedChangeListener { button, enabled ->
                    if (suppressGpsToggle) return@setOnCheckedChangeListener
                    if (enabled == gpsEnabled()) return@setOnCheckedChangeListener
                    if (enabled) {
                        if (gpsProvider?.hasPermission() == true) {
                            setGpsEnabled(true)
                        } else {
                            suppressGpsToggle = true
                            button.isChecked = false
                            suppressGpsToggle = false
                            requestPermissions(
                                GpsLocationProvider.permissions(), LOCATION_PERMISSION
                            )
                        }
                    } else {
                        setGpsEnabled(false)
                    }
                }
            })
            content.addView(TextView(this).apply {
                text = "Enabling requests location permission. Without a fresh fix " +
                    "photos save without GPS tags rather than with a stale position."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
            })
            content.addView(sectionTitle("Viewfinder"))
            // GPU VF: selectable superpixel resolution (480/640 full-rate / 960 balanced / 1080 detail).
            content.addView(Button(this).apply {
                text = vfResolutionText(vfResolution)
                setOnClickListener {
                    cycleVfResolution()
                    text = vfResolutionText(vfResolution)
                }
            })
            content.addView(Button(this).apply {
                text = vfPreviewModeText()
                setOnClickListener {
                    cycleVfPreviewMode()
                    text = vfPreviewModeText()
                }
            })
            content.addView(CheckBox(this).apply {
                text = "Focus peaking (manual + tap focus)"
                setTextColor(getColor(R.color.text_primary))
                isChecked = focusPeakingEnabled
                setOnCheckedChangeListener { _, enabled ->
                    focusPeakingEnabled = enabled
                    lensPreferences().edit().putBoolean(KEY_FOCUS_PEAKING, enabled).apply()
                    controller.setFocusPeakingEnabled(enabled)
                    setStatus(if (enabled) "PEAKING ON" else "PEAKING OFF")
                }
            })
            content.addView(sectionDesc("Peaking auto-shows while a manual focus distance is set or a tap-to-focus scan/lock is in flight."))
            content.addView(Button(this).apply {
                fun refresh() {
                    text = "Peaking color: ${focusPeakingColor.label}"
                }
                refresh()
                setOnClickListener {
                    focusPeakingColor = focusPeakingColor.next()
                    lensPreferences().edit()
                        .putString(KEY_FOCUS_PEAKING_COLOR, focusPeakingColor.preferenceValue).apply()
                    focusPeakingOverlay.peakingColor = focusPeakingColor
                    refresh()
                    setStatus("PEAKING • ${focusPeakingColor.label}")
                }
            })
            content.addView(sectionDesc("Overlays, logs, and capture debug output live under the Debug tab."))
            content.addView(sectionTitle("Settings backup"))
            content.addView(sectionDesc("One JSON file with every setting: camera, JPEG, video, exposure, burst, denoise, lenses, DNG calibration, and PROGRAM profiles. Importing replaces all settings and asks for a restart."))
            content.addView(Button(this).apply {
                text = "Export settings to JSON"
                setOnClickListener { exportSettings() }
            })
            content.addView(Button(this).apply {
                text = "Import settings from JSON"
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Import settings?")
                        .setMessage("This replaces ALL current settings with the file contents. Export first to keep a backup.")
                        .setPositiveButton("Choose file") { _, _ -> importSettings() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            })
            markActive(generalTab)
            polish()
        }
        fun showJpegTab() {
            rawZslSettingsStatus = null
            sidecarSettingsStatus = null
            content.removeAllViews()
            // JPEG XL container options, mirroring darktable's export panel:
            // bit depth, 16-bit pixel type, quality, encoding effort, and
            // decoding-speed tier. The develop look (AgX) is shared with JPEG.
            content.addView(sectionTitle("Photo format & JPEG XL quality"))
            content.addView(sectionDesc("Up to 16-bit integer (plus 16/32-bit float), like darktable. Quality 100 is lossless on integer depths. Higher effort compresses better but encodes slower."))
            content.addView(Button(this).apply {
                fun refresh() {
                    text = "Developed format: ${developedFormat.label}"
                }
                refresh()
                setOnClickListener {
                    val next = developedFormat.next()
                    if (!controller.setDevelopedFormat(next)) {
                        setStatus("SETTINGS AFTER SAVES")
                        return@setOnClickListener
                    }
                    developedFormat = next
                    lensPreferences().edit().putString(KEY_DEVELOPED_FORMAT, next.name).apply()
                    refresh()
                    refreshCaptureFormatControl()
                    setStatus("DEVELOPED • ${next.label}")
                }
            })
            content.addView(sectionDesc("JPEG writes .jpg (8-bit); JXL writes .jxl at the bit depth below. Applies to new captures and offline merges."))
            var currentJxlSettings = jxlOutputSettings()
            fun applyJxlOutputSettings(settings: JxlOutputSettings): Boolean {
                val resolved = settings.resolved()
                if (!controller.setJxlOutputSettings(resolved)) {
                    setStatus("SETTINGS AFTER SAVES")
                    return false
                }
                currentJxlSettings = resolved
                lensPreferences().edit()
                    .putString(KEY_JXL_BIT_DEPTH, resolved.bitDepth.name)
                    .putString(KEY_JXL_PIXEL_TYPE, resolved.pixelType.name)
                    .putInt(KEY_JXL_QUALITY, resolved.quality)
                    .putInt(KEY_JXL_EFFORT, resolved.effort)
                    .putInt(KEY_JXL_DECODING_SPEED, resolved.decodingSpeed)
                    .putBoolean(KEY_JXL_DISPLAY_P3, resolved.displayP3)
                    .putString(KEY_JXL_HDR_TRANSFER, resolved.hdrTransfer.name)
                    .apply()
                return true
            }
            if (!NativeJxlEncoder.isAvailable()) {
                content.addView(sectionDesc("The JXL encoder library is not bundled in this build (offline or source-only build): developed-format JXL saves fail with an explanatory error. A networked build bundles libjxl automatically; see app/src/main/cpp/CMakeLists.txt."))
            }
            content.addView(Button(this).apply {
                fun refresh() {
                    text = "HDR transfer: ${currentJxlSettings.hdrTransfer.label}"
                }
                refresh()
                setOnClickListener {
                    if (applyJxlOutputSettings(
                        currentJxlSettings.copy(hdrTransfer = currentJxlSettings.hdrTransfer.next())
                    )) {
                        refresh()
                        setStatus("JXL TRANSFER • ${currentJxlSettings.hdrTransfer.label}")
                    }
                }
            })
            content.addView(sectionDesc("SDR is the classic still. HDR PQ keeps scene highlights above SDR white as true HDR (ST2084, 10+ bit, needs an HDR display); the AgX look stays shared."))
            content.addView(sectionDesc("Stills carry EXIF/XMP: Ultra HDR gainmaps apply to JPEG output only, and the AgX photo look below is shared."))
            // Declared pixel-type-first: the depth cycler below toggles its
            // visibility (darktable shows it for 16-bit only).
            val pixelTypeButton = Button(this).apply {
                fun refresh() {
                    text = "JXL pixel type: ${currentJxlSettings.pixelType.label}"
                }
                refresh()
                visibility =
                    if (currentJxlSettings.bitDepth == JxlBitDepth.BIT_16) Button.VISIBLE
                    else Button.GONE
                setOnClickListener {
                    if (applyJxlOutputSettings(
                        currentJxlSettings.copy(pixelType = currentJxlSettings.pixelType.next())
                    )) {
                        refresh()
                        setStatus("JXL PIXEL TYPE • ${currentJxlSettings.pixelType.label}")
                    }
                }
            }
            val depthButton = Button(this).apply {
                fun refresh() {
                    text = "JXL bit depth: ${currentJxlSettings.bitDepth.label}"
                }
                refresh()
                setOnClickListener {
                    if (applyJxlOutputSettings(
                        currentJxlSettings.copy(bitDepth = currentJxlSettings.bitDepth.next())
                    )) {
                        refresh()
                        pixelTypeButton.visibility =
                            if (currentJxlSettings.bitDepth == JxlBitDepth.BIT_16) Button.VISIBLE
                            else Button.GONE
                        setStatus("JXL DEPTH • ${currentJxlSettings.bitDepth.label}")
                    }
                }
            }
            content.addView(depthButton)
            content.addView(pixelTypeButton)
            content.addView(sectionDesc("Pixel type applies to 16-bit only (darktable parity): unsigned integer, or half-precision float. 32-bit is always float."))
            fun jxlQualityLabel(quality: Int): String =
                if (quality >= 100) "JXL quality: lossless" else "JXL quality: $quality%"
            var jxlQuality = currentJxlSettings.quality.coerceIn(
                JxlOutputSettings.MIN_QUALITY, JxlOutputSettings.MAX_QUALITY
            )
            val jxlQualityLabel = TextView(this).apply {
                text = jxlQualityLabel(jxlQuality)
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                setPadding(dp(12), dp(8), dp(12), 0)
            }
            content.addView(jxlQualityLabel)
            content.addView(SeekBar(this).apply {
                max = JxlOutputSettings.MAX_QUALITY - JxlOutputSettings.MIN_QUALITY
                progress = jxlQuality - JxlOutputSettings.MIN_QUALITY
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        jxlQuality = progress + JxlOutputSettings.MIN_QUALITY
                        jxlQualityLabel.text = jxlQualityLabel(jxlQuality)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        if (applyJxlOutputSettings(currentJxlSettings.copy(quality = jxlQuality))) {
                            setStatus(
                                if (jxlQuality >= 100) "JXL QUALITY • LOSSLESS"
                                else "JXL QUALITY • $jxlQuality%"
                            )
                        } else {
                            jxlQuality = currentJxlSettings.quality
                            seekBar.progress = jxlQuality - JxlOutputSettings.MIN_QUALITY
                        }
                    }
                })
            })
            fun addJxlSlider(
                title: String,
                maximum: Int,
                initial: Int,
                format: (Int) -> String,
                update: (JxlOutputSettings, Int) -> JxlOutputSettings,
                status: (Int) -> String
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
                            if (applyJxlOutputSettings(update(currentJxlSettings, selected))) {
                                setStatus(status(selected))
                            } else {
                                selected = initial.coerceIn(0, maximum)
                                seekBar.progress = selected
                            }
                        }
                    })
                })
            }
            addJxlSlider(
                "JXL encoding effort", 8, currentJxlSettings.effort - 1,
                { "${it + 1}" },
                { settings, value -> settings.copy(effort = value + 1) },
                { "JXL EFFORT • ${it + 1}" }
            )
            addJxlSlider(
                "JXL decoding speed", 4, currentJxlSettings.decodingSpeed,
                { "$it" },
                { settings, value -> settings.copy(decodingSpeed = value) },
                { "JXL DECODING SPEED • $it" }
            )
            content.addView(CheckBox(this).apply {
                text = "Display P3 JXL"
                setTextColor(getColor(R.color.text_primary))
                isChecked = currentJxlSettings.displayP3
                setOnCheckedChangeListener { button, enabled ->
                    if (enabled == currentJxlSettings.displayP3) return@setOnCheckedChangeListener
                    if (!applyJxlOutputSettings(currentJxlSettings.copy(displayP3 = enabled))) {
                        button.isChecked = currentJxlSettings.displayP3
                    }
                }
            })
            content.addView(sectionTitle("Photo look & JPEG quality"))
            content.addView(sectionDesc("Ultra HDR and Display P3 targets, JPEG quality and chroma, AgX look, adaptive exposure and highlight handling."))
            var currentJpegSettings = jpegOutputSettings()
            fun applyJpegOutputSettings(settings: JpegOutputSettings): Boolean {
                val resolved = settings.resolvedForPlatform()
                if (!controller.setJpegOutputSettings(resolved)) {
                    setStatus("SETTINGS AFTER SAVES")
                    return false
                }
                currentJpegSettings = resolved
                lensPreferences().edit()
                    .putBoolean(KEY_JPEG_ULTRA_HDR, resolved.ultraHdr)
                    .putBoolean(KEY_JPEG_DISPLAY_P3, resolved.displayP3)
                    .putInt(KEY_JPEG_QUALITY, resolved.jpegQuality)
                    .putString(KEY_JPEG_CHROMA_SUBSAMPLING, resolved.chromaSubsampling.name)
                    .putString(KEY_JPEG_DEMOSAIC, resolved.demosaic.name)
                    .putFloat(KEY_JPEG_AGX_PURITY, resolved.agxPurityBoost)
                    .putFloat(KEY_JPEG_AGX_CONTRAST, resolved.agxContrast)
                    .putFloat(KEY_JPEG_AGX_SATURATION, resolved.agxSaturation)
                    .putFloat(KEY_JPEG_AGX_HUE, resolved.agxHuePreservation)
                    .putFloat(KEY_JPEG_AGX_SHADOW_EV, resolved.agxShadowEv)
                    .putFloat(KEY_JPEG_AGX_HIGHLIGHT_EV, resolved.agxHighlightEv)
                    .putFloat(KEY_JPEG_AGX_GAMUT, resolved.agxGamutCompression)
                    .putBoolean(KEY_JPEG_ADAPTIVE_EXPOSURE, resolved.adaptiveExposureAuto)
                    .putFloat(KEY_JPEG_ADAPTIVE_PROGRAM, resolved.adaptiveExposureProgramStrength)
                    .putFloat(KEY_JPEG_HIGHLIGHT_HEADROOM, resolved.highlightHeadroom)
                    .putFloat(KEY_JPEG_SKY_PROTECTION, resolved.highlightSoftHeadroom)
                    .putFloat(KEY_JPEG_HIGHLIGHT_SHOULDER, resolved.highlightShoulder)
                    .putBoolean(KEY_JPEG_HL_RECONSTRUCTION, resolved.hlReconstruction)
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
            content.addView(CheckBox(this).apply {
                text = "Highlight reconstruction (color propagation)"
                setTextColor(getColor(R.color.text_primary))
                isChecked = currentJpegSettings.hlReconstruction
                setOnCheckedChangeListener { button, enabled ->
                    if (enabled == currentJpegSettings.hlReconstruction) return@setOnCheckedChangeListener
                    if (!applyJpegOutputSettings(currentJpegSettings.copy(hlReconstruction = enabled))) {
                        button.isChecked = currentJpegSettings.hlReconstruction
                    }
                }
            })
            val chromaButton = Button(this).apply {
                fun refresh() {
                    text = "JPEG chroma subsampling: ${currentJpegSettings.chromaSubsampling.label}"
                }
                refresh()
                setOnClickListener {
                    val next = currentJpegSettings.chromaSubsampling.next()
                    if (applyJpegOutputSettings(currentJpegSettings.copy(chromaSubsampling = next))) {
                        refresh()
                        setStatus("JPEG CHROMA • ${next.label}")
                    }
                }
            }
            content.addView(chromaButton)
            val demosaicButton = Button(this).apply {
                fun refresh() {
                    text = "Demosaic: ${currentJpegSettings.demosaic.label}"
                }
                refresh()
                setOnClickListener {
                    val next = currentJpegSettings.demosaic.next()
                    if (applyJpegOutputSettings(currentJpegSettings.copy(demosaic = next))) {
                        refresh()
                        setStatus("JPEG DEMOSAIC • ${next.label}")
                    }
                }
            }
            content.addView(demosaicButton)
            content.addView(sectionDesc("AMaZE (GLES) is the default still demosaic; RCD runs the Vulkan RCD modes and imports the result back. Quad-Bayer always uses AMaZE."))
            var jpegQuality = currentJpegSettings.jpegQuality.coerceIn(1, 100)
            val jpegQualityLabel = TextView(this).apply {
                text = "JPEG quality: $jpegQuality%"
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                setPadding(dp(12), dp(8), dp(12), 0)
            }
            content.addView(jpegQualityLabel)
            content.addView(SeekBar(this).apply {
                max = 99
                progress = jpegQuality - 1
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        jpegQuality = progress + 1
                        jpegQualityLabel.text = "JPEG quality: $jpegQuality%"
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        if (applyJpegOutputSettings(currentJpegSettings.copy(jpegQuality = jpegQuality))) {
                            setStatus("JPEG QUALITY • $jpegQuality%")
                        } else {
                            jpegQuality = currentJpegSettings.jpegQuality
                            seekBar.progress = jpegQuality - 1
                        }
                    }
                })
            })
            content.addView(TextView(this).apply {
                text = "Native libjpeg-turbo is used for SDR JPEG. Ultra HDR uses Android JPEG/R; quality still applies there, while chroma subsampling is controlled by the platform encoder."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(dp(12), dp(4), dp(12), dp(8))
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
                            // Smooth the drag: live-push the candidate look to the VF
                            // (preview-only; the saved contract commits on release).
                            if (fromUser) controller.previewAgxLive(update(currentJpegSettings, selected))
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                        override fun onStopTrackingTouch(seekBar: SeekBar) {
                            if (!applyJpegOutputSettings(update(currentJpegSettings, selected))) {
                                selected = initial.coerceIn(0, maximum)
                                seekBar.progress = selected
                                // Commit refused (saves in flight): drop the live
                                // candidate from the VF so it tracks the contract.
                                controller.previewAgxLive(currentJpegSettings)
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
                        if (fromUser) controller.previewAgxLive(
                            currentJpegSettings.copy(agxPurityBoost = purityPercent / 100f)
                        )
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        if (applyJpegOutputSettings(
                            currentJpegSettings.copy(agxPurityBoost = purityPercent / 100f)
                        )) {
                            setStatus("AGX PURITY • $purityPercent%")
                        } else {
                            // Commit refused (saves in flight): revert the label
                            // and drop the live candidate from the VF.
                            purityPercent = (currentJpegSettings.agxPurityBoost * 100f).toInt()
                            seekBar.progress = purityPercent
                            controller.previewAgxLive(currentJpegSettings)
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
            content.addView(TextView(this).apply {
                text = "Highlights: headroom caps the p99.5 spike at white; sky protection caps broad p95 skies; shoulder rolls off near-white into AgX."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(dp(12), dp(8), dp(12), 0)
            })
            addAgxSlider(
                "Highlight headroom", 100,
                ((currentJpegSettings.highlightHeadroom - 0.5f) * 100f).toInt(),
                { String.format(Locale.US, "%.2f", 0.5f + it / 100f) },
                { settings, value -> settings.copy(highlightHeadroom = 0.5f + value / 100f) }
            )
            addAgxSlider(
                "Sky protection", 40,
                ((currentJpegSettings.highlightSoftHeadroom - 0.6f) * 100f).toInt(),
                { String.format(Locale.US, "%.2f", 0.6f + it / 100f) },
                { settings, value -> settings.copy(highlightSoftHeadroom = 0.6f + value / 100f) }
            )
            addAgxSlider(
                "Highlight shoulder", 100,
                (currentJpegSettings.highlightShoulder * 100f).toInt(),
                { "$it%" },
                { settings, value -> settings.copy(highlightShoulder = value / 100f) }
            )
            content.addView(Button(this).apply {
                text = "Reset official AgX Base"
                setOnClickListener {
                    val official = JpegOutputSettings(
                        ultraHdr = currentJpegSettings.ultraHdr,
                        displayP3 = currentJpegSettings.displayP3,
                        adaptiveExposureAuto = currentJpegSettings.adaptiveExposureAuto,
                        adaptiveExposureProgramStrength = currentJpegSettings.adaptiveExposureProgramStrength,
                        highlightHeadroom = currentJpegSettings.highlightHeadroom,
                        highlightSoftHeadroom = currentJpegSettings.highlightSoftHeadroom,
                        highlightShoulder = currentJpegSettings.highlightShoulder,
                        demosaic = currentJpegSettings.demosaic
                    )
                    if (applyJpegOutputSettings(official)) {
                        setStatus("AGX RESET • OFFICIAL BASE")
                        currentTab = 1
                        showJpegTab()
                    }
                }
            })
            markActive(jpegTab)
            polish()
        }
        fun showVideoTab() {
            content.removeAllViews()
            content.addView(sectionTitle("Direct Log"))
            content.addView(CheckBox(this).apply {
                text = "Direct Log video"
                setTextColor(getColor(R.color.text_primary))
                isChecked = directLogEnabled
                setOnCheckedChangeListener { _, enabled ->
                    directLogEnabled = enabled
                    lensPreferences().edit().putBoolean(KEY_DIRECT_LOG, enabled).apply()
                    if (isVideoMode && !videoRecording) {
                        refreshVideoAudioDescription()
                        refreshVideoSensorInfo()
                        rawBadge.text = if (enabled) logBadgeLabel() else "CRS"
                        setStatus(if (enabled) "${directLogProfile().hudLabel} • HEVC 10-BIT" else "CRS • TYPE-7")
                    } else {
                        setStatus(if (enabled) "LOG VIDEO ON" else "LOG VIDEO OFF")
                    }
                }
            })
            content.addView(sectionDesc("HEVC 10-bit MP4 instead of RAW .crs: BT.709, S-Log3, or HLG in this tab or the LOG quick tile. Full sensor, live RAW viewfinder; sound follows the Sound toggle."))
            content.addView(Button(this).apply {
                fun refresh() {
                    text = "LOG profile: ${directLogProfile().hudLabel}"
                }
                refresh()
                setOnClickListener {
                    cycleDirectLogProfile()
                    refresh()
                }
            })
            content.addView(Button(this).apply {
                fun refresh() {
                    text = "LOG frame rate: ${directLogFpsPref()}FPS"
                }
                refresh()
                setOnClickListener {
                    cycleDirectLogFps()
                    refresh()
                }
            })
            content.addView(Button(this).apply {
                fun refresh() {
                    val res = directLogResPref()
                    text = "LOG resolution: ${res.width}x${res.height}"
                }
                refresh()
                setOnClickListener {
                    cycleDirectLogRes()
                    refresh()
                }
            })
            content.addView(Button(this).apply {
                fun refresh() {
                    text = "LOG demosaic: ${videoDemosaic().label}"
                }
                refresh()
                setOnClickListener {
                    val next = videoDemosaic().next()
                    lensPreferences().edit().putString(KEY_VIDEO_DEMOSAIC, next.name).apply()
                    refresh()
                    setStatus("LOG DEMOSAIC • ${next.label} — NEXT TAKE")
                }
            })
            content.addView(sectionDesc("MHC (fused, one dispatch) is the realtime record path. RCD runs the 4-mode split path and drops to a few fps at 4K: A/B comparison only."))
            var logGainEv = directLogGainPref()
            val logGainLabel = TextView(this).apply {
                text = logGainText(logGainEv)
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                setPadding(dp(12), dp(4), dp(12), 0)
            }
            content.addView(logGainLabel)
            content.addView(SeekBar(this).apply {
                max = 60 // -2.0..+4.0 EV stops in 0.1 steps, shown as ×gain
                progress = ((logGainEv + 2f) * 10).toInt().coerceIn(0, max)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        logGainEv = progress / 10f - 2f
                        logGainLabel.text = logGainText(logGainEv)
                        // Live: the camera thread reads exposureEv per frame.
                        logRecorder?.exposureEv = logGainEv
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        lensPreferences().edit().putFloat(KEY_DIRECT_LOG_GAIN, logGainEv).apply()
                        setStatus("LOG GAIN • ${formatDirectLogGain(logGainEv)}")
                    }
                })
            })
            content.addView(sectionDesc("Digital gain folded into the grade (×1.0 = neutral): brightens the log image without touching sensor exposure. Applies live, including mid-take."))
            content.addView(CheckBox(this).apply {
                text = "Video sound"
                setTextColor(getColor(R.color.text_primary))
                isChecked = lensPreferences().getBoolean(KEY_VIDEO_AUDIO, true)
                setOnCheckedChangeListener { button, enabled ->
                    if (enabled == lensPreferences().getBoolean(KEY_VIDEO_AUDIO, true)) return@setOnCheckedChangeListener
                    toggleVideoSound()
                    button.isChecked = lensPreferences().getBoolean(KEY_VIDEO_AUDIO, true)
                }
            })
            content.addView(sectionTitle("RAW video"))
            content.addView(Button(this).apply {
                fun refresh() {
                    text = "CRS crop: ${videoCropShort()}"
                }
                refresh()
                setOnClickListener {
                    cycleVideoCrop()
                    refresh()
                }
            })
            content.addView(sectionDesc("RAW .crs container crop. N/A in Direct Log (full sensor)."))
            content.addView(CheckBox(this).apply {
                text = "Binning (half resolution)"
                setTextColor(getColor(R.color.text_primary))
                isChecked = lensPreferences().getBoolean(KEY_VIDEO_BINNING, false)
                setOnCheckedChangeListener { _, enabled ->
                    lensPreferences().edit().putBoolean(KEY_VIDEO_BINNING, enabled).apply()
                    if (isVideoMode && !videoRecording) refreshVideoSensorInfo()
                    setStatus(if (enabled) "BINNING ON — NEXT TAKE" else "BINNING OFF")
                }
            })
            content.addView(sectionDesc("2x2 sensor binning for smaller, cleaner .crs: averaged R/B and diamond-averaged green. Applies to the next take; N/A in Direct Log."))
            content.addView(sectionTitle("Locks on record"))
            content.addView(CheckBox(this).apply {
                text = "Lock exposure on record"
                setTextColor(getColor(R.color.text_primary))
                isChecked = lensPreferences().getBoolean(VideoRecordLocks.KEY_LOCK_EXPOSURE, false)
                setOnCheckedChangeListener { _, enabled ->
                    lensPreferences().edit().putBoolean(VideoRecordLocks.KEY_LOCK_EXPOSURE, enabled).apply()
                    setStatus(if (enabled) "AE LOCK ON RECORD" else "AE CONTINUOUS IN TAKES")
                }
            })
            content.addView(CheckBox(this).apply {
                text = "Lock white balance on record"
                setTextColor(getColor(R.color.text_primary))
                isChecked = lensPreferences().getBoolean(VideoRecordLocks.KEY_LOCK_WB, false)
                setOnCheckedChangeListener { _, enabled ->
                    lensPreferences().edit().putBoolean(VideoRecordLocks.KEY_LOCK_WB, enabled).apply()
                    setStatus(if (enabled) "AWB LOCK ON RECORD" else "AWB CONTINUOUS IN TAKES")
                }
            })
            content.addView(CheckBox(this).apply {
                text = "Lock focus on record"
                setTextColor(getColor(R.color.text_primary))
                isChecked = lensPreferences().getBoolean(VideoRecordLocks.KEY_LOCK_FOCUS, false)
                setOnCheckedChangeListener { _, enabled ->
                    lensPreferences().edit().putBoolean(VideoRecordLocks.KEY_LOCK_FOCUS, enabled).apply()
                    setStatus(if (enabled) "AF LOCK ON RECORD" else "AF CONTINUOUS IN TAKES")
                }
            })
            content.addView(sectionDesc("Freeze the preview AE/AWB/AF at REC (manual tiles always hold). Off = continuous auto during takes. SS/ISO/WB/focus/EV tiles stay live before and during takes."))
            content.addView(sectionTitle("Stabilization"))
            content.addView(Button(this).apply {
                fun refresh() {
                    text = "Stabilized output: ${stabOutputMode().label.uppercase(Locale.US)}"
                }
                refresh()
                setOnClickListener {
                    val next = stabOutputMode().next()
                    lensPreferences().edit().putString(KEY_VIDEO_STAB_OUTPUT, next.name).apply()
                    refresh()
                    setStatus("STAB OUTPUT • ${next.label.uppercase(Locale.US)} — NEXT TAKE")
                }
            })
            content.addView(sectionDesc("Post-record gyro stabilization (Direct Log only): keep the original take, the stabilized re-encode, or both. The warp pass runs on-device right after stop; any failure keeps the original."))
            markActive(videoTab)
            polish()
        }
        fun showExposureTab() {
            rawZslSettingsStatus = null
            sidecarSettingsStatus = null
            content.removeAllViews()
            content.addView(sectionTitle("HDR brackets"))
            content.addView(sectionDesc("Merged HDR saves one DNG by default; optionally keep every bracket. Frame-level debug output lives under the Debug tab."))
            content.addView(CheckBox(this).apply {
                text = "HDR: save all 3 brackets as separate DNGs (do not merge)"
                setTextColor(getColor(R.color.text_primary))
                isChecked = lensPreferences().getBoolean(KEY_HDR_SAVE_EACH_BRACKET, false)
                setOnCheckedChangeListener { _, enabled ->
                    lensPreferences().edit().putBoolean(KEY_HDR_SAVE_EACH_BRACKET, enabled).apply()
                    setStatus(if (lensPreferences().getBoolean(KEY_HDR_SAVE_DEBUG_FRAMES, false))
                        "HDR DBG ×3"
                    else if (enabled) "HDR ×3 DNG" else "HDR MERGED")
                    preloadFlowNetForMergedHdr()
                    updateQuickControls()
                }
            })
            content.addView(CheckBox(this).apply {
                text = "HDR bracket range: −4 / 0 / +4 EV"
                setTextColor(getColor(R.color.text_primary))
                isChecked = hdrBracketStops() == 4
                setOnCheckedChangeListener { _, enabled ->
                    val stops = if (enabled) 4 else 2
                    lensPreferences().edit().putInt(KEY_HDR_BRACKET_STOPS, stops).apply()
                    setStatus("HDR ±${stops} EV")
                    updateQuickControls()
                }
            })
            content.addView(CheckBox(this).apply {
                text = "Sequential trio 0 / −2 / 0 EV (shadow averaging + highlights)"
                setTextColor(getColor(R.color.text_primary))
                isChecked = seqTrioEnabled()
                setOnCheckedChangeListener { _, enabled ->
                    lensPreferences().edit().putBoolean(KEY_SEQ_TRIO_ENABLED, enabled).apply()
                    setStatus(if (enabled) "SEQ TRIO 0/−2/0 ON" else "SEQ TRIO OFF")
                    updateQuickControls()
                }
            })
            content.addView(TextView(this).apply {
                text = "Two 0 EV frames average shadows (~3 dB); the −2 EV frame " +
                    "rescues highlights. Same ISO, lightweight local alignment. " +
                    "Needs the HDR toggle on; overrides the ±2/±4 bracket range."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
            })
            content.addView(sectionTitle("Face priority"))
            content.addView(sectionDesc("HAL face detection steers AF/AE to the largest face (sand box). Tap targets override faces; manual sensor modes ignore the regions but still show boxes."))
            content.addView(CheckBox(this).apply {
                text = "Face-priority AF/AE"
                setTextColor(getColor(R.color.text_primary))
                isChecked = lensPreferences().getBoolean(KEY_FACE_PRIORITY, false)
                setOnCheckedChangeListener { _, enabled ->
                    lensPreferences().edit().putBoolean(KEY_FACE_PRIORITY, enabled).apply()
                    controller.setFacePriorityEnabled(enabled)
                    setStatus(
                        if (!enabled) "FACE OFF"
                        else if (controller.isFaceDetectSupported()) "FACE ON" else "FACE N/A"
                    )
                }
            })
            markActive(exposureTab)
            polish()
        }
        fun showBurstTab() {
            content.removeAllViews()
            content.addView(sectionTitle("Offline burst merges"))
            content.addView(sectionDesc(
                "Staged IMG_<ts>_BURST.crs takes merge here later via MFSR or HDR+, " +
                    "to DNG, JPEG, or both. RAW video never lists."
            ))
            content.addView(settingsButton("OPEN MERGES").apply {
                setOnClickListener { openMerges() }
            })
            content.addView(CheckBox(this).apply {
                text = "Save ZSL bursts to .crs for offline merge"
                setTextColor(getColor(R.color.text_primary))
                isChecked = burstCrsEnabled
                setOnCheckedChangeListener { _, enabled ->
                    if (enabled != burstCrsEnabled) setBurstCrsEnabled(enabled)
                }
            })
            content.addView(sectionDesc(
                "When on, the shutter dumps the whole ZSL ring to one " +
                    "IMG_<ts>_BURST.crs instead of merging immediately."
            ))
            content.addView(sectionTitle("Zero shutter lag"))
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
            content.addView(sectionDesc("Keeps full-resolution RAW frames in camera memory. Unsupported devices fall back to normal RAW."))
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
                        setStatus("ZSL ×$selectedFrameCount")
                    }
                })
            })
            var hybridTopup = lensPreferences().getBoolean(KEY_ZSL_HYBRID_TOPUP, true)
            content.addView(Button(this).apply {
                text = zslHybridTopupText(hybridTopup)
                setOnClickListener {
                    hybridTopup = !hybridTopup
                    lensPreferences().edit().putBoolean(KEY_ZSL_HYBRID_TOPUP, hybridTopup).apply()
                    controller.setZslHybridTopup(hybridTopup)
                    text = zslHybridTopupText(hybridTopup)
                    setStatus(if (hybridTopup) "ZSL TOP-UP ON" else "ZSL TOP-UP OFF")
                }
            })
            // Future SR hook: SR settings rows here (merge enable checkbox, DNG mode,
            // sharpest reference, kernel preset, mosaic scale, linear scale).
            content.addView(sectionTitle("HDR+ merge"))
            content.addView(sectionDesc(
                "Noise-reduction strength " +
                    "${HdrPlusSettings.MIN_STRENGTH.toInt()}–${HdrPlusSettings.MAX_STRENGTH.toInt()}. " +
                    "Higher merges harder: cleaner stills, more ghosting on motion. " +
                    "AUTO picks ${HdrPlusAutoTuning.AUTO_STRENGTH_MIN.toInt()}–" +
                    "${HdrPlusAutoTuning.AUTO_STRENGTH_MAX.toInt()} from scene motion " +
                    "and ignores the slider."
            ))
            fun strengthRowText(value: Float): String =
                HdrPlusSettings.strengthSliderText(value) +
                    if (hdrPlusSettings.autoStrength) " • AUTO" else ""
            var selectedStrength = hdrPlusSettings.strength
            val strengthLabel = TextView(this).apply {
                text = strengthRowText(selectedStrength)
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                setPadding(dp(12), dp(8), dp(12), 0)
            }
            content.addView(settingsCheck("Auto strength", hdrPlusSettings.autoStrength).apply {
                setOnCheckedChangeListener { _, checked ->
                    val applied = applyHdrPlusSettings(
                        hdrPlusSettings.copy(autoStrength = checked)
                    )
                    if (applied) {
                        strengthLabel.text = strengthRowText(selectedStrength)
                        setStatus(if (checked) "HDR+ STRENGTH • AUTO" else "HDR+ STRENGTH • MANUAL")
                    } else {
                        isChecked = hdrPlusSettings.autoStrength
                    }
                }
            })
            content.addView(strengthLabel)
            content.addView(SeekBar(this).apply {
                max = HdrPlusSettings.STRENGTH_SLIDER_STEPS
                progress = HdrPlusSettings.strengthToProgress(selectedStrength)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        selectedStrength = HdrPlusSettings.progressToStrength(progress)
                        strengthLabel.text = strengthRowText(selectedStrength)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        val applied = applyHdrPlusSettings(
                            hdrPlusSettings.copy(autoStrength = false, strength = selectedStrength)
                        )
                        if (applied) {
                            strengthLabel.text = strengthRowText(selectedStrength)
                            setStatus(
                                String.format(Locale.US, "HDR+ STRENGTH • %.1f", selectedStrength)
                            )
                        } else {
                            selectedStrength = hdrPlusSettings.strength
                            seekBar.progress =
                                HdrPlusSettings.strengthToProgress(selectedStrength)
                            strengthLabel.text = strengthRowText(selectedStrength)
                        }
                    }
                })
            })
            rawZslSettingsStatus = TextView(this).apply {
                text = rawZslSettingsText(rawZslStatus)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(dp(12), 0, dp(12), dp(16))
            }.also(content::addView)
            content.addView(sectionTitle("Burst gyro sidecars"))
            sidecarSettingsStatus = TextView(this).apply {
                text = sidecarFolderText()
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
            }.also(content::addView)
            content.addView(Button(this).apply {
                text = "Save sidecars next to DNGs (choose photo folder)"
                setOnClickListener { pickSidecarFolder() }
            })
            content.addView(TextView(this).apply {
                text = "Android forbids text sidecars under DCIM via MediaStore. " +
                    "RawLens asks for the photo folder on first install; choose " +
                    "DCIM/RawLens so burst.json + gyro/ land in " +
                    "DCIM/RawLens/<burst> next to the DNGs. Use the button above " +
                    "to re-grant after a reinstall or a revoked grant — no other " +
                    "permission is needed."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
                setPadding(0, 0, 0, dp(16))
            })
            markActive(burstTab)
            polish()
        }
        fun showDenoiseTab() {
            rawZslSettingsStatus = null
            sidecarSettingsStatus = null
            content.removeAllViews()
            var settings = denoiseSettings()
            val aiSubordinate = ArrayList<View>()

            content.addView(sectionTitle("AI RAW denoise (RawNIND Bayer)"))

            val aiStatus = TextView(this).apply {
                text = aiModelStatus()
                setTextColor(getColor(R.color.text_secondary)); textSize = 12f
                setPadding(dp(12), dp(10), dp(12), 0)
            }
            val aiMaster = CheckBox(this).apply {
                text = "AI RAW denoise (RawNIND Bayer)\nOn writes a denoised DNG and develops the JPEG from it. Needs the trained model files in assets."
                setTextColor(getColor(R.color.text_primary))
                isChecked = settings.aiEnabled
                setOnCheckedChangeListener { button, value ->
                    val proposed = settings.copy(aiEnabled = value)
                    if (persistDenoiseSettings(proposed)) {
                        settings = proposed
                        if (value) preloadRawNindIfEnabled()
                        aiStatus.text = aiModelStatus()
                        aiSubordinate.forEach { it.isEnabled = settings.aiEnabled }
                    } else if (button.isChecked != settings.aiEnabled) {
                        button.isChecked = settings.aiEnabled
                    }
                }
            }
            val keepOriginal = CheckBox(this).apply {
                text = "Save original DNG alongside\nOff halves storage; the untouched sensor DNG is skipped."
                setTextColor(getColor(R.color.text_primary))
                isChecked = settings.saveOriginalDng
                setOnCheckedChangeListener { button, value ->
                    val proposed = settings.copy(saveOriginalDng = value)
                    if (persistDenoiseSettings(proposed)) {
                        settings = proposed
                    } else if (button.isChecked != settings.saveOriginalDng) {
                        button.isChecked = settings.saveOriginalDng
                    }
                }
            }
            content.addView(aiMaster); content.addView(aiStatus); content.addView(keepOriginal)
            aiSubordinate += keepOriginal
            var aiStrengthPct = (settings.aiStrength * 100 + 0.5f).toInt().coerceIn(0, 100)
            val aiStrengthLabel = TextView(this).apply {
                text = "AI strength: $aiStrengthPct%"
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                setPadding(dp(12), dp(8), dp(12), 0)
            }
            content.addView(aiStrengthLabel)
            val aiStrengthBar = SeekBar(this).apply {
                max = 100
                progress = aiStrengthPct
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        aiStrengthPct = progress
                        aiStrengthLabel.text = "AI strength: $aiStrengthPct%"
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        val proposed = settings.copy(aiStrength = aiStrengthPct / 100f)
                        if (persistDenoiseSettings(proposed)) {
                            settings = proposed
                            setStatus("AI STRENGTH • $aiStrengthPct%")
                        } else {
                            aiStrengthPct = (settings.aiStrength * 100 + 0.5f).toInt()
                            seekBar.progress = aiStrengthPct
                        }
                    }
                })
            }
            content.addView(aiStrengthBar)
            content.addView(TextView(this).apply {
                text = "0% = untouched source, 100% = full AI output (darktable neural-restore semantics; moving the slider never re-runs the model)."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(dp(12), dp(4), dp(12), dp(8))
            })
            aiSubordinate += aiStrengthLabel
            aiSubordinate += aiStrengthBar
            aiSubordinate.forEach { it.isEnabled = settings.aiEnabled }

            content.addView(sectionTitle("Classical denoise (GALOSH Vulkan)"))
            var galosh = galoshSettings()
            val galoshSubordinate = ArrayList<View>()
            val galoshMaster = CheckBox(this).apply {
                text = "GALOSH RAW denoise (Vulkan)\nOn writes a denoised _GALOSH DNG and develops the JPEG from it. Wins over AI denoise when both are on."
                setTextColor(getColor(R.color.text_primary))
                isChecked = galosh.enabled
                setOnCheckedChangeListener { button, value ->
                    val proposed = galosh.copy(enabled = value)
                    if (persistGaloshSettings(proposed)) {
                        galosh = proposed
                        galoshSubordinate.forEach { it.isEnabled = galosh.enabled }
                        // Enable-time preload: create the Vulkan device off
                        // the capture path so the first shot stays fast.
                        if (galosh.enabled) controllerIfReady?.prewarmGalosh()
                    } else if (button.isChecked != galosh.enabled) {
                        button.isChecked = galosh.enabled
                    }
                }
            }
            val galoshKeepOriginal = CheckBox(this).apply {
                text = "Save original DNG alongside\nOff halves storage; the untouched sensor DNG is skipped."
                setTextColor(getColor(R.color.text_primary))
                isChecked = galosh.saveOriginalDng
                setOnCheckedChangeListener { button, value ->
                    val proposed = galosh.copy(saveOriginalDng = value)
                    if (persistGaloshSettings(proposed)) {
                        galosh = proposed
                    } else if (button.isChecked != galosh.saveOriginalDng) {
                        button.isChecked = galosh.saveOriginalDng
                    }
                }
            }
            content.addView(galoshMaster); content.addView(galoshKeepOriginal)
            galoshSubordinate += galoshKeepOriginal
            fun addGaloshSlider(
                title: String,
                maximum: Int,
                initial: Int,
                update: (GaloshSettings, Int) -> GaloshSettings
            ) {
                var selected = initial.coerceIn(0, maximum)
                val label = TextView(this).apply {
                    text = "$title: $selected%"
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 14f
                    setPadding(dp(12), dp(8), dp(12), 0)
                }
                content.addView(label)
                val bar = SeekBar(this).apply {
                    max = maximum
                    progress = selected
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                            selected = progress
                            label.text = "$title: $selected%"
                        }
                        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                        override fun onStopTrackingTouch(seekBar: SeekBar) {
                            val proposed = update(galosh, selected)
                            if (persistGaloshSettings(proposed)) {
                                galosh = proposed
                                setStatus("GALOSH ${title.uppercase(Locale.US)} • $selected%")
                            } else {
                                selected = initial.coerceIn(0, maximum)
                                seekBar.progress = selected
                            }
                        }
                    })
                }
                content.addView(bar)
                galoshSubordinate += label
                galoshSubordinate += bar
            }
            addGaloshSlider("Strength", 100, (galosh.strength * 100 + 0.5f).toInt()) { s, v ->
                s.copy(strength = v / 100f)
            }
            addGaloshSlider("Luma", 200, (galosh.luma * 100 + 0.5f).toInt()) { s, v ->
                s.copy(luma = v / 100f)
            }
            addGaloshSlider("Chroma", 200, (galosh.chroma * 100 + 0.5f).toInt()) { s, v ->
                s.copy(chroma = v / 100f)
            }
            content.addView(TextView(this).apply {
                text = "Strength 0% keeps the source CFA. Luma/chroma 100% is neutral; noise model is always blind-fit."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(dp(12), dp(4), dp(12), dp(4))
            })
            val galoshFast = CheckBox(this).apply {
                text = "Fast chroma upsample (guided bilinear)\nFaster, slightly softer chroma than the K16 path."
                setTextColor(getColor(R.color.text_primary))
                isChecked = galosh.fastUpsample
                setOnCheckedChangeListener { button, value ->
                    val proposed = galosh.copy(fastUpsample = value)
                    if (persistGaloshSettings(proposed)) {
                        galosh = proposed
                    } else if (button.isChecked != galosh.fastUpsample) {
                        button.isChecked = galosh.fastUpsample
                    }
                }
            }
            content.addView(galoshFast)
            galoshSubordinate += galoshFast
            val galoshFastMode = CheckBox(this).apply {
                text = "Fast mode (4-phase WHT)\nAbout 4x faster denoise, slightly less smooth. Recommended on this device."
                setTextColor(getColor(R.color.text_primary))
                isChecked = galosh.fastMode
                setOnCheckedChangeListener { button, value ->
                    val proposed = galosh.copy(fastMode = value)
                    if (persistGaloshSettings(proposed)) {
                        galosh = proposed
                    } else if (button.isChecked != galosh.fastMode) {
                        button.isChecked = galosh.fastMode
                    }
                }
            }
            content.addView(galoshFastMode)
            galoshSubordinate += galoshFastMode
            galoshSubordinate.forEach { it.isEnabled = galosh.enabled }

            content.addView(sectionTitle("Profiled wavelet (Vulkan)"))
            val waveSubordinate = ArrayList<View>()
            val waveMaster = CheckBox(this).apply {
                text = "Wavelet luma+chroma denoise (Vulkan)\nPost-demosaic darktable-profiled wavelets on camera-linear RGB; JPEG only, never the DNG. Off when Galosh is on (mutual exclusion)."
                setTextColor(getColor(R.color.text_primary))
                isChecked = settings.wavelet.enabled
                setOnCheckedChangeListener { button, value ->
                    val proposed = settings.copy(wavelet = settings.wavelet.copy(enabled = value))
                    if (persistDenoiseSettings(proposed)) {
                        settings = proposed
                        waveSubordinate.forEach { it.isEnabled = settings.wavelet.enabled }
                        if (settings.wavelet.enabled) controllerIfReady?.prewarmWavelet()
                    } else if (button.isChecked != settings.wavelet.enabled) {
                        button.isChecked = settings.wavelet.enabled
                    }
                }
            }
            content.addView(waveMaster)
            fun addWaveSlider(
                title: String,
                maximum: Int,
                initial: Int,
                format: (Int) -> String,
                update: (WaveletSettings, Int) -> WaveletSettings
            ) {
                var selected = initial.coerceIn(0, maximum)
                val label = TextView(this).apply {
                    text = "$title: ${format(selected)}"
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 14f
                    setPadding(dp(12), dp(8), dp(12), 0)
                }
                content.addView(label)
                val bar = SeekBar(this).apply {
                    max = maximum
                    progress = selected
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                            selected = progress
                            label.text = "$title: ${format(selected)}"
                        }
                        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                        override fun onStopTrackingTouch(seekBar: SeekBar) {
                            val proposed = settings.copy(wavelet = update(settings.wavelet, selected))
                            if (persistDenoiseSettings(proposed)) {
                                settings = proposed
                                setStatus("WAVELET ${title.uppercase(Locale.US)} • ${format(selected)}")
                            } else {
                                selected = initial.coerceIn(0, maximum)
                                seekBar.progress = selected
                            }
                        }
                    })
                }
                content.addView(bar)
                waveSubordinate += label
                waveSubordinate += bar
            }
            addWaveSlider("Strength", 200, (settings.wavelet.strength * 100 + 0.5f).toInt(),
                { "$it%" }) { s, v -> s.copy(strength = v / 100f) }
            addWaveSlider("Luma", 100, (settings.wavelet.luma * 100 + 0.5f).toInt(),
                { "$it%" }) { s, v -> s.copy(luma = v / 100f) }
            addWaveSlider("Scales", 7, settings.wavelet.maxScale,
                { if (it <= 1) "$it scale" else "$it scales" }) { s, v -> s.copy(maxScale = v.coerceAtLeast(1)) }
            content.addView(TextView(this).apply {
                text = "Strength 100% and 7 scales are the validated defaults; luma 25% keeps fine detail while chroma (fixed 75%) does the heavy lifting. Noise comes from the sensor profile, so higher ISO denoises harder automatically."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(dp(12), dp(4), dp(12), dp(4))
            })
            waveSubordinate.forEach { it.isEnabled = settings.wavelet.enabled }

            markActive(denoiseTab)
            polish()
        }
        fun showLensesTab() {
            rawZslSettingsStatus = null
            sidecarSettingsStatus = null
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
            content.addView(sectionTitle("PROGRAM custom AE"))
            content.addView(sectionDesc("RAW-driven per-lens sensor ISO + shutter. Full per-lens editor (priority, locks, bounds, bias) mirrors the PROGRAM panel; ISO/S chips open it too."))
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
                text = "PROGRAM custom AE (RAW-driven)"
                setTextColor(getColor(R.color.text_primary))
                isChecked = dynamicSettings.enabled
                setOnCheckedChangeListener { _, enabled ->
                    dynamicSettings = dynamicSettings.copy(enabled = enabled)
                    applyDynamicSettings()
                }
            })
            content.addView(TextView(this).apply {
                text = "PROGRAM custom AE is RAW-driven per-lens (sensor ISO + shutter). " +
                    "Hold ISO/SHUTTER chips to lock, tap for slider, Auto releases both. " +
                    "Cameras without manual sensor control keep using Android AE. " +
                    "Use ISO/S chips for the full editor; ETTR overrides PROGRAM when converged."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
            })
            if (!controller.hasManualSensorControl()) {
                content.addView(TextView(this).apply {
                    text = "Android AE • this camera has no manual sensor control; " +
                        "PROGRAM locks and limits are unavailable."
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 12f
                })
            }
            var programSettings = programProfile()
            fun applyProgramSettings() = saveProgramProfile(programSettings)
            val programBalanceLabel = TextView(this).apply {
                text = programBalanceText(programSettings.balance)
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                setPadding(dp(12), dp(4), dp(12), 0)
            }
            content.addView(programBalanceLabel)
            content.addView(SeekBar(this).apply {
                max = 100
                progress = (programSettings.balance * 100).toInt().coerceIn(0, max)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        programSettings = programSettings.copy(balance = progress / 100f)
                        programBalanceLabel.text = programBalanceText(programSettings.balance)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = applyProgramSettings()
                })
            })
            val programIsoMinButton = Button(this)
            val programIsoMaxButton = Button(this)
            val programShutterMinButton = Button(this)
            val programShutterMaxButton = Button(this)
            val programLockButton = Button(this)
            val programMeteringButton = Button(this)
            val programBiasLabel = TextView(this).apply {
                text = programEvBiasText(programSettings.evBias)
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
            }
            fun refreshProgramButtons() {
                programIsoMinButton.text = programIsoBoundText(programSettings.isoMin, isMin = true)
                programIsoMaxButton.text = programIsoBoundText(programSettings.isoMax, isMin = false)
                programShutterMinButton.text = programShutterBoundText(
                    programSettings.shutterMinNanos, isMin = true, autoSafe = false)
                programShutterMaxButton.text = programShutterBoundText(
                    programSettings.shutterMaxNanos, isMin = false,
                    autoSafe = programSettings.useAutoSafeShutter)
                programLockButton.text = programLockText(programSettings.lockMode)
                programBiasLabel.text = programEvBiasText(programSettings.evBias)
                programMeteringButton.text =
                    "Metering: ${programMeteringLabel(programSettings.metering)}"
                val manualSensor = controller.hasManualSensorControl()
                programLockButton.isEnabled = manualSensor
                programIsoMinButton.isEnabled = manualSensor
                programIsoMaxButton.isEnabled = manualSensor
                programShutterMinButton.isEnabled = manualSensor
                programShutterMaxButton.isEnabled = manualSensor
                programMeteringButton.isEnabled = manualSensor
            }
            programLockButton.setOnClickListener {
                programSettings = seedLockedValuesFor(programSettings.copy(lockMode = when (programSettings.lockMode) {
                    ProgramLockMode.NONE -> ProgramLockMode.ISO_LOCK
                    ProgramLockMode.ISO_LOCK -> ProgramLockMode.SHUTTER_LOCK
                    ProgramLockMode.SHUTTER_LOCK -> ProgramLockMode.NONE
                }))
                refreshProgramButtons(); applyProgramSettings()
            }
            programIsoMinButton.setOnClickListener {
                val values = listOf(0, 100, 200, 400, 800)
                programSettings = programSettings.copy(isoMin = values[
                    (values.indexOf(programSettings.isoMin).takeIf { it >= 0 } ?: 0).let { (it + 1) % values.size }])
                refreshProgramButtons(); applyProgramSettings()
            }
            programIsoMaxButton.setOnClickListener {
                val values = listOf(0, 400, 800, 1600, 3200, 6400)
                programSettings = programSettings.copy(isoMax = values[
                    (values.indexOf(programSettings.isoMax).takeIf { it >= 0 } ?: 0).let { (it + 1) % values.size }])
                refreshProgramButtons(); applyProgramSettings()
            }
            programShutterMinButton.setOnClickListener {
                val values = listOf(0L, 1_000_000_000L / 1000, 1_000_000_000L / 500,
                    1_000_000_000L / 250, 1_000_000_000L / 125, 1_000_000_000L / 60)
                programSettings = programSettings.copy(shutterMinNanos = values[
                    (values.indexOf(programSettings.shutterMinNanos).takeIf { it >= 0 } ?: 0).let { (it + 1) % values.size }])
                refreshProgramButtons(); applyProgramSettings()
            }
            programShutterMaxButton.setOnClickListener {
                val values = listOf(0L, 1_000_000_000L / 15, 1_000_000_000L / 30,
                    1_000_000_000L / 60, 1_000_000_000L / 125, 1_000_000_000L / 250)
                if (programSettings.useAutoSafeShutter) {
                    programSettings = programSettings.copy(useAutoSafeShutter = false, shutterMaxNanos = 0L)
                } else {
                    val next = values[(values.indexOf(programSettings.shutterMaxNanos).takeIf { it >= 0 } ?: 0)
                        .let { (it + 1) % values.size }]
                    programSettings = programSettings.copy(
                        shutterMaxNanos = next, useAutoSafeShutter = next == 0L)
                }
                refreshProgramButtons(); applyProgramSettings()
            }
            refreshProgramButtons()
            content.addView(programLockButton)
            content.addView(programMeteringButton)
            programMeteringButton.setOnClickListener {
                programSettings = programSettings.copy(metering = nextProgramMetering(programSettings.metering))
                refreshProgramButtons(); applyProgramSettings()
            }
            content.addView(programIsoMinButton)
            content.addView(programIsoMaxButton)
            content.addView(programShutterMinButton)
            content.addView(programShutterMaxButton)
            content.addView(programBiasLabel)
            content.addView(SeekBar(this).apply {
                max = 60
                progress = ((programSettings.evBias - ProgramAeProfile.MIN_EV_BIAS) * 20f).toInt().coerceIn(0, max)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        programSettings = programSettings.copy(
                            evBias = ProgramAeProfile.MIN_EV_BIAS + progress / 20f)
                        programBiasLabel.text = programEvBiasText(programSettings.evBias)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = applyProgramSettings()
                })
            })
            var ettr = ettrSettings()
            fun applyEttr() = saveEttrSettings(ettr)
            content.addView(CheckBox(this).apply {
                text = "ETTR single exposure (RAW-measured)"
                setTextColor(getColor(R.color.text_primary))
                isChecked = ettr.enabled
                setOnCheckedChangeListener { _, enabled ->
                    ettr = ettr.copy(enabled = enabled)
                    applyEttr()
                }
            })
            content.addView(TextView(this).apply {
                text = "When the gain ceiling binds, ETTR keeps the hand-motion " +
                    "shutter cap and accepts a darker frame instead of trading noise for blur."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
            })
            val ettrHeadroomButton = Button(this)
            val ettrIsoLimitButton = Button(this)
            fun refreshEttrButtons() {
                ettrHeadroomButton.text = ettrHeadroomText(ettr.headroomEv)
                ettrIsoLimitButton.text = ettrIsoLimitText(ettr.isoLimit)
            }
            ettrHeadroomButton.setOnClickListener {
                val values = floatArrayOf(0f, 0.3f, 0.5f, 1f)
                val next = (values.indexOfFirst { it == ettr.headroomEv }.takeIf { it >= 0 } ?: 0)
                ettr = ettr.copy(headroomEv = values[(next + 1) % values.size])
                applyEttr(); refreshEttrButtons()
            }
            ettrIsoLimitButton.setOnClickListener {
                val values = intArrayOf(0, 400, 800, 1600, 3200, 6400)
                val next = (values.indexOf(ettr.isoLimit).takeIf { it >= 0 } ?: 0)
                ettr = ettr.copy(isoLimit = values[(next + 1) % values.size])
                applyEttr(); refreshEttrButtons()
            }
            refreshEttrButtons()
            content.addView(ettrHeadroomButton)
            content.addView(ettrIsoLimitButton)
            markActive(lensesTab)
            polish()
        }
        fun showDebugTab() {
            rawZslSettingsStatus = null
            sidecarSettingsStatus = null
            content.removeAllViews()
            content.addView(sectionTitle("HDR debug frames"))
            content.addView(CheckBox(this).apply {
                text = "Save debug HDR frames"
                setTextColor(getColor(R.color.text_primary))
                isChecked = lensPreferences().getBoolean(KEY_HDR_SAVE_DEBUG_FRAMES, false)
                setOnCheckedChangeListener { _, enabled ->
                    lensPreferences().edit().putBoolean(KEY_HDR_SAVE_DEBUG_FRAMES, enabled).apply()
                    setStatus(if (enabled) "HDR DBG ×3"
                        else "HDR DBG OFF")
                    preloadFlowNetForMergedHdr()
                    updateQuickControls()
                }
            })
            content.addView(TextView(this).apply {
                text = "Debug saves all 3 source DNGs and a merged DNG with matching filenames, plus JPEG when selected. Overrides do not merge; uses extra storage."
                setTextColor(getColor(R.color.text_primary))
            })
            content.addView(sectionTitle("Camera diagnostics"))
            // The two debug overlays stack on the same corner: enabling one
            // switches the other off. The guard stops the programmatic
            // uncheck from ping-ponging back through the other listener.
            var overlayExclusionGuard = false
            var cameraDebugCheckBox: CheckBox? = null
            var rawVfDebugCheckBox: CheckBox? = null
            content.addView(CheckBox(this).apply {
                text = "Camera2 debug overlay"
                setTextColor(getColor(R.color.text_primary))
                isChecked = lensPreferences().getBoolean(KEY_DEBUG_OVERLAY, false)
                cameraDebugCheckBox = this
                setOnCheckedChangeListener { _, enabled ->
                    if (overlayExclusionGuard) return@setOnCheckedChangeListener
                    lensPreferences().edit().putBoolean(KEY_DEBUG_OVERLAY, enabled).apply()
                    debugOverlay.visibility = if (enabled) View.VISIBLE else View.GONE
                    if (enabled) {
                        overlayExclusionGuard = true
                        try {
                            lensPreferences().edit().putBoolean(KEY_RAW_VF_DEBUG_OVERLAY, false).apply()
                            rawVfDebugCheckBox?.isChecked = false
                            rawVfDebugOverlay.visibility = View.GONE
                        } finally {
                            overlayExclusionGuard = false
                        }
                        debugOverlay.post { positionWholeRotatedPanels() }
                    }
                }
            })
            content.addView(CheckBox(this).apply {
                text = "RAW viewfinder debug overlay"
                setTextColor(getColor(R.color.text_primary))
                isChecked = lensPreferences().getBoolean(KEY_RAW_VF_DEBUG_OVERLAY, false)
                rawVfDebugCheckBox = this
                setOnCheckedChangeListener { _, enabled ->
                    if (overlayExclusionGuard) return@setOnCheckedChangeListener
                    lensPreferences().edit().putBoolean(KEY_RAW_VF_DEBUG_OVERLAY, enabled).apply()
                    rawVfDebugOverlay.visibility = if (enabled) View.VISIBLE else View.GONE
                    if (enabled) {
                        overlayExclusionGuard = true
                        try {
                            lensPreferences().edit().putBoolean(KEY_DEBUG_OVERLAY, false).apply()
                            cameraDebugCheckBox?.isChecked = false
                            debugOverlay.visibility = View.GONE
                        } finally {
                            overlayExclusionGuard = false
                        }
                        rawVfDebugOverlay.post { positionWholeRotatedPanels() }
                    }
                }
            })
            content.addView(sectionTitle("App log"))
            content.addView(CheckBox(this).apply {
                text = "Save app log to file (survives crashes)"
                setTextColor(getColor(R.color.text_primary))
                isChecked = lensPreferences().getBoolean(KEY_LOGCAT_FILE, true)
                setOnCheckedChangeListener { _, enabled ->
                    lensPreferences().edit().putBoolean(KEY_LOGCAT_FILE, enabled).apply()
                    if (enabled) LogcatFileWriter.start(this@MainActivity)
                    else LogcatFileWriter.stop()
                }
            })
            content.addView(Button(this).apply {
                text = "Share latest log"
                setOnClickListener { shareLatestLog() }
            })
            content.addView(TextView(this).apply {
                text = LogcatFileWriter.statusLine()
                setTextColor(getColor(R.color.text_primary))
                textSize = 11f
                setPadding(0, dp(4), 0, 0)
            })
            content.addView(TextView(this).apply {
                text = "The app's own logcat streams to its private folder and is " +
                    "mirrored to Download/RawLens/logs/ by itself (no permission " +
                    "needed, no taps): 8 MB sessions, newest 5 kept, crashes " +
                    "appended before the process dies. After a crash on launch, " +
                    "the next start re-exports that session and offers to share it."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
            })
            markActive(debugTab)
            polish()
        }
        fun showAboutTab() {
            rawZslSettingsStatus = null
            sidecarSettingsStatus = null
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
            // F-Droid builds leave updates to the store: no checker UI.
            if (BuildConfig.ENABLE_UPDATE_CHECK) {
            content.addView(Button(this).apply {
                text = "Check for updates"
                setOnClickListener { updateFlow.checkManually() }
            })
            content.addView(CheckBox(this).apply {
                text = "Check for updates on startup"
                setTextColor(getColor(R.color.text_primary))
                isChecked = lensPreferences().getBoolean(UpdateChecker.KEY_AUTO_CHECK, true)
                setOnCheckedChangeListener { _, enabled ->
                    lensPreferences().edit()
                        .putBoolean(UpdateChecker.KEY_AUTO_CHECK, enabled)
                        .apply()
                }
            })
            content.addView(TextView(this).apply {
                text = "Compares the installed version against the latest " +
                    "GitHub release (matthew777777/RawLens), at most once a " +
                    "day. Download fetches the release APK in the app and " +
                    "opens the system installer; nothing is uploaded."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
            })
            }
            markActive(aboutTab)
            polish()
        }
        val tabPages: List<() -> Unit> = listOf(
            { showGeneralTab() },
            { showJpegTab() },
            { showVideoTab() },
            { showExposureTab() },
            { showBurstTab() },
            { showDenoiseTab() },
            { showLensesTab() },
            { showDebugTab() },
            { showAboutTab() }
        )
        fun goTab(index: Int) {
            val clamped = index.coerceIn(tabPages.indices)
            val changed = clamped != currentTab
            currentTab = clamped
            tabPages[clamped]()
            if (changed) content.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            // Keep the top tab strip following the page (taps and swipes alike)
            // so the active tab is always visible, centered when possible.
            tabRow.post {
                val active = allTabs[clamped]
                val centered = active.left - (tabScroll.width - active.width) / 2
                tabScroll.smoothScrollTo(centered.coerceAtLeast(0), 0)
            }
        }
        generalTab.setOnClickListener { goTab(0) }
        jpegTab.setOnClickListener { goTab(1) }
        videoTab.setOnClickListener { goTab(2) }
        exposureTab.setOnClickListener { goTab(3) }
        burstTab.setOnClickListener { goTab(4) }
        denoiseTab.setOnClickListener { goTab(5) }
        lensesTab.setOnClickListener { goTab(6) }
        debugTab.setOnClickListener { goTab(7) }
        aboutTab.setOnClickListener { goTab(8) }
        // Swipe left/right anywhere on background space (tab strip, labels, gaps,
        // dialog padding) flips tabs like book pages: left goes forward, right
        // goes back. Touches that start on buttons, sliders, or checkboxes are
        // consumed by those controls, so they never reach this detector. Both
        // fast flings and slow drags count (DOWN/UP distance check), and the
        // listeners always return false so scrolling is unaffected.
        val swipeDistance = dp(64).toFloat()
        var swipeDownX = 0f
        var swipeDownY = 0f
        var swipeTracking = false
        val swipeTouch = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeDownX = event.x
                    swipeDownY = event.y
                    swipeTracking = true
                }
                MotionEvent.ACTION_UP -> {
                    if (swipeTracking) {
                        swipeTracking = false
                        val dx = event.x - swipeDownX
                        val dy = event.y - swipeDownY
                        if (kotlin.math.abs(dx) >= swipeDistance &&
                            kotlin.math.abs(dx) >= kotlin.math.abs(dy) * 1.5f
                        ) {
                            if (dx < 0f && currentTab < tabPages.lastIndex) goTab(currentTab + 1)
                            else if (dx > 0f && currentTab > 0) goTab(currentTab - 1)
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> swipeTracking = false
            }
            false
        }
        settingsScroll.setOnTouchListener(swipeTouch)
        content.setOnTouchListener(swipeTouch)
        tabScroll.setOnTouchListener(swipeTouch)
        tabRow.setOnTouchListener(swipeTouch)
        container.setOnTouchListener(swipeTouch)
        tabScroll.contentDescription = "Settings tabs. Swipe left or right anywhere to flip tabs."
        goTab(initialTab)
        dialog = AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(container)
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnDismissListener { rawZslSettingsStatus = null }
        dialog.show()
        // Match the island design language: same 16dp panel + stroke as the
        // photo chrome instead of the default dialog frame.
        dialog.window?.setBackgroundDrawableResource(R.drawable.quick_panel)
        // Keep the sheet compact and floating: fixed width with side margins
        // and capped height instead of stretching edge to edge.
        dialog.window?.let { window ->
            val metrics = resources.displayMetrics
            val targetWidth = (metrics.widthPixels - dp(48)).coerceAtLeast(dp(280))
            val targetHeight = (metrics.heightPixels * 0.85f).toInt()
            window.setLayout(targetWidth, targetHeight)
        }
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
            setStatus("WAIT FOR CAMERA")
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
        fun row(title: String, detail: String, active: Boolean, action: () -> Unit) =
            content.addView(twoLineChipButton(title, detail, active, action))
        row("Sensor levels", "Black RGGB and white level",
            current.blackLevels != null || current.whiteLevel != null) { showDngLevelsEditor(cameraId, defaults) }
        row("Noise model", "R, G and B scale / offset pairs", current.noiseProfile != null) { showDngNoiseEditor(cameraId, defaults) }
        row("Color matrices", "ColorMatrix 1 and 2",
            current.colorMatrix1 != null || current.colorMatrix2 != null) {
            showDngMatrixEditor(cameraId, "Color matrices", "ColorMatrix", defaults.colorMatrix1, defaults.colorMatrix2,
                { it.colorMatrix1 }, { it.colorMatrix2 }, { profile, first, second -> profile.copy(colorMatrix1 = first, colorMatrix2 = second) })
        }
        row("Camera calibration", "CalibrationMatrix 1 and 2",
            current.cameraCalibration1 != null || current.cameraCalibration2 != null) {
            showDngMatrixEditor(cameraId, "Camera calibration", "CameraCalibration", defaults.cameraCalibration1, defaults.cameraCalibration2,
                { it.cameraCalibration1 }, { it.cameraCalibration2 }, { profile, first, second -> profile.copy(cameraCalibration1 = first, cameraCalibration2 = second) })
        }
        row("Forward matrices", "ForwardMatrix 1 and 2",
            current.forwardMatrix1 != null || current.forwardMatrix2 != null) {
            showDngMatrixEditor(cameraId, "Forward matrices", "ForwardMatrix", defaults.forwardMatrix1, defaults.forwardMatrix2,
                { it.forwardMatrix1 }, { it.forwardMatrix2 }, { profile, first, second -> profile.copy(forwardMatrix1 = first, forwardMatrix2 = second) })
        }
        row("PROGRAM AE limits", "ISO/shutter bounds for this lens",
            programAeProfileStore.hasProfile(cameraId)) { showProgramPanel() }
        row("Vendor keys", "Session + request tags for this lens",
            vendorKeyStore.get(cameraId).activeEntries().isNotEmpty()) { showVendorKeysEditor() }
        AlertDialog.Builder(this).setTitle("RAW DNG calibration")
            .setView(ScrollView(this).apply { addView(content) })
            .setNeutralButton("Reset all", null).setNegativeButton("Close", null)
            .show().also { dialog ->
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    dngMetadataOverrideStore.clear(cameraId)
                    dialog.dismiss()
                    setStatus("CALIB RESET")
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
        inputs.forEach { input ->
            container.addView(TextView(this).apply {
                text = input.hint
                setTextColor(getColor(R.color.text_secondary)); textSize = 11f
                setPadding(0, dp(8), 0, 0)
            })
            input.hint = ""
            container.addView(input)
        }
        fun updateEnabled() = inputs.forEach { it.isEnabled = toggle.isChecked && available }
        toggle.setOnCheckedChangeListener { _, _ -> updateEnabled() }
        updateEnabled()
        val dialog = AlertDialog.Builder(this).setTitle(title)
            .setView(ScrollView(this).apply { addView(container) })
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
            setStatus(if (overrides.isEmpty()) "CALIB DEFAULT" else "CALIB SAVED")
            return true
        } catch (failure: Exception) {
            setStatus("CALIB NOT SAVED")
            return false
        }
    }

    /** Settings chip style shared by the panel and the vendor dialogs. */
    private fun styleSettingsButton(button: Button, active: Boolean = false) {
        button.background = getDrawable(
            if (active) R.drawable.control_chip_active else R.drawable.control_chip
        )
        button.setTextColor(
            getColor(if (active) R.color.accent_dark else R.color.text_primary)
        )
        button.textSize = 12f
        button.isAllCaps = false
        button.setPadding(dp(14), dp(10), dp(14), dp(10))
    }

    /** Accent section header matching the lens-picker groups. */
    private fun vendorSectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text.uppercase(Locale.US)
        setTextColor(getColor(R.color.accent))
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.1f
        setPadding(0, dp(12), 0, dp(4))
    }

    /** Full-width chip action button. */
    private fun vendorActionButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            styleSettingsButton(this)
            setOnClickListener { action() }
        }

    /** Two-line chip row: bold title on top, secondary detail below, left-aligned. */
    private fun twoLineChipButton(title: String, detail: String, active: Boolean, action: () -> Unit): Button =
        Button(this).apply {
            val full = "$title\n$detail"
            text = SpannableString(full).apply {
                setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            styleSettingsButton(this, active)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minHeight = dp(56)
            setOnClickListener { action() }
        }

    /** Key row: bold name on top, type • scope • value below. */
    private fun vendorEntryButton(entry: VendorKeyEntry, action: () -> Unit): Button =
        twoLineChipButton(
            entry.name.trim(),
            "${entry.type.label} • ${entry.scope.id} • " +
                entry.value.trim().ifEmpty { "—" } +
                if (entry.enabled) "" else " • off",
            entry.enabled,
            action
        )

    /** Per-lens vendor key/tag editor: session-early keys plus per-request keys. */
    private fun showVendorKeysEditor() {
        val cameraId = controller.activeCameraId()
        if (cameraId == null) {
            setStatus("WAIT FOR CAMERA")
            return
        }
        val profile = vendorKeyStore.get(cameraId)
        val onCount = profile.activeEntries().size
        var editor: AlertDialog? = null
        fun edit(entry: VendorKeyEntry?) {
            editor?.dismiss()
            showVendorKeyEditDialog(cameraId, entry) { showVendorKeysEditor() }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(TextView(this@MainActivity).apply {
                text = "Camera $cameraId • ${profile.entries.size} keys ($onCount on)\n" +
                    "Session keys load before the session opens (Vivo DCG needs this). " +
                    "Request keys ride every capture. Black/white levels are not camera " +
                    "keys — set them under Sensor levels."
                setTextColor(getColor(R.color.text_secondary)); textSize = 12f
                setPadding(0, 0, 0, dp(8))
            })
            fun reportLine(name: String, report: VendorKeyApplyReport?): String? =
                report?.takeIf { it.applied.isNotEmpty() || it.skipped.isNotEmpty() }?.let {
                    "$name ${it.applied.size} on / ${it.skipped.size} off" +
                        if (it.skipped.isNotEmpty()) " (${it.skipped.take(2).joinToString()})" else ""
                }
            val (lastSession, lastRequest) = controller.vendorLastApply()
            val applySummary = listOfNotNull(
                reportLine("Session", lastSession), reportLine("Request", lastRequest)
            ).joinToString("\n")
            if (applySummary.isNotEmpty()) {
                addView(TextView(this@MainActivity).apply {
                    text = "Last apply\n$applySummary"
                    setTextColor(getColor(R.color.text_secondary)); textSize = 12f
                    setPadding(0, 0, 0, dp(8))
                })
            }
            if (controller.vendorSessionNeedsReopen()) {
                addView(TextView(this@MainActivity).apply {
                    text = "Session keys changed — reopen the camera."
                    setTextColor(getColor(R.color.danger)); textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, 0, 0, dp(8))
                })
            }
        }
        if (profile.entries.isNotEmpty()) {
            content.addView(vendorSectionHeader("Your keys"))
        }
        profile.entries.sortedBy { it.name }.forEach { entry ->
            content.addView(vendorEntryButton(entry) { edit(entry) })
        }
        content.addView(vendorSectionHeader("Add"))
        content.addView(vendorActionButton("Add key") { edit(null) })
        if (profile.entries.isNotEmpty()) {
            content.addView(vendorActionButton("Save current as preset") {
                showPresetNameDialog { name ->
                    try {
                        vendorKeyStore.savePreset(name, profile)
                        setStatus("PRESET SAVED")
                    } catch (failure: Exception) {
                        setStatus("PRESET NOT SAVED")
                    }
                }
            })
        }
        val presets = vendorKeyStore.listPresets()
        if (presets.isNotEmpty()) {
            content.addView(vendorActionButton("Apply preset") {
                showPresetPicker(presets, "Apply preset") { name ->
                    val merged = profile.mergedWith(vendorKeyStore.getPreset(name))
                    if (saveVendorKeys(cameraId, merged)) {
                        editor?.dismiss()
                        showVendorKeysEditor()
                    }
                }
            })
            content.addView(vendorActionButton("Delete preset") {
                showPresetPicker(presets, "Delete preset") { name ->
                    vendorKeyStore.deletePreset(name)
                    setStatus("PRESET DELETED")
                    editor?.dismiss()
                    showVendorKeysEditor()
                }
            })
        }
        if (VendorKeyProfile.presets().entries.none { preset -> profile.entries.any { it.name == preset.name } }) {
            content.addView(vendorActionButton("Add Vivo DCG preset (off)") {
                val missing = VendorKeyProfile.presets().entries
                    .filter { preset -> profile.entries.none { it.name == preset.name } }
                if (saveVendorKeys(cameraId, VendorKeyProfile(profile.entries + missing))) {
                    editor?.dismiss()
                    showVendorKeysEditor()
                }
            })
        }
        content.addView(vendorSectionHeader("Lens"))
        content.addView(vendorActionButton("Keys advertised on this lens") {
            editor?.dismiss()
            showVendorDiscoveryDialog(cameraId)
        })
        content.addView(vendorActionButton("Reopen camera (applies session keys)") {
            controller.reopenCamera()
            setStatus("REOPENING")
        })
        val opmodeDetail = if (profile.operatingMode == 0) "Default (0)" else "${profile.operatingMode} — custom"
        content.addView(twoLineChipButton("Session operating mode", opmodeDetail, profile.operatingMode != 0) {
            showOperatingModeDialog(cameraId, profile) {
                editor?.dismiss()
                showVendorKeysEditor()
            }
        })
        content.addView(vendorSectionHeader("Share"))
        content.addView(vendorActionButton("Export keys (copy text)") {
            showVendorExportDialog(profile)
        })
        content.addView(vendorActionButton("Import keys") {
            editor?.dismiss()
            showVendorImportDialog(cameraId)
        })
        editor = AlertDialog.Builder(this).setTitle("Vendor keys • $cameraId")
            .setView(ScrollView(this).apply { addView(content) })
            .setNeutralButton("Reset all", null).setNegativeButton("Close", null)
            .show().also { dialog ->
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    vendorKeyStore.clear(cameraId)
                    controller.refreshVendorKeys()
                    dialog.dismiss()
                    setStatus("VENDOR RESET")
                }
            }
    }

    private fun showVendorKeyEditDialog(
        cameraId: String,
        existing: VendorKeyEntry?,
        prefillName: String? = null,
        prefillScope: VendorKeyScope? = null,
        onSaved: () -> Unit
    ) {
        var type = existing?.type ?: VendorKeyType.INT
        var scope = existing?.scope ?: prefillScope ?: VendorKeyScope.REQUEST
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(TextView(this@MainActivity).apply {
                text = "Names (section.key) or numeric tags (0x…). Scalars take one " +
                    "number or true/false; arrays take comma-separated values."
                setTextColor(getColor(R.color.text_secondary)); textSize = 12f
            })
        }
        container.addView(vendorSectionHeader("Name"))
        val nameInput = EditText(this).apply {
            hint = "section.key or 0x tag"
            setText(existing?.name ?: prefillName ?: "")
            setSelectAllOnFocus(true)
        }
        container.addView(nameInput)
        val suggestionsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(suggestionsBox)
        container.addView(vendorSectionHeader("Type"))
        val typeButton = Button(this).apply { isAllCaps = false }
        fun refreshType() {
            typeButton.text = type.label
            styleSettingsButton(typeButton, active = true)
        }
        refreshType()
        typeButton.setOnClickListener {
            val labels = VendorKeyType.entries.map { it.label }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Value type")
                .setSingleChoiceItems(labels, type.ordinal) { choice, which ->
                    type = VendorKeyType.entries[which]
                    refreshType()
                    choice.dismiss()
                }
                .setNegativeButton("Cancel", null).show()
        }
        container.addView(typeButton)
        container.addView(vendorSectionHeader("Scope"))
        val scopeButton = Button(this).apply { isAllCaps = false }
        fun refreshScope() {
            scopeButton.text = if (scope == VendorKeyScope.SESSION) "Session (before open)" else "Request (every capture)"
            styleSettingsButton(scopeButton, active = true)
        }
        refreshScope()
        scopeButton.setOnClickListener {
            val options = VendorKeyScope.entries.toTypedArray()
            val labels = options.map {
                if (it == VendorKeyScope.SESSION) "Session (before open)" else "Request (every capture)"
            }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Scope")
                .setSingleChoiceItems(labels, scope.ordinal) { choice, which ->
                    scope = options[which]
                    refreshScope()
                    choice.dismiss()
                }
                .setNegativeButton("Cancel", null).show()
        }
        container.addView(scopeButton)
        // Autocomplete: suggests advertised keys matching what is typed so far.
        val advertisedKeys = controller.vendorKeyDiscovery()
        fun refreshSuggestions() {
            suggestionsBox.removeAllViews()
            val query = nameInput.text.toString().trim().lowercase()
            if (query.length < 2) return
            advertisedKeys
                .filter { it.name.lowercase().contains(query) && it.name.trim().lowercase() != query }
                .take(5)
                .forEach { found ->
                    suggestionsBox.addView(
                        twoLineChipButton(
                            found.name,
                            "${found.scopeHint.id}" +
                                found.tagHex?.let { " • $it" }.orEmpty() +
                                " • tap to use",
                            false
                        ) {
                            nameInput.setText(found.name)
                            scope = found.scopeHint
                            refreshScope()
                            suggestionsBox.removeAllViews()
                        }
                    )
                }
        }
        nameInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = refreshSuggestions()
        })
        container.addView(vendorSectionHeader("Value"))
        val valueInput = EditText(this).apply {
            hint = "1, or 64, 64, 64, 64"
            setText(existing?.value ?: "")
            setSelectAllOnFocus(true)
        }
        container.addView(valueInput)
        val enabledBox = CheckBox(this).apply {
            text = "Enabled"
            isChecked = existing?.enabled ?: true
            setTextColor(getColor(R.color.text_primary))
        }
        container.addView(enabledBox)
        val probeLine = TextView(this).apply {
            setTextColor(getColor(R.color.text_secondary)); textSize = 12f
            setPadding(0, dp(4), 0, 0)
        }
        container.addView(vendorActionButton("Test on this lens") {
            val candidate = try {
                VendorKeyEntry(
                    nameInput.text.toString().trim(), type, scope,
                    valueInput.text.toString(), enabledBox.isChecked
                ).also(VendorKeyEntry::validate)
            } catch (failure: IllegalArgumentException) {
                probeLine.text = "Fix the fields first (${failure.message})"
                probeLine.setTextColor(getColor(R.color.danger))
                return@vendorActionButton
            }
            probeLine.text = "Testing…"
            probeLine.setTextColor(getColor(R.color.text_secondary))
            controller.probeVendorKey(candidate) { result ->
                runOnUiThread {
                    probeLine.text = (if (result.supported) "Accepted: " else "Not accepted: ") + result.detail
                    probeLine.setTextColor(getColor(if (result.supported) R.color.accent else R.color.danger))
                }
            }
        })
        container.addView(probeLine)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add vendor key" else "Edit vendor key")
            .setView(ScrollView(this).apply { addView(container) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .apply { if (existing != null) setNeutralButton("Delete", null) }
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (VendorKeyApply.isLevelTag(name)) {
                nameInput.error = "Not a camera key — set levels under Sensor levels"
                return@setOnClickListener
            }
            val candidate = try {
                VendorKeyEntry(name, type, scope, valueInput.text.toString(), enabledBox.isChecked)
                    .also(VendorKeyEntry::validate)
            } catch (failure: IllegalArgumentException) {
                val message = failure.message ?: "Invalid key"
                if ("name" in message.lowercase()) nameInput.error = message
                else valueInput.error = message
                return@setOnClickListener
            }
            val current = vendorKeyStore.get(cameraId)
            val merged = (current.entries.filter { it.name.trim() != candidate.name.trim() } + candidate)
                .sortedBy { it.name }
            try {
                VendorKeyProfile(merged).validate()
            } catch (failure: IllegalArgumentException) {
                valueInput.error = failure.message
                return@setOnClickListener
            }
            if (saveVendorKeys(cameraId, VendorKeyProfile(merged))) {
                dialog.dismiss()
                onSaved()
            }
        }
        if (existing != null) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val current = vendorKeyStore.get(cameraId)
                val kept = current.entries.filter { it.name.trim() != existing.name.trim() }
                if (saveVendorKeys(cameraId, VendorKeyProfile(kept))) {
                    dialog.dismiss()
                    onSaved()
                }
            }
        }
    }

    /** Advertised keys for the open lens; tapping one starts an add prefilled with it. */
    private fun showVendorDiscoveryDialog(cameraId: String) {
        val keys = controller.vendorKeyDiscovery()
        val added = vendorKeyStore.get(cameraId).entries.map { it.name.trim() }.toSet()
        var list: AlertDialog? = null
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }
        val countLine = TextView(this).apply {
            setTextColor(getColor(R.color.text_secondary)); textSize = 12f
            setPadding(0, 0, 0, dp(8))
        }
        body.addView(countLine)
        val queryInput = EditText(this).apply {
            hint = "Search (e.g. com.mediatek, bpp)"
            setSelectAllOnFocus(true)
            setSingleLine()
        }
        body.addView(queryInput)
        val rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(rowsBox)
        fun refreshRows() {
            val query = queryInput.text.toString().trim().lowercase()
            val matches = if (query.isEmpty()) keys else keys.filter {
                it.name.lowercase().contains(query) ||
                    it.scopeHint.id.contains(query) ||
                    it.source.lowercase().contains(query)
            }
            countLine.text = when {
                keys.isEmpty() -> "Nothing advertised yet — wait for the camera."
                query.isEmpty() -> "${keys.size} keys advertised on $cameraId. Tap one to add it."
                else -> "${matches.size} of ${keys.size} keys match."
            }
            rowsBox.removeAllViews()
            // Cap the rows so a tag-heavy HAL cannot blow up the dialog.
            matches.take(MAX_VENDOR_DISCOVERY_ROWS).forEach { found ->
                rowsBox.addView(
                    twoLineChipButton(
                        found.name,
                        "${found.scopeHint.id} • ${found.source}" +
                            found.tagHex?.let { " • $it" }.orEmpty() +
                            if (found.name in added) " • added" else "",
                        found.name in added
                    ) {
                        list?.dismiss()
                        showVendorKeyEditDialog(cameraId, null, found.name, found.scopeHint) {
                            showVendorKeysEditor()
                        }
                    }
                )
            }
            if (matches.size > MAX_VENDOR_DISCOVERY_ROWS) {
                rowsBox.addView(TextView(this).apply {
                    text = "…and ${matches.size - MAX_VENDOR_DISCOVERY_ROWS} more — narrow the search."
                    setTextColor(getColor(R.color.text_secondary)); textSize = 12f
                })
            }
        }
        queryInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = refreshRows()
        })
        refreshRows()
        list = AlertDialog.Builder(this).setTitle("Advertised keys • $cameraId")
            .setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton("Close", null).show()
    }

    private fun showOperatingModeDialog(cameraId: String, profile: VendorKeyProfile, onSaved: () -> Unit) {
        val opmodeInput = EditText(this).apply {
            hint = "0 = default (e.g. 37379 for Xiaomi DCG)"
            setText(profile.operatingMode.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setSelectAllOnFocus(true)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(TextView(this@MainActivity).apply {
                text = "Non-zero opens the session through the vendor operating-mode " +
                    "path (PhotonVidCam recipe). A wrong value only falls back to " +
                    "the standard session. Takes effect after reopen."
                setTextColor(getColor(R.color.text_secondary)); textSize = 12f
            })
            addView(opmodeInput)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Session operating mode")
            .setView(ScrollView(this).apply { addView(container) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val opmode = try {
                VendorKeyProfile.parseOperatingMode(opmodeInput.text.toString())
            } catch (failure: IllegalArgumentException) {
                opmodeInput.error = failure.message
                return@setOnClickListener
            }
            if (saveVendorKeys(cameraId, profile.copy(operatingMode = opmode))) {
                dialog.dismiss()
                onSaved()
            }
        }
    }

    private fun showPresetNameDialog(onName: (String) -> Unit) {
        val nameInput = EditText(this).apply {
            hint = "Preset name (e.g. 14-bit MediaTek)"
            setSelectAllOnFocus(true)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(nameInput)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Save preset")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                VendorKeyStore.cleanPresetName(nameInput.text.toString())
            } catch (failure: IllegalArgumentException) {
                nameInput.error = failure.message
                return@setOnClickListener
            }
            dialog.dismiss()
            onName(nameInput.text.toString())
        }
    }

    private fun showPresetPicker(presets: List<String>, title: String, onPick: (String) -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(presets.toTypedArray(), -1) { choice, which ->
                choice.dismiss()
                onPick(presets[which])
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showVendorExportDialog(profile: VendorKeyProfile) {
        if (profile.isEmpty()) {
            setStatus("NOTHING TO EXPORT")
            return
        }
        val payload = profile.toJson()
        val pasteBox = EditText(this).apply {
            setText(payload)
            setSelectAllOnFocus(true)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(TextView(this@MainActivity).apply {
                text = "Copy this text to share the key set with another phone."
                setTextColor(getColor(R.color.text_secondary)); textSize = 12f
            })
            addView(pasteBox)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Export keys")
            .setView(ScrollView(this).apply { addView(container) })
            .setNegativeButton("Close", null)
            .setPositiveButton("Copy", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("rawlens-vendor-keys", payload)
            )
            dialog.dismiss()
            setStatus("KEYS COPIED")
        }
    }

    private fun showVendorImportDialog(cameraId: String) {
        val resultLine = TextView(this).apply {
            setTextColor(getColor(R.color.text_secondary)); textSize = 12f
        }
        val pasteBox = EditText(this).apply {
            hint = "Paste an exported key set here"
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(TextView(this@MainActivity).apply {
                text = "Adds keys this lens does not already have. Nothing is deleted."
                setTextColor(getColor(R.color.text_secondary)); textSize = 12f
            })
            addView(pasteBox)
            addView(resultLine)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Import keys")
            .setView(ScrollView(this).apply { addView(container) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Import", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val incoming = try {
                VendorKeyProfile.fromJson(pasteBox.text.toString()).also(VendorKeyProfile::validate)
            } catch (failure: IllegalArgumentException) {
                resultLine.text = "Not a key set (${failure.message})"
                resultLine.setTextColor(getColor(R.color.danger))
                return@setOnClickListener
            }
            if (incoming.isEmpty()) {
                resultLine.text = "The pasted text holds no valid keys."
                resultLine.setTextColor(getColor(R.color.danger))
                return@setOnClickListener
            }
            val current = vendorKeyStore.get(cameraId)
            if (saveVendorKeys(cameraId, current.mergedWith(incoming))) {
                dialog.dismiss()
                showVendorKeysEditor()
            }
        }
    }

    private fun saveVendorKeys(cameraId: String, profile: VendorKeyProfile): Boolean {
        try {
            vendorKeyStore.save(cameraId, profile)
        } catch (failure: Exception) {
            setStatus("VENDOR NOT SAVED")
            return false
        }
        // Request keys take effect on the live repeating request; session keys
        // wait for the next open (see "Reopen camera" above).
        controller.refreshVendorKeys()
        setStatus(if (profile.activeEntries().isEmpty()) "VENDOR OFF" else "VENDOR SAVED")
        return true
    }

    private fun rawZslFrameCountText(frameCount: Int): String =
        "ZSL frames saved: $frameCount\n" +
            "Approx. ${frameCount * 25} MB at 4080×3060; higher values need more camera memory."

    private fun zslHybridTopupText(enabled: Boolean): String =
        if (enabled) "Top-up: ON • thin ring completes with fresh frames"
        else "Top-up: OFF • thin ring waits for refill"

    private fun vfResolutionText(res: Int): String =
        "VF resolution: ${res}px long edge (tap: 480→640→960→1080)"

    private fun vfPreviewModeText(): String =
        "VF preview: ${vfPreviewMode.name} (${vfPreviewMode.label(captureFormat)})"

    private fun dynamicExposureBalanceText(balance: Float): String = when {
        balance > 1.01f -> "Exposure balance: ${String.format(Locale.US, "%.2f", balance)}× • faster shutter"
        balance < 0.99f -> "Exposure balance: ${String.format(Locale.US, "%.2f", balance)}× • lower ISO"
        else -> "Exposure balance: 1.00× • neutral"
    }

    private fun showLensDiscovery(firstRun: Boolean) {
        // First launch covers the whole camera UI (including the top bar)
        // with an opaque black overlay — a dialog window can't reliably do
        // that — while later invocations keep the floating progress dialog.
        var progressDialog: AlertDialog? = null
        var progressOverlay: View? = null
        if (firstRun) {
            // Already immersive from onCreate; the overlay just covers the UI.
            progressOverlay = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.BLACK)
                elevation = 32f
                isClickable = true
                isFocusableInTouchMode = true
                setPadding(dp(32), 0, dp(32), 0)
                addView(ProgressBar(this@MainActivity).apply { isIndeterminate = true })
                addView(TextView(this@MainActivity).apply {
                    text = "Finding RAW lenses"
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(0, dp(16), 0, dp(8))
                })
                addView(TextView(this@MainActivity).apply {
                    text = "Checking every Camera2 ID for rear RAW support.\nThis usually takes a few seconds…"
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 13f
                    gravity = Gravity.CENTER
                })
            }.also { overlay ->
                blockBackKey(overlay)
                findViewById<ViewGroup>(android.R.id.content)
                    .addView(overlay, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                overlay.requestFocus()
            }
        } else {
            progressDialog = AlertDialog.Builder(this)
                .setTitle("Finding RAW lenses")
                .setMessage("Checking every Camera2 ID for rear RAW support.\nThis usually takes a few seconds…")
                .setCancelable(true)
                .show()
        }
        val verdicts = runCatching { lensVerdictStore.snapshot() }.getOrDefault(LensVerdicts.EMPTY)
        lensDiscovery.discover(verdicts) { lenses ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progressDialog?.dismiss()
                progressOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
                progressOverlay = null
                if (lenses.isEmpty()) {
                    // No selection page follows; stay fullscreen (the dialog
                    // re-hides the bars via onWindowFocusChanged on dismiss).
                    // The deferred first-run consent fires as this alert
                    // leaves, so it never stacks over discovery either.
                    AlertDialog.Builder(this)
                        .setTitle("No RAW lenses found")
                        .setMessage("The camera service did not expose a RAW-capable camera ID.")
                        .setPositiveButton("OK", null)
                        .setOnDismissListener { updateFlow.showPendingConsent() }
                        .show()
                    return@runOnUiThread
                }
                val missingSaved = selectedLensIds()
                    .filterNot { savedId -> lenses.any { it.id == savedId } }
                    .map { unavailableLens(it) }
                // First run stays fullscreen throughout: dismissing the
                // selection page re-applies immersive mode, never shows bars.
                showLensSelection(lenses + missingSaved, firstRun)
            }
        }
    }

    /** Overlay pages are modal: the back key must not escape onboarding. */
    private fun blockBackKey(view: View) {
        view.setOnKeyListener { _, keyCode, event ->
            keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP
        }
    }

    private fun showLensSelection(lenses: List<DiscoveredLens>, firstRun: Boolean) {
        val selected = selectedLensIds()
        val groups = groupByOptic(lenses)
        val opticCount = groups.count { it.key != "unavailable" }
        val routeCount = groups.sumOf { it.lenses.size }
        // Nothing is pre-checked, including on first launch: the user
        // explicitly picks what they want. The Recommended button checks
        // the suggested set in one tap.
        val recommended = recommendedLensIds(lenses)
        val checked = lenses.associate { lens ->
            lens.id to (lens.id in selected)
        }.toMutableMap()
        val checkBoxes = mutableListOf<CheckBox>()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        container.addView(TextView(this).apply {
            text = "$opticCount lens${if (opticCount == 1) "" else "es"} · $routeCount route${if (routeCount == 1) "" else "s"}"
            setTextColor(getColor(R.color.text_primary))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        })
        container.addView(TextView(this).apply {
            text = "One card per lens. Checked lenses appear in the viewfinder switcher, widest first."
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        })

        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        fun styleQuickButton(button: Button) {
            button.background = getDrawable(R.drawable.control_chip)
            button.setTextColor(getColor(R.color.text_primary))
            button.textSize = 12f
            button.isAllCaps = false
            button.setTypeface(button.typeface, Typeface.BOLD)
            button.letterSpacing = 0.02f
            // Compact pill: single-line 12sp stays readable while the
            // slimmer padding keeps secondary actions visually secondary.
            button.setPadding(dp(12), dp(6), dp(12), dp(6))
            button.minHeight = dp(36)
            // One line everywhere: a wrapped label grows its button and the
            // row next to it looks misaligned on narrow screens.
            button.maxLines = 1
            button.ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val useRecommended = Button(this).apply {
            text = "Best"
            styleQuickButton(this)
        }
        val selectAll = Button(this).apply {
            text = "All"
            styleQuickButton(this)
        }
        val clear = Button(this).apply {
            text = "Clear"
            styleQuickButton(this)
        }
        val recommendedParams = LinearLayout.LayoutParams(0, wrapContent(), 1f).apply { marginEnd = dp(8) }
        val selectParams = LinearLayout.LayoutParams(0, wrapContent(), 1f).apply { marginEnd = dp(8) }
        val clearParams = LinearLayout.LayoutParams(0, wrapContent(), 1f)
        quickRow.addView(useRecommended, recommendedParams)
        quickRow.addView(selectAll, selectParams)
        quickRow.addView(clear, clearParams)
        container.addView(quickRow)
        // Secondary action as a plain text link: a third full-width chip
        // made the preset block dominate the screen.
        val copyReport = TextView(this).apply {
            text = "Copy lens report"
            setTextColor(getColor(R.color.accent))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(10))
        }
        container.addView(copyReport, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, wrapContent()
        ))
        copyReport.setOnClickListener {
            val report = lensReport(
                lenses,
                chosenSize = { rawSizePreferenceStore.get(it) },
                chosenFormat = { rawFormatPreferenceStore.get(it) }
            )
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard == null) {
                setStatus("COPY FAILED")
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("RawLens lens report", report))
                setStatus("LENS REPORT COPIED")
            }
        }

        val listScroll = ScrollView(this).apply {
            layoutParams = if (firstRun) {
                // Full-screen onboarding: fill everything between the header
                // and the bottom action bar so actions pin to the bottom.
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                )
            } else {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.52).toInt()
                )
            }
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        val listBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        listScroll.addView(listBody)

        fun refreshQuickState() {
            val values = checked.values
            selectAll.isEnabled = values.any { !it }
            clear.isEnabled = values.any { it }
            useRecommended.isEnabled = checked.any { (it.value != (it.key in recommended)) }
            selectAll.alpha = if (selectAll.isEnabled) 1f else 0.5f
            clear.alpha = if (clear.isEnabled) 1f else 0.5f
            useRecommended.alpha = if (useRecommended.isEnabled) 1f else 0.5f
        }

        fun lensCard(lens: DiscoveredLens, recommendedId: String?): LinearLayout {
            val dead = lens.verdict == LensVerdict.DEAD
            val dimmed = lens.kind == LensRouteKind.UNAVAILABLE || dead
            val chip = when {
                dead -> "⚠ OPEN FAILED BEFORE"
                lens.id == recommendedId -> "★ RECOMMENDED"
                else -> null
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.control_chip)
                setPadding(dp(8), dp(10), dp(12), dp(10))
                val cardParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                layoutParams = cardParams
                alpha = if (dimmed) 0.72f else 1f
            }
            val box = CheckBox(this).apply {
                tag = lens.id
                isChecked = checked[lens.id] == true
                contentDescription = lens.label
            }
            checkBoxes += box
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, wrapContent(), 1f)
            }
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topRow.addView(TextView(this).apply {
                text = lens.title
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, wrapContent(), 1f)
            })
            topRow.addView(TextView(this).apply {
                text = "ID ${lens.id}"
                setTextColor(getColor(R.color.text_muted))
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setPadding(dp(8), 0, 0, 0)
            })
            textCol.addView(topRow)
            textCol.addView(TextView(this).apply {
                text = lens.details.ifEmpty { lens.label }
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, dp(2), 0, 0)
            })
            // One muted line: the route text already says direct/logical,
            // so the kind chip only appears for the special states.
            val routeText = lens.route.ifEmpty { lens.kind.sectionTitle }
            textCol.addView(TextView(this).apply {
                text = if (chip != null) "$chip · $routeText" else routeText
                setTextColor(getColor(R.color.text_muted))
                textSize = 11f
                setPadding(0, dp(4), 0, 0)
            })
            card.addView(box)
            card.addView(textCol)
            box.setOnCheckedChangeListener { _, enabled -> checked[lens.id] = enabled; refreshQuickState() }
            card.setOnClickListener { box.toggle() }
            return card
        }

        fun showRawFormatChoice(lens: DiscoveredLens, formatRow: TextView) {
            val formats = lens.packedFormats
            val items = (listOf("Auto (RAW_SENSOR)") + formats).toTypedArray()
            val stored = rawFormatPreferenceStore.get(lens.id)
            val checked = stored?.let { formats.indexOf(it) + 1 }?.takeIf { it > 0 } ?: 0
            AlertDialog.Builder(this)
                .setTitle("RAW format · ID ${lens.id}")
                .setSingleChoiceItems(items, checked) { dialog, which ->
                    val picked = if (which == 0) null else formats[which - 1]
                    rawFormatPreferenceStore.set(lens.id, picked)
                    setStatus(if (picked == null) "RAW FORMAT AUTO" else "RAW FORMAT $picked")
                    formatRow.text = "RAW format: ${picked ?: "Auto"} ▾"
                    controller.reopenCamera()
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        fun showRawSizeChoice(lens: DiscoveredLens, sizeRow: TextView) {
            val sizes = lens.rawSizes
            val items = (listOf("Auto (largest)") + sizes).toTypedArray()
            val stored = rawSizePreferenceStore.get(lens.id)
            val checked = stored?.let { sizes.indexOf(it) + 1 }?.takeIf { it > 0 } ?: 0
            AlertDialog.Builder(this)
                .setTitle("RAW size · ID ${lens.id}")
                .setSingleChoiceItems(items, checked) { dialog, which ->
                    val picked = if (which == 0) null else sizes[which - 1]
                    rawSizePreferenceStore.set(lens.id, picked)
                    setStatus(if (picked == null) "RAW SIZE AUTO" else "RAW SIZE $picked")
                    sizeRow.text = "RAW size: ${picked ?: "Auto"} ▾"
                    controller.reopenCamera()
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        var firstGroup = true
        groups.forEach { group ->
            // ---- One card per physical lens ----
            // No big header: the card title already names the lens, so the
            // section only gets a small muted line. Same sizes everywhere.
            listBody.addView(TextView(this).apply {
                text = group.subtitle
                setTextColor(getColor(R.color.text_muted))
                textSize = 12f
                setPadding(0, if (firstGroup) dp(4) else dp(12), 0, dp(6))
            })
            firstGroup = false
            if (group.key == "unavailable") {
                group.lenses.forEach { lens ->
                    listBody.addView(lensCard(lens, null))
                }
            } else {
                val head = group.recommended
                listBody.addView(lensCard(head, head.id))
                if (group.lenses.any { it.sensorModeSwitch }) {
                    listBody.addView(TextView(this).apply {
                        text = "Sensor-mode switch advertised here — special modes need a per-lens vendor key in Settings."
                        setTextColor(getColor(R.color.text_muted))
                        textSize = 11f
                        setPadding(0, 0, 0, dp(8))
                    })
                }
                if (head.packedFormats.isNotEmpty()) {
                    listBody.addView(TextView(this).apply {
                        text = "Lists packed ${head.packedFormats.joinToString(" + ")} (info only — capture stays RAW_SENSOR)."
                        setTextColor(getColor(R.color.text_muted))
                        textSize = 11f
                        setPadding(0, 0, 0, dp(8))
                    })
                }
                if (head.rawSizes.size > 1) {
                    val stored = rawSizePreferenceStore.get(head.id)
                    val sizeRow = TextView(this).apply {
                        text = "RAW size: ${stored ?: "Auto"} ▾"
                        setTextColor(getColor(R.color.accent))
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(0, 0, 0, dp(8))
                    }
                    sizeRow.setOnClickListener { showRawSizeChoice(head, sizeRow) }
                    listBody.addView(sizeRow)
                }
                // Packed capture is strictly opt-in: the row appears only
                // where the map lists packed formats, defaulting to Auto
                // (RAW_SENSOR) like every fresh install.
                if (head.packedFormats.isNotEmpty()) {
                    val stored = rawFormatPreferenceStore.get(head.id)
                    val formatRow = TextView(this).apply {
                        text = "RAW format: ${stored ?: "Auto"} ▾"
                        setTextColor(getColor(R.color.accent))
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(0, 0, 0, dp(8))
                    }
                    formatRow.setOnClickListener { showRawFormatChoice(head, formatRow) }
                    listBody.addView(formatRow)
                }
                if (group.alternates.isNotEmpty()) {
                    val alternateBody = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        visibility = View.GONE
                    }
                    group.alternates.forEach { lens ->
                        alternateBody.addView(lensCard(lens, head.id))
                    }
                    val toggle = TextView(this).apply {
                        text = "＋ ${group.alternates.size} more route${if (group.alternates.size == 1) "" else "s"} (same lens)"
                        setTextColor(getColor(R.color.accent))
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(0, 0, 0, dp(8))
                    }
                    toggle.setOnClickListener {
                        val open = alternateBody.visibility != View.VISIBLE
                        alternateBody.visibility = if (open) View.VISIBLE else View.GONE
                        toggle.text = if (open) "－ hide extra routes"
                            else "＋ ${group.alternates.size} more route${if (group.alternates.size == 1) "" else "s"} (same lens)"
                    }
                    listBody.addView(toggle)
                    listBody.addView(alternateBody)
                }
            }
        }
        container.addView(listScroll)
        container.addView(TextView(this).apply {
            text = "Tip: Recommended is the daily driver. Extra routes are fallbacks for missing lenses or special sensor modes."
            setTextColor(getColor(R.color.text_muted))
            textSize = 11f
            setPadding(0, dp(8), 0, dp(4))
        })

        useRecommended.setOnClickListener {
            checkBoxes.forEach { it.isChecked = it.tag in recommended }
            refreshQuickState()
        }
        selectAll.setOnClickListener {
            checked.keys.forEach { checked[it] = true }
            checkBoxes.forEach { it.isChecked = true }
            refreshQuickState()
        }
        clear.setOnClickListener {
            checked.keys.forEach { checked[it] = false }
            checkBoxes.forEach { it.isChecked = false }
            refreshQuickState()
        }
        refreshQuickState()

        // First initial setup: dedicated full-screen black page (welcome
        // continuity) that scales to the bottom — the list fills the window
        // and Save / Use default pin to the bottom. Gone on either tap.
        // Later opens keep the floating panel below.
        if (firstRun) {
            container.setPadding(0, 0, 0, 0)
            fun bottomAction(label: String, primary: Boolean): Button = Button(this).apply {
                text = label
                background = getDrawable(
                    if (primary) R.drawable.control_chip_active else R.drawable.control_chip
                )
                setTextColor(
                    getColor(if (primary) R.color.accent_dark else R.color.text_primary)
                )
                textSize = 13f
                isAllCaps = false
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.04f
                setPadding(dp(14), dp(12), dp(14), dp(12))
                minHeight = dp(48)
            }
            val saveButton = bottomAction("Save", primary = true)
            val defaultButton = bottomAction("Use default", primary = false)
            val bottomBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12), 0, 0)
            }
            bottomBar.addView(
                defaultButton,
                LinearLayout.LayoutParams(0, wrapContent(), 1f).apply { marginEnd = dp(8) }
            )
            bottomBar.addView(saveButton, LinearLayout.LayoutParams(0, wrapContent(), 1f))
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.BLACK)
                elevation = 32f
                isClickable = true
                isFocusableInTouchMode = true
                setPadding(dp(20), dp(16), dp(20), dp(20))
            }
            root.addView(TextView(this).apply {
                text = "Add RAW lenses"
                setTextColor(getColor(R.color.text_primary))
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(8))
            })
            root.addView(
                container,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                )
            )
            val saveHint = TextView(this).apply {
                text = "Select at least one lens to continue"
                setTextColor(getColor(R.color.danger))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
                visibility = View.GONE
            }
            root.addView(saveHint)
            root.addView(bottomBar)
            blockBackKey(root)
            findViewById<ViewGroup>(android.R.id.content).addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            root.requestFocus()
            fun dismissPage() {
                (root.parent as? ViewGroup)?.removeView(root)
                // Used to showStatusBar() here: first install kept the
                // notification bar until relaunch. Stay fullscreen instead.
                ImmersiveMode.apply(window)
                // First run ends here: the deferred keep-up-to-date ask
                // gets its own moment now that discovery is off screen.
                updateFlow.showPendingConsent()
            }
            saveButton.setOnClickListener {
                val ids = checked.filterValues { it }.keys.toMutableSet()
                if (ids.isEmpty()) {
                    saveHint.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                lensPreferences().edit()
                    .putStringSet(KEY_SELECTED_LENSES, ids)
                    .putBoolean(KEY_LENS_SETUP_COMPLETE, true)
                    .apply()
                controller.reloadLenses()
                setStatus("${ids.size} RAW LENS${if (ids.size == 1) "" else "ES"} ADDED")
                dismissPage()
            }
            defaultButton.setOnClickListener {
                lensPreferences().edit().putBoolean(KEY_LENS_SETUP_COMPLETE, true).apply()
                dismissPage()
            }
            return
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Lens discovery")
            .setView(container)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val ids = checked.filterValues { it }.keys.toMutableSet()
            if (ids.isEmpty()) {
                setStatus("PICK A RAW LENS")
                return@setOnClickListener
            }
            lensPreferences().edit()
                .putStringSet(KEY_SELECTED_LENSES, ids)
                .putBoolean(KEY_LENS_SETUP_COMPLETE, true)
                .apply()
            controller.reloadLenses()
            setStatus("${ids.size} RAW LENS${if (ids.size == 1) "" else "ES"} ADDED")
            dialog.dismiss()
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            dialog.dismiss()
        }
    }

    private fun dngWriterBackend(): DngWriterBackend = DngWriterBackend.fromPreference(
        lensPreferences().getString(KEY_DNG_WRITER_BACKEND, DngWriterBackend.ANDROID.preferenceValue)
    )

    private fun dngCompression(): DngCompression = DngCompression.fromPreference(
        lensPreferences().getString(KEY_DNG_COMPRESSION, DngCompression.UNCOMPRESSED.preferenceValue)
    )

    private fun gpsEnabled(): Boolean =
        lensPreferences().getBoolean(KEY_SAVE_LOCATION, false)

    private fun setGpsEnabled(enabled: Boolean) {
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

    private fun lensPreferences() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun hdrBracketStops(): Int =
        if (lensPreferences().getInt(KEY_HDR_BRACKET_STOPS, 2) == 4) 4 else 2

    private fun seqTrioEnabled(): Boolean =
        lensPreferences().getBoolean(KEY_SEQ_TRIO_ENABLED, false)

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

    companion object {
        const val LOG_TAG = "RawLensCamera"
        const val CAMERA_PERMISSION = 42
        const val LOCATION_PERMISSION = 43
        const val SIDECAR_TREE_REQUEST = 44
        const val SETTINGS_EXPORT_REQUEST = 45
        const val SETTINGS_IMPORT_REQUEST = 46
        const val UPDATE_INSTALL_SOURCES = 47
        /** Rejects absurd import files before parsing (a real backup is tens of KB). */
        const val MAX_SETTINGS_IMPORT_BYTES = 2 * 1024 * 1024
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
