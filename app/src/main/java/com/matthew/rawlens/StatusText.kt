// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/** Short status-line text for the top-right corner overlay.
 *
 * The status view is only ~104dp wide, so every message shown there must stay
 * short. Full file names (IMG_...dng/jpg) and exception details belong in Log,
 * never in this view. [compact] is the safety net: sources should already use
 * the short literals below, and [compact] guarantees no long text slips through
 * by stripping file names and truncating to [MAX_LEN]. */
object StatusText {
    const val MAX_LEN = 28

    private val fileStem = Regex("""IMG_[A-Za-z0-9_.\-]*""")
    private val fileExtension = Regex("""\S*?\.(dng|jpg|jpeg)\b""", RegexOption.IGNORE_CASE)
    private val whitespace = Regex("\\s+")
    private val straySeparators = Regex("""([•+])(\s*[•+]\s*)+""")

    fun compact(raw: String, maxLen: Int = MAX_LEN): String {
        var s = raw.trim().replace(whitespace, " ")
        s = s.replace(fileStem, "")
        s = s.replace(fileExtension, "")
        s = s.replace(whitespace, " ")
        s = s.replace(straySeparators, "$1 ")
        s = s.replace(Regex("""^[•+\s]+"""), "")
        s = s.replace(Regex("""[•+\s]+$"""), "")
        s = s.replace(whitespace, " ").trim()
        if (s.isEmpty()) return "SAVED"
        if (s.length <= maxLen) return s
        val cut = s.lastIndexOf(" • ", maxLen - 1)
        return if (cut >= 8) s.take(cut) else s.take(maxLen - 1) + "…"
    }

    /** Short save outcome: type only (DNG/JPG), never file names. */
    fun saveOutcome(
        dngSaved: Boolean,
        jpegSaved: Boolean,
        failed: Boolean,
    ): String = when {
        dngSaved && jpegSaved && failed -> "PARTIAL DNG+JPG"
        dngSaved && jpegSaved -> "SAVED DNG+JPG"
        dngSaved && failed -> "PARTIAL DNG"
        jpegSaved && failed -> "PARTIAL JPG"
        dngSaved -> "SAVED DNG"
        jpegSaved -> "SAVED JPG"
        else -> "SAVE ERROR"
    }
}
