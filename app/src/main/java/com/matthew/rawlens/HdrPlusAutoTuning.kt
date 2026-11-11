// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Fixed geometry and meter parameters of HDR+ auto. Decisions (strength,
 * path, frame rejection, reference) are not tuned here: they are derived per
 * burst from the measured noise model ([HdrPlusAutoSelect],
 * [HdrPlusMergeModel], [HdrPlusSceneNoise]).
 */
object HdrPlusAutoTuning {
    /**
     * Meter-only: a block is "hot" when its difference exceeds this
     * multiple of its texture. Reported in the motion summary for the log;
     * no decision reads it.
     */
    const val HOT_RATIO = 2.0
    /**
     * Strength-map grid (Fast-path maps, GCam-style denoise maps at
     * subject scale): one strength per NxN raw-pixel block, covering
     * the unpadded frame ((W+31)/32 x (H+31)/32). Must match the
     * shader's `q >> 4` lookup over 2px weight cells and the host's
     * HDRPLUS_MAP_BLOCK_PX (all three documented together).
     */
    const val MAP_BLOCK_PX = 32
}
