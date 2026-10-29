// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Uses only framework APIs and persisted keys, so it also runs against R8. */
class DirectLogReleaseSmokeDeviceTest {
    @Test fun lutManagerAndBakedTakeInMinifiedApp() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with -e rawlensReleaseLutSmoke true",args.getString("rawlensReleaseLutSmoke") == "true")
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val source = File(requireNotNull(args.getString("sonyLutPath")))
        assertTrue(source.isFile)
        val width=args.getString("width","1920").toInt()
        val height=args.getString("height","1080").toInt()
        val fps=args.getString("fps","30").toInt()
        val seconds=args.getString("seconds","5").toInt().coerceIn(5,35)
        val sound=args.getString("sound") == "true"
        val resolution=when(width) { 3840 -> "UHD4K";2560 -> "QHD1440";else -> "FHD1080" }
        val prefs = context.getSharedPreferences("rawlens_settings",Context.MODE_PRIVATE)
        val library = context.getSharedPreferences("direct_log_monitor",Context.MODE_PRIVATE)
        val saved = prefs.all.toMap(); val savedLibrary = library.all.toMap()
        val id = UUID.randomUUID().toString()+".cube"
        val directory = File(context.filesDir,"monitor_luts").apply { mkdirs() }
        val imported = File(directory,id)
        var activity: Activity? = null
        var published: Uri? = null
        val report = StringBuilder()
        try {
            source.copyTo(imported)
            library.edit().putString("selected",id).putString("name_$id","Release s709 smoke")
                .putFloat("intensity_$id",1f).putBoolean("bake_recording",true).commit()
            prefs.edit().putBoolean("lens_setup_complete",true).putBoolean("direct_log_video",true)
                .putString("direct_log_profile","SLOG3").putString("video_demosaic","RCD")
                .putString("direct_log_crop","WIDE_16_9")
                .putString("direct_log_res",resolution).putInt("direct_log_fps",fps)
                .putString("video_stab_output","OG_ONLY").putBoolean("video_audio",sound).commit()
            inst.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA").close()
            if (sound) inst.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO").close()
            activity = inst.startActivitySync(Intent().setClassName(context,"com.matthew.rawlens.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val runningActivity = requireNotNull(activity)
            fun view(name: String): View = requireNotNull(runningActivity.findViewById(
                context.resources.getIdentifier(name,"id",context.packageName))) { "Missing view $name" }
            fun click(name: String) = inst.runOnMainSync { assertTrue(view(name).performClick()) }
            fun text(name: String): String {
                var value=""
                inst.runOnMainSync { value=(view(name) as TextView).text.toString() }
                return value
            }
            fun node(label: String): AccessibilityNodeInfo {
                val end = SystemClock.elapsedRealtime()+5000
                while (SystemClock.elapsedRealtime()<end) {
                    val found = inst.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)
                        ?.firstOrNull { it.text?.toString()?.equals(label,ignoreCase=true) == true }
                    if (found != null) return found
                    SystemClock.sleep(100)
                }
                error("Missing UI text: $label")
            }
            val ready = SystemClock.elapsedRealtime()+30000
            while (!view("shutter").isEnabled && SystemClock.elapsedRealtime()<ready) SystemClock.sleep(100)
            assertTrue(view("shutter").isEnabled)
            click("settingsButton")
            assertTrue(node("Video").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            node("Import LUT (.cube)")
            node("Remove imported LUT")
            node("Bake selected LUT into S-Log3 recordings (SDR Rec.709 output)")
            assertTrue(node("S-Log3 LUT library: choose assist / LUT").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(node("Release s709 smoke").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(node("Selected LUT intensity").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(node("100%").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(node("Close").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            report.append("Minified manager: selection, intensity, import/remove/baking controls present\n")
            click("videoRecordButton")
            SystemClock.sleep(500)
            assertEquals("LOG • LUT709",text("rawBadge"))
            val started = System.currentTimeMillis()/1000
            click("shutter")
            SystemClock.sleep(seconds*1000L)
            assertEquals("● LOG • LUT709",text("rawBadge"))
            val hud=text("videoDebugOverlay")
            assertTrue("RCD inactive: $hud",hud.contains("RCD"))
            assertTrue("Recorder reported drops: $hud",Regex("\\bD0\\b").containsMatchIn(hud))
            report.append("Recording HUD: $hud\n")
            click("shutter")
            val deadline=SystemClock.elapsedRealtime()+60000
            var displayName=""
            while (published==null && SystemClock.elapsedRealtime()<deadline) {
                context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Video.Media._ID,MediaStore.Video.Media.DISPLAY_NAME),
                    "${MediaStore.Video.Media.DATE_ADDED} >= ? AND ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
                    arrayOf((started-1).toString(),"%_LUT709.mp4"),"${MediaStore.Video.Media._ID} DESC")?.use {
                    if (it.moveToFirst()) {
                        published=Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,it.getLong(0).toString())
                        displayName=it.getString(1)
                    }
                }
                if (published==null) SystemClock.sleep(200)
            }
            val uri=requireNotNull(published) { "Baked take not published" }
            val extractor=MediaExtractor()
            try {
                extractor.setDataSource(context,uri,null)
                val tracks=(0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                val videoTrack=tracks.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true }
                val format=tracks[videoTrack]
                assertEquals("video/hevc",format.getString(MediaFormat.KEY_MIME))
                assertEquals(width,format.getInteger(MediaFormat.KEY_WIDTH))
                assertEquals(height,format.getInteger(MediaFormat.KEY_HEIGHT))
                assertEquals(MediaFormat.COLOR_STANDARD_BT709,format.getInteger(MediaFormat.KEY_COLOR_STANDARD))
                assertEquals(MediaFormat.COLOR_RANGE_LIMITED,format.getInteger(MediaFormat.KEY_COLOR_RANGE))
                report.append("Published $displayName format=$format\n")
                if (sound) assertTrue("Missing audio",tracks.any { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true })
                if (args.getString("strictCadence") == "true") {
                    extractor.selectTrack(videoTrack)
                    val pts=ArrayList<Long>()
                    while(extractor.sampleTime>=0) { pts.add(extractor.sampleTime);extractor.advance() }
                    assertTrue("Too few frames",pts.size>seconds*fps/2)
                    val origin=pts.first()
                    val fullSeconds=((pts.last()-origin)/1_000_000L).toInt()
                    val windows=(2 until fullSeconds).map { s -> pts.count {
                        it>=origin+s*1_000_000L && it<origin+(s+1)*1_000_000L
                    } }
                    val gaps=pts.zipWithNext { a,b -> b-a }.drop(fps*2).sorted()
                    report.append("frames=${pts.size} steadySeconds=$windows maxSteadyGapUs=${gaps.last()}\n")
                    assertTrue("No steady cadence windows",windows.isNotEmpty())
                    assertTrue("Cadence windows: $windows",windows.all { it>=fps-1 })
                    assertTrue("Missing steady-state interval: ${gaps.last()}",gaps.last()<=1_500_000L/fps)
                }
            } finally { extractor.release() }
            val decoder=MediaMetadataRetriever()
            try {
                decoder.setDataSource(context,uri)
                val frame=decoder.getFrameAtTime(1_000_000L,MediaMetadataRetriever.OPTION_CLOSEST)
                assertNotNull("Baked release file cannot decode",frame)
                frame?.recycle()
                report.append("Baked release take decoded successfully\n")
            } finally { decoder.release() }
        } finally {
            File(context.getExternalFilesDir(null),"release-lut-smoke.txt").writeText(report.toString())
            activity?.let { inst.runOnMainSync { it.finish() }; inst.waitForIdleSync() }
            published?.let { context.contentResolver.delete(it,null,null) }
            imported.delete()
            restore(prefs,saved);restore(library,savedLibrary)
        }
    }

    private fun restore(prefs: SharedPreferences, saved: Map<String,*>) {
        val edit=prefs.edit().clear()
        for ((key,value) in saved) when (value) {
            is String -> edit.putString(key,value)
            is Boolean -> edit.putBoolean(key,value)
            is Int -> edit.putInt(key,value)
            is Long -> edit.putLong(key,value)
            is Float -> edit.putFloat(key,value)
            is Set<*> -> edit.putStringSet(key,value.filterIsInstance<String>().toSet())
        }
        edit.commit()
    }
}
