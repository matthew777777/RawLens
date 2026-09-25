// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// DNG admission for the desktop CPU twin. Independent implementation that
// shares mosaic-desktop's dependency-free TIFF concepts (uncompressed 16-bit
// single-sample CFA strips, CFARepeatPatternDim 2x2, ActiveArea even-trim,
// stored-orientation processing) without sharing its algorithm or CLI.
//
// Units: pixels for geometry, DN for codes, seconds/ISO for exposure.
// DNG tags are authoritative for image geometry and radiometry; manifest
// sidecar values cover only data unreliable in ordinary DNGs.
package com.matthew.burstrecon

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * One admitted burst frame in the normalized merge domain.
 *
 * @param width stored-orientation crop width in pixels (even).
 * @param height stored-orientation crop height in pixels (even).
 * @param pattern Bayer phase at the stored origin.
 * @param sensorLeft full-sensor column of stored pixel (0,0) in pixels.
 * @param sensorTop full-sensor row of stored pixel (0,0) in pixels.
 * @param samples black-subtracted, white-normalized, unclamped CFA samples,
 *   row-major over [width]x[height] (dimensionless, 0 ~= black, 1 ~= white).
 * @param blackLevels four CFA black levels in DN (Camera2 reading order).
 * @param whiteLevel white level in DN.
 * @param noiseProfile six DNG NoiseProfile doubles (R S,O,G S,O,B S,O) or null.
 * @param exposureSec exposure time in seconds (>0 when known).
 * @param iso ISO speed rating (>0 when known).
 * @param orientation EXIF orientation 1..8 (merge runs in stored orientation).
 */
data class AdmittedFrame(
    val file: File,
    val width: Int,
    val height: Int,
    val pattern: BayerPattern,
    val sensorLeft: Int,
    val sensorTop: Int,
    val samples: FloatArray,
    val blackLevels: FloatArray,
    val whiteLevel: Float,
    val noiseProfile: DoubleArray?,
    val exposureSec: Double?,
    val iso: Int?,
    val orientation: Int,
    val make: String,
    val model: String,
    val colorMatrix1: DoubleArray?,
    val colorMatrix2: DoubleArray?,
    val asShotNeutral: DoubleArray?,
    /** DNG CalibrationIlluminant1/2 (EXIF light-source codes, e.g. 21 = D65). */
    val illuminant1: Int = 21,
    val illuminant2: Int? = null,
    /** ForwardMatrix1/2 when present (carried to derived output, never invented). */
    val forwardMatrix1: DoubleArray? = null,
    val forwardMatrix2: DoubleArray? = null,
    /** Opcode-list tags present on the source (e.g. 51009 gain maps): detected
     * and recorded, never silently assumed applied. Lens-shading gain maps
     * are NOT applied by this pipeline (see provenance). */
    val opcodeLists: List<Int> = emptyList(),
    // DESKTOP-ADDITION (sr-vulkan only): raw sensor codes over the admitted
    // crop, row-major [width]x[height], for the SR packed-frame path (which
    // applies its own black/white normalization). Null only for frames built
    // by other producers; read()/crop() below always populate it.
    val codes: ShortArray? = null
)

object DngAdmission {
    /**
     * Reads one DNG frame. Fails loudly on compressed strips, non-CFA
     * photometric interpretation, or unsupported geometry; never guesses.
     */
    fun read(file: File): AdmittedFrame = DngReader.read(file)

    /**
     * Crops an admitted frame in stored pixels. All values must be even so
     * quad alignment is preserved. Phase follows the sensor origin.
     */
    fun crop(frame: AdmittedFrame, x: Int, y: Int, w: Int, h: Int): AdmittedFrame {
        require(x >= 0 && y >= 0 && w >= 4 && h >= 4)
        require(x % 2 == 0 && y % 2 == 0 && w % 2 == 0 && h % 2 == 0) {
            "crop rect must be even (got $x,$y ${w}x$h)"
        }
        require(x + w <= frame.width && y + h <= frame.height) {
            "crop $x,$y ${w}x$h exceeds ${frame.width}x${frame.height}"
        }
        val out = FloatArray(w * h)
        for (row in 0 until h) {
            frame.samples.copyInto(out, row * w, (y + row) * frame.width + x, (y + row) * frame.width + x + w)
        }
        // DESKTOP-ADDITION (sr-vulkan only): crop the retained codes with the
        // same window so codes/samples can never disagree about geometry.
        val outCodes = frame.codes?.let { src ->
            ShortArray(w * h).also { dst ->
                for (row in 0 until h) {
                    src.copyInto(dst, row * w, (y + row) * frame.width + x, (y + row) * frame.width + x + w)
                }
            }
        }
        val left = frame.sensorLeft + x
        val top = frame.sensorTop + y
        // Re-derive the stored-origin phase: full pattern shifted by the
        // frame's own sensor origin, then by the crop offset. Since the frame
        // pattern is already sensorPattern.shifted(sensorLeft, sensorTop),
        // shifting it again by (x, y) gives the crop phase.
        var cells = frame.pattern
        // pattern.shifted(x, y) maps a stored-origin pattern to the crop phase.
        cells = cells.shifted(x, y)
        return frame.copy(
            width = w, height = h, pattern = cells,
            sensorLeft = left, sensorTop = top, samples = out, codes = outCodes
        )
    }

    /** Where each frame's exposure/ISO came from (never invented). */
    enum class ExposureProvenance { EXIF, MANIFEST }

    /**
     * Cross-checks one manifest entry against its decoded DNG and resolves
     * exposure/ISO: DNG EXIF wins when present; the checked manifest sidecar
     * fills gaps. Conflicts (both present, disagreeing) throw instead of
     * silently picking one. Geometry/pattern checks only apply when the
     * sidecar actually declares them (nonzero/non-blank). Sensor origins are
     * intentionally unchecked here because the manifest carries no
     * authoritative origin field (zero is both the default and the common
     * full-frame origin); origins ARE consumed downstream (admit() requires
     * cross-frame agreement and the pipeline seeds intrinsics from them).
     */
    fun resolveExposure(
        entry: BurstManifestFrame,
        frame: AdmittedFrame
    ): Pair<AdmittedFrame, ExposureProvenance> {
        if (entry.width != 0 || entry.height != 0) {
            require(entry.width == frame.width && entry.height == frame.height) {
                "${entry.file}: manifest geometry ${entry.width}x${entry.height} != " +
                    "DNG ${frame.width}x${frame.height}"
            }
        }
        if (entry.cfaPattern.isNotEmpty()) {
            require(entry.cfaPattern == frame.pattern.name) {
                "${entry.file}: manifest CFA ${entry.cfaPattern} != DNG ${frame.pattern.name}"
            }
        }
        var out = frame
        var provenance = ExposureProvenance.EXIF
        val manifestSec = entry.exposureNanos / 1e9
        if (frame.exposureSec != null) {
            val rel = abs(frame.exposureSec - manifestSec) / manifestSec
            require(rel <= 1e-6) {
                "${entry.file}: manifest exposure ${entry.exposureNanos}ns conflicts " +
                    "with DNG EXIF ${frame.exposureSec}s"
            }
        } else {
            // Fallback, never invention: unpopulated sidecar placeholders
            // (zero exposure/ISO) are rejected, not filled silently.
            require(entry.exposureNanos > 0) {
                "${entry.file}: DNG EXIF lacks ExposureTime and the manifest " +
                    "sidecar carries no usable exposure"
            }
            out = out.copy(exposureSec = manifestSec)
            provenance = ExposureProvenance.MANIFEST
        }
        if (frame.iso != null) {
            require(frame.iso == entry.iso) {
                "${entry.file}: manifest ISO ${entry.iso} conflicts with DNG EXIF ${frame.iso}"
            }
        } else {
            require(entry.iso > 0) {
                "${entry.file}: DNG EXIF lacks ISO and the manifest sidecar " +
                    "carries no usable ISO"
            }
            out = out.copy(iso = entry.iso)
            provenance = ExposureProvenance.MANIFEST
        }
        // Color calibration cross-check: the sidecar declares what import
        // saw; a DNG whose matrices drifted (mixed sources, reprocessed
        // files) is rejected. Undeclared (empty) sidecar lists skip the
        // check for manifests written before calibration was recorded.
        if (entry.colorMatrix1.isNotEmpty()) {
            val declared = entry.colorMatrix1
            require(declared.size == 9) { "${entry.file}: manifest ColorMatrix1 must hold 9 values" }
            val actual = frame.colorMatrix1
                ?: throw IllegalArgumentException("${entry.file}: manifest declares ColorMatrix1 but the DNG has none")
            // 1e-6 covers the writer's own 1e-6 rational quantization on
            // re-admitted derived DNGs; import-declared values match exactly.
            require(declared.indices.all { abs(declared[it] - actual[it]) <= 1e-6 }) {
                "${entry.file}: manifest ColorMatrix1 conflicts with DNG calibration"
            }
        }
        if (entry.asShotNeutral.isNotEmpty()) {
            val declared = entry.asShotNeutral
            require(declared.size == 3) { "${entry.file}: manifest AsShotNeutral must hold 3 values" }
            val actual = frame.asShotNeutral
                ?: throw IllegalArgumentException("${entry.file}: manifest declares AsShotNeutral but the DNG has none")
            require(declared.indices.all { abs(declared[it] - actual[it]) <= 1e-6 }) {
                "${entry.file}: manifest AsShotNeutral conflicts with DNG white balance"
            }
        }
        return Pair(out, provenance)
    }

    /**
     * Admits a burst: every frame must share the reference geometry, phase,
     * normalization, and stored orientation. Returns frames in input order.
     * Rejects (by throwing) when fewer than 2 frames are compatible.
     */
    fun admit(frames: List<AdmittedFrame>): List<AdmittedFrame> {
        require(frames.size >= 2) { "need at least 2 frames, got ${frames.size}" }
        val ref = frames[frames.size / 2]
        for (f in frames) {
            require(f.width == ref.width && f.height == ref.height) {
                "${f.file.name}: geometry ${f.width}x${f.height} != reference ${ref.width}x${ref.height}"
            }
            require(f.pattern == ref.pattern) {
                "${f.file.name}: CFA phase ${f.pattern} != reference ${ref.pattern}"
            }
            require(f.sensorLeft == ref.sensorLeft && f.sensorTop == ref.sensorTop) { "sensor origin mismatch" }
            require(f.make == ref.make && f.model == ref.model) { "camera mismatch" }
            // Color calibration must agree across frames (same camera, same
            // processing): per-frame black/white and white balance may vary,
            // but a drifting ColorMatrix means mixed sources. Null matrices
            // only occur for synthetic frames, which must then all be null.
            require((f.colorMatrix1 == null) == (ref.colorMatrix1 == null) &&
                (f.colorMatrix1 == null || f.colorMatrix1.contentEquals(ref.colorMatrix1))) {
                "${f.file.name}: ColorMatrix1 differs from reference calibration"
            }
            require(f.illuminant1 == ref.illuminant1) {
                "${f.file.name}: CalibrationIlluminant1 ${f.illuminant1} != reference ${ref.illuminant1}"
            }
            require(f.orientation == ref.orientation) {
                "${f.file.name}: orientation ${f.orientation} != reference ${ref.orientation}"
            }
        }
        return frames
    }

    /**
     * Unpacks packed 10-bit rows, MSB-first bit order: every 5 bytes hold 4
     * consecutive 10-bit pixels starting at the high bit (p0 in bits 39..30
     * of the 40-bit group). Verified against LibRaw on Redmi 25080RABDG
     * DNGs, whose packing is big-endian within the group despite the
     * little-endian TIFF container. Writes [count] codes into [dst] at
     * [dstPos]. Units: codes (DN).
     */
    internal fun unpack10(src: ByteArray, srcPos: Int, dst: ShortArray, dstPos: Int, count: Int) {
        require(count % 4 == 0) { "packed 10-bit count must be a multiple of 4" }
        var si = srcPos
        var di = dstPos
        val end = dstPos + count
        while (di < end) {
            val b0 = src[si].toInt() and 0xFF
            val b1 = src[si + 1].toInt() and 0xFF
            val b2 = src[si + 2].toInt() and 0xFF
            val b3 = src[si + 3].toInt() and 0xFF
            val b4 = src[si + 4].toInt() and 0xFF
            dst[di] = ((b0 shl 2) or (b1 shr 6)).toShort()
            dst[di + 1] = (((b1 and 0x3F) shl 4) or (b2 shr 4)).toShort()
            dst[di + 2] = (((b2 and 0x0F) shl 6) or (b3 shr 2)).toShort()
            dst[di + 3] = (((b3 and 0x03) shl 8) or b4).toShort()
            si += 5
            di += 4
        }
    }

    // ---- Minimal TIFF/DNG reader (burstrecon-local; concepts mirror mosaic). ----

    private object DngReader {
        fun read(file: File): AdmittedFrame {
            val bytes = file.readBytes()
            require(bytes.size > 8) { "${file.name}: too small to be a TIFF/DNG" }
            val order = when ((bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)) {
                0x4949 -> ByteOrder.LITTLE_ENDIAN
                0x4D4D -> ByteOrder.BIG_ENDIAN
                else -> throw IllegalArgumentException("${file.name}: not a TIFF/DNG (bad byte order mark)")
            }
            val buf = ByteBuffer.wrap(bytes).order(order)
            fun u16(p: Int) = buf.getShort(p).toInt() and 0xFFFF
            fun u32(p: Int) = (buf.getInt(p).toLong() and 0xFFFFFFFFL).toInt()
            require(u16(2) == 42) { "${file.name}: not a TIFF/DNG (bad magic)" }

            var ifd = u32(4)
            val root = readIfd(buf, bytes, ifd)
            val visited = mutableSetOf<Int>()
            val pending = ArrayDeque<Int>(); pending.add(ifd)
            var main: Map<Int, Entry> = emptyMap()
            while (pending.isNotEmpty()) {
                ifd = pending.removeFirst()
                require(visited.add(ifd)) { "cyclic TIFF IFD graph" }
                require(ifd in 2 until bytes.size) { "${file.name}: IFD offset out of range" }
                val entries = readIfd(buf, bytes, ifd)
                val subfile = entries[254]?.asLongs()?.firstOrNull() ?: 0L
                if ((subfile == 0L || subfile == 0x10000L) && entries[262]?.asLongs()?.firstOrNull() == 32803L) {
                    main = entries
                    break
                }
                val nextPos = ifd + 2 + (buf.getShort(ifd).toInt() and 0xFFFF) * 12
                val next = u32(nextPos)
                if (next != 0) pending.add(next)
                entries[330]?.asLongs()?.forEach { pending.add(it.toInt()) }
            }
            require(main.isNotEmpty()) { "no full-resolution CFA IFD found" }
            val exifOffset = (main[34665] ?: root[34665])?.asLongs()?.firstOrNull()?.toInt()
            val exif = exifOffset?.let { readIfd(buf,bytes,it) }.orEmpty()
            fun need(tag: Int): Entry =
                requireNotNull(main[tag]) { "${file.name}: missing required DNG tag $tag" }

            val width = need(256).asLongs().first().toInt()
            val height = need(257).asLongs().first().toInt()
            require(width >= 4 && height >= 4) { "${file.name}: DNG is ${width}x$height, need at least 4x4" }
            val bps = need(258).asLongs()
            // Uncompressed 16-bit words, or packed 10-bit (4 pixels in 5
            // bytes, MSB-first; see unpack10).
            require(bps.all { it == 16L } || bps.all { it == 10L }) {
                "${file.name}: BitsPerSample must be 16 or packed 10, got $bps"
            }
            val packed10 = bps.all { it == 10L }
            require((main[277]?.asLongs()?.firstOrNull() ?: 1L) == 1L) { "only single-sample CFA supported" }
            // PlanarConfiguration defaults to chunky (1) when absent per TIFF.
            require((main[284]?.asLongs()?.firstOrNull() ?: 1L) == 1L) { "only chunky planar configuration supported" }
            require((main[339]?.asLongs()?.firstOrNull() ?: 1L) == 1L) { "only unsigned CFA supported" }
            require((main[317]?.asLongs()?.firstOrNull() ?: 1L) == 1L) { "unsupported TIFF predictor" }
            require(main[50712] == null) { "LinearizationTable requires preprocessing not supported by this reader" }
            if (packed10) require(width % 4 == 0) {
                "${file.name}: packed 10-bit rows need width % 4 == 0, got $width"
            }
            val compression = main[259]?.asLongs()?.firstOrNull() ?: 1L
            require(compression == 1L) { "${file.name}: only uncompressed strips supported (Compression=$compression)" }
            val photo = main[262]?.asLongs()?.firstOrNull() ?: 32803L
            require(photo == 32803L) { "${file.name}: PhotometricInterpretation must be 32803 (CFA)" }
            val orientation = main[274]?.asLongs()?.firstOrNull()?.toInt() ?: 1
            require(orientation in 1..8) { "${file.name}: Orientation=$orientation invalid" }

            val repDim = need(33421).asLongs()
            require(repDim == listOf(2L, 2L)) { "${file.name}: CFARepeatPatternDim must be [2,2]" }
            val cfaBytes = need(33422).rawBytes()
            require(cfaBytes.size == 4) { "${file.name}: CFAPattern must hold 4 values" }
            val cfaInts = IntArray(4) { cfaBytes[it].toInt() and 0xFF }
            require(cfaInts.sorted() == listOf(0, 1, 1, 2)) {
                "${file.name}: CFAPattern ${cfaInts.toList()} is not a Bayer permutation of [0,1,1,2]"
            }
            val sensorPattern = BayerPattern.fromDngCfa(cfaInts)

            val blackRaw = need(50714).asDoubles()
            val blacks = FloatArray(4) { i ->
                when (blackRaw.size) {
                    1 -> blackRaw[0].toFloat()
                    4 -> blackRaw[i].toFloat()
                    else -> throw IllegalArgumentException("${file.name}: BlackLevel count must be 1 or 4")
                }
            }
            val white = need(50717).asDoubles().first().toFloat()
            require(white.isFinite() && blacks.all { white > it }) {
                "${file.name}: WhiteLevel ($white) must exceed every black level"
            }

            val stripOffsets = need(273).asLongs()
            val stripCounts = need(279).asLongs()
            require(stripOffsets.size == stripCounts.size) { "${file.name}: StripOffsets/StripByteCounts mismatch" }
            val rowsPerStrip = main[278]?.asLongs()?.firstOrNull()?.toInt() ?: height
            val codes = ShortArray(Math.multiplyExact(width, height))
            var row = 0
            for (s in stripOffsets.indices) {
                val rows = minOf(rowsPerStrip, height - row)
                require(rows > 0) { "${file.name}: strip $s exceeds ImageLength" }
                val off = stripOffsets[s].toInt()
                if (!packed10) {
                    val want = rows * width * 2L
                    require(stripCounts[s] >= want) { "${file.name}: strip $s truncated" }
                    require(off >= 0 && off + want <= bytes.size) { "${file.name}: strip $s out of range" }
                    ByteBuffer.wrap(bytes, off, want.toInt()).order(order).asShortBuffer()
                        .get(codes, row * width, rows * width)
                } else {
                    // Packed 10-bit: width/4 groups of 5 bytes per row.
                    val want = rows * (width / 4) * 5L
                    require(stripCounts[s] >= want) { "${file.name}: strip $s truncated" }
                    require(off >= 0 && off + want <= bytes.size) { "${file.name}: strip $s out of range" }
                    unpack10(bytes, off, codes, row * width, rows * width)
                }
                row += rows
            }
            require(row == height) { "${file.name}: strips cover $row rows, expected $height" }

            val active = main[50829]?.asLongs()
            var left = 0
            var top = 0
            var right = width
            var bottom = height
            if (active != null) {
                require(active.size == 4) { "${file.name}: ActiveArea must hold 4 values" }
                top = active[0].toInt()
                left = active[1].toInt()
                bottom = active[2].toInt()
                right = active[3].toInt()
                require(left >= 0 && top >= 0 && right <= width && bottom <= height && right > left && bottom > top) {
                    "${file.name}: ActiveArea out of bounds"
                }
            }
            var cropW = right - left
            var cropH = bottom - top
            if (cropW % 2 == 1) {
                right--
                cropW--
            }
            if (cropH % 2 == 1) {
                bottom--
                cropH--
            }
            require(cropW >= 4 && cropH >= 4) { "${file.name}: ActiveArea crop too small after even-trim" }

            // DESKTOP-ADDITION (sr-vulkan only): retain the admitted crop's raw
            // codes alongside the normalized samples (same crop window).
            val cropCodes = ShortArray(cropW * cropH)
            for (y in 0 until cropH) {
                codes.copyInto(cropCodes, y * cropW, (top + y) * width + left, (top + y) * width + left + cropW)
            }
            val samples = FloatArray(cropW * cropH)
            for (y in 0 until cropH) for (x in 0 until cropW) {
                val code = codes[(top + y) * width + (left + x)].toInt() and 0xFFFF
                // Per-tap black in full-sensor parity: blackLevels order matches
                // the full-sensor CFA repeat, so index by sensor coordinates.
                // P2: parenthesized explicitly (was correct by precedence:
                // `(par and 1) shl 1`, but fragile to a future edit).
                val black = blacks[(((top + y) and 1) shl 1) or (((left + x) and 1))]
                samples[y * cropW + x] = (code - black) / (white - black)
            }
            val pattern = sensorPattern.shifted(left, top)

            // Calibration lives with the CFA IFD or is inherited from its
            // parent IFD (RAW SubIFD layout): never from an unrelated IFD,
            // never invented.
            fun calib(tag: Int): Entry? = main[tag] ?: root[tag]
            fun matrix9(tag: Int): DoubleArray? {
                val e = calib(tag) ?: return null
                val v = e.asDoubles()
                require(v.size == 9) { "${file.name}: tag $tag must hold 9 values" }
                return v
            }
            val noise = main[51041]?.let {
                val v = it.asDoubles()
                require(v.size == 6) { "${file.name}: NoiseProfile must hold 6 values (RGB)" }
                v
            }
            // EXIF ExposureTime is a single RATIONAL (already num/den).
            val exposure = (exif[33434] ?: main[33434] ?: root[33434])?.asDoubles()?.firstOrNull()
                ?.takeIf { it.isFinite() && it > 0 }
            val iso = (exif[34855] ?: main[34855] ?: root[34855])?.asLongs()?.firstOrNull()?.toInt()?.takeIf { it in 1..65535 }
            fun ascii(tag: Int, fallback: String): String {
                val e = main[tag] ?: return fallback
                return e.asAscii().trim().trimEnd('\u0000').ifBlank { fallback }
            }
            val colorMatrix1 = matrix9(50721)
                ?: throw IllegalArgumentException("${file.name}: missing required ColorMatrix1")
            val neutral = calib(50728)?.asDoubles()?.takeIf { it.size == 3 }
                ?: throw IllegalArgumentException("${file.name}: missing required AsShotNeutral")
            // Opcode lists are detected and recorded, never silently assumed
            // applied. GainMap lens shading in particular is NOT applied by
            // this pipeline (see output provenance); rejecting these files
            // outright would needlessly refuse the entire phone corpus, so
            // presence is explicit metadata instead.
            val opcodeLists = listOf(51008, 51009, 51022).filter { main[it] != null || root[it] != null }
            return AdmittedFrame(
                file = file, width = cropW, height = cropH, pattern = pattern,
                sensorLeft = left, sensorTop = top, samples = samples,
                blackLevels = blacks, whiteLevel = white,
                noiseProfile = noise, exposureSec = exposure, iso = iso,
                orientation = orientation,
                make = ascii(271, "Unknown"), model = ascii(272, "Unknown"),
                colorMatrix1 = colorMatrix1, colorMatrix2 = matrix9(50722),
                asShotNeutral = neutral,
                illuminant1 = calib(50778)?.asLongs()?.firstOrNull()?.toInt() ?: 21,
                illuminant2 = calib(50779)?.asLongs()?.firstOrNull()?.toInt(),
                forwardMatrix1 = matrix9(50964), forwardMatrix2 = matrix9(50965),
                opcodeLists = opcodeLists,
                codes = cropCodes
            )
        }

        private data class Entry(val type: Int, val doubles: DoubleArray, val bytes: ByteArray) {
            fun asLongs(): List<Long> = doubles.map { it.toLong() }
            fun asDoubles(): DoubleArray = doubles.copyOf()
            fun asAscii(): String = String(bytes, Charsets.US_ASCII)
            fun rawBytes(): ByteArray = bytes.copyOf()
        }

        private fun fieldSize(type: Int): Int = when (type) {
            1, 2, 6, 7 -> 1
            3, 8 -> 2
            4, 9, 11, 13 -> 4
            5, 10, 12 -> 8
            else -> throw IllegalArgumentException("Unsupported TIFF field type $type")
        }

        private fun readIfd(buf: ByteBuffer, file: ByteArray, offset: Int): Map<Int, Entry> {
            require(offset >= 0 && offset.toLong() + 2 <= file.size) { "invalid IFD offset" }
            val order = buf.order()
            val count = buf.getShort(offset).toInt() and 0xFFFF
            require(offset.toLong() + 2 + count * 12L + 4 <= file.size) { "truncated IFD" }
            val map = HashMap<Int, Entry>(count * 2)
            for (i in 0 until count) {
                val p = offset + 2 + i * 12
                val tag = buf.getShort(p).toInt() and 0xFFFF
                val type = buf.getShort(p + 2).toInt() and 0xFFFF
                val n = (buf.getInt(p + 4).toLong() and 0xFFFFFFFFL).toInt()
                val size = fieldSize(type)
                require(n >= 0)
                val total = Math.multiplyExact(n, size)
                val pos = if (total <= 4) p + 8 else {
                    val off = (buf.getInt(p + 8).toLong() and 0xFFFFFFFFL).toInt()
                    require(off >= 0 && off.toLong() + total <= file.size) { "TIFF value offset out of range" }
                    off
                }
                val doubles = DoubleArray(n) { k ->
                    val q = pos + k * size
                    when (type) {
                        1, 2, 7 -> (file[q].toInt() and 0xFF).toDouble()
                        6 -> file[q].toDouble()
                        8 -> ByteBuffer.wrap(file,q,2).order(order).short.toDouble()
                        11 -> ByteBuffer.wrap(file,q,4).order(order).float.toDouble()
                        3 -> ByteBuffer.wrap(file, q, 2).order(order).getShort().toInt()
                            .let { it and 0xFFFF }.toDouble()
                        4, 13 -> (ByteBuffer.wrap(file, q, 4).order(order).getInt().toLong()
                            and 0xFFFFFFFFL).toDouble()
                        9 -> ByteBuffer.wrap(file, q, 4).order(order).getInt().toDouble()
                        5 -> {
                            val b = ByteBuffer.wrap(file, q, 8).order(order)
                            val num = b.getInt().toLong() and 0xFFFFFFFFL
                            val den = b.getInt().toLong() and 0xFFFFFFFFL
                            require(den != 0L) { "Zero RATIONAL denominator" }
                            num.toDouble() / den.toDouble()
                        }
                        10 -> {
                            val b = ByteBuffer.wrap(file, q, 8).order(order)
                            val num = b.getInt()
                            val den = b.getInt()
                            require(den != 0) { "Zero SRATIONAL denominator" }
                            num.toDouble() / den.toDouble()
                        }
                        12 -> ByteBuffer.wrap(file, q, 8).order(order).getDouble()
                        else -> throw IllegalArgumentException("Unsupported TIFF field type $type")
                    }
                }
                val bytes = if (total <= 4) file.copyOfRange(p + 8, p + 8 + total)
                else file.copyOfRange(pos, pos + total)
                map[tag] = Entry(type, doubles, bytes)
            }
            return map
        }
    }
}
