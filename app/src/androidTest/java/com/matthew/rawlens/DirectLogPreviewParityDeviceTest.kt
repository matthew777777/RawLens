// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Full-image A/B against the external pre-optimization SPIR-V, not a second implementation. */
class DirectLogPreviewParityDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun asset(name: String) = context.assets.open("shaders/vf/$name.spv").use { it.readBytes() }

    @Test fun packedYLoadsPreserveEveryMonitorPixel() {
        val path = InstrumentationRegistry.getArguments().getString("previewReferenceShader")
        assumeTrue("Supply pre-change shader with -e previewReferenceShader",path != null)
        val reference = File(requireNotNull(path)).readBytes()
        val current = asset("vf_p010preview")
        var compared = 0
        for (box in listOf(2,4,8,128,256)) for (pattern in 0..2) {
            val w=512; val h=256
            val input = HardwareBuffer.create(w,h,HardwareBuffer.RGBA_FP16,1,
                HardwareBuffer.USAGE_CPU_WRITE_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
            val p010 = requireNotNull(VfEglImport.createP010(w,h))
            val output = HardwareBufferRef.createRgba8(w/box,h/box)
            try {
                assertTrue(VfEglImport.fillHalfGradient(input,w,h,pattern)>0)
                fun render(shader: ByteArray): IntArray {
                    assertEquals(0,VfVulkan.initNative(asset("vf_superpixel")))
                    try {
                        assertEquals(0,VfLogGrade.initGradeNative(asset("vf_loggrade")))
                        assertEquals(0,VfLogGrade.initGradeYuvNative(asset("vf_gradeyuv")))
                        assertEquals(0,VfLogGrade.gradeLutUploadNative(VfLogGrade.bakeLut(1f)))
                        assertEquals(0,VfRecordPreview.initPreviewNative(shader))
                        val layout = requireNotNull(VfEglImport.describeP010(p010))
                        val strides = intArrayOf(layout[0],layout[1],w,h)
                        val (dims,grade) = VfLogGrade.packGrade(intArrayOf(w,h),floatArrayOf(1f,1f,1f,1f),
                            VfLogGrade.IDENTITY_CCM,0f,false,profile=DirectLogProfile.BT709)
                        assertEquals(0,VfLogGrade.gradeYuvBlockingNative(input,p010,dims,dims,grade,strides,0,1))
                        assertEquals(0,VfRecordPreview.previewBlockingNative(p010,output.buffer,
                            intArrayOf(w/box,h/box),dims,strides,box,0))
                        val hardware = requireNotNull(Bitmap.wrapHardwareBuffer(output.buffer,ColorSpace.get(ColorSpace.Named.SRGB)))
                        val copy = requireNotNull(hardware.copy(Bitmap.Config.ARGB_8888,false))
                        return try {
                            IntArray(w/box*(h/box)).also { copy.getPixels(it,0,w/box,0,0,w/box,h/box) }
                        } finally { copy.recycle(); hardware.recycle() }
                    } finally { VfVulkan.resetNative() }
                }
                val old = render(reference)
                val optimized = render(current)
                assertArrayEquals("box=$box pattern=$pattern",old,optimized)
                compared += old.size
            } finally { output.close();p010.close();input.close() }
        }
        File(context.getExternalFilesDir(null),"preview-parity.txt")
            .writeText("Compared $compared RGBA8 pixels across 15 cases; all bit-identical\n")
    }
}
