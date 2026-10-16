// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.core.view.WindowCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * First-install regression: completing lens onboarding used to call
 * `showStatusBar()`, leaving the notification/status bar over the camera
 * until the app was killed and reopened. All fullscreen transitions now
 * go through [ImmersiveMode.apply], which only ever hides.
 */
class ImmersiveModeTest {
    @Test fun `modern apply hides status and navigation bars`() {
        val window = mock(Window::class.java)
        val controller = mock(WindowInsetsController::class.java)
        `when`(window.insetsController).thenReturn(controller)

        mockStatic(WindowCompat::class.java).use { compat ->
            ImmersiveMode.apply(window, Build.VERSION_CODES.R)

            compat.verify { WindowCompat.setDecorFitsSystemWindows(window, false) }
        }
        verify(controller).hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        verify(controller).systemBarsBehavior =
            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        verify(controller, never()).show(anyInt())
    }

    @Test fun `modern apply tolerates missing insets controller`() {
        val window = mock(Window::class.java)
        `when`(window.insetsController).thenReturn(null)

        mockStatic(WindowCompat::class.java).use { compat ->
            ImmersiveMode.apply(window, Build.VERSION_CODES.R)

            compat.verify { WindowCompat.setDecorFitsSystemWindows(window, false) }
        }
        verify(window, never()).decorView
    }

    @Test fun `legacy apply sets full immersive-sticky mask`() {
        val window = mock(Window::class.java)
        val decorView = mock(View::class.java)
        `when`(window.decorView).thenReturn(decorView)

        ImmersiveMode.apply(window, Build.VERSION_CODES.Q)

        verify(decorView).systemUiVisibility = ImmersiveMode.legacySystemUiMask
    }

    @Test fun `legacy mask hides both bars and sticks`() {
        val mask = ImmersiveMode.legacySystemUiMask
        assertNotEquals(0, mask and View.SYSTEM_UI_FLAG_FULLSCREEN)
        assertNotEquals(0, mask and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
        assertNotEquals(0, mask and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        assertNotEquals(0, mask and View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        assertNotEquals(0, mask and View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        assertEquals(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN,
            mask
        )
    }
}
