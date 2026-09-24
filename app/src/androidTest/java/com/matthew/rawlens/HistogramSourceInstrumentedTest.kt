// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class HistogramSourceInstrumentedTest {
    @Test fun reusablePreviewBitmapStaysOwnedByCallerAndRefreshesBins() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = HistogramView(instrumentation.targetContext)
            val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
            try {
                view.setSourceRaw(false)
                bitmap.eraseColor(Color.RED)
                view.update(bitmap, recycleBitmap = false)
                bitmap.eraseColor(Color.BLACK)
                view.update(bitmap, recycleBitmap = false)
                assertFalse(bitmap.isRecycled)
                val bins = HistogramView::class.java.getDeclaredField("bins")
                    .apply { isAccessible = true }.get(view) as Array<*>
                val red = bins[0] as IntArray
                assertEquals(6, red[0])
                assertEquals(0, red[47])
                view.setSourceRaw(true)
                view.update(bitmap, recycleBitmap = false)
                assertFalse(bitmap.isRecycled)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test fun rawSourceSurvivesCaptureGapAndRejectsLateYuvUpdates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var view: HistogramView
        val raw = RgbHistogram(IntArray(48) { if (it == 9) 17 else 0 },
            IntArray(48), IntArray(48), IntArray(48), true)
        fun redBins(): IntArray = (HistogramView::class.java.getDeclaredField("bins")
            .apply { isAccessible = true }.get(view) as Array<*>)[0] as IntArray
        instrumentation.runOnMainSync {
            view = HistogramView(instrumentation.targetContext)
            view.setSourceRaw(true)
            view.update(raw)
        }
        // Exceed the old two-second RAW hold window used during capture/save gaps.
        SystemClock.sleep(2100)
        instrumentation.runOnMainSync {
            val yuv = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            view.update(yuv)
            assertTrue(yuv.isRecycled)
            assertArrayEquals(raw.red, redBins())

            view.setSourceRaw(false)
            view.update(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) })
            val previewBins = redBins().copyOf()
            assertEquals(4, previewBins[47])
            view.update(raw) // RAW result queued before the user's source change.
            assertArrayEquals(previewBins, redBins())

            view.setSourceRaw(true)
            assertTrue(redBins().all { it == 0 })
            view.update(raw)
            assertArrayEquals(raw.red, redBins())
        }
    }
}
