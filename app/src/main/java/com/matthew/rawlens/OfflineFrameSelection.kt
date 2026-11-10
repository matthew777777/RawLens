// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Zero-based source indices, always decoded in capture order. Null manual list means even sampling. */
internal data class OfflineFrameSelection(
    val count: Int? = null,
    val manualIndices: List<Int>? = null
) {
    fun resolve(total: Int, limit: Int): List<Int> {
        require(total >= 2) { "A merge needs at least 2 source frames" }
        val maximum = minOf(total, limit)
        require(maximum >= 2) { "This method needs at least 2 frames" }
        manualIndices?.let { indices ->
            require(indices.size in 2..maximum) { "Select 2–$maximum frames for this method" }
            require(indices.distinct().size == indices.size) { "Duplicate frame selection" }
            require(indices.all { it in 0 until total }) { "Selected frames are no longer in this take" }
            return indices.sorted()
        }
        val requested = count ?: maximum
        require(requested >= 2) { "A merge needs at least 2 frames" }
        return BurstMergeTakes.evenSampleIndices(total, minOf(requested, maximum))
    }
}
