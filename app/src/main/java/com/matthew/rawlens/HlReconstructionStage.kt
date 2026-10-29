// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Still-capture highlight reconstruction (RT "Color propagation") as a
// camera-linear tap stage. Runs after demosaic, before white balance.
// The RGBA16F readback is black-subtracted, white-normalized (the wavelet
// stage's documented contract); this stage rescales to raw units for the
// RT chain — whose constants are absolute — and back on pack, so the
// downstream re-upload sees the exact tap contract it always has
// (range-normalized: sensor white is 1.0, black is 0.0).
//
// PATH TODAY: CPU ([HlVulkanRecon.reconstructCpu], fully parity-gated).
// The Vulkan dispatches ([HlVulkanRecon.runVulkan]) are proven on-device
// by tools/hlmeasure and plug into [HlVulkanRecon.run] once a
// long-lived hub session exists on the develop worker thread; until then
// the switch below means "reconstruct on CPU", never "fail the capture".
// The stage returns the caller's buffer in every case: untouched when
// disabled, unclipped, or over heap budget — rewritten in place when
// reconstructed (the readback has no other readers, and a second 100 MB
// output buffer does not fit the heap). The capture never fails for
// highlight reasons.
//
// LOGGING (logcat tag "HlRecon", same shape as WaveletDenoiser): per-stage
// milliseconds plus a one-line summary per photo — the numbers the
// Vulkan-vs-CPU optimization decision needs.
package com.matthew.rawlens

import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder

object HlReconstructionStage {
    const val LOG_TAG = "HlRecon"

    /** RT highlight band used by the stage (matches the parity tests). */
    const val HLBL_DEFAULT = 2

    data class Inputs(
        val whiteLevel: Float,
        val blackLevels: FloatArray,
        val xyzRow: FloatArray
    )

    /**
     * Reconstructs clipped highlights in [camera] (RGBA16F interleaved,
     * raw scale) when [enabled]. Returns the input buffer unchanged when
     * disabled, when levels are missing, or when nothing is clipped.
     * Never throws for highlight reasons: unexpected failures log and
     * return the input.
     */
    internal fun applyIfEnabled(
        camera: ByteBuffer,
        width: Int,
        height: Int,
        enabled: Boolean,
        inputs: Inputs,
        /** Per-photo Vulkan session factory; null runs the CPU twin only. */
        gpuProvider: (() -> VulkanHub.GpuContext?)? = null,
        /**
         * Row/tile worker pool; null runs sequentially. Bands and tiles
         * are disjoint and reduces are order-fixed, so parallel execution
         * is bit-identical to sequential.
         */
        workers: java.util.concurrent.ExecutorService? = null
    ): ByteBuffer {
        if (!enabled) return camera
        val t0 = SystemClock.elapsedRealtime()
        return try {
            apply(camera, width, height, inputs, t0, gpuProvider, workers)
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "reconstruction failed, developing without it", failure)
            camera
        } catch (oom: OutOfMemoryError) {
            // OOM is an Error, not an Exception: an untiled CPU fallback on
            // a giant ROI can still exhaust the heap where the tiled Vulkan
            // path fits. Never fail the capture for it.
            Log.w(LOG_TAG, "highlight stage out of memory, developing without it")
            camera
        }
    }

    private fun apply(
        camera: ByteBuffer,
        width: Int,
        height: Int,
        inputs: Inputs,
        t0: Long,
        gpuProvider: (() -> VulkanHub.GpuContext?)? = null,
        workers: java.util.concurrent.ExecutorService? = null
    ): ByteBuffer {
        require(width % 2 == 0 && height % 2 == 0) {
            "ROI dims must be even (RT bufferW padding)"
        }
        val shorts = camera.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        require(shorts.remaining() >= width * height * 4) {
            "readback too small: ${shorts.remaining()} shorts for ${width}x$height RGBA"
        }
        val white = inputs.whiteLevel
        require(white.isFinite() && white > 0f) { "white level $white is unusable" }
        val blackAvg = if (inputs.blackLevels.isNotEmpty()) {
            inputs.blackLevels.average().toFloat()
        } else 0f
        // RT convention (parity tests): chmax is the per-channel white
        // level, clmax the per-channel clip point (white minus black), not
        // the black level itself. Typical result: maxF = 0.95 * white, so
        // only genuinely saturated pixels enter the ROI.
        val t = RtColorPropagation.computeThresholds(
            FloatArray(3) { white }, FloatArray(3) { white - blackAvg }
        )
        // Converted holds the frame in raw units; the bulk input copy
        // dies with this call, before the chain allocates its temporaries.
        // Peak retained here is the three channel planes plus one channel
        // of alpha shorts — the 12 MP heap cannot afford input, alpha
        // float, and output copies on top of the chain.
        val conv = convertTap(shorts, width, height, white, blackAvg, workers)
        val tConvert = SystemClock.elapsedRealtime()
        val tScan = SystemClock.elapsedRealtime()
        val rawRoi = RtHighlightMap.findRoi(conv.red, conv.green, conv.blue, t.maxF)
            ?: run {
                Log.i(
                    LOG_TAG,
                    "no clipped region in ${width}x$height, skipping " +
                        "(peakAbs=${conv.peak} white=$white blackAvg=$blackAvg " +
                        "convert=${tConvert - t0}ms scan=${SystemClock.elapsedRealtime() - tScan}ms)"
                )
                return camera
            }
        // RT pads its blur stride to a multiple of 16 (bufferW); the port
        // instead requires the ROI width to carry the multiple, so expand
        // the ROI out to it — never shrink, so no clipped pixel is dropped.
        // Height only needs to stay even (half-res buffers).
        val roi = snapRoi(rawRoi, width, height)
            ?: run {
                Log.i(LOG_TAG, "roi ${rawRoi.width}x${rawRoi.height} cannot snap to frame, skipping")
                return camera
            }
        // Tiled pre-stages: bounded memory at any ROI size (tile windows
        // carry halo context, so interiors match the untiled chain; the
        // directional scans stay full-map at quarter-res with true dims).
        val maps = HlTiledPrestage.run(conv.red, conv.green, conv.blue, t.thresh, t.maxF, roi, workers)
        val tStage = SystemClock.elapsedRealtime()
        val gamma = RtGuidedFilter.gammaTable()
        val guided = RtGuidedFilter.guidedParams(HLBL_DEFAULT)
        // Reconstruct arm: Vulkan always (GPU-side buffers ignore the Java
        // heap, and the dispatches parallelize the guided passes); the
        // exact CPU twin is the failure fallback. No heap pre-checks — the
        // tiling above bounds memory by construction, and any failure still
        // returns the readback through the catch in applyIfEnabled.
        var path = "cpu"
        var tVulkan = 0L
        var tGpuSetup = 0L
        var tGpuTiles = 0L
        var reconstructed = false
        if (gpuProvider != null) {
            val tVk0 = SystemClock.elapsedRealtime()
            try {
                val tProv0 = SystemClock.elapsedRealtime()
                val gpu = gpuProvider() ?: throw IllegalStateException("no Vulkan session")
                tGpuSetup = SystemClock.elapsedRealtime() - tProv0
                // Tiled reconstruct: each tile window uploads small (the
                // full-ROI interleave would park ~200 MB on the Java heap
                // and OOM the develop), dispatches the same 68-op plan on
                // tile dims, and commits only its interior — halo context
                // covers the guided radii, so interiors match a full-ROI
                // run. One session for all tiles.
                gpu.use { ctx ->
                    for ((window, interior) in HlTiledPrestage.tiles(
                        roi, HlTiledPrestage.GTILE, HlTiledPrestage.GHALO
                    )) {
                        require(window.width % 4 == 0 && window.height % 4 == 0) {
                            "unaligned GPU tile window dims"
                        }
                        val mx0 = window.minX - roi.minX
                        val my0 = window.minY - roi.minY
                        require(mx0 % 4 == 0 && my0 % 4 == 0) {
                            "unaligned GPU tile window"
                        }
                        val tileMaps = HlTiledPrestage.sliceMaps(
                            maps, mx0 / 4, my0 / 4, window.width / 4, window.height / 4
                        )
                        val tTile0 = SystemClock.elapsedRealtime()
                        HlVulkanRecon.run(
                            ctx, conv.red, conv.green, conv.blue, t, tileMaps,
                            window, inputs.xyzRow, gamma, guided, false, interior
                        )
                        tGpuTiles += SystemClock.elapsedRealtime() - tTile0
                    }
                }
                path = "vulkan"
                reconstructed = true
            } catch (failure: Exception) {
                Log.w(LOG_TAG, "HL Vulkan failed, CPU fallback", failure)
            } finally {
                tVulkan = SystemClock.elapsedRealtime() - tVk0
            }
        }
        if (!reconstructed) {
            // Last resort: the exact CPU twin. It is untiled, so on a giant
            // ROI it can OOM where Vulkan fits — check the heap first and
            // let the OOM catch above backstop the rest. This is a failure
            // path, not a skip: Vulkan already tried.
            if (cpuFitsHeap(roi.width.toLong() * roi.height.toLong())) {
                HlVulkanRecon.reconstructCpu(conv.red, conv.green, conv.blue, t, maps, roi, inputs.xyzRow, gamma, guided)
            } else {
                Log.i(LOG_TAG, "roi ${roi.width}x${roi.height}: Vulkan failed and CPU twin doesn't fit, no reconstruction")
                return camera
            }
        }
        val tRecon = SystemClock.elapsedRealtime()
        // Pack back into the caller's buffer (same object, new content):
        // the readback has no other readers past this point, and reusing
        // it saves a 100 MB output allocation the heap does not have.
        // Tap units again: norm = (raw - blackAvg) / range (the exact
        // inverse of convertTap), one row at a time through a small line
        // buffer with bulk puts.
        val invRange = 1f / (white - blackAvg)
        // Bands pack disjoint rows straight into the caller's buffer (each
        // band owns its ShortBuffer view, so positions never race).
        fun packBand(y0: Int, y1: Int) {
            val view = camera.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
            val line = ShortArray(width * 4)
            for (y in y0 until y1) {
                val rRow = conv.red[y]
                val gRow = conv.green[y]
                val bRow = conv.blue[y]
                var q = 0
                for (x in 0 until width) {
                    line[q] = packHalf((rRow[x] - blackAvg) * invRange)
                    line[q + 1] = packHalf((gRow[x] - blackAvg) * invRange)
                    line[q + 2] = packHalf((bRow[x] - blackAvg) * invRange)
                    line[q + 3] = conv.alpha[y * width + x]
                    q += 4
                }
                view.position(y * width * 4)
                view.put(line)
            }
        }
        if (workers == null) {
            packBand(0, height)
        } else {
            val futures = rowBands(height).map { (y0, y1) ->
                workers.submit { packBand(y0, y1) }
            }
            for (f in futures) f.get()
        }
        camera.rewind()
        Log.i(
            LOG_TAG,
            "hlrecon ${width}x$height roi=${roi.width}x${roi.height}@${roi.minX},${roi.minY} " +
                "path=$path convert=${tConvert - t0}ms stage=${tStage - tConvert}ms " +
                "recon=${tRecon - tStage}ms gpu=${tVulkan}ms setup=${tGpuSetup}ms tiles=${tGpuTiles}ms " +
                "pack=${SystemClock.elapsedRealtime() - tRecon}ms " +
                "total=${SystemClock.elapsedRealtime() - t0}ms"
        )
        return camera
    }



    /**
     * True when the untiled CPU twin's temporaries (~60 bytes/px) fit with
     * room to spare. Failure-path use only (CPU fallback after Vulkan
     * failed) — the primary paths are bounded by construction.
     */
    private fun cpuFitsHeap(roiPx: Long): Boolean {
        val rt = Runtime.getRuntime()
        val headroom = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        return roiPx * 60L < headroom / 3
    }

    private class Converted(
        val red: Array<FloatArray>,
        val green: Array<FloatArray>,
        val blue: Array<FloatArray>,
        /** Untouched tap alpha, raw half bits (no rescale). */
        val alpha: ShortArray,
        /** Peak channel value in raw units (diagnostics). */
        val peak: Float
    )

    /**
     * Bulk-copies the RGBA16F tap once, then converts to raw channel
     * planes in one tight inlined pass (per-pixel calls cost seconds on
     * 12 MP debug builds). The tap is black-subtracted, range-normalized;
     * the RT chain works in raw units: raw = norm * (white - blackAvg) + blackAvg.
     */
    private fun convertTap(
        shorts: java.nio.ShortBuffer,
        width: Int,
        height: Int,
        white: Float,
        blackAvg: Float,
        workers: java.util.concurrent.ExecutorService? = null
    ): Converted {
        val n = width * height
        // The tap is black-subtracted and range-normalized (the demosaic
        // CFA holds (code - black) / (white - black), so sensor white is
        // exactly 1.0): the RT chain's raw domain is norm * range + black.
        // Scaling by white instead would inflate every pixel by norm *
        // black and flag unclipped near-whites as clipped.
        val range = white - blackAvg
        // No full-frame packed copy: a 12 MP ShortArray costs 100 MB the
        // heap does not have next to the planes. Each band streams its rows
        // through a small line buffer with bulk gets instead (same half
        // bits in the same order, so bit-identical output).
        val red = Array(height) { FloatArray(width) }
        val green = Array(height) { FloatArray(width) }
        val blue = Array(height) { FloatArray(width) }
        val alpha = ShortArray(n)
        // One row band per worker (contiguous bands keep caches hot);
        // bands are disjoint and the peak reduce is order-free (max). Each
        // band owns its ShortBuffer view and line buffer, so rows never race.
        fun convertBand(y0: Int, y1: Int): Float {
            var peak = 0f
            val view = shorts.duplicate()
            val line = ShortArray(width * 4)
            for (y in y0 until y1) {
                val rRow = red[y]
                val gRow = green[y]
                val bRow = blue[y]
                view.position(y * width * 4)
                view.get(line)
                var k = 0
                for (x in 0 until width) {
                    val r = unpackHalf(line[k].toInt() and 0xffff) * range + blackAvg
                    val g = unpackHalf(line[k + 1].toInt() and 0xffff) * range + blackAvg
                    val b = unpackHalf(line[k + 2].toInt() and 0xffff) * range + blackAvg
                    alpha[y * width + x] = line[k + 3]
                    // RT's float32 math never sees non-finite pixels: Infs
                    // clamp to the finite half range (still >= maxF, still
                    // reconstructed), NaN becomes white (a lone corrupt
                    // pixel then joins the ROI and is inpainted). NOTE for
                    // the Vulkan path: the shader reads the same texture
                    // bits, so it needs the equivalent clamp op when it
                    // wires up. Plain comparisons: stdlib coerceIn/isNaN
                    // are calls, and 3 x 12.5M calls show up on a phone CPU.
                    rRow[x] = if (r != r) white else if (r > 65504f) 65504f else if (r < -65504f) -65504f else r
                    gRow[x] = if (g != g) white else if (g > 65504f) 65504f else if (g < -65504f) -65504f else g
                    bRow[x] = if (b != b) white else if (b > 65504f) 65504f else if (b < -65504f) -65504f else b
                    if (rRow[x] > peak) peak = rRow[x]
                    if (gRow[x] > peak) peak = gRow[x]
                    if (bRow[x] > peak) peak = bRow[x]
                    k += 4
                }
            }
            return peak
        }
        val peak = if (workers == null) {
            convertBand(0, height)
        } else {
            val bands = rowBands(height)
            val futures = bands.map { (y0, y1) -> workers.submit<Float> { convertBand(y0, y1) } }
            var p = 0f
            for (f in futures) {
                val b = f.get()
                if (b > p) p = b
            }
            p
        }
        return Converted(red, green, blue, alpha, peak)
    }

    /**
     * Contiguous row bands covering [0, height): four chunks (matches the
     * shared pool; any pool just runs them). Bands are disjoint, so
     * parallel bands are bit-identical to one sequential pass.
     */
    internal fun rowBands(height: Int): List<Pair<Int, Int>> {
        val step = (height + 3) / 4
        val out = ArrayList<Pair<Int, Int>>()
        var y = 0
        while (y < height) {
            val y1 = minOf(y + step, height)
            out.add(y to y1)
            y = y1
        }
        return out
    }

    /**
     * Expands [roi] to the port's window contract (width a multiple of 32,
     * height even), clamped to the frame. The 32-multiple keeps
     * boxblur_resamp's 8-column blocked vertical path aligned in the tiled
     * pre-stage (bit-exact, no scalar-remainder columns); 32 implies the
     * boxBlur2 16-multiple. Expansion only ever adds blur context — the
     * clipped extent is always covered. The origin aligns to the 32-grid
     * best-effort (the frame-edge clamp wins when they conflict).
     * Never refuses for width: when the frame cannot host the snapped span
     * (e.g. a full-frame ROI on a width-%-32 frame), the ROI keeps the full
     * frame width — boxBlur2's scalar tail covers the odd columns and the
     * tiled pre-stage degrades to bounded block-phase seams, both gated.
     * Returns null only for degenerate frames.
     */
    internal fun snapRoi(roi: HlRoi, frameW: Int, frameH: Int): HlRoi? {
        if (frameW <= 0 || frameH <= 0) return null
        val needW = ((roi.width + 31) / 32) * 32
        var minX = (roi.minX / 32) * 32
        var maxX = minX + needW - 1
        if (maxX >= frameW) {
            // Frame-edge clamp (covers the clipped extent; alignment
            // best-effort). When the frame cannot host the span at all,
            // this degrades to the full frame width — still every clipped
            // pixel, via boxBlur2's scalar tail.
            maxX = frameW - 1
            minX = maxOf(0, maxX - needW + 1)
        }
        // Origin on the 4-grid, height a multiple of 4: GPU tile windows
        // and map slices then divide evenly everywhere (the %4 requires in
        // the tile loop become unreachable). Evenness follows from %4.
        var minY = roi.minY - roi.minY % 4
        var maxY = roi.maxY
        if ((maxY - minY + 1) % 2 != 0) {
            if (maxY + 1 < frameH) maxY++ else minY--
        }
        if ((maxY - minY + 1) % 4 != 0) {
            if (maxY + 2 < frameH) maxY += 2 else minY -= 2
        }
        if (minY < 0 || maxY >= frameH) return null
        return HlRoi(minX, minY, maxX, maxY)
    }

    /**
     * Exact IEEE-754 binary16 pack, round-to-nearest-even. Pure Kotlin so
     * the stage stays unit-testable on the JVM (android.util.Half returns
     * stub zeros there). Matches Half.toHalf bit-for-bit on finite inputs.
     */
    internal inline fun packHalf(value: Float): Short {
        val bits = value.toBits()
        val sign = (bits ushr 16) and 0x8000
        val exp = (bits ushr 23) and 0xff
        val mant = bits and 0x7fffff
        val half = when {
            exp == 0xff -> if (mant == 0) 0x7c00 else 0x7e00
            exp > 142 -> 0x7c00
            exp < 113 -> {
                // Half-subnormal LSB is 2^-24; float value is m24 * 2^(exp-150),
                // so the 10-bit result is round(m24 / 2^(126-exp)).
                val shift = 126 - exp
                if (shift >= 25) {
                    0
                } else {
                    val m = mant or 0x800000
                    val dropped = m and ((1 shl shift) - 1)
                    val halfway = 1 shl (shift - 1)
                    var h = m ushr shift
                    if (dropped > halfway || (dropped == halfway && (h and 1) == 1)) h += 1
                    if (h >= 0x400) 0x0400 else h
                }
            }
            else -> {
                var h = ((exp - 112) shl 10) or (mant ushr 13)
                val dropped = mant and 0x1fff
                if (dropped > 0x1000 || (dropped == 0x1000 && (h and 1) == 1)) {
                    h += 1
                    if (h >= 0x7c00) return (sign or 0x7c00).toShort()
                }
                h
            }
        }
        return (sign or half).toShort()
    }

    /** Exact IEEE-754 binary16 unpack. */
    internal inline fun unpackHalf(half: Int): Float {
        val sign = (half ushr 15) and 1
        val exp = (half ushr 10) and 0x1f
        val mant = half and 0x3ff
        val bits = when {
            exp == 0 -> if (mant == 0) {
                sign shl 31
            } else {
                var e = -14
                var m = mant
                while ((m and 0x400) == 0) {
                    m = m shl 1
                    e--
                }
                (sign shl 31) or ((e + 127) shl 23) or ((m and 0x3ff) shl 13)
            }
            exp == 0x1f -> (sign shl 31) or (0xff shl 23) or (mant shl 13)
            else -> (sign shl 31) or ((exp - 15 + 127) shl 23) or (mant shl 13)
        }
        return Float.fromBits(bits)
    }
}
