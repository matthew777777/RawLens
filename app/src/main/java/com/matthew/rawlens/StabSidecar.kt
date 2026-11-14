// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.util.Locale

/**
 * Post-record stabilization take sidecar: the full-take gyro log plus the
 * `frameIndex -> SENSOR_TIMESTAMP` table the warp pass needs to align
 * encoded frames (constant CFR grid PTS) with the gyro timeline (boot-time
 * ns). Written by [EncodedVideoRecorder.stop] next to the staged MP4, consumed
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
    /** v2 adds [Extras] (row timing, lens calibration, OIS, accel); v1 still parses. */
    const val FORMAT_VERSION = 2
    private val ACCEPTED_VERSIONS = setOf(1, 2)

    /** File suffix for the staged sidecar (`<stem>_LOG709.stab.json`). */
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
        /** v2 stabilization metadata; null for v1 takes (planner degrades). */
        val extras: Extras? = null,
    )

    /** One OIS lens-shift sample (Camera2 OisSample, sensor pixels). */
    data class OisSample(val timestampNs: Long, val xShift: Float, val yShift: Float)

    /**
     * v2 metadata the rolling-shutter + lens planner ([StabPlanner]) needs.
     * Every field is optional: a missing one is null/empty and the planner
     * falls back (no skew → global shutter, no distortion → pinhole, ...).
     *
     * Rect conventions follow Camera2: [lensIntrinsics] / [lensDistortion]
     * are in the pre-correction active array pixel frame
     * ([preCorrLeft]..), [activeLeft].. is the active array inside the
     * pixel array, and the RAW crop in [Header] is relative to RAW buffer
     * pixels (the active array for RAW_SENSOR outputs).
     *
     * Per-frame arrays are aligned to [Take.frameTimestampsNs] (same length)
     * or empty when not captured; a value of -1 marks one missing frame.
     */
    class Extras(
        /** f_x, f_y, c_x, c_y, s (LENS_INTRINSIC_CALIBRATION), or null. */
        val lensIntrinsics: FloatArray? = null,
        /** k1, k2, k3, p1, p2 (LENS_DISTORTION), or null. */
        val lensDistortion: FloatArray? = null,
        val preCorrLeft: Int = 0,
        val preCorrTop: Int = 0,
        val preCorrW: Int = 0,
        val preCorrH: Int = 0,
        val activeLeft: Int = 0,
        val activeTop: Int = 0,
        val activeW: Int = 0,
        val activeH: Int = 0,
        /** SENSOR_EXPOSURE_TIME per frame. */
        val exposureNs: LongArray = LongArray(0),
        /** SENSOR_ROLLING_SHUTTER_SKEW per frame (first to last row). */
        val rollingShutterSkewNs: LongArray = LongArray(0),
        /** Per-frame LENS_INTRINSIC_CALIBRATION (`frames * 5`; NaN = missing). */
        val frameIntrinsics: FloatArray = FloatArray(0),
        /** STATISTICS_OIS_SAMPLES, ascending timestamps. */
        val ois: List<OisSample> = emptyList(),
        /** Accelerometer, RAW DEVICE axes (m/s²), ascending timestamps. */
        val accel: List<GyroSample> = emptyList(),
    )

    /** Renders [take] to canonical sidecar JSON. */
    fun render(take: Take): String {
        val h = take.header
        val sb = StringBuilder(512 + take.frameTimestampsNs.size * 20 + take.gyro.size * 40)
        sb.append("{\"format\":\"").append(FORMAT).append("\",")
        sb.append("\"formatVersion\":").append(if (take.extras == null) 1 else FORMAT_VERSION).append(',')
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
        sb.append(']')
        take.extras?.let { renderExtras(sb, it) }
        sb.append('}')
        return sb.toString()
    }

    private fun renderExtras(sb: StringBuilder, e: Extras) {
        fun floats(key: String, a: FloatArray?) {
            if (a == null || a.isEmpty()) return
            sb.append(",\"").append(key).append("\":[")
            a.forEachIndexed { i, f ->
                if (i > 0) sb.append(',')
                if (f.isFinite()) sb.append(num(f)) else sb.append("null")
            }
            sb.append(']')
        }
        fun longs(key: String, a: LongArray) {
            if (a.isEmpty()) return
            sb.append(",\"").append(key).append("\":[")
            a.forEachIndexed { i, v -> if (i > 0) sb.append(','); sb.append(v) }
            sb.append(']')
        }
        floats("lensIntrinsics", e.lensIntrinsics)
        floats("lensDistortion", e.lensDistortion)
        sb.append(",\"preCorr\":[").append(e.preCorrLeft).append(',').append(e.preCorrTop).append(',')
            .append(e.preCorrW).append(',').append(e.preCorrH).append(']')
        sb.append(",\"active\":[").append(e.activeLeft).append(',').append(e.activeTop).append(',')
            .append(e.activeW).append(',').append(e.activeH).append(']')
        longs("exposureNs", e.exposureNs)
        longs("rsSkewNs", e.rollingShutterSkewNs)
        floats("frameIntrinsics", e.frameIntrinsics)
        if (e.ois.isNotEmpty()) {
            sb.append(",\"ois\":[")
            e.ois.forEachIndexed { i, o ->
                if (i > 0) sb.append(',')
                sb.append('[').append(o.timestampNs).append(',').append(num(o.xShift)).append(',')
                    .append(num(o.yShift)).append(']')
            }
            sb.append(']')
        }
        if (e.accel.isNotEmpty()) {
            sb.append(",\"accel\":[")
            e.accel.forEachIndexed { i, a ->
                if (i > 0) sb.append(',')
                sb.append('[').append(a.timestampNanos).append(',').append(num(a.xRadiansPerSecond)).append(',')
                    .append(num(a.yRadiansPerSecond)).append(',').append(num(a.zRadiansPerSecond)).append(']')
            }
            sb.append(']')
        }
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
        val version = (root["formatVersion"] as? Number)?.toInt() ?: return null
        if (version !in ACCEPTED_VERSIONS) return null
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
        val extras = if (version >= 2) parseExtras(root, frameTs.size) ?: return null else null
        return Take(header, frameTs, gyro, extras)
    }

    /** v2 extras; null only when a present field is malformed (absent = default). */
    @Suppress("UNCHECKED_CAST")
    private fun parseExtras(root: Map<*, *>, frames: Int): Extras? {
        fun floats(key: String, size: Int?): FloatArray? {
            val raw = root[key] ?: return FloatArray(0)
            val list = raw as? List<Any?> ?: return null
            if (size != null && list.size != size) return null
            return FloatArray(list.size) { (list[it] as? Number)?.toFloat() ?: Float.NaN }
        }
        fun longs(key: String): LongArray? {
            val raw = root[key] ?: return LongArray(0)
            val list = raw as? List<Any?> ?: return null
            if (list.size != frames) return null
            return LongArray(list.size) { (list[it] as? Number)?.toLong() ?: -1L }
        }
        fun rect(key: String): IntArray? {
            val raw = root[key] ?: return IntArray(4)
            val list = raw as? List<Number> ?: return null
            if (list.size != 4) return null
            return IntArray(4) { list[it].toInt() }
        }
        val intr = floats("lensIntrinsics", null) ?: return null
        val dist = floats("lensDistortion", null) ?: return null
        if (intr.isNotEmpty() && intr.size != 5) return null
        if (dist.isNotEmpty() && dist.size != 5) return null
        val pre = rect("preCorr") ?: return null
        val act = rect("active") ?: return null
        val exp = longs("exposureNs") ?: return null
        val skew = longs("rsSkewNs") ?: return null
        val fi = floats("frameIntrinsics", null) ?: return null
        if (fi.isNotEmpty() && fi.size != frames * 5) return null
        fun samples(key: String, width: Int): List<List<Number>>? {
            val raw = root[key] ?: return emptyList()
            val list = raw as? List<List<Number>> ?: return null
            var prev = Long.MIN_VALUE
            for (row in list) {
                if (row.size != width) return null
                val ts = row[0].toLong()
                if (ts < prev) return null
                prev = ts
            }
            return list
        }
        val ois = (samples("ois", 3) ?: return null).map {
            OisSample(it[0].toLong(), it[1].toFloat(), it[2].toFloat())
        }
        val accel = (samples("accel", 4) ?: return null).map {
            GyroSample(it[0].toLong(), it[1].toFloat(), it[2].toFloat(), it[3].toFloat())
        }
        return Extras(
            lensIntrinsics = intr.takeIf { it.isNotEmpty() },
            lensDistortion = dist.takeIf { it.isNotEmpty() },
            preCorrLeft = pre[0], preCorrTop = pre[1], preCorrW = pre[2], preCorrH = pre[3],
            activeLeft = act[0], activeTop = act[1], activeW = act[2], activeH = act[3],
            exposureNs = exp, rollingShutterSkewNs = skew, frameIntrinsics = fi,
            ois = ois, accel = accel,
        )
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
