// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Quick Control exclusion between the HDR bracket tile and the HDR+ bottom
 * tile. Both merge one shutter press into one DNG (forward bracket vs ZSL
 * ring), so users lighting both expect a stack that can never run: the
 * shutter can only pick one merger. Enabling either tile therefore turns
 * the other off, and turning one off never touches the other. Pairs are
 * (hdrBracket, hdrPlus).
 */
object HdrShutterExclusivity {
    fun bracketToggled(hdrBracket: Boolean, hdrPlus: Boolean): Pair<Boolean, Boolean> =
        if (!hdrBracket) true to false else false to hdrPlus

    fun plusToggled(hdrBracket: Boolean, hdrPlus: Boolean): Pair<Boolean, Boolean> =
        if (!hdrPlus) false to true else hdrBracket to false

    /**
     * A both-on pair saved before the tiles excluded each other resolves to
     * HDR+: the explicit bottom tile keeps the shutter, the bracket drops.
     * Every other pair passes through unchanged.
     */
    fun resolveStale(hdrBracket: Boolean, hdrPlus: Boolean): Pair<Boolean, Boolean> =
        if (hdrBracket && hdrPlus) false to true else hdrBracket to hdrPlus

    /**
     * Exposure mode right after the bracket is enabled. HDR+ pulled the
     * camera into ZSL for its ring, but bracket frames are forward
     * captures, so ZSL falls back to AUTO. Deliberate PROGRAM/MANUAL
     * choices are untouched.
     */
    fun modeAfterBracketEnabled(current: CaptureExposureMode): CaptureExposureMode =
        if (current == CaptureExposureMode.ZSL) CaptureExposureMode.AUTO else current

    /**
     * Exposure mode after HDR+ is turned off. Enabling HDR+ pulls the
     * camera into ZSL for its ring buffer, so turning the tile off would
     * otherwise strand the user in ZSL burst output. The mode from before
     * HDR+ forced ZSL is restored; a manual mode change made while HDR+
     * was on is the user's new intent and is left alone, as is a camera
     * that was already in ZSL before HDR+.
     */
    fun modeAfterPlusDisabled(
        current: CaptureExposureMode,
        modeBeforePlus: CaptureExposureMode?
    ): CaptureExposureMode =
        if (current == CaptureExposureMode.ZSL &&
            modeBeforePlus != null &&
            modeBeforePlus != CaptureExposureMode.ZSL
        ) modeBeforePlus else current
}
