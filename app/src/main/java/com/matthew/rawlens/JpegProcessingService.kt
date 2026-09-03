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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps full-resolution RAW-to-JPEG work in Android's foreground scheduling group after the
 * camera Activity is backgrounded. The controller starts it only while JPEG work is outstanding.
 */
class JpegProcessingService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:jpeg-processing")
            .apply { acquire() }
        MemoryLeakDiagnostics.sample("jpeg-service-created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        MemoryLeakDiagnostics.sample("jpeg-service-destroyed")
        super.onDestroy()
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
        private const val CHANNEL_ID = "jpeg_processing"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            context.startForegroundService(Intent(context, JpegProcessingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JpegProcessingService::class.java))
        }
    }
}
