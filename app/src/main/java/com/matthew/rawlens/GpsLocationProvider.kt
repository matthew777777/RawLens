// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log

/**
 * Platform-location source for photo geotagging. Deliberately built on
 * [LocationManager] so geotagging needs no Play Services dependency.
 *
 * The provider holds no fix until [start] registers it; [stop] unregisters so
 * no location is collected while the app is backgrounded or the option is
 * off. [snapshot] returns the freshest live fix, falling back to the
 * platform's last-known position only while it is younger than
 * [MAX_LAST_KNOWN_AGE_MILLIS]; anything older is treated as no fix rather
 * than a stale geotag.
 */
class GpsLocationProvider(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)

    @Volatile private var latestGps: Location? = null
    @Volatile private var latestNetwork: Location? = null
    @Volatile private var started = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.provider == LocationManager.GPS_PROVIDER) latestGps = location
            else latestNetwork = location
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    fun hasFinePermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (started || !hasPermission()) return
        try {
            if (hasFinePermission() && manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, MIN_TIME_MILLIS, MIN_DISTANCE_METERS,
                    listener, Looper.getMainLooper()
                )
            }
            if (manager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                manager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, MIN_TIME_MILLIS, MIN_DISTANCE_METERS,
                    listener, Looper.getMainLooper()
                )
            }
            started = true
        } catch (failure: SecurityException) {
            Log.w(LOG_TAG, "Location updates denied without permission", failure)
        } catch (failure: IllegalArgumentException) {
            Log.w(LOG_TAG, "Location provider unavailable", failure)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            manager?.removeUpdates(listener)
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "Could not unregister location listener", failure)
        }
    }

    /**
     * Freshest usable fix, or null when location is disabled, unpermitted, or
     * only stale positions exist. Never throws: every platform failure maps
     * to "no geotag" so a location outage cannot fail a capture.
     */
    fun snapshot(): GpsLocation? {
        if (!hasPermission()) return null
        val live = pickNewer(latestGps, latestNetwork)
        live?.let { return fromLocation(it) }
        return try {
            val now = System.currentTimeMillis()
            val candidates = listOfNotNull(
                lastKnown(LocationManager.GPS_PROVIDER),
                lastKnown(LocationManager.NETWORK_PROVIDER)
            ).filter { now - it.time <= MAX_LAST_KNOWN_AGE_MILLIS }
            pickNewest(candidates)?.let(::fromLocation)
        } catch (failure: SecurityException) {
            null
        } catch (failure: IllegalArgumentException) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(provider: String): Location? {
        if (!hasPermission()) return null
        return try {
            manager?.getLastKnownLocation(provider)
        } catch (failure: SecurityException) {
            null
        }
    }

    companion object {
        const val LOG_TAG = "RawLensGps"
        const val MIN_TIME_MILLIS = 1000L
        const val MIN_DISTANCE_METERS = 0f
        const val MAX_LAST_KNOWN_AGE_MILLIS = 5L * 60L * 1000L

        fun permissions(): Array<String> = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        /**
         * Converts a platform fix, returning null for fixes that cannot be
         * represented in EXIF (non-finite or out-of-range coordinates).
         */
        fun fromLocation(location: Location): GpsLocation? {
            if (!location.latitude.isFinite() || !location.longitude.isFinite()) return null
            if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return null
            val altitude = if (location.hasAltitude() && location.altitude.isFinite()) {
                location.altitude
            } else null
            val accuracy = if (location.hasAccuracy() && location.accuracy.isFinite()) {
                location.accuracy
            } else null
            return try {
                GpsLocation(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitudeMeters = altitude,
                    timeMillis = location.time,
                    processingMethod = if (location.provider == LocationManager.GPS_PROVIDER) {
                        GpsLocation.METHOD_GPS
                    } else {
                        GpsLocation.METHOD_NETWORK
                    },
                    accuracyMeters = accuracy
                )
            } catch (failure: IllegalArgumentException) {
                null
            }
        }

        private fun pickNewer(first: Location?, second: Location?): Location? {
            if (first == null) return second
            if (second == null) return first
            return if (first.time >= second.time) first else second
        }

        private fun pickNewest(candidates: List<Location>): Location? =
            candidates.maxByOrNull { it.time }
    }
}
