// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest

/**
 * Per-lens vendor keys / vendor tags the user adds in settings.
 *
 * Step 1 of the bit-by-bit build: data model + per-lens store + discovery
 * helpers only. Nothing here touches a camera session or a request builder,
 * so adding this file changes no capture behavior.
 *
 * Scope mirrors Camera2 reality (see RAWR / PhotonCamera):
 * - [VendorKeyScope.SESSION] keys must be loaded into
 *   `SessionConfiguration.setSessionParameters()` *before* session creation.
 *   Vivo X300 Ultra DCG (`vivo.control.forceSensorMode`) only takes effect
 *   this way; setting it per-request does nothing.
 * - [VendorKeyScope.REQUEST] keys ride on every `CaptureRequest.Builder`
 *   (e.g. standard `android.sensor.blackLevelPattern` overrides).
 */
enum class VendorKeyType(val label: String) {
    INT("Int32"),
    LONG("Int64"),
    FLOAT("Float"),
    DOUBLE("Double"),
    BOOLEAN("Boolean"),
    BYTE("Byte"),
    INT_ARRAY("Int32[]"),
    LONG_ARRAY("Int64[]"),
    FLOAT_ARRAY("Float[]"),
    BYTE_ARRAY("Byte[]");

    /** Parse a user-typed value into the boxed/array object this key carries. */
    fun parse(raw: String): Any {
        val text = raw.trim()
        require(text.isNotEmpty()) { "Value must not be blank" }
        require(text.length <= MAX_VALUE_CHARS) { "Value too long" }
        return when (this) {
            INT -> text.toIntOrNull() ?: throw IllegalArgumentException("Not an Int32: $text")
            LONG -> text.toLongOrNull() ?: throw IllegalArgumentException("Not an Int64: $text")
            FLOAT -> text.toFloatOrNull()?.takeIf(Float::isFinite)
                ?: throw IllegalArgumentException("Not a finite Float: $text")
            DOUBLE -> text.toDoubleOrNull()?.takeIf(Double::isFinite)
                ?: throw IllegalArgumentException("Not a finite Double: $text")
            BOOLEAN -> when (text.lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("Not a Boolean (true/false): $text")
            }
            BYTE -> text.toIntOrNull()?.takeIf { it in Byte.MIN_VALUE..Byte.MAX_VALUE }?.toByte()
                ?: throw IllegalArgumentException("Not a Byte (-128..127): $text")
            INT_ARRAY -> parseIntArray(text).also { require(it.size in 1..MAX_ARRAY_ELEMENTS) { "Need 1..$MAX_ARRAY_ELEMENTS ints" } }
            LONG_ARRAY -> parseLongArray(text).also { require(it.size in 1..MAX_ARRAY_ELEMENTS) { "Need 1..$MAX_ARRAY_ELEMENTS longs" } }
            FLOAT_ARRAY -> parseFloatArray(text).also { require(it.size in 1..MAX_ARRAY_ELEMENTS) { "Need 1..$MAX_ARRAY_ELEMENTS floats" } }
            BYTE_ARRAY -> parseByteArray(text).also { require(it.size in 1..MAX_ARRAY_ELEMENTS) { "Need 1..$MAX_ARRAY_ELEMENTS bytes" } }
        }
    }

    companion object {
        const val MAX_VALUE_CHARS = 2048
        const val MAX_ARRAY_ELEMENTS = 64

        fun fromLabel(label: String?): VendorKeyType? =
            entries.firstOrNull { it.label.equals(label?.trim(), ignoreCase = true) }

        private fun splitCells(text: String): List<String> =
            text.split(',', ' ', ';').map(String::trim).filter(String::isNotEmpty)

        private fun parseIntArray(text: String): IntArray {
            val cells = splitCells(text)
            require(cells.isNotEmpty()) { "Empty array" }
            return IntArray(cells.size) { cells[it].toIntOrNull() ?: throw IllegalArgumentException("Not an int: ${cells[it]}") }
        }

        private fun parseLongArray(text: String): LongArray {
            val cells = splitCells(text)
            require(cells.isNotEmpty()) { "Empty array" }
            return LongArray(cells.size) { cells[it].toLongOrNull() ?: throw IllegalArgumentException("Not a long: ${cells[it]}") }
        }

        private fun parseFloatArray(text: String): FloatArray {
            val cells = splitCells(text)
            require(cells.isNotEmpty()) { "Empty array" }
            return FloatArray(cells.size) {
                cells[it].toFloatOrNull()?.takeIf(Float::isFinite)
                    ?: throw IllegalArgumentException("Not a finite float: ${cells[it]}")
            }
        }

        private fun parseByteArray(text: String): ByteArray {
            val cells = splitCells(text)
            require(cells.isNotEmpty()) { "Empty array" }
            return ByteArray(cells.size) {
                cells[it].toIntOrNull()?.takeIf { v -> v in 0..255 }?.toByte()
                    ?: throw IllegalArgumentException("Not a byte (0..255): ${cells[it]}")
            }
        }
    }
}

enum class VendorKeyScope(val id: String) {
    SESSION("session"),
    REQUEST("request");

    companion object {
        fun fromId(id: String?): VendorKeyScope? =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

/** One user-added vendor key. `value` stays a string so settings UI edits text. */
data class VendorKeyEntry(
    val name: String,
    val type: VendorKeyType,
    val scope: VendorKeyScope,
    val value: String,
    val enabled: Boolean = true
) {
    fun validate() {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Key name must not be blank" }
        require(clean.length <= MAX_NAME_CHARS) { "Key name too long" }
        require(' ' !in clean && '\t' !in clean && '@' !in clean) { "Key name must not contain spaces or @" }
        require('.' in clean) { "Key name must be reverse-domain (section.key)" }
        require(NAME_CHARS.matches(clean)) { "Key name uses unsupported characters: $clean" }
        type.parse(value)
    }

    /** Boxed/array payload for `CaptureRequest.Key` set calls (Step 2 consumes this). */
    fun parsedValue(): Any = type.parse(value)

    fun toJson(): String = buildString {
        append("{\"n\":\"").append(VendorKeyJson.esc(name.trim())).append('"')
        append(",\"t\":\"").append(VendorKeyJson.esc(type.label)).append('"')
        append(",\"s\":\"").append(VendorKeyJson.esc(scope.id)).append('"')
        append(",\"v\":\"").append(VendorKeyJson.esc(value)).append('"')
        append(",\"e\":").append(enabled).append('}')
    }

    companion object {
        const val MAX_NAME_CHARS = 128
        private val NAME_CHARS = Regex("[A-Za-z0-9._-]+")

        fun isPlausibleName(name: String): Boolean = runCatching {
            VendorKeyEntry(name, VendorKeyType.INT, VendorKeyScope.REQUEST, "0").validate()
        }.isSuccess

        fun fromJson(obj: Map<String, Any?>): VendorKeyEntry {
            val type = VendorKeyType.fromLabel(obj["t"] as? String)
                ?: throw IllegalArgumentException("Unknown vendor key type: ${obj["t"]}")
            val scope = VendorKeyScope.fromId(obj["s"] as? String)
                ?: throw IllegalArgumentException("Unknown vendor key scope: ${obj["s"]}")
            return VendorKeyEntry(
                name = obj["n"] as? String ?: throw IllegalArgumentException("Vendor key needs a name"),
                type = type,
                scope = scope,
                value = obj["v"] as? String ?: throw IllegalArgumentException("Vendor key needs a value"),
                enabled = (obj["e"] as? Boolean) ?: true
            ).also(VendorKeyEntry::validate)
        }
    }
}

/** One-line settings-row label, e.g. `vivo.control.forceSensorMode • Int32 • session • 1`. */
fun VendorKeyEntry.summary(): String = buildString {
    append(name.trim())
    append(" • ").append(type.label)
    append(" • ").append(scope.id)
    append(" • ").append(value.trim().ifEmpty { "—" })
    if (!enabled) append(" • OFF")
}

/** All vendor keys for one lens (camera route id, e.g. `0` or `3/2`). */
data class VendorKeyProfile(val entries: List<VendorKeyEntry> = emptyList()) {
    fun isEmpty(): Boolean = entries.isEmpty()

    fun activeEntries(): List<VendorKeyEntry> = entries.filter { it.enabled }

    fun sessionEntries(): List<VendorKeyEntry> = activeEntries().filter { it.scope == VendorKeyScope.SESSION }

    fun requestEntries(): List<VendorKeyEntry> = activeEntries().filter { it.scope == VendorKeyScope.REQUEST }

    fun validate() {
        entries.forEach(VendorKeyEntry::validate)
        val names = entries.map { it.name.trim() }
        require(names.size == names.toSet().size) { "Duplicate vendor key name" }
        require(entries.size <= MAX_ENTRIES) { "Too many vendor keys (max $MAX_ENTRIES)" }
    }

    fun toJson(): String = buildString {
        append("{\"v\":").append(FORMAT_VERSION).append(",\"entries\":[")
        entries.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            append(entry.toJson())
        }
        append("]}")
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val MAX_ENTRIES = 32

        fun fromJson(json: String?): VendorKeyProfile {
            if (json.isNullOrBlank()) return VendorKeyProfile()
            val root = VendorKeyJson.parseObject(json)
            val array = root["entries"] as? List<*>
                ?: throw IllegalArgumentException("Vendor key profile needs an entries array")
            val kept = mutableListOf<VendorKeyEntry>()
            for (row in array) {
                // Tolerant load: drop single bad rows (hand edits, OEM renames),
                // keep every valid key. Strict validation still applies on save.
                @Suppress("UNCHECKED_CAST")
                val obj = row as? Map<String, Any?> ?: continue
                runCatching { VendorKeyEntry.fromJson(obj) }.onSuccess { kept += it }
            }
            return VendorKeyProfile(kept)
        }

        /**
         * Disabled-by-default templates shown when a lens has no keys yet.
         * Enabling them is always explicit: nothing is sent until the user
         * toggles a key on (Step 2 reads only [VendorKeyProfile.activeEntries]).
         *
         * Black/white levels are deliberately NOT here: SDK-verified, no
         * `CaptureRequest` keys exist for them, so they stay on the per-lens
         * DNG calibration editor. See [VendorKeyApply.isLevelTag].
         */
        fun presets(): VendorKeyProfile = VendorKeyProfile(
            listOf(
                // RAWR-proven Vivo X300 Ultra DCG switch: session-early only.
                VendorKeyEntry(
                    name = "vivo.control.forceSensorMode",
                    type = VendorKeyType.INT,
                    scope = VendorKeyScope.SESSION,
                    value = "1",
                    enabled = false
                )
            )
        )
    }
}

/**
 * Minimal JSON codec for the fixed vendor-key shape. Pure Kotlin (no Android
 * calls) so host unit tests exercise the real round trip; mirrors the
 * escaping rules in [SettingsBackup].
 */
internal object VendorKeyJson {
    fun esc(s: String): String = buildString(s.length + 8) {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }

    fun parseObject(json: String): Map<String, Any?> {
        val parsed = Parser(json).parse()
            ?: throw IllegalArgumentException("Vendor key profile must be a JSON object")
        @Suppress("UNCHECKED_CAST")
        return parsed as? Map<String, Any?>
            ?: throw IllegalArgumentException("Vendor key profile must be a JSON object")
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun parse(): Any? {
            val value = parseValue(0)
            skipWs()
            if (pos != text.length) fail("trailing characters")
            return value
        }

        private fun parseValue(depth: Int): Any? {
            if (depth > 16) fail("nesting too deep")
            skipWs()
            if (pos >= text.length) fail("unexpected end")
            return when (val c = text[pos]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> fail("unexpected '$c'")
            }
        }

        private fun parseObject(depth: Int): Map<String, Any?> {
            pos++ // {
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') {
                pos++
                return out
            }
            while (true) {
                skipWs()
                if (peek() != '"') fail("object keys must be strings")
                val key = parseString()
                skipWs()
                if (peek() != ':') fail("expected ':'")
                pos++
                out[key] = parseValue(depth)
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return out
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun parseArray(depth: Int): List<Any?> {
            pos++ // [
            val out = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') {
                pos++
                return out
            }
            while (true) {
                out.add(parseValue(depth))
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return out
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) fail("unterminated string")
                val c = text[pos++]
                if (c == '"') return sb.toString()
                if (c != '\\') {
                    sb.append(c)
                    continue
                }
                if (pos >= text.length) fail("unterminated escape")
                when (val e = text[pos++]) {
                    '"', '\\', '/' -> sb.append(e)
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'u' -> {
                        if (pos + 4 > text.length) fail("bad unicode escape")
                        val hex = text.substring(pos, pos + 4)
                        pos += 4
                        sb.append(hex.toIntOrNull(16)?.toChar() ?: fail("bad unicode escape"))
                    }
                    else -> fail("bad escape '\\$e'")
                }
            }
        }

        private fun parseNumber(): Number {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            val raw = text.substring(start, pos)
            raw.toLongOrNull()?.let { return it }
            return raw.toDoubleOrNull() ?: fail("bad number $raw")
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) fail("expected $word")
            pos += word.length
            return value
        }

        private fun skipWs() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun peek(): Char = if (pos < text.length) text[pos] else 0.toChar()

        private fun fail(message: String): Nothing =
            throw IllegalArgumentException("Invalid vendor key JSON: $message")
    }
}

/** Per-lens vendor-key storage. Mirrors [DngMetadataOverrideStore] keying. */
class VendorKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(cameraId: String?): VendorKeyProfile = VendorKeyProfile.fromJson(
        cameraId?.let { preferences.getString(key(it), null) }
    )

    fun hasProfile(cameraId: String?): Boolean =
        cameraId != null && preferences.contains(key(cameraId))

    fun save(cameraId: String, profile: VendorKeyProfile) {
        profile.validate()
        preferences.edit().putString(key(cameraId), profile.toJson()).apply()
    }

    fun clear(cameraId: String) = preferences.edit().remove(key(cameraId)).apply()

    private fun key(cameraId: String) = "profile_$cameraId"

    companion object {
        const val PREFS_NAME = "rawlens_vendor_keys"
    }
}

/** One key discovered on-device, with a hint for which scope accepts it. */
data class DiscoveredVendorKey(
    val name: String,
    val scopeHint: VendorKeyScope,
    val source: String
)

/**
 * Android reflection/discovery helpers, line-by-line after PhotonVidCam:
 * - `VendorTagUtils.isSupported(builder, key)` probes `builder.get(key)` and
 *   treats `IllegalArgumentException` as "not on this camera".
 * - `SettingsActivity.getCharacteristicsValue` enumerates
 *   `characteristics.getKeys()` by name, then falls back to constructing a
 *   hidden `CameraCharacteristics.Key` via its `(String, Class)` constructor.
 */
object VendorKeyAndroid {
    fun characteristicKeyNames(characteristics: CameraCharacteristics): List<String> =
        runCatching { characteristics.keys.map { it.name }.sorted() }.getOrDefault(emptyList())

    fun availableRequestKeyNames(characteristics: CameraCharacteristics): List<String> =
        runCatching { characteristics.availableCaptureRequestKeys.map { it.name }.sorted() }
            .getOrDefault(emptyList())

    fun availableSessionKeyNames(characteristics: CameraCharacteristics): List<String> =
        runCatching { characteristics.availableSessionKeys.map { it.name }.sorted() }
            .getOrDefault(emptyList())

    fun availablePhysicalKeyNames(characteristics: CameraCharacteristics): List<String> =
        runCatching { characteristics.availablePhysicalCameraRequestKeys.map { it.name }.sorted() }
            .getOrDefault(emptyList())

    /** Enumerate everything this lens advertises, tagged with the scope that accepts it. */
    fun discover(characteristics: CameraCharacteristics): List<DiscoveredVendorKey> {
        val session = availableSessionKeyNames(characteristics).toSet()
        val request = availableRequestKeyNames(characteristics).toSet()
        val found = linkedMapOf<String, DiscoveredVendorKey>()
        characteristics.keys.forEach { key ->
            found[key.name] = DiscoveredVendorKey(key.name, VendorKeyScope.REQUEST, "characteristics")
        }
        request.forEach { name ->
            found.putIfAbsent(name, DiscoveredVendorKey(name, VendorKeyScope.REQUEST, "request"))
        }
        session.forEach { name ->
            found[name] = DiscoveredVendorKey(name, VendorKeyScope.SESSION, "session")
        }
        return found.values.sortedBy { it.name }
    }

    /** PhotonVidCam `VendorTagUtils.isSupported`: probe-only, never throws. */
    fun isSupported(builder: CaptureRequest.Builder, key: CaptureRequest.Key<*>): Boolean =
        try {
            builder.get(key)
            true
        } catch (_: IllegalArgumentException) {
            false
        }

    /** Typed `CaptureRequest.Key` construction; null when the HAL rejects the name. */
    fun <T> requestKeyOf(name: String, clazz: Class<T>): CaptureRequest.Key<T>? =
        try {
            CaptureRequest.Key(name, clazz)
        } catch (_: Exception) {
            null
        }

    /** Hidden characteristics keys via the `(String, Class)` constructor, as in PhotonVidCam. */
    @Suppress("UNCHECKED_CAST")
    fun hiddenCharacteristicsKey(name: String, clazz: Class<*>): CameraCharacteristics.Key<*>? =
        try {
            val ctor = CameraCharacteristics.Key::class.java.getDeclaredConstructor(String::class.java, Class::class.java)
            ctor.isAccessible = true
            ctor.newInstance(name, clazz) as CameraCharacteristics.Key<*>
        } catch (_: Exception) {
            null
        }
}
