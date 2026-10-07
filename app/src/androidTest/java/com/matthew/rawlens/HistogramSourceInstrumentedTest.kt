// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class HistogramSourceInstrumentedTest {
    @Test fun histogramAgxSelectionClearsBinsAndRejectsStaleSource() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var view: HistogramView
        val linear = RgbHistogram(IntArray(48) { if (it == 9) 17 else 0 },
            IntArray(48), IntArray(48), IntArray(48), agxApplied = false)
        val curved = RgbHistogram(IntArray(48) { if (it == 40) 13 else 0 },
            IntArray(48), IntArray(48), IntArray(48), agxApplied = true)
        fun redBins(): IntArray = (HistogramView::class.java.getDeclaredField("bins")
            .apply { isAccessible = true }.get(view) as Array<*>)[0] as IntArray
        instrumentation.runOnMainSync {
            view = HistogramView(instrumentation.targetContext)
            view.setAgxApplied(false)
            view.update(linear)
            assertArrayEquals(linear.red, redBins())
            // AgX frame queued before the user's format change must not leak in.
            view.update(curved)
            assertArrayEquals(linear.red, redBins())

            view.setAgxApplied(true)
            assertTrue(redBins().all { it == 0 })
            view.update(curved)
            assertArrayEquals(curved.red, redBins())
            view.update(linear)
            assertArrayEquals(curved.red, redBins())
        }
    }

    @Test fun waveformAgxSelectionClearsCellsAndRejectsStaleSource() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var view: WaveformView
        val cols = RawWaveformSampler.COLUMNS
        val levels = RawWaveformSampler.LEVELS
        val linear = RgbWaveform(cols, levels,
            IntArray(cols * levels) { if (it == 9) 17 else 0 },
            IntArray(cols * levels), IntArray(cols * levels), agxApplied = false)
        val curved = RgbWaveform(cols, levels,
            IntArray(cols * levels) { if (it == 40) 13 else 0 },
            IntArray(cols * levels), IntArray(cols * levels), agxApplied = true)
        fun redCells(): IntArray = (WaveformView::class.java.getDeclaredField("density")
            .apply { isAccessible = true }.get(view) as Array<*>)[0] as IntArray
        instrumentation.runOnMainSync {
            view = WaveformView(instrumentation.targetContext)
            view.setAgxApplied(false)
            view.update(linear)
            assertArrayEquals(linear.red, redCells())
            view.update(curved)
            assertArrayEquals(linear.red, redCells())

            view.setAgxApplied(true)
            assertTrue(redCells().all { it == 0 })
            view.update(curved)
            assertArrayEquals(curved.red, redCells())
            view.update(linear)
            assertArrayEquals(curved.red, redCells())
        }
    }
}
