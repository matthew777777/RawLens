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
import android.util.Log
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
            setBackgroundColor(Color.BLACK)
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
                text = "Checking every Camera2 ID for rear RAW support.\nThis usually takes a few seconds…"
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
        progressDialog = AlertDialog.Builder(this)
            .setTitle("Finding RAW lenses")
            .setMessage("Checking every Camera2 ID for rear RAW support.\nThis usually takes a few seconds…")
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
                AlertDialog.Builder(this)
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
    val groups = groupByOptic(lenses)
    val opticCount = groups.count { it.key != "unavailable" }
    val routeCount = groups.sumOf { it.lenses.size }
    // Nothing is pre-checked, including on first launch: the user
    // explicitly picks what they want. The Recommended button checks
    // the suggested set in one tap.
    val recommended = recommendedLensIds(lenses)
    val checked = lenses.associate { lens ->
        lens.id to (lens.id in selected)
    }.toMutableMap()
    val checkBoxes = mutableListOf<CheckBox>()

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
        text = "One card per lens. Checked lenses appear in the viewfinder switcher, widest first."
        setTextColor(getColor(R.color.text_secondary))
        textSize = 12f
        setPadding(0, dp(4), 0, dp(8))
    })

    val quickRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 0, 0, dp(8))
    }
    fun styleQuickButton(button: Button) {
        button.background = getDrawable(R.drawable.control_chip)
        button.setTextColor(getColor(R.color.text_primary))
        button.textSize = 12f
        button.isAllCaps = false
        button.setTypeface(button.typeface, Typeface.BOLD)
        button.letterSpacing = 0.02f
        // Compact pill: single-line 12sp stays readable while the
        // slimmer padding keeps secondary actions visually secondary.
        button.setPadding(dp(12), dp(6), dp(12), dp(6))
        button.minHeight = dp(36)
        // One line everywhere: a wrapped label grows its button and the
        // row next to it looks misaligned on narrow screens.
        button.maxLines = 1
        button.ellipsize = android.text.TextUtils.TruncateAt.END
    }
    val useRecommended = Button(this).apply {
        text = "Best"
        styleQuickButton(this)
    }
    val selectAll = Button(this).apply {
        text = "All"
        styleQuickButton(this)
    }
    val clear = Button(this).apply {
        text = "Clear"
        styleQuickButton(this)
    }
    val recommendedParams = LinearLayout.LayoutParams(0, wrapContent(), 1f).apply { marginEnd = dp(8) }
    val selectParams = LinearLayout.LayoutParams(0, wrapContent(), 1f).apply { marginEnd = dp(8) }
    val clearParams = LinearLayout.LayoutParams(0, wrapContent(), 1f)
    quickRow.addView(useRecommended, recommendedParams)
    quickRow.addView(selectAll, selectParams)
    quickRow.addView(clear, clearParams)
    container.addView(quickRow)
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
        layoutParams = if (firstRun) {
            // Full-screen onboarding: fill everything between the header
            // and the bottom action bar so actions pin to the bottom.
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        } else {
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.52).toInt()
            )
        }
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
        selectAll.isEnabled = values.any { !it }
        clear.isEnabled = values.any { it }
        useRecommended.isEnabled = checked.any { (it.value != (it.key in recommended)) }
        selectAll.alpha = if (selectAll.isEnabled) 1f else 0.5f
        clear.alpha = if (clear.isEnabled) 1f else 0.5f
        useRecommended.alpha = if (useRecommended.isEnabled) 1f else 0.5f
    }

    fun lensCard(lens: DiscoveredLens, recommendedId: String?): LinearLayout {
        val dead = lens.verdict == LensVerdict.DEAD
        val dimmed = lens.kind == LensRouteKind.UNAVAILABLE || dead
        val chip = when {
            dead -> "⚠ OPEN FAILED BEFORE"
            lens.id == recommendedId -> "★ RECOMMENDED"
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
        val box = CheckBox(this).apply {
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
            text = lens.title
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
        // One muted line: the route text already says direct/logical,
        // so the kind chip only appears for the special states.
        val routeText = lens.route.ifEmpty { lens.kind.sectionTitle }
        textCol.addView(TextView(this).apply {
            text = if (chip != null) "$chip · $routeText" else routeText
            setTextColor(getColor(R.color.text_muted))
            textSize = 11f
            setPadding(0, dp(4), 0, 0)
        })
        card.addView(box)
        card.addView(textCol)
        box.setOnCheckedChangeListener { _, enabled -> checked[lens.id] = enabled; refreshQuickState() }
        card.setOnClickListener { box.toggle() }
        return card
    }

    fun showRawFormatChoice(lens: DiscoveredLens, formatRow: TextView) {
        val formats = lens.packedFormats
        val items = (listOf("Auto (RAW_SENSOR)") + formats).toTypedArray()
        val stored = rawFormatPreferenceStore.get(lens.id)
        val checked = stored?.let { formats.indexOf(it) + 1 }?.takeIf { it > 0 } ?: 0
        AlertDialog.Builder(this)
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
        val checked = stored?.let { sizes.indexOf(it) + 1 }?.takeIf { it > 0 } ?: 0
        AlertDialog.Builder(this)
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
        // ---- One card per physical lens ----
        // No big header: the card title already names the lens, so the
        // section only gets a small muted line. Same sizes everywhere.
        listBody.addView(TextView(this).apply {
            text = group.subtitle
            setTextColor(getColor(R.color.text_muted))
            textSize = 12f
            setPadding(0, if (firstGroup) dp(4) else dp(12), 0, dp(6))
        })
        firstGroup = false
        if (group.key == "unavailable") {
            group.lenses.forEach { lens ->
                listBody.addView(lensCard(lens, null))
            }
        } else {
            val head = group.recommended
            listBody.addView(lensCard(head, head.id))
            if (group.lenses.any { it.sensorModeSwitch }) {
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
            if (group.alternates.isNotEmpty()) {
                val alternateBody = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = View.GONE
                }
                group.alternates.forEach { lens ->
                    alternateBody.addView(lensCard(lens, head.id))
                }
                val toggle = TextView(this).apply {
                    text = "＋ ${group.alternates.size} more route${if (group.alternates.size == 1) "" else "s"} (same lens)"
                    setTextColor(getColor(R.color.accent))
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 0, dp(8))
                }
                toggle.setOnClickListener {
                    val open = alternateBody.visibility != View.VISIBLE
                    alternateBody.visibility = if (open) View.VISIBLE else View.GONE
                    toggle.text = if (open) "－ hide extra routes"
                        else "＋ ${group.alternates.size} more route${if (group.alternates.size == 1) "" else "s"} (same lens)"
                }
                listBody.addView(toggle)
                listBody.addView(alternateBody)
            }
        }
    }
    container.addView(listScroll)
    container.addView(TextView(this).apply {
        text = "Tip: Recommended is the daily driver. Extra routes are fallbacks for missing lenses or special sensor modes."
        setTextColor(getColor(R.color.text_muted))
        textSize = 11f
        setPadding(0, dp(8), 0, dp(4))
    })

    useRecommended.setOnClickListener {
        checkBoxes.forEach { it.isChecked = it.tag in recommended }
        refreshQuickState()
    }
    selectAll.setOnClickListener {
        checked.keys.forEach { checked[it] = true }
        checkBoxes.forEach { it.isChecked = true }
        refreshQuickState()
    }
    clear.setOnClickListener {
        checked.keys.forEach { checked[it] = false }
        checkBoxes.forEach { it.isChecked = false }
        refreshQuickState()
    }
    refreshQuickState()

    // First initial setup: dedicated full-screen black page (welcome
    // continuity) that scales to the bottom — the list fills the window
    // and Save / Use default pin to the bottom. Gone on either tap.
    // Later opens keep the floating panel below.
    if (firstRun) {
        container.setPadding(0, 0, 0, 0)
        fun bottomAction(label: String, primary: Boolean): Button = Button(this).apply {
            text = label
            background = getDrawable(
                if (primary) R.drawable.control_chip_active else R.drawable.control_chip
            )
            setTextColor(
                getColor(if (primary) R.color.accent_dark else R.color.text_primary)
            )
            textSize = 13f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.04f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minHeight = dp(48)
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
            setBackgroundColor(Color.BLACK)
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

    val dialog = AlertDialog.Builder(this)
        .setTitle("Lens discovery")
        .setView(container)
        .setPositiveButton("Save", null)
        .setNegativeButton("Cancel", null)
        .create()
    dialog.show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
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
    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
        dialog.dismiss()
    }
}
