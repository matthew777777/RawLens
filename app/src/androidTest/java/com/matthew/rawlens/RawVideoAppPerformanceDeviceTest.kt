// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.CameraManager
import android.util.Size
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Real RAW timestamps; existing encoder and format, optional existing GPU denoise. */
class RawVideoAppPerformanceDeviceTest {
    @Test fun recordAndMeasure() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("lapisVideo60")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val denoise=args.getString("denoise")=="true"
        val seconds=args.getString("seconds","15").toLong().coerceIn(5,60)
        val key=UUID.randomUUID().toString()
        val ready=CompletableFuture<LapisRootSession.Spec?>()
        LapisRootSessionControl.prepare(context,key,LapisRootProfile.Mode.VIDEO60) {
            it.fold({ready.complete(it)},{ready.completeExceptionally(it)})
        }
        val spec=requireNotNull(ready.get(60,TimeUnit.SECONDS))
        val file=File(context.cacheDir,"raw-app-performance.craw")
        val recorder=RawVideoRecorder(context.getSystemService(CameraManager::class.java),"0",Size(spec.width,spec.height),context,LapisRecordingLease(key,spec))
        recorder.fps=60;recorder.audioEnabled=true;recorder.dctDenoise=denoise;recorder.compressionCodec=CrawWriter.CODEC_LIVE
        var stopped=false
        try {
            recorder.start(file,"{\"fps\":60}")
            if(denoise) {
                val field=RawVideoRecorder::class.java.getDeclaredField("dct").apply { isAccessible=true }
                assertNotNull("Existing RAW DCT pipeline unavailable",field.get(recorder))
            }
            Thread.sleep(seconds*1000)
            val timing=recorder.snapshot()
            val stats=recorder.stop();stopped=true
            val info=CrawReader.inspect(file);val times=info.timestamps
            assertTrue("No recorded frames",times.size>=20)
            val fps=(times.size-1)*1e9/(times.last()-times.first())
            val handle=CrawReader.open(file.path)
            try {
                val pixels=java.nio.ByteBuffer.allocateDirect(spec.width*spec.height*2)
                assertEquals(spec.width to spec.height,CrawReader.decodeFrame(handle,times[times.size/2],pixels))
                val row=JSONObject().put("requestedFps",60).put("measuredFps",fps).put("frames",times.size)
                    .put("width",spec.width).put("height",spec.height).put("denoise",denoise)
                    .put("workers",recorder.encodeWorkers).put("acquired",stats.framesAcquired).put("dropped",stats.framesDropped)
                    .put("encodeMs",timing.encodeMsAvg).put("writeMs",timing.writeMsAvg).put("bytes",file.length())
                    .put("frameMetadata",JSONObject(CrawReader.frameMetadata(handle,times[times.size/2])?:"{}"))
                    .put("targetMet",fps>=59.5)
                File(context.filesDir,"raw-app-performance-$denoise.json").writeText(row.toString(2))
                android.util.Log.i("RawAppPerformance",row.toString())
            } finally { CrawReader.closeReader(handle) }
        } finally {
            if(!stopped)runCatching { recorder.stop() }
            file.delete()
            val normal=CompletableFuture<LapisRootSession.Spec?>();val normalKey=UUID.randomUUID().toString()
            LapisRootSessionControl.prepare(context,normalKey,LapisRootProfile.Mode.NORMAL) {
                it.fold({normal.complete(it)},{normal.completeExceptionally(it)})
            }
            assertNull(normal.get(60,TimeUnit.SECONDS));LapisRootSessionControl.release(normalKey)
        }
    }
}
