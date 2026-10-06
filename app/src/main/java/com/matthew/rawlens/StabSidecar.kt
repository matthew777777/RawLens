// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.util.Locale

/**
 * Post-record stabilization take sidecar: the full-take gyro log plus the
 * `frameIndex -> SENSOR_TIMESTAMP` table the warp pass needs to align
 * encoded frames (constant CFR grid PTS) with the gyro timeline (boot-time
 * ns). Written by [DirectLogRecorder.stop] next to the staged MP4, consumed
 * once by the warp pass, then deleted.
 *
 * Gyro rates are already in the desktop camera frame
 * ([GyroCameraFrameMapper], applied by [MotionRecorder] before chunking),
 * so the planner needs no axis knowledge — only timestamps and intrinsics.
 *
 * Pure JVM, no Android dependencies — unit-testable on the host. Rendered
 * canonically (fixed key order, US locale); parsed tolerantly (null on any
 * problem, never throws for malformed input).
 */
internal object StabSidecar {
    const val FORMAT = "rawlens-stab-sidecar"
    const val FORMAT_VERSION = 1

    /** File suffix for the staged sidecar (`<stem>_LOG.stab.json`). */
    const val FILE_SUFFIX = ".stab.json"

    /**
     * Take geometry for the warp planner. Intrinsics derive from the window
     * the encode was scaled from: [cropW]/[cropH] (sensor px) inside
     * [sensorW]/[sensorH], [focalMm] over [sensorWMm]/[sensorHMm] physical.
     */
    data class Header(
        val fps: Int,
        val encodeW: Int,
        val encodeH: Int,
        val timeOriginNs: Long,
        val sensorOrientationDeg: Int,
        val frontFacing: Boolean,
        val focalMm: Float,
        val sensorWMm: Float,
        val sensorHMm: Float,
        val sensorW: Int,
        val sensorH: Int,
        val cropLeft: Int,
        val cropTop: Int,
        val cropW: Int,
        val cropH: Int,
        val mapperVersion: Int,
    )

    data class Take(
        val header: Header,
        /** Sensor timestamp (boot-time ns) per encoded frame, submit order. */
        val frameTimestampsNs: LongArray,
        /** Camera-frame gyro samples, ascending timestamps. */
        val gyro: List<GyroSample>,
    )

    /** Renders [take] to canonical sidecar JSON. */
    fun render(take: Take): String {
        val h = take.header
        val sb = StringBuilder(512 + take.frameTimestampsNs.size * 20 + take.gyro.size * 40)
        sb.append("{\"format\":\"").append(FORMAT).append("\",")
        sb.append("\"formatVersion\":").append(FORMAT_VERSION).append(',')
        sb.append("\"fps\":").append(h.fps).append(',')
        sb.append("\"encodeW\":").append(h.encodeW).append(',')
        sb.append("\"encodeH\":").append(h.encodeH).append(',')
        sb.append("\"timeOriginNs\":").append(h.timeOriginNs).append(',')
        sb.append("\"sensorOrientationDeg\":").append(h.sensorOrientationDeg).append(',')
        sb.append("\"frontFacing\":").append(h.frontFacing).append(',')
        sb.append("\"focalMm\":").append(num(h.focalMm)).append(',')
        sb.append("\"sensorWMm\":").append(num(h.sensorWMm)).append(',')
        sb.append("\"sensorHMm\":").append(num(h.sensorHMm)).append(',')
        sb.append("\"sensorW\":").append(h.sensorW).append(',')
        sb.append("\"sensorH\":").append(h.sensorH).append(',')
        sb.append("\"cropLeft\":").append(h.cropLeft).append(',')
        sb.append("\"cropTop\":").append(h.cropTop).append(',')
        sb.append("\"cropW\":").append(h.cropW).append(',')
        sb.append("\"cropH\":").append(h.cropH).append(',')
        sb.append("\"mapperVersion\":").append(h.mapperVersion).append(',')
        sb.append("\"frames\":[")
        take.frameTimestampsNs.forEachIndexed { i, ts ->
            if (i > 0) sb.append(',')
            sb.append(ts)
        }
        sb.append("],\"gyro\":[")
        take.gyro.forEachIndexed { i, s ->
            if (i > 0) sb.append(',')
            sb.append('[').append(s.timestampNanos).append(',')
                .append(num(s.xRadiansPerSecond)).append(',')
                .append(num(s.yRadiansPerSecond)).append(',')
                .append(num(s.zRadiansPerSecond)).append(']')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun num(f: Float): String = "%.6f".format(Locale.US, f)

    /**
     * Parses sidecar JSON, or null when anything is missing, mistyped, or
     * implausible (wrong format/version, empty frame table, unsorted
     * timestamps, non-positive geometry). Never throws.
     */
    fun parse(text: String): Take? = try {
        parseOrThrow(text)
    } catch (_: Exception) {
        null
    }

    private fun parseOrThrow(text: String): Take? {
        val reader = JsonReader(text)
        val root = reader.readValue() as? Map<*, *> ?: return null
        reader.expectEnd()
        if (root["format"] as? String != FORMAT) return null
        if ((root["formatVersion"] as? Number)?.toInt() != FORMAT_VERSION) return null
        fun num(key: String): Double? = (root[key] as? Number)?.toDouble()
        fun lng(key: String): Long? = (root[key] as? Number)?.toLong()
        fun int(key: String): Int? = (root[key] as? Number)?.toInt()
        val fps = int("fps") ?: return null
        val encodeW = int("encodeW") ?: return null
        val encodeH = int("encodeH") ?: return null
        val origin = lng("timeOriginNs") ?: return null
        val orientation = int("sensorOrientationDeg") ?: return null
        val front = root["frontFacing"] as? Boolean ?: return null
        val focal = num("focalMm")?.toFloat() ?: return null
        val sensorWMm = num("sensorWMm")?.toFloat() ?: return null
        val sensorHMm = num("sensorHMm")?.toFloat() ?: return null
        val sensorW = int("sensorW") ?: return null
        val sensorH = int("sensorH") ?: return null
        val cropLeft = int("cropLeft") ?: return null
        val cropTop = int("cropTop") ?: return null
        val cropW = int("cropW") ?: return null
        val cropH = int("cropH") ?: return null
        val mapper = int("mapperVersion") ?: return null
        if (fps <= 0 || encodeW <= 0 || encodeH <= 0) return null
        if (sensorW <= 0 || sensorH <= 0 || cropW <= 0 || cropH <= 0) return null
        if (!focal.isFinite() || focal <= 0f) return null
        if (!sensorWMm.isFinite() || sensorWMm <= 0f) return null
        if (!sensorHMm.isFinite() || sensorHMm <= 0f) return null
        @Suppress("UNCHECKED_CAST")
        val frames = (root["frames"] as? List<Number>) ?: return null
        if (frames.isEmpty()) return null
        val frameTs = LongArray(frames.size) { frames[it].toLong() }
        for (i in 1 until frameTs.size) {
            if (frameTs[i] < frameTs[i - 1]) return null
        }
        @Suppress("UNCHECKED_CAST")
        val gyroRaw = (root["gyro"] as? List<List<Number>>) ?: return null
        if (gyroRaw.isEmpty()) return null
        val gyro = ArrayList<GyroSample>(gyroRaw.size)
        var prevTs = Long.MIN_VALUE
        for (row in gyroRaw) {
            if (row.size != 4) return null
            val ts = row[0].toLong()
            val x = row[1].toFloat()
            val y = row[2].toFloat()
            val z = row[3].toFloat()
            if (ts < prevTs) return null
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return null
            prevTs = ts
            gyro.add(GyroSample(ts, x, y, z))
        }
        val header = Header(
            fps, encodeW, encodeH, origin, orientation, front,
            focal, sensorWMm, sensorHMm, sensorW, sensorH,
            cropLeft, cropTop, cropW, cropH, mapper
        )
        return Take(header, frameTs, gyro)
    }

    /**
     * Minimal JSON reader for the sidecar subset (objects, arrays, numbers,
     * booleans, strings, null). `org.json` is unusable in JVM unit tests
     * (default-values stub), so the sidecar parses itself.
     */
    private class JsonReader(private val text: String) {
        private var pos = 0

        fun readValue(): Any? {
            skipWs()
            if (pos >= text.length) error("truncated")
            return when (val c = text[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else error("unexpected '$c'")
            }
        }

        private fun readObject(): Map<String, Any?> {
            pos++ // {
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') {
                pos++
                return out
            }
            while (true) {
                skipWs()
                if (peek() != '"') error("key must be a string")
                val key = readString()
                skipWs()
                if (peek() != ':') error("missing colon")
                pos++
                out[key] = readValue()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return out
                    }
                    else -> error("missing comma")
                }
            }
        }

        private fun readArray(): List<Any?> {
            pos++ // [
            val out = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') {
                pos++
                return out
            }
            while (true) {
                out.add(readValue())
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return out
                    }
                    else -> error("missing comma")
                }
            }
        }

        private fun readString(): String {
            pos++ // "
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) error("unterminated string")
                val c = text[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= text.length) error("truncated escape")
                        when (val e = text[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) error("truncated unicode")
                                sb.append(text.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> error("bad escape")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            if (!text.regionMatches(pos, word, 0, word.length)) error("bad literal")
            pos += word.length
            return value
        }

        private fun readNumber(): Number {
            val start = pos
            if (peek() == '-') pos++
            var isDouble = false
            while (pos < text.length) {
                val c = text[pos]
                if (c.isDigit()) {
                    pos++
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    isDouble = true
                    pos++
                } else break
            }
            val token = text.substring(start, pos)
            if (token.isEmpty() || token == "-") error("bad number")
            return if (isDouble) token.toDouble() else token.toLong()
        }

        private fun skipWs() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun peek(): Char {
            if (pos >= text.length) error("truncated")
            return text[pos]
        }

        /** Rejects trailing garbage after the top-level value. */
        fun expectEnd() {
            skipWs()
            if (pos != text.length) error("trailing data")
        }
    }
}
