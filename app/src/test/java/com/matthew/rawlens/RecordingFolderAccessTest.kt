// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class RecordingFolderAccessTest {
    @Test fun standardParentsUseRawLensChildButCustomFoldersAreUsedDirectly() {
        for (id in listOf("primary:Documents", "primary:Documents/", "primary:DCIM", "primary:Download")) {
            assertTrue(RecordingFolderAccess.needsRawLensChild(id))
        }
        for (id in listOf("primary:Documents/RawLens", "primary:DCIM/RawLens/", "primary:Clips")) {
            assertFalse(RecordingFolderAccess.needsRawLensChild(id))
        }
    }

    @Test fun selectedFolderWinsWithoutCreatingFallbackCopy() {
        var fallbackCalls = 0
        assertEquals("Documents/RawLens", RecordingFolderAccess.export(
            { "Documents/RawLens" }, { fallbackCalls++; "Downloads/RawLens" }))
        assertEquals(0, fallbackCalls)
    }

    @Test fun missingOrRevokedFolderUsesDownloads() {
        assertEquals("Downloads", RecordingFolderAccess.export<String>(null) { "Downloads" })
        assertEquals("Downloads", RecordingFolderAccess.export(
            { throw SecurityException("grant revoked") }, { "Downloads" }))
    }

    @Test fun failedSelectedFolderFallsBackAndKeepsBothFailureReasons() {
        val selectedFailure = IOException("selected volume full")
        val downloadsFailure = IOException("Downloads full")
        try {
            RecordingFolderAccess.export<String>({ throw selectedFailure }, { throw downloadsFailure })
            fail("Must report failure so caller retains staged take")
        } catch (failure: IOException) {
            assertSame(downloadsFailure, failure)
            assertSame(selectedFailure, failure.suppressed.single())
        }
    }
}
