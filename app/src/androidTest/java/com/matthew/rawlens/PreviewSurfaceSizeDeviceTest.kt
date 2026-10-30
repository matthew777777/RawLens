// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Intent
import android.opengl.EGL14
import android.os.Handler
import android.os.SystemClock
import android.view.Surface
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreviewSurfaceSizeDeviceTest {
    @Test fun compositorSurfaceTracksPreviewSizeAndGlRecreatesAfterResize() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(inst.targetContext, PreviewSurfaceProbeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = inst.startActivitySync(intent) as PreviewSurfaceProbeActivity
        val vf = activity.preview
        fun awaitSize(edge: Int) {
            val until = SystemClock.elapsedRealtime() + 5000
            while (SystemClock.elapsedRealtime() < until) {
                var ready = false
                inst.runOnMainSync {
                    val frame = vf.holder.surfaceFrame
                    val want = VfPresentSize.forLayout(vf.width.coerceAtLeast(1), vf.height.coerceAtLeast(1), edge)
                    ready = vf.holder.surface.isValid && frame.width() == want.width && frame.height() == want.height
                }
                if (ready) return
                Thread.sleep(20)
            }
            fail("surface did not resize to edge $edge")
        }
        fun present() {
            val type = RawViewfinder::class.java
            fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }.get(vf)
            val handler = field("worker") as Handler
            val latch = CountDownLatch(1)
            val error = AtomicReference<Throwable>()
            handler.post {
                try {
                    if (field("surface") == EGL14.EGL_NO_SURFACE) {
                        type.getDeclaredMethod("initializeGl", Surface::class.java).apply { isAccessible = true }
                            .invoke(vf, vf.holder.surface)
                    }
                    type.getDeclaredMethod("coverPresentViewport", Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
                        .invoke(vf, 1020, 765, 90)
                    type.getDeclaredMethod("checkPresent").apply { isAccessible = true }.invoke(vf)
                } catch (t: Throwable) { error.set(t) } finally { latch.countDown() }
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            error.get()?.let { throw AssertionError("production GL surface present failed", it) }
        }
        try {
            awaitSize(1080); present()
            inst.runOnMainSync { vf.setTargetLongEdge(640) }
            awaitSize(640); present()
            inst.runOnMainSync { vf.layoutParams = FrameLayout.LayoutParams(700, 900) }
            awaitSize(640); present()
            inst.runOnMainSync { vf.setTargetLongEdge(1080) }
            awaitSize(1080); present()
            inst.runOnMainSync { vf.setRecordMode(true) }
            awaitSize(480); present()
            inst.runOnMainSync { vf.setRecordMode(false) }
            awaitSize(1080); present()
        } finally { inst.runOnMainSync { activity.finish() } }
    }
}
