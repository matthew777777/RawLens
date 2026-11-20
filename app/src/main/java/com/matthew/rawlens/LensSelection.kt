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
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
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
import com.particlesdevs.photoncamera.processing.ml.RawNindNcnnProcessor
import java.io.File
import java.util.Locale
import android.os.Build
import android.os.SystemClock

// First-run and settings lens discovery and selection screens. Moved verbatim from MainActivity.

/**
 * @param retestAf re-runs the AF scan probe on every route, discarding saved
 *   AF verdicts (the "Re-test autofocus" link).
 */
internal fun MainActivity.showLensDiscovery(firstRun: Boolean, retestAf: Boolean = false) {
    // First launch covers the whole camera UI (including the top bar)
    // with an opaque black overlay — a dialog window can't reliably do
    // that — while later invocations keep the floating progress dialog.
    var progressDialog: AlertDialog? = null
    var progressOverlay: View? = null
    var progressText: TextView? = null
    if (firstRun) {
        // First launch: a small card over the dimmed viewfinder instead of
        // a full black page — the scrim still eats touches while routes are
        // probed, since the AF probe stops the camera.
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.glass_button)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            addView(ProgressBar(this@showLensDiscovery).apply { isIndeterminate = true },
                LinearLayout.LayoutParams(dp(24), dp(24)))
            progressText = TextView(this@showLensDiscovery).apply {
                text = "Setting up lenses…"
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                setPadding(dp(12), 0, 0, 0)
            }
            addView(progressText)
        }
        progressOverlay = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            elevation = 32f
            isClickable = true
            isFocusableInTouchMode = true
            addView(card, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply { marginStart = dp(24); marginEnd = dp(24) })
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
    val stored = runCatching { lensVerdictStore.snapshot() }.getOrDefault(LensVerdicts.EMPTY)
    val verdicts = if (retestAf) stored.copy(af = emptyMap()) else stored
    lensDiscovery.discover(verdicts) { discovered ->
        // Active AF scan probe for routes never probed: characteristics lie
        // both ways (AF state runs without the lens moving, or the lens moves
        // with no AF state machine), so open each one and look.
        // Only on an explicit "Re-test autofocus": an automatic probe stops the
        // camera, races its reopen (open error 2 / device closed) and made the
        // lens page hang after a clean install. Unprobed routes stay UNKNOWN.
        val toProbe = if (firstRun || !retestAf) emptyList() else discovered.filter {
            it.kind != LensRouteKind.UNAVAILABLE && it.af == AfVerdict.UNKNOWN
        }
        val canProbe = toProbe.isNotEmpty() &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val lenses = if (!canProbe) discovered else {
            val stopped = java.util.concurrent.CountDownLatch(1)
            var restart = false
            runOnUiThread {
                controllerIfReady?.let { restart = it.isRunning(); it.stop() }
                progressDialog?.setMessage("Testing autofocus on ${toProbe.size} route(s)…\nPoint the camera at something with detail.")
                progressText?.text = "Setting up lenses… point at something with detail"
                stopped.countDown()
            }
            stopped.await()
            if (retestAf) runCatching { lensVerdictStore.clearAf(toProbe.map { it.id }) }
            val probe = AfScanProbe(this)
            val results = toProbe.associate { lens ->
                if (isFinishing || isDestroyed) return@associate lens.id to AfVerdict.UNKNOWN
                val verdict = probe.probe(lens)
                runCatching { lensVerdictStore.markAf(lens.id, verdict) }
                lens.id to verdict
            }
            if (restart) runOnUiThread { if (!isFinishing && !isDestroyed) controller.start() }
            discovered.map { lens -> results[lens.id]?.let { applyAfVerdict(lens, it) } ?: lens }
        }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            progressDialog?.dismiss()
            progressOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
            progressOverlay = null
            if (firstRun) {
                showFirstRunLensList(lenses)
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

/**
 * First-run lens list: one plain row per available route, nothing
 * preselected, and a single Done button. The full picker (duplicates,
 * manual IDs, profiles, AF re-test) stays under Settings → Lenses.
 */
internal fun MainActivity.showFirstRunLensList(lenses: List<DiscoveredLens>) {
    val available = lenses.filter { it.kind != LensRouteKind.UNAVAILABLE }
        .sortedWith(compareBy<DiscoveredLens> { it.role == LensRole.FRONT }
            .thenBy { it.opticalMetric }.thenBy { it.id })
    if (available.isEmpty()) {
        showLensSelection(lenses, firstRun = true)
        return
    }
    val rearMetrics = available.filter { it.role != LensRole.FRONT }.map { it.opticalMetric }
    val checked = mutableSetOf<String>()
    val selectable = TypedValue().also {
        theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
    }.resourceId

    val done = Button(this).apply {
        text = "Choose at least one lens"
        RawLensUi.styleButton(this, false)
        isEnabled = false
    }
    fun refreshDone() {
        val n = checked.size
        done.isEnabled = n > 0
        RawLensUi.styleButton(done, n > 0)
        done.text = if (n == 0) "Choose at least one lens"
            else "Continue with $n lens${if (n == 1) "" else "es"}"
    }

    // Same rounded-card column as the Settings pages: headings start cards,
    // rows inside one card get hairline dividers.
    val column = SettingsCardColumn(this).apply { setPadding(0, 0, 0, dp(16)) }
    fun heading(label: String) = TextView(this).apply {
        text = label
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isAllCaps = true
        letterSpacing = 0.12f
        tag = SettingsCardColumn.SECTION_TAG
        setTextColor(getColor(R.color.text_secondary))
        setPadding(dp(16), dp(22), dp(16), dp(8))
    }
    fun row(lens: DiscoveredLens): View {
        val front = lens.role == LensRole.FRONT
        val zoom = if (front) "F"
            else LensZoom.display(lens.opticalMetric, rearMetrics, LensZoom.focalLabels(this))
        val box = SettingsCheckBox(this).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(selectable)
            minimumHeight = dp(72)
            setPadding(dp(16), dp(12), dp(8), dp(12))
            isClickable = true
            isFocusable = true
            contentDescription = "$zoom ${lens.title}, camera ${lens.id}"
            // Zoom badge: the same tinted rounded square as the Settings page list.
            addView(TextView(context).apply {
                text = zoom
                gravity = Gravity.CENTER
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(getColor(R.color.accent))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(getColor(R.color.accent_soft))
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = lens.title
                    textSize = 15f
                    setTextColor(getColor(R.color.text_primary))
                })
                addView(TextView(context).apply {
                    text = listOfNotNull(
                        "Camera ${lens.id}",
                        lens.megapixels?.let { "%.0f MP".format(it) }
                    ).joinToString(" · ")
                    textSize = 13f
                    setTextColor(getColor(R.color.text_secondary))
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, wrapContent(), 1f))
            addView(box)
            setOnClickListener {
                box.isChecked = !box.isChecked
                if (box.isChecked) checked += lens.id else checked -= lens.id
                isSelected = box.isChecked
                refreshDone()
            }
        }
    }
    var dismissList: () -> Unit = {}
    DeviceLensProfiles.matching().firstOrNull()?.let { profile ->
        column.addView(heading("Recommended for this phone"))
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(selectable)
            minimumHeight = dp(72)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            addView(TextView(context).apply {
                text = "Use the ${profile.name} setup"
                textSize = 15f
                setTextColor(getColor(R.color.accent))
            })
            addView(TextView(context).apply {
                text = "${profile.lenses.joinToString(" · ") { it.name }} — from rawr's tested profile"
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(3), 0, 0)
            })
            setOnClickListener {
                DeviceLensProfiles.apply(this@showFirstRunLensList, profile)
                controller.reloadLenses()
                dismissList()
                ImmersiveMode.apply(window)
                setStatus("${profile.name.uppercase()} LENSES LOADED")
                updateFlow.showPendingConsent()
            }
        })
    }
    val (frontLenses, rearLenses) = available.partition { it.role == LensRole.FRONT }
    if (rearLenses.isNotEmpty()) {
        column.addView(heading("Rear"))
        rearLenses.forEach { column.addView(row(it)) }
    }
    if (frontLenses.isNotEmpty()) {
        column.addView(heading("Front"))
        frontLenses.forEach { column.addView(row(it)) }
    }

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(getColor(R.color.surface))
        elevation = 32f
        isClickable = true
        isFocusableInTouchMode = true
        setPadding(dp(16), dp(28), dp(16), dp(20))
    }
    root.addView(TextView(this).apply {
        text = "Choose your lenses"
        textSize = 24f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isAccessibilityHeading = true
        setTextColor(getColor(R.color.text_primary))
        setPadding(dp(16), 0, dp(16), 0)
    })
    root.addView(TextView(this).apply {
        text = "Each lens becomes a button in the viewfinder. Some phones list the same lens twice."
        textSize = 13f
        setTextColor(getColor(R.color.text_secondary))
        setPadding(dp(16), dp(6), dp(16), 0)
    })
    root.addView(ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        addView(column)
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.addView(TextView(this).apply {
        text = "You can change this anytime in Settings → Lenses."
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(getColor(R.color.text_muted))
        setPadding(0, dp(4), 0, dp(10))
    })
    root.addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wrapContent()))
    refreshDone()
    blockBackKey(root)
    findViewById<ViewGroup>(android.R.id.content).addView(root, ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    root.requestFocus()
    dismissList = { (root.parent as? ViewGroup)?.removeView(root) }
    done.setOnClickListener {
        if (checked.isEmpty()) return@setOnClickListener
        lensPreferences().edit()
            .putStringSet(KEY_SELECTED_LENSES, checked.toSet())
            .putBoolean(KEY_LENS_SETUP_COMPLETE, true)
            .apply()
        controller.reloadLenses()
        (root.parent as? ViewGroup)?.removeView(root)
        ImmersiveMode.apply(window)
        setStatus("${checked.size} RAW LENS${if (checked.size == 1) "" else "ES"} ADDED")
        updateFlow.showPendingConsent()
    }
}

/** Settings → Lenses → Load device profile: pick, confirm, apply, reopen the camera. */
internal fun MainActivity.pickDeviceLensProfile(onApplied: () -> Unit) {
    val profiles = DeviceLensProfiles.matching()
    if (profiles.isEmpty()) return
    fun confirm(profile: DeviceLensProfiles.Profile) {
        RawLensDialogBuilder(this)
            .setTitle("Load ${profile.name}?")
            .setMessage(profile.description + "\n\nLenses: " +
                profile.lenses.joinToString(", ") { it.name } +
                "\n\nReplaces the lens list, names, RAW size/format, vendor keys and fixed levels " +
                "of these lenses. Other settings stay.")
            .setPositiveButton("Load") { _, _ ->
                DeviceLensProfiles.apply(this, profile)
                controller.reloadLenses()
                setStatus("${profile.name.uppercase()} LENSES LOADED")
                onApplied()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    if (profiles.size == 1) confirm(profiles.first())
    else RawLensDialogBuilder(this)
        .setTitle("Load device profile")
        .setItems(profiles.map { it.name }.toTypedArray()) { _, which -> confirm(profiles[which]) }
        .setNegativeButton("Cancel", null)
        .show()
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
    val pickerLenses = lenses.toMutableList()
    val checked = lenses.associate { lens ->
        lens.id to (lens.id in selected)
    }.toMutableMap()
    val checkBoxes = mutableListOf<CheckBox>()
    // Several routes can reach one physical lens. Name each route by its switcher zoom
    // and flag routes that share a lens, so the same lens is not picked several times.
    val available = lenses.filter { it.kind != LensRouteKind.UNAVAILABLE }
    val rearMetrics = available.filter { it.role != LensRole.FRONT }.map { it.opticalMetric }
    fun zoomOf(lens: DiscoveredLens): String =
        if (lens.role == LensRole.FRONT) "F"
        else LensZoom.display(lens.opticalMetric, rearMetrics, LensZoom.focalLabels(this))
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
        val picked = pickerLenses.filter { checked[it.id] == true }
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
    // Everything scrolls as one list: the summary and quick actions sit
    // above the lens cards, rarely used actions in "More" below them.
    val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val more = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    top.addView(TextView(this).apply {
        text = "$opticCount lens${if (opticCount == 1) "" else "es"} · $routeCount route${if (routeCount == 1) "" else "s"}"
        setTextColor(getColor(R.color.text_primary))
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
    })
    top.addView(TextView(this).apply {
        text = "Some phones reach one lens through several camera IDs. Tick one per lens; each becomes a viewfinder button."
        setTextColor(getColor(R.color.text_secondary))
        textSize = 12f
        setPadding(0, dp(4), 0, dp(8))
    })
    // Switcher label style: zoom factors (0.6×, 1×, 3×) or 35 mm-equivalent focal lengths.
    val zoomTitles = mutableListOf<Pair<TextView, DiscoveredLens>>()
    top.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(8))
        addView(TextView(context).apply {
            text = "Show focal length (24mm) instead of zoom (1×)"
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
        }, LinearLayout.LayoutParams(0, wrapContent(), 1f))
        addView(SettingsSwitch(context).apply {
            isChecked = LensZoom.focalLabels(this@showLensSelection)
            setOnCheckedChangeListener { _, focal ->
                lensPreferences().edit().putBoolean(LensZoom.KEY_FOCAL_LABELS, focal).apply()
                zoomTitles.forEach { (view, lens) -> view.text = "${zoomOf(lens)} · ${lens.title}" }
                refreshLensSwitcher()
            }
        })
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
    top.addView(quickRow)
    top.addView(selectionSummary)
    more.addView(TextView(this).apply {
        text = "More"
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isAllCaps = true
        letterSpacing = 0.12f
        setTextColor(getColor(R.color.text_secondary))
        setPadding(0, dp(20), 0, dp(8))
    })
    more.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 0, 0, dp(8))
        addView(Button(context).apply {
            text = "Export profiles ZIP"
            styleQuickButton(this)
            setOnClickListener { lensProfilesTransfer.export() }
        }, LinearLayout.LayoutParams(0, wrapContent(), 1f))
        addView(Button(context).apply {
            text = "Import profiles ZIP"
            styleQuickButton(this)
            setOnClickListener { lensProfilesTransfer.import() }
        }, LinearLayout.LayoutParams(0, wrapContent(), 1f))
    })
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
    var closeSelectionLater: () -> Unit = {}
    if (!firstRun && DeviceLensProfiles.matching().isNotEmpty()) more.addView(TextView(this).apply {
        text = "Load device profile"
        setTextColor(getColor(R.color.accent))
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(0, dp(2), 0, dp(10))
        setOnClickListener { pickDeviceLensProfile { closeSelectionLater() } }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wrapContent()))
    if (!firstRun) more.addView(TextView(this).apply {
        text = "Viewfinder order & names"
        setTextColor(getColor(R.color.accent))
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(0, dp(2), 0, dp(10))
        setOnClickListener { showLensOrderPage(pickerLenses, ::zoomOf) }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wrapContent()))
    more.addView(copyReport, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, wrapContent()
    ))
    // Set by whichever page variant is shown below, so the retest link can close it.
    var closeSelection: () -> Unit = {}
    closeSelectionLater = { closeSelection() }
    val retestAf = TextView(this).apply {
        text = "Re-test autofocus"
        setTextColor(getColor(R.color.accent))
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, dp(10))
        setOnClickListener {
            closeSelection()
            showLensDiscovery(firstRun, retestAf = true)
        }
    }
    more.addView(retestAf, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, wrapContent()
    ))
    copyReport.setOnClickListener {
        val report = lensReport(
            pickerLenses,
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
    listBody.addView(top)

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
            if (lens.kind != LensRouteKind.UNAVAILABLE) zoomTitles += this to lens
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
    val manualId = EditText(this).apply {
        hint = "Lens ID, e.g. 0, 0-3 or 0/3"
        contentDescription = "Lens ID"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setSingleLine(true)
        setTextColor(getColor(R.color.text_primary))
        setHintTextColor(getColor(R.color.text_muted))
    }
    val manualEntries = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val addManual = Button(this).apply {
        text = "Add lens ID"
        RawLensUi.styleButton(this)
        setOnClickListener {
            val id = manualId.text.toString().trim()
            if (id.isEmpty()) {
                manualId.error = "Enter a lens ID"
                return@setOnClickListener
            }
            // Preserve the exact vendor ID: dashes and slashes have different meanings.
            if (id !in checked) {
                val lens = DiscoveredLens(
                    id = id,
                    label = "Camera $id · manually added",
                    title = "Camera $id",
                    details = "Manually added · RAW support checked when opened",
                    route = "Manual camera route"
                )
                pickerLenses += lens
                checked[id] = true
                manualEntries.addView(lensCard(lens))
            } else {
                checked[id] = true
                checkBoxes.firstOrNull { it.tag == id }?.isChecked = true
            }
            manualId.text.clear()
            manualId.error = null
            refreshQuickState()
            refreshDuplicates()
        }
    }
    listBody.addView(TextView(this).apply {
        text = "Add a lens manually"
        setTextColor(getColor(R.color.text_primary))
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(16), 0, dp(4))
    })
    listBody.addView(TextView(this).apply {
        text = "Enter any camera ID omitted by discovery. Tap Save to keep it; availability and RAW support depend on the camera service."
        setTextColor(getColor(R.color.text_secondary))
        textSize = 12f
    })
    listBody.addView(manualId)
    listBody.addView(addManual)
    listBody.addView(manualEntries)
    listBody.addView(virtualLensSection(lenses, ::zoomOf) { id -> checked[id] == true })
    more.addView(TextView(this).apply {
        text = "Use direct physical IDs first. Greyed routes remain selectable for features unavailable directly, such as Xiaomi sensor modes or zoom."
        setTextColor(getColor(R.color.text_muted))
        textSize = 11f
        setPadding(0, dp(8), 0, dp(4))
    })
    listBody.addView(more)
    container.addView(listScroll)

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
        closeSelection = { (root.parent as? ViewGroup)?.removeView(root) }
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
    closeSelection = { dialog.dismiss() }
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
