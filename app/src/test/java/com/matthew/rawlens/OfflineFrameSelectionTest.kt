// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OfflineFrameSelectionTest {
    @Test fun defaultKeepsExistingEvenSamplingCap() {
        assertEquals(BurstMergeTakes.evenSampleIndices(30, 16), OfflineFrameSelection().resolve(30, 16))
    }
    @Test fun requestedCountSpansEntireTake() {
        assertEquals(listOf(0, 4, 9), OfflineFrameSelection(count = 3).resolve(10, 16))
    }
    @Test fun manualSelectionPreservesExactFramesInCaptureOrder() {
        assertEquals(listOf(1, 4, 8), OfflineFrameSelection(manualIndices = listOf(8, 1, 4)).resolve(10, 16))
    }
    @Test fun manualSelectionIsNeverSilentlyResampled() {
        assertThrows(IllegalArgumentException::class.java) {
            OfflineFrameSelection(manualIndices = (0..16).toList()).resolve(30, 16)
        }
    }
    @Test fun rejectsInvalidOrInsufficientSelection() {
        listOf(emptyList(), listOf(1), listOf(0, 0), listOf(-1, 2), listOf(0, 10)).forEach { indices ->
            assertThrows(IllegalArgumentException::class.java) {
                OfflineFrameSelection(manualIndices = indices).resolve(10, 16)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { OfflineFrameSelection().resolve(1, 16) }
    }
    @Test fun automaticCountAdaptsToEngineLimitAndShortTake() {
        assertEquals(16, OfflineFrameSelection(count = 25).resolve(30, 16).size)
        assertEquals(listOf(0, 1, 2), OfflineFrameSelection(count = 12).resolve(3, 16))
    }
}
