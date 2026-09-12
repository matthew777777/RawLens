package com.matthew.rawlens

import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** TIFF field payloads for the pinned TinyDNG v3 writer. Matrices are serialized in DNG row-major order. */
internal class TinyDngMetadata {
    val descriptors = ArrayList<Int>()
    val payloads = ArrayList<ByteArray>()
    fun tag(id: Int, type: Int, bytes: ByteArray) {
        val unit = when (type) { 3 -> 2; 4 -> 4; 5, 10, 12 -> 8; else -> 1 }
        require(bytes.isNotEmpty() && bytes.size % unit == 0)
        descriptors.addAll(listOf(id, type, bytes.size / unit))
        payloads.add(bytes)
    }
    fun shorts(id: Int, vararg values: Int) = tag(id, 3, buffer(values.size * 2).apply {
        values.forEach { putShort(it.toShort()) }
    }.array())
    fun longs(id: Int, vararg values: Int) = tag(id, 4, buffer(values.size * 4).apply {
        values.forEach { putInt(it) }
    }.array())
    fun ascii(id: Int, value: String) = tag(id, 2, (value + '\u0000').toByteArray(Charsets.US_ASCII))
    fun reals(id: Int, values: DoubleArray, type: Int = 10) {
        require(values.all(Double::isFinite))
        tag(id, type, buffer(values.size * 8).apply {
            values.forEach {
                if (type == 12) putDouble(it) else {
                    require(type == 10 || it >= 0.0)
                    val denominator = 1_000_000
                    putInt((it * denominator).roundToInt()).putInt(denominator)
                }
            }
        }.array())
    }

    companion object {
        private fun buffer(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        fun create(metadata: RawFrameMetadata, overrides: DngMetadataOverrides,
                   pattern: BayerPattern, black: DoubleArray, white: Int,
                   active: RawCrop): TinyDngMetadata = TinyDngMetadata().apply {
            longs(254, 0)
            shorts(274, metadata.exifOrientation)
            ascii(271, Build.MANUFACTURER ?: "Unknown")
            ascii(272, Build.MODEL ?: "Unknown")
            ascii(305, "RawLens TinyDNG v3")
            ascii(50708, "${Build.MANUFACTURER} ${Build.MODEL} (${metadata.cameraId})")
            tag(50706, 1, byteArrayOf(1, 4, 0, 0))
            tag(50707, 1, byteArrayOf(1, 3, 0, 0))
            shorts(33421, 2, 2)
            tag(33422, 1, ByteArray(4) { pattern.colorAt(it and 1, it shr 1).ordinal.toByte() })
            tag(50710, 1, byteArrayOf(0, 1, 2))
            shorts(50711, 1)
            shorts(50713, 2, 2)
            reals(50714, black, 5)
            longs(50717, white)
            longs(50829, active.top, active.left, active.top + active.height, active.left + active.width)
            longs(50719, 0, 0)
            longs(50720, active.width, active.height)
            reals(50728, metadata.neutralColorPoint?.toDoubleArray() ?: doubleArrayOf(1.0, 1.0, 1.0), 5)
            fun matrix(id: Int, custom: List<Double>?, captured: ImmutableDoubleValues?) {
                val values = custom?.toDoubleArray() ?: captured?.toDoubleArray()?.let { snapshot ->
                    // RawFrameMetadata snapshots getElement(index / 3, index % 3).
                    // Camera2 takes (column, row), so captured arrays are column-major.
                    require(snapshot.size == 9)
                    DoubleArray(9) { snapshot[(it % 3) * 3 + it / 3] }
                }
                values?.let {
                    require(it.size == 9)
                    reals(id, it)
                }
            }
            matrix(50721, overrides.colorMatrix1, metadata.colorMatrix1)
            matrix(50723, overrides.cameraCalibration1, metadata.cameraCalibration1)
            matrix(50964, overrides.forwardMatrix1, metadata.forwardMatrix1)
            metadata.referenceIlluminant1?.let { shorts(50778, it) }
            if (metadata.referenceIlluminant2 != null) {
                matrix(50722, overrides.colorMatrix2, metadata.colorMatrix2)
                matrix(50724, overrides.cameraCalibration2, metadata.cameraCalibration2)
                matrix(50965, overrides.forwardMatrix2, metadata.forwardMatrix2)
                shorts(50779, metadata.referenceIlluminant2)
            }
            DngNoiseProfile.toRgb(overrides.noiseProfile?.toDoubleArray()
                ?: metadata.noiseProfile?.toDoubleArray(), requireNotNull(metadata.cfaPattern))
                ?.let { reals(51041, it, 12) }
            metadata.sensitivityIso?.let { shorts(34855, it.coerceIn(0, 65535)) }
            metadata.exposureTimeNanos?.let { reals(33434, doubleArrayOf(it / 1e9), 5) }
            metadata.lensShadingMap?.takeUnless { metadata.lensShadingAlreadyApplied }?.let {
                tag(51009, 7, gainMap(it, pattern, active,
                    (metadata.bufferGeometry as? RawBufferGeometry.Supported)?.sensorOriginY ?: 0))
            }
        }

        /** OpcodeList2 uses big-endian bytes regardless of TIFF byte order. */
        fun gainMap(map: LensShadingSnapshot, pattern: BayerPattern, active: RawCrop, sensorOriginY: Int = 0): ByteArray {
            val gains = map.gains.toFloatArray()
            require(map.rows > 0 && map.columns > 0 && active.width >= 2 && active.height >= 2)
            val points = Math.multiplyExact(map.rows, map.columns)
            require(gains.size == Math.multiplyExact(points, 4) && gains.all { it.isFinite() && it > 0f })
            val size = Math.addExact(76, Math.multiplyExact(points, 4))
            return ByteBuffer.allocate(Math.addExact(4, Math.multiplyExact(4, 16 + size)))
                .order(ByteOrder.BIG_ENDIAN).apply {
                    putInt(4)
                    for (cell in 0..3) {
                        val y = cell shr 1
                        val x = cell and 1
                        val color = pattern.colorAt(x + active.left, y + active.top)
                        // Camera2 green channels are even/odd SENSOR rows, before buffer cropping.
                        val channel = when (color) {
                            CfaColor.RED -> 0
                            CfaColor.BLUE -> 3
                            CfaColor.GREEN -> if ((y + active.top + sensorOriginY) and 1 == 0) 1 else 2
                        }
                        putInt(9).putInt(0x01030000).putInt(1).putInt(size)
                        putInt(y).putInt(x).putInt(active.height).putInt(active.width)
                        putInt(0).putInt(1).putInt(2).putInt(2)
                        putInt(map.rows).putInt(map.columns)
                        putDouble(1.0 / maxOf(1, map.rows - 1)).putDouble(1.0 / maxOf(1, map.columns - 1))
                        putDouble(0.0).putDouble(0.0).putInt(1)
                        for (point in 0 until points) putFloat(gains[point * 4 + channel])
                    }
                }.array()
        }
    }
}
