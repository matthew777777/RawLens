// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.net.Uri
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.roundToLong

/** Per-physical-camera DNG metadata replacements. Null values preserve the device declaration. */
data class DngMetadataOverrides(
    val blackLevels: List<Double>? = null,
    val whiteLevel: Double? = null,
    val colorMatrix1: List<Double>? = null,
    val colorMatrix2: List<Double>? = null,
    val cameraCalibration1: List<Double>? = null,
    val cameraCalibration2: List<Double>? = null,
    val forwardMatrix1: List<Double>? = null,
    val forwardMatrix2: List<Double>? = null,
    val noiseProfile: List<Double>? = null
) {
    fun isEmpty(): Boolean = listOf(
        blackLevels, whiteLevel?.let(::listOf), colorMatrix1, colorMatrix2,
        cameraCalibration1, cameraCalibration2, forwardMatrix1, forwardMatrix2, noiseProfile
    ).all { it == null }

    fun validate() {
        require(blackLevels == null || blackLevels.size == 4) { "Black levels require RGGB (4 values)" }
        require(whiteLevel == null || whiteLevel > 0.0) { "White level must be positive" }
        listOf(colorMatrix1, colorMatrix2, cameraCalibration1, cameraCalibration2, forwardMatrix1, forwardMatrix2)
            .forEach { require(it == null || it.size == 9) { "Color matrices require 9 values" } }
        require(noiseProfile == null || noiseProfile.size in listOf(6, 8)) {
            "Noise profile requires 6 or 8 values (scale/offset pairs)"
        }
        allValues().forEach { require(it.isFinite()) { "Metadata values must be finite" } }
    }

    fun toJson(): String = JSONObject().apply {
        putArray("blackLevels", blackLevels); put("whiteLevel", whiteLevel)
        putArray("colorMatrix1", colorMatrix1); putArray("colorMatrix2", colorMatrix2)
        putArray("cameraCalibration1", cameraCalibration1); putArray("cameraCalibration2", cameraCalibration2)
        putArray("forwardMatrix1", forwardMatrix1); putArray("forwardMatrix2", forwardMatrix2)
        putArray("noiseProfile", noiseProfile)
    }.toString()

    private fun allValues(): List<Double> = buildList {
        listOf(blackLevels, colorMatrix1, colorMatrix2, cameraCalibration1, cameraCalibration2,
            forwardMatrix1, forwardMatrix2, noiseProfile).forEach { it?.let(::addAll) }
        whiteLevel?.let(::add)
    }

    companion object {
        fun fromJson(json: String?): DngMetadataOverrides {
            if (json.isNullOrBlank()) return DngMetadataOverrides()
            val objectJson = JSONObject(json)
            return DngMetadataOverrides(
                array(objectJson, "blackLevels"), objectJson.optDoubleOrNull("whiteLevel"),
                array(objectJson, "colorMatrix1"), array(objectJson, "colorMatrix2"),
                array(objectJson, "cameraCalibration1"), array(objectJson, "cameraCalibration2"),
                array(objectJson, "forwardMatrix1"), array(objectJson, "forwardMatrix2"),
                array(objectJson, "noiseProfile")
            ).also(DngMetadataOverrides::validate)
        }

        private fun array(source: JSONObject, key: String): List<Double>? = source.optJSONArray(key)?.let { values ->
            List(values.length()) { values.getDouble(it) }
        }

        private fun JSONObject.optDoubleOrNull(key: String): Double? =
            if (has(key) && !isNull(key)) getDouble(key) else null

        private fun JSONObject.putArray(key: String, values: List<Double>?) {
            if (values != null) put(key, JSONArray(values))
        }
    }
}

/** Values advertised by the currently selected camera, used to make override choices explicit. */
data class DngMetadataDefaults(
    val blackLevels: List<Double>? = null,
    val whiteLevel: Double? = null,
    val colorMatrix1: List<Double>? = null,
    val colorMatrix2: List<Double>? = null,
    val cameraCalibration1: List<Double>? = null,
    val cameraCalibration2: List<Double>? = null,
    val forwardMatrix1: List<Double>? = null,
    val forwardMatrix2: List<Double>? = null,
    val noiseProfile: List<Double>? = null
)

class DngMetadataOverrideStore(context: Context) {
    private val preferences = context.getSharedPreferences("rawlens_dng_metadata", Context.MODE_PRIVATE)

    fun get(cameraId: String?): DngMetadataOverrides = DngMetadataOverrides.fromJson(
        cameraId?.let { preferences.getString("profile_$it", null) }
    )

    fun save(cameraId: String, overrides: DngMetadataOverrides) {
        overrides.validate()
        preferences.edit().putString("profile_$cameraId", overrides.toJson()).apply()
    }

    fun clear(cameraId: String) = preferences.edit().remove("profile_$cameraId").apply()
}

/** Patches existing, device-declared DNG TIFF tags without touching the RAW image payload. */
object DngMetadataPatcher {
    private const val TAG_SUB_IFDS = 330
    private const val TAG_BLACK_LEVEL = 50714
    private const val TAG_WHITE_LEVEL = 50717
    private const val TAG_COLOR_MATRIX_1 = 50721
    private const val TAG_COLOR_MATRIX_2 = 50722
    private const val TAG_CAMERA_CALIBRATION_1 = 50723
    private const val TAG_CAMERA_CALIBRATION_2 = 50724
    private const val TAG_FORWARD_MATRIX_1 = 50964
    private const val TAG_FORWARD_MATRIX_2 = 50965
    private const val TAG_NOISE_PROFILE = 51041

    fun apply(context: Context, uri: Uri, overrides: DngMetadataOverrides) {
        if (overrides.isEmpty()) return
        overrides.validate()
        context.contentResolver.openFileDescriptor(uri, "rw")!!.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                patch(mapped, overrides).forEach { write ->
                    var copied = 0
                    while (copied < write.bytes.size) {
                        val written = Os.pwrite(
                            descriptor.fileDescriptor, write.bytes, copied,
                            write.bytes.size - copied, write.offset + copied
                        )
                        require(written > 0) { "Could not write DNG metadata" }
                        copied += written
                    }
                }
            }
        }
    }

    private fun patch(buffer: ByteBuffer, overrides: DngMetadataOverrides): List<PendingWrite> {
        currentBuffer = buffer
        require(buffer.limit() >= 8) { "Invalid DNG: TIFF header is truncated" }
        val byteOrder = when ("${buffer.get(0).toInt().toChar()}${buffer.get(1).toInt().toChar()}") {
            "II" -> ByteOrder.LITTLE_ENDIAN
            "MM" -> ByteOrder.BIG_ENDIAN
            else -> error("Invalid DNG: unsupported TIFF byte order")
        }
        buffer.order(byteOrder)
        require(u16(buffer, 2) == 42) { "Unsupported DNG: not classic TIFF" }
        val entries = linkedMapOf<Int, TiffEntry>()
        collectIfd(buffer, u32(buffer, 4), entries, mutableSetOf())
        val replacements = listOfNotNull(
            overrides.blackLevels?.let { TAG_BLACK_LEVEL to it },
            overrides.whiteLevel?.let { TAG_WHITE_LEVEL to listOf(it) },
            overrides.colorMatrix1?.let { TAG_COLOR_MATRIX_1 to it },
            overrides.colorMatrix2?.let { TAG_COLOR_MATRIX_2 to it },
            overrides.cameraCalibration1?.let { TAG_CAMERA_CALIBRATION_1 to it },
            overrides.cameraCalibration2?.let { TAG_CAMERA_CALIBRATION_2 to it },
            overrides.forwardMatrix1?.let { TAG_FORWARD_MATRIX_1 to it },
            overrides.forwardMatrix2?.let { TAG_FORWARD_MATRIX_2 to it },
            overrides.noiseProfile?.let { TAG_NOISE_PROFILE to it }
        )
        replacements.forEach { (tag, values) -> validateWritable(entries[tag], tag, values, buffer.limit()) }
        return replacements.map { (tag, values) ->
            val entry = entries.getValue(tag)
            PendingWrite(entry.dataOffset(), encode(buffer.order(), entry, values))
        }
    }

    private fun collectIfd(
        buffer: ByteBuffer,
        offset: Long,
        entries: MutableMap<Int, TiffEntry>,
        visited: MutableSet<Long>
    ) {
        if (offset == 0L || !visited.add(offset) || offset > buffer.limit() - 2) return
        val base = offset.toInt()
        val count = u16(buffer, base)
        require(base + 2L + count * 12L + 4L <= buffer.limit()) { "Invalid DNG IFD" }
        repeat(count) { index ->
            val position = base + 2 + index * 12
            val entry = TiffEntry(u16(buffer, position), u16(buffer, position + 2), u32(buffer, position + 4), position)
            entries.putIfAbsent(entry.tag, entry)
            if (entry.tag == TAG_SUB_IFDS) {
                entry.offsets(buffer).forEach { collectIfd(buffer, it, entries, visited) }
            }
        }
        collectIfd(buffer, u32(buffer, base + 2 + count * 12), entries, visited)
    }

    private fun validateWritable(entry: TiffEntry?, tag: Int, values: List<Double>, limit: Int) {
        require(entry != null) { "Device DNG does not declare ${tagName(tag)}" }
        require(entry.count == values.size.toLong()) {
            "${tagName(tag)} declares ${entry.count} values; override supplies ${values.size}"
        }
        require(entry.type in setOf(3, 4, 5, 8, 9, 10, 11, 12)) {
            "${tagName(tag)} uses unsupported TIFF type ${entry.type}"
        }
        require(entry.dataOffset() + entry.count * typeSize(entry.type) <= limit) { "Invalid ${tagName(tag)} offset" }
    }

    private fun encode(byteOrder: ByteOrder, entry: TiffEntry, values: List<Double>): ByteArray {
        val encoded = ByteBuffer.allocate(entry.count.toInt() * typeSize(entry.type)).order(byteOrder)
        values.forEachIndexed { index, value ->
            val position = index * typeSize(entry.type)
            when (entry.type) {
                3 -> encoded.putShort(position, value.roundToLong().coerceIn(0, 0xffff).toShort())
                4 -> encoded.putInt(position, value.roundToLong().coerceIn(0, 0xffffffffL).toInt())
                5 -> putRational(encoded, position, value, signed = false)
                8 -> encoded.putShort(position, value.roundToLong().coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort())
                9 -> encoded.putInt(position, value.roundToLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
                10 -> putRational(encoded, position, value, signed = true)
                11 -> encoded.putFloat(position, value.toFloat())
                12 -> encoded.putDouble(position, value)
            }
        }
        return encoded.array()
    }

    private fun putRational(buffer: ByteBuffer, position: Int, value: Double, signed: Boolean) {
        val denominator = if (abs(value) < 65_000.0) 1_000_000L else 1L
        val numerator = (value * denominator).roundToLong()
        if (signed) {
            buffer.putInt(position, numerator.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
            buffer.putInt(position + 4, denominator.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        } else {
            require(value >= 0.0) { "Unsigned DNG value cannot be negative" }
            buffer.putInt(position, numerator.coerceIn(0, 0xffffffffL).toInt())
            buffer.putInt(position + 4, denominator.coerceIn(1, 0xffffffffL).toInt())
        }
    }

    private data class TiffEntry(val tag: Int, val type: Int, val count: Long, val entryOffset: Int) {
        fun dataOffset(): Long = if (count * typeSize(type) <= 4) (entryOffset + 8).toLong() else currentBuffer.getInt(entryOffset + 8).toLong() and 0xffffffffL
        fun offsets(buffer: ByteBuffer): List<Long> {
            currentBuffer = buffer
            return List(count.toInt()) { index ->
                val at = dataOffset().toInt() + index * typeSize(type)
                when (type) { 3 -> u16(buffer, at).toLong(); else -> u32(buffer, at) }
            }
        }
    }

    private data class PendingWrite(val offset: Long, val bytes: ByteArray)

    // TiffEntry needs the active buffer only for its compact data-offset helper.
    private lateinit var currentBuffer: ByteBuffer
    private fun u16(buffer: ByteBuffer, offset: Int): Int = buffer.getShort(offset).toInt() and 0xffff
    private fun u32(buffer: ByteBuffer, offset: Int): Long = buffer.getInt(offset).toLong() and 0xffffffffL
    private fun typeSize(type: Int): Int = when (type) {
        1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9, 11 -> 4; 5, 10, 12 -> 8
        else -> error("Unknown TIFF type $type")
    }
    private fun tagName(tag: Int): String = when (tag) {
        TAG_BLACK_LEVEL -> "BlackLevel"; TAG_WHITE_LEVEL -> "WhiteLevel"; TAG_COLOR_MATRIX_1 -> "ColorMatrix1"
        TAG_COLOR_MATRIX_2 -> "ColorMatrix2"; TAG_CAMERA_CALIBRATION_1 -> "CameraCalibration1"
        TAG_CAMERA_CALIBRATION_2 -> "CameraCalibration2"; TAG_FORWARD_MATRIX_1 -> "ForwardMatrix1"
        TAG_FORWARD_MATRIX_2 -> "ForwardMatrix2"; TAG_NOISE_PROFILE -> "NoiseProfile"; else -> "tag $tag"
    }
}
