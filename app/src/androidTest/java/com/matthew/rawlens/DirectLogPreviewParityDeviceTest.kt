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

    @Test fun kernelFenceWaitPreservesReadinessAndOwnership() {
        assumeTrue(VfEglImport.available)
        assertFalse(VfEglImport.awaitSyncFd(-1, 10))
        assertFalse(VfEglImport.awaitSyncFd(Int.MAX_VALUE, 10))
        val pipe = android.os.ParcelFileDescriptor.createPipe()
        val input = android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe[0])
        val output = android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        try {
            val fd = pipe[0].fd
            assertFalse("Unsignaled timeout", VfEglImport.awaitSyncFd(fd, 5))
            val producer = Thread { Thread.sleep(20); output.write(42) }.apply { start() }
            assertTrue("Wake on readiness", VfEglImport.awaitSyncFd(fd, 1000))
            producer.join()
            assertTrue("Wait must not consume readiness", VfEglImport.awaitSyncFd(fd, 0))
            assertEquals("Wait must not consume bytes or close fd", 42, input.read())
            assertFalse("Readiness cleared", VfEglImport.awaitSyncFd(fd, 0))
            output.close()
            assertFalse("Hangup is not successful completion", VfEglImport.awaitSyncFd(fd, 10))
        } finally { input.close(); output.close() }
    }

    @Test fun packedYLoadsPreserveEveryMonitorPixel() {
        val path = InstrumentationRegistry.getArguments().getString("previewReferenceShader")
        assumeTrue("Supply pre-change shader with -e previewReferenceShader",path != null)
        val reference = File(requireNotNull(path)).readBytes()
        val current = asset("vf_p010preview")
        var compared = 0
        var cases = 0
        for ((w, h) in listOf(512 to 256, 2560 to 1440, 3840 to 2160))
            for (box in if (w == 512) listOf(2,4,8,128,256) else listOf(4)) for (pattern in 0..2) {
            val input = HardwareBuffer.create(w,h,HardwareBuffer.RGBA_FP16,1,
                HardwareBuffer.USAGE_CPU_WRITE_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
            val p010 = requireNotNull(VfEglImport.createLinearP010(w,h))
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
                        val layout = intArrayOf(w * 2, w * 2)
                        val strides = intArrayOf(layout[0],layout[1],w,h)
                        val (dims,grade) = VfLogGrade.packGrade(intArrayOf(w,h),floatArrayOf(1f,1f,1f,1f),
                            VfLogGrade.IDENTITY_CCM,0f,false,profile=DirectLogProfile.BT709)
                        assertEquals(0,VfLogGrade.gradeYuvBlockingNative(input,p010,dims,dims,grade,strides,0,1))
                        assertEquals(0,VfRecordPreview.previewBlockingNative(p010,output.buffer,
                            intArrayOf(w/box,h/box),dims,strides,box,0))
                        if (w == 2560 && pattern == 0) {
                            val samples = ArrayList<Long>()
                            repeat(24) { iteration ->
                                val started = System.nanoTime()
                                assertEquals(0,VfRecordPreview.previewBlockingNative(p010,output.buffer,
                                    intArrayOf(w/box,h/box),dims,strides,box,0))
                                if (iteration >= 4) samples.add(System.nanoTime() - started)
                            }
                            samples.sort()
                            android.util.Log.i("PreviewCompare", "1440p original=${shader === reference} medianMs=${samples[samples.size/2]/1e6}")
                        }
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
                cases++
            } finally { output.close();p010.close();input.close() }
        }
        File(context.getExternalFilesDir(null),"preview-parity.txt")
            .writeText("Compared $compared RGBA8 pixels across $cases cases; all bit-identical\n")
    }
}
