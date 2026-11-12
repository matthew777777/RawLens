// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import com.matthew.rawlens.MainActivity.Companion.KEY_LENS_SETUP_COMPLETE

import com.matthew.rawlens.MainActivity.Companion.KEY_SELECTED_LENSES

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

// First-run and settings lens discovery and selection screens. Moved verbatim from MainActivity.

internal fun MainActivity.showLensDiscovery(firstRun: Boolean) {
    // First launch covers the whole camera UI (including the top bar)
    // with an opaque black overlay — a dialog window can't reliably do
    // that — while later invocations keep the floating progress dialog.
    var progressDialog: AlertDialog? = null
    var progressOverlay: View? = null
    if (firstRun) {
        // Already immersive from onCreate; the overlay just covers the UI.
        progressOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(getColor(R.color.surface))
            elevation = 32f
            isClickable = true
            isFocusableInTouchMode = true
            setPadding(dp(32), 0, dp(32), 0)
            addView(ProgressBar(this@showLensDiscovery).apply { isIndeterminate = true })
            addView(TextView(this@showLensDiscovery).apply {
                text = "Finding RAW lenses"
                setTextColor(getColor(R.color.text_primary))
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(8))
            })
            addView(TextView(this@showLensDiscovery).apply {
                text = "Checking every Camera2 ID for RAW support (rear and front).\nThis usually takes a few seconds…"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
                gravity = Gravity.CENTER
            })
        }.also { overlay ->
            blockBackKey(overlay)
            findViewById<ViewGroup>(android.R.id.content)
                .addView(overlay, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
            overlay.requestFocus()
        }
    } else {
        progressDialog = RawLensDialogBuilder(this)
            .setTitle("Finding RAW lenses")
            .setMessage("Checking every Camera2 ID for RAW support (rear and front).\nThis usually takes a few seconds…")
            .setCancelable(true)
            .show()
    }
    val verdicts = runCatching { lensVerdictStore.snapshot() }.getOrDefault(LensVerdicts.EMPTY)
    lensDiscovery.discover(verdicts) { lenses ->
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            progressDialog?.dismiss()
            progressOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
            progressOverlay = null
            if (lenses.isEmpty()) {
                // No selection page follows; stay fullscreen (the dialog
                // re-hides the bars via onWindowFocusChanged on dismiss).
                // The deferred first-run consent fires as this alert
                // leaves, so it never stacks over discovery either.
                RawLensDialogBuilder(this)
                    .setTitle("No RAW lenses found")
                    .setMessage("The camera service did not expose a RAW-capable camera ID.")
                    .setPositiveButton("OK", null)
                    .setOnDismissListener { updateFlow.showPendingConsent() }
                    .show()
                return@runOnUiThread
            }
            val missingSaved = selectedLensIds()
                .filterNot { savedId -> lenses.any { it.id == savedId } }
                .map { unavailableLens(it) }
            // First run stays fullscreen throughout: dismissing the
            // selection page re-applies immersive mode, never shows bars.
            showLensSelection(lenses + missingSaved, firstRun)
        }
    }
}

/** Overlay pages are modal: the back key must not escape onboarding. */
internal fun MainActivity.blockBackKey(view: View) {
    view.setOnKeyListener { _, keyCode, event ->
        keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP
    }
}

internal fun MainActivity.showLensSelection(lenses: List<DiscoveredLens>, firstRun: Boolean) {
    val selected = selectedLensIds()
    val groups = groupLenses(lenses)
    val opticCount = groupByOptic(lenses).count { it.key != "unavailable" }
    val routeCount = groups.sumOf { it.lenses.size }
    // Preserve saved choices; new routes remain unchecked.
    val checked = lenses.associate { lens ->
        lens.id to (lens.id in selected)
    }.toMutableMap()
    val checkBoxes = mutableListOf<CheckBox>()
    // Several routes can reach one physical lens. Name each route by its switcher zoom
    // and flag routes that share a lens, so the same lens is not picked several times.
    val available = lenses.filter { it.kind != LensRouteKind.UNAVAILABLE }
    val rearMetrics = available.filter { it.role != LensRole.FRONT }.map { it.opticalMetric }
    fun zoomOf(lens: DiscoveredLens): String =
        if (lens.role == LensRole.FRONT) "F" else LensZoom.label(lens.opticalMetric, rearMetrics)
    val sameLens: Map<String, List<DiscoveredLens>> = available.groupBy(::opticFingerprint)
        .values.flatMap { routes -> routes.map { it.id to routes } }.toMap()
    val recommended = recommendedLensIds(lenses)
    val duplicateNotes = mutableMapOf<String, TextView>()
    val selectionSummary = TextView(this).apply {
        textSize = 12f
        setPadding(0, 0, 0, dp(8))
    }
    fun refreshDuplicates() {
        var duplicates = 0
        duplicateNotes.forEach { (id, note) ->
            val others = sameLens[id].orEmpty().filter { it.id != id }
            val clashing = others.filter { checked[it.id] == true }
            val isChecked = checked[id] == true
            if (isChecked && clashing.isNotEmpty()) duplicates++
            note.text = when {
                others.isEmpty() -> ""
                isChecked && clashing.isNotEmpty() ->
                    "Same lens as ID ${clashing.joinToString(", ") { it.id }}, also checked: it will appear ${clashing.size + 1}× in the switcher"
                id in recommended -> "Recommended route for this lens · ${others.size} other route${if (others.size == 1) "" else "s"}"
                else -> "Another route to the same lens as ID ${(others.firstOrNull { it.id in recommended } ?: others.first()).id}"
            }
            note.setTextColor(getColor(if (isChecked && clashing.isNotEmpty()) R.color.accent else R.color.text_muted))
            note.visibility = if (others.isEmpty()) View.GONE else View.VISIBLE
        }
        val picked = available.filter { checked[it.id] == true }
        val pickedLenses = picked.map(::opticFingerprint).distinct().size
        selectionSummary.text = when {
            picked.isEmpty() -> "Nothing checked yet."
            duplicates > 0 -> "${picked.size} routes checked for $pickedLenses lens${if (pickedLenses == 1) "" else "es"}: some lenses are checked more than once. Tap “One per lens” to keep the recommended route of each."
            else -> "${picked.size} checked, one route per lens."
        }
        selectionSummary.setTextColor(getColor(if (duplicates > 0) R.color.accent else R.color.text_muted))
    }

    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
    }
    container.addView(TextView(this).apply {
        text = "$opticCount lens${if (opticCount == 1) "" else "es"} · $routeCount route${if (routeCount == 1) "" else "s"}"
        setTextColor(getColor(R.color.text_primary))
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
    })
    container.addView(TextView(this).apply {
        text = "Phones often expose one lens through several camera IDs (routes). Check one route per lens; each checked entry becomes a button in the viewfinder switcher, widest first."
        setTextColor(getColor(R.color.text_secondary))
        textSize = 12f
        setPadding(0, dp(4), 0, dp(8))
    })

    val quickRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 0, 0, dp(8))
    }
    fun styleQuickButton(button: Button) {
        RawLensUi.styleButton(button)
        button.maxLines = 1
        button.ellipsize = android.text.TextUtils.TruncateAt.END
    }
    val selectAll = Button(this).apply {
        text = "One per lens"
        styleQuickButton(this)
    }
    val clear = Button(this).apply {
        text = "Clear"
        styleQuickButton(this)
    }
    val selectParams = LinearLayout.LayoutParams(0, wrapContent(), 1f).apply { marginEnd = dp(8) }
    val clearParams = LinearLayout.LayoutParams(0, wrapContent(), 1f)
    quickRow.addView(selectAll, selectParams)
    quickRow.addView(clear, clearParams)
    container.addView(quickRow)
    container.addView(selectionSummary)
    // Secondary action as a plain text link: a third full-width chip
    // made the preset block dominate the screen.
    val copyReport = TextView(this).apply {
        text = "Copy lens report"
        setTextColor(getColor(R.color.accent))
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(0, dp(2), 0, dp(10))
    }
    container.addView(copyReport, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, wrapContent()
    ))
    copyReport.setOnClickListener {
        val report = lensReport(
            lenses,
            chosenSize = { rawSizePreferenceStore.get(it) },
            chosenFormat = { rawFormatPreferenceStore.get(it) }
        )
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            setStatus("COPY FAILED")
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("RawLens lens report", report))
            setStatus("LENS REPORT COPIED")
        }
    }

    val listScroll = ScrollView(this).apply {
        // Both presentations are full-screen pages: the list fills everything between
        // the header and the bottom action bar, so the actions pin to the bottom.
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        isFillViewport = true
        isVerticalScrollBarEnabled = false
    }
    val listBody = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(8))
    }
    listScroll.addView(listBody)

    fun refreshQuickState() {
        val values = checked.values
        selectAll.isEnabled = available.any { (checked[it.id] == true) != (it.id in recommended) }
        clear.isEnabled = values.any { it }
        selectAll.alpha = if (selectAll.isEnabled) 1f else 0.5f
        clear.alpha = if (clear.isEnabled) 1f else 0.5f
    }

    fun lensCard(lens: DiscoveredLens): LinearLayout {
        val dead = lens.verdict == LensVerdict.DEAD
        val dimmed = lens.kind != LensRouteKind.STANDALONE || dead
        val chip = when {
            dead -> "⚠ OPEN FAILED BEFORE"
            else -> null
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.control_chip)
            setPadding(dp(8), dp(10), dp(12), dp(10))
            val cardParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            layoutParams = cardParams
            alpha = if (dimmed) 0.72f else 1f
        }
        val box = SettingsCheckBox(this).apply {
            tag = lens.id
            isChecked = checked[lens.id] == true
            contentDescription = lens.label
        }
        checkBoxes += box
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, wrapContent(), 1f)
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(TextView(this).apply {
            text = if (lens.kind == LensRouteKind.UNAVAILABLE) lens.title else "${zoomOf(lens)} · ${lens.title}"
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, wrapContent(), 1f)
        })
        topRow.addView(TextView(this).apply {
            text = "ID ${lens.id}"
            setTextColor(getColor(R.color.text_muted))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), 0, 0, 0)
        })
        textCol.addView(topRow)
        textCol.addView(TextView(this).apply {
            text = lens.details.ifEmpty { lens.label }
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(2), 0, 0)
        })
        // One muted line: the route text already identifies physical versus logical,
        // so the kind chip only appears for the special states.
        val routeText = lens.route.ifEmpty { lens.kind.sectionTitle }
        textCol.addView(TextView(this).apply {
            text = if (chip != null) "$chip · $routeText" else routeText
            setTextColor(getColor(R.color.text_muted))
            textSize = 11f
            setPadding(0, dp(4), 0, 0)
        })
        if (lens.kind != LensRouteKind.UNAVAILABLE) {
            textCol.addView(TextView(this).apply {
                textSize = 11f
                setPadding(0, dp(4), 0, 0)
                duplicateNotes[lens.id] = this
            })
        }
        card.addView(box)
        card.addView(textCol)
        box.setOnCheckedChangeListener { _, enabled ->
            checked[lens.id] = enabled
            refreshQuickState()
            refreshDuplicates()
        }
        card.setOnClickListener { box.toggle() }
        return card
    }

    fun showRawFormatChoice(lens: DiscoveredLens, formatRow: TextView) {
        val formats = lens.packedFormats
        val items = (listOf("Auto (RAW_SENSOR)") + formats).toTypedArray()
        val stored = rawFormatPreferenceStore.get(lens.id)
        val checked = stored?.let { formats.indexOf(it) + 1 }?.takeIf { it > 0 } ?: 0
        RawLensDialogBuilder(this)
            .setTitle("RAW format · ID ${lens.id}")
            .setSingleChoiceItems(items, checked) { dialog, which ->
                val picked = if (which == 0) null else formats[which - 1]
                rawFormatPreferenceStore.set(lens.id, picked)
                setStatus(if (picked == null) "RAW FORMAT AUTO" else "RAW FORMAT $picked")
                formatRow.text = "RAW format: ${picked ?: "Auto"} ▾"
                controller.reopenCamera()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showRawSizeChoice(lens: DiscoveredLens, sizeRow: TextView) {
        val sizes = lens.rawSizes
        val items = (listOf("Auto (largest)") + sizes).toTypedArray()
        val stored = rawSizePreferenceStore.get(lens.id)
        val checked = stored?.let { wanted ->
            sizes.indexOfFirst { RawSizePreferenceStore.normalizeSize(it) == wanted } + 1
        }?.takeIf { it > 0 } ?: 0
        RawLensDialogBuilder(this)
            .setTitle("RAW size · ID ${lens.id}")
            .setSingleChoiceItems(items, checked) { dialog, which ->
                val picked = if (which == 0) null else sizes[which - 1]
                rawSizePreferenceStore.set(lens.id, picked)
                setStatus(if (picked == null) "RAW SIZE AUTO" else "RAW SIZE $picked")
                sizeRow.text = "RAW size: ${picked ?: "Auto"} ▾"
                controller.reopenCamera()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    var firstGroup = true
    groups.forEach { group ->
        listBody.addView(TextView(this).apply {
            text = "${group.title}\n${group.subtitle}"
            setTextColor(getColor(R.color.text_muted))
            textSize = 12f
            setPadding(0, if (firstGroup) dp(4) else dp(12), 0, dp(6))
        })
        firstGroup = false
        group.lenses.forEach { head ->
            listBody.addView(lensCard(head))
            if (head.sensorModeSwitch) {
                listBody.addView(TextView(this).apply {
                    text = "Sensor-mode switch advertised here — special modes need a per-lens vendor key in Settings."
                    setTextColor(getColor(R.color.text_muted))
                    textSize = 11f
                    setPadding(0, 0, 0, dp(8))
                })
            }
            if (head.packedFormats.isNotEmpty()) {
                listBody.addView(TextView(this).apply {
                    text = "Lists packed ${head.packedFormats.joinToString(" + ")} (info only — capture stays RAW_SENSOR)."
                    setTextColor(getColor(R.color.text_muted))
                    textSize = 11f
                    setPadding(0, 0, 0, dp(8))
                })
            }
            if (head.rawSizes.size > 1) {
                val stored = rawSizePreferenceStore.get(head.id)
                val sizeRow = TextView(this).apply {
                    text = "RAW size: ${stored ?: "Auto"} ▾"
                    setTextColor(getColor(R.color.accent))
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 0, dp(8))
                }
                sizeRow.setOnClickListener { showRawSizeChoice(head, sizeRow) }
                listBody.addView(sizeRow)
            }
            // Packed capture is strictly opt-in: the row appears only
            // where the map lists packed formats, defaulting to Auto
            // (RAW_SENSOR) like every fresh install.
            if (head.packedFormats.isNotEmpty()) {
                val stored = rawFormatPreferenceStore.get(head.id)
                val formatRow = TextView(this).apply {
                    text = "RAW format: ${stored ?: "Auto"} ▾"
                    setTextColor(getColor(R.color.accent))
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 0, dp(8))
                }
                formatRow.setOnClickListener { showRawFormatChoice(head, formatRow) }
                listBody.addView(formatRow)
            }
        }
    }
    container.addView(listScroll)
    container.addView(TextView(this).apply {
        text = "Use direct physical IDs first. Greyed routes remain selectable for features unavailable directly, such as Xiaomi sensor modes or zoom."
        setTextColor(getColor(R.color.text_muted))
        textSize = 11f
        setPadding(0, dp(8), 0, dp(4))
    })

    selectAll.setOnClickListener {
        // One route per physical lens (the recommended one); saved-but-missing entries keep their state.
        available.forEach { checked[it.id] = it.id in recommended }
        checkBoxes.forEach { box -> (box.tag as? String)?.let { id -> checked[id]?.let { box.isChecked = it } } }
        refreshQuickState()
        refreshDuplicates()
    }
    clear.setOnClickListener {
        checked.keys.forEach { checked[it] = false }
        checkBoxes.forEach { it.isChecked = false }
        refreshQuickState()
        refreshDuplicates()
    }
    refreshQuickState()
    refreshDuplicates()

    // First initial setup: dedicated full-screen black page (welcome
    // continuity) that scales to the bottom — the list fills the window
    // and Save / Use default pin to the bottom. Gone on either tap.
    // Later opens keep the floating panel below.
    if (firstRun) {
        container.setPadding(0, 0, 0, 0)
        fun bottomAction(label: String, primary: Boolean): Button = Button(this).apply {
            text = label
            RawLensUi.styleButton(this, primary)
        }
        val saveButton = bottomAction("Save", primary = true)
        val defaultButton = bottomAction("Use default", primary = false)
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        bottomBar.addView(
            defaultButton,
            LinearLayout.LayoutParams(0, wrapContent(), 1f).apply { marginEnd = dp(8) }
        )
        bottomBar.addView(saveButton, LinearLayout.LayoutParams(0, wrapContent(), 1f))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.surface))
            elevation = 32f
            isClickable = true
            isFocusableInTouchMode = true
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        root.addView(TextView(this).apply {
            text = "Add RAW lenses"
            setTextColor(getColor(R.color.text_primary))
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(
            container,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        val saveHint = TextView(this).apply {
            text = "Select at least one lens to continue"
            setTextColor(getColor(R.color.danger))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        root.addView(saveHint)
        root.addView(bottomBar)
        blockBackKey(root)
        findViewById<ViewGroup>(android.R.id.content).addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.requestFocus()
        fun dismissPage() {
            (root.parent as? ViewGroup)?.removeView(root)
            // Used to showStatusBar() here: first install kept the
            // notification bar until relaunch. Stay fullscreen instead.
            ImmersiveMode.apply(window)
            // First run ends here: the deferred keep-up-to-date ask
            // gets its own moment now that discovery is off screen.
            updateFlow.showPendingConsent()
        }
        saveButton.setOnClickListener {
            val ids = checked.filterValues { it }.keys.toMutableSet()
            if (ids.isEmpty()) {
                saveHint.visibility = View.VISIBLE
                return@setOnClickListener
            }
            lensPreferences().edit()
                .putStringSet(KEY_SELECTED_LENSES, ids)
                .putBoolean(KEY_LENS_SETUP_COMPLETE, true)
                .apply()
            controller.reloadLenses()
            setStatus("${ids.size} RAW LENS${if (ids.size == 1) "" else "ES"} ADDED")
            dismissPage()
        }
        defaultButton.setOnClickListener {
            lensPreferences().edit().putBoolean(KEY_LENS_SETUP_COMPLETE, true).apply()
            dismissPage()
        }
        return
    }

    // Later opens: a full-screen page like the rest of Settings (back arrow and title on
    // top, Cancel / Save pinned to the bottom) instead of a floating panel.
    val dialog = android.app.Dialog(this, R.style.Theme_RawLens)
    val backButton = TextView(this).apply {
        text = "←"
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(getColor(R.color.text_primary))
        contentDescription = "Back"
        isClickable = true
        isFocusable = true
        setOnClickListener { dialog.dismiss() }
    }
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(4))
        addView(backButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(TextView(context).apply {
            text = "Lenses"
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(getColor(R.color.text_primary))
            isAccessibilityHeading = true
            setPadding(dp(12), 0, dp(4), 0)
        }, LinearLayout.LayoutParams(0, wrapContent(), 1f))
    }
    val saveButton = Button(this).apply { text = "Save"; RawLensUi.styleButton(this, true) }
    val cancelButton = Button(this).apply { text = "Cancel"; RawLensUi.styleButton(this, false) }
    val bottomBar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(20), dp(12), dp(20), dp(20))
        addView(cancelButton, LinearLayout.LayoutParams(0, wrapContent(), 1f).apply { marginEnd = dp(8) })
        addView(saveButton, LinearLayout.LayoutParams(0, wrapContent(), 1f))
    }
    val page = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(getColor(R.color.surface))
        addView(header)
        addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(bottomBar)
    }
    RawLensUi.showFullscreen(dialog, page)
    saveButton.setOnClickListener {
        val ids = checked.filterValues { it }.keys.toMutableSet()
        if (ids.isEmpty()) {
            setStatus("PICK A RAW LENS")
            return@setOnClickListener
        }
        lensPreferences().edit()
            .putStringSet(KEY_SELECTED_LENSES, ids)
            .putBoolean(KEY_LENS_SETUP_COMPLETE, true)
            .apply()
        controller.reloadLenses()
        setStatus("${ids.size} RAW LENS${if (ids.size == 1) "" else "ES"} ADDED")
        dialog.dismiss()
    }
    cancelButton.setOnClickListener {
        dialog.dismiss()
    }
}
