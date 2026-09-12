// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.res.AssetManager
import org.json.JSONObject
import org.json.JSONArray
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Test-only reader: no rendered fallback, inferred sensor timestamps, or automatic missing-map identity. */
internal data class RawSrFixture(val manifest: JSONObject, val frames: List<RawSrPackedFrame>,
                                 val referenceIndex: Int) {
    val referenceFirst get() = listOf(referenceIndex) + frames.indices.filter { it != referenceIndex }

    companion object {
        fun load(assets: AssetManager, directory: String = "rawsr/sea"): RawSrFixture {
            val json = JSONObject(assets.open("$directory/manifest.json").bufferedReader().use { it.readText() })
            return decode(json) { name -> assets.open("$directory/$name").use { it.readBytes() } }
        }

        fun decode(json: JSONObject, read: (String) -> ByteArray): RawSrFixture {
            require(json.getInt("schemaVersion") == 1 && json.getString("dataKind") == "real-camera-bayer")
            val license = json.getJSONObject("license")
            require(license.getString("spdx") in setOf("CC-BY-4.0", "CC-BY-SA-4.0")) {
                "Fixture data must be redistributable with attribution (CC-BY-4.0 or CC-BY-SA-4.0); " +
                    "user-supplied data without a confirmed license is rejected"
            }
            require(license.getString("attribution").isNotBlank() && license.getString("permission").isNotBlank())
            val array = json.getJSONArray("frames")
            require(array.length() in 2..30)
            val reference = json.getInt("referenceIndex")
            require(reference in 0 until array.length())
            var lastTimestamp = Long.MIN_VALUE
            val files = HashSet<String>()
            val frames = List(array.length()) { index ->
                val frame = array.getJSONObject(index)
                val name = frame.getString("file")
                require(Regex("frame-[0-9]{2}\\.raw16le\\.gzip").matches(name) && files.add(name))
                val device = frame.getJSONObject("device")
                require(device.getString("make").isNotBlank() && device.getString("model").isNotBlank())
                require(frame.getDouble("exposureSeconds").let { it.isFinite() && it > 0 })
                require(frame.getInt("iso") > 0)
                val timestamp = frame.getJSONObject("timestamp")
                val millis = timestamp.getLong("filenameMillis")
                require(millis > lastTimestamp); lastTimestamp = millis
                require(timestamp.has("sensorNanos") && timestamp.getString("provenance").isNotBlank())
                val width = frame.getInt("width"); val height = frame.getInt("height")
                val rowStride = frame.getInt("rowStride")
                val byteCount = frame.getInt("byteCount")
                require(width > 0 && height > 0 && byteCount in 1..8_388_608)
                require(rowStride.toLong() * height == byteCount.toLong())
                val packed = read(name)
                require(sha(packed) == frame.getString("compressedSha256")) { "Compressed checksum: $name" }
                val bytes = GZIPInputStream(packed.inputStream()).use { input ->
                    val output = ByteArray(byteCount)
                    var position = 0
                    while (position < output.size) {
                        val count = input.read(output, position, output.size - position)
                        require(count > 0) { "Truncated RAW plane: $name" }; position += count
                    }
                    require(input.read() == -1) { "Oversized RAW plane: $name" }
                    output
                }
                require(sha(bytes) == frame.getString("sha256")) { "RAW checksum: $name" }
                val origin = frame.getJSONArray("sensorOrigin")
                val crop = frame.getJSONArray("crop")
                require(origin.length() == 2 && crop.length() == 4)
                require(frame.getString("coordinateSystem") == "dng-active-array")
                val normalization = RawNormalization(BayerPattern.valueOf(frame.getString("cfaPattern")),
                    frame.getJSONArray("blackLevels").floats(4).toList(), frame.getDouble("whiteLevel").toFloat())
                val shading = frame.getJSONObject("lensShading")
                require(shading.getString("status") == "pending-dng-opcode-list2" && !shading.getBoolean("alreadyApplied"))
                val active = shading.getJSONArray("activeArray")
                val rows = shading.getInt("rows"); val columns = shading.getInt("columns")
                require(rows in 1..128 && columns in 1..128 && active.length() == 4)
                val lens = LensShadingModel(rows, columns, shading.getJSONArray("gains").floats(rows * columns * 4),
                    IntRectSnapshot(active.getInt(0), active.getInt(1), active.getInt(2), active.getInt(3)))
                val noise = frame.getJSONArray("noiseProfile")
                require(noise.length() == 6)
                val profile = DoubleArray(6) { noise.getDouble(it).also { n -> require(n.isFinite() && n >= 0) } }
                RawSrPackedFrame(ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put(bytes); flip()
                }, RawPlaneLayout(width, height, rowStride, frame.getInt("pixelStride"), origin.getInt(0), origin.getInt(1)),
                    RawCrop(crop.getInt(0), crop.getInt(1), crop.getInt(2), crop.getInt(3)),
                    normalization, lens, ImmutableDoubleValues(profile))
            }
            val first = frames.first()
            require(frames.all { it.width == first.width && it.height == first.height && it.pattern == first.pattern })
            return RawSrFixture(json, frames, reference)
        }

        private fun JSONArray.floats(count: Int): FloatArray {
            require(length() == count)
            return FloatArray(count) { getDouble(it).toFloat().also { n -> require(n.isFinite()) } }
        }
        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
