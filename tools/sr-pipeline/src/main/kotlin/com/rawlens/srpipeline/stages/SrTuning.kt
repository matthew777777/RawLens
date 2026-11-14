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
 * Defaults = the shipping engine before the Lab existed.
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
    val kStretch: Float = 4f,
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
    /** KernelNet width scale. */
    val knSigmaScale: Float = KernelNet.DEFAULT_SIGMA_SCALE,
    /** KernelNet width cap, px. */
    val knSigmaCap: Float = KernelNet.DEFAULT_SIGMA_CAP,
) {
    fun kernelParams(fin: BurstConfig.Final): KernelParams = KernelParams(
        kDetail = (fin.kDetail * kDetailScale).toFloat(),
        kDenoise = (fin.kDenoise * kDenoiseScale).toFloat(),
        dTh = (fin.dTh * dThScale).toFloat(),
        dTr = (fin.dTr * dTrScale).toFloat(),
        kStretch = kStretch,
        kShrink = kShrink,
        kMin = kMin,
    )

    fun robustnessParams(haveNoiseLut: Boolean, s3: Boolean): RobustnessParams = RobustnessParams(
        t = t, s1 = s1, s2 = s2, mt = mt,
        noiseCorrection = haveNoiseLut && noiseCorrection,
        s3 = s3, s3Rho = s3Rho, s3K = s3K,
    )
}
