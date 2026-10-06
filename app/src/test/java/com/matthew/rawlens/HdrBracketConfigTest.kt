package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrBracketConfigTest {
    @Test fun classicBracketsMatchLegacyShutters() {
        val two = HdrBracketConfig.classic(2)
        assertArrayEquals(intArrayOf(-2, 0, 2), two.stops)
        assertEquals(3, two.totalFrames)
        assertTrue(two.allEvsCovered)
        val four = HdrBracketConfig.classic(4)
        assertArrayEquals(intArrayOf(-4, 0, 4), four.stops)
    }

    @Test fun denseRaymergeBurstTotals24() {
        val plan = HdrBracketConfig.plan(
            HdrBracketConfig.DENSE_STOPS, HdrBracketConfig.DENSE_FRAMES_PER_STOP)
        assertEquals(24, plan.totalFrames)
        assertEquals(listOf(-3, -2, -1, 0, 1), plan.coveredEvs)
        assertTrue(plan.allEvsCovered)
        // Darkest first, per-stop runs contiguous.
        assertEquals(-3, plan.frames.first().evStops)
        assertEquals(1, plan.frames.last().evStops)
        assertEquals(5, plan.frames.count { it.evStops == -3 })
        assertEquals(8, plan.frames.count { it.evStops == -1 })
    }

    @Test fun stopsSortDarkestFirst() {
        val plan = HdrBracketConfig.plan(intArrayOf(2, -2, 0))
        assertArrayEquals(intArrayOf(-2, 0, 2), plan.stops)
    }

    @Test fun invalidPlansRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            HdrBracketConfig.plan(intArrayOf(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HdrBracketConfig.plan(intArrayOf(-2, -2, 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HdrBracketConfig.plan(intArrayOf(-5, 0, 5))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HdrBracketConfig.plan(intArrayOf(-2, 0, 2), intArrayOf(20, 20, 20))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HdrBracketConfig.classic(3)
        }
    }

    @Test fun shutterScalesByTwoToTheStops() {
        assertEquals(2_500_000L, HdrBracketConfig.shutterNanos(10_000_000L, -2))
        assertEquals(10_000_000L, HdrBracketConfig.shutterNanos(10_000_000L, 0))
        assertEquals(40_000_000L, HdrBracketConfig.shutterNanos(10_000_000L, 2))
    }

    @Test fun referenceIsMiddleExposure() {
        // Unsorted input (capture order): middle by value wins, robust to clamping.
        assertEquals(2, HdrBracketConfig.referenceIndex(listOf(1.0, 4.0, 2.0)))
        assertEquals(1, HdrBracketConfig.referenceIndex(listOf(0.25, 1.0, 4.0)))
    }

    @Test fun evRelativeHandlesMissingMetadata() {
        val ev = HdrBracketConfig.evRelativeToReference(listOf(0.5, 1.0, 2.0, null), 1)
        assertEquals(-1.0, ev[0]!!, 1e-9)
        assertEquals(0.0, ev[1]!!, 1e-9)
        assertEquals(1.0, ev[2]!!, 1e-9)
        assertNull(ev[3])
    }

    @Test fun frameLogLineMarksReference() {
        assertEquals("F0[*B*] EV=0: Reference",
            HdrBracketConfig.frameLogLine(0, 0, "Reference", isReference = true))
        assertEquals("F18 EV=+1: Accepted ev=+1.0",
            HdrBracketConfig.frameLogLine(18, 1, "Accepted", "ev=+1.0"))
    }

    @Test fun coverageSummaryNamesMissingEvs() {
        val plan = HdrBracketConfig.classic(2)
        assertTrue(HdrBracketConfig.coverageSummary(plan, mapOf(-2 to 1, 0 to 1, 2 to 1))
            .contains("all EVs covered"))
        val missing = HdrBracketConfig.coverageSummary(plan, mapOf(-2 to 1, 0 to 1))
        assertTrue(missing, missing.contains("MISSING EVs"))
    }

    private fun assertThrows(expected: Class<out Throwable>, block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected ${expected.simpleName}")
        } catch (failure: Throwable) {
            assertTrue("expected ${expected.simpleName}, got $failure",
                expected.isInstance(failure))
        }
    }
}
