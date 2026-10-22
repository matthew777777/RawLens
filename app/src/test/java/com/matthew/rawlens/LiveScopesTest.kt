// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveScopesTest {
    private class FakeHost : LiveScopes.Host {
        var manual = false
        var touch = false
        val active = ArrayList<Boolean>()
        override fun jpegOutputSettings() = JpegOutputSettings()
        override fun vfStats(): RawVfStats? = null
        override fun manualFocusSet() = manual
        override fun touchFocusActive() = touch
        override fun onRawHistogram(histogram: RgbHistogram) = Unit
        override fun onRawWaveform(waveform: RgbWaveform) = Unit
        override fun onFocusPeaking(frame: FocusPeakingFrame) = Unit
        override fun onFocusPeakingActive(active: Boolean) { this.active += active }
    }

    private fun scopes(host: FakeHost, peaking: Boolean = true) =
        LiveScopes(host, SamplerGuard(), true, false, ScopeMode.HISTOGRAM, peaking)

    @Test fun peakingVisibilityIsEdgeTriggered() {
        val host = FakeHost(); val s = scopes(host)
        s.publishPeakingActiveIfChanged()
        assertTrue(host.active.isEmpty())           // idle: hidden, same as initial
        host.manual = true
        s.publishPeakingActiveIfChanged()
        s.publishPeakingActiveIfChanged()
        assertEquals(listOf(true), host.active)     // one SHOWN edge
        host.manual = false
        s.publishPeakingActiveIfChanged()
        assertEquals(listOf(true, false), host.active)
    }

    @Test fun masterSwitchOffHidesPeakingWhileFocusing() {
        val host = FakeHost(); val s = scopes(host)
        host.touch = true
        s.publishPeakingActiveIfChanged()
        s.setPeakingEnabled(false)
        assertEquals(listOf(true, false), host.active)
    }

    @Test fun histogramSwitch() {
        val host = FakeHost(); val s = scopes(host)
        assertTrue(s.histogramEnabled)
        s.setHistogramEnabled(false)
        assertFalse(s.histogramEnabled)
    }

    @Test fun samplerGuardSwallowsAndReturnsNull() {
        val guard = SamplerGuard()
        assertNull(guard.run<Int>("t") { throw NoSuchMethodError("api30") })
        assertEquals(7, guard.run("t") { 7 })
    }
}
