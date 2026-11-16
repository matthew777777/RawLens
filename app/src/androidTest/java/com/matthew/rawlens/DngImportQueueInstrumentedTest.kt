// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.net.Uri
import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DeflaterOutputStream

class DngImportQueueInstrumentedTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    /** FGS test exemption only; never launches an Activity or touches the display. */
    private fun startHeadlessQueue() {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND")
        try { DevelopQueueService.start(context) }
        finally { automation.dropShellPermissionIdentity() }
    }
    private fun fields(channels: Int,white: Int=4095,dng17: Boolean=false)=TiffFields().apply {
        shorts(274,6)
        bytes(50706,byteArrayOf(1,if(dng17) 7 else 4,0,0))
        rationals(50728,doubleArrayOf(1.0,1.0,1.0))
        srationals(50721,doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0))
        srationals(50964,doubleArrayOf(.96422,0.0,0.0,0.0,1.0,0.0,0.0,0.0,.82521))
        shorts(50778,21)
        if(channels==1) { shorts(33421,2,2);bytes(33422,byteArrayOf(0,1,1,2));shorts(50713,2,2) }
        rationals(50714,if(channels==1) doubleArrayOf(64.0,65.0,66.0,67.0) else doubleArrayOf(0.0,0.0,0.0))
        longs(50717,white)
    }
    private fun raster(name: String,channels: Int=1,float: Boolean=false,zip: Boolean=false,jxl: Boolean=false): File {
        val w=32;val h=24
        val bytes=ByteBuffer.allocate(w*h*channels*if(float) 4 else 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(w*h*channels) { i -> if(float) bytes.putFloat(if(i%11==0) 1.5f else .25f) else bytes.putShort((512+i%1000).toShort()) }
        val file=File(context.cacheDir,"$name.dng")
        val tags=fields(channels,if(float) 1 else if(channels==3) 65535 else 4095,jxl)
        val use=if(float) TiffFields().apply {
            shorts(274,6);bytes(50706,byteArrayOf(1,4,0,0));rationals(50728,doubleArrayOf(1.0,1.0,1.0))
            srationals(50721,doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0))
            shorts(50778,21);shorts(33421,2,2);bytes(33422,byteArrayOf(0,1,1,2));longs(50717,1)
        } else tags
        file.outputStream().use { output ->
            if(jxl) TinyDngImageWriter.writeCompressed(output,w,h,bytes.array(),use,null,null)
            else TinyDngImageWriter.write(output,w,h,channels,if(float) 32 else 16,if(float) 3 else 1,
                if(channels==1) 32803 else 34892,h,use,null,null,1) { bytes.array() }
        }
        if(zip) {
            val original=file.readBytes();val b=ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN)
            val ifd=b.getInt(4);val count=b.getShort(ifd).toInt() and 65535
            var offset=0;var countEntry=0
            repeat(count) { i -> val p=ifd+2+i*12;when(b.getShort(p).toInt() and 65535) {
                259 -> b.putShort(p+8,8.toShort());273 -> offset=b.getInt(p+8);279 -> countEntry=p+8
            } }
            val compressed=java.io.ByteArrayOutputStream().also { target -> DeflaterOutputStream(target).use { it.write(original,offset,original.size-offset) } }.toByteArray()
            b.putInt(countEntry,compressed.size)
            file.outputStream().use { it.write(original,0,offset);it.write(compressed) }
        }
        return file
    }
    private fun checkBayer(file: File,float: Boolean=false) {
        val image=DngImportReader.read(file)
        assertEquals(32,image.width);assertEquals(24,image.height);assertEquals(6,image.orientation);assertEquals(BayerPattern.RGGB,image.pattern)
        repeat(32*24) { i ->
            val black=64+((i/32%2)*2+i%32%2)
            val expected=if(float) { if(i%11==0) 1.5f else .25f } else ((512+i%1000-black).toFloat()/(4095-black))
            assertEquals("pixel $i",expected,image.rgb.getFloat(i*4),2e-5f)
        }
    }
    @Test fun uncompressedBayerNormalizesEachCfaBlackLevel() { checkBayer(raster("queue-bayer")) }
    @Test fun deflateBayerMatchesUncompressed() { checkBayer(raster("queue-zip",zip=true)) }
    @Test fun losslessJxlBayerMatchesUncompressed() { checkBayer(raster("queue-jxl",jxl=true)) }
    @Test fun floatCfaRetainsValuesAboveSensorWhite() { checkBayer(raster("queue-float",float=true),float=true) }
    @Test fun deflateFloatCfaRetainsValuesAboveSensorWhite() { checkBayer(raster("queue-float-zip",float=true,zip=true),float=true) }
    @Test fun linearRgbDoesNotGetDemosaiced() {
        val image=DngImportReader.read(raster("queue-linear",channels=3))
        assertNull(image.pattern);assertEquals(6,image.orientation)
        repeat(32*24*3) { assertEquals((512+it%1000)/65535f,image.rgb.getFloat(it*4),2e-5f) }
    }
    @Test fun optionalFlowerDngCleanupComparisons() {
        val file=File(context.getExternalFilesDir(null),"dng-import-flower.dng")
        org.junit.Assume.assumeTrue("External flower fixture not installed",file.isFile)
        val image=DngImportReader.read(file)
        val metadata=image.frameMetadata(ExifInterface(file))
        val developer=RawDevelopmentCoordinator(context)
        try { for((name,cleanup) in listOf("off" to ChromaCleanup(false,0f),
            "defringe" to ChromaCleanup(false,1f),"median" to ChromaCleanup(true,0f),"both" to ChromaCleanup(true,1f))) {
            image.rgb.rewind()
            val look=JpegOutputSettings(toneMapper=PhotoToneMapper.BASIC,chromaCleanup=cleanup)
            val settings=RawDevelopmentSettings(adaptiveExposureStrength=1f,adaptiveExposureTuning=AdaptiveExposureTuning.fromOutputSettings(look))
            val developed=developer.developMfsrJpeg(image.rgb,image.width,image.height,metadata,settings,look)
            try { File(context.getExternalFilesDir(null),"flower-$name.jpg").outputStream().use { developed.bitmap.compress(Bitmap.CompressFormat.JPEG,100,it) } }
            finally { developed.bitmap.recycle();developed.gainmap?.recycle() }
        } } finally { developer.close() }
    }

    @Test fun optionalTodayDngsExportThroughTheRealQueue() {
        val directory=File(context.getExternalFilesDir(null),"today-dngs")
        org.junit.Assume.assumeTrue("External DNG batch not installed",directory.isDirectory)
        val sources=directory.listFiles { f -> f.extension.equals("dng",true) }!!.sortedBy { it.name }
        assertEquals(8,sources.size)
        DevelopQueue.snapshot(context).forEach { DevelopQueue.remove(context,it.id) }
        DevelopQueue.add(context,sources.map { Uri.fromFile(it).toString() to it.name })
        DevelopQueue.prepare(context,DevelopQueueOptions())
        startHeadlessQueue()
        val deadline=android.os.SystemClock.elapsedRealtime()+180_000
        while(android.os.SystemClock.elapsedRealtime()<deadline) {
            if(DevelopQueue.snapshot(context).all { it.state in listOf("Exported","Failed") }) break
            Thread.sleep(100)
        }
        val items=DevelopQueue.snapshot(context)
        assertEquals(items.toString(),8,items.count { it.state=="Exported" })
        val result=org.json.JSONArray()
        items.forEach { item -> result.put(org.json.JSONObject().put("source",item.name).put("output",item.output)) }
        File(context.getExternalFilesDir(null),"today-queue-results.json").writeText(result.toString(2))
    }

    @Test fun stopAfterCurrentLeavesRemainingPhotosWaitingAndResumes() {
        val first=raster("queue-stop-first",channels=3);val second=raster("queue-stop-second",channels=3)
        DevelopQueue.snapshot(context).forEach { DevelopQueue.remove(context,it.id) }
        DevelopQueue.add(context,listOf(first,second).map { Uri.fromFile(it).toString() to it.name })
        DevelopQueue.prepare(context,DevelopQueueOptions(autoExposure=false))
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val blocker=PhotoDevelopmentExecutor(1)
        blocker.execute { entered.countDown();release.await(30,java.util.concurrent.TimeUnit.SECONDS) }
        assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
        try {
            startHeadlessQueue()
            val deadline=android.os.SystemClock.elapsedRealtime()+10_000
            while(DevelopQueue.snapshot(context).none { it.state=="Developing" } && android.os.SystemClock.elapsedRealtime()<deadline) Thread.sleep(10)
            assertEquals(1,DevelopQueue.snapshot(context).count { it.state=="Developing" })
            DevelopQueueService.pause()
        } finally { release.countDown();blocker.shutdown() }
        var deadline=android.os.SystemClock.elapsedRealtime()+30_000
        while(DevelopQueueService.running && android.os.SystemClock.elapsedRealtime()<deadline) Thread.sleep(50)
        val stopped=DevelopQueue.snapshot(context)
        assertEquals(stopped.toString(),1,stopped.count { it.state=="Exported" })
        assertEquals(stopped.toString(),1,stopped.count { it.state=="Waiting" })
        startHeadlessQueue()
        deadline=android.os.SystemClock.elapsedRealtime()+30_000
        while(DevelopQueue.snapshot(context).any { it.state in listOf("Waiting","Developing") } && android.os.SystemClock.elapsedRealtime()<deadline) Thread.sleep(50)
        assertEquals(2,DevelopQueue.snapshot(context).count { it.state=="Exported" })
    }

    @Test fun mixedQueueExportsAndContinuesAfterBadInput() {
        val sources=listOf(raster("queue-good-linear",channels=3),File(context.cacheDir,"queue-bad.dng").apply { writeText("invalid") },raster("queue-good-bayer",zip=true))
        DevelopQueue.snapshot(context).forEach { DevelopQueue.remove(context,it.id) }
        DevelopQueue.add(context,sources.map { Uri.fromFile(it).toString() to it.name })
        DevelopQueue.prepare(context,DevelopQueueOptions(autoExposure=false))
        startHeadlessQueue()
        val deadline=android.os.SystemClock.elapsedRealtime()+90_000
        while(android.os.SystemClock.elapsedRealtime()<deadline) {
            val items=DevelopQueue.snapshot(context)
            if(items.size==3 && items.all { it.state in listOf("Exported","Failed") }) break
            Thread.sleep(100)
        }
        val items=DevelopQueue.snapshot(context)
        assertEquals(items.toString(),2,items.count { it.state=="Exported" })
        assertEquals(items.toString(),1,items.count { it.state=="Failed" })
        items.filter { it.state=="Exported" }.forEach { item ->
            context.contentResolver.openFileDescriptor(Uri.parse(item.output),"r")!!.use {
                assertEquals(6,ExifInterface(it.fileDescriptor).getAttributeInt(ExifInterface.TAG_ORIENTATION,0))
            }
        }

    }
}
