// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** One job per shared development-executor turn, keeping camera allocation peaks serialized. */
class DevelopQueueService : Service() {
    private val executor=PhotoDevelopmentExecutor(1)
    @Volatile private var ending=false
    private var wake: PowerManager.WakeLock?=null
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"Develop queue",NotificationManager.IMPORTANCE_LOW))
        promote("Preparing export…")
        wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"$packageName:develop-queue")
            .apply { acquire(6*60*60*1000L) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action==PAUSE) { stopRequested=true; if(!running) stopSelf() }
        else if(!running) { running=true;stopRequested=false;next() }
        return START_NOT_STICKY
    }
    private fun promote(message: String) {
        val open=PendingIntent.getActivity(this,2,Intent(this,DevelopQueueActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause=PendingIntent.getService(this,3,Intent(this,DevelopQueueService::class.java).setAction(PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val note=Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_tune)
            .setContentTitle("Develop queue").setContentText(message).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).addAction(Notification.Action.Builder(null,"Stop after current",pause).build()).build()
        if(Build.VERSION.SDK_INT>=35) startForeground(ID,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        else if(Build.VERSION.SDK_INT>=34) startForeground(ID,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID,note)
    }
    private fun next() {
        val item=if(stopRequested || ending) null else DevelopQueue.claimNext(this)
        if(item==null) { running=false;stopSelf();return }
        executor.execute {
            val cache=File(cacheDir,"develop-queue-${item.id}.dng")
            // ADPF: background batch export, so ask the OS to favor efficiency
            // (API 35) and keep the phone cool for the next capture.
            val powerHint=AdpfHint.create(this,DEVELOP_HINT_NS).apply { preferPowerEfficiency(true) }
            try {
                DevelopQueue.update(this,item.copy(state="Developing",detail="Reading DNG…"))
                promote(item.name)
                contentResolver.openInputStream(Uri.parse(item.uri))?.use { input ->
                    cache.outputStream().use { output ->
                        val block=ByteArray(65536);var total=0L
                        while(true) {
                            val count=input.read(block);if(count<0) break
                            total+=count;require(total<=512L*1024*1024) { "DNG exceeds 512 MB import limit" }
                            output.write(block,0,count)
                        }
                    }
                } ?: error("Cannot open source. Import it again to renew access.")
                val exif=ExifInterface(cache)
                val image=DngImportReader.read(cache)
                val metadata=image.frameMetadata(exif)
                val look = item.options.look().copy(photoLut = PhotoLutSelection(applicationContext).current())
                val settings=RawDevelopmentSettings(exposureEv=item.options.exposureEv+(image.tags[50730]?.first() ?: 0.0),
                    adaptiveExposureStrength=if(item.options.autoExposure) 1f else 0f,
                    adaptiveExposureTuning=AdaptiveExposureTuning.fromOutputSettings(look))
                DevelopQueue.update(this,item.copy(state="Developing",detail="Developing ${image.width} × ${image.height}…"))
                val developer=RawDevelopmentCoordinator(this)
                val uri=try {
                    val developed=if(image.pattern==null) {
                        developer.developMfsrJpeg(image.rgb,image.width,image.height,metadata,settings,look)
                    } else {
                        val values=FloatArray(image.width*image.height)
                        image.rgb.asFloatBuffer().get(values)
                        developer.developCfaJpeg(UnpackedRawCfa(image.width,image.height,image.pattern,
                            values,RawCrop(0,0,image.width,image.height)),metadata,settings,look)
                    }
                    try {
                        val time=parseCaptureTime(exif,item.name)
                        var outputUri: String? = null
                        JpegSaver(this).save(developed,metadata,null,captureTimeMillis=time,
                            typeSuffix="DEVELOPED",subfolder="Developed",
                            description="RawLens development queue\nSource: ${item.name}\nTone mapper: ${look.toneMapper.label}\n"+
                                "Automatic exposure: ${item.options.autoExposure}\nExposure compensation: ${item.options.exposureEv} EV\n"+
                                "Calibrated Basic highlight guards: ${look.toneMapper==PhotoToneMapper.BASIC && item.options.autoExposure}\n",
                            sourceCamera=exif.getAttribute(ExifInterface.TAG_MAKE) to exif.getAttribute(ExifInterface.TAG_MODEL),
                            onPublished={ outputUri=it.toString() })
                        requireNotNull(outputUri)
                    } finally { developed.bitmap.recycle();developed.gainmap?.recycle() }
                } finally { developer.close() }
                DevelopQueue.update(this,item.copy(state="Exported",detail="Saved to DCIM/RawLens/Developed",output=uri))
            } catch(failure: Exception) {
                Log.w("DevelopQueue","Export failed for ${item.name}",failure)
                DevelopQueue.update(this,item.copy(state="Failed",detail=failure.message ?: "Export failed"))
            } finally { cache.delete();powerHint.close() }
            next()
        }
    }
    override fun onTimeout(startId: Int,fgsType: Int) { stopRequested=true;stopSelf() }
    override fun onDestroy() {
        ending=true;stopRequested=true;running=false
        wake?.let { if(it.isHeld) it.release() };wake=null
        executor.shutdown()
        super.onDestroy()
    }
    private fun parseCaptureTime(exif: ExifInterface,name: String): Long {
        val date=exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        if(date!=null) runCatching { SimpleDateFormat("yyyy:MM:dd HH:mm:ss",Locale.US).parse(date)?.time }.getOrNull()?.let { return it }
        val stamp=Regex("(\\d{8}_\\d{6})").find(name)?.value
        if(stamp!=null) runCatching { SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).parse(stamp)?.time }.getOrNull()?.let { return it }
        return System.currentTimeMillis()
    }
    companion object {
        private const val DEVELOP_HINT_NS=10_000_000_000L
        private const val CHANNEL="develop_queue"
        private const val ID=1003
        private const val PAUSE="com.matthew.rawlens.PAUSE_DEVELOP_QUEUE"
        @Volatile var running=false;private set
        @Volatile var stopRequested=false;private set
        fun start(context: Context) { context.startForegroundService(Intent(context,DevelopQueueService::class.java)) }
        fun pause() { stopRequested=true }
    }
}
