// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Plain width/height pair for session-combo selection. `android.util.Size`
 * is a framework stub under JVM unit tests, so the ladder below runs on
 * this type and the camera thread maps to/from `Size` at the boundary.
 */
internal data class SessionStreamSize(val width: Int, val height: Int) {
    val area: Long get() = width.toLong() * height
}

/**
 * One session attempt: RAW size, preview size, and reader queue depth.
 * A null preview is a RAW-only session (no preview stream at all) for
 * HALs that do not support RAW+preview in any combination.
 */
internal data class SessionStreamCombo(
    val raw: SessionStreamSize,
    val preview: SessionStreamSize?,
    val readerMaxImages: Int,
    val label: String
)

/**
 * Session stream selection: RAW/preview stream combos and sizes, per-lens size
 * and packed-format preferences, the ISP frame-rate probe, and the RAW reader
 * depth / ZSL ring capacity it implies.
 *
 * Moved verbatim from RawCameraController's companion (A10,
 * docs/refactor/controller-remaining-plan.md).
 */
internal object SessionStreamSelection {
    /** Label of the default raw-only step (full RAW, ZSL-capable reader). */
    internal const val RAW_ONLY_FULL_LABEL = "RAW ONLY FULL"

    internal const val ASPECT_RATIO_TOLERANCE = 0.015

    /**
     * Below this measured RAW rate the ISP is considered stalled: the
     * session rebuilds with a normal preview target, which unlocks
     * full rate on HALs that need any preview request (MediaTek).
     */
    // 24 fps is a legitimate RAW cadence on Xiaomi aux routes. The old
    // 25 fps gate rebuilt a working 6/4 or 6/5 session into RAW+preview,
    // which crashes that HAL. Keep margin above the observed 20 fps stall.
    internal const val ISP_STALL_FPS = 22.5

    /** Overlap/lag headroom inside ImageReader maxImages: absorbs result-lag
     * spikes while the ZSL ring streams at full rate (plus the pairing
     * window and transition slack), so transient HAL stalls degrade to
     * dropped ring frames instead of overflow storms. Small enough (+4
     * full-res buffers) to stay clear of gralloc/Mali pressure. The ring
     * itself is only ever restarted with zero saves held (see
     * resumeRawZslIfIdle), so these slots never cover save-held Images. */
    internal const val ZSL_REARM_OVERLAP_SLOTS = 4

    // Hard ceiling for the RAW ImageReader queue. The reference HAL
    // configures a session with at most 61 RAW buffers and aborts the
    // process when ImageReader exceeds 62, so the computed depth
    // (ring + pairing + transition + rearm headroom) clamps here.
    internal const val READER_MAXIMAGES_HARD_CAP = 61

    internal const val RAW_PREVIEW_RESERVED_SLOTS = RawCameraController.ZSL_PAIR_WINDOW_SLOTS + RawCameraController.RAW_READER_TRANSITION_SLOTS + 2

    internal const val MIN_ACQUIRED_RAW_IMAGES = RawCameraController.BURST_FRAME_COUNT + 2

    /** RAW-only first. When a preview is required, retain a normal advertised preview
     * while reducing reader depth and RAW size on session rejection. */
    internal fun selectSessionStreamCombos(
        rawSizes: List<SessionStreamSize>,
        previewSizes: List<SessionStreamSize>,
        fullReaderMaxImages: Int,
        reducedReaderMaxImages: Int
    ): List<SessionStreamCombo> {
        val fullRaw = rawSizes.maxByOrNull { it.area } ?: return emptyList()
        val combos = mutableListOf<SessionStreamCombo>()
        if (fullReaderMaxImages != reducedReaderMaxImages) {
            combos += SessionStreamCombo(fullRaw, null, fullReaderMaxImages, RAW_ONLY_FULL_LABEL)
        }
        if (previewSizes.isNotEmpty()) {
            val fullPreview = selectFullPreviewSize(previewSizes, fullRaw)
            val fallbackPreview = selectFallbackPreviewSize(previewSizes, fullRaw)
            combos += SessionStreamCombo(fullRaw, fullPreview, fullReaderMaxImages, "FULL")
            combos += SessionStreamCombo(fullRaw, fallbackPreview, reducedReaderMaxImages, "MIN BUFFERS")
            selectSmallerRawSize(rawSizes)?.let { smaller ->
                combos += SessionStreamCombo(smaller, fallbackPreview, reducedReaderMaxImages, "SMALL RAW")
            }
        }
        combos += SessionStreamCombo(fullRaw, null, reducedReaderMaxImages, "RAW ONLY")
        // Cropped sensor modes can sit between the largest and smallest sizes
        // (MCPro's Xiaomi 14 Ultra DCG16 profiles use 4096x2304). The
        // half-area preview step above skips these, so keep them reachable
        // both for fallback and for an explicit per-lens size preference.
        val smallestRaw = rawSizes.minByOrNull { it.area }
        rawSizes.distinct().sortedByDescending { it.area }
            .filter { it != fullRaw && it != smallestRaw }
            .forEach { intermediate ->
                combos += SessionStreamCombo(intermediate, null, fullReaderMaxImages, "RAW ONLY CROP")
                combos += SessionStreamCombo(intermediate, null, reducedReaderMaxImages, "RAW ONLY CROP MIN BUFFERS")
            }
        rawSizes.minByOrNull { it.area }?.let { smallest ->
            combos += SessionStreamCombo(smallest, null, reducedReaderMaxImages, "RAW ONLY MIN")
        }
        val deduped = mutableListOf<SessionStreamCombo>()
        for (combo in combos) {
            val prev = deduped.lastOrNull()
            if (prev == null || prev.raw != combo.raw || prev.preview != combo.preview ||
                prev.readerMaxImages != combo.readerMaxImages
            ) {
                deduped += combo
            }
        }
        return deduped
    }

    /** Advertised normal preview, bounded by 1080p; prefer the RAW aspect ratio.
     * Do not request a tiny ISP placeholder when an HD stream is available.
     */
    internal fun selectFullPreviewSize(
        previewSizes: List<SessionStreamSize>,
        rawSize: SessionStreamSize? = null
    ): SessionStreamSize {
        require(previewSizes.isNotEmpty())
        val bounded = previewSizes.filter { maxOf(it.width, it.height) <= 1920 &&
            minOf(it.width, it.height) <= 1080 }
        val hd = bounded.filter { maxOf(it.width, it.height) >= 1280 &&
            minOf(it.width, it.height) >= 720 }
        val candidates = hd.ifEmpty { bounded }.ifEmpty { previewSizes }
        val matching = if (rawSize == null) emptyList() else candidates.filter {
            kotlin.math.abs(it.width.toDouble() / it.height -
                rawSize.width.toDouble() / rawSize.height) <= ASPECT_RATIO_TOLERANCE
        }
        return matching.ifEmpty { candidates }.maxBy { it.area }
    }

    /** Buffer/RAW retries retain a normal ISP preview instead of reducing its resolution. */
    internal fun selectFallbackPreviewSize(
        previewSizes: List<SessionStreamSize>, rawSize: SessionStreamSize
    ): SessionStreamSize = selectFullPreviewSize(previewSizes, rawSize)

    /**
     * Largest RAW size at most half the sensor area: one decisive bandwidth
     * step instead of a slow walk through near-identical remosaic sizes.
     * Null when no such step exists (single RAW size).
     */
    internal fun selectSmallerRawSize(rawSizes: List<SessionStreamSize>): SessionStreamSize? {
        val largest = rawSizes.maxByOrNull { it.area } ?: return null
        return rawSizes.filter { it.area * 2 <= largest.area }.maxByOrNull { it.area }
    }

    /**
     * User size preference as a stable ladder reorder: combos carrying
     * the preferred RAW size move first with their relative order kept,
     * everything else follows untouched, so HAL rejection still degrades
     * exactly as before. An explicit crop first gets a full-depth RAW-only
     * attempt, even when it only appeared as a minimal-buffer fallback.
     * Null (auto) or a size with no combo returns the
     * ladder unchanged.
     */
    internal fun orderCombosForPreferredSize(
        combos: List<SessionStreamCombo>,
        preferred: SessionStreamSize?
    ): List<SessionStreamCombo> {
        if (preferred == null || combos.none { it.raw == preferred }) return combos
        val (matching, rest) = combos.partition { it.raw == preferred }
        // An explicit crop must not inherit a last-resort reader's reduced
        // capture capacity (Xiaomi DCG16: 4096x2304 is the smallest size).
        val fullDepth = combos.maxOf { it.readerMaxImages }
        if (matching.none { it.preview == null && it.readerMaxImages == fullDepth }) {
            return listOf(SessionStreamCombo(preferred, null, fullDepth, "PREFERRED RAW ONLY FULL")) +
                matching + rest
        }
        return matching + rest
    }

    /** Pure + host-tested: stored "WxH" into a combo size, else null. */
    internal fun parseRawSizePreference(text: String?): SessionStreamSize? {
        val normalized = RawSizePreferenceStore.normalizeSize(text) ?: return null
        val (width, height) = normalized.split('x').map(String::toInt)
        return SessionStreamSize(width, height)
    }

    /**
     * Pure + host-tested: stored format name into a session format int.
     * Anything but an available packed format resolves to `RAW_SENSOR`,
     * so packed capture stays strictly opt-in and stale choices degrade
     * to today's behavior instead of wedging the session.
     */
    internal fun resolveRawFormatPreference(
        wanted: String?,
        availableFormats: Set<Int>
    ): Int {
        if (wanted == null) return android.graphics.ImageFormat.RAW_SENSOR
        val packed = runCatching { PackedRawFormat.valueOf(wanted) }.getOrNull()
            ?: return android.graphics.ImageFormat.RAW_SENSOR
        val format = packed.toImageFormat()
        return if (format in availableFormats) format else android.graphics.ImageFormat.RAW_SENSOR
    }

    /**
     * Measured RAW rate over the probe window, from sensor timestamps
     * (monotonic on any timestamp source — only differences matter).
     */
    internal fun rawFpsForProbeWindow(frames: Int, elapsedNs: Long): Double =
        if (elapsedNs <= 0L) Double.POSITIVE_INFINITY else frames * 1e9 / elapsedNs

    /** Below-threshold rate means the ISP holds the pipe: add the preview target. */
    internal fun previewRequestNeededForFps(fps: Double): Boolean = fps < ISP_STALL_FPS

    /** Continuous-AF results needed before a silent AF counts as dead (~1.5 s at 30 fps). */
    internal const val THREE_A_PROBE_MIN_AF_FRAMES = 45

    /**
     * 3A probe verdict for a raw-only session: the preview target is added
     * only when nothing in AF/AE/AWB showed life and continuous AF was
     * observable long enough to prove it never left INACTIVE. Too few
     * continuous-AF results (manual or fixed focus) is no verdict.
     */
    internal fun previewRequestNeededFor3a(continuousAfFrames: Int, anyAlive: Boolean): Boolean =
        !anyAlive && continuousAfFrames >= THREE_A_PROBE_MIN_AF_FRAMES

    /**
     * First ladder step carrying any preview target (the 20 fps remedy),
     * or -1 when the ladder is raw-only throughout. The probe jumps here
     * — never backwards past already-rejected preview steps, so a
     * stillborn-degraded tail cannot loop back onto a refused combo.
     */
    internal fun firstPreviewComboIndex(combos: List<SessionStreamCombo>): Int =
        combos.indexOfFirst { it.preview != null }

    /**
     * Pure reader sizing behind [fullReaderMaxImages]: ring plus pairing,
     * transition, and rearm headroom, clamped to the HAL queue hard cap.
     * Unit-testable without the Android framework.
     */
    internal fun fullReaderMaxImagesFor(frameCount: Int): Int = minOf(
        maxOf(
            MIN_ACQUIRED_RAW_IMAGES,
            maxOf(frameCount, RawCameraController.MAX_IN_FLIGHT_JPEG_SAVES) + RAW_PREVIEW_RESERVED_SLOTS +
                ZSL_REARM_OVERLAP_SLOTS
        ),
        READER_MAXIMAGES_HARD_CAP
    )

    /** Forward capture needs save slots + live-consumer reserve, not a ZSL ring/rearm. */
    internal fun readerMaxImagesFor(frameCount: Int, zslRequested: Boolean): Int =
        if (zslRequested) fullReaderMaxImagesFor(frameCount) else
            maxOf(MIN_ACQUIRED_RAW_IMAGES,
                RawCameraController.MAX_IN_FLIGHT_JPEG_SAVES + RAW_PREVIEW_RESERVED_SLOTS)

    /** Expand only at an idle boundary; never tear down save-owned Images. */
    internal fun needsZslReaderUpgrade(zslRequested: Boolean, ringBudget: Boolean,
                                      captureActive: Boolean, pendingSaves: Int): Boolean =
        zslRequested && !ringBudget && !captureActive && pendingSaves == 0

    /**
     * Inverse of the reader sizing in [createSession]: the forward formula
     * keeps `max(capacity, RawCameraController.MAX_IN_FLIGHT_JPEG_SAVES)` plus pairing,
     * transition, and rearm headroom within the reader. A full-size reader
     * clamps back to the requested count; a reader below that floor yields
     * zero (viewfinder-only, no ZSL ring).
     */
    internal fun clampZslCapacityForReader(requestedFrameCount: Int, readerMaxImages: Int): Int {
        val roomForRing = readerMaxImages - RAW_PREVIEW_RESERVED_SLOTS - ZSL_REARM_OVERLAP_SLOTS
        if (roomForRing < RawCameraController.MAX_IN_FLIGHT_JPEG_SAVES) return 0
        return minOf(requestedFrameCount, roomForRing)
    }
}
