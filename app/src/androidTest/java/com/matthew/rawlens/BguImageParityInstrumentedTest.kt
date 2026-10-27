// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Same RAW, same quad values and look parameters: direct operator vs fitted BGU. */
class BguImageParityInstrumentedTest {
    @Test fun capturePairThroughCurrentApp() {
        val inst = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Pass -e rawlensSavedPair true", InstrumentationRegistry.getArguments().getString("rawlensSavedPair") == "true")
        val ctx = inst.targetContext
        val activity = inst.startActivitySync(android.content.Intent(ctx, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val field = MainActivity::class.java.getDeclaredField("controller").apply { isAccessible = true }
        val controller = field.get(activity) as RawCameraController
        val output = File(ctx.getExternalFilesDir(null), "saved-pair").apply { mkdirs() }
        try {
            assertTrue(controller.setCaptureFormat(CaptureFormat.JPEG_DNG))
            android.os.SystemClock.sleep(8000)
            val preview = inst.uiAutomation.takeScreenshot()
            File(output, "preview.png").outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
            preview.recycle()
            android.util.Log.i("RawLensSavedPair", "preview=${activity.findViewById<RawViewfinder>(R.id.rawViewfinder).snapshot()}")
            controller.capture()
            val pendingField = RawCameraController::class.java.getDeclaredField("pendingSaveCount").apply { isAccessible = true }
            val pending = pendingField.get(controller) as java.util.concurrent.atomic.AtomicInteger
            android.os.SystemClock.sleep(3000)
            val deadline = android.os.SystemClock.elapsedRealtime() + 60000
            while (pending.get() > 0 && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(100)
            assertEquals("save did not complete", 0, pending.get())
        } finally { inst.runOnMainSync { activity.finish() } }
    }

    @Test fun compareRealRawToDirectOperator() {
        val inst = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Pass -e rawlensA5Image true", InstrumentationRegistry.getArguments()
            .getString("rawlensA5Image") == "true")
        val ctx = inst.targetContext
        val manager = ctx.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.first { id ->
            val c = manager.getCameraCharacteristics(id)
            c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK &&
                c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]!!.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        }
        val c = manager.getCameraCharacteristics(id)
        val size = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
            .getOutputSizes(ImageFormat.RAW_SENSOR)!!.minBy { it.width.toLong()*it.height }
        val thread = HandlerThread("BguImageA5").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(size.width,size.height,ImageFormat.RAW_SENSOR,2)
        val output = File(ctx.getExternalFilesDir(null),"a5-image").apply { mkdirs() }
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var view: BguViewfinder? = null
        try {
            val opened = CompletableFuture<CameraDevice>()
            manager.openCamera(id,object:CameraDevice.StateCallback() {
                override fun onOpened(d:CameraDevice) { opened.complete(d) }
                override fun onDisconnected(d:CameraDevice) { d.close();opened.completeExceptionally(IllegalStateException("disconnected")) }
                override fun onError(d:CameraDevice,error:Int) { d.close();opened.completeExceptionally(IllegalStateException("camera $error")) }
            },handler)
            camera=opened.get(20,TimeUnit.SECONDS)
            val configured=CompletableFuture<CameraCaptureSession>()
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(reader.surface),object:CameraCaptureSession.StateCallback() {
                override fun onConfigured(s:CameraCaptureSession) { configured.complete(s) }
                override fun onConfigureFailed(s:CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("session rejected")) }
            },handler)
            session=configured.get(20,TimeUnit.SECONDS)
            inst.runOnMainSync { view=BguViewfinder(ctx) }
            val engine=view!!
            val prefs = ctx.getSharedPreferences("rawlens_settings", Context.MODE_PRIVATE)
            val captureLook = JpegOutputSettings(
                agxContrast = prefs.getFloat("jpeg_agx_contrast", 1f),
                agxSaturation = prefs.getFloat("jpeg_agx_saturation", 1f),
                agxPurityBoost = prefs.getFloat("jpeg_agx_purity", 1f),
                agxShadowEv = prefs.getFloat("jpeg_agx_shadow_ev", 10f),
                agxHighlightEv = prefs.getFloat("jpeg_agx_highlight_ev", 6.5f),
                agxHuePreservation = prefs.getFloat("jpeg_agx_hue", 0f),
                agxGamutCompression = prefs.getFloat("jpeg_agx_gamut", 0f)
            )
            engine.setAgx(captureLook)

            val snapshot=engine.javaClass.declaredMethods.first { it.name=="snapshotParams" }.apply { isAccessible=true }
            val report=StringBuilder("camera=$id raw=$size\n")
            File(ctx.applicationInfo.dataDir, "shared_prefs").listFiles()?.filter { it.extension == "xml" }?.forEach { file ->
                ctx.getSharedPreferences(file.nameWithoutExtension, Context.MODE_PRIVATE).all
                    .filterKeys { key -> listOf("agx", "adaptive", "preview", "exposure_mode", "vf_engine", "demosaic").any { key.contains(it, ignoreCase = true) } }
                    .forEach { (key, value) -> report.appendLine("PREF ${file.nameWithoutExtension} $key=$value") }
            }

            repeat(8) { frame ->
                val imageFuture=CompletableFuture<Image>()
                val resultFuture=CompletableFuture<TotalCaptureResult>()
                reader.setOnImageAvailableListener({ src -> src.acquireNextImage()?.let {
                    if(!imageFuture.complete(it)) it.close()
                } },handler)
                val request=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                }.build()
                session.capture(request,object:CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s:CameraCaptureSession,q:CaptureRequest,r:TotalCaptureResult) { resultFuture.complete(r) }
                },handler)
                val result=resultFuture.get(20,TimeUnit.SECONDS)
                imageFuture.get(20,TimeUnit.SECONDS).use { image ->
                    if(frame!=7) return@use
                    val plane=image.planes[0]
                    val geo=BguGeometry.guideGeometry(image.width,image.height,null,1080)
                    val cfa=c[CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT]!!
                    val channels=BguGeometry.cfaSites(cfa)
                    val black=result[CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL] ?:
                        FloatArray(4) { c[CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN]!!.getOffsetForIndex(it%2,it/2).toFloat() }
                    val white=(result[CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL] ?: c[CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL]!!).toFloat()
                    val inv=FloatArray(4) { 1f/(white-black[it]) }
                    val low=BguGeometry.lowDims(geo.width,geo.height)
                    fun sample(w:Int,h:Int,step:Int):ByteArray = ByteArray(w*h*4).also {
                        assertTrue(BguSample.sampleLowFrame(plane.buffer,plane.rowStride,plane.pixelStride,
                            geo.left,geo.top,step,w,h,channels,black,inv,it))
                    }
                    val lowQuad=sample(low[0],low[1],geo.step*8)
                    val fullQuad=sample(geo.width,geo.height,geo.step)
                    for(jpeg in listOf(false,true)) {
                        engine.setRenderJpeg(jpeg)
                        var params:Any?=null
                        repeat(40) { params=snapshot.invoke(engine,c,result,plane.buffer,plane.rowStride,
                            plane.pixelStride,image.width,image.height,black,geo,white) }
                        val p=params!!
                        fun field(name:String):Any = p.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(p)
                        val lens=field("lens") as FloatArray
                        val cols=field("lensCols") as Int
                        val rows=field("lensRows") as Int
                        val wb=field("wb") as FloatArray
                        val ccm=field("ccm") as FloatArray
                        val aces=field("aces") as FloatArray
                        val camWhite=field("white") as FloatArray
                        val fp=field("fparams") as FloatArray
                        val ip=field("iparams") as IntArray
                        val pair=BguLook.run(lowQuad,low[0],low[1],lens,cols,rows,wb,ccm,aces,camWhite,fp,ip)!!
                        val fullIp=ip.copyOf().apply { this[BguLook.IP_LOW_STEP]=geo.step }
                        val direct=BguLook.run(fullQuad,geo.width,geo.height,lens,cols,rows,wb,ccm,aces,camWhite,fp,fullIp)!!
                        if (jpeg) {
                            val metadata = RawFrameMetadataFactory.capture(id, image, c, result, 1)
                            val look = captureLook
                            val tuning = AdaptiveExposureTuning.fromOutputSettings(look)
                            val developer = RawDevelopmentCoordinator(ctx)
                            File(output, "capture.dng").outputStream().use { stream ->
                                DngCreator(c, result).use { it.writeImage(stream, image) }
                            }
                            val saved = developer.developJpeg(plane.buffer, metadata,
                                RawDevelopmentSettings(adaptiveExposureStrength = 1f, adaptiveExposureTuning = tuning), look)
                            try {
                                File(output, "capture.jpg").outputStream().use {
                                    saved.bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
                                }
                                report.appendLine("SAVE previewEV=${fp[0]} size=${saved.bitmap.width}x${saved.bitmap.height}")
                                val geometry = metadata.bufferGeometry as RawBufferGeometry.Supported
                                val analysis = AdaptiveDevelopmentExposure.analyzeRaw(plane.buffer,
                                    RawPlaneLayout(image.width, image.height, plane.rowStride, plane.pixelStride,
                                        geometry.sensorOriginX, geometry.sensorOriginY),
                                    metadata.normalizationOrNull()!!, geometry.processingCrop,
                                    RawPreDemosaicPipeline.lensShadingModel(metadata), tuning = tuning)
                                report.appendLine("SAVE meter=$analysis crop=${geometry.processingCrop} preview=$geo")
                            } finally { saved.bitmap.recycle(); saved.gainmap?.recycle(); developer.close() }
                        }
                        val pixels=geo.width*geo.height
                        val guide=ByteBuffer.allocateDirect(pixels*4)
                        for(i in 0 until pixels) for(ch in 0..3) guide.put(fullQuad[ch*pixels+i])
                        guide.rewind()
                        val name=if(jpeg) "JPEG" else "RAW"
                        // Frozen operator pairs let host probes compare reference
                        // coordinate mappings on exactly the same RAW samples.
                        val frozen = ByteBuffer.allocate(16+4*(pair.guide.size+pair.developed.size+
                            direct.guide.size+direct.developed.size)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        frozen.putInt(low[0]).putInt(low[1]).putInt(geo.width).putInt(geo.height)
                        for(array in listOf(pair.guide,pair.developed,direct.guide,direct.developed))
                            for(value in array) frozen.putFloat(value)
                        File(output,"$name-pairs.bin").writeBytes(frozen.array())
                        fun bitmap(name:String,colors:IntArray) {
                            val bm=Bitmap.createBitmap(colors,geo.width,geo.height,Bitmap.Config.ARGB_8888)
                            File(output,name+".png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG,100,it) }
                            bm.recycle()
                        }
                        bitmap(name+"-direct",IntArray(pixels) { i ->
                            var argb=0xff000000.toInt()
                            for(ch in 0..2) argb=argb or (((direct.developed[ch*pixels+i]*255+0.5f).toInt().coerceIn(0,255)) shl (16-ch*8))
                            argb
                        })
                        for(bins in listOf(8,16,32)) {
                            val r=1f/bins
                            val grid=BguFit.fit(pair.guide,pair.developed,low[0],low[1],r=r)!!
                            val actual=ByteBuffer.allocateDirect(pixels*4)
                            assertEquals(0,BguSliceCpu.slice(guide,geo.width,geo.height,grid.coeffs,grid.gw,grid.gh,grid.gz,
                                low[0]/16f,low[1]/16f,bins.toFloat(),false,actual))
                            var sum=0.0;var shadow=0.0;var shadowN=0;var max=0.0
                            val colors=IntArray(pixels)
                            for(i in 0 until pixels) {
                                var argb=0xff000000.toInt()
                                for(ch in 0..2) {
                                    val got=(actual.get(i*4+if(ch==2)3 else ch).toInt() and 255)/255.0
                                    val wanted=direct.developed[ch*pixels+i].toDouble()
                                    val error=got-wanted
                                    sum+=kotlin.math.abs(error);max=maxOf(max,kotlin.math.abs(error))
                                    if(wanted<0.25) { shadow+=error;shadowN++ }
                                    argb=argb or ((got*255+0.5).toInt() shl (16-ch*8))
                                }
                                colors[i]=argb
                            }
                            val line="$name bins=$bins meanAbs=${sum/(pixels*3)} max=$max darkBias=${shadow/shadowN.coerceAtLeast(1)} darkN=$shadowN"
                            report.appendLine(line);android.util.Log.i("RawLensBguImage",line)
                            bitmap("$name-bgu-$bins",colors)
                        }
                    }
                }
            }
            File(output,"report.txt").writeText(report.toString())
        } finally {
            session?.close();camera?.close();reader.close()
            inst.runOnMainSync { view?.dispose() }
            thread.quitSafely();thread.join(3000)
        }
    }
}
