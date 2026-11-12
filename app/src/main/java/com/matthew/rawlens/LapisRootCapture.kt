// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.ContentValues
import android.content.Context
import android.os.Process
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** One-shot phone-only capture; no ADB channel, persistent root server, or vendor binary shipped. */
internal object LapisRootCapture {
    data class Settings(val iso: Int, val exposureNanos: Long, val orientation: Int,
                        val gains: List<Float>, val focusDiopters: Float)

    fun capture(context: Context, mode: LapisRootProfile.Mode, settings: Settings): String {
        require(LapisRootProfile.supported() && mode != LapisRootProfile.Mode.NORMAL)
        require(settings.gains.size == 4 && settings.gains.all { it.isFinite() && it > 0 })
        val job = File(context.noBackupFilesDir, "lapis-captures/${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            val app = context.applicationInfo
            val args = listOf(mode.argument, job.path, app.sourceDir, app.nativeLibraryDir, Process.myUid().toString(),
                settings.iso.toString(), settings.exposureNanos.toString(), settings.orientation.toString(),
                settings.gains.joinToString(","), "v1", settings.focusDiopters.toString())
            val command = "CLASSPATH=${quote(app.sourceDir)} exec app_process /system/bin " +
                "com.matthew.rawlens.LapisRootCameraWorker " + args.joinToString(" ", transform = ::quote)
            val log = File(job, "worker.log")
            val root = ProcessBuilder("su", "-M", "-c", command).redirectErrorStream(true).redirectOutput(log).start()
            if (!root.waitFor(110, TimeUnit.SECONDS)) {
                root.destroyForcibly()
                throw IOException("Root capture timed out; recovery watchdog will restore the camera")
            }
            val reportFile = File(job,"report.json")
            val report = reportFile.takeIf(File::isFile)?.readText()?.let(::JSONObject)
            if (root.exitValue() != 0 || report?.optBoolean("restored") != true) {
                val error = report?.optString("error")?.takeIf(String::isNotBlank)
                    ?: "Root access unavailable. Restore temporary root with GhostLock and grant RawLens access in KernelSU."
                throw IOException(error)
            }
            val width = if (mode == LapisRootProfile.Mode.FULL) 8160 else 4080
            val height = if (mode == LapisRootProfile.Mode.FULL) 6120 else 3060
            require(report.getInt("width") == width && report.getInt("height") == height && report.getBoolean("quadrantsPopulated"))
            val dng = File(job,"capture.dng")
            LapisDngValidation.validate(dng,width,height)
            val stem = "IMG_${System.currentTimeMillis()}_${if (mode == LapisRootProfile.Mode.FULL) "50MP" else "SENSOR2X"}"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME,"$stem.dng")
                put(MediaStore.Images.Media.MIME_TYPE,"image/x-adobe-dng")
                put(MediaStore.Images.Media.RELATIVE_PATH,"DCIM/RawLens")
                put(MediaStore.Images.Media.IS_PENDING,1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values) ?: error("Cannot create DNG media entry")
            try {
                resolver.openOutputStream(uri,"w")!!.use { output -> dng.inputStream().use { it.copyTo(output) } }
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING,0)
                check(resolver.update(uri,values,null,null) == 1)
            } catch (failure: Throwable) { resolver.delete(uri,null,null); throw failure }
            // Retain capture provenance privately; the RAW contains an explicit mode description too.
            File(context.noBackupFilesDir,"lapis-last-capture.json").writeText(report.put("fileName","$stem.dng").toString(2))
            return "$stem.dng"
        } finally {
            for (name in listOf("worker.log", "report.json")) runCatching {
                File(job, name).takeIf(File::isFile)?.copyTo(File(context.noBackupFilesDir,"lapis-last-$name"),overwrite = true)
            }
            // Worker removes the vendor-labelled temporary file while still root.
            job.deleteRecursively()
        }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
