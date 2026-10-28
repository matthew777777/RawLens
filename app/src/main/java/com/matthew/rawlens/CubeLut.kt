// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.io.Reader
import kotlin.math.floor

/**
 * Iridas .cube 3D subset, independently implemented from the format described in
 * OpenColorIO v2.5.2 FileFormatIridasCube.cpp (format comment, lines 20-61).
 * Red index varies fastest. Domain maps input to the lattice; values outside
 * the domain clamp to its edge. Output values retain their signed float range.
 * No 1D shaper, mixed 1D/3D, or Resolve video-range extensions are accepted.
 */
internal class CubeLut private constructor(
    val title: String,
    val size: Int,
    private val minimum: FloatArray,
    private val maximum: FloatArray,
    private val values: FloatArray,
) {
    fun domainMin() = minimum.copyOf()
    fun domainMax() = maximum.copyOf()
    fun data() = values.copyOf()

    /** Vulkan RGBA16F upload, x=R/y=G/z=B; opaque alpha is unused by grading. */
    fun rgbaHalfBits(): ShortArray = ShortArray(size * size * size * 4) { i ->
        VfLogGrade.floatToHalfBits(if (i % 4 == 3) 1f else values[i / 4 * 3 + i % 4]).toShort()
    }

    fun gpuParameters(intensity: Float): FloatArray {
        require(intensity.isFinite() && intensity in 0f..1f)
        return minimum + maximum + floatArrayOf(intensity)
    }

    /** CPU golden for GL_LINEAR 3D texture sampling (trilinear, not tetrahedral). */
    fun apply(rgb: FloatArray, intensity: Float = 1f): FloatArray {
        require(rgb.size == 3 && rgb.all { it.isFinite() })
        require(intensity.isFinite() && intensity in 0f..1f)
        val x = FloatArray(3) { ((rgb[it] - minimum[it]) / (maximum[it] - minimum[it]))
            .coerceIn(0f, 1f) * (size - 1) }
        val lo = IntArray(3) { floor(x[it]).toInt() }
        val hi = IntArray(3) { (lo[it] + 1).coerceAtMost(size - 1) }
        val f = FloatArray(3) { x[it] - lo[it] }
        val out = FloatArray(3)
        repeat(8) { corner ->
            val index = IntArray(3) { if (corner and (1 shl it) != 0) hi[it] else lo[it] }
            var weight = 1f
            repeat(3) { weight *= if (corner and (1 shl it) != 0) f[it] else 1f - f[it] }
            val offset = 3 * (index[0] + size * (index[1] + size * index[2]))
            repeat(3) { out[it] += weight * values[offset + it] }
        }
        return FloatArray(3) { rgb[it] + intensity * (out[it] - rgb[it]) }
    }

    companion object {
        const val MAX_BYTES = 32 * 1024 * 1024

        /** Resource limits are app policy, not additional .cube format rules. */
        fun parse(reader: Reader): CubeLut {
            var title = "Imported LUT"
            var size = 0
            var data: FloatArray? = null
            var count = 0
            var chars = 0
            val seen = HashSet<String>()
            var min = floatArrayOf(0f, 0f, 0f)
            var max = floatArrayOf(1f, 1f, 1f)
            reader.buffered().forEachLine { raw ->
                chars += raw.length + 1
                require(chars <= MAX_BYTES && raw.length <= 8192) { "LUT exceeds import limits" }
                // A hash inside TITLE quotes is text; only an unquoted hash
                // starts a comment (including optional inline comments).
                var quoted = false
                val comment = raw.indexOfFirst { ch ->
                    if (ch == '"') quoted = !quoted
                    ch == '#' && !quoted
                }
                val line = (if (comment < 0) raw else raw.substring(0,comment))
                    .removePrefix("\uFEFF").trim()
                if (line.isNotEmpty()) {
                    val words = line.split(Regex("\\s+"))
                    val key = words[0]
                    fun triple(): FloatArray {
                        require(words.size == 4) { "Expected three domain values" }
                        return FloatArray(3) { words[it + 1].toFloat().also { v ->
                            require(v.isFinite()) { "Non-finite LUT value" }
                        } }
                    }
                    if (key in listOf("TITLE", "LUT_3D_SIZE", "DOMAIN_MIN", "DOMAIN_MAX")) {
                        require(count == 0 && seen.add(key)) { "Repeated or misplaced $key" }
                        when (key) {
                            "TITLE" -> {
                                val quoted = line.removePrefix(key).trim()
                                require(quoted.length >= 2 && quoted.first() == '"' && quoted.last() == '"') {
                                    "TITLE must be quoted"
                                }
                                title = quoted.substring(1, quoted.length - 1).take(120)
                            }
                            "LUT_3D_SIZE" -> {
                                require(words.size == 2) { "Invalid LUT size" }
                                size = words[1].toInt()
                                require(size in 16..65) { "3D LUT size must be 16–65" }
                                data = FloatArray(3 * size * size * size)
                            }
                            "DOMAIN_MIN" -> min = triple()
                            "DOMAIN_MAX" -> max = triple()
                        }
                    } else {
                        val dst = requireNotNull(data) { "Expected LUT_3D_SIZE before data" }
                        require(words.size == 3 && count + 3 <= dst.size) { "Invalid LUT row count" }
                        words.forEach { token ->
                            dst[count++] = token.toFloat().also {
                                require(it.isFinite() && kotlin.math.abs(it) <= 65504f) {
                                    "LUT value exceeds finite half-float range"
                                }
                            }
                        }
                    }
                }
            }
            val result = requireNotNull(data) { "Missing 3D LUT" }
            require(count == result.size) { "Incomplete LUT: ${count / 3} / ${result.size / 3} rows" }
            require((0..2).all { max[it] > min[it] && (max[it] - min[it]).isFinite() }) {
                "Invalid LUT domain"
            }
            return CubeLut(title, size, min, max, result)
        }

    }
}
