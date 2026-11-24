// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

internal const val PHOTO_LUT_PICK_REQUEST = 0x504c

/** Shares imported files with video; photos have their own global selection. */
internal class PhotoLutSelection(context: Context) {
    val library = EncodedVideoLutLibrary(context)
    private val prefs = context.applicationContext.getSharedPreferences("photo_lut", Context.MODE_PRIVATE)
    private var loadedId: String? = null
    private var loaded: CubeLut? = null
    private var request = 0

    fun nextRequest(): Int = ++request
    fun isLatest(request: Int): Boolean = request == this.request

    val selected: String get() = prefs.getString("selected", EncodedVideoLutLibrary.OFF) ?: EncodedVideoLutLibrary.OFF

    fun current(): PhotoLutLook? {
        val id = selected
        if (id != loadedId) {
            loaded = runCatching { library.load(id)?.also { it.rgbaHalfBuffer() } }.onFailure {
                Log.w("PhotoLut", "Could not load selected cube", it)
            }.getOrNull()
            loadedId = id
        }
        return look(id, loaded)
    }

    fun look(id: String, cube: CubeLut?): PhotoLutLook? {
        if (cube == null) return null
        fun read(key: String) = prefs.getInt("adjust.$id.$key", 0).coerceIn(-100, 100)
        return PhotoLutLook(cube, PhotoLutAdjustments(
            saturation = read("saturation"),
            contrast = read("contrast"),
            shadows = read("shadows"),
            highlights = read("highlights"),
            warmth = read("warmth"),
            tint = read("tint"),
            vibrance = read("vibrance"),
        ))
    }

    fun commit(id: String, look: PhotoLutLook?) {
        loadedId = id
        loaded = look?.cube
        val edit = prefs.edit().putString("selected", id)
        look?.adjustments?.let { a ->
            edit.putInt("adjust.$id.saturation", a.saturation)
                .putInt("adjust.$id.contrast", a.contrast)
                .putInt("adjust.$id.shadows", a.shadows)
                .putInt("adjust.$id.highlights", a.highlights)
                .putInt("adjust.$id.warmth", a.warmth)
                .putInt("adjust.$id.tint", a.tint)
                .putInt("adjust.$id.vibrance", a.vibrance)
        }
        edit.apply()
    }
}

internal fun MainActivity.showPhotoLutSettings() {
    val library = photoLuts.library
    val files = library.entries()
    val ids = listOf(EncodedVideoLutLibrary.OFF) + files.map { it.name }
    val labels = listOf("Off") + files.map { library.title(it.name) }
    val builder = RawLensDialogBuilder(this).setTitle("Photo LUT")
        .setSingleChoiceItems(labels.toTypedArray(), ids.indexOf(photoLuts.selected)) { dialog, which ->
            dialog.dismiss()
            updatePhotoLut { ids[which] to library.load(ids[which]) }
        }
        .setNeutralButton("Import .cube") { _, _ ->
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, PHOTO_LUT_PICK_REQUEST)
        }
        .setNegativeButton("Close", null)
    val id = photoLuts.selected
    photoLuts.current()?.let { look ->
        builder.setPositiveButton("Adjust") { _, _ -> showPhotoLutAdjustments(id, look) }
    }
    builder.show()
}

private fun MainActivity.showPhotoLutAdjustments(id: String, look: PhotoLutLook) {
    val inset = RawLensUi.dp(this, 16)
    val fields = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(inset, 0, inset, inset)
    }
    val sliders = mutableListOf<SeekBar>()
    fun slider(title: String, initial: Int): SeekBar {
        fun valueText(progress: Int): String {
            val value = progress - 100
            return "$title ${if (value > 0) "+" else ""}$value"
        }
        val label = TextView(this).apply {
            text = valueText(initial + 100)
            setTextColor(getColor(R.color.text_primary))
        }
        fields.addView(label)
        return SeekBar(this).apply {
            max = 200
            progress = initial + 100
            contentDescription = label.text
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    label.text = valueText(progress)
                    bar.contentDescription = label.text
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
            fields.addView(this)
            sliders.add(this)
        }
    }
    val a = look.adjustments
    val saturation = slider("Saturation", a.saturation)
    val contrast = slider("Contrast", a.contrast)
    val shadows = slider("Shadows", a.shadows)
    val highlights = slider("Highlights", a.highlights)
    val warmth = slider("White balance warmth", a.warmth)
    val tint = slider("White balance tint", a.tint)
    val vibrance = slider("Vibrance", a.vibrance)
    val dialog = RawLensDialogBuilder(this).setTitle("Adjust ${look.title}")
        .setView(ScrollView(this).apply { addView(fields) })
        .setNeutralButton("Reset", null)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Apply", null)
        .show()
    dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
        sliders.forEach { it.progress = 100 }
    }
    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val adjusted = look.copy(adjustments = PhotoLutAdjustments(
            saturation = saturation.progress - 100,
            contrast = contrast.progress - 100,
            shadows = shadows.progress - 100,
            highlights = highlights.progress - 100,
            warmth = warmth.progress - 100,
            tint = tint.progress - 100,
            vibrance = vibrance.progress - 100,
        ))
        if (controller.setJpegOutputSettings(jpegOutputSettings().copy(photoLut = adjusted))) {
            photoLuts.nextRequest()
            photoLuts.commit(id, adjusted)
            dialog.dismiss()
        } else {
            android.widget.Toast.makeText(this, "Try again after saving finishes", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

internal fun MainActivity.handlePhotoLutResult(resultCode: Int, data: Intent?) {
    if (resultCode != Activity.RESULT_OK) return
    val uri = data?.data ?: return
    updatePhotoLut { photoLuts.library.import(uri, selectForVideo = false) }
}

private fun MainActivity.updatePhotoLut(select: () -> Pair<String, CubeLut?>) {
    val selection = photoLuts
    val request = selection.nextRequest()
    Thread({
        val result = runCatching {
            select().also { (_, lut) -> lut?.rgbaHalfBuffer() }
        }
        runOnUiThread {
            if (isFinishing || isDestroyed || !selection.isLatest(request)) return@runOnUiThread
            result.fold(onSuccess = { (id, lut) ->
                val look = selection.look(id, lut)
                if (controller.setJpegOutputSettings(jpegOutputSettings().copy(photoLut = look))) {
                    selection.commit(id, look)
                    if (settingsDialog?.isShowing == true) showSettings(1)
                } else {
                    setStatus("SETTINGS AFTER SAVES")
                }
            }, onFailure = { error ->
                RawLensDialogBuilder(this).setTitle("Could not load photo LUT")
                    .setMessage(error.message).setPositiveButton("OK", null).show()
            })
        }
    }, "PhotoLutLoad").start()
}
