// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** Streaming compatibility export: black subtraction and LSC, with no demosaic or clipping.
 * Strips bound memory independently of sensor resolution. The original Image is read-only.
 */
internal object ShadedFloatDngWriter {
    fun write(output: OutputStream, source: ByteBuffer, rowStride: Int, pixelStride: Int,
              format: Int, metadata: RawFrameMetadata, overrides: DngMetadataOverrides,
              gps: GpsLocation? = null, captureTimeMillis: Long? = null) {
        overrides.validate()
        require(!metadata.quadBayer) { "Shading compatibility export needs a standard Bayer readout" }
        val geometry = metadata.bufferGeometry as? RawBufferGeometry.Supported
            ?: error("Shading compatibility export needs verified RAW geometry")
        val model = if (metadata.lensShadingAlreadyApplied) null
            else RawPreDemosaicPipeline.lensShadingModel(metadata)
        require(metadata.lensShadingAlreadyApplied || model != null) {
            "Shading compatibility export needs a camera lens-shading map"
        }
        val black = overrides.blackLevels?.map(Double::toFloat) ?: metadata.blackLevels?.toList()
            ?: error("Missing black levels")
        val white = overrides.whiteLevel?.toFloat() ?: metadata.whiteLevel ?: error("Missing white level")
        val normalization = RawNormalization(requireNotNull(metadata.cfaPattern), black, white)
        val width = metadata.imageWidth; val height = metadata.imageHeight
        val pattern = normalization.sensorPattern.shifted(geometry.sensorOriginX, geometry.sensorOriginY)
        val tags = TinyDngMetadata.create(metadata,
            overrides.copy(blackLevels = null, whiteLevel = null, noiseProfile = null), pattern,
            DoubleArray(4), 1, geometry.processingCrop, shadingBaked = true)
        val fields = TiffFields()
        tags.descriptors.chunked(3).forEachIndexed { index, d ->
            // The streaming facade owns NewSubfileType; replace backward version below.
            if (d[0] != 254 && d[0] != 50707) fields.tag(d[0], d[1], tags.payloads[index])
        }
        fields.ascii(270, "Captured with RawLens\nFloating-point Bayer compatibility DNG\n" +
            "Lens shading baked into samples; black subtracted; no demosaic or geometric warp")
        // IEEE-float CFA requires DNG 1.4, including its backward-version declaration.
        fields.bytes(50707, byteArrayOf(1,4,0,0))
        val rows = 32
        val origin = source.position()
        TinyDngImageWriter.write(output,width,height,1,32,3,32803,rows,fields,
            DngExifDirectory.fields(metadata,captureTimeMillis), gps?.let { GpsTiffDirectory.fields(it) },
            stripCount = (height+rows-1)/rows) { strip ->
            val top = strip*rows
            val count = min(rows,height-top)
            val view = source.duplicate().apply { position(Math.addExact(origin,Math.multiplyExact(top,rowStride))) }
            val cfa = RawPackedUnpacker.unpackFrameNormalized(view,width,count,rowStride,pixelStride,format,
                normalization, geometry.sensorOriginX, geometry.sensorOriginY+top,
                byteOrder = ByteOrder.LITTLE_ENDIAN)
            val corrected = LensShadingCorrector.applyOwnedInPlace(cfa,model).cfa
            ByteBuffer.allocate(Math.multiplyExact(corrected.values.size,4)).order(ByteOrder.LITTLE_ENDIAN).apply {
                corrected.values.forEach { value ->
                    require(value.isFinite()) { "Invalid corrected RAW sample" }
                    putFloat(value) // preserve negative noise and highlights above nominal white
                }
            }.array()
        }
    }
}
