package com.matthew.rawlens

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.log2

/**
 * Diagnostic (opt-in, SRP_VKFULL_DIR): renders full-res MFSR merges written by
 * sr-pipeline VulkanBurstCheckTest.fullResKernelNet (camera RGB f32 + DNG colour
 * tags) with the app's SceneLinearColorProcessor (camera -> ACEScg) and
 * AgxDisplayTransform (default settings = AgX Base) to sRGB PNG. Exposure: median
 * luminance -> 0.18 (not the app's adaptive meter).
 */
class MfsrAgxRenderTest {
    @Test fun renderAll() {
        val dir = System.getenv("SRP_VKFULL_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        for (f32 in dir!!.listFiles { f -> f.name.endsWith(".f32") }!!.sorted()) {
            val tags = File(f32.path.removeSuffix(".f32") + ".tags").readLines().filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            fun arr(k: String) = tags[k]?.split(',')?.map { it.toDouble() }?.toDoubleArray()?.let(::ImmutableDoubleValues)
            val w = tags.getValue("w").toInt(); val h = tags.getValue("h").toInt()
            val meta = SceneLinearColorMetadata(asShotNeutral = arr("neutral"), wbGains = null,
                cameraCalibration1 = arr("cc1"), cameraCalibration2 = arr("cc2"), forwardMatrix1 = arr("fm1"), forwardMatrix2 = arr("fm2"),
                colorMatrix1 = arr("cm1"), colorMatrix2 = arr("cm2"),
                referenceIlluminant1 = tags["illum1"]?.toInt(), referenceIlluminant2 = tags["illum2"]?.toInt())
            val rgb = ByteBuffer.wrap(f32.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val t0 = SceneLinearColorProcessor.resolve(meta, 0.0)
            val m = t0.cameraToAcescg.toDoubleArray()
            // median ACEScg luminance (sampled) -> 0.18
            val lum = ArrayList<Double>()
            for (i in 0 until w * h step 97) {
                val r = rgb.get(3 * i).toDouble(); val g = rgb.get(3 * i + 1).toDouble(); val b = rgb.get(3 * i + 2).toDouble()
                lum += 0.2722 * (m[0] * r + m[1] * g + m[2] * b) + 0.6741 * (m[3] * r + m[4] * g + m[5] * b) + 0.0537 * (m[6] * r + m[7] * g + m[8] * b)
            }
            val ev = log2(0.18 / lum.sorted()[lum.size / 2].coerceAtLeast(1e-6)).coerceIn(-4.0, 8.0)
            val mm = SceneLinearColorProcessor.resolve(meta, ev).cameraToAcescg.toDoubleArray()
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val ac = FloatArray(3)
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                val r = rgb.get(3 * i).toDouble(); val g = rgb.get(3 * i + 1).toDouble(); val b = rgb.get(3 * i + 2).toDouble()
                for (c in 0..2) ac[c] = (mm[3 * c] * r + mm[3 * c + 1] * g + mm[3 * c + 2] * b).toFloat()
                val out = AgxDisplayTransform.finalGamutClip(AgxDisplayTransform.acescgToOutputLinearSrgb(ac))
                val q = AgxDisplayTransform.quantizeSrgb8(out, x, y)
                img.setRGB(x, y, ((q[0].toInt() and 255) shl 16) or ((q[1].toInt() and 255) shl 8) or (q[2].toInt() and 255))
            }
            ImageIO.write(img, "png", File(dir, f32.name.removeSuffix(".f32") + "_kernelnet_agx_base.png"))
            println("AGX ${f32.name} ev %+.2f".format(ev))
        }
    }
}
