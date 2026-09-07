// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LensDiscoveryInstrumentedTest {
    @Test
    fun discoveryIncludesEveryQueryableRearRawCamera() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(CameraManager::class.java)
        val expected = (manager.cameraIdList.toList() + (0..200).map(Int::toString))
            .distinct()
            .filter { id ->
                runCatching { manager.getCameraCharacteristics(id) }.getOrNull()?.let { c ->
                    val raw = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                        ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
                    c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_FRONT && raw
                } == true
            }
            .toSet()

        val latch = CountDownLatch(1)
        var actual = emptyList<DiscoveredLens>()
        LensDiscovery(context).discover {
            actual = it
            latch.countDown()
        }
        assertTrue("Lens discovery timed out", latch.await(60, TimeUnit.SECONDS))
        Log.i("RawLensDiscoveryTest", "expected=$expected actual=${actual.map(DiscoveredLens::id)}")
        assertTrue("Missing directly queryable RAW lenses: ${expected - actual.map { it.id }.toSet()}",
            actual.map { it.id }.toSet().containsAll(expected))
    }
}
