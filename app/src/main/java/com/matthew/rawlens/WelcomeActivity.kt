// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * First-install welcome screens.
 *
 * Order ([WelcomeFlow]): welcome content -> one full-black permissions page
 * (camera grant + photo-folder grant + optional location tags + the
 * keep-up-to-date ask, updater builds only) -> [MainActivity] which runs
 * lens discovery on first run.
 * The update ask never layers over the welcome content anymore; returning
 * users (welcome already completed, or lenses already set up from an
 * earlier version without these screens) skip straight to [MainActivity],
 * where [UpdateConsent] still asks once. Everything after that is
 * unchanged.
 *
 * The folder grant is best-effort: a cancel, a deny, or an unavailable
 * picker stays on the permissions page (sidecars fall back to Downloads
 * until the folder is granted from burst settings). Continuing never
 * blocks on any grant: [MainActivity] re-asks the camera itself when it
 * still lacks it.
 */
class WelcomeActivity : Activity() {

    private var step = WelcomeFlow.Step.WELCOME

    private var welcomeStatus: TextView? = null

    private var cameraStatus: TextView? = null
    private var cameraButton: Button? = null
    private var folderStatus: TextView? = null
    private var locationSwitch: Switch? = null
    private var updateSection: LinearLayout? = null
    private var updateSwitch: Switch? = null
    /** True while the optional location request from Continue is outstanding. */
    private var awaitingLocationResult = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isOnboardingDone()) {
            openCamera()
            return
        }
        // No update dialog here: the keep-up-to-date ask has its own page
        // (see showPermissionsPage) instead of layering over welcome.
        showWelcomePage()
    }

    /** Welcome content page: nothing overlays it. */
    private fun showWelcomePage() {
        step = WelcomeFlow.Step.WELCOME
        clearPermissionRefs()
        setContentView(R.layout.activity_welcome)
        welcomeStatus = findViewById(R.id.welcomeStatus)
        findViewById<Button>(R.id.openCameraButton).setOnClickListener {
            if (hasCameraPermission()) {
                showPermissionsPage()
            } else {
                requestPermissions(
                    arrayOf(Manifest.permission.CAMERA),
                    CAMERA_PERMISSION,
                )
            }
        }
    }

    /**
     * Full-black permissions page: camera row, photo-folder row, the
     * optional location switch, and the keep-up-to-date ask (updater
     * builds only), then lens discovery.
     */
    private fun showPermissionsPage() {
        step = WelcomeFlow.Step.PERMISSIONS
        setContentView(R.layout.activity_welcome_permissions)
        cameraStatus = findViewById(R.id.permCameraStatus)
        cameraButton = findViewById(R.id.permCameraButton)
        folderStatus = findViewById(R.id.permFolderStatus)
        locationSwitch = findViewById(R.id.locationSwitch)
        updateSection = findViewById(R.id.updateSection)
        updateSwitch = findViewById(R.id.updateSwitch)
        updateSection?.visibility =
            if (WelcomeFlow.showsUpdateAsk(step, BuildConfig.ENABLE_UPDATE_CHECK)) {
                View.VISIBLE
            } else {
                View.GONE
            }
        refreshCameraRow()
        refreshFolderRow()
        cameraButton?.setOnClickListener {
            if (!hasCameraPermission()) {
                requestPermissions(
                    arrayOf(Manifest.permission.CAMERA),
                    CAMERA_PERMISSION,
                )
            }
        }
        findViewById<Button>(R.id.permFolderButton).setOnClickListener {
            SidecarTreeAccess.markFirstInstallPrompted(this)
            pickPhotoFolder()
        }
        findViewById<Button>(R.id.permContinueButton).setOnClickListener {
            continueFromPermissionsPage()
        }
    }

    private fun clearPermissionRefs() {
        cameraStatus = null
        cameraButton = null
        folderStatus = null
        locationSwitch = null
        updateSection = null
        updateSwitch = null
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun refreshCameraRow() {
        if (hasCameraPermission()) {
            cameraStatus?.text = "Granted — ready to discover RAW lenses and shoot."
            cameraButton?.text = "Camera Allowed ✓"
            cameraButton?.isEnabled = false
            cameraButton?.alpha = 0.6f
        } else {
            cameraStatus?.text = "Camera access is needed to discover RAW lenses and shoot."
            cameraButton?.text = "Allow Camera"
            cameraButton?.isEnabled = true
            cameraButton?.alpha = 1f
        }
    }

    private fun refreshFolderRow() {
        val uri = SidecarTreeAccess.savedTreeUri(this)
        folderStatus?.text = if (uri != null) {
            val label = SidecarTreeAccess.displayPath(uri)
            if (label.isNullOrEmpty()) "Folder chosen." else "Chosen: $label."
        } else {
            "Not chosen yet."
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION) {
            // Optional location decision from Continue: granted tags photos,
            // denied simply leaves them untagged. Either way entry proceeds.
            setSaveLocation(
                grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            )
            if (awaitingLocationResult) {
                awaitingLocationResult = false
                openCamera()
            }
            return
        }
        if (requestCode != CAMERA_PERMISSION) return
        if (step == WelcomeFlow.Step.PERMISSIONS) {
            refreshCameraRow()
            return
        }
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            showPermissionsPage()
        } else {
            welcomeStatus?.text = "Camera access is needed to discover RAW lenses and shoot. " +
                "Tap Get Started to try again."
        }
    }

    private fun isOnboardingDone(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_WELCOME_COMPLETE, false) ||
            prefs.getBoolean(KEY_LENS_SETUP_COMPLETE, false)
    }

    /**
     * Continue from the permissions page: records the page choices and
     * hands off to [MainActivity], which runs lens discovery on first run.
     * Location is optional and never blocks entry — a switch-on without a
     * grant asks once via the system dialog, then proceeds either way, and
     * the same toggle stays available later under Settings.
     */
    private fun continueFromPermissionsPage() {
        val wantLocation = locationSwitch?.isChecked == true
        if (wantLocation && !hasLocationPermission()) {
            completeWelcomeAndUpdateChoice()
            awaitingLocationResult = true
            requestPermissions(
                GpsLocationProvider.permissions(),
                LOCATION_PERMISSION,
            )
        } else {
            setSaveLocation(wantLocation && hasLocationPermission())
            completeAndOpenCamera()
        }
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /** Same opt-in MainActivity's Settings toggle persists. */
    private fun setSaveLocation(enabled: Boolean) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SAVE_LOCATION, enabled)
            .apply()
    }

    /**
     * Records the permissions-page choices (including the keep-up-to-date
     * switch on updater builds) and hands off to [MainActivity], which runs
     * lens discovery on first run.
     */
    private fun completeAndOpenCamera() {
        completeWelcomeAndUpdateChoice()
        openCamera()
    }

    private fun completeWelcomeAndUpdateChoice() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WELCOME_COMPLETE, true)
            .apply()
        if (BuildConfig.ENABLE_UPDATE_CHECK) {
            val enabled = updateSwitch?.isChecked ?: true
            getSharedPreferences(UpdateChecker.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(UpdateConsent.KEY_CONSENT_ASKED, true)
                .putBoolean(UpdateChecker.KEY_AUTO_CHECK, enabled)
                .apply()
        }
    }

    /**
     * Same folder grant burst settings offers, fired from the permissions
     * page. Never blocks entry: any failure stays on the page.
     */
    private fun pickPhotoFolder() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, SIDECAR_TREE_REQUEST)
        } catch (_: Exception) {
            refreshFolderRow()
        }
    }

    @Deprecated("Use picker intent result for the sidecar folder grant")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SIDECAR_TREE_REQUEST) return
        if (resultCode == RESULT_OK) {
            data?.data?.let { SidecarTreeAccess.saveTreeUri(this, it) }
        }
        // Stay on the permissions page so the keep-up-to-date ask and the
        // Continue button remain on their own screen; the row just reflects
        // the new folder state.
        refreshFolderRow()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (step == WelcomeFlow.Step.PERMISSIONS) {
            showWelcomePage()
            return
        }
        super.onBackPressed()
    }

    private fun openCamera() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private companion object {
        const val CAMERA_PERMISSION = 42
        const val LOCATION_PERMISSION = 43
        const val SIDECAR_TREE_REQUEST = 44
        const val PREFS_NAME = "rawlens_settings"
        const val KEY_WELCOME_COMPLETE = "welcome_complete"
        const val KEY_LENS_SETUP_COMPLETE = "lens_setup_complete"
        /** Kept in sync with MainActivity: the same opt-in its Settings toggle persists. */
        const val KEY_SAVE_LOCATION = "save_location_gps"
    }
}
