// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class UltraHdrPlatformInstrumentedTest {
    private fun solidBitmap(width: Int, height: Int, argb: Int, p3: Boolean): Bitmap {
        val colorSpace = ColorSpace.get(
            if (p3) ColorSpace.Named.DISPLAY_P3 else ColorSpace.Named.SRGB
        )
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, false, colorSpace).also {
            it.eraseColor(argb)
        }
    }

    private fun readBytes(file: File): ByteArray = file.readBytes()

    private fun markerPositions(bytes: ByteArray, first: Byte, second: Byte): List<Int> {
        val out = mutableListOf<Int>()
        for (index in 0 until bytes.size - 1) {
            if (bytes[index] == first && bytes[index + 1] == second) out += index
        }
        return out
    }

    private fun ascii(bytes: ByteArray): String = String(bytes, Charsets.ISO_8859_1)

    @Test
    fun nativeWriterProducesMpfXmpAndIsoContainer() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("rawlens-ultrahdr-native-", ".jpg", cache)
        val base = solidBitmap(32, 32, 0xffb08050.toInt(), p3 = false)
        // Bright luma ramp in the map so the adaptive fit has real headroom.
        val map = solidBitmap(8, 8, 0xffc0c0c0.toInt(), p3 = false)
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { pfd ->
                NativeUltraHdrWriter.encode(base, map, pfd, JpegOutputSettings(ultraHdr = true))
            }
            val bytes = readBytes(file)
            val text = ascii(bytes)
            // SOI..EOI envelope with a second SOI for the gain-map image.
            val sois = markerPositions(bytes, 0xFF.toByte(), 0xD8.toByte())
            assertTrue("expected primary + secondary SOI, saw ${sois.size}", sois.size >= 2)
            assertEquals(0, sois[0])
            assertEquals(0xFF.toByte(), bytes[bytes.size - 2])
            assertEquals(0xD9.toByte(), bytes[bytes.size - 1])
            // MPF multiplex block (single MPF header).
            assertTrue("MPF header missing", text.contains("MPF"))
            // GContainer primary XMP + hdrgm secondary XMP with the packet wrapper.
            assertTrue("XMP packet wrapper missing", text.contains("<?xpacket"))
            assertTrue("GContainer directory missing", text.contains("Container:Directory"))
            assertTrue("hdrgm metadata missing", text.contains("hdrgm:GainMapMax"))
            // ISO 21496-1 boxes for primary and secondary.
            assertTrue(
                "ISO 21496-1 namespace missing",
                text.contains("urn:iso:std:iso:ts:21496:-1")
            )
            // Legacy readers still decode the SDR base.
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
            assertTrue("native Ultra HDR file did not legacy-decode", decoded != null)
            assertEquals(32, decoded.width)
            assertEquals(32, decoded.height)
            decoded.recycle()
        } finally {
            base.recycle()
            map.recycle()
            file.delete()
        }
    }

    @Test
    fun nativeWriterSurvivesExifRewriteWithGainmapIntact() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("rawlens-ultrahdr-exif-", ".jpg", cache)
        val p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        val base = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888, false, p3)
        val map = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888, false, p3)
        try {
            base.eraseColor(0xff808080.toInt())
            map.eraseColor(0xffb0b0b0.toInt())
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { pfd ->
                NativeUltraHdrWriter.encode(
                    base, map, pfd,
                    JpegOutputSettings(ultraHdr = true, displayP3 = true)
                )
            }
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_SOFTWARE, "RawLens test")
                saveAttributes()
            }
            val text = ascii(readBytes(file))
            assertTrue("Exif rewrite dropped the MPF multiplex", text.contains("MPF"))
            assertTrue("Exif rewrite dropped the hdrgm metadata", text.contains("hdrgm:GainMapMax"))
            val decoded = BitmapFactory.decodeFile(file.absolutePath)
            assertTrue("Exif rewrite lost the Ultra HDR gainmap", decoded.hasGainmap())
            assertTrue(
                "Exif rewrite lost the Display P3 color profile",
                decoded.colorSpace == p3
            )
            decoded.recycle()
        } finally {
            base.recycle()
            map.recycle()
            file.delete()
        }
    }
}
