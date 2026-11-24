// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens;

/** Loaded by the root worker only. No camera controls are changed by this query. */
public final class LapisRootCameraNative {
    public static native int[] activeSensorFormat();
    /** rawr 2x2 sum + Quad Detail remosaic; 50 MP Bayer, x4 sensor code scale. */
    public static native double hexaReduced(java.nio.ByteBuffer src,int width,int height,float black,java.nio.ByteBuffer dst,boolean phaseFit) throws java.io.IOException;
}
