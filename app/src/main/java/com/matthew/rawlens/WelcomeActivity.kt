// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

/**
 * First-install welcome screen.
 *
 * Flow: welcome content (from README highlights) -> "Open Camera" button ->
 * CAMERA runtime permission -> [MainActivity] which runs lens discovery on
 * first run. Everything after that is unchanged.
 *
 * Returning users (welcome already completed, or lenses already set up from
 * an earlier version without this screen) skip straight to [MainActivity].
 */
class WelcomeActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isOnboardingDone()) {
            openCamera()
            return
        }
        setContentView(R.layout.activity_welcome)
        status = findViewById(R.id.welcomeStatus)
        findViewById<Button>(R.id.openCameraButton).setOnClickListener {
            if (checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                completeAndOpenCamera()
            } else {
                requestPermissions(
                    arrayOf(Manifest.permission.CAMERA),
                    CAMERA_PERMISSION,
                )
            }
        }
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
        } else {
            status.text = "Camera access is needed to discover RAW lenses and shoot. " +
                "Tap Open Camera to try again."
        }
    }

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
        openCamera()
    }

    private fun openCamera() {
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
