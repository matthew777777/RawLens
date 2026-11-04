// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File

/** Recording-start position, rather than a track of the user's movement. */
internal object VideoLocationMetadata {
    fun writeTo(muxer: MediaMuxer, gps: GpsLocation?) {
        gps ?: return
        // MPEG-4 udta/©xyz ISO-6709 location; must precede muxer.start().
        muxer.setLocation(gps.latitude.toFloat(), gps.longitude.toFloat())
    }

    fun writeTo(muxer: androidx.media3.muxer.MediaMuxerCompat, gps: GpsLocation?) {
        gps ?: return
        muxer.setLocation(gps.latitude.toFloat(), gps.longitude.toFloat())
    }

    fun copyFrom(source: File, muxer: androidx.media3.muxer.MediaMuxerCompat) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            parse(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))?.let {
                muxer.setLocation(it.first, it.second)
            }
        } finally {
            retriever.release()
        }
    }

    /** Preserve the source MP4/MOV position when creating a stabilized MP4. */
    fun copyFrom(source: File, muxer: MediaMuxer) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            parse(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))?.let {
                muxer.setLocation(it.first, it.second)
            }
        } catch (failure: RuntimeException) {
            Log.w("RawLensVideoGps", "Source video location unavailable", failure)
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    internal fun parse(value: String?): Pair<Float, Float>? {
        val match = value?.let { ISO6709.matchEntire(it) } ?: return null
        val latitude = match.groupValues[1].toFloatOrNull() ?: return null
        val longitude = match.groupValues[2].toFloatOrNull() ?: return null
        if (!latitude.isFinite() || latitude !in -90f..90f ||
            !longitude.isFinite() || longitude !in -180f..180f) return null
        return latitude to longitude
    }

    private val ISO6709 = Regex("^([+-]\\d{2}(?:\\.\\d+)?)([+-]\\d{3}(?:\\.\\d+)?)(?:[+-]\\d+(?:\\.\\d+)?)?/?$")
}
