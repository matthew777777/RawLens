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

    /** Fallback preview stays usable as a viewfinder at or above this size. */
    internal const val FALLBACK_PREVIEW_MIN_WIDTH = 640

    internal const val FALLBACK_PREVIEW_MIN_HEIGHT = 480

    /**
     * Below this measured RAW rate the ISP is considered stalled: the
     * session rebuilds with the smallest preview target, which unlocks
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

    /**
     * Session ladder, RAW-only first: taps, metering, and all overlays
     * are wired to the RAW viewfinder and the fixed view rect (the
     * system preview is an alpha-0 stream target only), so the default
     * attempt carries no preview target at all — full RAW with a
     * ZSL-capable reader. Some HALs (MediaTek) hold the RAW stream at
     * ~20 fps until any preview target exists; the ISP frame-rate probe
     * detects that stall and rebuilds at FULL, persisting the verdict
     * per camera. HALs that reject the raw-only session outright fall
     * through the same ladder via the stillborn path. Each later step
     * degrades exactly one axis: the smallest preview retry, a larger
     * preview retry, minimal buffers (viewfinder-only, the reader
     * cannot host a ZSL ring), a half-area RAW size, then RAW-only
     * sessions (largest RAW first for capture quality, smallest last for
     * minimum bandwidth). Steps that would retry an identical session are
     * deduped away. An empty preview list yields only the RAW-only steps.
     */
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
            val fullPreview = selectFullPreviewSize(previewSizes)
            val fallbackPreview = selectFallbackPreviewSize(previewSizes, fullRaw)
            combos += SessionStreamCombo(fullRaw, fullPreview, fullReaderMaxImages, "FULL")
            combos += SessionStreamCombo(fullRaw, fallbackPreview, fullReaderMaxImages, "MIN PREVIEW")
            combos += SessionStreamCombo(fullRaw, fallbackPreview, reducedReaderMaxImages, "MIN BUFFERS")
            selectSmallerRawSize(rawSizes)?.let { smaller ->
                combos += SessionStreamCombo(smaller, fallbackPreview, reducedReaderMaxImages, "SMALL RAW")
            }
        }
        combos += SessionStreamCombo(fullRaw, null, reducedReaderMaxImages, "RAW ONLY")
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

    /**
     * Invisible-stream choice: the smallest preview available, requested
     * only so HALs that need a preview target sustain the 30 fps RAW
     * stream. No aspect, coverage, or viewfinder requirement applies —
     * the TextureView is alpha-0 and `fillViewport` ignores aspect.
     */
    internal fun selectFullPreviewSize(
        previewSizes: List<SessionStreamSize>
    ): SessionStreamSize = previewSizes.minByOrNull { it.area }!!

    /**
     * Fallback preview: the smallest matching-aspect stream that stays
     * usable as a viewfinder, else the smallest matching-aspect stream,
     * else the smallest stream overall.
     */
    internal fun selectFallbackPreviewSize(
        previewSizes: List<SessionStreamSize>,
        rawSize: SessionStreamSize
    ): SessionStreamSize {
        val matchingAspect = previewSizes.filter { size ->
            kotlin.math.abs(
                size.width.toDouble() / size.height - rawSize.width.toDouble() / rawSize.height
            ) <= ASPECT_RATIO_TOLERANCE
        }
        return matchingAspect
            .filter { it.width >= FALLBACK_PREVIEW_MIN_WIDTH && it.height >= FALLBACK_PREVIEW_MIN_HEIGHT }
            .minByOrNull { it.area }
            ?: matchingAspect.minByOrNull { it.area }
            ?: previewSizes.minByOrNull { it.area }!!
    }

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
     * exactly as before. Null (auto) or a size with no combo returns the
     * ladder unchanged.
     */
    internal fun orderCombosForPreferredSize(
        combos: List<SessionStreamCombo>,
        preferred: SessionStreamSize?
    ): List<SessionStreamCombo> {
        if (preferred == null || combos.none { it.raw == preferred }) return combos
        val (matching, rest) = combos.partition { it.raw == preferred }
        return matching + rest
    }

    /** Pure + host-tested: stored "WxH" into a combo size, else null. */
    internal fun parseRawSizePreference(text: String?): SessionStreamSize? {
        if (text == null || !RawSizePreferenceStore.isPlausibleSize(text)) return null
        val (width, height) = text.trim().split('x').map(String::toInt)
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
