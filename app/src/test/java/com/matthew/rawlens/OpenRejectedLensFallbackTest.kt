// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRejectedLensFallbackTest {
    // Regression: on vivo V2562 a saved lens resolves characteristics yet the
    // camera service rejects its open ID as an unknown device, throwing
    // IllegalArgumentException off CameraManager.openCamera on the camera
    // thread (FATAL EXCEPTION on RawCamera). The open must fall through to
    // the next lens instead of crashing.
    @Test
    fun rejectedLensFallsThroughToNext() {
        val routes = mapOf("3" to "3", "0" to "0")
        assertEquals(
            "0",
            SessionRecoveryPolicy.nextLensAfterOpenRejected(
                listOf("3", "0"), setOf("3"), routes::get
            )
        )
    }

    @Test
    fun compositeSiblingsSharingRejectedLogicalDieTogether() {
        // "3/2" and "3/4" both open logical camera 3: rejecting 3 excludes both.
        val routes = mapOf("3/2" to "3", "3/4" to "3", "0" to "0")
        assertEquals(
            "0",
            SessionRecoveryPolicy.nextLensAfterOpenRejected(
                listOf("3/2", "3/4", "0"), setOf("3"), routes::get
            )
        )
    }

    @Test
    fun unresolvableCandidatesSkipped() {
        val routes = mapOf("0" to "0")
        assertEquals(
            "0",
            SessionRecoveryPolicy.nextLensAfterOpenRejected(
                listOf("bogus", "0"), emptySet(), routes::get
            )
        )
    }

    @Test
    fun noSurvivorMeansTerminalState() {
        val routes = mapOf("3/2" to "3", "0" to "0")
        assertNull(
            SessionRecoveryPolicy.nextLensAfterOpenRejected(
                listOf("3/2", "0"), setOf("3", "0"), routes::get
            )
        )
    }

    @Test
    fun emptyCandidatesMeansTerminalState() {
        assertNull(
            SessionRecoveryPolicy.nextLensAfterOpenRejected(
                emptyList(), setOf("3"), { "0" }
            )
        )
    }

    @Test
    fun noRejectionsKeepsFirstCandidate() {
        val routes = mapOf("0" to "0", "2" to "2")
        assertEquals(
            "0",
            SessionRecoveryPolicy.nextLensAfterOpenRejected(
                listOf("0", "2"), emptySet(), routes::get
            )
        )
    }

    // Regression: on Xiaomi 24030PN60G all six lenses reported unknown-device
    // within 2 ms (a camera-service hiccup); the old code denylisted every
    // healthy lens that had opened seconds earlier and wedged the VF ~50 s.
    @Test
    fun previouslyOpenedIdIsNeverDenylisted() {
        assertFalse(SessionRecoveryPolicy.shouldDenylistOnOpenRejected("0", setOf("0", "6")))
        assertTrue(SessionRecoveryPolicy.shouldDenylistOnOpenRejected("3", setOf("0", "6")))
        assertTrue(SessionRecoveryPolicy.shouldDenylistOnOpenRejected("3", emptySet()))
    }

    // Regression (2026-10-29, Xiaomi 24030PN60G HyperOS 3): a HAL fault on
    // 6/3 restarted the camera provider; 9 (never opened this run) was then
    // rejected 0.4 s later and persisted DEAD for every 9/x route.
    @Test
    fun rejectionShortlyAfterDeviceFaultIsTransient() {
        assertFalse(SessionRecoveryPolicy.shouldDenylistOnOpenRejected("9", setOf("0", "6"), 400L))
        assertFalse(SessionRecoveryPolicy.shouldDenylistOnOpenRejected(
            "9", emptySet(), SessionRecoveryPolicy.HAL_RESTART_GRACE_MS - 1))
        assertTrue(SessionRecoveryPolicy.shouldDenylistOnOpenRejected(
            "9", emptySet(), SessionRecoveryPolicy.HAL_RESTART_GRACE_MS))
        assertTrue(SessionRecoveryPolicy.shouldDenylistOnOpenRejected("9", emptySet(), null))
    }
}
