// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Saved-DNG raw-data compression. Uncompressed is the default for maximum
 * reader compatibility. JXL stores the mosaic losslessly as a JPEG XL
 * codestream (DNG 1.7, Compression 52546 — the same flavor as iPhone
 * ProRAW), roughly halving single-frame and HDR+ merged DNG sizes on noisy
 * phone sensors. Float/linear merges and packed frames stay uncompressed.
 *
 * Compatibility warning (shown in Settings): Adobe Lightroom / Camera Raw /
 * DNG Converter 16+ and Apple Photos read JXL DNGs; darktable (rawspeed)
 * and most LibRaw-based tools do not yet. The toggle stays off unless the
 * user explicitly opts in.
 */
enum class DngCompression(val preferenceValue: String, val label: String) {
    UNCOMPRESSED("uncompressed", "Uncompressed (widest compatibility)"),
    JXL("jxl", "JXL lossless (DNG 1.7, smaller)");

    companion object {
        fun fromPreference(value: String?): DngCompression =
            entries.firstOrNull { it.preferenceValue == value } ?: UNCOMPRESSED
    }
}
