// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

/** Windowed swap-rate meter pins (fake timestamps, no GL needed). */
class BguFpsWindowTest {
    @Test fun steadyThirtyReadsThirty() {
        val w = BguFpsWindow()
        assertEquals(0f, w.fps, 0f)
        // 60 swaps over 2000 ms: exact windowed rate, no smoothing swing.
        for (i in 0..59) w.onSwap(i * 2000L / 59)
        assertEquals(30f, w.fps, 0.001f)
    }

    @Test fun burstThenIdleReadsWindowedNotInstantaneous() {
        val w = BguFpsWindow()
        // Five back-to-back swaps (an EMA of 1000/dt would spike past 200)
        // then silence until the window closes.
        for (i in 0..4) w.onSwap(i.toLong())
        w.onSwap(2000L)
        assertEquals(3f, w.fps, 0.001f)
    }

    @Test fun resetReopensWindow() {
        val w = BguFpsWindow()
        for (i in 0..10) w.onSwap(i * 100L)
        w.reset()
        assertEquals(0f, w.fps, 0f)
        w.onSwap(5000L)
        w.onSwap(7000L)
        assertEquals(1f, w.fps, 0.001f)
    }
}
