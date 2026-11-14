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

// The Settings screen shell (category list, page header, back navigation) around the Settings*Tab pages.

/** [showSettings] page index for the category list itself. */
internal const val SETTINGS_ROOT = -1

/** [showSettings] index of Lenses: not a page, the list row opens lens discovery itself. */
internal const val SETTINGS_LENSES_TAB = 7

/**
 * Fullscreen Settings. Opens on the category list, like system settings: a row per
 * page, tap to open it, back to return. [initialTab] opens one page directly (see
 * [SETTINGS_VIDEO_TAB]); the settings button uses that for Video in video mode and
 * Exposure in PROGRAM. Back from such a page still leads to the category list.
 */
internal const val KEY_PAUSE_CAMERA_IN_SETTINGS = "settings_pause_camera"

internal fun MainActivity.pauseCameraInSettings(): Boolean =
    lensPreferences().getBoolean(KEY_PAUSE_CAMERA_IN_SETTINGS, true)

/** Closes the camera behind Settings when the switch is on; a recording keeps it (recorder owns it). */
internal fun MainActivity.pauseCameraForSettings() {
    if (pauseCameraInSettings() && !videoRecording && videoRecorder == null && encodedVideoRecorder == null) {
        controller.stop()
    }
}

internal fun MainActivity.showSettings(initialTab: Int = SETTINGS_ROOT) {
    settingsDialog?.dismiss()
    fun selectableBackground(borderless: Boolean): Int = android.util.TypedValue().also {
        theme.resolveAttribute(
            if (borderless) android.R.attr.selectableItemBackgroundBorderless
            else android.R.attr.selectableItemBackground, it, true)
    }.resourceId
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(getColor(R.color.surface))
    }
    val backButton = TextView(this).apply {
        text = "←"
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(getColor(R.color.text_primary))
        setBackgroundResource(selectableBackground(borderless = true))
        isClickable = true
        isFocusable = true
    }
    val titleView = TextView(this).apply {
        textSize = 26f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = -0.01f
        setTextColor(getColor(R.color.text_primary))
        maxLines = 1
        isAccessibilityHeading = true
        setPadding(dp(12), 0, dp(4), 0)
    }
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(12), 0, dp(4))
        addView(backButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    container.addView(header, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    // Name and one-line summary per page, in page-index order.
    val pageInfo = listOf(
        "General" to "RAW capture, viewfinder, location, backup & restore",
        "Photo" to "File format and photo output",
        "Video" to "RAW and encoded video, audio, stabilization, monitoring",
        "Exposure" to "PROGRAM auto exposure, HDR brackets, face priority",
        "Burst" to "Zero shutter lag, gyro sidecars, offline merges",
        "Merge" to "HDR+, KB-SR and LK-SR burst merges",
        "Denoise" to "AI RAW denoise, wavelet denoise, capture sharpening",
        "Lenses" to "",
        "Debug" to "App log, camera diagnostics, debug frames",
        "About" to "Version, licences, updates",
    )
    val pageIcons = listOf(
        R.drawable.ic_tune, R.drawable.ic_set_photo, R.drawable.ic_set_video, R.drawable.ic_set_exposure,
        R.drawable.ic_set_burst, R.drawable.ic_set_merge, R.drawable.ic_set_denoise, R.drawable.ic_set_lenses,
        R.drawable.ic_set_debug, R.drawable.ic_set_about,
    )
    // The list as cards: a heading and the page indices under it.
    val pageGroups = listOf(
        "Capture" to listOf(0, 1, 2, 3),
        "Processing" to listOf(4, 5, 6),
        "Camera" to listOf(SETTINGS_LENSES_TAB),
        "App" to listOf(8, 9),
    )
    // Page handles: the Settings*Tab pages identify themselves by these when they
    // report that they have filled the content column. They are never displayed.
    val allTabs = pageInfo.map { (name, _) -> Button(this).apply { text = name } }
    val jpegTab = allTabs[1]
    // A page's sub-page (see SettingsKit.collapseRange) takes over the content area.
    var subTitle: String? = null
    var subBody: View? = null
    var pageTitle: CharSequence = ""
    var pageScrollY = 0
    lateinit var closeSubPage: () -> Unit
    lateinit var subPageStillValid: () -> Boolean
    fun markActive(active: Button) {
        pageTitle = active.text
        if (subTitle == null) titleView.text = pageTitle
        // The page rebuilt itself; if it no longer offers the open sub-page, leave it.
        else titleView.post { if (subTitle != null && !subPageStillValid()) closeSubPage() }
    }
    val content = SettingsCardColumn(this).apply { setPadding(0, 0, 0, dp(24)) }
    val kit = SettingsKit(this, content) { button, active -> styleSettingsButton(button, active) }
    fun polish() = kit.polish()
    val settingsScroll = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        scrollIndicators = 0
        isFillViewport = true
        // Scrolled rows dissolve under the header instead of being sliced by it.
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(20))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(content)
        })
    }
    val subHost = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(24))
        visibility = View.GONE
    }
    (settingsScroll.getChildAt(0) as LinearLayout).addView(subHost)
    closeSubPage = {
        subHost.removeAllViews()
        subHost.visibility = View.GONE
        content.visibility = View.VISIBLE
        subTitle = null
        subBody = null
        titleView.text = pageTitle
        settingsScroll.post { settingsScroll.scrollTo(0, pageScrollY) }
    }
    subPageStillValid = { subBody != null && kit.subPages[subTitle] === subBody }
    kit.openSubPageTitle = { subTitle }
    kit.openSubPage = { title, body ->
        val refresh = subTitle == title
        if (!refresh) pageScrollY = settingsScroll.scrollY
        subHost.removeAllViews()
        (body.parent as? ViewGroup)?.removeView(body)
        subHost.addView(body)
        subTitle = title
        subBody = body
        content.visibility = View.GONE
        subHost.visibility = View.VISIBLE
        titleView.text = title
        if (!refresh) {
            settingsScroll.scrollTo(0, 0)
            content.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
    container.addView(settingsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

    // The activity theme, not a dialog theme: a real fullscreen window like the camera itself.
    val dialog = android.app.Dialog(this, R.style.Theme_RawLens)
    var currentTab = SETTINGS_ROOT
    val page = SettingsPage(content, kit, ::markActive, allTabs)
    page.dialog = dialog
    fun showGeneralTab() = showGeneralSettings(page)
    lateinit var photoTab: PhotoSettingsTab
    fun showJpegTab() {
        photoTab.show()
        markActive(jpegTab)
        polish()
    }
    photoTab = PhotoSettingsTab(kit, object : PhotoSettingsTab.Host {
        override val controller: RawCameraController get() = this@showSettings.controller
        override var developedFormat: DevelopedFormat
            get() = this@showSettings.developedFormat
            set(value) { this@showSettings.developedFormat = value }
        override fun jpegOutputSettings() = this@showSettings.jpegOutputSettings()
        override fun jxlOutputSettings() = this@showSettings.jxlOutputSettings()
        override fun lensPreferences() = this@showSettings.lensPreferences()
        override fun setStatus(message: String) = this@showSettings.setStatus(message)
        override fun refreshCaptureFormatControl() = this@showSettings.refreshCaptureFormatControl()
        override fun clearTabStatusViews() {
            rawZslSettingsStatus = null
            sidecarSettingsStatus = null
        }
        override fun reshow() {
            currentTab = 1
            showJpegTab()
        }
    })
    val tabPages: List<() -> Unit> = listOf(
        { showGeneralTab() },
        { showJpegTab() },
        { showVideoSettings(page) },
        { showExposureSettings(page) },
        { showBurstSettings(page) },
        { showMergeSettings(page) },
        { showDenoiseSettings(page) },
        { },
        { showDebugSettings(page) },
        { showAboutSettings(page) }
    )
    lateinit var goTab: (Int) -> Unit
    /** Category list: grouped cards, a row per page with its icon, name and summary. */
    fun showRoot() {
        rawZslSettingsStatus = null
        sidecarSettingsStatus = null
        content.removeAllViews()
        titleView.text = "Settings"
        pageGroups.forEach { (group, pages) ->
            content.addView(kit.sectionTitle(group))
            pages.forEach { index ->
                val (name, pageSummary) = pageInfo[index]
                val summary = if (index != SETTINGS_LENSES_TAB) pageSummary else {
                    val count = selectedLensIds().size
                    "$count RAW lens${if (count == 1) "" else "es"} enabled · discover and choose"
                }
                content.addView(kit.navRow(name, summary, pageIcons[index]) { goTab(index) },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
    }
    goTab = { index ->
        if (index == SETTINGS_LENSES_TAB) {
            // Lenses has no page of its own: the row opens lens discovery over the list.
            content.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            showLensDiscovery(firstRun = false)
        } else {
            val target = if (index in tabPages.indices) index else SETTINGS_ROOT
            val changed = target != currentTab
            if (changed || subTitle != null) {
                if (subTitle != null) closeSubPage()
                kit.subPages.clear()
                pageScrollY = 0
            }
            currentTab = target
            if (target == SETTINGS_ROOT) showRoot() else tabPages[target]()
            backButton.contentDescription = if (target == SETTINGS_ROOT) "Close settings" else "Back"
            settingsScroll.scrollTo(0, 0)
            if (changed) content.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
    // One back rule everywhere: a sub-page returns to its page, a page to the list,
    // and the list closes Settings.
    fun goBack() {
        when {
            subTitle != null -> closeSubPage()
            currentTab == SETTINGS_ROOT -> dialog.dismiss()
            else -> goTab(SETTINGS_ROOT)
        }
    }
    backButton.setOnClickListener { goBack() }
    dialog.setOnKeyListener { _, keyCode, event ->
        if (keyCode == KeyEvent.KEYCODE_BACK && currentTab != SETTINGS_ROOT) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) goBack()
            true
        } else false
    }
    goTab(if (initialTab == SETTINGS_LENSES_TAB) SETTINGS_ROOT else initialTab)
    settingsDialog = dialog
    // Settings covers the viewfinder, so close the camera while it is open (battery).
    // A recording keeps the camera: the recorder owns it.
    pauseCameraForSettings()
    dialog.setOnDismissListener {
        rawZslSettingsStatus = null
        sidecarSettingsStatus = null
        // A replacement Settings (reopen) keeps the camera closed.
        if (settingsDialog === dialog) {
            settingsDialog = null
            startCameraWhenReady()
        }
    }
    container.setPadding(dp(12), 0, dp(12), 0)
    RawLensUi.showFullscreen(dialog, container)
    if (initialTab == SETTINGS_LENSES_TAB) goTab(SETTINGS_LENSES_TAB)
    // Coming back from lens discovery: the Lenses row shows the saved selection.
    dialog.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener { focused ->
        if (focused && currentTab == SETTINGS_ROOT && dialog.isShowing) showRoot()
    }
}

internal fun MainActivity.rawZslSettingsText(status: RawZslStatus): String {
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
