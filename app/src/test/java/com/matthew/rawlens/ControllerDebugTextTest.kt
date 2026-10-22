// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerDebugTextTest {
    private fun snapshot() = DebugOverlaySnapshot(
        afMode = null, afTrigger = null, afState = null,
        cameraSequenceBusy = false, saveQueue = 2, saveQueueLimit = 6,
        aeMode = null, aeTrigger = null, aeState = null, oisMode = null,
        iso = 400, exposureNanos = 10_000_000L,
        programEnabled = true, programProfile = ProgramAeProfile(balance = 0.5f, evBias = 0.3f),
        isoCapHit = false, shutterCapHit = true, ettrCaptureActive = false,
        programTargetIso = 800, programTargetShutterNanos = 20_000_000L,
        liveIso = null, liveShutterNanos = null,
        programConverged = true, programBrightness = 0.25f,
        ettrEnabled = false, ettrModeAvailable = true,
        ettrIso = null, ettrShutterNanos = null, ettrConverged = false,
        ettrHottest = Float.NaN, ettrClipHot = Float.NaN, ettrClipSecond = Float.NaN,
        zslStreaming = true, zslBuffered = 3, zslCapacity = 8, gyroMotion = 0.0125f,
    )

    @Test fun formatExposureFractionsAndSeconds() {
        assertEquals("1/100", ControllerDebugText.formatExposure(10_000_000L))
        assertEquals("1/3", ControllerDebugText.formatExposure(333_333_333L))
        assertEquals("2.5s", ControllerDebugText.formatExposure(2_500_000_000L))
    }

    @Test fun clipPercentHandlesNaN() {
        assertEquals("--", ControllerDebugText.clipPercent(Float.NaN))
        assertEquals("1.250%", ControllerDebugText.clipPercent(0.0125f))
    }

    @Test fun overlayProgramAndZslLines() {
        val lines = ControllerDebugText.debugOverlay(snapshot()).split("\n")
        assertEquals("SAVE_QUEUE 2/6", lines[4])
        assertEquals("ISO 400  EXP 1/100", lines[9])
        assertEquals("P_AE ON B01 0.50 METER CENTER_WEIGHTED LOCK NONE EV +0.3 ISO_CAP OK S_CAP HIT", lines[10])
        assertEquals("P_TARGET TGT ISO 800 EXP 1/50 LIVE -- LOCKED BR 0.25", lines[11])
        assertEquals("ETTR OFF METERING", lines[12])
        assertEquals("RAW_ZSL ON BUF 3/8 GYRO 0.013", lines[13])
    }

    @Test fun overlayEttrFrozenAndActive() {
        val text = ControllerDebugText.debugOverlay(snapshot().copy(
            ettrCaptureActive = true, ettrEnabled = true, ettrIso = 100,
            ettrShutterNanos = 4_000_000L, ettrHottest = 0.9f, ettrClipHot = 0.001f,
        ))
        assertTrue(text.contains("P_TARGET FROZEN>ETTR\n"))
        assertTrue(text.contains("ETTR ON ISO 100 EXP 1/250 TRACKING HOT 0.90 CLIP 0.100%/--\n"))
    }

    @Test fun rawZslStatusBranches() {
        fun status(
            requested: Boolean = true, disabled: Boolean = false, capacity: Int = 8,
            broken: Boolean = false, streaming: Boolean = true, filled: Boolean = true,
        ) = ControllerDebugText.rawZslStatus(requested, disabled, null, capacity, broken, streaming, filled, 8)
        assertEquals(RawZslState.OFF, status(requested = false).state)
        assertEquals(RawZslStatus(RawZslState.FALLBACK, "Unavailable; using normal RAW capture"),
            status(disabled = true))
        assertEquals(ControllerDebugText.ZSL_NO_CAPACITY_DETAIL, status(capacity = 0).detail)
        assertEquals(RawZslState.WARMING_UP, status(broken = true).state)
        assertEquals(RawZslStatus(RawZslState.ACTIVE, "8/8 RAW frames buffered"), status())
        assertEquals("Waiting for a paired RAW frame", status(filled = false).detail)
    }

    @Test fun rawVfLine() {
        val stats = RawVfStats(fps = 29.97f, frameMs = 0f, vfWidth = 1280, vfHeight = 960,
            rawWidth = 4080, rawHeight = 3060, glActive = true, starved = true, busySkips = 0,
            gpu = true, jpeg = true, exposureEv = 0.5f)
        assertEquals(
            "30.0 FPS STARVED -- ms\nVF: 1280×960 GPU AUTO ⇄ JPG +0.5EV · RAW: 4080×3060\nEffects: Yes (RAW +UHDR)",
            ControllerDebugText.rawVfDebug(stats, listOf("UHDR")))
        assertEquals("Effects: No",
            ControllerDebugText.rawVfDebug(stats.copy(starved = false), emptyList()).lines().last())
    }
}
