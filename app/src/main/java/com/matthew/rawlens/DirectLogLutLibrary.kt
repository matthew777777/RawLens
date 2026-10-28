// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/** User-owned DirectLog LUTs. No maker files are shipped or downloaded by the app. */
internal class DirectLogLutLibrary(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("direct_log_monitor", Context.MODE_PRIVATE)
    private val directory get() = File(app.filesDir, "monitor_luts").apply { mkdirs() }
    var bakeIntoRecording: Boolean
        get() = prefs.getBoolean("bake_recording", false)
        set(value) { prefs.edit().putBoolean("bake_recording", value).apply() }
    var selected: String
        get() = prefs.getString("selected", OFF) ?: OFF
        set(value) { prefs.edit().putString("selected", value).apply() }
    var intensity: Float
        get() = prefs.getFloat("intensity_$selected", 1f).let { if (it.isFinite()) it.coerceIn(0f,1f) else 1f }
        set(value) { require(value.isFinite()); prefs.edit().putFloat("intensity_$selected", value.coerceIn(0f,1f)).apply() }

    fun entries(): List<File> = directory.listFiles()?.filter { it.name.matches(FILE_NAME) }
        ?.sortedBy { it.name } ?: emptyList()

    fun load(selection: String = selected): CubeLut? = when (selection) {
        OFF -> null
        TECHNICAL -> null // Analytic shader, avoiding interpolation bias around neutral.
        else -> {
            require(selection.matches(FILE_NAME)) { "Invalid LUT selection" }
            File(directory, selection).reader().use { CubeLut.parse(it) }
        }
    }

    fun label(): String = when (val id = selected) {
        OFF -> "Off"
        TECHNICAL -> "Technical BT.709"
        else -> title(id)
    }

    fun title(id: String): String = prefs.getString("name_$id", id) ?: id

    /** Bounded copy into private storage; publish only after full parse succeeds. */
    fun import(uri: Uri): String {
        require(entries().size < 16) { "Library is full (16 LUTs); remove one first" }
        val id = UUID.randomUUID().toString() + ".cube"
        val temp = File(directory, "$id.tmp")
        try {
            requireNotNull(app.contentResolver.openInputStream(uri)) { "Cannot open LUT" }.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= CubeLut.MAX_BYTES) { "LUT exceeds 32 MiB" }
                        output.write(buffer, 0, n)
                    }
                }
            }
            val lut = temp.reader().use { CubeLut.parse(it) }
            val originalName = runCatching {
                app.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                    if (it.moveToFirst()) it.getString(0)?.take(120) else null
                }
            }.getOrNull() ?: "Imported LUT"
            check(temp.renameTo(File(directory, id))) { "Cannot save LUT" }
            prefs.edit().putString("name_$id", if (lut.title == "Imported LUT") originalName else lut.title).apply()
            selected = id
            return id
        } finally { temp.delete() }
    }

    fun remove(id: String) {
        require(id.matches(FILE_NAME))
        check(File(directory, id).delete()) { "Cannot remove LUT" }
        prefs.edit().remove("name_$id").remove("intensity_$id").apply()
        if (selected == id) selected = OFF
    }

    companion object {
        const val PICK_REQUEST = 0x4c55
        const val OFF = "off"
        const val TECHNICAL = "technical709"
        private val FILE_NAME = Regex("[0-9a-f-]{36}\\.cube")
    }
}

internal fun MainActivity.pickDirectLogLut() {
    if (videoRecording) return
    @Suppress("DEPRECATION")
    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type = "*/*"
        addCategory(Intent.CATEGORY_OPENABLE)
    }, DirectLogLutLibrary.PICK_REQUEST)
}

internal fun MainActivity.handleDirectLogLutResult(resultCode: Int, data: Intent?) {
    val uri = data?.data ?: return
    if (resultCode != Activity.RESULT_OK) return
    Thread({
        val result = runCatching { DirectLogLutLibrary(this).import(uri) }
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                AlertDialog.Builder(this).setTitle(if (result.isSuccess) "LUT imported" else "LUT import failed")
                    .setMessage(if (result.isSuccess) "Selected for S-Log3. Enable LUT baking in Video settings to save the look in recorded files."
                        else result.exceptionOrNull()?.message ?: "Could not import LUT")
                    .setPositiveButton("OK", null).show()
            }
        }
    }, "LutImport").start()
}

internal fun MainActivity.chooseDirectLogMonitorLut() {
    if (videoRecording) return
    val library = DirectLogLutLibrary(this)
    val files = library.entries()
    val ids = listOf(DirectLogLutLibrary.OFF, DirectLogLutLibrary.TECHNICAL) + files.map { it.name }
    val labels = listOf("Off", "Technical BT.709 assist (clips highlights)") + files.map { library.title(it.name) }
    AlertDialog.Builder(this).setTitle("S-Log3 LUT selection")
        .setSingleChoiceItems(labels.toTypedArray(), ids.indexOf(library.selected)) { dialog, which ->
            library.selected = ids[which]
            dialog.dismiss()
        }.setNegativeButton("Close", null).show()
}

internal fun MainActivity.removeDirectLogMonitorLut() {
    if (videoRecording) return
    val library = DirectLogLutLibrary(this)
    val files = library.entries()
    AlertDialog.Builder(this).setTitle("Remove imported LUT")
        .setItems(files.map { library.title(it.name) }.toTypedArray()) { _, which ->
            library.remove(files[which].name)
        }.setNegativeButton("Cancel", null).show()
}
