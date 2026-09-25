// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Keeps full-resolution RAW-to-JPEG work in Android's foreground scheduling group after the
 * camera Activity is backgrounded. The controller starts it only while JPEG work is outstanding.
 */
class JpegProcessingService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // The foreground-service timeout (~10 s from startForegroundService) kills the process
        // when it fires, so this acknowledgement is the first and only main-thread work here.
        // A rejected promotion (background start, service-type enforcement) stops the service
        // instead of crashing: development still finishes at background priority.
        if (!acknowledgeForeground()) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:jpeg-processing")
            .apply { acquire() }
        sampleDiagnosticsAsync("jpeg-service-created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every foreground start must be acknowledged, including a new start delivered
        // to an existing instance. A fast failed job can queue STOP before onCreate runs.
        acknowledgeForeground()
        if (intent?.action == ACTION_STOP) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        sampleDiagnosticsAsync("jpeg-service-destroyed")
        super.onDestroy()
    }

    /**
     * True once this start counts as foreground. Never throws: a rejected promotion stops the
     * service (cancelling the pending timeout) so a backgrounded save degrades instead of dying.
     */
    private fun acknowledgeForeground(): Boolean {
        return try {
            val note = notification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    note,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, note)
            }
            true
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Foreground promotion rejected; developing without it", failure)
            stopSelf()
            false
        }
    }

    private fun sampleDiagnosticsAsync(label: String) {
        Thread({ MemoryLeakDiagnostics.sample(label) }, "jpeg-service-diag").apply {
            isDaemon = true
            start()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Photo processing",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while RawLens develops a captured JPEG"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tune)
            .setContentTitle("Developing photo")
            .setContentText("RAW-to-JPEG processing is in progress")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()
    }

    companion object {
        private const val TAG = "JpegProcessingService"
        private const val CHANNEL_ID = "jpeg_processing"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.matthew.rawlens.STOP_JPEG_PROCESSING"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, JpegProcessingService::class.java))
        }

        fun stop(context: Context) {
            // Deliver shutdown after startup on the service main thread. stopService()
            // here can cancel a pending foreground start before it is acknowledged.
            try {
                context.startService(Intent(context, JpegProcessingService::class.java).apply {
                    action = ACTION_STOP
                })
            } catch (failure: RuntimeException) {
                // Ordered delivery is rejected once backgrounded (background-service start
                // limits), which is exactly when this service matters. Release directly
                // instead of leaking a foreground service; when the matching start was
                // also rejected this is a no-op against a service that never ran.
                Log.i(TAG, "Ordered stop rejected; stopping JPEG service directly", failure)
                runCatching {
                    context.stopService(Intent(context, JpegProcessingService::class.java))
                }
            }
        }
    }
}
