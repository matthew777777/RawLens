// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*

class VendorSessionOpenerTest {
    private class Harness(mode: Int = 37379, params: Boolean = true) {
        val device = mock(CameraDevice::class.java)
        val base = mock(SessionConfiguration::class.java)
        val submissions = mutableListOf<Pair<VendorSessionAttempt, CameraCaptureSession.StateCallback>>()
        var configured: VendorSessionAttempt? = null
        var configuredLegacy = false
        var failed = 0
        var current = true
        var syncReject = false
        var legacyCallback: CameraCaptureSession.StateCallback? = null
        val opener: VendorSessionOpener
        init {
            `when`(base.sessionParameters).thenReturn(if (params) mock(CaptureRequest::class.java) else null)
            doAnswer {
                if (syncReject) throw UnsupportedOperationException("modern unavailable")
                null
            }.`when`(device).createCaptureSession(any(SessionConfiguration::class.java))
            doAnswer {
                legacyCallback = it.getArgument(1)
                null
            }.`when`(device).createCaptureSession(anyList<Surface>(), any(CameraCaptureSession.StateCallback::class.java), any(Handler::class.java))
            opener = VendorSessionOpener(device, base, mode, emptyList(), mock(Handler::class.java),
                { current }, { attempt, legacy, _ -> configured = attempt; configuredLegacy = legacy },
                { failed++ }, {}, { _, attempt, callback ->
                    submissions += attempt to callback
                    mock(SessionConfiguration::class.java)
                })
        }
        fun rejectLast() = submissions.last().second.onConfigureFailed(mock(CameraCaptureSession::class.java))
    }

    @Test fun `async mode rejection retries standard with parameters then without and stops`() {
        val h = Harness()
        h.opener.open()
        assertEquals(VendorSessionAttempt(37379, true), h.submissions.single().first)
        h.rejectLast()
        assertEquals(VendorSessionAttempt(0, true), h.submissions.last().first)
        h.rejectLast()
        assertEquals(VendorSessionAttempt(0, false), h.submissions.last().first)
        h.rejectLast()
        assertEquals(3, h.submissions.size)
        assertEquals(1, h.failed)
        assertNull(h.configured)
        assertNull(h.legacyCallback)
    }

    @Test fun `successful vendor configure keeps parameters and submits once`() {
        val h = Harness()
        h.opener.open()
        h.submissions.single().second.onConfigured(mock(CameraCaptureSession::class.java))
        assertEquals(VendorSessionAttempt(37379, true), h.configured)
        assertEquals(1, h.submissions.size)
        assertEquals(0, h.failed)
    }

    @Test fun `late callbacks from rejected attempts cannot install stale sessions`() {
        val h = Harness()
        h.opener.open()
        val stale = h.submissions.single().second
        h.rejectLast()
        val lateSession = mock(CameraCaptureSession::class.java)
        stale.onConfigured(lateSession)
        verify(lateSession).close()
        stale.onConfigureFailed(mock(CameraCaptureSession::class.java))
        assertEquals(2, h.submissions.size)
        assertNull(h.configured)
    }

    @Test fun `closed device callbacks cannot retry or configure`() {
        val h = Harness()
        h.opener.open()
        h.current = false
        h.rejectLast()
        assertEquals(1, h.submissions.size)
        assertEquals(0, h.failed)
    }

    @Test fun `default has one modern attempt and preserves legacy API fallback`() {
        val h = Harness(0, false)
        h.syncReject = true
        h.opener.open()
        assertEquals(1, h.submissions.size)
        assertNotNull(h.legacyCallback)
        h.legacyCallback!!.onConfigured(mock(CameraCaptureSession::class.java))
        assertEquals(VendorSessionAttempt(0, false), h.configured)
        assertTrue(h.configuredLegacy)
    }

    @Test fun `reserved standard modes cannot become accidental high speed sessions`() {
        assertFalse(VendorSessionAttempt.validMode(1))
        assertFalse(VendorSessionAttempt.validMode(32767))
        assertTrue(VendorSessionAttempt.validMode(32768))
        assertEquals(listOf(VendorSessionAttempt(0, false)), VendorSessionAttempt.sequence(1, false))
    }
}
