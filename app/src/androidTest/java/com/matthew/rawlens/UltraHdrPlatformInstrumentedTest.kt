// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class UltraHdrPlatformInstrumentedTest {
    @Test
    fun androidEncoderRetainsGainmapAndDisplayP3AfterExifWrite() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("rawlens-ultrahdr-", ".jpg", cache)
        try {
            val p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
            val base = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888, false, p3)
            val contents = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888, false, p3)
            base.eraseColor(0xff808080.toInt())
            contents.eraseColor(0xff808080.toInt())
            base.setGainmap(Gainmap(contents).apply {
                setRatioMin(1f, 1f, 1f)
                setRatioMax(16f, 16f, 16f)
                setGamma(1f, 1f, 1f)
                setDisplayRatioForFullHdr(16f)
                setMinDisplayRatioForHdrTransition(1f)
            })
            file.outputStream().use { assertTrue(base.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_SOFTWARE, "RawLens test")
                saveAttributes()
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath)
            assertTrue("Android JPEG encoder/Exif rewrite lost the Ultra HDR gainmap", decoded.hasGainmap())
            assertTrue("Android JPEG encoder did not retain the Display P3 color profile", decoded.colorSpace == p3)
        } finally {
            file.delete()
        }
    }
}
