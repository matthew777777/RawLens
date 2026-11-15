// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens;

/** Loaded by the root worker only. No camera controls are changed by this query. */
public final class LapisRootCameraNative {
    public static native int[] activeSensorFormat();
    /** Version 2: 200 MP 4x4 CFA -> 50 MP linear RGB16 on Vulkan; returns {gainsMs, gpuMs, totalMs, tiles}. */
    public static native double[] hexaV2(java.nio.ByteBuffer src,int rowStride,int width,int height,float black,float white,java.nio.ByteBuffer dst,byte[] filterSpv,byte[] pointSpv,byte[] boxSpv) throws java.io.IOException;
}
