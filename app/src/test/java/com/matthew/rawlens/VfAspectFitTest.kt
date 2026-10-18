// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the WYSIWYG aspect-fit: no stretch on any surface/frame combination. */
class VfAspectFitTest {

    @Test
    fun tallPortraitBoxLetterboxes34Frame() {
        // The field shape: 1280x2074-class tall box, 3:4 portrait content
        // (90-degree sensor, landscape frame). Full width, centered bars.
        val f = VfAspectFit.fit(1280, 2074, 1360, 1020, 90)
        assertArrayEquals(intArrayOf(0, (2074 - 1706) / 2, 1280, 1706), f)
    }

    @Test
    fun exactFitReturnsFullSurface() {
        assertArrayEquals(intArrayOf(0, 0, 960, 1280), VfAspectFit.fit(960, 1280, 1020, 1360, 0))
        assertArrayEquals(intArrayOf(0, 0, 960, 1280), VfAspectFit.fit(960, 1280, 1360, 1020, 90))
    }

    @Test
    fun wideSurfacePillarboxes() {
        val f = VfAspectFit.fit(1920, 1080, 1020, 1360, 0)
        // Portrait content in a landscape surface: full height, side bars.
        assertArrayEquals(intArrayOf((1920 - 810) / 2, 0, 810, 1080), f)
    }

    @Test
    fun rotation270SwapsLike90() {
        assertArrayEquals(
            VfAspectFit.fit(1280, 2074, 1360, 1020, 90),
            VfAspectFit.fit(1280, 2074, 1360, 1020, 270)
        )
    }

    @Test
    fun fittedRectKeepsFrameAspect() {
        // Any box: fitted w/h must equal displayed frame w/h within 1px.
        listOf(
            intArrayOf(1280, 2074, 1360, 1020, 90),
            intArrayOf(1080, 2400, 2016, 1512, 90),
            intArrayOf(1440, 2560, 800, 600, 270),
            intArrayOf(1920, 1080, 960, 1280, 0)
        ).forEach { (sw, sh, iw, ih, rot) ->
            val f = VfAspectFit.fit(sw, sh, iw, ih, rot)
            val (dw, dh) = if (rot == 90 || rot == 270) ih to iw else iw to ih
            val cross = f[2].toLong() * dh - f[3].toLong() * dw
            // Pillarbox truncates down (< 1px slack scaled by dh),
            // letterbox truncates down (< 1px slack scaled by dw).
            assertTrue("aspect drift $cross", cross > -dh && cross < dw)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptySurface() {
        VfAspectFit.fit(0, 100, 10, 10, 90)
    }

    @Test
    fun tallPortraitBoxCropsSides() {
        // The field shape: 3:4 portrait content in a taller box. Full
        // height, symmetric side crop, no bars, kept aspect.
        val f = VfAspectFit.fill(1280, 2074, 1360, 1020, 90)
        assertArrayEquals(intArrayOf((1280 - 1555) / 2, 0, 1555, 2074), f)
        assertTrue(f[0] < 0 && f[1] == 0 && f[2] > 1280 && f[3] == 2074)
    }

    @Test
    fun wideSurfaceCropsTopBottom() {
        val f = VfAspectFit.fill(1920, 1080, 1020, 1360, 0)
        // Portrait content in a landscape surface: full width, top/bottom crop.
        assertTrue(f[0] == 0 && f[1] < 0 && f[2] == 1920 && f[3] > 1080)
    }

    @Test
    fun coverExactFitReturnsFullSurface() {
        assertArrayEquals(intArrayOf(0, 0, 960, 1280), VfAspectFit.fill(960, 1280, 1360, 1020, 90))
    }

    @Test
    fun coverKeepsFrameAspect() {
        listOf(
            intArrayOf(1280, 2074, 1360, 1020, 90),
            intArrayOf(1080, 2400, 2016, 1512, 90),
            intArrayOf(1920, 1080, 960, 1280, 0)
        ).forEach { (sw, sh, iw, ih, rot) ->
            val f = VfAspectFit.fill(sw, sh, iw, ih, rot)
            val (dw, dh) = if (rot == 90 || rot == 270) ih to iw else iw to ih
            // Cover spans the surface on both axes...
            assertTrue(f[0] <= 0 && f[1] <= 0 && f[0] + f[2] >= sw && f[1] + f[3] >= sh)
            // ...with the frame aspect intact within 1px.
            val cross = f[2].toLong() * dh - f[3].toLong() * dw
            assertTrue("aspect drift $cross", cross > -dh && cross < dw)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun coverRejectsEmptySurface() {
        VfAspectFit.fill(100, 0, 10, 10, 0)
    }
}
