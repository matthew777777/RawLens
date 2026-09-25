// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Opt-in camera/GPU contention regression. Uses synthetic RAW; saves no photographs. */
class RawSrPreviewInstrumentedTest {
    @Test fun fullResolutionMergeKeepsRawPreviewPresenting() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("rawlensPreviewStress") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA").close()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val vf = activity.findViewById<RawViewfinder>(R.id.rawViewfinder)
        val running = AtomicBoolean(false)
        val maxAge = AtomicLong(0)
        val inactive = AtomicLong(0)
        var monitor: Thread? = null
        try {
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (!vf.snapshot().glActive && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertTrue("RAW preview never became live", vf.snapshot().glActive)
            val w = 4080; val h = 3060
            val plane = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder())
            for (y in 0 until h) for (x in 0 until w) {
                plane.putShort((2000 + ((x / 16 + y / 16) % 2) * 1000).toShort())
            }
            plane.flip()
            val frame = RawSrPackedFrame(plane, RawPlaneLayout(w, h, w * 2, 2), RawCrop(0, 0, w, h),
                RawNormalization(BayerPattern.RGGB, List(4) { 64f }, 4095f), null)
            val displayed = RawViewfinder::class.java.getDeclaredField("lastDisplayed").apply { isAccessible = true }
            running.set(true)
            monitor = Thread {
                while (running.get()) {
                    val age = SystemClock.elapsedRealtime() - displayed.getLong(vf)
                    maxAge.updateAndGet { maxOf(it, age) }
                    if (!vf.snapshot().glActive) inactive.incrementAndGet()
                    SystemClock.sleep(20)
                }
            }.also { it.start() }
            val start = SystemClock.elapsedRealtime()
            VkRawSrProcessor(context).use { processor ->
                processor.processPacked(List(8) { frame }) {
                    Log.i("RawLensSrPreview", "mergeMs=${SystemClock.elapsedRealtime() - start} peakTextureBytes=${it.peakTextureBytes}")
                }
            }
            running.set(false)
            monitor.join()
            Log.i("RawLensSrPreview", "maxFrameAgeMs=${maxAge.get()} inactiveSamples=${inactive.get()} fps=${vf.snapshot().fps}")
            assertTrue("RAW preview stopped during merge: max age ${maxAge.get()}ms", maxAge.get() < 2000)
            assertTrue("RAW preview became inactive during merge", inactive.get() == 0L)
        } finally {
            running.set(false)
            monitor?.join(5000)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
