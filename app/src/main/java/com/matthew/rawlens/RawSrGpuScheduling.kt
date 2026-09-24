// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Bounded independent invocation rectangles; preserves coordinates and arithmetic. */
internal object RawSrGpuScheduling {
    data class Slice(val x: Int, val y: Int, val groupsX: Int, val groupsY: Int)

    fun slices(width: Int, height: Int, localX: Int, localY: Int, budget: Int): Sequence<Slice> {
        require(width > 0 && height > 0 && localX > 0 && localY > 0)
        require(budget >= localX * localY)
        val gx = (width + localX - 1) / localX
        val gy = (height + localY - 1) / localY
        val maxGroups = budget / (localX * localY)
        val spanX = minOf(gx, maxGroups)
        val spanY = maxOf(1, maxGroups / spanX)
        return sequence {
            for (y in 0 until gy step spanY) for (x in 0 until gx step spanX) {
                yield(Slice(x * localX, y * localY, minOf(spanX, gx - x), minOf(spanY, gy - y)))
            }
        }
    }

    fun shaderSource(source: String): String {
        require(source.startsWith("#version") && source.contains("gl_GlobalInvocationID"))
        // These kernels have no workgroup communication. Offsetting global IDs is
        // equivalent to a full dispatch, including partial edge workgroups.
        require(!source.contains("gl_WorkGroupID") && !source.contains("gl_NumWorkGroups"))
        val end = source.indexOf('\n')
        return source.substring(0, end + 1) + "uniform highp uvec3 u_dispatch_offset;\n" +
            source.substring(end + 1).replace("gl_GlobalInvocationID", "(gl_GlobalInvocationID + u_dispatch_offset)")
    }

    fun shouldRecoverCamera(nowMs: Long, lastImageMs: Long): Boolean =
        lastImageMs == 0L || nowMs - lastImageMs > 2000L
}
