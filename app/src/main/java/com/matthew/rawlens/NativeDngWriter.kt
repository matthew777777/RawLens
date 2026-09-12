package com.matthew.rawlens

import android.hardware.camera2.CaptureResult
import android.media.Image
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Camera2 metadata/stride bridge into the pinned TinyDNG v3 writer. */
object NativeDngWriter {
    @Synchronized
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
        val black = DoubleArray(4) { outputIndex ->
            val x = outputIndex and 1
            val y = outputIndex shr 1
            val sourceIndex = (((y + originY) and 1) shl 1) or ((x + originX) and 1)
            declaredBlack[sourceIndex]
        }
        val white = (overrides.whiteLevel ?: metadata.whiteLevel?.toDouble()
            ?: error("Camera2 did not report a white level")).roundToInt()
        val tags = TinyDngMetadata.create(metadata, overrides, outputPattern,
            black, white, active)
        result.get(CaptureResult.LENS_FOCAL_LENGTH)?.let {
            tags.reals(37386, doubleArrayOf(it.toDouble()), 5)
        }
        result.get(CaptureResult.LENS_APERTURE)?.let {
            tags.reals(33437, doubleArrayOf(it.toDouble()), 5)
        }
        writeNative(contiguousRawBuffer(image, plane), image.width, image.height,
            tags.descriptors.toIntArray(), tags.payloads.toTypedArray(), outputStream)
    }

    private external fun writeNative(raw: ByteBuffer, width: Int, height: Int,
        descriptors: IntArray, payloads: Array<ByteArray>, output: OutputStream)

    init { System.loadLibrary("rawLensDng") }

    private fun contiguousRawBuffer(image: Image, plane: Image.Plane): ByteBuffer {
        val source = plane.buffer.duplicate()
        val offset = source.position()
        val required = offset.toLong() + (image.height - 1L) * plane.rowStride +
            (image.width - 1L) * plane.pixelStride + 2L
        require(required <= source.limit()) { "RAW_SENSOR plane is truncated" }
        if (offset == 0 && plane.rowStride == image.width * 2 && plane.pixelStride == 2) {
            source.position(0)
            return source
        }
        val requiredBytes = Math.multiplyExact(Math.multiplyExact(image.width, image.height), 2)
        var output = rawScratch
        if (output == null || output.capacity() < requiredBytes) {
            output = ByteBuffer.allocateDirect(requiredBytes)
            rawScratch = output
        }
        output.clear()
        output.limit(requiredBytes)
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

    // Camera RAW buffers commonly have padded rows. Reuse one direct staging allocation instead
    // of allocating ~width*height*2 native bytes for every DNG. JNI DNG writing is synchronous,
    // and write() is synchronized so the shared staging storage cannot be overwritten concurrently.
    private var rawScratch: ByteBuffer? = null

}
