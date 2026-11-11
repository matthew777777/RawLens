// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.SeekBar
import android.content.Context
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline burst-merge screen for staged and published `_BURST.craw` takes.
 * Each take owns its merge setup and action menu. Pick a method and output
 * (DNG / developed / developed+DNG) and the developed container
 * (JPEG / JXL, shared with shutter captures), run with a stats progress
 * line (stage, frames, elapsed, ETA, input and estimated output sizes).
 *
 * Entry points: long-press the bottom-panel burst switch, or OPEN
 * MERGES in Settings → Burst.
 */
class MergesActivity : Activity() {
    private lateinit var listBox: LinearLayout
    private lateinit var detailLine: TextView
    private lateinit var progressLine: TextView
    private lateinit var statusLine: TextView
    private lateinit var methodMfsr: Button
    private lateinit var methodHdrPlus: Button
    private lateinit var methodLkr: Button
    private lateinit var outDng: Button
    private lateinit var outJpeg: Button
    private lateinit var outBoth: Button
    private lateinit var developedJpeg: Button
    private lateinit var developedJxl: Button
    private lateinit var runButton: Button
    private lateinit var zoomTitle: TextView
    private lateinit var zoomRow: LinearLayout
    private lateinit var zoomButtons: List<Pair<LkrZoom, Button>>

    private lateinit var selectionLine: TextView
    private lateinit var methodHint: TextView
    private lateinit var framesButton: Button
    private var frameSelection = OfflineFrameSelection()
    private var inspectingFrames = false
    private lateinit var tuningButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var controls: LinearLayout
    private var loadingGeneration = 0
    private var pendingDeleteUri: android.net.Uri? = null
    private var tuningMfsr = MfsrSettings()
    private var tuningHdr = HdrPlusSettings()
    private var rows: List<BurstTakeRow> = emptyList()
    private var publishedRows: List<PublishedTake> = emptyList()
    private var selected: File? = null
    private var selectedPublished: PublishedTake? = null
    private var method = BurstMergeRunner.Method.MFSR
    private var format = CaptureFormat.DNG_ONLY
    private var developedFormat = DevelopedFormat.JPEG
    /** LK-R zoom for staged takes (own choice, not the live chip's). */
    private var lkrZoom = LkrZoom.X1
    private val mergeRunning = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = getSharedPreferences(SettingsBackup.STORE_MAIN, Context.MODE_PRIVATE)
        method = runCatching {
            BurstMergeRunner.Method.valueOf(
                prefs.getString(KEY_MERGE_METHOD, BurstMergeRunner.Method.MFSR.name)
                    ?: BurstMergeRunner.Method.MFSR.name
            )
        }.getOrDefault(BurstMergeRunner.Method.MFSR)
        format = CaptureFormat.fromPreference(prefs.getString(KEY_MERGE_OUTPUT_FORMAT, null))
        developedFormat =
            DevelopedFormat.fromPreference(prefs.getString(MainActivity.KEY_DEVELOPED_FORMAT, null))

        tuningMfsr = MfsrSettings.fromPreferences(prefs.all)
        tuningHdr = HdrPlusSettings.fromPreferences(prefs.all)
        val root = column().apply {
            setPadding(dp(20), dp(12), dp(20), dp(16))
            setBackgroundColor(getColor(R.color.surface))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(optionButton("‹").apply {
            contentDescription = "Back"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(this).apply {
            text = "Offline merge"
            textSize = 26f
            setTextColor(getColor(R.color.text_primary))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(12), 0, 0, 0)
            isAccessibilityHeading = true
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(optionButton("↻").apply {
            contentDescription = "Refresh burst takes"
            setOnClickListener { if (!mergeRunning.get() && !inspectingFrames) reloadTakes() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)
        val body = column()
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(title("Your bursts"))
        detailLine = desc("Loading burst takes…")
        body.addView(detailLine)
        listBox = column()
        body.addView(listBox)
        body.addView(desc("Tap to select · Hold a take or tap ⋯ for actions").apply {
            setPadding(0, dp(8), 0, dp(12))
        })
        controls = column().apply {
            background = panel()
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }
        body.addView(controls)
        controls.addView(title("Merge setup"))
        selectionLine = desc("Select a burst to configure its merge")
        controls.addView(selectionLine)
        controls.addView(title("Method"))
        val methodRow = LinearLayout(this)
        methodMfsr = optionButton("MFSR").also { button ->
            button.setOnClickListener { setMethod(BurstMergeRunner.Method.MFSR) }
            methodRow.addView(button, rowParams())
        }
        methodHdrPlus = optionButton("HDR+").also { button ->
            button.setOnClickListener { setMethod(BurstMergeRunner.Method.HDRPLUS) }
            methodRow.addView(button, rowParams())
        }
        methodLkr = optionButton("LK-R").also { button ->
            button.setOnClickListener { setMethod(BurstMergeRunner.Method.LKR) }
            methodRow.addView(button, rowParams())
        }
        controls.addView(methodRow)
        methodHint = desc("").apply { setPadding(0, dp(8), 0, 0) }
        controls.addView(methodHint)
        zoomTitle = title("Crop zoom")
        controls.addView(zoomTitle)
        zoomRow = LinearLayout(this)
        zoomButtons = LkrZoom.entries.map { z ->
            z to optionButton(z.label).also { button ->
                button.setOnClickListener { setLkrZoom(z) }
                zoomRow.addView(button, rowParams())
            }
        }
        controls.addView(zoomRow)
        controls.addView(title("Source frames"))
        framesButton = optionButton("Frames · Auto  ›").apply { setOnClickListener { showFramePicker() } }
        controls.addView(framesButton, LinearLayout.LayoutParams(-1, -2))
        controls.addView(title("Save as"))
        val outRow = LinearLayout(this)
        outDng = optionButton("DNG").also { button ->
            button.setOnClickListener { setFormat(CaptureFormat.DNG_ONLY) }
            outRow.addView(button, rowParams())
        }
        outJpeg = optionButton("JPEG").also { button ->
            button.setOnClickListener { setFormat(CaptureFormat.JPEG) }
            outRow.addView(button, rowParams())
        }
        outBoth = optionButton("JPEG+DNG").also { button ->
            button.setOnClickListener { setFormat(CaptureFormat.JPEG_DNG) }
            outRow.addView(button, rowParams())
        }
        controls.addView(outRow)
        val developedRow = LinearLayout(this)
        developedJpeg = optionButton("JPEG").also { button ->
            button.setOnClickListener { setDevelopedFormat(DevelopedFormat.JPEG) }
            developedRow.addView(button, rowParams())
        }
        developedJxl = optionButton("JXL").also { button ->
            button.setOnClickListener { setDevelopedFormat(DevelopedFormat.JXL) }
            developedRow.addView(button, rowParams())
        }
        controls.addView(desc("Developed format").apply { setPadding(0, dp(12), 0, dp(4)) })
        controls.addView(developedRow)
        tuningButton = optionButton("Fine-tune method  ›").apply {
            setOnClickListener { showTuning() }
        }
        controls.addView(tuningButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        progressLine = desc("").apply { setPadding(0, dp(12), 0, 0) }
        root.addView(progressLine)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
            visibility = View.GONE
        }
        root.addView(progressBar, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(8) })
        statusLine = desc("")
        root.addView(statusLine)
        runButton = optionButton("Select a burst").apply { setOnClickListener { runSelected() } }
        RawLensUi.styleButton(runButton, active = true)
        root.addView(runButton, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(12) })
        setContentView(root)
        // Decor view exists only after setContentView: applying earlier
        // crashes on API 30+ (null WindowInsetsController).
        ImmersiveMode.apply(window)
        refreshOptions()
    }

    override fun onResume() {
        super.onResume()
        ImmersiveMode.apply(window)
        reloadTakes()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ImmersiveMode.apply(window)
    }

    private fun prefs() = getSharedPreferences(SettingsBackup.STORE_MAIN, Context.MODE_PRIVATE)

    private fun stageDir(): File =
        getExternalFilesDir(BurstCrawStore.STAGE_SUBDIR) ?: File(filesDir, BurstCrawStore.STAGE_SUBDIR)

    private fun reloadTakes() {
        val generation = ++loadingGeneration
        detailLine.text = "Loading burst takes…"
        Thread({
            val staged = BurstMergeTakes.loadStage(stageDir())
            val granted = SidecarTreeAccess.savedTreeUri(this)?.let {
                BurstMergeTakes.loadPublishedTree(contentResolver, it)
            }.orEmpty()
            // Prefer the granted document URI when the same take is also indexed.
            val published = (granted + BurstMergeTakes.loadPublished(contentResolver))
                .distinctBy { it.displayName to it.sizeBytes }
                .sortedBy { it.lastModified }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != loadingGeneration) return@runOnUiThread
                rows = staged
                publishedRows = published
                if (selected?.let { file -> rows.none { it.file.absolutePath == file.absolutePath } } == true) {
                    selected = null
                }
                if (selectedPublished?.let { pub ->
                        publishedRows.none { it.uri == pub.uri }
                    } == true
                ) {
                    selectedPublished = null
                }
                renderList()
            }
        }, "MergesList").start()
    }

    private fun renderList() {
        listBox.removeAllViews()
        detailLine.text = if (rows.isEmpty() && publishedRows.isEmpty())
            "No bursts yet. Save a burst from Settings → Burst to start merging."
        else "${rows.size + publishedRows.size} takes · ${rows.size} on device · ${publishedRows.size} saved"
        rows.forEach { row ->
            addTakeCard(row.file.name, rowText(row), "ON DEVICE", row.file == selected,
                { choose(row.file, null) }, { anchor -> showActions(anchor, row, null) })
        }
        publishedRows.forEach { pub ->
            addTakeCard(pub.displayName, publishedText(pub), "SAVED", pub.uri == selectedPublished?.uri,
                { choose(null, pub) }, { anchor -> showActions(anchor, null, pub) })
        }
        refreshOptions()
    }

    private fun addTakeCard(name: String, metadata: String, location: String, active: Boolean,
                            select: () -> Unit, actions: (View) -> Unit) {
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = panel(active)
            setPadding(dp(14), dp(12), dp(6), dp(12))
            isClickable = true
            isFocusable = true
            contentDescription = "$name, $metadata${if (active) ", selected" else ""}"
            setOnClickListener { if (!mergeRunning.get() && !inspectingFrames) { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); select() } }
            setOnLongClickListener {
                if (!mergeRunning.get() && !inspectingFrames) { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); actions(this) }
                true
            }
        }
        val labels = column()
        labels.addView(desc("${if (active) "●  " else ""}$location").apply {
            textSize = 10f
            letterSpacing = .12f
            setTextColor(getColor(if (active) R.color.accent else R.color.text_secondary))
        })
        labels.addView(TextView(this).apply {
            text = name.removeSuffix(".craw")
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(getColor(R.color.text_primary))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(0, dp(5), 0, dp(6))
        })
        labels.addView(desc(metadata.substringAfter('\n')))
        card.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(optionButton("⋯").apply {
            contentDescription = "Actions for $name"
            isEnabled = !mergeRunning.get()
            setOnClickListener { actions(it) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        listBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun takePrefs(name: String): SharedPreferences =
        getSharedPreferences("offline_merge_" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(name.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }, Context.MODE_PRIVATE)

    private fun choose(file: File?, published: PublishedTake?) {
        selected = file
        selectedPublished = published
        val values = takePrefs(requireNotNull(file?.name ?: published?.displayName)).all
        method = runCatching { BurstMergeRunner.Method.valueOf(values[KEY_MERGE_METHOD] as String) }.getOrDefault(runCatching { BurstMergeRunner.Method.valueOf(prefs().getString(KEY_MERGE_METHOD, null) ?: "MFSR") }.getOrDefault(BurstMergeRunner.Method.MFSR))
        format = CaptureFormat.fromPreference(values[KEY_MERGE_OUTPUT_FORMAT] as? String ?: prefs().getString(KEY_MERGE_OUTPUT_FORMAT, null))
        developedFormat = DevelopedFormat.fromPreference(values[MainActivity.KEY_DEVELOPED_FORMAT] as? String ?: prefs().getString(MainActivity.KEY_DEVELOPED_FORMAT, null))
        lkrZoom = LkrZoom.entries.firstOrNull { it.name == values[KEY_MERGE_LKR_ZOOM] } ?: LkrZoom.X1
        tuningMfsr = MfsrSettings.fromPreferences(prefs().all + values)
        tuningHdr = HdrPlusSettings.fromPreferences(prefs().all + values)
        frameSelection = OfflineFrameSelection(
            (values["frame_count"] as? Int)?.takeIf { it >= 2 },
            (values["frame_indices"] as? String)?.let { encoded ->
                encoded.split(',').mapNotNull { it.toIntOrNull() }
            })
        saveTakeOptions()
        renderList()
        controls.post {
            controls.requestRectangleOnScreen(android.graphics.Rect(0, 0, controls.width, dp(120)), false)
        }
    }

    private fun saveTakeOptions() {
        val name = selected?.name ?: selectedPublished?.displayName ?: return
        takePrefs(name).edit()
            .putInt("frame_count", frameSelection.count ?: 0)
            .putString("frame_indices", frameSelection.manualIndices?.joinToString(","))
            .putString(KEY_MERGE_METHOD, method.name)
            .putString(KEY_MERGE_OUTPUT_FORMAT, format.name)
            .putString(MainActivity.KEY_DEVELOPED_FORMAT, developedFormat.name)
            .putString(KEY_MERGE_LKR_ZOOM, lkrZoom.name)
            .putString("mfsr_kernel", tuningMfsr.kernel.name)
            .putBoolean("mfsr_s3", tuningMfsr.s3)
            .putBoolean("mfsr_zipper_fix", tuningMfsr.zipperFix)
            .putBoolean("mfsr_learned_noise", tuningMfsr.learnedNoise)
            .putBoolean("lkr_dct_prior", tuningMfsr.lkrDctPrior)
            .putBoolean("hdr_plus_scene_auto_migrated", true)
            .putBoolean("hdr_plus_auto_strength", tuningHdr.autoStrength)
            .putBoolean("hdr_plus_auto_quality", tuningHdr.autoQuality)
            .putBoolean("hdr_plus_high_quality", tuningHdr.highQuality)
            .putFloat("hdr_plus_strength", tuningHdr.strength)
            .apply()
    }

    private fun showActions(anchor: View, row: BurstTakeRow?, pub: PublishedTake?) {
        if (mergeRunning.get() || inspectingFrames) return
        val name = row?.file?.name ?: requireNotNull(pub).displayName
        val menu = column().apply { background = panel(); setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val popup = PopupWindow(menu, dp(240), -2, true).apply {
            setBackgroundDrawable(panel())
            elevation = dp(12).toFloat()
            isOutsideTouchable = true
        }
        fun action(label: String, action: () -> Unit) {
            menu.addView(optionButton(label).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                if (label.startsWith("Delete")) setTextColor(getColor(R.color.danger))
                setOnClickListener { popup.dismiss(); action() }
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        action("Merge options") {
            choose(row?.file, pub)
        }
        action("Choose frames") { choose(row?.file, pub); showFramePicker() }
        action("Fine-tune method") { choose(row?.file, pub); showTuning() }
        action("Reset merge options") {
            takePrefs(name).edit().clear().apply()
            choose(row?.file, pub)
            statusLine.text = "Merge options reset for this take"
        }
        action("Info") {
            RawLensDialogBuilder(this).setTitle("Burst info")
                .setMessage("$name\n\n" + (row?.let { rowText(it).substringAfter('\n') } ?: publishedText(requireNotNull(pub)).substringAfter('\n')) +
                    "\n\n" + (row?.file?.absolutePath ?: pub?.uri.toString()))
                .setPositiveButton("Done", null).show()
        }
        action("Delete take…") { confirmDelete(row, pub) }
        popup.showAsDropDown(anchor, -dp(180), 0, Gravity.END)
    }

    private fun confirmDelete(row: BurstTakeRow?, pub: PublishedTake?) {
        val name = row?.file?.name ?: requireNotNull(pub).displayName
        RawLensDialogBuilder(this).setTitle("Delete burst?")
            .setMessage("Delete $name?\n\n${if (row != null) "This removes the on-device copy. Any saved library copy is kept." else "This removes the saved library take. Any on-device copy is kept."} Existing merged photos are kept.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Delete") { _, _ ->
                if (mergeRunning.get()) return@setPositiveButton
                Thread({
                    try {
                        val removed = if (row != null) row.file.delete() else {
                            val uri = requireNotNull(pub).uri
                            if (android.provider.DocumentsContract.isDocumentUri(this, uri))
                                android.provider.DocumentsContract.deleteDocument(contentResolver, uri)
                            else contentResolver.delete(uri, null, null) > 0
                        }
                        if (!removed) error("Could not delete this take")
                        runOnUiThread { reloadTakes() }
                    } catch (failure: Exception) {
                        runOnUiThread {
                            try {
                                when {
                                    failure is RecoverableSecurityException -> {
                                        pendingDeleteUri = pub?.uri
                                        startIntentSenderForResult(failure.userAction.actionIntent.intentSender, 41, null, 0, 0, 0)
                                    }
                                    failure is SecurityException && pub != null && android.os.Build.VERSION.SDK_INT >= 30 ->
                                        startIntentSenderForResult(android.provider.MediaStore.createDeleteRequest(
                                            contentResolver, listOf(pub.uri)).intentSender, 42, null, 0, 0, 0)
                                    else -> statusLine.text = failure.message ?: "Could not delete take"
                                }
                            } catch (requestFailure: Exception) { statusLine.text = requestFailure.message }
                        }
                    }
                }, "DeleteBurst").start()
            }.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 41) {
            val uri = pendingDeleteUri
            pendingDeleteUri = null
            if (resultCode == RESULT_OK && uri != null) {
                Thread({
                    runCatching { contentResolver.delete(uri, null, null) }
                        .onFailure { runOnUiThread { statusLine.text = it.message ?: "Could not delete take" } }
                    runOnUiThread { reloadTakes() }
                }, "DeleteApprovedBurst").start()
            }
        }
        if (requestCode == 41 || requestCode == 42) reloadTakes()
    }

    private fun frameLimit(): Int = when (method) {
        BurstMergeRunner.Method.MFSR -> MfsrSettings.MAX_MERGE_FRAMES
        BurstMergeRunner.Method.LKR -> MfsrSettings.maxFrames(MfsrEngine.LKR, lkrZoom)
        BurstMergeRunner.Method.HDRPLUS -> minOf(HdrPlusSettings.MAX_MERGE_FRAMES, HdrPlusMerge.MAX_NATIVE_FRAMES)
    }

    private fun showFramePicker() {
        if (mergeRunning.get() || inspectingFrames) return
        val file = selected
        val published = selectedPublished
        if (file == null && published == null) return
        inspectingFrames = true
        refreshOptions()
        statusLine.text = if (published != null) "Preparing saved take for frame selection…" else "Reading frame index…"
        Thread({
            try {
                val take = file ?: BurstMergeTakes.restage(contentResolver, requireNotNull(published), stageDir())
                val handle = CrawReader.open(take.absolutePath)
                val timestamps: LongArray
                val labels: List<String>
                try {
                    timestamps = CrawReader.frameTimestamps(handle) ?: error("Could not read frame index")
                    require(timestamps.size >= 2) { "This take has fewer than 2 frames" }
                    labels = timestamps.mapIndexed { index, timestamp ->
                        val meta = runCatching { org.json.JSONObject(CrawReader.frameMetadata(handle, timestamp) ?: "{}") }.getOrNull()
                        val iso = meta?.optInt("sensitivityIso", 0) ?: 0
                        val elapsed = (timestamp - timestamps.first()) / 1_000_000.0
                        "Frame ${index + 1} · +${"%.1f".format(elapsed)} ms" + if (iso > 0) " · ISO $iso" else ""
                    }
                } finally { CrawReader.closeReader(handle) }
                runOnUiThread {
                    inspectingFrames = false
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    refreshOptions()
                    statusLine.text = ""
                    framePickerDialog(timestamps.size, labels)
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    inspectingFrames = false
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    refreshOptions()
                    statusLine.text = failure.message ?: "Could not read source frames"
                }
            }
        }, "BurstFrameIndex").start()
    }

    private fun framePickerDialog(total: Int, labels: List<String>) {
        val maximum = minOf(total, frameLimit())
        val content = column()
        content.addView(desc("$total source frames · ${methodLabel()} supports 2–$maximum. Frame numbers follow capture order. The engine may reject blurred or moving frames."))
        val summary = desc("")
        content.addView(summary)
        var manual = frameSelection.manualIndices != null
        var count = (frameSelection.count ?: maximum).coerceIn(2, maximum)
        val selectedIndices = (frameSelection.manualIndices?.filter { it in 0 until total }
            ?: BurstMergeTakes.evenSampleIndices(total, count)).toMutableSet()
        val modeRow = LinearLayout(this)
        val autoButton = optionButton("Evenly spaced")
        val manualButton = optionButton("Choose frames")
        modeRow.addView(autoButton, rowParams()); modeRow.addView(manualButton, rowParams())
        content.addView(modeRow)
        val countLabel = desc("")
        content.addView(countLabel)
        val slider = SeekBar(this).apply {
            max = maximum - 2; progress = count - 2
            contentDescription = "Number of source frames"
        }
        content.addView(slider)
        val grid = android.widget.GridLayout(this).apply { columnCount = 4 }
        content.addView(grid)
        val error = desc("").apply { setTextColor(getColor(R.color.danger)) }
        content.addView(error)
        val buttons = mutableListOf<Button>()
        fun update() {
            stylePick(autoButton, !manual); stylePick(manualButton, manual)
            slider.visibility = if (manual) View.GONE else View.VISIBLE
            countLabel.text = if (manual) "Tap to include or exclude · Hold for capture timing" else "$count frames · evenly spaced across the take"
            val chosen = if (manual) selectedIndices else BurstMergeTakes.evenSampleIndices(total, count).toSet()
            summary.text = "${chosen.size} of $total frames selected"
            buttons.forEachIndexed { index, button ->
                stylePick(button, index in chosen)
                button.isEnabled = manual
                button.contentDescription = labels[index] + if (index in chosen) ", included" else ", excluded"
            }
            error.text = if (manual && selectedIndices.size !in 2..maximum) "Choose 2–$maximum frames to continue" else ""
        }
        repeat(total) { index ->
            val button = optionButton("${index + 1}").apply {
                setOnClickListener {
                    if (index in selectedIndices) selectedIndices.remove(index)
                    else if (selectedIndices.size < maximum) selectedIndices.add(index)
                    else { error.text = "This method supports up to $maximum frames"; return@setOnClickListener }
                    update()
                }
                setOnLongClickListener {
                    android.widget.Toast.makeText(this@MergesActivity, labels[index], android.widget.Toast.LENGTH_LONG).show()
                    true
                }
            }
            buttons += button
            grid.addView(button, android.widget.GridLayout.LayoutParams().apply {
                width = 0; height = dp(48)
                columnSpec = android.widget.GridLayout.spec(index % 4, 1f)
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        autoButton.setOnClickListener { manual = false; update() }
        manualButton.setOnClickListener {
            if (!manual) { selectedIndices.clear(); selectedIndices.addAll(BurstMergeTakes.evenSampleIndices(total, count)) }
            manual = true; update()
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { count = value + 2; update() }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        update()
        val dialog = RawLensDialogBuilder(this).setTitle("Source frames")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("Cancel", null).setPositiveButton("Apply", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (manual && selectedIndices.size !in 2..maximum) { error.text = "Choose 2–$maximum frames to continue"; return@setOnClickListener }
                frameSelection = if (manual) OfflineFrameSelection(manualIndices = selectedIndices.sorted()) else OfflineFrameSelection(count = count)
                saveTakeOptions(); refreshOptions(); dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showTuning() {
        if (mergeRunning.get() || (selected == null && selectedPublished == null)) return
        val content = column()
        content.addView(desc("Options are saved for this take. Output and method are on the merge setup card."))
        fun toggle(label: String, checked: Boolean, change: (Boolean) -> Unit): Switch {
            val control = Switch(this).apply {
                text = label; isChecked = checked
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnCheckedChangeListener { _, value -> change(value); saveTakeOptions() }
            }
            content.addView(control)
            return control
        }
        when (method) {
            BurstMergeRunner.Method.MFSR -> {
                val kernel = optionButton("Kernel · ${tuningMfsr.kernel.name}")
                kernel.setOnClickListener {
                    tuningMfsr = tuningMfsr.copy(kernel = if (tuningMfsr.kernel == MfsrKernel.KERNELNET) MfsrKernel.WRONSKI else MfsrKernel.KERNELNET)
                    kernel.text = "Kernel · ${tuningMfsr.kernel.name}"; saveTakeOptions()
                }
                content.addView(kernel)
                toggle("Artefact rejection", tuningMfsr.s3) { tuningMfsr = tuningMfsr.copy(s3 = it) }
                toggle("Colour zipper correction", tuningMfsr.zipperFix) { tuningMfsr = tuningMfsr.copy(zipperFix = it) }
                toggle("Learned noise model", tuningMfsr.learnedNoise) { tuningMfsr = tuningMfsr.copy(learnedNoise = it) }
            }
            BurstMergeRunner.Method.LKR -> {
                toggle("DCT denoise", tuningMfsr.lkrDctPrior) { tuningMfsr = tuningMfsr.copy(lkrDctPrior = it) }
                content.addView(desc("Crop zoom is set on the merge setup card. Output keeps the sensor resolution."))
            }
            BurstMergeRunner.Method.HDRPLUS -> {
                val autoStrength = toggle("Automatic strength", tuningHdr.autoStrength) { tuningHdr = tuningHdr.copy(autoStrength = it) }
                toggle("Automatic quality", tuningHdr.autoQuality) { tuningHdr = tuningHdr.copy(autoQuality = it) }
                toggle("Manual quality · high", tuningHdr.highQuality) { tuningHdr = tuningHdr.copy(highQuality = it) }
                val strengthLabel = desc("Manual strength · ${tuningHdr.strength.toInt()}")
                content.addView(strengthLabel)
                content.addView(SeekBar(this).apply {
                    max = 21; progress = tuningHdr.strength.toInt() - 1
                    contentDescription = "HDR+ manual strength"
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                            if (fromUser) {
                                autoStrength.isChecked = false
                                tuningHdr = tuningHdr.copy(strength = (value + 1).toFloat(), autoStrength = false)
                                strengthLabel.text = "Manual strength · ${value + 1} (auto off)"; saveTakeOptions()
                            }
                        }
                        override fun onStartTrackingTouch(bar: SeekBar?) {}
                        override fun onStopTrackingTouch(bar: SeekBar?) {}
                    })
                })
            }
        }
        RawLensDialogBuilder(this).setTitle("${methodLabel()} options")
            .setView(ScrollView(this).apply { addView(content) }).setPositiveButton("Done", null).show()
    }

    private fun methodLabel() = when (method) {
        BurstMergeRunner.Method.MFSR -> "MFSR"
        BurstMergeRunner.Method.HDRPLUS -> "HDR+"
        BurstMergeRunner.Method.LKR -> "LK-R"
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun panel(active: Boolean = false) = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(getColor(if (active) R.color.accent_soft else R.color.surface_container))
        setStroke(dp(1), getColor(if (active) R.color.accent else R.color.outline))
    }

    private fun publishedText(pub: PublishedTake): String {
        val mb = pub.sizeBytes / 1e6
        val date = if (pub.lastModified > 0) {
            SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(pub.lastModified))
        } else "?"
        return "${pub.displayName.removeSuffix(".craw")}\n${"%.1f".format(mb)}MB • $date"
    }

    private fun rowText(row: BurstTakeRow): String {
        val mb = row.fileBytes / 1e6
        val dims = if (row.width != null && row.height != null) " • ${row.width}×${row.height}" else ""
        val date = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(row.lastModified))
        return "${row.file.nameWithoutExtension}\n×${row.frameCount} frames • ${"%.1f".format(mb)}MB$dims • $date"
    }

    private fun setMethod(next: BurstMergeRunner.Method) {
        method = next
        saveTakeOptions()
        refreshOptions()
    }

    private fun setLkrZoom(next: LkrZoom) {
        lkrZoom = next
        saveTakeOptions()
        refreshOptions()
    }

    private fun setFormat(next: CaptureFormat) {
        format = next
        saveTakeOptions()
        refreshOptions()
    }

    private fun setDevelopedFormat(next: DevelopedFormat) {
        developedFormat = next
        saveTakeOptions()
        refreshOptions()
    }

    private fun refreshOptions() {
        if (!::methodMfsr.isInitialized) return
        val hasSelection = selected != null || selectedPublished != null
        val busy = mergeRunning.get() || inspectingFrames
        fun enable(view: View) {
            view.isEnabled = hasSelection && !busy
            if (view is ViewGroup) for (i in 0 until view.childCount) enable(view.getChildAt(i))
        }
        enable(controls)
        controls.alpha = if (hasSelection) 1f else .5f
        selectionLine.text = selected?.name ?: selectedPublished?.displayName ?: "Select a burst to configure its merge"
        methodHint.text = when (method) {
            BurstMergeRunner.Method.MFSR -> "Fine detail with adaptive reconstruction."
            BurstMergeRunner.Method.HDRPLUS -> "Noise reduction with motion-aware alignment."
            BurstMergeRunner.Method.LKR -> "Iterative reconstruction with optional crop zoom."
        }
        val validFrames = frameSelection.manualIndices?.size?.let { it in 2..frameLimit() } ?: true
        runButton.isEnabled = hasSelection && !busy && validFrames
        if (!validFrames) methodHint.text = "Choose 2–${frameLimit()} source frames for ${methodLabel()}."
        runButton.text = if (busy) "Merging…" else if (hasSelection) "Merge with ${methodLabel()}" else "Select a burst"
        framesButton.text = when {
            frameSelection.manualIndices != null -> "Frames · ${frameSelection.manualIndices!!.size} selected  ›"
            frameSelection.count != null -> "Frames · ${minOf(frameSelection.count!!, frameLimit())} evenly spaced  ›"
            else -> "Frames · Auto  ›"
        }
        val developed = developedFormat.label
        outJpeg.text = developed
        outBoth.text = "$developed+DNG"
        developedJpeg.isEnabled = hasSelection && !busy && format != CaptureFormat.DNG_ONLY
        developedJxl.isEnabled = developedJpeg.isEnabled
        stylePick(methodMfsr, method == BurstMergeRunner.Method.MFSR)
        stylePick(methodHdrPlus, method == BurstMergeRunner.Method.HDRPLUS)
        stylePick(methodLkr, method == BurstMergeRunner.Method.LKR)
        val lkr = method == BurstMergeRunner.Method.LKR
        if (::zoomTitle.isInitialized) {
        zoomTitle.visibility = if (lkr) android.view.View.VISIBLE else android.view.View.GONE
        zoomRow.visibility = zoomTitle.visibility
        zoomButtons.forEach { (z, b) -> stylePick(b, z == lkrZoom) }
        }
        stylePick(outDng, format == CaptureFormat.DNG_ONLY)
        stylePick(outJpeg, format == CaptureFormat.JPEG)
        stylePick(outBoth, format == CaptureFormat.JPEG_DNG)
        stylePick(developedJpeg, developedFormat == DevelopedFormat.JPEG)
        stylePick(developedJxl, developedFormat == DevelopedFormat.JXL)
    }

    private fun runSelected() {
        val published = selectedPublished
        val stagedNow = selected
        if (stagedNow == null && published == null) return
        if (!mergeRunning.compareAndSet(false, true)) return
        refreshOptions()
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        statusLine.text = ""
        progressLine.text = "Starting…"
        val prefs = prefs()
        val inputs = BurstMergeRunner.Inputs(
            mfsr = tuningMfsr.copy(lkrZoom = lkrZoom),
            hdrPlus = tuningHdr,
            jpeg = MergeScreenSettings.jpeg(prefs),
            denoise = MergeScreenSettings.denoise(prefs),
            jxl = MergeScreenSettings.jxl(prefs),
            developedFormat = developedFormat,
            frameSelection = frameSelection.copy(manualIndices = frameSelection.manualIndices?.toList())
        )
        val workContext = applicationContext
        val selectedMethod = method
        val selectedFormat = format
        ProcessingWork.begin(workContext)
        Thread({
            try {
                // Published takes have no local path: copy back into the
                // stage dir first (the published original is kept).
                val take = if (published != null) {
                    runOnUiThread { progressLine.text = "${published.displayName}\nCopying to stage…" }
                    BurstMergeTakes.restage(contentResolver, published, stageDir())
                } else {
                    requireNotNull(stagedNow)
                }
                val result = BurstMergeRunner(workContext).run(take, selectedMethod, selectedFormat, inputs) { progress ->
                    runOnUiThread {
                        progressLine.text = progressText(take, progress)
                        progressBar.isIndeterminate = progress.total <= 0
                        if (progress.total > 0) progressBar.progress = (100L * progress.done / progress.total).toInt()
                    }
                }
                runOnUiThread {
                    reloadTakes()
                    mergeRunning.set(false)
                    progressBar.visibility = View.GONE
                    renderList()
                    progressLine.text = "Done • ${result.outputs.size} output${if (result.outputs.size == 1) "" else "s"}"
                    statusLine.text =
                        "${when (method) {
                            BurstMergeRunner.Method.MFSR -> "MFSR"
                            BurstMergeRunner.Method.HDRPLUS -> "HDR+"
                            BurstMergeRunner.Method.LKR -> "LK-R"
                        }} " +
                            "×${result.keptFrames}/${result.pooledFrames} • " +
                            "${result.width}×${result.height} • " +
                            "${result.mergeMs}ms merge • " +
                            result.outputs.joinToString(" • ")
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    mergeRunning.set(false)
                    progressBar.visibility = View.GONE
                    renderList()
                    progressLine.text = "Merge failed"
                    statusLine.text = (failure.message ?: failure.javaClass.simpleName).take(160)
                }
            } finally {
                ProcessingWork.end(workContext)
            }
        }, "BurstMerge").start()
    }

    private fun progressText(take: File, progress: BurstMergeRunner.Progress): String {
        val pct = if (progress.total > 0) (100 * progress.done / progress.total) else 0
        val eta = progress.etaMs?.let { " • ETA ${it / 1000}s" } ?: ""
        val est = progress.outEstimateBytes?.let { " • est ${"%.1f".format(it / 1e6)}MB" } ?: ""
        val mb = progress.inBytes / 1e6
        return "${take.nameWithoutExtension}\n${progress.stage} ${progress.done}/${progress.total} " +
            "($pct%) • ${progress.elapsedMs / 1000}s$eta • in ${"%.1f".format(mb)}MB$est"
    }

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
        setTextColor(getColor(R.color.accent))
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        letterSpacing = 0.02f
        setPadding(dp(4), dp(14), dp(4), dp(6))
    }

    private fun desc(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_secondary))
        textSize = 12f
        setPadding(0, 0, 0, dp(4))
    }

    private fun optionButton(label: String): Button = Button(this).apply {
        text = label
        RawLensUi.styleButton(this)
    }

    private fun rowParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        }

    private fun stylePick(button: Button, active: Boolean) {
        RawLensUi.styleButton(button, active)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val KEY_MERGE_METHOD = "burst_merge_method"
        private const val KEY_MERGE_LKR_ZOOM = "merges_lkr_zoom"
        const val KEY_MERGE_OUTPUT_FORMAT = "burst_merge_output_format"
    }
}
