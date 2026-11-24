package com.rawlens.srpipeline.stages

/**
 * KB-SR (Wronski / IPOL engine) tuning, shared by the app's "KB-SR Lab"
 * (Settings -> Merge) and the desktop tuning sweeps, so a value tuned on the
 * Mac is exactly what the phone runs.
 *
 * SNR-tuned kernel values (Wronski supplement S.2 / IPOL update_snr_config:
 * kDetail, kDenoise, Dth, Dtr) are exposed as MULTIPLIERS of the per-burst
 * SNR value ([BurstConfig.finalize]), so 1.0 keeps the automatic tuning at
 * every light level. Everything else is the value itself.
 * The neutral constructor is the lab's original baseline. [APP_DEFAULT]
 * is the starting point for app settings and desktop app-mode renders.
 */
data class SrTuning(
    /** x kDetail (base kernel std, S.1): lower = sharper and noisier. */
    val kDetailScale: Float = 1f,
    /** x kDenoise (kernel growth in flat areas): higher = smoother flats. */
    val kDenoiseScale: Float = 1f,
    /** x Dth (denoise threshold in the D map). */
    val dThScale: Float = 1f,
    /** x Dtr (transition from denoise to detail). */
    val dTrScale: Float = 1f,
    /** Kernel stretch along edges (S.1 kstretch). */
    val kStretch: Float = 3f,
    /** Kernel shrink across edges (S.1 kshrink). */
    val kShrink: Float = 2f,
    /** Minimum kernel std, px (C26 checker fix; 0 = paper). */
    val kMin: Float = 0.64f,
    /** Robustness threshold t (Eq. 5). */
    val t: Float = 0.12f,
    /** Robustness scale where the flow varies (M > Mt), Eq. 8 (IPOL order). */
    val s1: Float = 2f,
    /** Robustness scale elsewhere, Eq. 8. */
    val s2: Float = 12f,
    /** Motion threshold Mt (px), Eq. 7-8. */
    val mt: Float = 0.8f,
    /** S.3 variance-loss ratio threshold. */
    val s3Rho: Float = 0.1f,
    /** S.3 minimum variance in noise units. */
    val s3K: Float = 16f,
    /** Noise-corrected robustness (Eq. 6) when a noise LUT exists. */
    val noiseCorrection: Boolean = true,
    /** ICA (Lucas-Kanade) iterations per pyramid level (paper: 3). */
    val icaIterations: Int = 3,
    /**
     * R/B kernel width relative to green at 2x (colour-difference merge).
     * The 2x zipper is aliasing from incomplete sub-pixel coverage
     * (ZipperRootCauseTest): green keeps the narrow kernel for detail, the
     * colour differences get x1.5 (R/B zipper 1.38/1.30 -> 1.15/1.17 on the
     * reported burst, green unchanged). 1 = one kernel for all channels.
     */
    val chromaScale2x: Float = 1.5f,
    /** R/B (colour-difference) kernel width relative to green at 1x; 1 = one kernel. */
    val chromaScale1x: Float = 1f,
    /** Merge frame equalization gamma (0 = Wronski weights, 1 = equal frames). */
    val frameEqualize: Float = 0f,
    /** Noise energy for experimental weak-edge gating in GAT units. Zero disables it. */
    val weakEdgeNoise: Float = 0f,
    /** Anchor flat width to the untuned per-burst detail width. */
    val independentWidths: Boolean = false,
) {
    companion object {
        val APP_DEFAULT = SrTuning(
            kDetailScale = 0.85f,
            kDenoiseScale = 1.30f,
            independentWidths = true,
        )
    }

    /** R/B width factor for an output [scale]. */
    fun chromaScale(scale: Float): Float = if (scale > 1f) chromaScale2x else chromaScale1x


    fun kernelParams(fin: BurstConfig.Final): KernelParams = KernelParams(
        kDetail = (fin.kDetail * kDetailScale).toFloat(),
        kDenoise = (fin.kDenoise * kDenoiseScale).toFloat(),
        dTh = (fin.dTh * dThScale).toFloat(),
        dTr = (fin.dTr * dTrScale).toFloat(),
        kStretch = kStretch,
        kShrink = kShrink,
        kMin = kMin,
        weakEdgeNoise = weakEdgeNoise,
        flatSigma = if (independentWidths) (fin.kDetail * fin.kDenoise * kDenoiseScale).toFloat() else 0f,
    )

    fun robustnessParams(haveNoiseLut: Boolean, s3: Boolean): RobustnessParams = RobustnessParams(
        t = t, s1 = s1, s2 = s2, mt = mt,
        noiseCorrection = haveNoiseLut && noiseCorrection,
        s3 = s3, s3Rho = s3Rho, s3K = s3K,
    )
}
