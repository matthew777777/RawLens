// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock

/** Foreground-only collector. Capture never calls a location service or waits for a fix. */
class GpsLocationProvider(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val store = GpsFixStore()
    private val thread = HandlerThread("RawLensGps").apply { start() }
    private val worker = Handler(thread.looper)
    private val manager by lazy { this.context.getSystemService(LocationManager::class.java) }
    private val appOps by lazy { this.context.getSystemService(AppOpsManager::class.java) }
    @Volatile private var started = false
    @Volatile private var closed = false
    // Worker-confined registration state. Each listener carries its own cache generation.
    private var listener: LocationListener? = null
    private var precision = 0
    private var observing = false

    private val opListener = AppOpsManager.OnOpChangedListener { _, packageName ->
        if (packageName == this.context.packageName) accessChanged()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = accessChanged()
    }
    private val maintenance = object : Runnable {
        override fun run() {
            reconcile()
            if (started && !closed) worker.postDelayed(this, ACCESS_CHECK_MILLIS)
        }
    }

    fun hasPermission(): Boolean = hasFinePermission() ||
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasFinePermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Synchronized fun start() {
        if (closed || started) return
        store.reset(false)
        started = true
        worker.post {
            if (!started || closed) return@post
            observe()
            worker.removeCallbacks(maintenance)
            maintenance.run()
        }
    }

    @Synchronized fun stop() {
        started = false
        store.reset(false) // Immediate, including when no registration succeeded.
        worker.post {
            worker.removeCallbacks(maintenance)
            unregister()
            stopObserving()
        }
    }

    fun isRunning(): Boolean = started
    fun snapshot(): GpsLocation? = if (started && !closed) store.snapshot(SystemClock.elapsedRealtimeNanos()) else null

    private fun accessChanged() {
        store.reset(false) // Revoke cached fine coordinates before a possible coarse restart.
        worker.post {
            unregister()
            reconcile()
        }
    }

    @Suppress("DEPRECATION")
    private fun accessPrecision(): Int {
        if (manager?.isLocationEnabled != true) return 0
        fun allowed(op: String): Boolean = when (appOps?.checkOpNoThrow(op, Process.myUid(), context.packageName)) {
            AppOpsManager.MODE_ALLOWED, AppOpsManager.MODE_DEFAULT -> true
            else -> false
        }
        return when {
            hasFinePermission() && allowed(AppOpsManager.OPSTR_FINE_LOCATION) -> 2
            hasPermission() && allowed(AppOpsManager.OPSTR_COARSE_LOCATION) -> 1
            else -> 0
        }
    }

    @SuppressLint("MissingPermission")
    private fun reconcile() {
        if (!started || closed) return
        try {
            val access = accessPrecision()
            if (listener != null && access == precision) return
            unregister()
            if (access == 0) return
            val token = store.reset(true)
            precision = access
            val next = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (!started || closed) return
                    // All callbacks run on the worker; recheck before admitting a new fix.
                    try {
                        if (accessPrecision() != access) { accessChanged(); return }
                        fromLocation(location)?.let {
                            store.offer(token, it, location.elapsedRealtimeNanos,
                                SystemClock.elapsedRealtimeNanos(), location.provider)
                        }
                    } catch (_: RuntimeException) {
                        accessChanged()
                    }
                }
                override fun onProviderDisabled(provider: String) = accessChanged()
                override fun onProviderEnabled(provider: String) = accessChanged()
                @Deprecated("Deprecated by the Android location API")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            listener = next
            val providers = if (access == 2) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                else listOf(LocationManager.NETWORK_PROVIDER)
            var registered = false
            for (provider in providers) {
                try {
                    if (manager?.isProviderEnabled(provider) != true) continue
                    manager?.getLastKnownLocation(provider)?.let(next::onLocationChanged)
                    manager?.requestLocationUpdates(provider, MIN_TIME_MILLIS, MIN_DISTANCE_METERS, next, thread.looper)
                    registered = true
                } catch (failure: IllegalArgumentException) {
                    Log.w(LOG_TAG, "Location provider unavailable", failure)
                }
            }
            // stop() may have run while a platform call was in progress.
            if (!registered || !started || closed) unregister()
        } catch (failure: RuntimeException) {
            unregister()
            Log.w(LOG_TAG, "Location access unavailable", failure)
        }
    }

    private fun unregister() {
        store.reset(false)
        val previous = listener
        listener = null
        precision = 0
        if (previous != null) try { manager?.removeUpdates(previous) } catch (_: RuntimeException) { }
    }

    private fun observe() {
        if (observing) return
        observing = true
        try {
            appOps?.startWatchingMode(AppOpsManager.OPSTR_FINE_LOCATION, context.packageName, opListener)
            appOps?.startWatchingMode(AppOpsManager.OPSTR_COARSE_LOCATION, context.packageName, opListener)
            context.registerReceiver(receiver, IntentFilter().apply {
                addAction(LocationManager.MODE_CHANGED_ACTION)
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            })
        } catch (failure: RuntimeException) {
            Log.w(LOG_TAG, "Location access observer unavailable", failure)
        }
    }

    private fun stopObserving() {
        if (!observing) return
        observing = false
        try { appOps?.stopWatchingMode(opListener) } catch (_: RuntimeException) { }
        try { context.unregisterReceiver(receiver) } catch (_: RuntimeException) { }
    }

    @Synchronized override fun close() {
        if (closed) return
        stop()
        closed = true
        worker.post { thread.quitSafely() }
    }

    companion object {
        const val LOG_TAG = "RawLensGps"
        const val MIN_TIME_MILLIS = 10_000L
        const val MIN_DISTANCE_METERS = 0f
        const val MAX_LAST_KNOWN_AGE_MILLIS = 120_000L
        private const val ACCESS_CHECK_MILLIS = 10_000L

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

    }
}
