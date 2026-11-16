// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.opengl.GLES30
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs only in the isolated validation package. Synthetic output stays in memory. */
class RapidRawDailyDrivingInstrumentedTest {
    private var restoreSleep=false
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun launch():MainActivity {
        require(context.packageName.endsWith(".rcdcheck"))
        restoreSleep=!(context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").let {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() }
        }
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.CAMERA}").close()
        context.getSharedPreferences(MainActivity.PREFS_NAME,Context.MODE_PRIVATE).edit()
            .putBoolean("lens_setup_complete",true).commit()
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.runOnMainSync {
            activity.setShowWhenLocked(true);activity.setTurnScreenOn(true)
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        return activity
    }
    private fun finish(activity:MainActivity) {
        instrumentation.runOnMainSync{activity.finish()}
        if(restoreSleep)instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
    }
    private fun report(text:String) {
        File(context.getExternalFilesDir(null),"rapidraw-daily-results.txt").appendText("$text\n")
    }

    @Test fun detailsSwitchPersistsAcrossReopening() {
        val activity=launch()
        val prefs=context.getSharedPreferences(MainActivity.PREFS_NAME,Context.MODE_PRIVATE)
        val original=prefs.getBoolean(ChromaCleanup.KEY_RAPIDRAW,true)
        fun find(view:View):SettingsSwitch? {
            if(view is SettingsSwitch&&view.text.toString().startsWith("RapidRAW cleanup"))return view
            if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let{return it}
            return null
        }
        try {
            SystemClock.sleep(1200)
            instrumentation.runOnMainSync {
                activity.showSettings(6)
                val toggle=checkNotNull(find(checkNotNull(activity.settingsDialog?.window?.decorView)))
                assertEquals(original,toggle.isChecked)
                toggle.performClick()
                assertEquals(!original,prefs.getBoolean(ChromaCleanup.KEY_RAPIDRAW,original))
                assertEquals(!original,activity.jpegOutputSettings().chromaCleanup.rapidRawPreprocessing)
                activity.showSettings(6)
                val reopened=checkNotNull(find(checkNotNull(activity.settingsDialog?.window?.decorView)))
                assertEquals(!original,reopened.isChecked)
                reopened.performClick()
                assertEquals(original,prefs.getBoolean(ChromaCleanup.KEY_RAPIDRAW,!original))
            }
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                File(context.getExternalFilesDir(null),"rapidraw-details.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                }
                image.recycle()
            }
            report("Details switch: visible; ON/OFF saved; reopening preserves state; original state restored.")
        }finally{
            prefs.edit().putBoolean(ChromaCleanup.KEY_RAPIDRAW,original).commit()
            finish(activity)
        }
    }

    @Test fun viewfinderContinuesDuringFullResolutionJpegWork() {
        val activity=launch()
        val executor=Executors.newSingleThreadExecutor()
        try {
            val vf=activity.findViewById<RawViewfinder>(R.id.rawViewfinder)
            val deadline=SystemClock.elapsedRealtime()+25_000
            while((!vf.snapshot().glActive||vf.snapshot().fps<=0)&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100)
            val baseline=vf.snapshot()
            report("Viewfinder baseline: $baseline")
            assumeTrue("RAW viewfinder unavailable in isolated app",baseline.glActive&&baseline.fps>0)
            val stamp=RawViewfinder::class.java.getDeclaredField("lastRenderedTimestamp").apply{isAccessible=true}
            val job=executor.submit {
                Gles31AmazeProcessor.EglComputeContext().use {
                    val texture=IntArray(1);val fbo=IntArray(1)
                    GLES30.glGenTextures(1,texture,0);GLES30.glGenFramebuffers(1,fbo,0)
                    val processor=Gles31JpegOutputProcessor(context)
                    try {
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture[0])
                        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D,1,GLES30.GL_RGBA16F,4080,3060)
                        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo[0])
                        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,texture[0],0)
                        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)==GLES30.GL_FRAMEBUFFER_COMPLETE)
                        GLES30.glClearBufferfv(GLES30.GL_COLOR,0,floatArrayOf(.18f,.16f,.14f,1f),0)
                        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0)
                        repeat(3){
                            val start=SystemClock.elapsedRealtime()
                            val jpeg=processor.process(AmazeGpuOutput(texture[0],4080,3060),JpegOutputSettings(ultraHdr=true))
                            jpeg.bitmap.recycle();jpeg.gainmap?.recycle()
                            report("Concurrent 12.5 MP UltraHDR bitmap: ${SystemClock.elapsedRealtime()-start}ms")
                        }
                    }finally{
                        processor.close();GLES30.glDeleteTextures(1,texture,0);GLES30.glDeleteFramebuffers(1,fbo,0)
                    }
                }
            }
            var minFps=baseline.fps;var maxAge=0L;var samples=0
            val stop=SystemClock.elapsedRealtime()+60_000
            while(!job.isDone&&SystemClock.elapsedRealtime()<stop){
                val stats=vf.snapshot();if(stats.fps>0)minFps=minOf(minFps,stats.fps)
                val last=stamp.getLong(vf)
                if(last>0)maxAge=maxOf(maxAge,SystemClock.elapsedRealtime()-last)
                samples++;SystemClock.sleep(20)
            }
            job.get(5,TimeUnit.SECONDS)
            report("Concurrent viewfinder: samples=$samples minRollingFps=$minFps maxFrameAge=${maxAge}ms final=${vf.snapshot()}")
            assertTrue("Viewfinder stalled for ${maxAge}ms",maxAge<1000)
            assertTrue(vf.snapshot().glActive)
        }finally{
            executor.shutdownNow()
            finish(activity)
        }
    }
}
