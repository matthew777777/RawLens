// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.CameraManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Size
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Real recordings, no activities. Requested cadence is separate from measured delivery. */
class Video60ModeInstrumentedTest {
    @Test fun raw24_30_60() = exercise(false)
    @Test fun encoded24_30_60() = exercise(true)

    private fun exercise(encoded: Boolean) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lapisVideo60")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val manager=context.getSystemService(CameraManager::class.java)
        val reports=JSONArray()
        try {
            val rates=InstrumentationRegistry.getArguments().getString("video60Rates")?.split(',')?.map { it.trim().toInt() } ?: listOf(24,30,60)
            for(rate in rates) {
                val key=UUID.randomUUID().toString()
                val ready=CompletableFuture<LapisRootSession.Spec?>()
                LapisRootSessionControl.prepare(context,key,LapisRootProfile.Mode.VIDEO60) {
                    it.fold({ready.complete(it)},{ready.completeExceptionally(it)})
                }
                val spec=requireNotNull(ready.get(60,TimeUnit.SECONDS))
                val lease=LapisRecordingLease(key,spec)
                val file=File(context.cacheDir,"video60-$rate.${if(encoded) "mp4" else "craw"}")
                var stopped=false
                var stop: (() -> Unit)?=null
                var stats=""
                try {
                    if(encoded) {
                        val recorder=EncodedVideoRecorder(manager,"0",context,lease)
                        recorder.fps=rate;recorder.width=1280;recorder.height=720;recorder.audioEnabled=false
                        stop={stats=recorder.stop().toString()}
                        recorder.start(file)
                    } else {
                        val recorder=RawVideoRecorder(manager,"0",Size(spec.width,spec.height),context,lease)
                        recorder.fps=rate;recorder.audioEnabled=false;recorder.compressionCodec=CrawWriter.CODEC_FAST
                        stop={stats=recorder.stop().toString()}
                        recorder.start(file,JSONObject().put("fps",rate).toString())
                    }
                    Thread.sleep(4000)
                    stop.invoke();stopped=true
                    val times=if(encoded) {
                        val extractor=MediaExtractor()
                        try {
                            extractor.setDataSource(file.path)
                            val track=(0 until extractor.trackCount).first {
                                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true
                            }
                            val format=extractor.getTrackFormat(track)
                            assertEquals(1280,format.getInteger(MediaFormat.KEY_WIDTH))
                            assertEquals(720,format.getInteger(MediaFormat.KEY_HEIGHT))
                            extractor.selectTrack(track)
                            val timestamps=ArrayList<Long>()
                            while(extractor.sampleTime>=0) { timestamps+=extractor.sampleTime*1000;if(!extractor.advance())break }
                            timestamps.sorted().toLongArray()
                        } finally { extractor.release() }
                    } else {
                        val info=CrawReader.inspect(file)
                        assertEquals(rate,JSONObject(info.containerJson).getInt("fps"))
                        val handle=CrawReader.open(file.path)
                        try {
                            val pixels=java.nio.ByteBuffer.allocateDirect(spec.width*spec.height*2)
                            assertEquals(spec.width to spec.height,CrawReader.decodeFrame(handle,info.timestamps[info.timestamps.size/2],pixels))
                        } finally { CrawReader.closeReader(handle) }
                        info.timestamps
                    }
                    assertTrue("No video frames at requested $rate",times.size>=20)
                    val measured=(times.size-1)*1e9/(times.last()-times.first())
                    android.util.Log.i("Video60Test","rate=$rate measured=$measured frames=${times.size} stats=$stats")
                    assertTrue("Requested $rate but received $measured fps",measured>rate*0.5 && measured<rate*1.2)
                    reports.put(JSONObject().put("requestedFps",rate).put("measuredFps",measured).put("frames",times.size).put("bytes",file.length()).put("stats",stats))
                } finally { if(!stopped)runCatching { stop?.invoke() };lease.close();file.delete() }
            }
        } finally {
            val normal=CompletableFuture<LapisRootSession.Spec?>()
            val key=UUID.randomUUID().toString()
            LapisRootSessionControl.prepare(context,key,LapisRootProfile.Mode.NORMAL) {
                it.fold({normal.complete(it)},{normal.completeExceptionally(it)})
            }
            assertNull(normal.get(60,TimeUnit.SECONDS));LapisRootSessionControl.release(key)
            File(context.filesDir,"video60-${if(encoded) "encoded" else "raw"}.json").writeText(reports.toString(2))
        }
    }
}
