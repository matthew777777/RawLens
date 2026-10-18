package com.rawlens.srpipeline.stages

import java.io.File
import java.util.zip.ZipInputStream

/**
 * Noise-LUT loader (`load_noise_lut` in
 * `handheld_super_resolution/noise_lut.py`).
 *
 * Reads the `.npz` (zip of `.npy` entries, plain or deflated) and applies
 * the same acceptance checks: required fields, format version 1, the
 * `clip_raw_then_sqrt_bayer_quad_rgb_v1` transform, 3x3 patches, bins /
 * trials / seed consistency, uniform [0,1] brightness grid (`allclose`
 * atol 2e-7), curve shapes, finiteness, non-negativity, counts summing to
 * trials, and alpha/beta RGBG match against the burst tag values
 * (`allclose` rtol 1e-6, atol 1e-12). Anything else throws
 * IllegalArgumentException like the Python ValueErrors.
 *
 * Only the pipeline inputs are returned (curves + profile); the
 * provenance metadata (counts, sems, seed) is validated, not kept.
 */
object NoiseLut {

    /** Pipeline inputs from a validated LUT. */
    data class Curves(
        val sigmaSq: FloatArray,
        val dSq: FloatArray,
        val alpha: DoubleArray,
        val beta: DoubleArray,
    )

    private fun allClose(a: DoubleArray, b: DoubleArray, rtol: Double, atol: Double): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (kotlin.math.abs(a[i] - b[i]) > atol + rtol * kotlin.math.abs(b[i])) return false
        }
        return true
    }

    private fun allCloseF(a: FloatArray, b: FloatArray, rtol: Double, atol: Double): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (kotlin.math.abs(a[i] - b[i]) > atol + rtol * kotlin.math.abs(b[i])) return false
        }
        return true
    }

    /**
     * Loads and validates `path`. `expectedAlpha`/`expectedBeta` are the
     * tag-derived RGBG profile (both or neither) the LUT is checked
     * against, mirroring `load_noise_lut(path, expected_alpha,
     * expected_beta)`.
     */
    fun load(path: String, expectedAlpha: DoubleArray?, expectedBeta: DoubleArray?): Curves {
        val file = File(path)
        require(file.isFile) { "noise LUT does not exist: $path" }
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) entries[entry.name.removeSuffix(".npy")] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        fun npy(name: String): ByteArray =
            entries[name] ?: throw IllegalArgumentException("noise LUT is missing fields: [$name]")

        // Minimal .npy parsing (C-order, little-endian): descr + shape + payload.
        fun parse(name: String): Triple<String, IntArray, ByteArray> {
            val bytes = npy(name)
            require(bytes.size > 10 && bytes[0] == 0x93.toByte()) { "cannot read noise LUT $path: $name" }
            val hlen = ((bytes[9].toInt() and 0xFF) shl 8) or (bytes[8].toInt() and 0xFF)
            val header = String(bytes, 10, hlen, Charsets.US_ASCII)
            val descr = Regex("'descr':\\s*'([^']+)'").find(header)?.groupValues?.get(1)
                ?: throw IllegalArgumentException("cannot read noise LUT $path: $name")
            val shapeText = Regex("'shape':\\s*\\(([^)]*)\\)").find(header)?.groupValues?.get(1)
                ?: throw IllegalArgumentException("cannot read noise LUT $path: $name")
            val shape = shapeText.split(",").mapNotNull {
                it.trim().takeIf(String::isNotEmpty)?.toInt()
            }.toIntArray()
            return Triple(descr, shape, bytes.copyOfRange(10 + hlen, bytes.size))
        }

        fun intScalar(name: String): Long {
            val (descr, shape, payload) = parse(name)
            val count = if (shape.isEmpty()) 1 else shape.fold(1, Int::times)
            require(count == 1) { "noise LUT $name must be scalar" }
            val buf = java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            return when (descr) {
                "<i4" -> buf.int.toLong()
                "<i8" -> buf.long
                "<u4" -> buf.int.toLong() and 0xFFFFFFFFL
                "<u8" -> buf.long
                else -> throw IllegalArgumentException("noise LUT $name has non-integer dtype $descr")
            }
        }

        fun strScalar(name: String): String {
            val (descr, shape, payload) = parse(name)
            require(descr == "<U0" || descr.startsWith("<U")) { "noise LUT $name must be a string, got $descr" }
            // Unicode entries are UCS4 code points.
            val ints = IntArray(payload.size / 4)
            java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(ints)
            return String(ints.map { it.toChar() }.toCharArray())
        }

        fun floatArray(name: String, n: Int): FloatArray {
            val (descr, shape, payload) = parse(name)
            require(descr == "<f4" || descr == "<f8") { "noise LUT $name must be float, got $descr" }
            val count = if (shape.isEmpty()) 1 else shape.fold(1, Int::times)
            require(count == n) { "noise LUT $name has shape ${shape.toList()}, expected [$n]" }
            val buf = java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            return FloatArray(n) { i ->
                if (descr == "<f4") buf.getFloat(i * 4) else buf.getDouble(i * 8).toFloat()
            }
        }

        fun doubleArray4(name: String): DoubleArray {
            val (descr, shape, payload) = parse(name)
            require(descr == "<f4" || descr == "<f8") { "noise LUT $name must be float, got $descr" }
            val buf = java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            return DoubleArray(4) { i ->
                if (descr == "<f4") buf.getFloat(i * 4).toDouble() else buf.getDouble(i * 8)
            }
        }

        fun longArray(name: String, n: Int): LongArray {
            val (descr, shape, payload) = parse(name)
            require(descr in setOf("<i4", "<i8", "<u4", "<u8")) {
                "noise LUT bin counts must use an integer dtype, got $descr"
            }
            val buf = java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            return LongArray(n) { i ->
                when (descr) {
                    "<i4" -> buf.getInt(i * 4).toLong()
                    "<i8" -> buf.getLong(i * 8)
                    "<u4" -> buf.getInt(i * 4).toLong() and 0xFFFFFFFFL
                    else -> buf.getLong(i * 8)
                }
            }
        }

        val required = setOf("format_version", "transform", "brightness", "sigma_noise_sq",
            "d_noise_sq", "bin_counts", "sigma_noise_sq_sem", "d_noise_sq_sem",
            "alpha_rgbg", "beta_rgbg", "patch_size", "latent_prior", "bins", "trials", "seed")
        val missing = required - entries.keys.map { it }.toSet()
        require(missing.isEmpty()) { "noise LUT is missing fields: ${missing.sorted()}" }
        require(intScalar("format_version") == 1L) { "unsupported noise LUT format version" }
        require(strScalar("transform") == "clip_raw_then_sqrt_bayer_quad_rgb_v1") {
            "noise LUT was generated for the wrong transform"
        }
        require(intScalar("patch_size") == 3L) { "noise LUT must use 3x3 patches" }
        val storedBins = intScalar("bins").toInt()
        val storedTrials = intScalar("trials")
        require(intScalar("seed") >= 0) { "noise LUT seed must be nonnegative" }
        require(strScalar("latent_prior") == "uniform_stratified_0_1") { "unsupported latent prior" }

        val brightness = floatArray("brightness", storedBins)
        require(storedBins >= 2) { "noise LUT brightness must have at least two bins" }
        val sigmaSq = floatArray("sigma_noise_sq", storedBins)
        val dSq = floatArray("d_noise_sq", storedBins)
        val counts = longArray("bin_counts", storedBins)
        val sigmaSem = floatArray("sigma_noise_sq_sem", storedBins)
        val dSem = floatArray("d_noise_sq_sem", storedBins)
        val alpha = doubleArray4("alpha_rgbg")
        val beta = doubleArray4("beta_rgbg")

        val expectedGrid = FloatArray(storedBins) { it.toFloat() / (storedBins - 1) }
        require(allCloseF(brightness, expectedGrid, 0.0, 2e-7)) {
            "noise LUT brightness must be a uniform measured-brightness grid from 0 to 1"
        }
        for ((name, a) in listOf("sigma" to sigmaSq, "d" to dSq, "sigma_sem" to sigmaSem, "d_sem" to dSem)) {
            require(a.all { it.isFinite() && it >= 0f }) { "noise LUT $name must be finite nonnegative" }
        }
        require(brightness.all { it.isFinite() }) { "noise LUT brightness must be finite" }
        require(alpha.all { it.isFinite() && it >= 0 } && beta.all { it.isFinite() && it >= 0 }) {
            "noise LUT alpha and beta must be finite nonnegative"
        }
        require(counts.all { it >= 0 }) { "noise LUT bin counts must be nonnegative" }
        require(storedTrials >= 1) { "noise LUT trials must be positive" }
        require(counts.sum() == storedTrials) { "noise LUT bin counts sum to ${counts.sum()}, expected $storedTrials trials" }

        if ((expectedAlpha == null) != (expectedBeta == null)) {
            throw IllegalArgumentException("expectedAlpha and expectedBeta must be provided together")
        }
        if (expectedAlpha != null && expectedBeta != null) {
            require(allClose(alpha, expectedAlpha, 1e-6, 1e-12)) {
                "noise LUT alpha does not match the burst"
            }
            require(allClose(beta, expectedBeta, 1e-6, 1e-12)) {
                "noise LUT beta does not match the burst"
            }
        }
        return Curves(sigmaSq, dSq, alpha, beta)
    }
}
