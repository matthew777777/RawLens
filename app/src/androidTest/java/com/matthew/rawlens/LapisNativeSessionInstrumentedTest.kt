// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** No activities: real app Camera2 repeating RAW, pre-shutter ZSL, burst and production HDR+ merge. */
class LapisNativeSessionInstrumentedTest {
    @Test fun fiftyMpNativePipeline() = run(LapisRootProfile.Mode.FULL)
    @Test fun sensor2xNativePipeline() = run(LapisRootProfile.Mode.CROP_2X)
    @Test fun dcg14NativePipeline() = run(LapisRootProfile.Mode.DCG14)

    @Test fun killedWorkerRestoresSensor() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lapisKillWorker")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val lease=LapisRootSession.open(context,LapisRootProfile.Mode.CROP_2X)
        try {
            val command="pid=\$(cat /data/adb/rawlens-lapis/owner.pid); " +
                "case \"\$(tr '\\000' ' ' </proc/\$pid/cmdline)\" in " +
                "*com.matthew.rawlens.LapisRootCameraWorker*session-2x*) kill -KILL \$pid;; *) exit 1;; esac"
            val kill=ProcessBuilder("su","-M","-c",command).start()
            assertTrue(kill.waitFor(5,TimeUnit.SECONDS));assertEquals(0,kill.exitValue())
        } finally { lease.close() }
        File(context.filesDir,"lapis-killed-worker-recovered.txt").writeText("Verified watchdog restoration after SIGKILL")
    }

    @Test fun dcg14RawVideoContainer() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lapisNativeSession")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val key=java.util.UUID.randomUUID().toString()
        val prepared=CompletableFuture<LapisRootSession.Spec?>()
        LapisRootSessionControl.prepare(context,key,LapisRootProfile.Mode.DCG14) { result ->
            result.fold({prepared.complete(it)},{prepared.completeExceptionally(it)})
        }
        val spec=requireNotNull(prepared.get(60,TimeUnit.SECONDS))
        val lease=LapisRecordingLease(key,spec)
        val output=File(context.cacheDir,"lapis-dcg14-acceptance.craw")
        val recorder=RawVideoRecorder(context.getSystemService(CameraManager::class.java),"0",android.util.Size(4080,3060),context,lease)
        recorder.audioEnabled=false
        recorder.compressionCodec=CrawWriter.CODEC_FAST
        var stopped=false
        try {
            recorder.start(output,"{}")
            Thread.sleep(4000)
            val stats=recorder.stop();stopped=true
            assertTrue("No encoded DCG frames: $stats",stats.framesEncoded>=20)
            val info=CrawReader.inspect(output)
            assertTrue(info.timestamps.size>=20)
            assertEquals(16383,JSONObject(info.containerJson).getInt("whiteLevel"))
            val handle=CrawReader.open(output.path)
            try {
                val data=java.nio.ByteBuffer.allocateDirect(4080*3060*2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                assertEquals(4080 to 3060,CrawReader.decodeFrame(handle,info.timestamps[info.timestamps.size/2],data))
                var maximum=0
                for(i in 0 until 4080*3060 step 127)maximum=maxOf(maximum,data.getShort(i*2).toInt() and 65535)
                assertTrue("CRAW truncated DCG to ten bits: $maximum",maximum>1023)
                assertTrue(maximum<=16383)
            }finally { CrawReader.closeReader(handle) }
            File(context.filesDir,"lapis-dcg14-recording.json").writeText(JSONObject()
                .put("encodedFrames",stats.framesEncoded).put("containerFrames",info.timestamps.size)
                .put("bytes",output.length()).put("whiteLevel",16383).put("decoded14Bit",true).toString(2))
        } finally { if(!stopped)runCatching { recorder.stop() };lease.close() }
        val restored=CompletableFuture<LapisRootSession.Spec?>()
        val normalKey=java.util.UUID.randomUUID().toString()
        LapisRootSessionControl.prepare(context,normalKey,LapisRootProfile.Mode.NORMAL) { result ->
            result.fold({restored.complete(it)},{restored.completeExceptionally(it)})
        }
        assertNull(restored.get(60,TimeUnit.SECONDS))
        LapisRootSessionControl.release(normalKey)
    }

    private fun run(mode: LapisRootProfile.Mode) {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("lapisNativeSession")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val lease=LapisRootSession.open(context,mode)
        val thread=HandlerThread("lapis-native-test").apply { start() };val handler=Handler(thread.looper)
        var device:CameraDevice?=null;var session:CameraCaptureSession?=null;var reader:ImageReader?=null
        val ring=RawZslBuffer(3)
        val images=HashMap<Long,Image>();val results=HashMap<Long,TotalCaptureResult>()
        val warm=CountDownLatch(if(mode==LapisRootProfile.Mode.DCG14)90 else 8);val burst=CountDownLatch(6)
        val errors=CompletableFuture<Throwable>()
        val burstFrames=mutableListOf<BufferedRawFrame>()
        val timestamps=mutableListOf<Long>()
        val lock=Any();var collectingBurst=false;var accepting=true
        var selected:List<BufferedRawFrame> = emptyList()
        try {
            val manager=context.getSystemService(CameraManager::class.java)
            val c=manager.getCameraCharacteristics("0")
            LapisRootSession.bind(c,lease.spec)
            val opened=CompletableFuture<CameraDevice>()
            manager.openCamera("0",object:CameraDevice.StateCallback(){
                override fun onOpened(d:CameraDevice){opened.complete(d)}
                override fun onDisconnected(d:CameraDevice){opened.completeExceptionally(IllegalStateException("disconnected"))}
                override fun onError(d:CameraDevice,e:Int){opened.completeExceptionally(IllegalStateException("camera error $e"))}
            },handler)
            val camera=opened.get(20,TimeUnit.SECONDS);device=camera
            val width=lease.spec.width;val height=lease.spec.height
            val raw=ImageReader.newInstance(width,height,ImageFormat.RAW_SENSOR,10);reader=raw
            fun paired(timestamp:Long) {
                val image=images[timestamp]?:return
                val result=results[timestamp]?:return
                images.remove(timestamp);results.remove(timestamp)
                if(!accepting||!lease.spec.accepts(image)){image.close();return}
                timestamps.add(timestamp)
                if(collectingBurst) {
                    burstFrames.add(BufferedRawFrame(image,result,timestamp,result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?:20_000_000L,0,0f))
                    burst.countDown()
                } else { ring.add(image,result,0f);warm.countDown() }
            }
            raw.setOnImageAvailableListener({ r ->
                try { val image=r.acquireNextImage()?:return@setOnImageAvailableListener
                    synchronized(lock) { images[image.timestamp]=image;paired(image.timestamp) }
                }catch(failure:Throwable){errors.complete(failure)}
            },handler)
            val configured=CompletableFuture<CameraCaptureSession>()
            val configuration=SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(raw.surface)),{ command -> handler.post(command) },object:CameraCaptureSession.StateCallback(){
                    override fun onConfigured(s:CameraCaptureSession){configured.complete(s)}
                    override fun onConfigureFailed(s:CameraCaptureSession){configured.completeExceptionally(IllegalStateException("configure failed"))}
                })
            if(lease.spec.dcg) configuration.setSessionParameters(camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                lease.spec.applyRequest(this)
            }.build())
            camera.createCaptureSession(configuration)
            session=configured.get(20,TimeUnit.SECONDS)
            fun request(template:Int)=camera.createCaptureRequest(template).apply {
                addTarget(raw.surface)
                set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_SENSITIVITY,400);set(CaptureRequest.SENSOR_EXPOSURE_TIME,20_000_000L)
                set(CaptureRequest.SENSOR_FRAME_DURATION,100_000_000L)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,Range(10,10))
                lease.spec.applyRequest(this)
            }.build()
            val callback=object:CameraCaptureSession.CaptureCallback(){
                override fun onCaptureCompleted(s:CameraCaptureSession,q:CaptureRequest,r:TotalCaptureResult){
                    val timestamp=r.get(CaptureResult.SENSOR_TIMESTAMP)?:return
                    synchronized(lock){results[timestamp]=r;paired(timestamp)}
                }
                override fun onCaptureFailed(s:CameraCaptureSession,q:CaptureRequest,f:CaptureFailure){errors.complete(IllegalStateException("capture failed ${f.reason}"))}
            }
            session.setRepeatingRequest(request(CameraDevice.TEMPLATE_PREVIEW),callback,handler)
            assertTrue("No steady native RAW/ZSL stream; ${errors.getNow(null)}",warm.await(25,TimeUnit.SECONDS))
            val shutter=SystemClock.elapsedRealtimeNanos()
            synchronized(lock){accepting=false;selected=ring.takeAll()}
            session.stopRepeating()
            assertEquals(3,selected.size)
            assertTrue("ZSL must precede shutter",selected.all { it.timestampNanos<shutter })
            val reference=selected[1]
            val metadata=RawFrameMetadataFactory.capture("0",reference.image,c,reference.result,1)
            assertNull(metadata.rawDevelopmentUnsupportedReason)
            assertEquals(mode.argument,metadata.sensorReadoutProfile)
            if(lease.spec.dcg) {
                assertTrue("DCG14 white level lost",(metadata.whiteLevel?:0f)>1023f)
                assertTrue("DCG14 black level lost",metadata.blackLevels!!.toList().all { it>256f })
            }
            val saved=DngSaver(context).save(reference.image,c,reference.result,1,DngMetadataOverrides(),metadata,DngWriterBackend.AUTO)
            val report=JSONObject().put("mode",mode.argument).put("width",width).put("height",height)
                .put("zslFrames",selected.size).put("sourceDng",saved).put("hardwareVerified",lease.spec.hardwareVerified)
                .put("whiteLevel",metadata.whiteLevel).put("blackLevels",metadata.blackLevels?.toList())
            if(lease.spec.dcg) {
                val fps=(timestamps.size-1)*1e9/(timestamps.last()-timestamps.first())
                report.put("verifiedFrames",timestamps.size).put("measuredFps",fps)
                assertTrue("DCG cadence below 27 fps: $fps",fps>27.0)
            }
            if(args.getString("lapisMerge")=="true") {
                val processor=HdrPlusMerge(context)
                val merged=processor.merge(selected.map { frame ->
                    val plane=frame.image.planes.single()
                    HdrPlusMerge.FrameInput(frame.image,RawPlaneLayout(width,height,plane.rowStride,plane.pixelStride),
                        RawCrop(0,0,width,height),requireNotNull(metadata.normalizationOrNull()),timestampNanos=frame.timestampNanos,
                        sensitivityIso=400,gyroRadiansPerSecond=0f)
                },1,HdrPlusSettings(enabled=true,highQuality=false,autoQuality=false,autoStrength=false))
                assertNotNull("Production HDR+ merge failed",merged)
                assertEquals(width,merged!!.width);assertEquals(height,merged.height)
                val mergeName=DngSaver(context).saveMerged(merged,metadata.copy(compositeSourceCount=3,compositeUsedCount=3),sixteenBit=true)
                report.put("mergedFrames",3).put("mergedDng",mergeName)
            }
            selected.forEach { it.image.close() };selected=emptyList()
            synchronized(lock){collectingBurst=true;accepting=true}
            session.captureBurst(List(6){request(CameraDevice.TEMPLATE_STILL_CAPTURE)},callback,handler)
            assertTrue("Six-frame native burst did not complete; ${errors.getNow(null)}",burst.await(25,TimeUnit.SECONDS))
            synchronized(lock){accepting=false;assertEquals(6,burstFrames.size)}
            report.put("burstFrames",burstFrames.size)
            File(context.filesDir,"lapis-native-${mode.argument}.json").writeText(report.toString(2))
        } finally {
            synchronized(lock){accepting=false}
            session?.close();reader?.setOnImageAvailableListener(null,null)
            synchronized(lock){images.values.forEach { it.close() };images.clear();burstFrames.forEach { it.image.close() };burstFrames.clear()}
            selected.forEach { it.image.close() };ring.clear();reader?.close();device?.close();thread.quitSafely()
            lease.close()
        }
    }
}
