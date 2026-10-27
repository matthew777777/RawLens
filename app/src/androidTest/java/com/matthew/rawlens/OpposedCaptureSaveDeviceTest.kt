// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Real sensor -> production RAW developer -> full-size JPEG + fsync, no gallery entry. */
class OpposedCaptureSaveDeviceTest {
    @SuppressLint("MissingPermission")
    @Test fun fullResolutionCaptureToDurableJpeg() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA").close()
        val manager=context.getSystemService(android.content.Context.CAMERA_SERVICE) as CameraManager
        val id=manager.cameraIdList.first { key ->
            val c=manager.getCameraCharacteristics(key)
            c[CameraCharacteristics.LENS_FACING]==CameraCharacteristics.LENS_FACING_BACK &&
                c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]!!.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        }
        val characteristics=manager.getCameraCharacteristics(id)
        val size=characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
            .getOutputSizes(ImageFormat.RAW_SENSOR)!!.minBy { kotlin.math.abs(it.width.toLong()*it.height-12_000_000L) }
        val reader=ImageReader.newInstance(size.width,size.height,ImageFormat.RAW_SENSOR,2)
        val thread=HandlerThread("opposed-save-benchmark").apply { start() }
        val handler=Handler(thread.looper)
        var device: CameraDevice?=null
        var session: CameraCaptureSession?=null
        val developer=RawDevelopmentCoordinator(context)
        val file=File(context.cacheDir,"opposed-full-resolution-save.jpg")
        try {
            developer.prewarmHighlights()
            val opened=CompletableFuture<CameraDevice>()
            manager.openCamera(id,object:CameraDevice.StateCallback() {
                override fun onOpened(d:CameraDevice) { if(!opened.complete(d)) d.close() }
                override fun onDisconnected(d:CameraDevice) { d.close(); opened.completeExceptionally(IllegalStateException("camera disconnected")) }
                override fun onError(d:CameraDevice,error:Int) { d.close(); opened.completeExceptionally(IllegalStateException("camera error $error")) }
            },handler)
            val camera=opened.get(20,TimeUnit.SECONDS); device=camera
            val configured=CompletableFuture<CameraCaptureSession>()
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(reader.surface),object:CameraCaptureSession.StateCallback() {
                override fun onConfigured(s:CameraCaptureSession) { if(!configured.complete(s)) s.close() }
                override fun onConfigureFailed(s:CameraCaptureSession) { s.close(); configured.completeExceptionally(IllegalStateException("RAW-only session rejected")) }
            },handler)
            val active=configured.get(20,TimeUnit.SECONDS); session=active
            val imageReady=CompletableFuture<Image>(); val resultReady=CompletableFuture<TotalCaptureResult>()
            reader.setOnImageAvailableListener({ source -> source.acquireNextImage()?.let { if(!imageReady.complete(it)) it.close() } },handler)
            val request=camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            }.build()
            val start=SystemClock.elapsedRealtimeNanos()
            active.capture(request,object:CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s:CameraCaptureSession,q:CaptureRequest,r:TotalCaptureResult) { resultReady.complete(r) }
                override fun onCaptureFailed(s:CameraCaptureSession,q:CaptureRequest,f:CaptureFailure) { resultReady.completeExceptionally(IllegalStateException("capture failed ${f.reason}")) }
            },handler)
            val result=resultReady.get(20,TimeUnit.SECONDS)
            imageReady.get(20,TimeUnit.SECONDS).use { image ->
                val rawReady=SystemClock.elapsedRealtimeNanos()
                assertEquals(result[CaptureResult.SENSOR_TIMESTAMP],image.timestamp)
                val metadata=RawFrameMetadataFactory.capture(id,image,characteristics,result,1)
                val developed=developer.developJpeg(image.planes[0].buffer,metadata,
                    RawDevelopmentSettings(),JpegOutputSettings(hlReconstruction=true))
                try {
                    val developedAt=SystemClock.elapsedRealtimeNanos()
                    FileOutputStream(file).use { out ->
                        assertTrue(developed.bitmap.compress(Bitmap.CompressFormat.JPEG,developed.settings.jpegQuality,out))
                        out.fd.sync()
                    }
                    val savedAt=SystemClock.elapsedRealtimeNanos()
                    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                    BitmapFactory.decodeFile(file.absolutePath,bounds)
                    assertEquals(developed.bitmap.width,bounds.outWidth)
                    assertEquals(developed.bitmap.height,bounds.outHeight)
                    assertTrue(file.length()>1000)
                    Log.i("OpposedParity","CAPTURE_SAVE raw=${image.width}x${image.height} jpeg=${bounds.outWidth}x${bounds.outHeight} cameraMs=${(rawReady-start)/1e6} developMs=${(developedAt-rawReady)/1e6} encodeSyncMs=${(savedAt-developedAt)/1e6} totalMs=${(savedAt-start)/1e6} bytes=${file.length()}")
                } finally { developed.bitmap.recycle(); developed.gainmap?.recycle() }
            }
        } finally {
            session?.close(); device?.close(); reader.close()
            developer.close(); thread.quitSafely(); file.delete()
        }
    }
}
