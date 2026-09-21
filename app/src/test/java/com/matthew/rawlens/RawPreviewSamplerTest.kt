// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class RawPreviewSamplerTest {
    @Test fun `all Bayer patterns reconstruct canonical channels with padded rows and pixel strides`() {
        for (cfa in 0..3) {
            val channels = RawPreviewGeometry.channels(cfa)
            val source = ByteBuffer.allocate(128).order(ByteOrder.nativeOrder())
            source.position(4)
            val values = intArrayOf(100, 200, 300, 400)
            channels.forEachIndexed { index, channel ->
                source.putShort(4 + (2 + channel / 2) * 20 + (2 + channel % 2) * 4, values[index].toShort())
            }
            val output = ByteBuffer.allocate(4)
            RawPreviewSampler.copy(source, 20, 4, 2, 2, 1, 1, 2, channels, FloatArray(4), 510f, output)
            assertEquals(listOf(50, 100, 150, 200), List(4) { output.get().toInt() and 255 })
            assertEquals(4, source.position())
        }
    }
    @Test fun `black white and clipping use each sensor site level`() {
        val source = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        listOf(10, 40, 65535, 0).forEach { source.putShort(it.toShort()) }; source.flip()
        val output = ByteBuffer.allocate(4)
        RawPreviewSampler.copy(source, 4, 2, 0, 0, 1, 1, 2, intArrayOf(0, 1, 2, 3),
            floatArrayOf(10f, 20f, 30f, 40f), 60f, output)
        assertEquals(listOf(0, 128, 255, 0), List(4) { output.get().toInt() and 255 })
    }
    @Test fun `portrait rotation and mirroring share metering coordinates`() {
        assertEquals(0.25f to 0.75f, RawPreviewGeometry.sensorPoint(0.25f, 0.25f, 90, false))
        assertEquals(0.25f to 0.25f, RawPreviewGeometry.sensorPoint(0.25f, 0.25f, 90, true))
        assertEquals(0.75f to 0.25f, RawPreviewGeometry.sensorPoint(0.25f, 0.25f, 270, false))
        assertEquals(0.75f to 0.75f, RawPreviewGeometry.sensorPoint(0.25f, 0.25f, 180, false))
    }
}
