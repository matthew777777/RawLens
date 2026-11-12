// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.hardware.camera2.CameraCharacteristics
import android.media.Image
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Session-scoped root selection; the regular app Camera2 pipeline owns preview/ZSL/bursts. */
internal class LapisRootSession private constructor(val mode: LapisRootProfile.Mode,
    private val process: Process, private val job: File) : AutoCloseable {
    class Spec(val mode: LapisRootProfile.Mode) {
        val width = if (mode == LapisRootProfile.Mode.FULL) 8160 else 4080
        val height = if (mode == LapisRootProfile.Mode.FULL) 6120 else 3060
        val dcg: Boolean get() = mode == LapisRootProfile.Mode.DCG14
        val fps: Int get() = if(dcg) 30 else 10
        fun applyRequest(builder: CaptureRequest.Builder) {
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,Range(fps,fps))
            if(mode==LapisRootProfile.Mode.CROP_4X) {
                builder.set(CaptureRequest.Key("com.mediatek.seamlessfeature.configSensorScenarios",IntArray::class.java),intArrayOf(0,1,4))
                builder.set(CaptureRequest.Key("com.mediatek.seamlessfeature.configCellCropSensorIds",IntArray::class.java),intArrayOf(0))
                builder.set(CaptureRequest.Key("com.mediatek.seamlessfeature.forceSensorMode",IntArray::class.java),intArrayOf(0,8))
                builder.set(CaptureRequest.Key("com.mediatek.seamlessfeature.sensorScenario",IntArray::class.java),intArrayOf(0,5))
            }
            if(!dcg)return
            builder.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY,null)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME,null)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION,33_333_333L)
            builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT,CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
            builder.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_OFF)
            builder.set(CaptureRequest.Key("com.mediatek.hdrfeature.hdrMode",Int::class.javaObjectType),3)
            builder.set(CaptureRequest.Key("com.mediatek.hdrfeature.SessionParamhdrMode",Int::class.javaObjectType),3)
            builder.set(CaptureRequest.Key("com.mediatek.seamlessfeature.configSensorScenarios",IntArray::class.java),intArrayOf(0,1,12))
            builder.set(CaptureRequest.Key("com.mediatek.seamlessfeature.forceSensorMode",IntArray::class.java),intArrayOf(0,9))
            builder.set(CaptureRequest.Key("com.mediatek.seamlessfeature.sensorScenario",IntArray::class.java),intArrayOf(0,12))
        }
        @Volatile var hardwareVerified = false
        @Volatile private var pixelsVerified = false
        val metadataVerified: Boolean get() = pixelsVerified
        fun accepts(image: Image): Boolean {
            if (!hardwareVerified || image.width != width || image.height != height) return false
            if (pixelsVerified) return true
            val plane = image.planes.singleOrNull() ?: return false
            if (plane.pixelStride != 2 || plane.rowStride < width*2) return false
            val data = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            var maximum=0
            for (ty in 0..1) for (tx in 0..1) {
                var n=0; var nonzero=0
                for (y in ty*height/2+8 until (ty+1)*height/2-8 step 64)
                    for (x in tx*width/2+8 until (tx+1)*width/2-8 step 64) {
                        val v=data.getShort(y*plane.rowStride+x*2).toInt() and 65535
                        maximum=maxOf(maximum,v)
                        if (v>(if(dcg)16383 else 1023)) throw IOException("Unexpected RAW precision for the verified Redmi profile")
                        n++; if(v!=0)nonzero++
                    }
                check(nonzero >= n/2) { "Empty RAW quadrant; refusing padded sensor data" }
            }
            if(dcg && maximum<=1023)return false
            pixelsVerified=true
            return true
        }
    }
    val spec = Spec(mode)
    private val heartbeat = Executors.newSingleThreadScheduledExecutor()

    init {
        heartbeat.scheduleAtFixedRate({ if(!process.isAlive) {
            spec.hardwareVerified=false
            heartbeat.shutdown()
        } else runCatching {
            val tmp=File(job,"heartbeat.tmp")
            tmp.writeText((SystemClock.elapsedRealtime()/1000).toString())
            Files.move(tmp.toPath(),File(job,"heartbeat").toPath(),StandardCopyOption.REPLACE_EXISTING)
            File(job,"verified.json").takeIf(File::isFile)?.readText()?.let(::JSONObject)?.let {
                spec.hardwareVerified=process.isAlive && it.optBoolean("verified") && it.optString("mode")==mode.argument
            }
        } },0,300,TimeUnit.MILLISECONDS)
    }

    /** True while the worker still holds the sensor mode; a dead worker must be replaced. */
    val alive: Boolean get() = process.isAlive && job.exists()

    @Synchronized override fun close() = close(handoff = false)

    /** [handoff] restores props/libraries but leaves the HAL restart to the next mode's worker. */
    @Synchronized fun close(handoff: Boolean) {
        if(!job.exists())return
        File(job,"release").writeText(if(handoff) "handoff" else "release")
        val exited=process.waitFor(20,TimeUnit.SECONDS)
        heartbeat.shutdownNow()
        spec.hardwareVerified=false
        if(!exited)process.destroyForcibly()
        val report=File(job,"report.json").takeIf(File::isFile)?.readText()?.let(::JSONObject)
        if(report?.optBoolean("restored")!=true) {
            // Stop heartbeats and allow the independent watchdog to restore a killed worker.
            // Only inspect recovery state here; never race another lease with a restore script.
            val recovery=File(job,"recovery.json").takeIf(File::isFile)?.readText()?.let(::JSONObject)
            val previous=recovery?.getString("previousMode")
                ?: throw IOException(report?.optString("error") ?: "Sensor restoration was not verified")
            check(previous.matches(Regex("-?[0-9]*")))
            val previousConfig=recovery!!.optString("previousConfig")
            val previousRuntime=recovery.optString("previousRuntime")
            check(previousConfig.matches(Regex("-?[0-9]*")) && previousRuntime.matches(Regex("-?[0-9]*")))
            val checkCommand="test ! -e /data/adb/rawlens-lapis/lease && " +
                "test \"\$(getprop vendor.debug.cameng.force_sensormode)\" = '$previous' && " +
                "test \"\$(sha256sum /vendor/lib64/mt6878/libmtkcam_metastore.so | cut -d ' ' -f 1)\" = '${LapisRootProfile.VENDOR_SHA256}' && " +
                "test \"\$(sha256sum /vendor/lib64/mt6878/libmtkcam_hal_core_utils.so | cut -d ' ' -f 1)\" = '${LapisRootProfile.CORE_SHA256}' && " +
                "test \"\$(sha256sum /vendor/lib64/mt6878/libcam.sensor_mode_helper.so | cut -d ' ' -f 1)\" = '${LapisRootProfile.SMH_SHA256}' && " +
                "test \"\$(sha256sum /vendor/lib64/mt6878/libcam.sensor_mode_helper_custom.so | cut -d ' ' -f 1)\" = '${LapisRootProfile.SMH_CUSTOM_SHA256}' && " +
                "test \"\$(sha256sum /vendor/lib64/libcam.sensor_mode_helper.so | cut -d ' ' -f 1)\" = '${LapisRootProfile.SMH_SHA256}' && " +
                "test \"\$(sha256sum /vendor/lib64/libcam.sensor_mode_helper_custom.so | cut -d ' ' -f 1)\" = '${LapisRootProfile.SMH_CUSTOM_SHA256}' && " +
                "test \"\$(getprop vendor.debug.vhdr.strategy.config.force)\" = '$previousConfig' && " +
                "test \"\$(getprop vendor.debug.vhdr.strategy.force)\" = '$previousRuntime' && " +
                "test \"\$(getenforce)\" = Enforcing"
            val deadline=SystemClock.elapsedRealtime()+45000
            var restored=false
            do {
                val probe=ProcessBuilder("su","-M","-c",checkCommand).redirectErrorStream(true).redirectOutput(File(job,"recovery-check.log")).start()
                restored=probe.waitFor(3,TimeUnit.SECONDS) && probe.exitValue()==0
                if(!restored) { probe.destroyForcibly();Thread.sleep(500) }
            } while(!restored && SystemClock.elapsedRealtime()<deadline)
            check(restored) { "Sensor recovery not verified; refusing another camera session" }
        }
        job.deleteRecursively()
    }

    companion object {
        /** Recovery after a handoff whose next session never started: restart what the handoff skipped. */
        fun restartCamera() {
            val pending="/data/adb/rawlens-lapis/restart-cameraserver"
            val probe=ProcessBuilder("su","-M","-c","setprop ctl.restart camerahalserver && " +
                "if [ -e $pending ]; then rm -f $pending; setprop ctl.restart cameraserver; fi").redirectErrorStream(true).start()
            if(!probe.waitFor(5,TimeUnit.SECONDS)) probe.destroyForcibly()
        }
        fun awaitMainCamera(context: Context) {
            val manager=context.getSystemService(CameraManager::class.java)
            val deadline=SystemClock.elapsedRealtime()+20000
            do {
                if(runCatching { manager.getCameraCharacteristics("0").get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!=null }.getOrDefault(false))return
                Thread.sleep(200)
            }while(SystemClock.elapsedRealtime()<deadline)
            throw IOException("Main camera did not return after sensor restoration")
        }
        private val bindings=Collections.synchronizedMap(WeakHashMap<CameraCharacteristics,Spec>())
        fun storageWhite(c:CameraCharacteristics):Int? = if(spec(c)?.dcg==true)16383 else c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
        fun storageBlack(c:CameraCharacteristics,index:Int):Int = if(spec(c)?.dcg==true)1024
            else c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.getOffsetForIndex(index%2,index/2) ?: 0
        fun bind(characteristics: CameraCharacteristics,spec: Spec) { bindings[characteristics]=spec }
        fun spec(characteristics: CameraCharacteristics): Spec? = bindings[characteristics]
        fun previewCrop(c: CameraCharacteristics,crop: Rect?,width: Int,height: Int): Rect? {
            val spec=spec(c)?.takeIf { it.metadataVerified && it.width==width && it.height==height } ?: return crop
            val active=c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return Rect(0,0,width,height)
            val source=crop ?: active
            fun x(v:Int)=((v-active.left).toLong()*spec.width/active.width()).toInt().coerceIn(0,width)
            fun y(v:Int)=((v-active.top).toLong()*spec.height/active.height()).toInt().coerceIn(0,height)
            return Rect(x(source.left),y(source.top),x(source.right),y(source.bottom))
        }
        fun normalized(metadata: RawFrameMetadata,c: CameraCharacteristics): RawFrameMetadata {
            val spec=spec(c)?.takeIf { it.metadataVerified && metadata.cameraId=="0" &&
                metadata.imageWidth==it.width && metadata.imageHeight==it.height } ?: return metadata
            if(metadata.rawPlaneCount!=1 || metadata.rawPlanePixelStride!=2 ||
                (metadata.rawPlaneRowStride?:0)<spec.width*2 || metadata.cfaPattern!=BayerPattern.GBRG) return metadata
            if(spec.dcg && ((metadata.whiteLevel?:0f)<=1023f || metadata.blackLevels?.toList()?.all { it>256f }!=true))
                return metadata.copy(rawDevelopmentUnsupportedReason="DCG14 sensor levels are not available yet")
            val full=IntRectSnapshot(0,0,spec.width,spec.height)
            return metadata.copy(imageCrop=full,pixelArraySize=spec.width to spec.height,activeArray=full,preCorrectionActiveArray=full,
                rawCropRegion=full,bufferGeometry=RawBufferGeometry.Supported(0,0,RawCrop(0,0,spec.width,spec.height),"Verified Lapis ${spec.mode.argument}"),
                rawDevelopmentUnsupportedReason=null,quadBayer=false,lensShadingMap=null,hotPixels=emptyList(),noiseProfile=null,
                sensorReadoutProfile=spec.mode.argument)
        }
        fun open(context: Context,mode: LapisRootProfile.Mode): LapisRootSession {
            require(LapisRootProfile.supported() && mode!=LapisRootProfile.Mode.NORMAL)
            val job=File(context.noBackupFilesDir,"lapis-sessions/${UUID.randomUUID()}").apply { check(mkdirs()) }
            File(job,"heartbeat").writeText((SystemClock.elapsedRealtime()/1000).toString())
            val app=context.applicationInfo
            val args=listOf("session-${mode.argument}",job.path,app.sourceDir,app.nativeLibraryDir,
                android.os.Process.myUid().toString(),"400","20000000","1","1,1,1,1","v1","0")
            fun q(s:String)="'"+s.replace("'","'\\''")+"'"
            val command="CLASSPATH=${q(app.sourceDir)} exec app_process /system/bin com.matthew.rawlens.LapisRootCameraWorker "+args.joinToString(" ",transform=::q)
            val process=ProcessBuilder("su","-M","-c",command).redirectErrorStream(true).redirectOutput(File(job,"worker.log")).start()
            val session=LapisRootSession(mode,process,job)
            try {
                val deadline=SystemClock.elapsedRealtime()+40000
                while(!File(job,"ready.json").exists()) {
                    if(!process.isAlive||SystemClock.elapsedRealtime()>deadline)throw IOException(File(job,"worker.log").readText().takeLast(2000))
                    Thread.sleep(100)
                }
                // A provider restart returns before camera IDs are registered again.
                val manager=context.getSystemService(CameraManager::class.java)
                var available=false
                while(SystemClock.elapsedRealtime()<deadline && process.isAlive) {
                    available=runCatching { manager.getCameraCharacteristics("0")
                        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!=null }.getOrDefault(false)
                    if(available)break
                    Thread.sleep(200)
                }
                check(available) { "Main camera did not return after sensor setup" }
                return session
            } catch(failure:Throwable) {
                runCatching { session.close() };throw failure
            }
        }
    }
}
