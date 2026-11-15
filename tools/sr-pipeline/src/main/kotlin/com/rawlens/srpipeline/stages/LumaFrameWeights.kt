package com.rawlens.srpipeline.stages

import kotlin.math.roundToInt

/**
 * Keep original sensor grain uniformly by limiting luminance averaging, while
 * all frames still contribute colour differences and run motion rejection.
 * The same weights apply to buildings, foliage, subjects and smooth areas.
 */
internal object LumaFrameWeights {
    fun forBurst(frames: Int, retention: Float): FloatArray {
        require(frames > 0)
        require(retention.isFinite() && retention in 0f..1f) { "grainRetention must be in 0..1" }
        val kept = minOf(frames, 6)
        val selected = BooleanArray(frames)
        if(kept==1) selected[0]=true
        else for(i in 0 until kept) selected[(i.toDouble()*(frames-1)/(kept-1)).roundToInt()]=true
        return FloatArray(frames) { i ->
            1f-retention + if(selected[i]) retention*frames/kept else 0f
        }
    }
}
