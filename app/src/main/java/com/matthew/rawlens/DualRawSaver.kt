package com.matthew.rawlens

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.Image
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import kotlin.math.abs

/** Two imported Bayer buffers -> clip-only GPU merge -> calibrated 16-bit DNG. */
internal object DualRawSaver {
    @Synchronized
    fun save(context: Context, c: CameraCharacteristics,
             high: Image, highResult: CaptureResult, low: Image, lowResult: CaptureResult,
             orientation: Int, captureTime: Long, gps: GpsLocation?): String {
        check(DualRawVulkan.available) { "Vulkan dual RAW library unavailable" }
        require(high.width == low.width && high.height == low.height && low.width % 2 == 0)
        for ((image, result) in listOf(high to highResult, low to lowResult)) {
            check(image.timestamp == result[CaptureResult.SENSOR_TIMESTAMP]) { "RAW metadata mismatch" }
            if (Build.VERSION.SDK_INT >= 33) image.fence.use {
                if (it.isValid) check(it.await(Duration.ofSeconds(2))) { "RAW producer fence timeout" }
            }
        }
        val highIso = requireNotNull(highResult[CaptureResult.SENSOR_SENSITIVITY])
        val lowIso = requireNotNull(lowResult[CaptureResult.SENSOR_SENSITIVITY])
        check(highIso == lowIso) { "Camera did not apply the shared RAW gain" }
        val hp = high.planes[0]; val lp = low.planes[0]
        require(hp.pixelStride == 2 && lp.pixelStride == 2) { "RAW16 required" }
        val le = requireNotNull(lowResult[CaptureResult.SENSOR_EXPOSURE_TIME])
        val he = requireNotNull(highResult[CaptureResult.SENSOR_EXPOSURE_TIME])
        require(le > 0 && he > 0)
        fun reportedBlack(r: CaptureResult): FloatArray? = r[CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL]
            ?.takeIf { it.size == 4 }
            ?: c[CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN]?.let { pattern ->
                FloatArray(4) { pattern.getOffsetForIndex(it % 2, it / 2).toFloat() }
            }
        fun reportedWhite(r: CaptureResult): Float? =
            (r[CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL]
                ?: c[CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL])?.toFloat()
        // Unusable pairs (Vivo X300 Ultra default mode: white=0 or white <=
        // black) fail the merge require below; sample each frame's own bytes
        // instead. The merged DNG's tags are forced by
        // DualRawDngMetadata.patch, so only the merge math needs these.
        fun repaired(image: Image, r: CaptureResult): Pair<FloatArray, Float>? {
            val b = reportedBlack(r)
            val w = reportedWhite(r)
            if (b != null && w != null && !DngLevelRepair.needsRepair(b, w)) return b to w
            val plane = image.planes.singleOrNull() ?: return null
            val sample = DngLevelRepair.sample(plane.buffer, plane.rowStride, plane.pixelStride,
                image.width, image.height) ?: return null
            val (rb, rw) = DngLevelRepair.repair(sample)
            Log.w("RawLensDualRaw", "Dual levels sampled from frame (reported black=" +
                "${b?.joinToString(",")} white=$w unusable): black=${rb.joinToString(",")} white=$rw")
            return rb to rw
        }
        val (lb, lw) = checkNotNull(repaired(low, lowResult)) {
            "Dual RAW levels unusable and unsampleable (low)"
        }
        val (hb, hw) = checkNotNull(repaired(high, highResult)) {
            "Dual RAW levels unusable and unsampleable (high)"
        }
        require(lb.size == 4 && hb.size == 4 && lb.all { it.isFinite() && it < lw } && hb.all { it.isFinite() && it < hw })
        val scale = (le.toDouble()*lowIso/(he.toDouble()*highIso)).toFloat()
        check(scale.isFinite() && scale >= 1.5f) { "Camera did not apply the exposure bracket" }
        // Both streams use long-frame DN units. Dividing each by its own white
        // range would introduce a channel-dependent exposure mismatch.
        val headroom = maxOf(1f, (0..3).maxOf { scale * (hw-hb[it])/(lw-lb[it]) })
        val params = lb + hb + floatArrayOf(lw,hw,scale,headroom)
        val output = ByteBuffer.allocateDirect(Math.multiplyExact(Math.multiplyExact(low.width,low.height),2))
            .order(ByteOrder.LITTLE_ENDIAN)
        val timing = DoubleArray(3)
        try {
            val init = DualRawVulkan.init(
                context.assets.open("shaders/vf/vf_superpixel.spv").use { it.readBytes() },
                context.assets.open("shaders/dualraw/merge.spv").use { it.readBytes() })
            check(init == 0) { "Vulkan init failed ($init)" }
            requireNotNull(low.hardwareBuffer).use { la -> requireNotNull(high.hardwareBuffer).use { ha ->
                val code = DualRawVulkan.merge(la,ha,
                    intArrayOf(low.width,low.height,lp.rowStride/2,hp.rowStride/2),params,output,timing)
                check(code == 0) { "Vulkan merge failed ($code)" }
            } }
        } finally { DualRawVulkan.close() }
        Log.i("RawLensDualRaw", "Merged high=$highIso/$he low=$lowIso/$le " +
            "startGapMs=${abs(low.timestamp-high.timestamp)/1e6} " +
            "midpointGapMs=${abs(low.timestamp+le/2-high.timestamp-he/2)/1e6} " +
            "setupMs=${timing[0]} submitFenceMs=${timing[1]} readbackMs=${timing[2]} scale=$scale headroom=$headroom")
        val name = CaptureFileNames.fileName(captureTime,"DUAL","dng")
        val temporary = File.createTempFile("dual-raw-", ".dng",context.cacheDir)
        try {
            DngCreator(c,lowResult).use { creator ->
                creator.setOrientation(orientation)
                creator.setDescription("RawLens experimental dual-exposure RAW; simple Vulkan Bayer merge; unclipped long samples preserved; short replaces clipped samples only")
                gps?.let { creator.setLocation(it.toAndroidLocation()) }
                output.position(0)
                temporary.outputStream().use { creator.writeByteBuffer(it,Size(low.width,low.height),output,0L) }
            }
            DualRawDngMetadata.patch(temporary, headroom.toDouble())
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME,name)
                put(MediaStore.Images.Media.MIME_TYPE,"image/x-adobe-dng")
                put(MediaStore.Images.Media.RELATIVE_PATH,"DCIM/RawLens")
                put(MediaStore.Images.Media.IS_PENDING,1)
            }
            val resolver = context.contentResolver
            val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values))
            try {
                checkNotNull(resolver.openOutputStream(uri,"w")).use { out -> temporary.inputStream().use { it.copyTo(out) } }
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING,0)
                check(resolver.update(uri,values,null,null) == 1) { "Could not publish dual RAW DNG" }
            } catch (failure: Throwable) {
                resolver.delete(uri,null,null); throw failure
            }
            Log.i("RawLensDualRaw","Saved $name bytes=${temporary.length()}")
            return name
        } finally { temporary.delete() }
    }
}
