package com.rawlens.srpipeline.stages

import com.rawlens.srpipeline.SrpBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DNG burst ingest: TinyDNG v3 metadata + raster, RGGB flip, [0,1]
 * normalisation.
 *
 * Mirrors `load_dng_burst` / `DNGStack.get_raw_arrays` in
 * `handheld_super_resolution/utils_dng.py` plus `cfa_to_rggb` in
 * `utils_image.py`:
 * - CFA values use the LibRaw convention (G2 remapped `3 -> 1`, as the
 *   native reader returns them); the four `cfa_to_rggb` branches are
 *   reproduced exactly (identity, full flip, horizontal flip, vertical
 *   flip), anything else throws like the Python `NotImplementedError`.
 * - Normalisation is per-Bayer-plane `(v - black) / (white - black)` in
 *   double precision, rounded once to float — mirroring the float64
 *   `get_raw_arrays` followed by the `astype(float32)` at the `main`
 *   call. Plane order after the RGGB flip: `[0,0] -> black[0]`,
 *   `[0,1] -> black[1]`, `[1,0] -> black[3]`, `[1,1] -> black[2]`.
 * - Noise alpha/beta come from the NoiseProfile `(S,O)` pairs in
 *   R,G1,B,G2 plane order, mirroring `noise_profile_from_tags` +
 *   `expand_noise_profile_to_rgbg` for the 4-pair form.
 */
object DngRead {

    /** Parsed DNG metadata (image 0). */
    data class Meta(
        val width: Int,
        val height: Int,
        val black: IntArray,
        val white: Int,
        /** CFA values row-major, LibRaw convention (`[[0,1],[1,2]]` RGGB). */
        val cfa: IntArray,
        /** NoiseProfile (S,O) pairs, R,G1,B,G2 plane order. */
        val noise: DoubleArray,
        /** Valid doubles in [noise] (2 per profile pair; the JNI pads to 8). */
        val noiseCount: Int,
    )

    /** Reads image-0 metadata; throws IllegalStateException when unreadable. */
    fun readMeta(path: String): Meta {
        val info = SrpBridge.nativeDngMeta(path)
            ?: throw IllegalStateException("DngRead: cannot read DNG meta: $path")
        require(info.size == 12) { "DngRead: bad meta length ${info.size}" }
        val noise = SrpBridge.nativeDngNoise(path)
            ?: throw IllegalStateException("DngRead: cannot read DNG noise: $path")
        return Meta(
            width = info[0],
            height = info[1],
            black = info.copyOfRange(2, 6),
            white = info[6],
            cfa = info.copyOfRange(7, 11),
            noise = noise,
            noiseCount = info[11],
        )
    }

    /**
     * Decodes the full frame to uint16 bits (as signed shorts, bit-exact).
     * The buffer is direct, native-order, position 0.
     */
    fun decodeFull(path: String, meta: Meta): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(meta.width * meta.height * 2)
            .order(ByteOrder.nativeOrder())
        val rc = SrpBridge.nativeDngPixels(path, buf, meta.width, meta.height,
            0, 0, 0, 0)
        require(rc == 0) { "DngRead: full decode failed with status $rc: $path" }
        buf.rewind()
        return buf
    }

    /**
     * Decodes one even-aligned region (Bayer phase preserving) to uint16
     * bits. Used by the parity proof; the CLI decodes full frames.
     */
    fun decodeRegion(path: String, meta: Meta, x: Int, y: Int, w: Int, h: Int): ByteBuffer {
        require(x >= 0 && y >= 0 && w > 0 && h > 0) { "DngRead: bad region" }
        require(x % 2 == 0 && y % 2 == 0 && w % 2 == 0 && h % 2 == 0) {
            "DngRead: region must be even-aligned"
        }
        val buf = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder())
        val rc = SrpBridge.nativeDngPixels(path, buf, meta.width, meta.height, x, y, w, h)
        require(rc == 0) { "DngRead: region decode failed with status $rc: $path" }
        buf.rewind()
        return buf
    }

    /** Reads uint16 bits from a decoded buffer as unsigned ints. */
    fun toUnsigned(buf: ByteBuffer, count: Int): IntArray {
        buf.rewind()
        val shorts = ShortArray(count)
        buf.asShortBuffer().get(shorts)
        return IntArray(count) { shorts[it].toInt() and 0xFFFF }
    }

    /**
     * Flips an RGGB-candidate raster into RGGB, mirroring `cfa_to_rggb`.
     * `cfa` holds the four LibRaw-convention values row-major.
     */
    fun flipToRggb(pixels: IntArray, w: Int, h: Int, cfa: IntArray): IntArray {
        require(pixels.size == w * h) { "DngRead: raster size mismatch" }
        val rggb = cfa[0] == 0 && cfa[1] == 1 && cfa[2] == 1 && cfa[3] == 2
        if (rggb) return pixels
        // BGGR [[2,1],[1,0]]: flip both axes.
        if (cfa[0] == 2 && cfa[1] == 1 && cfa[2] == 1 && cfa[3] == 0) {
            return IntArray(w * h) { i ->
                pixels[(h - 1 - i / w) * w + (w - 1 - i % w)]
            }
        }
        // GBRG [[1,0],[2,1]]: flip horizontal.
        if (cfa[0] == 1 && cfa[1] == 0 && cfa[2] == 2 && cfa[3] == 1) {
            return IntArray(w * h) { i ->
                pixels[(i / w) * w + (w - 1 - i % w)]
            }
        }
        // GRBG [[1,2],[0,1]]: flip vertical.
        if (cfa[0] == 1 && cfa[1] == 2 && cfa[2] == 0 && cfa[3] == 1) {
            return IntArray(w * h) { i ->
                pixels[(h - 1 - i / w) * w + (i % w)]
            }
        }
        throw IllegalArgumentException("DngRead: unsupported CFA pattern ${cfa.toList()}")
    }

    /**
     * Per-plane `(v - black) / (white - black)` normalisation of an RGGB
     * raster, mirroring `get_raw_arrays` (float64, rounded once).
     */
    fun normalize(pixels: IntArray, w: Int, h: Int, black: IntArray, white: Int): Array<FloatArray> {
        require(pixels.size == w * h) { "DngRead: raster size mismatch" }
        require(black.size == 4) { "DngRead: need 4 black levels" }
        return Array(h) { y ->
            FloatArray(w) { x ->
                val plane = when {
                    y % 2 == 0 && x % 2 == 0 -> black[0]
                    y % 2 == 0 -> black[1]
                    x % 2 == 0 -> black[3]
                    else -> black[2]
                }
                ((pixels[y * w + x].toDouble() - plane) / (white - plane)).toFloat()
            }
        }
    }

    /**
     * Single-pass decode + flip + normalise via
     * [SrpBridge.nativeDngNormalized] (same bytes as [decodeFull] +
     * [toUnsigned] + [flipToRggb] + [normalize]: same unsigned value,
     * same flip index map, same float64 formula rounded once — the C++
     * loop is held to the exact Kotlin op order, so the output stays
     * bit-identical to the four-step chain while skipping the
     * `ShortArray`/`IntArray` transients and the host per-pixel loop).
     */
    fun readNormalized(path: String, meta: Meta): Array<FloatArray> {
        val w = meta.width
        val h = meta.height
        require(meta.black.size == 4) { "DngRead: need 4 black levels" }
        require(meta.cfa.size == 4) { "DngRead: need 4 CFA values" }
        val buf = SrpBridge.directFloats(w * h)
        return readNormalizedInto(path, meta, buf)
    }

    /**
     * Same bytes as [readNormalized], decoding into the caller-owned
     * direct buffer `buf` (capacity >= `w*h` floats, fully rewritten):
     * lets memory-tight callers decode frame after frame without one
     * 50 MB staging allocation per frame.
     */
    fun readNormalizedInto(
        path: String,
        meta: Meta,
        buf: java.nio.ByteBuffer,
    ): Array<FloatArray> {
        val w = meta.width
        val h = meta.height
        require(meta.black.size == 4) { "DngRead: need 4 black levels" }
        require(meta.cfa.size == 4) { "DngRead: need 4 CFA values" }
        require(buf.capacity() >= w * h * 4) {
            "DngRead: staging buffer too small for ${w}x$h"
        }
        buf.rewind()
        val rc = SrpBridge.nativeDngNormalized(
            path, buf, w, h, meta.black, meta.white, meta.cfa)
        require(rc == 0) { "DngRead: normalized decode failed with status $rc: $path" }
        buf.rewind()
        val fb = buf.asFloatBuffer()
        return Array(h) { FloatArray(w).also { fb.get(it) } }
    }

    /**
     * Noise alpha/beta in R,G1,B,G2 order from NoiseProfile `(S,O)`
     * pairs, mirroring `noise_profile_from_tags` +
     * `expand_noise_profile_to_rgbg` (1-, 3-, or 4-plane forms; the
     * burst DNGs carry 3 planes). `count` is the pair count from the
     * tag (2, 6, or 8 doubles).
     */
    fun noiseAlphaBeta(noise: DoubleArray, count: Int): Pair<DoubleArray, DoubleArray> {
        val planes = when (count) {
            2 -> listOf(0, 0, 0, 0)
            6 -> listOf(0, 1, 2, 1)
            8 -> listOf(0, 1, 2, 3)
            else -> throw IllegalArgumentException(
                "DngRead: noise profiles must contain 1, 3, or 4 pairs, got ${count / 2}")
        }
        val alpha = DoubleArray(4) { noise[2 * planes[it]] }
        val beta = DoubleArray(4) { noise[2 * planes[it] + 1] }
        return alpha to beta
    }
}
