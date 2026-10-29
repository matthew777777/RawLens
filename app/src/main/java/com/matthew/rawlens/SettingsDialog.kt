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

// The Settings dialog shell (tab chips, paging, swipe) around the Settings*Tab pages. Moved verbatim from MainActivity.

/**
 * Settings sheet. [initialTab] selects the opening page (see
 * [SETTINGS_VIDEO_TAB]); every entry point except the video-mode
 * settings button uses the default General tab.
 */
internal fun MainActivity.showSettings(initialTab: Int = 0) {
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(8))
    }
    // Modern chip tab row (mirrors PROGRAM AE panel styling): scrollable so
    // seven tabs fit without squeezing. Active tab uses the accent chip.
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
    val kit = SettingsKit(this, content) { button, active -> styleSettingsButton(button, active) }
    fun sectionTitle(text: String): TextView = kit.sectionTitle(text)
    fun sectionDesc(text: String): TextView = kit.sectionDesc(text)
    fun settingsButton(label: String, active: Boolean = false): Button = kit.settingsButton(label, active)
    fun settingsCheck(label: String, checked: Boolean): CheckBox = kit.settingsCheck(label, checked)
    fun polish() = kit.polish()
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
    val page = SettingsPage(content, kit, ::markActive, allTabs)
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
    fun showVideoTab() = showVideoSettings(page)
    fun showExposureTab() = showExposureSettings(page)
    fun showBurstTab() = showBurstSettings(page)
    fun showDenoiseTab() = showDenoiseSettings(page)
    fun showLensesTab() = showLensesSettings(page)
    fun showDebugTab() = showDebugSettings(page)
    fun showAboutTab() = showAboutSettings(page)
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
    page.dialog = dialog
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
