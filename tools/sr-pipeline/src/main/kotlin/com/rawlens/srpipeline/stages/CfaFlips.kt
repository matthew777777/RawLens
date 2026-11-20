package com.rawlens.srpipeline.stages

/** RGGB normalization flips (DngRead.flipToRggb) for a DNG CFA pattern. */
object CfaFlips {
    /**
     * (flipX, flipY) that map `cfa` to RGGB. CFA codes in
     * [00, 01, 10, 11] order (0 R, 1 G, 2 B): RGGB none, [2,1,1,0] both,
     * [1,0,2,1] horizontal, [1,2,0,1] vertical.
     */
    fun rggbFlips(cfa: IntArray): Pair<Boolean, Boolean> = when (cfa.toList()) {
        listOf(0, 1, 1, 2) -> false to false
        listOf(2, 1, 1, 0) -> true to true
        listOf(1, 0, 2, 1) -> true to false
        listOf(1, 2, 0, 1) -> false to true
        else -> throw IllegalArgumentException("unsupported CFA ${cfa.toList()}")
    }
}
