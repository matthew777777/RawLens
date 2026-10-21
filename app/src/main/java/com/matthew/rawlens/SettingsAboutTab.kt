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

/** Settings → About. Moved verbatim from MainActivity.showSettings (showAboutTab). */
internal fun MainActivity.showAboutSettings(p: SettingsPage) {
    val content = p.content
    fun polish() = p.kit.polish()
    fun markActive(active: Button) = p.markActive(active)
    val aboutTab = p.tabs[8]
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
                AlertDialog.Builder(this@showAboutSettings)
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
