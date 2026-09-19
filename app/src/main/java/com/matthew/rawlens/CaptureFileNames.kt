// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Single source of truth for capture file names.
 *
 * Scheme: `IMG_YYYYMMDD_HHMMSS_mmm[_TYPE].ext` (e.g. `IMG_20260910_133342_527.dng`).
 *
 * Properties:
 * - Unique per millisecond; burst/source frames sharing one timestamp stay unique via `_Fxx`.
 * - Lexicographically sortable in chronological order.
 * - Human-readable; capture parameters (ISO, shutter, WB, EV, sensor ID) belong in
 *   DNG/EXIF metadata, never in the name.
 * - Every output of one capture shares the same timestamp stem so related files group
 *   together: `IMG_<ts>.dng` + `IMG_<ts>.jpg`, or `IMG_<ts>_F00.dng` … + `IMG_<ts>_HDR.dng`.
 */
object CaptureFileNames {
    const val PREFIX = "IMG"
    const val TYPE_HDR = "HDR"
    const val TYPE_RAW = "RAW"
    const val TYPE_AI = "AI"

    private const val STEM_PATTERN = "yyyyMMdd_HHmmss_SSS"

    private val stemFormat = ThreadLocal.withInitial {
        SimpleDateFormat(STEM_PATTERN, Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
    }

    /** `IMG_20260910_133342_527` — shared stem for every output of one capture. */
    fun stem(captureTimeMillis: Long): String =
        "${PREFIX}_${requireNotNull(stemFormat.get()).format(captureTimeMillis)}"

    /**
     * Full display name. [typeSuffix] must be null/blank or `[A-Za-z0-9]+`
     * (e.g. `HDR`, `F00`); anything else is rejected so exposure metadata
     * (`-2EV`, `+`, spaces) can never leak back into file names.
     */
    fun fileName(captureTimeMillis: Long, typeSuffix: String?, extension: String): String {
        require(extension.matches(EXTENSION_REGEX)) { "Unsupported extension: $extension" }
        val suffix = typeSuffix?.takeIf { it.isNotBlank() }?.let {
            require(it.matches(SUFFIX_REGEX)) { "File suffix must match [A-Za-z0-9]+: $it" }
            "_${it.uppercase(Locale.US)}"
        }.orEmpty()
        return "${stem(captureTimeMillis)}$suffix.$extension"
    }

    /** Plain single-shot DNG: `IMG_<ts>.dng`. */
    fun singleDng(captureTimeMillis: Long): String = fileName(captureTimeMillis, null, "dng")

    /** Plain single-shot JPEG sharing its DNG's stem: `IMG_<ts>.jpg`. */
    fun singleJpeg(captureTimeMillis: Long): String = fileName(captureTimeMillis, null, "jpg")

    /** Merged HDR outputs: `IMG_<ts>_HDR.dng` / `IMG_<ts>_HDR.jpg`. */
    fun hdrDng(captureTimeMillis: Long): String = fileName(captureTimeMillis, TYPE_HDR, "dng")

    fun hdrJpeg(captureTimeMillis: Long): String = fileName(captureTimeMillis, TYPE_HDR, "jpg")

    /**
     * AI-denoised DNG sharing its capture's stem: `IMG_<ts>_AI.dng`, or
     * `IMG_<ts>_F00AI.dng` when [frameSuffix] carries a burst/bracket frame
     * tag (e.g. `F00`). Groups with the original DNG/JPEG in listings.
     */
    fun aiDng(captureTimeMillis: Long, frameSuffix: String? = null): String {
        val suffix = if (frameSuffix.isNullOrBlank()) TYPE_AI else "${frameSuffix.uppercase(Locale.US)}$TYPE_AI"
        return fileName(captureTimeMillis, suffix, "dng")
    }

    /** Burst/bracket source frame: `IMG_<ts>_F00.dng`, `IMG_<ts>_F01.dng`, … */
    fun bracketDng(captureTimeMillis: Long, index: Int): String {
        require(index in 0..99) { "Bracket index out of range: $index" }
        return fileName(captureTimeMillis, "F%02d".format(index), "dng")
    }

    private val SUFFIX_REGEX = Regex("[A-Za-z0-9]+")
    private val EXTENSION_REGEX = Regex("[A-Za-z0-9]{2,4}")
}
