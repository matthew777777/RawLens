// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.IOException
import java.util.Locale

/**
 * Sidecar files for gyro-assisted bursts. Scoped storage forbids generic
 * text files under DCIM (MediaStore.Files only allows Download/Documents
 * on targetSdk 35, hence the log `Primary directory DCIM not allowed`),
 * so without a folder grant the burst is split by collection while keeping
 * the same stem:
 *
 * ```text
 * DCIM/RawLens/IMG_<ts>/
 *   IMG_<ts>_F00.dng … IMG_<ts>_F07.dng   (Images collection)
 *   IMG_<ts>_F00.jpg …                     (Images collection, if developed)
 * Download/RawLens/IMG_<ts>/
 *   burst.json                             (Downloads collection)
 *   gyro/IMG_<ts>_F00.csv …                (Downloads collection)
 * ```
 *
 * When the user grants the photo folder (Settings → General → sidecars),
 * [SidecarTreeAccess.writeViaTree] instead writes `burst.json` + `gyro/`
 * directly into `DCIM/RawLens/IMG_<ts>/` next to the DNGs — no extra
 * manifest permission needed. Either way, desktop `import` wants one
 * folder holding DNGs + `burst.json` + `gyro/`, so copy the Downloads
 * sidecars next to the DNGs when the split applies.
 *
 * `burst.json` carries what plain DNGs cannot: true per-frame boot-time
 * timestamps (desktop `import` otherwise synthesizes 33 ms spacing, which
 * would make gyro windows meaningless), exposure/skew/ISO, intrinsics, and
 * the gyro mapping provenance. Schema:
 *
 * ```json
 * {"format":"rawlens-burst-meta","formatVersion":1,"burstName":"IMG_…",
 *  "mapperVersion":1,"sensorOrientationDeg":90,"frontFacing":false,
 *  "deviceOrientationDeg":0,"exifOrientation":6,
 *  "intrinsics":{"fxPixels":…,"fyPixels":…,"cxPixels":…,"cyPixels":…},
 *  "frames":[{"file":"IMG_…_F00.dng","timestampNanos":…,"exposureNanos":…,
 *  "skewNanos":…,"iso":100,"gyro":"gyro/IMG_…_F00.csv"|null}, …]}
 * ```
 *
 * Pure builders are host-testable; only [writeFiles] touches MediaStore.
 */
object BurstSidecar {
    const val GYRO_DIR = "gyro"
    const val META_FILE = "burst.json"
    const val FORMAT = "rawlens-burst-meta"
    const val FORMAT_VERSION = 1
    /** Downloads base: Files collection rejects DCIM, so sidecars live here. */
    const val SIDECAR_BASE = "Download/RawLens"

    /** `Download/RawLens/<subfolder>` for logs and reassemble instructions. */
    fun sidecarDir(subfolder: String): String = "$SIDECAR_BASE/${requireSubfolder(subfolder)}"

    private val SUBFOLDER_REGEX = Regex("[A-Za-z0-9_]+")

    /** Rejects path traversal before a name ever reaches MediaStore. */
    fun requireSubfolder(name: String): String {
        require(name.matches(SUBFOLDER_REGEX)) { "subfolder must match [A-Za-z0-9_]+: $name" }
        return name
    }

    /** `IMG_<ts>_F00.dng` -> `gyro/IMG_<ts>_F00.csv` (desktop contract). */
    fun gyroFileName(dngName: String): String {
        require(!dngName.contains('/')) { "DNG name must be a basename: $dngName" }
        return "$GYRO_DIR/${dngName.substringBeforeLast('.')}.csv"
    }

    /** `gyro/IMG_<ts>_F00.csv` -> `IMG_<ts>_F00.csv` (MediaStore DISPLAY_NAME). */
    fun csvDisplayName(csvName: String): String {
        require(csvName.startsWith("$GYRO_DIR/")) { "CSV must live under $GYRO_DIR/: $csvName" }
        val base = csvName.removePrefix("$GYRO_DIR/")
        require(base.isNotEmpty() && !base.contains('/')) { "CSV name must be a basename: $csvName" }
        return base
    }

    /** One frame row of burst.json; [gyroFile] null = no usable window. */
    data class FrameMeta(
        val file: String,
        val timestampNanos: Long,
        val exposureNanos: Long,
        val skewNanos: Long,
        val iso: Int,
        val gyroFile: String?
    ) {
        init {
            require(file.isNotBlank() && !file.contains('/')) { "frame file must be a basename" }
            require(exposureNanos > 0L) { "exposure must be positive" }
            require(skewNanos >= 0L) { "skew must be non-negative" }
            require(iso > 0) { "iso must be positive" }
        }
    }

    /** Pinhole intrinsics in stored pixels (principal point centered approx). */
    data class IntrinsicsSnapshot(
        val fxPixels: Double,
        val fyPixels: Double,
        val cxPixels: Double,
        val cyPixels: Double
    )

    /**
     * Pinhole from focal length + sensor size + stored dimensions, or null
     * when calibration is missing (desktop then uses the image-only path —
     * an approximate calibration is never fabricated... except the centered
     * principal point, which is the documented standard fallback).
     * EXIF 6/8 transpose stored axes relative to the sensor, so the
     * physical dims swap with them.
     */
    fun intrinsicsOrNull(
        focalMm: Float?,
        sensorWidthMm: Float?,
        sensorHeightMm: Float?,
        storedWidthPx: Int,
        storedHeightPx: Int,
        exifOrientation: Int
    ): IntrinsicsSnapshot? {
        if (focalMm == null || sensorWidthMm == null || sensorHeightMm == null) return null
        if (!focalMm.isFinite() || focalMm <= 0f) return null
        if (!sensorWidthMm.isFinite() || !sensorHeightMm.isFinite()) return null
        if (sensorWidthMm <= 0f || sensorHeightMm <= 0f) return null
        if (storedWidthPx <= 0 || storedHeightPx <= 0) return null
        val transposed = exifOrientation == 6 || exifOrientation == 8
        val sensorW = if (transposed) sensorHeightMm else sensorWidthMm
        val sensorH = if (transposed) sensorWidthMm else sensorHeightMm
        if (sensorW <= 0f || sensorH <= 0f) return null
        val fx = focalMm.toDouble() * storedWidthPx / sensorW
        val fy = focalMm.toDouble() * storedHeightPx / sensorH
        if (!fx.isFinite() || !fy.isFinite() || fx <= 0 || fy <= 0) return null
        return IntrinsicsSnapshot(fx, fy, storedWidthPx / 2.0, storedHeightPx / 2.0)
    }

    /** Deterministic burst.json renderer (fixed key order, US locale). */
    fun buildMetaJson(
        burstName: String,
        sensorOrientationDeg: Int,
        frontFacing: Boolean,
        deviceOrientationDeg: Int,
        exifOrientation: Int,
        intrinsics: IntrinsicsSnapshot?,
        frames: List<FrameMeta>
    ): String {
        require(frames.size in 2..30) { "burst needs 2..30 frames, got ${frames.size}" }
        fun esc(s: String): String = buildString {
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        fun num(d: Double): String = "%.6f".format(Locale.US, d)
        val sb = StringBuilder(512 + frames.size * 160)
        sb.append("{\"format\":\"").append(FORMAT).append("\",")
        sb.append("\"formatVersion\":").append(FORMAT_VERSION).append(',')
        sb.append("\"burstName\":\"").append(esc(burstName)).append("\",")
        sb.append("\"mapperVersion\":").append(GyroCameraFrameMapper.MAPPER_VERSION).append(',')
        sb.append("\"sensorOrientationDeg\":").append(sensorOrientationDeg).append(',')
        sb.append("\"frontFacing\":").append(frontFacing).append(',')
        sb.append("\"deviceOrientationDeg\":").append(deviceOrientationDeg).append(',')
        sb.append("\"exifOrientation\":").append(exifOrientation).append(',')
        if (intrinsics != null) {
            sb.append("\"intrinsics\":{\"fxPixels\":").append(num(intrinsics.fxPixels))
            sb.append(",\"fyPixels\":").append(num(intrinsics.fyPixels))
            sb.append(",\"cxPixels\":").append(num(intrinsics.cxPixels))
            sb.append(",\"cyPixels\":").append(num(intrinsics.cyPixels)).append("},")
        } else {
            sb.append("\"intrinsics\":null,")
        }
        sb.append("\"frames\":[")
        frames.forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append("{\"file\":\"").append(esc(f.file)).append('"')
            sb.append(",\"timestampNanos\":").append(f.timestampNanos)
            sb.append(",\"exposureNanos\":").append(f.exposureNanos)
            sb.append(",\"skewNanos\":").append(f.skewNanos)
            sb.append(",\"iso\":").append(f.iso)
            if (f.gyroFile != null) sb.append(",\"gyro\":\"").append(esc(f.gyroFile)).append('"')
            else sb.append(",\"gyro\":null")
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    /**
     * Writes the per-frame gyro CSVs plus `burst.json` under
     * `Download/RawLens/<subfolder>/` via the Downloads collection.
     * DCIM cannot be used here: MediaStore.Files rejects it with
     * `Primary directory DCIM not allowed` on modern scoped storage.
     * Best-effort by design: throws IOException on failure and the caller
     * logs without failing the burst (DNGs are already safe).
     */
    @Throws(IOException::class)
    fun writeFiles(
        context: Context,
        subfolder: String,
        metaJson: String,
        gyroCsvByName: Map<String, String>
    ) {
        requireSubfolder(subfolder)
        val resolver = context.contentResolver
        // Downloads (minSdk 29) accepts text/csv + application/json under
        // Download/; Files/Images with a DCIM path throw
        // IllegalArgumentException on insert.
        val collection = MediaStore.Downloads.getContentUri("external")
        fun putText(displayName: String, relativeDir: String, mime: String, text: String) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, relativeDir)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: throw IOException("Could not create sidecar entry $displayName")
            try {
                resolver.openOutputStream(uri, "w")?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                } ?: throw IOException("Could not open sidecar stream $displayName")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                if (resolver.update(uri, values, null, null) != 1) {
                    throw IOException("Could not publish sidecar $displayName")
                }
            } catch (failure: Exception) {
                resolver.delete(uri, null, null)
                throw failure
            }
        }
        val dir = sidecarDir(subfolder)
        for ((csvName, csv) in gyroCsvByName) {
            putText(csvDisplayName(csvName), "$dir/$GYRO_DIR", "text/csv", csv)
        }
        putText(META_FILE, dir, "application/json", metaJson)
    }
}
