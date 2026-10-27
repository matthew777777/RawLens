// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.app.Activity
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
 * Offline burst-merge screen: staged `_BURST.craw` takes only, never
 * RAW video. Pick a take, pick MFSR or HDR+, pick the output panel
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
    private lateinit var outDng: Button
    private lateinit var outJpeg: Button
    private lateinit var outBoth: Button
    private lateinit var developedJpeg: Button
    private lateinit var developedJxl: Button
    private lateinit var runButton: Button

    private var rows: List<BurstTakeRow> = emptyList()
    private var publishedRows: List<PublishedTake> = emptyList()
    private var selected: File? = null
    private var selectedPublished: PublishedTake? = null
    private var method = BurstMergeRunner.Method.MFSR
    private var format = CaptureFormat.DNG_ONLY
    private var developedFormat = DevelopedFormat.JPEG
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(getColor(R.color.surface))
        }
        root.addView(title("BURST MERGES"))
        detailLine = desc("Loading staged takes…")
        root.addView(detailLine)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val listScroll = ScrollView(this).apply { addView(listBox) }
        root.addView(listScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f))
        root.addView(title("METHOD"))
        val methodRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        methodMfsr = optionButton("MFSR").also { button ->
            button.setOnClickListener { setMethod(BurstMergeRunner.Method.MFSR) }
            methodRow.addView(button, rowParams())
        }
        methodHdrPlus = optionButton("HDR+").also { button ->
            button.setOnClickListener { setMethod(BurstMergeRunner.Method.HDRPLUS) }
            methodRow.addView(button, rowParams())
        }
        root.addView(methodRow)
        root.addView(title("OUTPUT"))
        val outRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
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
        root.addView(outRow)
        root.addView(title("DEVELOPED"))
        val developedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        developedJpeg = optionButton("JPEG").also { button ->
            button.setOnClickListener { setDevelopedFormat(DevelopedFormat.JPEG) }
            developedRow.addView(button, rowParams())
        }
        developedJxl = optionButton("JXL").also { button ->
            button.setOnClickListener { setDevelopedFormat(DevelopedFormat.JXL) }
            developedRow.addView(button, rowParams())
        }
        root.addView(developedRow)
        runButton = Button(this).apply {
            text = "RUN MERGE"
            setOnClickListener { runSelected() }
        }
        root.addView(runButton)
        progressLine = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 10f
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(progressLine)
        statusLine = desc("")
        root.addView(statusLine)
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
        detailLine.text = "Loading burst takes…"
        Thread({
            val staged = BurstMergeTakes.loadStage(stageDir())
            val published = BurstMergeTakes.loadPublished(contentResolver)
            runOnUiThread {
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
        val head = when {
            rows.isEmpty() && publishedRows.isEmpty() ->
                "No burst takes yet. Enable the dump in Settings → Burst (ZSL) and tap the shutter."
            else -> "${rows.size} staged • ${publishedRows.size} published in DCIM/RawLens/Bursts"
        }
        detailLine.text = head
        rows.forEach { row ->
            val active = row.file.absolutePath == selected?.absolutePath
            listBox.addView(Button(this).apply {
                text = "STAGED\n${rowText(row)}"
                stylePick(this, active)
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    selected = row.file
                    selectedPublished = null
                    renderList()
                }
            })
        }
        publishedRows.forEach { pub ->
            val active = pub.uri == selectedPublished?.uri
            listBox.addView(Button(this).apply {
                text = "SAVED\n${publishedText(pub)}"
                stylePick(this, active)
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    selectedPublished = pub
                    selected = null
                    renderList()
                }
            })
        }
        runButton.isEnabled = (selected != null || selectedPublished != null) && !mergeRunning.get()
    }

    private fun publishedText(pub: PublishedTake): String {
        val mb = pub.sizeBytes / 1e6
        val date = if (pub.lastModified > 0) {
            SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(pub.lastModified))
        } else "?"
        return "${pub.displayName.removeSuffix(".craw")}\n${"%.1f".format(mb)}MB • $date • copies to stage to merge"
    }

    private fun rowText(row: BurstTakeRow): String {
        val mb = row.fileBytes / 1e6
        val dims = if (row.width != null && row.height != null) " • ${row.width}×${row.height}" else ""
        val date = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(row.lastModified))
        return "${row.file.nameWithoutExtension}\n×${row.frameCount} frames • ${"%.1f".format(mb)}MB$dims • $date"
    }

    private fun setMethod(next: BurstMergeRunner.Method) {
        method = next
        prefs().edit().putString(KEY_MERGE_METHOD, next.name).apply()
        refreshOptions()
    }

    private fun setFormat(next: CaptureFormat) {
        format = next
        prefs().edit().putString(KEY_MERGE_OUTPUT_FORMAT, next.name).apply()
        refreshOptions()
    }

    private fun setDevelopedFormat(next: DevelopedFormat) {
        developedFormat = next
        prefs().edit().putString(MainActivity.KEY_DEVELOPED_FORMAT, next.name).apply()
        refreshOptions()
    }

    private fun refreshOptions() {
        if (!::methodMfsr.isInitialized) return
        stylePick(methodMfsr, method == BurstMergeRunner.Method.MFSR)
        stylePick(methodHdrPlus, method == BurstMergeRunner.Method.HDRPLUS)
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
        runButton.isEnabled = false
        statusLine.text = ""
        progressLine.text = "Starting…"
        val prefs = prefs()
        val inputs = BurstMergeRunner.Inputs(
            mfsr = MfsrSettings.fromPreferences(prefs.all),
            hdrPlus = HdrPlusSettings.fromPreferences(prefs.all),
            jpeg = MergeScreenSettings.jpeg(prefs),
            denoise = MergeScreenSettings.denoise(prefs),
            jxl = MergeScreenSettings.jxl(prefs),
            developedFormat = developedFormat
        )
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
                val result = BurstMergeRunner(this).run(take, method, format, inputs) { progress ->
                    runOnUiThread { progressLine.text = progressText(take, progress) }
                }
                reloadTakes()
                runOnUiThread {
                    mergeRunning.set(false)
                    runButton.isEnabled = true
                    progressLine.text = "Done • ${result.outputs.size} output${if (result.outputs.size == 1) "" else "s"}"
                    statusLine.text =
                        "${if (method == BurstMergeRunner.Method.MFSR) "MFSR" else "HDR+"} " +
                            "×${result.keptFrames}/${result.pooledFrames} • " +
                            "${result.width}×${result.height} • " +
                            "${result.mergeMs}ms merge • " +
                            result.outputs.joinToString(" • ")
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    mergeRunning.set(false)
                    runButton.isEnabled = true
                    progressLine.text = "Merge failed"
                    statusLine.text = (failure.message ?: failure.javaClass.simpleName).take(160)
                }
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
        this.text = text
        setTextColor(getColor(R.color.text_primary))
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        letterSpacing = 0.08f
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun desc(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_secondary))
        textSize = 11f
        setPadding(0, 0, 0, dp(4))
    }

    private fun optionButton(label: String): Button = Button(this).apply { text = label }

    private fun rowParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        }

    private fun stylePick(button: Button, active: Boolean) {
        button.background = getDrawable(
            if (active) R.drawable.control_chip_active else R.drawable.control_chip)
        button.setTextColor(getColor(
            if (active) R.color.accent_dark else R.color.text_primary))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val KEY_MERGE_METHOD = "burst_merge_method"
        const val KEY_MERGE_OUTPUT_FORMAT = "burst_merge_output_format"
    }
}
