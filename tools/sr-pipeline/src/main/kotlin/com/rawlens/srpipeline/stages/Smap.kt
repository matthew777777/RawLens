package com.rawlens.srpipeline.stages

/**
 * Sensitivity (flow-spread) map stage.
 *
 * Mirrors `compute_s` / `cpu_compute_s` in
 * `handheld_super_resolution/robustness.py`: for each flow tile, the 3x3
 * min/max spread decides between `s1` (irregular, `spread^2 > Mt^2`) and
 * `s2` (smooth). Out-of-window neighbours are skipped, not clamped.
 *
 * Python config contract: `M_th`, `s1`, `s2` come from
 * `config.robustness` (`Mt = 0.8`, `s1 = 2`, `s2 = 12`); the map feeds
 * `robustness_threshold` (see [robustnessThreshold]).
 *
 * Delegates to the real implementation in Kernels.kt ([computeS]); no
 * logic is duplicated here.
 */
object Smap {

    /**
     * Flow-spread map `[nTy][nTx]`, cf. `compute_s(flows, M_th, s1, s2)`.
     * Defaults are the `RobustnessConfig` values.
     */
    fun compute(
        flow: Array<Array<FloatArray>>,
        mTh: Float = 0.8f,
        s1: Float = 2f,
        s2: Float = 12f,
    ): Array<FloatArray> {
        require(flow.isNotEmpty() && flow[0].isNotEmpty()) { "flow must be non-empty" }
        return computeS(flow, mTh, s1, s2)
    }
}
