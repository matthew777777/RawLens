// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * All HDR+ auto thresholds in one place (GCam `Tuning`, minimal).
 *
 * Pixel thresholds are fitted on eleven bursts, measured with the
 * same stride-16 metric the meter ships: the 33TJ street + burst-0006
 * landscape file bursts, plus nine RawLens source bursts (daylight,
 * evening, and one ISO-4653 night burst). Burst-level max pair hot:
 * stills at most 0.42% (burst-0006; the eight in-app stills at most
 * 0.27%), motion at 3.0% (evening scene) and 90% (33TJ, corrupt
 * frame). Drop rule verified: it catches 33TJ frame 10 with no
 * tuning (pair 48x the median MAD, frame 4.3x median brightness);
 * no in-app burst drops anything (pairs within 1.6x, brightness
 * within 6%). Heavy night noise does not inflate the ratio (0.0%
 * hot at ISO 4653).
 * Gyro thresholds are fitted two-sided on seven sidecar bursts with
 * per-frame gyro (sparse single samples, ~13 ms windows at GAME rate;
 * pair i uses frame i's magnitude, NaN when the window is empty):
 * - Still bursts (3): 13 samples at 0.042-0.166 rad/s, burst maxes
 *   0.100-0.166; phase-correlation ground truth shows 0.1-1.4 px
 *   inter-frame shifts, i.e. instantaneous peaks overread net
 *   displacement 8-30x (tremor + post-gap sample timing).
 * - Shaky bursts (4, confirmed by 3-175 px shifts and 9.7-51% hot):
 *   burst maxes 0.478-1.074 rad/s. Sustained pans agree with pixels
 *   (0.412 predicts 49 px vs 48 measured); lone spikes do not.
 * The pixel leg backstops motion (all four shaky bursts trip it),
 * so the gyro line favors still-side safety. Single-sample spikes
 * remain a false-Fast risk; revisit if logs show stills flipping.
 */
object HdrPlusAutoTuning {
    /** Block is hot when its MAD exceeds this multiple of its texture. */
    const val HOT_RATIO = 2.0
    /** At or below this pair hot fraction the burst counts as still. */
    const val STILL_HOT_FRACTION = 0.002
    /** At or above this pair hot fraction the burst counts as motion. */
    const val MOTION_HOT_FRACTION = 0.008
    /**
     * Above this max pair hot fraction the auto path is Fast. Sits
     * between the hottest still burst (0.42%) and the mildest motion
     * burst (3.0%), and equals [MOTION_HOT_FRACTION], so full motion
     * penalty coincides with the Fast path.
     */
    const val FAST_PATH_HOT_FRACTION = 0.008
    /** Drop frames whose every adjacent pair exceeds this x median MAD. */
    const val OUTLIER_PAIR_RATIO = 4.0
    /** Drop frames whose mean brightness leaves this x median band. */
    const val BRIGHTNESS_RATIO_MIN = 0.5
    const val BRIGHTNESS_RATIO_MAX = 2.0
    /**
     * Gyro at/below this counts as still: covers 12 of 13 still
     * samples (max 0.166).
     */
    const val GYRO_STILL_RAD_S = 0.15
    /**
     * Gyro at/above this counts as full motion. Equals the Fast
     * line, so full gyro penalty coincides with the Fast path.
     */
    const val GYRO_MOTION_RAD_S = 0.30
    /**
     * Above this max gyro the auto path is Fast. Sits between the
     * still max (0.166) and the mildest shaky-burst max (0.478),
     * favoring still-side safety (the pixel leg backstops motion).
     */
    const val GYRO_FAST_PATH_RAD_S = 0.30
    /** Strength curve anchor: still-scene strength. */
    const val STRENGTH_BASE = 13f
    /** Strength lost from still to full motion. */
    const val STRENGTH_MOTION_PENALTY = 10f
    /** Strength lost from still to full gyro motion. */
    const val STRENGTH_GYRO_PENALTY = 6f
    /** Strength regained in full darkness (denoise need). */
    const val STRENGTH_DARK_BOOST = 2f
    /** Auto strength floor (plan range). */
    const val AUTO_STRENGTH_MIN = 3f
    /**
     * Auto strength ceiling. 13 is the tested default; 16 needs a
     * still-burst sweep proving stronger helps before it is allowed.
     */
    const val AUTO_STRENGTH_MAX = 13f
    /** Mean level at/below this is full darkness. */
    const val DARK_LEVEL_FULL = 0.02
    /** Mean level at/above this needs no darkness boost. */
    const val DARK_LEVEL_NONE = 0.12
    /** ISO at/below this needs no darkness boost. */
    const val ISO_REF = 100
    /** ISO at/above this is full darkness. */
    const val ISO_FULL = 1600
    /** Base score is sharpness / (1 + gain x local motion). */
    const val LOCAL_MOTION_GAIN = 100.0
    /**
     * Strength-map grid (Fast-path maps, GCam-style denoise maps at
     * subject scale): one strength per NxN raw-pixel block, covering
     * the unpadded frame ((W+31)/32 x (H+31)/32). Must match the
     * shader's `q >> 4` lookup over 2px weight cells and the host's
     * HDRPLUS_MAP_BLOCK_PX (all three documented together).
     */
    const val MAP_BLOCK_PX = 32
    /** Block mismatch ratio at/below this merges at full strength. */
    const val MAP_RATIO_CLEAN = 1.0
    /** Block mismatch ratio at/above this merges at the floor. */
    const val MAP_RATIO_MOTION = 4.0
    /**
     * Dense-MAD danger ramp (native meter): full-res mean |prev-curr|
     * per 32px cell, normalized. Stride-16 sampling skips the 1-2px
     * wires that actually ghost (s16 ratio AUC 0.39 on merge-labeled
     * blocks); dense MAD ranks them first (AUC 0.82 pooled, 0.77-1.00
     * per burst). Calibrated on s16-lenient blocks only (the ratio
     * already owns strict ones): CLEAN sits below the positives' p10
     * (0.0043), STRICT near their median (0.0104); ~7% of s16-lenient
     * clean blocks read above STRICT, all with real dense jitter.
     * Fit: 551 merge-sensitive blocks (|s13-s3| > 0.008, micro-ghosts
     * visually confirmed) over 3 still + 2 shaky bursts, Oct 2026.
     */
    const val MAP_DANGER_CLEAN = 0.004
    /** Dense MAD at/above this merges at the floor (see above). */
    const val MAP_DANGER_STRICT = 0.012
}
