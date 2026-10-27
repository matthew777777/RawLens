// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in live A5 test: GPU/CPU guides, resize, surface recreation and settings. */
class BguViewfinderDeviceTest {
    @Test fun liveGpuCpuResizeAndResume() {
        val inst = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Pass -e rawlensA5 true", InstrumentationRegistry.getArguments().getString("rawlensA5") == "true")
        val ctx = inst.targetContext
        val activity = inst.startActivitySync(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val field = MainActivity::class.java.getDeclaredField("controller").apply { isAccessible=true }
        val controller = field.get(activity) as RawCameraController
        val vf = activity.findViewById<BguViewfinder>(R.id.bguViewfinder)
        val originalMode = controller.vfEngineMode()
        val originalResolution = activity.lensPreferences().getInt(MainActivity.KEY_VF_RESOLUTION, VfResolution.MAX)
        fun settle(name: String, cpu: Boolean, waitMs: Long = 7000) {
            SystemClock.sleep(waitMs)
            val stats = vf.snapshot()
            android.util.Log.i("RawLensBguA5", "$name $stats")
            assertTrue("$name starved: $stats",!stats.starved)
            assertTrue("$name no GL: $stats",stats.glActive)
            assertTrue("$name no presents: $stats",stats.fps>4f)
            assertEquals("$name wrong guide path",!cpu,stats.gpu)
        }
        try {
            inst.runOnMainSync { controller.setVfEngineMode(VfEngineMode.BGU) }
            settle("gpu",false,12000)
            inst.runOnMainSync { controller.setVfEngineMode(VfEngineMode.BGU_CPU) }
            settle("cpu",true)
            inst.runOnMainSync { controller.setVfTargetLongEdge(480) }
            settle("cpu-small",true)
            inst.runOnMainSync { controller.setVfTargetLongEdge(1080) }
            settle("cpu-large",true)
            inst.runOnMainSync {
                controller.setVfEngineMode(VfEngineMode.BGU)
                vf.setRenderJpeg(false)
            }
            settle("gpu-raw",false)
            inst.runOnMainSync { vf.setRenderJpeg(true); vf.setPowerSave(true) }
            settle("gpu-power-save",false)
            inst.runOnMainSync { vf.setPowerSave(false) }
            inst.runOnMainSync { inst.callActivityOnPause(activity) }
            SystemClock.sleep(500)
            inst.runOnMainSync { inst.callActivityOnResume(activity) }
            settle("gpu-resume",false,10000)
        } finally {
            inst.runOnMainSync {
                vf.setPowerSave(false)
                vf.setRenderJpeg(activity.lensPreferences().getString(MainActivity.KEY_VF_PREVIEW_MODE,null)!="RAW")
                controller.setVfTargetLongEdge(originalResolution)
                controller.setVfEngineMode(originalMode)
                activity.finish()
            }
        }
    }
}
