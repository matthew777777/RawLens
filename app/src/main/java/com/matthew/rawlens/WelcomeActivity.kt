// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView

/**
 * First launch: one black screen with a single "Allow camera" action, then
 * straight into [MainActivity] (which runs lens discovery on first run).
 *
 * Every other ask lives next to its feature instead of in setup — location
 * after the first saved photo, the photo folder at the first ZSL burst,
 * the keep-up-to-date ask after a few launches (see [FirstUseAsks]).
 *
 * Returning users, or anyone who already granted the camera, never see
 * this screen. The requirements line shows only when the phone fails it.
 */
class WelcomeActivity : Activity() {

    /** Same UI geometry at every system display-size / font-size setting. */
    override fun attachBaseContext(newBase: android.content.Context) =
        super.attachBaseContext(StableUiScale.wrap(newBase))

    private var status: TextView? = null
    private var button: Button? = null
    private var contentReady = false

    /** Set after a denial the system will no longer re-prompt for. */
    private var deniedForGood = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isOnboardingDone() || hasCameraPermission()) {
            completeAndOpenCamera()
            return
        }
        setContentView(R.layout.activity_welcome)
        contentReady = true
        ImmersiveMode.apply(window)
        status = findViewById(R.id.welcomeStatus)
        button = findViewById(R.id.openCameraButton)

        unsupportedReason()?.let { reason ->
            status?.text = reason
            button?.isEnabled = false
            button?.alpha = 0.5f
            return
        }
        button?.setOnClickListener {
            if (deniedForGood) openAppSettings()
            else requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!contentReady) return
        ImmersiveMode.apply(window)
        // Back from system Settings with the grant flipped on.
        if (hasCameraPermission()) completeAndOpenCamera()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && contentReady) ImmersiveMode.apply(window)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_PERMISSION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            completeAndOpenCamera()
            return
        }
        deniedForGood = !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        status?.text = if (deniedForGood) {
            "Camera access is off for RawLens. Turn it on in Settings → Permissions."
        } else {
            "RawLens needs the camera to shoot. Nothing leaves your phone."
        }
        button?.text = if (deniedForGood) "Open settings" else "Allow camera"
    }

    /** Null when the phone can run RawLens; otherwise the one line explaining why not. */
    private fun unsupportedReason(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "RawLens needs Android 11 or newer."
        }
        return null // Lens discovery checks RAW routes after camera permission is granted.
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        }
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun isOnboardingDone(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_WELCOME_COMPLETE, false) ||
            prefs.getBoolean(KEY_LENS_SETUP_COMPLETE, false)
    }

    private fun completeAndOpenCamera() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WELCOME_COMPLETE, true)
            .apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private companion object {
        const val CAMERA_PERMISSION = 42
        const val PREFS_NAME = "rawlens_settings"
        const val KEY_WELCOME_COMPLETE = "welcome_complete"
        const val KEY_LENS_SETUP_COMPLETE = "lens_setup_complete"
    }
}
