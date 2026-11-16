package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.hypot
import kotlin.math.max

class DngLensCorrectionTest {
    private val intrinsics = floatArrayOf(1800f, 1800f, 2030f, 1480f, 0f)
    private val distortion = floatArrayOf(-0.08f, 0.015f, -0.001f, 0.002f, -0.003f)

    @Test fun serializedDngMapsToTheSameSourcePixelsAsCamera2IncludingCropAndTangentialTerms() {
        for ((w, h, ox, oy) in listOf(
            doubleArrayOf(4000.0, 3000.0, 0.0, 0.0),
            doubleArrayOf(3200.0, 2400.0, 200.0, 100.0))) {
            val bytes = requireNotNull(DngLensCorrection.encode(intrinsics, distortion, w.toInt(), h.toInt(), ox, oy))
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            assertEquals(1, b.int); assertEquals(1, b.int)
            assertEquals(0x01030000, b.int); assertEquals(1, b.int)
            assertEquals(68, b.int); assertEquals(1, b.int)
            val k = DoubleArray(6) { b.double }
            val cx = b.double * w; val cy = b.double * h
            assertFalse(b.hasRemaining())
            val radius = hypot(max(cx, w-cx), max(cy, h-cy))
            for (iy in 0..10) for (ix in 0..10) {
                val px = ix * (w-1) / 10; val py = iy * (h-1) / 10
                // Independent Camera2 reference, evaluated in focal-normalized coordinates.
                val x = (px+ox-intrinsics[2])/intrinsics[0]
                val y = (py+oy-intrinsics[3])/intrinsics[1]
                val r2 = x*x+y*y
                val radial = 1+distortion[0]*r2+distortion[1]*r2*r2+distortion[2]*r2*r2*r2
                val sourceX = intrinsics[0]*(x*radial+2*distortion[3]*x*y+distortion[4]*(r2+2*x*x))+intrinsics[2]-ox
                val sourceY = intrinsics[1]*(y*radial+2*distortion[4]*x*y+distortion[3]*(r2+2*y*y))+intrinsics[3]-oy
                // DNG SDK reference, decoded from the actual serialized bytes.
                val dx = (px-cx)/radius; val dy = (py-cy)/radius
                val rr = dx*dx+dy*dy
                val ratio = k[0]+k[1]*rr+k[2]*rr*rr+k[3]*rr*rr*rr
                val dngX = cx+radius*(dx*ratio+2*k[4]*dx*dy+k[5]*(rr+2*dx*dx))
                val dngY = cy+radius*(dy*ratio+2*k[5]*dx*dy+k[4]*(rr+2*dy*dy))
                assertEquals(sourceX, dngX, 1e-8)
                assertEquals(sourceY, dngY, 1e-8)
            }
        }
    }

    @Test fun omitsMissingInvalidUnrepresentableAndFoldingCalibration() {
        fun encode(i: FloatArray = intrinsics, d: FloatArray = distortion) =
            DngLensCorrection.encode(i, d, 4000, 3000)
        assertNull(encode(d = FloatArray(5)))
        assertNull(encode(d = floatArrayOf(Float.NaN, 0f, 0f, 0f, 0f)))
        assertNull(encode(d = floatArrayOf(-10f, 0f, 0f, 0f, 0f)))
        assertNull(encode(d = floatArrayOf(0f, 0f, 0f, 10f, 0f)))
        assertNull(encode(i = intrinsics.copyOf().apply { this[0] = 0f }))
        assertNull(encode(i = intrinsics.copyOf().apply { this[1] = 1900f }))
        assertNull(encode(i = intrinsics.copyOf().apply { this[4] = 10f }))
        assertNull(encode(i = intrinsics.copyOf().apply { this[2] = 5000f }))
        assertNull(encode(d = FloatArray(6))) // deprecated model must never enter this path
    }

    @Test fun metadataConversionUsesPreCorrectionOriginAndRefusesVendorReadoutOverrides() {
        val m = mock(RawFrameMetadata::class.java)
        `when`(m.imageWidth).thenReturn(4040); `when`(m.imageHeight).thenReturn(3040)
        `when`(m.preCorrectionActiveArray).thenReturn(IntRectSnapshot(20, 20, 4020, 3020))
        `when`(m.bufferGeometry).thenReturn(RawBufferGeometry.Supported(0, 0, RawCrop(20,20,4000,3000), "test"))
        `when`(m.lensIntrinsics).thenReturn(ImmutableFloatValues(intrinsics))
        `when`(m.lensDistortion).thenReturn(ImmutableFloatValues(distortion))
        val active = RawCrop(20,20,4000,3000)
        assertArrayEquals(DngLensCorrection.encode(intrinsics,distortion,4000,3000), DngLensCorrection.opcodeList(m, active))
        `when`(m.sensorReadoutProfile).thenReturn("vendor-crop")
        assertNull(DngLensCorrection.opcodeList(m, active))
    }
}
