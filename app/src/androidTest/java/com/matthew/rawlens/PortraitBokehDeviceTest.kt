package com.matthew.rawlens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLES31
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Runs the real GPU portrait renderer on pushed JPEGs (files/portrait_in/\*.jpg)
 * and writes the result PNGs to files/portrait_out, so device output can be
 * compared with the Mac reference renderer.
 */
class PortraitBokehDeviceTest {
    @Test fun renderPushedImages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val inputs = File(context.getExternalFilesDir(null), "portrait_in").listFiles { f -> f.name.endsWith(".jpg") }
        assumeTrue("push JPEGs to files/portrait_in", !inputs.isNullOrEmpty())
        val out = File(context.getExternalFilesDir(null), "portrait_out").apply { mkdirs() }
        val nets = checkNotNull(PortraitNetsHolder.get(context))
        Gles31AmazeProcessor.EglComputeContext().use {
            val bokeh = Gles31PortraitBokeh(context)
            for (file in inputs!!.sorted()) {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                val w = bmp.width; val h = bmp.height
                val tex = upload(bmp)
                val f = file.nameWithoutExtension.substringAfter("_f", "1.8").toFloatOrNull() ?: 1.8f
                val result = bokeh.apply(AmazeGpuOutput(tex, w, h), PortraitSettings(enabled = true, fNumber = f), nets, 1, 1)
                Log.i("RawLensPortrait", "device test ${file.name}: $result")
                save(tex, w, h, File(out, file.nameWithoutExtension + "_out.png"))
                GLES31.glDeleteTextures(1, intArrayOf(tex), 0)
            }
            bokeh.close()
        }
    }

    private fun srgbToLinear(v: Float) = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    private fun linearToSrgb(v: Float): Float { val x = v.coerceIn(0f, 1f)
        return if (x <= 0.0031308f) x * 12.92f else 1.055f * x.pow(1f / 2.4f) - 0.055f }

    /** sRGB JPEG -> linear Rec.709 -> ACEScg (AP1, Bradford D65->D60), RGBA16F. */
    private fun upload(bmp: Bitmap): Int {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        val buf = ByteBuffer.allocateDirect(w * h * 16).order(ByteOrder.nativeOrder())
        val fb = buf.asFloatBuffer()
        for (p in px) {
            val r = srgbToLinear(((p shr 16) and 0xff) / 255f)
            val g = srgbToLinear(((p shr 8) and 0xff) / 255f)
            val b = srgbToLinear((p and 0xff) / 255f)
            fb.put(0.61309f * r + 0.33952f * g + 0.04737f * b)
            fb.put(0.07019f * r + 0.91635f * g + 0.01345f * b)
            fb.put(0.02062f * r + 0.10957f * g + 0.86981f * b)
            fb.put(1f)
        }
        val id = IntArray(1).also { GLES31.glGenTextures(1, it, 0) }[0]
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, id)
        GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, w, h)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST)
        GLES31.glPixelStorei(GLES31.GL_UNPACK_ALIGNMENT, 4)
        GLES31.glTexSubImage2D(GLES31.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES30.GL_RGBA, GLES31.GL_FLOAT, buf)
        check(GLES31.glGetError() == 0) { "upload failed" }
        return id
    }

    private fun save(tex: Int, w: Int, h: Int, file: File) {
        val fbo = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        val buf = ByteBuffer.allocateDirect(w * h * 16).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES31.GL_FLOAT, buf)
        check(GLES31.glGetError() == 0) { "readback failed" }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        val fb = buf.asFloatBuffer()
        val px = IntArray(w * h)
        for (i in 0 until w * h) {
            val a = fb.get(); val b = fb.get(); val c = fb.get(); fb.get()
            // ACEScg -> linear Rec.709 (common.glsl AP1_TO_709)
            val r = 1.70505f * a - 0.62179f * b - 0.08326f * c
            val g = -0.13026f * a + 1.14080f * b - 0.01055f * c
            val bl = -0.02400f * a - 0.12897f * b + 1.15297f * c
            fun q(v: Float) = (linearToSrgb(v) * 255f + 0.5f).toInt()
            px[i] = (0xff shl 24) or (q(r) shl 16) or (q(g) shl 8) or q(bl)
        }
        file.outputStream().use { Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
