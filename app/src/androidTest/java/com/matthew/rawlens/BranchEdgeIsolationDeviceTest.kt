// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue

/** Optional external fixture. Independent per-pixel CPU reference excludes all spatial processing. */
class BranchEdgeIsolationDeviceTest {
    @Test fun matchedExposureCpuAndGpuWithNoSpatialCleanup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.getExternalFilesDir(null),"branch-isolation").apply { mkdirs() }
        val file=File(root,"source.dng")
        assumeTrue("External branch DNG not installed",file.isFile)
        val image=DngImportReader.read(file)
        val metadata=image.frameMetadata(ExifInterface(file))
        val ev=InstrumentationRegistry.getArguments().getString("branchEv")?.toDouble() ?: 0.0
        val transform=SceneLinearColorProcessor.resolve(SceneLinearColorMetadata.from(metadata),ev)
        val matrix=transform.cameraToAcescg.toDoubleArray()
        val white=transform.cameraWhiteNormalized.toDoubleArray()
        val x0=1200;val y0=500;val w=800;val h=650
        val metrics=StringBuilder()
        val developer=RawDevelopmentCoordinator(context)
        try {
            for (variant in listOf("forward")) {
            val renderMetadata=if(variant=="color") metadata.copy(forwardMatrix1=null,forwardMatrix2=null) else metadata
            for(mapper in PhotoToneMapper.entries.filter { it==PhotoToneMapper.SIGMOID }) {
                val clean=JpegOutputSettings(toneMapper=mapper,chromaCleanup=ChromaCleanup(false,0f))
                val settings=RawDevelopmentSettings(exposureEv=ev,adaptiveExposureStrength=0f)
                val reference=IntArray(w*h)
                val camera=FloatArray(3);val aces=FloatArray(3)
                fun smooth(v: Float): Float { val t=((v-.70f)/.29f).coerceIn(0f,1f);return t*t*(3-2*t) }
                for(y in 0 until h) for(x in 0 until w) {
                    for(c in 0..2) camera[c]=image.rgb.getFloat((((y+y0)*image.width+x+x0)*3+c)*4)
                    val blend=camera.maxOf(::smooth)
                    for(c in 0..2) camera[c]=(camera[c]*(1-blend)+white[c]*blend).toFloat()
                    for(r in 0..2) aces[r]=(0..2).sumOf { matrix[r*3+it]*camera[it] }.toFloat()
                    val rgb=AgxDisplayTransform.acescgToOutputLinearSrgb(aces,clean)
                    fun code(c: Int)=(AgxDisplayTransform.srgbOetf(rgb[c].coerceIn(0f,1f))*255+.5f).toInt().coerceIn(0,255)
                    reference[y*w+x]=Color.rgb(code(0),code(1),code(2))
                }
                Bitmap.createBitmap(reference,w,h,Bitmap.Config.ARGB_8888).let { cpu ->
                    File(root,"${mapper.name}-CPU-pointwise.png").outputStream().use { cpu.compress(Bitmap.CompressFormat.PNG,100,it) };cpu.recycle()
                }
                for(enabled in listOf(false,true)) {
                    image.rgb.rewind()
                    val look=clean.copy(chromaCleanup=if(enabled) ChromaCleanup() else ChromaCleanup(false,0f))
                    val result=developer.developMfsrJpeg(image.rgb,image.width,image.height,renderMetadata,settings,look)
                    try {
                        if(mapper==PhotoToneMapper.SIGMOID && variant=="forward" && !enabled) File(root,"sigmoid-full.png").outputStream().use { result.bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                        val pixels=IntArray(w*h);result.bitmap.getPixels(pixels,0,w,x0,y0,w,h)
                        val crop=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
                        File(root,"${variant}-${mapper.name}-GPU-cleanup-${if(enabled) "on" else "off"}.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG,100,it) };crop.recycle()
                        if(!enabled && variant=="forward") {
                            var error=0L;var maxError=0
                            for(i in pixels.indices) for(shift in intArrayOf(0,8,16)) {
                                val delta=kotlin.math.abs(((pixels[i] shr shift) and 255)-((reference[i] shr shift) and 255))
                                error+=delta;maxError=maxOf(maxError,delta)
                            }
                            val mean=error.toDouble()/(pixels.size*3)
                            metrics.append("$mapper meanCpuGpuCodeError=$mean max=$maxError\n")
                            assertTrue("$mapper CPU/GPU mean difference $mean",mean<1.5)
                        }
                    } finally { result.bitmap.recycle();result.gainmap?.recycle() }
                }
            }
            }
            File(root,"metrics.txt").writeText(metrics.toString())
        } finally { developer.close() }
    }
}
