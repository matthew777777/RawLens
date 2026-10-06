// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.log2

/**
 * EXIF sub-IFD contents for merged DNGs (phone + desktop, parity-shared) as
 * TinyDNG field values. Our merged writers historically emitted IFD0 only;
 * converters and archival tools expect the capture facts (f-number, focal
 * length, dates, flash) in the EXIF directory like reference multiframe
 * mergers emit. Only truthful tags are written — every value comes from the
 * reference [RawFrameMetadata] snapshot or the save's wall-clock
 * [captureTimeMillis]; anything unknown is omitted, never fabricated.
 * Sub-IFD layout (sorting, offsets, blobs) is owned by the writer.
 *
 * Deliberately omitted (no truthful source): ExposureMode/WhiteBalance (AE/AWB
 * state does not map to the EXIF auto/manual enum), MeteringMode,
 * DigitalZoomRatio, FocalLengthIn35mmFormat (no crop factor — sensor physical
 * size is unavailable), LensInfo/LensMake/LensModel (fixed-lens min==max is an
 * inference we do not make), ComponentsConfiguration/FlashpixVersion/ColorSpace
 * (meaningless for raw data). ISO and ExposureTime stay in IFD0 where the DNG
 * writers already emit them and parser tests pin them.
 */
object DngExifDirectory {
    const val TAG_EXIF_IFD_POINTER = 34665

    /**
     * EXIF sub-IFD contents, or null when no EXIF fact is available at all
     * (never writes a constants-only directory).
     *
     * @param captureTimeMillis wall-clock save time (the savers' captureId);
     *   feeds the three date tags, sub-seconds, and timezone offsets. Null
     *   omits all date tags.
     */
    fun fields(
        metadata: RawFrameMetadata,
        captureTimeMillis: Long?
    ): TiffFields? {
        val exposureSec = metadata.exposureTimeNanos
            ?.takeIf { it > 0 }?.let { it / 1e9 }
        val aperture = metadata.aperture?.takeIf { it.isFinite() && it > 0f }
        val focal = metadata.focalLengthMm?.takeIf { it.isFinite() && it > 0f }
        val subject = metadata.focusDistanceM?.takeIf { it.isFinite() && it > 0f }
        val flash = metadata.flashFired
        val whenMs = captureTimeMillis?.takeIf { it > 0 }
        if (exposureSec == null && aperture == null && focal == null &&
            subject == null && flash == null && whenMs == null
        ) {
            return null
        }
        return TiffFields().apply {
            undefined(36864, "0300".toByteArray(Charsets.US_ASCII))
            if (aperture != null) {
                rationals(33437, doubleArrayOf(aperture.toDouble()))
                rationals(37378, doubleArrayOf(apexAperture(aperture.toDouble())))
            }
            if (exposureSec != null) {
                srationals(37377, doubleArrayOf(apexShutter(exposureSec)))
            }
            if (focal != null) {
                rationals(37386, doubleArrayOf(focal.toDouble()))
            }
            if (subject != null) {
                rationals(37382, doubleArrayOf(subject.toDouble()))
            }
            if (flash != null) {
                // EXIF Flash (0x9209), SHORT bitfield: bit 0 = flash fired.
                // (37388 is SpatialFrequencyResponse — a past typo, now fixed.)
                shorts(37385, if (flash) 1 else 0)
            }
            if (whenMs != null) {
                val stamp = dateTime(whenMs)
                ascii(36867, stamp)
                ascii(36868, stamp)
                val offset = offsetString(whenMs)
                ascii(36880, offset)
                ascii(36881, offset)
                ascii(36882, offset)
                val sub = subSec(whenMs)
                ascii(37520, sub)
                ascii(37521, sub)
                ascii(37522, sub)
            }
            shorts(41495, 2)
        }
    }

    /** APEX shutter value Tv = -log2(t); negative for exposures over 1s (SRATIONAL). */
    fun apexShutter(exposureSec: Double): Double {
        require(exposureSec.isFinite() && exposureSec > 0.0) { "Exposure must be finite and positive" }
        return -log2(exposureSec)
    }

    /** APEX aperture value Av = 2*log2(F); always positive for real lenses (RATIONAL). */
    fun apexAperture(fNumber: Double): Double {
        require(fNumber.isFinite() && fNumber > 0.0) { "f-number must be finite and positive" }
        return 2.0 * log2(fNumber)
    }

    /** EXIF `YYYY:MM:DD HH:MM:SS` in the device timezone. */
    fun dateTime(millis: Long): String =
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(millis))

    /** EXIF `±HH:MM` zone offset at [millis] (DST-aware). */
    fun offsetString(millis: Long): String {
        val offsetMin = TimeZone.getDefault().getOffset(millis) / 60000
        val sign = if (offsetMin < 0) "-" else "+"
        val abs = kotlin.math.abs(offsetMin)
        return "%s%02d:%02d".format(Locale.US, sign, abs / 60, abs % 60)
    }

    /** Milliseconds past the second, zero-padded to 3 (SubSecTime). */
    fun subSec(millis: Long): String =
        "%03d".format(Locale.US, ((millis % 1000) + 1000) % 1000)
}
