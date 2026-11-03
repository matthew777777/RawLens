// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real sensor -> verified RCD -> separate grade/P010 -> Main10 + audio, no UI. */
@RunWith(AndroidJUnit4::class)
class PhotoRcdRecordingDeviceTest {
    @Test fun recordRequestedModeHeadless() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        check(args.getString("authorizeRcdRecording")=="true") { "Explicit opt-in required" }
        val context=instrumentation.targetContext
        val w=args.getString("width","1920").toInt();val h=args.getString("height","1080").toInt()
        val fps=args.getString("fps","30").toInt();val seconds=args.getString("seconds","30").toInt()
        require(listOf(Triple(1920,1080,30),Triple(2560,1440,30),Triple(3840,2160,24)).contains(Triple(w,h,fps)))
        require(seconds in 5..660)
        for(permission in listOf("CAMERA","RECORD_AUDIO")) instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.$permission").close()
        val manager=context.getSystemService(CameraManager::class.java)
        val cameraId=args.getString("cameraId") ?: manager.cameraIdList.first { id ->
            val c=manager.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK &&
                c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.RAW_SENSOR)?.isNotEmpty()==true
        }
        val directory=File(context.getExternalFilesDir(null),"photo-rcd-recordings").apply { mkdirs() }
        val label="${w}x${h}-${fps}-${args.getString("label","initial")}";val file=File(directory,"$label.mp4")
        val trace=PhotoRcdRecordingTrace()
        val report=JSONObject().put("requested",JSONArray(listOf(w,h,fps))).put("cameraId",cameraId)
            .put("gpuImage",args.getString("gpuImage")=="true").put("tileLocal",args.getString("tileLocal")=="true").put("frameGlobal",args.getString("frameGlobal")=="true").put("sharedFinal",args.getString("sharedFinal")=="true").put("normalizeOnce",args.getString("normalizeOnce")=="true").put("gpuWriterRequested",args.getString("gpuWriter")=="true").put("reconstructionScale",args.getString("reconstructionScale","1").toInt()).put("requirementsInput",args.getString("requirementsInput")=="true").put("profile","BT709").put("preview",false).put("sourcePolicy",if(args.getString("reconstructionScale","1")=="1") "FULL rung, existing 16:9 crop/FOV, full-source RCD; no detail reduction" else "Approximate RCD: full crop/FOV, Bayer-cell area reduction before reconstruction; reduced fine detail")
        val recorder=EncodedVideoRecorder(manager,cameraId,context).apply {
            width=w;height=h;this.fps=fps;recordingProfile=EncodedVideoProfile.BT709
            rung=EncodedVideoLadder.Rung.FULL;computeStep=2;preferMhc=true;videoDemosaic=VideoDemosaic.MHC
            validatePublicPhotoRcd=true;validatePublicPhotoRcdOptimized=true
            validatePublicPhotoRcdResponseCache=args.getString("responseCache")=="true"
            validatePublicPhotoRcdTiled=args.getString("tiled")=="true"
            validatePublicPhotoRcdImages=args.getString("images")=="true"
            validatePhotoRcdRecordingCandidate=args.getString("recordingCandidate")=="true"
            validatePhotoRcdBakedStages=args.getString("baked")=="true"
            validatePhotoRcdPackedCells=args.getString("packed")=="true"
            validatePhotoRcdGpuImage=args.getString("gpuImage")=="true"
            validatePhotoRcdTileLocal=args.getString("tileLocal")=="true"
            validatePhotoRcdFrameGlobal=args.getString("frameGlobal")=="true"
            validatePhotoRcdSharedFinal=args.getString("sharedFinal")=="true"
            validatePhotoRcdNormalizeOnce=args.getString("normalizeOnce")=="true"
            validatePhotoRcdGpuWriter=args.getString("gpuWriter")=="true"
            validatePhotoRcdRequirementsInput=args.getString("requirementsInput")=="true"
            validatePhotoRcdReconstructionScale=args.getString("reconstructionScale","1").toInt()
            validatePhotoRcdDirectRaw=args.getString("directRaw")=="true"
            photoRcdTrace=trace;allowThermalDegrade=false;audioEnabled=true;stabEnabled=false
            onRecordingError={ message -> report.put("recordingError",message) }
        }
        val power=context.getSystemService(PowerManager::class.java)
        val intervals=JSONArray();report.put("intervals",intervals)
        var started=false
        try {
            report.put("startWallNs",SystemClock.elapsedRealtimeNanos())
            recorder.start(file);started=true
            check(recorder.width==w && recorder.height==h) { "Encoder negotiated a different size" }
            repeat(seconds) {
                SystemClock.sleep(1000)
                intervals.put(JSONObject().put("second",it+1).put("thermal",power.currentThermalStatus)
                    .put("stats",recorder.snapshot().toString()))
            }
            val stats=recorder.stop();started=false
            report.put("stats",JSONObject().apply {
                for(field in stats.javaClass.declaredFields) {
                    if(field.isSynthetic)continue
                    field.isAccessible=true;put(field.name,field.get(stats))
                }
            })
            report.put("actualOutput",JSONArray(listOf(recorder.width,recorder.height)))
            val extractor=MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val formats=JSONArray();for(i in 0 until extractor.trackCount)formats.put(extractor.getTrackFormat(i).toString())
                report.put("trackFormats",formats)
            } finally { extractor.release() }
            report.put("fileBytes",file.length())
        } catch(e:Exception) {
            report.put("error",e.stackTraceToString())
            throw e
        } finally {
            if(started)runCatching { recorder.stop() }.onFailure { report.put("stopError",it.stackTraceToString()) }
            report.put("trace",trace.json()).put("endWallNs",SystemClock.elapsedRealtimeNanos())
            File(directory,"$label.json").writeText(report.toString(2))
        }
    }
}
