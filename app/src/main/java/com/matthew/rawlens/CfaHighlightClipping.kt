// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Merge-provenance mask: only these photosites still lack an unclipped measurement.
 * One bit per pixel, rows padded to 32 bits (about 1.5 MiB at 12 MP).
 * Keep this with the CFA through lens correction and denoise; never infer it from HDR radiance.
 */
class CfaHighlightClipping internal constructor(
    val width: Int,
    val height: Int,
    internal val words: IntArray
) {
    internal val wordsPerRow = (width + 31) / 32
    init { require(width > 0 && height > 0 && words.size == wordsPerRow * height) }
    val any: Boolean = words.any { it != 0 }
    fun isClipped(index: Int): Boolean {
        val y = index / width; val x = index % width
        return words[y * wordsPerRow + x / 32] and (1 shl (x and 31)) != 0
    }
    /** Copy with the given CFA indices unmarked (pixels a later stage repaired, e.g. hot pixels). */
    internal fun cleared(indices: IntArray): CfaHighlightClipping {
        if (indices.isEmpty()) return this
        val out = words.copyOf()
        for (index in indices) {
            val y = index / width; val x = index % width
            out[y * wordsPerRow + x / 32] = out[y * wordsPerRow + x / 32] and (1 shl (x and 31)).inv()
        }
        return CfaHighlightClipping(width, height, out)
    }

    companion object {
        /** Row-sharded [from]: rows own disjoint words, so the predicate runs on every core. */
        internal fun fromParallel(width: Int, height: Int, predicate: (Int) -> Boolean): CfaHighlightClipping {
            val stride = (width + 31) / 32
            val words = IntArray(stride * height)
            ShardWorkers.forEachShard(height) { y0, y1 ->
                for (y in y0 until y1) for (x in 0 until width) {
                    if (predicate(y * width + x)) {
                        val i = y * stride + x / 32
                        words[i] = words[i] or (1 shl (x and 31))
                    }
                }
            }
            return CfaHighlightClipping(width, height, words)
        }

        internal fun from(width: Int, height: Int, predicate: (Int) -> Boolean): CfaHighlightClipping {
            val stride = (width + 31) / 32
            val words = IntArray(stride * height)
            for (y in 0 until height) for (x in 0 until width) {
                if (predicate(y * width + x)) {
                    val i = y * stride + x / 32
                    words[i] = words[i] or (1 shl (x and 31))
                }
            }
            return CfaHighlightClipping(width, height, words)
        }
    }
}
