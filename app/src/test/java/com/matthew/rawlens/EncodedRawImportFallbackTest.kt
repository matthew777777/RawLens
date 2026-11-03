package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class EncodedRawImportFallbackTest {
    @Test fun importFailureRetriesCurrentFrameAndLatchesForLaterSlots() {
        for (error in listOf(-5, 5)) {
            val fallback = EncodedRawImportFallback()
            var direct = 0
            var uploads = 0
            repeat(6) {
                assertEquals(17, fallback.submit(error, { direct++; error }, { uploads++; 17 }))
            }
            assertEquals(1, direct)
            assertEquals(6, uploads)
        }
    }

    @Test fun validFenceIncludingFdFiveAndOtherErrorsNeverRetry() {
        for (rc in listOf(0, 5, 17, -4, -7, -20)) {
            val fallback = EncodedRawImportFallback()
            assertEquals(rc, fallback.submit(-5, { rc }, { error("unexpected retry") }))
            assertEquals(19, fallback.submit(-5, { 19 }, { error("unexpected latch") }))
        }
    }

    @Test fun uploadFailureDoesNotResumeBrokenCameraImports() {
        val fallback = EncodedRawImportFallback()
        assertEquals(-5, fallback.submit(-5, { -5 }, { -5 }))
        assertEquals(23, fallback.submit(-5, { error("broken import retried") }, { 23 }))
    }
}
