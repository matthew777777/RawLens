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
 * Metering pattern and lens branding are not exposed by Camera2 and are omitted.
 * AE/AWB modes come from the frozen controls, never their convergence states.
 * ISO/ExposureTime are also written here for EXIF readers; legacy IFD0 copies
 * are retained by the existing DNG writers.
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
            subject == null && flash == null && whenMs == null &&
            metadata.sensitivityIso == null && metadata.aeMode == null &&
            metadata.awbMode == null && metadata.exposureCompensationEv == null &&
            metadata.digitalZoomRatio == null && metadata.lensModel == null &&
            metadata.lensSpecification == null && metadata.compositeUsedCount == null
        ) {
            return null
        }
        return TiffFields().apply {
            undefined(36864, "0300".toByteArray(Charsets.US_ASCII))
            val used = metadata.compositeUsedCount
            val total = metadata.compositeSourceCount
            if (used != null && total != null && used in 2..65535 && total in used..65535) {
                shorts(42080, 2) // General composite; no claim that field of view stayed constant.
                shorts(42081, total, used)
            }
            metadata.lensModel?.let { ascii(42036, it) }
            metadata.lensSpecification?.toDoubleArray()?.takeIf {
                it.size == 4 && it.all { value -> value.isFinite() && value > 0.0 }
            }?.let { rationals(42034, it) }
            metadata.sensitivityIso?.takeIf { it > 0 }?.let {
                shorts(34855, it.coerceAtMost(65535))
                shorts(34864, 3) // SensitivityType = ISO speed.
                longs(34867, it)
            }
            metadata.aeMode?.let {
                val manual = it == 0 // Camera2 CONTROL_AE_MODE_OFF
                shorts(41986, if (manual) 1 else 0)
                shorts(34850, if (manual) 1 else 2)
            }
            metadata.awbMode?.let { shorts(41987, if (it == 1) 0 else 1) }
            metadata.exposureCompensationEv?.takeIf(Double::isFinite)?.let {
                srationals(37380, doubleArrayOf(it))
            }
            metadata.digitalZoomRatio?.takeIf { it.isFinite() && it >= 1f }?.let {
                rationals(41988, doubleArrayOf(it.toDouble()))
            }
            if (aperture != null) {
                rationals(33437, doubleArrayOf(aperture.toDouble()))
                rationals(37378, doubleArrayOf(apexAperture(aperture.toDouble())))
            }
            if (exposureSec != null) {
                rationals(33434, doubleArrayOf(exposureSec))
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
