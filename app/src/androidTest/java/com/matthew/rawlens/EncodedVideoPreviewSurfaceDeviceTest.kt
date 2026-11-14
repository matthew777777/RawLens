// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Checks the composed screen: a successful EGL swap alone missed the black monitor. */
class EncodedVideoPreviewSurfaceDeviceTest {
    @Test fun monitorShowsAboveIspAndSurvivesResizeAndReattach() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val activity = inst.startActivitySync(
            Intent(inst.targetContext, PreviewSurfaceProbeActivity::class.java)
                .putExtra("encodedPreview", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as PreviewSurfaceProbeActivity
        val monitor = requireNotNull(activity.encodedPreview)
        val software = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val hardware = requireNotNull(software.copy(Bitmap.Config.HARDWARE, false))
        val buffer = requireNotNull(hardware.hardwareBuffer)
        fun awaitSurface() {
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (SystemClock.elapsedRealtime() < deadline) {
                var ready = false
                inst.runOnMainSync {
                    if (monitor.width > 0 && monitor.height > 0) {
                        val size = VfPresentSize.forLayout(monitor.width, monitor.height, 1080)
                        val frame = monitor.holder.surfaceFrame
                        ready = monitor.isPreviewActive && frame.width() == size.width && frame.height() == size.height
                    }
                }
                if (ready) return
                Thread.sleep(20)
            }
            fail("Monitor surface did not reach its bounded presentation size")
        }
        fun presentAndCheck() {
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (monitor.isIdleForOffer()) {
                    val released = CountDownLatch(1)
                    monitor.offerPreview(buffer, -1, 0, false) { released.countDown() }
                    assertTrue("Preview slot must release", released.await(3, TimeUnit.SECONDS))
                    val screenshot = requireNotNull(inst.uiAutomation.takeScreenshot())
                    val location = IntArray(2)
                    inst.runOnMainSync { monitor.getLocationOnScreen(location) }
                    val pixel = screenshot.getPixel(location[0] + monitor.width / 2, location[1] + monitor.height / 2)
                    screenshot.recycle()
                    if (Color.red(pixel) > 220 && Color.green(pixel) < 30 && Color.blue(pixel) < 30) return
                }
                Thread.sleep(20)
            }
            fail("Monitor swapped but the composed screen does not show its red frame")
        }
        try {
            monitor.setPreviewSize(64, 64)
            monitor.setPreviewAttached(true)
            awaitSurface()
            presentAndCheck()
            inst.runOnMainSync { monitor.layoutParams = FrameLayout.LayoutParams(700, 900) }
            awaitSurface()
            presentAndCheck()
            monitor.setPreviewAttached(false)
            monitor.setPreviewAttached(true)
            presentAndCheck()
        } finally {
            monitor.setPreviewAttached(false)
            inst.runOnMainSync { activity.finish() }
            inst.waitForIdleSync()
            buffer.close()
            hardware.recycle()
            software.recycle()
        }
    }
}
