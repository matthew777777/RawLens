// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import com.matthew.rawlens.MainActivity.Companion.MAX_VENDOR_DISCOVERY_ROWS

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.PowerManager
import android.net.Uri
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.particlesdevs.photoncamera.processing.ml.FlowNetNcnnProcessor
import com.particlesdevs.photoncamera.processing.ml.RawNindNcnnProcessor
import java.io.File
import java.util.Locale
import android.os.Build
import android.os.SystemClock

// Vendor (HAL) key editors: entries, discovery, operating mode, presets, import/export. Moved verbatim from MainActivity.

/** Settings chip style shared by the panel and the vendor dialogs. */
internal fun MainActivity.styleSettingsButton(button: Button, active: Boolean = false) {
    button.background = getDrawable(
        if (active) R.drawable.control_chip_active else R.drawable.control_chip
    )
    button.setTextColor(
        getColor(if (active) R.color.accent_dark else R.color.text_primary)
    )
    button.textSize = 12f
    button.isAllCaps = false
    button.setPadding(dp(14), dp(10), dp(14), dp(10))
}

/** Accent section header matching the lens-picker groups. */
internal fun MainActivity.vendorSectionHeader(text: String): TextView = TextView(this).apply {
    this.text = text.uppercase(Locale.US)
    setTextColor(getColor(R.color.accent))
    textSize = 12f
    typeface = Typeface.DEFAULT_BOLD
    letterSpacing = 0.1f
    setPadding(0, dp(12), 0, dp(4))
}

/** Full-width chip action button. */
internal fun MainActivity.vendorActionButton(label: String, action: () -> Unit): Button =
    Button(this).apply {
        text = label
        styleSettingsButton(this)
        setOnClickListener { action() }
    }

/** Two-line chip row: bold title on top, secondary detail below, left-aligned. */
internal fun MainActivity.twoLineChipButton(title: String, detail: String, active: Boolean, action: () -> Unit): Button =
    Button(this).apply {
        val full = "$title\n$detail"
        text = SpannableString(full).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        styleSettingsButton(this, active)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        minHeight = dp(56)
        setOnClickListener { action() }
    }

/** Key row: bold name on top, type • scope • value below. */
internal fun MainActivity.vendorEntryButton(entry: VendorKeyEntry, action: () -> Unit): Button =
    twoLineChipButton(
        entry.name.trim(),
        "${entry.type.label} • ${entry.scope.id} • " +
            entry.value.trim().ifEmpty { "—" } +
            if (entry.enabled) "" else " • off",
        entry.enabled,
        action
    )

/** Per-lens vendor key/tag editor: session-early keys plus per-request keys. */
internal fun MainActivity.showVendorKeysEditor() {
    val cameraId = controller.activeCameraId()
    if (cameraId == null) {
        setStatus("WAIT FOR CAMERA")
        return
    }
    val profile = vendorKeyStore.get(cameraId)
    val onCount = profile.activeEntries().size
    var editor: AlertDialog? = null
    fun edit(entry: VendorKeyEntry?) {
        editor?.dismiss()
        showVendorKeyEditDialog(cameraId, entry) { showVendorKeysEditor() }
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), 0, dp(20), 0)
        addView(TextView(this@showVendorKeysEditor).apply {
            text = "Camera $cameraId • ${profile.entries.size} keys ($onCount on)\n" +
                "Session keys load before the session opens. " +
                "Request keys ride every capture. Black/white levels are not camera " +
                "keys — set them under Sensor levels."
            setTextColor(getColor(R.color.text_secondary)); textSize = 12f
            setPadding(0, 0, 0, dp(8))
        })
        fun reportLine(name: String, report: VendorKeyApplyReport?): String? =
            report?.takeIf { it.applied.isNotEmpty() || it.skipped.isNotEmpty() || it.detail != null }?.let {
                "$name ${it.applied.size} accepted / ${it.skipped.size} skipped" +
                    (if (it.skipped.isNotEmpty()) " (${it.skipped.take(2).joinToString()})" else "") +
                    it.detail?.let { detail -> "\n$detail" }.orEmpty()
            }
        val (lastSession, lastRequest) = controller.vendorLastApply()
        val applySummary = listOfNotNull(
            reportLine("Session", lastSession), reportLine("Request", lastRequest)
        ).joinToString("\n")
        if (applySummary.isNotEmpty()) {
            addView(TextView(this@showVendorKeysEditor).apply {
                text = "Last builder/session result\n$applySummary"
                setTextColor(getColor(R.color.text_secondary)); textSize = 12f
                setPadding(0, 0, 0, dp(8))
            })
        }
        if (controller.vendorSessionNeedsReopen()) {
            addView(TextView(this@showVendorKeysEditor).apply {
                text = "Saved session settings differ from the active session — reopen to retry."
                setTextColor(getColor(R.color.danger)); textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(8))
            })
        }
    }
    if (profile.entries.isNotEmpty()) {
        content.addView(vendorSectionHeader("Your keys"))
    }
    profile.entries.sortedBy { it.name }.forEach { entry ->
        content.addView(vendorEntryButton(entry) { edit(entry) })
    }
    content.addView(vendorSectionHeader("Add"))
    content.addView(vendorActionButton("Add key") { edit(null) })
    if (!profile.isEmpty()) {
        content.addView(vendorActionButton("Save current as preset") {
            showPresetNameDialog { name ->
                try {
                    vendorKeyStore.savePreset(name, profile)
                    setStatus("PRESET SAVED")
                } catch (failure: Exception) {
                    setStatus("PRESET NOT SAVED")
                }
            }
        })
    }
    val presets = vendorKeyStore.listPresets()
    if (presets.isNotEmpty()) {
        content.addView(vendorActionButton("Apply preset") {
            showPresetPicker(presets, "Apply preset") { name ->
                val incoming = vendorKeyStore.getPreset(name)
                if (updateVendorKeys(cameraId) { it.mergedWith(incoming) }) {
                    editor?.dismiss()
                    showVendorKeysEditor()
                }
            }
        })
        content.addView(vendorActionButton("Delete preset") {
            showPresetPicker(presets, "Delete preset") { name ->
                vendorKeyStore.deletePreset(name)
                setStatus("PRESET DELETED")
                editor?.dismiss()
                showVendorKeysEditor()
            }
        })
    }
    if (VendorKeyProfile.presets().entries.none { preset -> profile.entries.any { it.name == preset.name } }) {
        content.addView(vendorActionButton("Add Vivo DCG preset (off)") {
            val missing = VendorKeyProfile.presets().entries
                .filter { preset -> profile.entries.none { it.name == preset.name } }
            if (updateVendorKeys(cameraId) { current -> current.mergedWith(VendorKeyProfile(missing)) }) {
                editor?.dismiss()
                showVendorKeysEditor()
            }
        })
    }
    content.addView(vendorSectionHeader("Lens"))
    content.addView(vendorActionButton("Keys advertised on this lens") {
        editor?.dismiss()
        showVendorDiscoveryDialog(cameraId)
    })
    content.addView(vendorActionButton("Reopen camera (applies session keys)") {
        controller.reopenCamera()
        setStatus("REOPENING")
    })
    val opmodeDetail = if (profile.operatingMode == 0) "Default (0)" else "${profile.operatingMode} — custom"
    content.addView(twoLineChipButton("Session operating mode", opmodeDetail, profile.operatingMode != 0) {
        showOperatingModeDialog(cameraId, profile) {
            editor?.dismiss()
            showVendorKeysEditor()
        }
    })
    content.addView(vendorSectionHeader("Share"))
    content.addView(vendorActionButton("Export keys (copy text)") {
        showVendorExportDialog(profile)
    })
    content.addView(vendorActionButton("Import keys") {
        editor?.dismiss()
        showVendorImportDialog(cameraId)
    })
    editor = AlertDialog.Builder(this).setTitle("Vendor keys • $cameraId")
        .setView(ScrollView(this).apply { addView(content) })
        .setNeutralButton("Reset all", null).setNegativeButton("Close", null)
        .show().also { dialog ->
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                vendorKeyStore.clear(cameraId)
                controller.refreshVendorKeys()
                dialog.dismiss()
                setStatus("VENDOR RESET")
            }
        }
}

internal fun MainActivity.showVendorKeyEditDialog(
    cameraId: String,
    existing: VendorKeyEntry?,
    prefillName: String? = null,
    prefillScope: VendorKeyScope? = null,
    onSaved: () -> Unit
) {
    var type = existing?.type ?: VendorKeyType.INT
    var scope = existing?.scope ?: prefillScope ?: VendorKeyScope.REQUEST
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), 0, dp(20), 0)
        addView(TextView(this@showVendorKeyEditDialog).apply {
            text = "Names (section.key) or numeric tags (0x…). Scalars take one " +
                "number or true/false; arrays take comma-separated values."
            setTextColor(getColor(R.color.text_secondary)); textSize = 12f
        })
    }
    container.addView(vendorSectionHeader("Name"))
    val nameInput = EditText(this).apply {
        hint = "section.key or 0x tag"
        setText(existing?.name ?: prefillName ?: "")
        setSelectAllOnFocus(true)
    }
    container.addView(nameInput)
    val suggestionsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    container.addView(suggestionsBox)
    container.addView(vendorSectionHeader("Type"))
    val typeButton = Button(this).apply { isAllCaps = false }
    fun refreshType() {
        typeButton.text = type.label
        styleSettingsButton(typeButton, active = true)
    }
    refreshType()
    typeButton.setOnClickListener {
        val labels = VendorKeyType.entries.map { it.label }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Value type")
            .setSingleChoiceItems(labels, type.ordinal) { choice, which ->
                type = VendorKeyType.entries[which]
                refreshType()
                choice.dismiss()
            }
            .setNegativeButton("Cancel", null).show()
    }
    container.addView(typeButton)
    container.addView(vendorSectionHeader("Scope"))
    val scopeButton = Button(this).apply { isAllCaps = false }
    fun refreshScope() {
        scopeButton.text = if (scope == VendorKeyScope.SESSION) "Session (before open)" else "Request (every capture)"
        styleSettingsButton(scopeButton, active = true)
    }
    refreshScope()
    scopeButton.setOnClickListener {
        val options = VendorKeyScope.entries.toTypedArray()
        val labels = options.map {
            if (it == VendorKeyScope.SESSION) "Session (before open)" else "Request (every capture)"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Scope")
            .setSingleChoiceItems(labels, scope.ordinal) { choice, which ->
                scope = options[which]
                refreshScope()
                choice.dismiss()
            }
            .setNegativeButton("Cancel", null).show()
    }
    container.addView(scopeButton)
    // Autocomplete: suggests advertised keys matching what is typed so far.
    val advertisedKeys = controller.vendorKeyDiscovery()
    fun refreshSuggestions() {
        suggestionsBox.removeAllViews()
        val query = nameInput.text.toString().trim().lowercase()
        if (query.length < 2) return
        advertisedKeys
            .filter { it.name.lowercase().contains(query) && it.name.trim().lowercase() != query }
            .take(5)
            .forEach { found ->
                suggestionsBox.addView(
                    twoLineChipButton(
                        found.name,
                        "${found.scopeHint.id}" +
                            found.tagHex?.let { " • $it" }.orEmpty() +
                            " • tap to use",
                        false
                    ) {
                        nameInput.setText(found.name)
                        scope = found.scopeHint
                        refreshScope()
                        suggestionsBox.removeAllViews()
                    }
                )
            }
    }
    nameInput.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) = refreshSuggestions()
    })
    container.addView(vendorSectionHeader("Value"))
    val valueInput = EditText(this).apply {
        hint = "1, or 64, 64, 64, 64"
        setText(existing?.value ?: "")
        setSelectAllOnFocus(true)
    }
    container.addView(valueInput)
    val enabledBox = CheckBox(this).apply {
        text = "Enabled"
        isChecked = existing?.enabled ?: true
        setTextColor(getColor(R.color.text_primary))
    }
    container.addView(enabledBox)
    val probeLine = TextView(this).apply {
        setTextColor(getColor(R.color.text_secondary)); textSize = 12f
        setPadding(0, dp(4), 0, 0)
    }
    container.addView(vendorActionButton("Test on this lens") {
        val candidate = try {
            VendorKeyEntry(
                nameInput.text.toString().trim(), type, scope,
                valueInput.text.toString(), enabledBox.isChecked
            ).also(VendorKeyEntry::validate)
        } catch (failure: IllegalArgumentException) {
            probeLine.text = "Fix the fields first (${failure.message})"
            probeLine.setTextColor(getColor(R.color.danger))
            return@vendorActionButton
        }
        probeLine.text = "Testing…"
        probeLine.setTextColor(getColor(R.color.text_secondary))
        controller.probeVendorKey(candidate) { result ->
            runOnUiThread {
                probeLine.text = (if (result.supported) "Accepted: " else "Not accepted: ") + result.detail
                probeLine.setTextColor(getColor(if (result.supported) R.color.accent else R.color.danger))
            }
        }
    })
    container.addView(probeLine)
    val dialog = AlertDialog.Builder(this)
        .setTitle(if (existing == null) "Add vendor key" else "Edit vendor key")
        .setView(ScrollView(this).apply { addView(container) })
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Save", null)
        .apply { if (existing != null) setNeutralButton("Delete", null) }
        .show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val name = nameInput.text.toString().trim()
        if (VendorKeyApply.isLevelTag(name)) {
            nameInput.error = "Not a camera key — set levels under Sensor levels"
            return@setOnClickListener
        }
        val candidate = try {
            VendorKeyEntry(name, type, scope, valueInput.text.toString(), enabledBox.isChecked)
                .also(VendorKeyEntry::validate)
        } catch (failure: IllegalArgumentException) {
            val message = failure.message ?: "Invalid key"
            if ("name" in message.lowercase()) nameInput.error = message
            else valueInput.error = message
            return@setOnClickListener
        }
        if (updateVendorKeys(cameraId) { current ->
            val merged = (current.entries.filter { it.identity() != candidate.identity() &&
                (existing == null || it.identity() != existing.identity()) } + candidate).sortedBy { it.name }
            current.copy(entries = merged)
        }) {
            dialog.dismiss()
            onSaved()
        }
    }
    if (existing != null) {
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            if (updateVendorKeys(cameraId) { current ->
                current.copy(entries = current.entries.filter { it.identity() != existing.identity() })
            }) {
                dialog.dismiss()
                onSaved()
            }
        }
    }
}

/** Advertised keys for the open lens; tapping one starts an add prefilled with it. */
internal fun MainActivity.showVendorDiscoveryDialog(cameraId: String) {
    val keys = controller.vendorKeyDiscovery()
    val added = vendorKeyStore.get(cameraId).entries.map { it.name.trim() }.toSet()
    var list: AlertDialog? = null
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), 0, dp(20), 0)
    }
    val countLine = TextView(this).apply {
        setTextColor(getColor(R.color.text_secondary)); textSize = 12f
        setPadding(0, 0, 0, dp(8))
    }
    body.addView(countLine)
    val queryInput = EditText(this).apply {
        hint = "Search (e.g. com.mediatek, bpp)"
        setSelectAllOnFocus(true)
        setSingleLine()
    }
    body.addView(queryInput)
    val rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    body.addView(rowsBox)
    fun refreshRows() {
        val query = queryInput.text.toString().trim().lowercase()
        val matches = if (query.isEmpty()) keys else keys.filter {
            it.name.lowercase().contains(query) ||
                it.scopeHint.id.contains(query) ||
                it.source.lowercase().contains(query)
        }
        countLine.text = when {
            keys.isEmpty() -> "Nothing advertised yet — wait for the camera."
            query.isEmpty() -> "${keys.size} keys advertised on $cameraId. Tap one to add it."
            else -> "${matches.size} of ${keys.size} keys match."
        }
        rowsBox.removeAllViews()
        // Cap the rows so a tag-heavy HAL cannot blow up the dialog.
        matches.take(MAX_VENDOR_DISCOVERY_ROWS).forEach { found ->
            rowsBox.addView(
                twoLineChipButton(
                    found.name,
                    "${found.scopeHint.id} • ${found.source}" +
                        found.tagHex?.let { " • $it" }.orEmpty() +
                        if (found.name in added) " • added" else "",
                    found.name in added
                ) {
                    list?.dismiss()
                    showVendorKeyEditDialog(cameraId, null, found.name, found.scopeHint) {
                        showVendorKeysEditor()
                    }
                }
            )
        }
        if (matches.size > MAX_VENDOR_DISCOVERY_ROWS) {
            rowsBox.addView(TextView(this).apply {
                text = "…and ${matches.size - MAX_VENDOR_DISCOVERY_ROWS} more — narrow the search."
                setTextColor(getColor(R.color.text_secondary)); textSize = 12f
            })
        }
    }
    queryInput.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) = refreshRows()
    })
    refreshRows()
    list = AlertDialog.Builder(this).setTitle("Advertised keys • $cameraId")
        .setView(ScrollView(this).apply { addView(body) })
        .setNegativeButton("Close", null).show()
}

internal fun MainActivity.showOperatingModeDialog(cameraId: String, profile: VendorKeyProfile, onSaved: () -> Unit) {
    val opmodeInput = EditText(this).apply {
        hint = "0 = default; use a device-verified value"
        setText(profile.operatingMode.toString())
        inputType = InputType.TYPE_CLASS_NUMBER
        setSelectAllOnFocus(true)
    }
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), 0, dp(20), 0)
        addView(TextView(this@showOperatingModeDialog).apply {
            text = "Non-zero opens the session through the vendor operating-mode " +
                "path. Immediate API failures fall back to " +
                "the standard session; configuration may still fail later. " +
                "Session keys travel with the mode. Takes effect after reopen."
            setTextColor(getColor(R.color.text_secondary)); textSize = 12f
        })
        addView(opmodeInput)
    }
    val dialog = AlertDialog.Builder(this).setTitle("Session operating mode")
        .setView(ScrollView(this).apply { addView(container) })
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Save", null).show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val opmode = try {
            VendorKeyProfile.parseOperatingMode(opmodeInput.text.toString())
        } catch (failure: IllegalArgumentException) {
            opmodeInput.error = failure.message
            return@setOnClickListener
        }
        if (updateVendorKeys(cameraId) { it.copy(operatingMode = opmode) }) {
            dialog.dismiss()
            onSaved()
        }
    }
}

internal fun MainActivity.showPresetNameDialog(onName: (String) -> Unit) {
    val nameInput = EditText(this).apply {
        hint = "Preset name (e.g. 14-bit MediaTek)"
        setSelectAllOnFocus(true)
    }
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), 0, dp(20), 0)
        addView(nameInput)
    }
    val dialog = AlertDialog.Builder(this).setTitle("Save preset")
        .setView(container)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Save", null).show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        try {
            VendorKeyStore.cleanPresetName(nameInput.text.toString())
        } catch (failure: IllegalArgumentException) {
            nameInput.error = failure.message
            return@setOnClickListener
        }
        dialog.dismiss()
        onName(nameInput.text.toString())
    }
}

internal fun MainActivity.showPresetPicker(presets: List<String>, title: String, onPick: (String) -> Unit) {
    AlertDialog.Builder(this).setTitle(title)
        .setSingleChoiceItems(presets.toTypedArray(), -1) { choice, which ->
            choice.dismiss()
            onPick(presets[which])
        }
        .setNegativeButton("Cancel", null).show()
}

internal fun MainActivity.showVendorExportDialog(profile: VendorKeyProfile) {
    if (profile.isEmpty()) {
        setStatus("NOTHING TO EXPORT")
        return
    }
    val payload = profile.toJson()
    val pasteBox = EditText(this).apply {
        setText(payload)
        setSelectAllOnFocus(true)
    }
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), 0, dp(20), 0)
        addView(TextView(this@showVendorExportDialog).apply {
            text = "Copy this text to share the key set with another phone."
            setTextColor(getColor(R.color.text_secondary)); textSize = 12f
        })
        addView(pasteBox)
    }
    val dialog = AlertDialog.Builder(this).setTitle("Export keys")
        .setView(ScrollView(this).apply { addView(container) })
        .setNegativeButton("Close", null)
        .setPositiveButton("Copy", null).show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("rawlens-vendor-keys", payload)
        )
        dialog.dismiss()
        setStatus("KEYS COPIED")
    }
}

internal fun MainActivity.showVendorImportDialog(cameraId: String) {
    val resultLine = TextView(this).apply {
        setTextColor(getColor(R.color.text_secondary)); textSize = 12f
    }
    val pasteBox = EditText(this).apply {
        hint = "Paste an exported key set here"
    }
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), 0, dp(20), 0)
        addView(TextView(this@showVendorImportDialog).apply {
            text = "Adds missing keys. Keeps this lens’s nonzero operating mode; otherwise imports the shared mode."
            setTextColor(getColor(R.color.text_secondary)); textSize = 12f
        })
        addView(pasteBox)
        addView(resultLine)
    }
    val dialog = AlertDialog.Builder(this).setTitle("Import keys")
        .setView(ScrollView(this).apply { addView(container) })
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Import", null).show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val incoming = try {
            VendorKeyProfile.fromJson(pasteBox.text.toString(), strict = true).also(VendorKeyProfile::validate)
        } catch (failure: IllegalArgumentException) {
            resultLine.text = "Not a key set (${failure.message})"
            resultLine.setTextColor(getColor(R.color.danger))
            return@setOnClickListener
        }
        if (incoming.isEmpty()) {
            resultLine.text = "The pasted text holds no valid keys."
            resultLine.setTextColor(getColor(R.color.danger))
            return@setOnClickListener
        }
        if (updateVendorKeys(cameraId) { it.mergedWith(incoming) }) {
            dialog.dismiss()
            showVendorKeysEditor()
        }
    }
}

internal fun MainActivity.updateVendorKeys(
    cameraId: String,
    edit: (VendorKeyProfile) -> VendorKeyProfile
): Boolean {
    val profile = try {
        vendorKeyStore.update(cameraId, edit)
    } catch (failure: Exception) {
        setStatus("VENDOR NOT SAVED: ${failure.message.orEmpty()}")
        return false
    }
    controller.refreshVendorKeys()
    setStatus(if (profile.isEmpty()) "VENDOR OFF" else "VENDOR SAVED")
    return true
}
