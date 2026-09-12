// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureFileNamesTest {

    @Test
    fun stemMatchesImgDateTimeMillisPattern() {
        val stem = CaptureFileNames.stem(1_786_269_212_527L)
        assertTrue(stem, stem.matches(Regex("IMG_\\d{8}_\\d{6}_\\d{3}")))
        // Millisecond component is preserved regardless of time zone.
        assertTrue(stem, stem.endsWith("_527"))
    }

    @Test
    fun singleDngHasNoTypeSuffix() {
        val name = CaptureFileNames.singleDng(1_786_269_212_527L)
        assertTrue(name, name.matches(Regex("IMG_\\d{8}_\\d{6}_527\\.dng")))
    }

    @Test
    fun singleShotDngAndJpegShareStem() {
        val ts = 1_786_269_212_527L
        val dng = CaptureFileNames.singleDng(ts)
        val jpg = CaptureFileNames.singleJpeg(ts)
        assertEquals(dng.removeSuffix(".dng"), jpg.removeSuffix(".jpg"))
    }

    @Test
    fun hdrOutputsShareStemWithHdrSuffix() {
        val ts = 1_786_269_212_527L
        val stem = CaptureFileNames.stem(ts)
        assertEquals("${stem}_HDR.dng", CaptureFileNames.hdrDng(ts))
        assertEquals("${stem}_HDR.jpg", CaptureFileNames.hdrJpeg(ts))
    }

    @Test
    fun bracketFramesAreUniqueAndShareStem() {
        val ts = 1_786_269_212_527L
        val stem = CaptureFileNames.stem(ts)
        val frames = (0..2).map { CaptureFileNames.bracketDng(ts, it) }
        assertEquals(listOf("${stem}_F00.dng", "${stem}_F01.dng", "${stem}_F02.dng"), frames)
        assertEquals(3, frames.toSet().size)
    }

    @Test
    fun namesSortChronologically() {
        val stamps = listOf(
            1_786_269_212_527L,
            1_786_269_212_528L,
            1_786_269_213_000L,
            1_786_269_212_000L
        )
        val names = stamps.map { CaptureFileNames.singleDng(it) }
        assertEquals(
            stamps.sorted().map { CaptureFileNames.singleDng(it) },
            names.sorted()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun exposureSuffixIsRejected() {
        CaptureFileNames.fileName(1_786_269_212_527L, "HDR_-2EV", "dng")
    }

    @Test(expected = IllegalArgumentException::class)
    fun plusSignSuffixIsRejected() {
        CaptureFileNames.fileName(1_786_269_212_527L, "HDR_+2EV", "dng")
    }
}
