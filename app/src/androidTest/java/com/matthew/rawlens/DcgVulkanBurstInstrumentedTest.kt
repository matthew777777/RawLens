package com.matthew.rawlens

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

object DcgVulkanProbe {
    init { System.loadLibrary("dcg_probe") }
    external fun init(bootstrap: ByteArray, shader: ByteArray): Int
    external fun merge(low: HardwareBuffer, high: HardwareBuffer, geometry: IntArray,
        levels: FloatArray, output: ByteBuffer, timings: DoubleArray): Int
    external fun close()
}

/** Explicit ADB-only experiment. Does not modify the installed capture pipeline. */
class DcgVulkanBurstInstrumentedTest {
    @Test fun adjacentRawPair() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val highFirst = InstrumentationRegistry.getArguments().getString("highFirst", "true") == "true"
        val context = instrumentation.targetContext
        val manager = context.getSystemService(CameraManager::class.java)
        val c = manager.getCameraCharacteristics("0")
        val size = Size(4080,3060)
        val config = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
        require(config.getOutputSizes(ImageFormat.RAW_SENSOR).contains(size))
        val minDuration = config.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR,size)
        val outDir = File(context.getExternalFilesDir(null),"dcg-vulkan").apply { mkdirs() }
        val report = StringBuilder()
        fun log(s: String) { report.appendLine(s); Log.i("DCGVULKAN",s) }
        val thread = HandlerThread("dcg-vulkan-capture").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(size.width,size.height,ImageFormat.RAW_SENSOR,6)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        val images = mutableMapOf<Long,Image>()
        val results = mutableMapOf<Long,TotalCaptureResult>()
        val pairs = List(4) { CompletableFuture<Pair<Image,TotalCaptureResult>>() }
        val held = mutableListOf<Image>()
        val opened = CompletableFuture<CameraDevice>()
        val configured = CompletableFuture<CameraCaptureSession>()
        try {
            val status = DcgVulkanProbe.init(context.assets.open("shaders/vf/vf_superpixel.spv").use { it.readBytes() },
                instrumentation.context.assets.open("dcg/merge.spv").use { it.readBytes() })
            check(status==0) { "Vulkan init failed $status" }
            log("VULKAN initialized; RAW 4080x3060 minFrameDurationNs=$minDuration highFirst=$highFirst")
            manager.openCamera("0",object:CameraDevice.StateCallback() {
                override fun onOpened(d:CameraDevice) { if(!opened.complete(d)) d.close() }
                override fun onDisconnected(d:CameraDevice) { d.close(); opened.completeExceptionally(Exception("disconnected")) }
                override fun onError(d:CameraDevice,error:Int) { d.close(); opened.completeExceptionally(Exception("camera error $error")); pairs.forEach { it.completeExceptionally(Exception("camera error $error")) } }
            },handler)
            device=opened.get(10,TimeUnit.SECONDS)
            device.createCaptureSession(listOf(reader.surface),object:CameraCaptureSession.StateCallback() {
                override fun onConfigured(s:CameraCaptureSession) { if(!configured.complete(s)) s.close() }
                override fun onConfigureFailed(s:CameraCaptureSession) { s.close(); configured.completeExceptionally(Exception("session failed")) }
            },handler)
            session=configured.get(10,TimeUnit.SECONDS)
            fun deliver(timestamp:Long) {
                val image=images[timestamp] ?: return
                val result=results[timestamp] ?: return
                images.remove(timestamp); results.remove(timestamp)
                val index=result.request.tag as Int
                if(index<8) image.close() else {
                    held.add(image)
                    pairs[index-8].complete(image to result)
                }
            }
            reader.setOnImageAvailableListener({ source ->
                var image=source.acquireNextImage()
                while(image!=null) {
                    images[image.timestamp]=image; deliver(image.timestamp)
                    image=source.acquireNextImage()
                }
            },handler)
            val lowExposure=33_333_333L
            val highExposure=4_166_667L
            val requests=List(12) { index ->
                val high=index>=8 && (index%2==0)==highFirst
                device.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL).apply {
                    addTarget(reader.surface); setTag(index)
                    set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_SENSITIVITY,if(high) 800 else 100)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME,if(high) highExposure else lowExposure)
                    set(CaptureRequest.SENSOR_FRAME_DURATION,max(minDuration,lowExposure))
                    set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AWB_MODE,CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_CAPTURE_INTENT,CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW)
                    set(CaptureRequest.NOISE_REDUCTION_MODE,CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                    set(CaptureRequest.EDGE_MODE,CaptureRequest.EDGE_MODE_OFF)
                }.build()
            }
            // One submission, one session, no disk I/O or per-frame processing in callbacks.
            session.captureBurst(requests,object:CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s:CameraCaptureSession,q:CaptureRequest,r:TotalCaptureResult) {
                    val timestamp=r[CaptureResult.SENSOR_TIMESTAMP]!!
                    results[timestamp]=r; deliver(timestamp)
                }
                override fun onCaptureFailed(s:CameraCaptureSession,q:CaptureRequest,f:CaptureFailure) {
                    val index=q.tag as Int
                    if(index>=8) pairs[index-8].completeExceptionally(Exception("capture $index failed ${f.reason}"))
                }
            },handler)
            val captured=pairs.map { it.get(20,TimeUnit.SECONDS) }
            for((index,item) in captured.withIndex()) {
                val (image,r)=item
                image.fence.use {
                    log("FENCE frame=$index valid=${it.isValid} signalTime=${it.signalTime}")
                    // No fence means no pending producer work. Some vendor builds
                    // return false from await() on this invalid-fence sentinel.
                    if(it.isValid) check(it.await(Duration.ofSeconds(2))) { "Camera producer fence timeout" }
                }
                log("FRAME $index timestamp=${image.timestamp} resultTimestamp=${r[CaptureResult.SENSOR_TIMESTAMP]} iso=${r[CaptureResult.SENSOR_SENSITIVITY]} exposure=${r[CaptureResult.SENSOR_EXPOSURE_TIME]} duration=${r[CaptureResult.SENSOR_FRAME_DURATION]} skew=${r[CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW]} focus=${r[CaptureResult.LENS_FOCUS_DISTANCE]} af=${r[CaptureResult.CONTROL_AF_STATE]}")
                check(image.timestamp==r[CaptureResult.SENSOR_TIMESTAMP])
                val high=(index%2==0)==highFirst
                check(r[CaptureResult.SENSOR_SENSITIVITY]==if(high) 800 else 100)
                val requested=if(high) highExposure else lowExposure
                check(abs(r[CaptureResult.SENSOR_EXPOSURE_TIME]!!-requested)<requested/20)
            }
            val output=ByteBuffer.allocateDirect(size.width*size.height*2).order(ByteOrder.LITTLE_ENDIAN)
            for(pairIndex in 0..1) {
                val lowIndex=pairIndex*2+if(highFirst) 1 else 0
                val highIndex=pairIndex*2+if(highFirst) 0 else 1
                val (low,lr)=captured[lowIndex]
                val (high,hr)=captured[highIndex]
                val lp=low.planes[0]; val hp=high.planes[0]
                require(lp.pixelStride==2 && hp.pixelStride==2)
                val blackPattern=c[CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN]!!
                val lb=lr[CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL] ?: FloatArray(4) { blackPattern.getOffsetForIndex(it%2,it/2).toFloat() }
                val hb=hr[CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL] ?: lb
                val lw=(lr[CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL] ?: c[CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL]!!).toFloat()
                val hw=(hr[CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL] ?: c[CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL]!!).toFloat()
                val le=lr[CaptureResult.SENSOR_EXPOSURE_TIME]!!; val he=hr[CaptureResult.SENSOR_EXPOSURE_TIME]!!
                val scale=(le.toDouble()*lr[CaptureResult.SENSOR_SENSITIVITY]!!/(he.toDouble()*hr[CaptureResult.SENSOR_SENSITIVITY]!!)).toFloat()
                // Simple photon-count weight; no alignment or motion/robustness masks.
                val ratio=le.toFloat()/he
                val params=lb+hb+floatArrayOf(lw,hw,scale,ratio)
                val timings=DoubleArray(3)
                low.hardwareBuffer!!.use { la -> high.hardwareBuffer!!.use { ha ->
                    val code=DcgVulkanProbe.merge(la,ha,intArrayOf(size.width,size.height,lp.rowStride/2,hp.rowStride/2),params,output,timings)
                    check(code==0) { "Vulkan merge failed code=$code" }
                } }
                log("PAIR $pairIndex gapMs=${abs(high.timestamp-low.timestamp)/1e6} exposureMidpointGapMs=${abs(high.timestamp+he/2-low.timestamp-le/2)/1e6} importSetupMs=${timings[0]} submitFenceMs=${timings[1]} outputReadbackMs=${timings[2]} lowWeightRatio=$ratio")
                // CPU validation happens AFTER GPU completion, never before the input import.
                val a=lp.buffer.order(ByteOrder.LITTLE_ENDIAN); val b=hp.buffer.order(ByteOrder.LITTLE_ENDIAN)
                var maxError=0
                for(y in 0 until size.height step 7) for(x in 0 until size.width step 7) {
                    val site=(y%2)*2+x%2
                    val va=((a.getShort(y*lp.rowStride+x*2).toInt() and 65535)-lb[site])/(lw-lb[site])
                    val vb=((b.getShort(y*hp.rowStride+x*2).toInt() and 65535)-hb[site])/(hw-hb[site])
                    val wa=ratio*((.99f-va)/.09f).coerceIn(0f,1f); val wb=((.99f-vb)/.09f).coerceIn(0f,1f)
                    val expected=(((if(wa+wb>0) (wa*va+wb*vb*scale)/(wa+wb) else va)*64511f)+1024f).roundToInt().coerceIn(0,65535)
                    val actual=output.getShort((y*size.width+x)*2).toInt() and 65535
                    maxError=max(maxError,abs(expected-actual))
                }
                log("VALIDATION pair=$pairIndex sampledMaxError16bit=$maxError")
                check(maxError<=1) { "GPU/CPU mismatch: $maxError" }
                val file=File(outDir,"RawLens_Vulkan_adjacent_pair$pairIndex.dng")
                DngCreator(c,lr).use { creator ->
                    creator.setOrientation(1)
                    creator.setDescription("RawLens Vulkan sequential dual-gain Bayer merge, camera 0; no alignment; reference exposure metadata")
                    output.position(0)
                    file.outputStream().use { creator.writeByteBuffer(it,size,output,0) }
                }
                patchMergedLevels(file)
                log("SAVED ${file.name} bytes=${file.length()}")
                if(pairIndex==0) {
                    for((name,item) in listOf("low" to captured[lowIndex],"high" to captured[highIndex])) {
                        DngCreator(c,item.second).use { creator ->
                            creator.setOrientation(1)
                            File(outDir,"source-$name.dng").outputStream().use { creator.writeImage(it,item.first) }
                        }
                    }
                }
            }
        } finally {
            opened.cancel(false); configured.cancel(false)
            session?.close(); device?.close()
            val cleaned=CompletableFuture<Unit>()
            handler.post { held.forEach { it.close() }; images.values.forEach { it.close() }; reader.close(); cleaned.complete(Unit) }
            cleaned.get(5,TimeUnit.SECONDS)
            DcgVulkanProbe.close()
            thread.quitSafely()
            File(outDir,"report.txt").writeText(report.toString())
        }
    }

    /** Keep Camera2 calibration/crop/opcodes, update levels and drop stale source noise. */
    private fun patchMergedLevels(file:File) {
        RandomAccessFile(file,"rw").use { f ->
            val header=ByteArray(8); f.readFully(header)
            val order=if(header[0]=='I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
            val h=ByteBuffer.wrap(header).order(order)
            check(h.getShort(2).toInt()==42)
            val ifd=h.getInt(4).toLong() and 0xffffffffL
            f.seek(ifd); val countBytes=ByteArray(2); f.readFully(countBytes)
            val count=ByteBuffer.wrap(countBytes).order(order).short.toInt() and 65535
            val entries=ByteArray(count*12); f.readFully(entries)
            val next=ByteArray(4); f.readFully(next)
            val kept=mutableListOf<ByteArray>(); var blackFound=false; var whiteFound=false
            for(i in 0 until count) {
                val entry=entries.copyOfRange(i*12,(i+1)*12); val b=ByteBuffer.wrap(entry).order(order)
                when(b.getShort(0).toInt() and 65535) {
                    50714 -> {
                        check(b.getShort(2).toInt()==5 && b.getInt(4)==4)
                        val offset=b.getInt(8).toLong() and 0xffffffffL
                        f.seek(offset); val black=ByteBuffer.allocate(32).order(order)
                        repeat(4) { black.putInt(1024).putInt(1) }; f.write(black.array()); blackFound=true
                    }
                    50717 -> { check(b.getShort(2).toInt()==4 && b.getInt(4)==1); b.putInt(8,65535); whiteFound=true }
                    51041 -> continue // NoiseProfile belongs to the unmerged reference.
                }
                kept.add(entry)
            }
            check(blackFound && whiteFound)
            f.seek(ifd); f.write(ByteBuffer.allocate(2).order(order).putShort(kept.size.toShort()).array())
            kept.forEach { f.write(it) }; f.write(next)
        }
    }
}
