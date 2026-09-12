// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawStreamGatingTest {
    // Regression: a 6-frame JPEG burst must not take the RAW viewfinder down for
    // the whole development queue. Queued saves are not a gating input at all —
    // only an active forward capture stops the live RAW streams.
    @Test
    fun requestedStreamRuns() {
        assertTrue(RawCameraController.shouldRunRawStream(
            requested = true, disabledForSession = false,
            readerReady = true, captureActive = false
        ))
    }

    @Test
    fun activeForwardCaptureStopsStream() {
        // Only an in-flight capture sequence stops the live RAW streams.
        assertFalse(RawCameraController.shouldRunRawStream(
            requested = true, disabledForSession = false,
            readerReady = true, captureActive = true
        ))
    }

    @Test
    fun sessionFallbackAndMissingReaderStopStream() {
        assertFalse(RawCameraController.shouldRunRawStream(
            requested = true, disabledForSession = true,
            readerReady = true, captureActive = false
        ))
        assertFalse(RawCameraController.shouldRunRawStream(
            requested = true, disabledForSession = false,
            readerReady = false, captureActive = false
        ))
        assertFalse(RawCameraController.shouldRunRawStream(
            requested = false, disabledForSession = false,
            readerReady = true, captureActive = false
        ))
    }

}
