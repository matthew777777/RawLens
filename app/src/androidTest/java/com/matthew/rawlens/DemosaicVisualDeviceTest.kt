// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Visual Direct-Log demosaic check (docs/refactor/vf-native-study.md):
 * records a short take per [VideoDemosaic] and saves a full-res frame from
 * the middle of each as PNG for inspection on the host. Opt-in:
 * `-e rawlensDemosaicVisual true` (output: files/demosaic-<MHC|RCD>.png).
 */
class DemosaicVisualDeviceTest {
    @Test
    fun recordFramePerDemosaic() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Pass -e rawlensDemosaicVisual true", args.getString("rawlensDemosaicVisual") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.CAMERA").close()
        val manager = context.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.first {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
        val out = context.getExternalFilesDir(null)!!
        for (demosaic in VideoDemosaic.entries) {
            val file = File(context.cacheDir, "visual_${demosaic.name}.mp4").apply { delete() }
            val rec = DirectLogRecorder(manager, id, context).apply {
                videoDemosaic = demosaic
                allowThermalDegrade = false
                stabEnabled = false
            }
            rec.start(file)
            SystemClock.sleep(4_000)
            val stats = rec.stop()
            Log.i(TAG, "VISUAL ${demosaic.name} acq=${stats.framesAcquired} graded=${stats.framesGraded} " +
                "drop=${stats.framesDropped} export=${stats.exportSize}")
            MediaMetadataRetriever().apply {
                setDataSource(file.absolutePath)
                val frame = getFrameAtTime(2_000_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: error("no frame in ${file.name}")
                File(out, "demosaic-${demosaic.name}.png").outputStream().use {
                    frame.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                release()
            }
            file.delete()
        }
    }

    private companion object {
        const val TAG = "RawLensDemosaicVisual"
    }
}
