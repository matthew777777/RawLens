// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens;

/** Loaded by the root worker only. No camera controls are changed by this query. */
public final class LapisRootCameraNative {
    public static native int[] activeSensorFormat();
}
