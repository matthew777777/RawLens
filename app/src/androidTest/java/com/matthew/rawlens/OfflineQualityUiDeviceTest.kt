// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OfflineQualityUiDeviceTest {
    @Test fun outputQualityControlsPersistThePhotoSettingsKeys() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val prefs = context.getSharedPreferences(SettingsBackup.STORE_MAIN, 0)
        val old = prefs.all
        prefs.edit().putInt(MainActivity.KEY_JPEG_QUALITY, 100).putInt(MainActivity.KEY_JXL_QUALITY, 100).commit()
        val activity = inst.startActivitySync(Intent(context, MergesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MergesActivity
        fun field(name: String): Any? = MergesActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
        fun text(view: View): String = when (view) {
            is TextView -> view.text.toString()
            is ViewGroup -> (0 until view.childCount).joinToString("\n") { text(view.getChildAt(it)) }
            else -> ""
        }
        try {
            inst.waitForIdleSync()
            inst.runOnMainSync {
                MergesActivity::class.java.getDeclaredField("selected").apply { isAccessible = true }
                    .set(activity, File(context.cacheDir, "quality-test.craw"))
                (field("outJpeg") as Button).performClick()
                (field("developedJpeg") as Button).performClick()
                val jpeg = field("jpegQualityControls") as LinearLayout
                val jxl = field("jxlQualityControls") as LinearLayout
                assertEquals(View.VISIBLE, jpeg.visibility)
                assertEquals(View.GONE, jxl.visibility)
                assertTrue(text(jpeg).contains("100%"))
                val slider = jpeg.getChildAt(1) as SettingsSlider
                slider.progress = 86
                val listener = SettingsSlider::class.java.getDeclaredField("settingsListener").apply { isAccessible = true }
                    .get(slider) as SettingsSlider.OnSettingsSliderChangeListener
                listener.onStopTrackingTouch(slider)
                assertEquals(87, MergeScreenSettings.jpeg(prefs).jpegQuality)
                (field("developedJxl") as Button).performClick()
                assertEquals(View.VISIBLE, jxl.visibility)
                assertEquals(View.GONE, jpeg.visibility)
                assertTrue(text(jxl).contains("lossless"))
                val jxlSlider = jxl.getChildAt(1) as SettingsSlider
                assertEquals(JxlOutputSettings.MAX_QUALITY - JxlOutputSettings.MIN_QUALITY, jxlSlider.max)
                jxlSlider.progress = 92 - JxlOutputSettings.MIN_QUALITY
                val jxlListener = SettingsSlider::class.java.getDeclaredField("settingsListener").apply { isAccessible = true }
                    .get(jxlSlider) as SettingsSlider.OnSettingsSliderChangeListener
                jxlListener.onStopTrackingTouch(jxlSlider)
                assertEquals(92, MergeScreenSettings.jxl(prefs).quality)
            }
        } finally {
            inst.runOnMainSync { activity.finish() }
            val edit = prefs.edit()
            for (key in listOf(MainActivity.KEY_JPEG_QUALITY, MainActivity.KEY_JXL_QUALITY)) {
                (old[key] as? Int)?.let { edit.putInt(key, it) } ?: edit.remove(key)
            }
            edit.commit()
        }
    }
}
