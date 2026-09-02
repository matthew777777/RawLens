package com.matthew.rawlens

import android.graphics.Point
import android.graphics.Rect
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.util.Pair
import com.particlesdevs.photoncamera.processing.DngCreator
import com.particlesdevs.photoncamera.processing.render.NoiseModeler
import com.particlesdevs.photoncamera.processing.render.Parameters
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Camera2 metadata/stride bridge into PhotonCamera's unchanged Java and native DNG saver. */
object NativeDngWriter {
    fun write(
        outputStream: OutputStream,
        image: Image,
        metadata: RawFrameMetadata,
        result: CaptureResult,
        overrides: DngMetadataOverrides
    ) {
        overrides.validate()
        val plane = image.planes.singleOrNull()
            ?: error("RAW_SENSOR must expose exactly one image plane")
        require(plane.pixelStride >= 2) { "RAW_SENSOR pixel stride cannot hold 16 bits" }

        val geometry = metadata.bufferGeometry as? RawBufferGeometry.Supported
        val originX = geometry?.sensorOriginX ?: 0
        val originY = geometry?.sensorOriginY ?: 0
        val active = geometry?.processingCrop ?: RawCrop(0, 0, image.width, image.height)
        val sensorPattern = metadata.cfaPattern
            ?: error("Camera2 did not report a supported Bayer CFA arrangement")
        val outputPattern = sensorPattern.shifted(originX, originY)

        val declaredBlack = overrides.blackLevels
            ?: metadata.blackLevels?.toList()?.map(Float::toDouble)
            ?: error("Camera2 did not report black levels")
        val black = FloatArray(4) { outputIndex ->
            val x = outputIndex and 1
            val y = outputIndex shr 1
            val sourceIndex = (((y + originY) and 1) shl 1) or ((x + originX) and 1)
            declaredBlack[sourceIndex].toFloat()
        }
        val white = (overrides.whiteLevel ?: metadata.whiteLevel?.toDouble()
            ?: error("Camera2 did not report a white level")).roundToInt()
        val lensMap = metadata.lensShadingMap.takeUnless { metadata.lensShadingAlreadyApplied }

        val parameters = Parameters().apply {
            rawSize = Point(image.width, image.height)
            iso = metadata.sensitivityIso ?: 0
            exposureTime = (metadata.exposureTimeNanos ?: 0L) / 1_000_000_000.0
            focalLength = result.get(CaptureResult.LENS_FOCAL_LENGTH) ?: 4.75f
            aperture = result.get(CaptureResult.LENS_APERTURE) ?: 1.8f
            cameraRotation = exifOrientationToDegrees(metadata.exifOrientation)
            cfaPattern = outputPattern.ordinal.toByte()
            blackLevel = black
            whitePoint = (metadata.neutralColorPoint?.toDoubleArray()
                ?: doubleArrayOf(1.0, 1.0, 1.0)).map(Double::toFloat).toFloatArray()
            whiteLevel = white
            calibrationIlluminant1 = metadata.referenceIlluminant1 ?: 0
            calibrationIlluminant2 = metadata.referenceIlluminant2 ?: calibrationIlluminant1
            calibrationTransform1 = matrix(overrides.cameraCalibration1, metadata.cameraCalibration1)
            calibrationTransform2 = matrix(overrides.cameraCalibration2, metadata.cameraCalibration2)
            ForwardTransform1 = matrix(overrides.forwardMatrix1, metadata.forwardMatrix1)
            ForwardTransform2 = matrix(overrides.forwardMatrix2, metadata.forwardMatrix2)
            ColorMatrix1 = matrix(overrides.colorMatrix1, metadata.colorMatrix1)
            ColorMatrix2 = matrix(overrides.colorMatrix2, metadata.colorMatrix2)
            gainMap = lensMap?.gains?.toFloatArray() ?: floatArrayOf(1f, 1f, 1f, 1f)
            mapSize = Point(lensMap?.columns ?: 1, lensMap?.rows ?: 1)
            sensorPix = Rect(active.left, active.top, active.left + active.width, active.top + active.height)
            noiseModeler = photonNoiseModel(
                overrides.noiseProfile?.toDoubleArray() ?: metadata.noiseProfile?.toDoubleArray()
            )
        }

        val rawBuffer = contiguousRawBuffer(image, plane)
        val creator = DngCreator()
        try {
            creator.setParameters(parameters)
            creator.setCompression(false)
            creator.writeBuffer(outputStream, rawBuffer, image.width, image.height)
        } finally {
            creator.close()
        }
    }

    private fun matrix(override: List<Double>?, captured: ImmutableDoubleValues?): FloatArray {
        val camera2 = override?.toDoubleArray() ?: captured?.toDoubleArray() ?: IDENTITY
        // Exact PhotonCamera Converter.convertColorspaceTransform convention. Camera2 exposes
        // getElement(row, column); Photon stores output[row,column] = input[column,row].
        return FloatArray(9) { index ->
            val row = index / 3
            val column = index % 3
            camera2[column * 3 + row].toFloat()
        }
    }

    private fun photonNoiseModel(values: DoubleArray?): NoiseModeler? {
        if (values == null || values.size !in setOf(6, 8)) return null
        val rgb = if (values.size == 8) {
            doubleArrayOf(
                values[0], values[1],
                (values[2] + values[4]) * 0.5, (values[3] + values[5]) * 0.5,
                values[6], values[7]
            )
        } else values
        @Suppress("UNCHECKED_CAST")
        val pairs = arrayOfNulls<Pair<Double, Double>>(3) as Array<Pair<Double, Double>>
        for (channel in 0..2) pairs[channel] = Pair(rgb[channel * 2], rgb[channel * 2 + 1])
        return NoiseModeler().apply {
            baseModel = pairs.copyOf()
            computeModel = pairs.copyOf()
        }
    }

    private fun contiguousRawBuffer(image: Image, plane: Image.Plane): ByteBuffer {
        val source = plane.buffer.duplicate()
        val offset = source.position()
        val required = offset.toLong() + (image.height - 1L) * plane.rowStride +
            (image.width - 1L) * plane.pixelStride + 2L
        require(required <= source.capacity()) { "RAW_SENSOR plane is truncated" }
        if (offset == 0 && plane.rowStride == image.width * 2 && plane.pixelStride == 2) {
            source.position(0)
            return source
        }
        val output = ByteBuffer.allocateDirect(image.width * image.height * 2)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val index = offset + y * plane.rowStride + x * plane.pixelStride
                output.put(source.get(index))
                output.put(source.get(index + 1))
            }
        }
        output.flip()
        return output
    }

    private fun exifOrientationToDegrees(orientation: Int): Int = when (orientation) {
        6 -> 90
        3 -> 180
        8 -> 270
        else -> 0
    }

    private val IDENTITY = doubleArrayOf(
        1.0, 0.0, 0.0,
        0.0, 1.0, 0.0,
        0.0, 0.0, 1.0
    )
}
