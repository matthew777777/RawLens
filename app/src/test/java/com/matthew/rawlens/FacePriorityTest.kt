// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FacePriorityTest {
    private fun face(left: Int, top: Int, right: Int, bottom: Int, score: Int = 50) =
        HalFace(left, top, right, bottom, score)

    @Test fun `select returns null without faces`() {
        assertNull(FacePriority.select(emptyList(), null))
        assertNull(FacePriority.select(emptyList(), face(0, 0, 10, 10)))
    }

    @Test fun `select picks the largest face`() {
        val small = face(0, 0, 10, 10, score = 90)
        val large = face(100, 100, 200, 220, score = 10)

        assertEquals(large, FacePriority.select(listOf(small, large), null))
    }

    @Test fun `select breaks area ties by score`() {
        val low = face(0, 0, 10, 10, score = 20)
        val high = face(100, 100, 110, 110, score = 80)

        assertEquals(high, FacePriority.select(listOf(low, high), null))
    }

    @Test fun `select sticks to the previous face while visible`() {
        val previous = face(100, 100, 200, 200)
        val jittered = face(105, 98, 203, 202, score = 30)
        val bigger = face(300, 300, 500, 520, score = 99)

        assertEquals(jittered, FacePriority.select(listOf(jittered, bigger), previous))
    }

    @Test fun `select falls back to largest once the previous face is gone`() {
        val previous = face(100, 100, 200, 200)
        val a = face(0, 0, 40, 40)
        val b = face(300, 300, 400, 420)

        assertEquals(b, FacePriority.select(listOf(a, b), previous))
    }

    @Test fun `matches tolerates jitter but not jumps`() {
        val base = face(100, 100, 200, 200)

        assertTrue(FacePriority.matches(base, face(110, 95, 208, 205)))
        assertTrue(FacePriority.matches(base, base))
        assertFalse(FacePriority.matches(base, face(300, 300, 400, 400)))
        assertFalse(FacePriority.matches(base, face(100, 300, 200, 400)))
    }

    @Test fun `nearlyEqual gates repeating-request churn on jitter`() {
        val base = face(100, 100, 200, 200)

        assertTrue(FacePriority.nearlyEqual(base, face(102, 99, 201, 202), tolerancePx = 4))
        assertFalse(FacePriority.nearlyEqual(base, face(120, 100, 200, 200), tolerancePx = 4))
    }

    @Test fun `viewPoint inverts sensorPoint for every rotation and mirror`() {
        val rotations = intArrayOf(0, 90, 180, 270)
        val points = arrayOf(0.1f to 0.2f, 0.5f to 0.5f, 0.83f to 0.07f, 0f to 1f, 1f to 0f)
        for (rotation in rotations) for (mirrored in booleanArrayOf(false, true)) {
            for ((x, y) in points) {
                val sensor = RawPreviewGeometry.sensorPoint(x, y, rotation, mirrored)
                val back = RawPreviewGeometry.viewPoint(sensor.first, sensor.second, rotation, mirrored)
                assertEquals("rot=$rotation mirrored=$mirrored", x, back.first, 1e-6f)
                assertEquals("rot=$rotation mirrored=$mirrored", y, back.second, 1e-6f)
            }
        }
    }

    @Test fun `mapToView lands a centered sensor box on the view center`() {
        // 4000x3000 sensor, typical rear portrait geometry (rotation 90, not mirrored).
        val active = SensorActiveArray(0, 0, 4000, 3000)
        val box = FacePriority.mapToView(face(1800, 1300, 2200, 1700), active, 90, false)

        assertEquals(0.5f, (box.left + box.right) / 2f, 1e-6f)
        assertEquals(0.5f, (box.top + box.bottom) / 2f, 1e-6f)
        assertTrue(box.left < box.right)
        assertTrue(box.top < box.bottom)
        assertFalse(box.tracked)
    }

    @Test fun `mapToView clamps boxes hanging off the active array`() {
        val active = SensorActiveArray(0, 0, 4000, 3000)
        val box = FacePriority.mapToView(face(-500, -500, 500, 500), active, 0, false)

        assertEquals(0f, box.left, 1e-6f)
        assertEquals(0f, box.top, 1e-6f)
        assertTrue(box.right > 0f)
        assertTrue(box.bottom > 0f)
    }
}
