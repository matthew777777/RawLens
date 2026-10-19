// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * JPEG XL output contract, mirroring darktable's `src/imageio/format/jxl.c`
 * options one for one: bit depth, 16-bit pixel type, quality, encoding
 * effort, and decoding-speed tier.
 *
 * darktable mapping preserved here:
 * - bit depth 8/10/12/16 selects `bits_per_sample`; 32 is always float
 *   (`exponent_bits_per_sample = 8`).
 * - 16-bit float (`pixel_type = true` in darktable, half float here) sets
 *   `exponent_bits_per_sample = 5`; 16-bit integer leaves it at 0.
 * - quality 100 is lossless: frame distance 0, original profile, and
 *   `SetFrameLossless` — except 16-bit float, which darktable deliberately
 *   excludes from the lossless flag (libjxl issue #17487).
 * - effort 1..9 (darktable default 7): higher is slower with better results.
 * - decoding speed 0..4 (darktable default 0): higher favors decode speed
 *   over quality.
 */
data class JxlOutputSettings(
    val bitDepth: JxlBitDepth = JxlBitDepth.BIT_16,
    val pixelType: JxlPixelType = JxlPixelType.UINT,
    /** Lossy quality 4..100; 100 means mathematically lossless (uint paths). */
    val quality: Int = 100,
    /** Encoding effort 1..9; higher is slower with better compression. */
    val effort: Int = 7,
    /** Decoding-speed tier 0..4; higher decodes faster with some quality cost. */
    val decodingSpeed: Int = 0,
    val displayP3: Boolean = false,
    /** SDR is the default still; PQ keeps scene highlights as true HDR. */
    val hdrTransfer: JxlHdrTransfer = JxlHdrTransfer.SDR
) {
    /** True lossless path: quality 100 on an integer (non-float) sample type. */
    val isLossless: Boolean
        get() = quality == 100 && !bitDepth.isFloat && pixelType == JxlPixelType.UINT

    fun resolved(): JxlOutputSettings = copy(
        quality = quality.coerceIn(MIN_QUALITY, MAX_QUALITY),
        effort = effort.coerceIn(MIN_EFFORT, MAX_EFFORT),
        decodingSpeed = decodingSpeed.coerceIn(MIN_TIER, MAX_TIER),
        // Pixel type only applies to 16-bit, exactly like darktable's GUI
        // (the control is hidden for every other depth).
        pixelType = if (bitDepth == JxlBitDepth.BIT_16) pixelType else JxlPixelType.UINT,
        // PQ codes need 10+ bits (8-bit PQ bands visibly); bump, never drop.
        bitDepth = if (hdrTransfer == JxlHdrTransfer.PQ && bitDepth == JxlBitDepth.BIT_8) {
            JxlBitDepth.BIT_10
        } else {
            bitDepth
        }
    )

    companion object {
        const val MIN_QUALITY = 4
        const val MAX_QUALITY = 100
        const val MIN_EFFORT = 1
        const val MAX_EFFORT = 9
        const val MIN_TIER = 0
        const val MAX_TIER = 4

        fun fromPreferences(
            bitDepthName: String?,
            pixelTypeName: String?,
            quality: Int,
            effort: Int,
            decodingSpeed: Int,
            displayP3: Boolean,
            hdrTransferName: String? = null
        ): JxlOutputSettings = JxlOutputSettings(
            bitDepth = JxlBitDepth.fromPreference(bitDepthName),
            pixelType = JxlPixelType.fromPreference(pixelTypeName),
            quality = quality,
            effort = effort,
            decodingSpeed = decodingSpeed,
            displayP3 = displayP3,
            hdrTransfer = JxlHdrTransfer.fromPreference(hdrTransferName)
        ).resolved()
    }
}

/** darktable `bpp` combobox: 8 / 10 / 12 / 16 bit integer, or 32-bit float. */
enum class JxlBitDepth(
    val bits: Int,
    val label: String,
    /** libjxl `exponent_bits_per_sample` for the pure-float member. */
    val isFloat: Boolean
) {
    BIT_8(8, "8 bit", false),
    BIT_10(10, "10 bit", false),
    BIT_12(12, "12 bit", false),
    BIT_16(16, "16 bit", false),
    FLOAT_32(32, "32 bit (float)", true);

    /** Max code value for integer depths; unused for float. */
    val maxCodeValue: Int get() = (1 shl bits) - 1

    fun next(): JxlBitDepth = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromPreference(value: String?): JxlBitDepth =
            entries.firstOrNull { it.name == value } ?: BIT_16
    }
}

/**
 * HDR transfer for JXL stills. SDR keeps the classic AgX SDR grade;
 * PQ preserves scene highlights above SDR white as true HDR (ST2084,
 * graded linear 1.0 = 100 nits). The develop look stays shared: only the
 * highlight handling and output encoding change.
 */
enum class JxlHdrTransfer(val label: String) {
    SDR("SDR"),
    PQ("HDR PQ");

    fun next(): JxlHdrTransfer = if (this == SDR) PQ else SDR

    companion object {
        fun fromPreference(value: String?): JxlHdrTransfer =
            entries.firstOrNull { it.name == value } ?: SDR
    }
}

/**
 * darktable 16-bit `pixel_type` combobox: unsigned integer samples versus
 * half-precision floating point samples (`exponent_bits_per_sample = 5`).
 * Only meaningful for [JxlBitDepth.BIT_16]; [JxlOutputSettings.resolved]
 * coerces every other depth back to [UINT].
 */
enum class JxlPixelType(val label: String) {
    UINT("unsigned integer"),
    HALF_FLOAT("floating point");

    fun next(): JxlPixelType = if (this == UINT) HALF_FLOAT else UINT

    companion object {
        fun fromPreference(value: String?): JxlPixelType =
            entries.firstOrNull { it.name == value } ?: UINT
    }
}
