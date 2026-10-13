// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController

/**
 * Single owner of the app's fullscreen chrome. The camera must stay
 * immersive across permission dialogs, folder pickers, and first-run
 * onboarding: anything that shows the status bar must re-hide it instead
 * of leaving the system bars up until the next launch.
 */
object ImmersiveMode {
    /** Legacy (pre-R) `systemUiVisibility` mask: hide + lay out under both bars. */
    @Suppress("DEPRECATION")
    val legacySystemUiMask: Int =
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN

    /**
     * Hides the status and navigation bars. Re-entrant: safe to call from
     * `onCreate`, `onResume`, and `onWindowFocusChanged`.
     *
     * @param sdkInt injectable only for JVM tests (`Build.VERSION.SDK_INT`
     * is 0 under `unitTests.isReturnDefaultValues`).
     */
    fun apply(window: Window, sdkInt: Int = Build.VERSION.SDK_INT) {
        if (sdkInt >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = legacySystemUiMask
        }
    }
}
