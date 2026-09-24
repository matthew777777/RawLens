package com.matthew.rawlens

import kotlin.math.ceil
import kotlin.math.roundToLong

/** Short-first bracket at a shared gain; field names retained for capture ordering. */
internal object DualRawExposure {
    data class Frame(val iso: Int, val nanos: Long)
    data class Plan(val high: Frame, val low: Frame)

    fun plan(meteredIso: Int, meteredNanos: Long, minIso: Int, maxIso: Int,
             minNanos: Long, maxNanos: Long): Plan {
        require(minIso > 0 && maxIso >= minIso && minNanos > 0 && maxNanos >= minNanos)
        require(meteredIso > 0 && meteredNanos > 0)
        val target = meteredIso.toDouble() * meteredNanos
        val limit = minOf(maxNanos, 33_333_333L).coerceAtLeast(minNanos)
        val iso = maxOf(minIso, ceil(target / limit).coerceAtMost(maxIso.toDouble()).toInt())
        val longTime = (target / iso).roundToLong().coerceIn(minNanos, limit)
        val shortTime = (longTime / 4.0).roundToLong().coerceAtLeast(minNanos)
        require(longTime >= shortTime * 2.0) { "Scene too bright for useful RAW highlight headroom" }
        return Plan(Frame(iso, shortTime), Frame(iso, longTime))
    }
}
