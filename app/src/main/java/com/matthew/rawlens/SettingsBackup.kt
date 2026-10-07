// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Verbatim JSON backup of every RawLens [android.content.SharedPreferences]
 * store. Pure Kotlin with no Android calls (store names reference compile-time
 * `const val`s only, so no Activity class ever loads): host-testable.
 *
 * SharedPreferences values are typed (Boolean/Int/Long/Float/String/Set),
 * and JSON numbers cannot tell Int from Long from Float, so each entry keeps
 * its type tag: `{"key":{"t":"int","v":1}}`. Floats ride as strings
 * ([Float.toString] round-trips exactly, including NaN/Infinity); string sets
 * sort for deterministic output. Unknown stores are skipped on import so an
 * older app still reads a newer backup; unknown keys inside known stores are
 * kept so a newer app still restores them.
 */
object SettingsBackup {
    const val FORMAT = "rawlens-settings"
    const val FORMAT_VERSION = 1

    /** Main store name; [MainActivity.PREFS_NAME] aliases this (its companion is private). */
    const val STORE_MAIN = "rawlens_settings"

    /** Every preferences file the backup covers. */
    val KNOWN_STORES: Set<String> = setOf(
        STORE_MAIN,
        DngMetadataOverrideStore.PREFS_NAME,
        ProgramAeProfileStore.PREFS_NAME
    )

    /** Deterministic export of [stores] (store name to its `SharedPreferences.all`). */
    fun toJson(stores: Map<String, Map<String, *>>): String {
        val sb = StringBuilder(1024)
        sb.append("{\"format\":\"").append(FORMAT).append("\",\"formatVersion\":")
            .append(FORMAT_VERSION).append(",\"stores\":{")
        stores.keys.sorted().forEachIndexed { storeIndex, store ->
            if (storeIndex > 0) sb.append(',')
            sb.append('"').append(esc(store)).append("\":{")
            val entries = stores.getValue(store)
            entries.keys.sorted().forEachIndexed { entryIndex, key ->
                if (entryIndex > 0) sb.append(',')
                sb.append('"').append(esc(key)).append("\":")
                appendEntry(sb, key, entries.getValue(key))
            }
            sb.append('}')
        }
        sb.append("}}")
        return sb.toString()
    }

    /**
     * Parses an export back to per-store maps. Unknown stores are skipped;
     * anything else malformed throws [IllegalArgumentException].
     */
    fun fromJson(json: String): Map<String, Map<String, Any>> {
        val root = JsonParser(json).parse() as? Map<*, *>
            ?: throw IllegalArgumentException("settings backup must be a JSON object")
        if (root["format"] != FORMAT) throw IllegalArgumentException("not a RawLens settings backup")
        if ((root["formatVersion"] as? Long)?.toInt() != FORMAT_VERSION) {
            throw IllegalArgumentException("unsupported settings backup version ${root["formatVersion"]}")
        }
        val stores = root["stores"] as? Map<*, *>
            ?: throw IllegalArgumentException("settings backup has no stores object")
        return buildMap {
            for ((storeName, storeValue) in stores) {
                if (storeName !is String || storeName !in KNOWN_STORES) continue
                val entries = storeValue as? Map<*, *>
                    ?: throw IllegalArgumentException("store \"$storeName\" must be an object")
                put(storeName, entries.map { (key, entry) ->
                    if (key !is String) throw IllegalArgumentException("store \"$storeName\" has a non-string key")
                    key to decodeEntry(storeName, key, entry)
                }.toMap())
            }
        }
    }

    private fun appendEntry(sb: StringBuilder, key: String, value: Any?) {
        when (value) {
            is Boolean -> sb.append("{\"t\":\"bool\",\"v\":").append(value).append('}')
            is Int -> sb.append("{\"t\":\"int\",\"v\":").append(value).append('}')
            is Long -> sb.append("{\"t\":\"long\",\"v\":").append(value).append('}')
            is Float -> sb.append("{\"t\":\"float\",\"v\":\"").append(value.toString()).append("\"}")
            is String -> sb.append("{\"t\":\"string\",\"v\":\"").append(esc(value)).append("\"}")
            is Set<*> -> {
                if (value.any { it !is String }) {
                    throw IllegalArgumentException("string set \"$key\" holds a non-string")
                }
                sb.append("{\"t\":\"stringSet\",\"v\":[")
                @Suppress("UNCHECKED_CAST")
                (value as Set<String>).sorted().forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    sb.append('"').append(esc(item)).append('"')
                }
                sb.append("]}")
            }
            else -> throw IllegalArgumentException(
                "cannot back up \"$key\": ${value?.let { it::class.simpleName } ?: "null"}"
            )
        }
    }

    private fun decodeEntry(store: String, key: String, entry: Any?): Any {
        val at = "\"$store\" key \"$key\""
        val typed = entry as? Map<*, *>
            ?: throw IllegalArgumentException("$at must be a {\"t\",\"v\"} object")
        return when (val tag = typed["t"]) {
            "bool" -> (typed["v"] as? Boolean)
                ?: throw IllegalArgumentException("$at bool needs a boolean v")
            "int" -> (typed["v"] as? Long)?.toIntExact(at)
                ?: throw IllegalArgumentException("$at int needs an integer v")
            "long" -> (typed["v"] as? Long)
                ?: throw IllegalArgumentException("$at long needs an integer v")
            "float" -> {
                val raw = typed["v"] as? String
                    ?: throw IllegalArgumentException("$at float needs a string v")
                try {
                    raw.toFloat()
                } catch (_: NumberFormatException) {
                    throw IllegalArgumentException("$at float v is not a float: $raw")
                }
            }
            "string" -> (typed["v"] as? String)
                ?: throw IllegalArgumentException("$at string needs a string v")
            "stringSet" -> {
                val items = typed["v"] as? List<*>
                    ?: throw IllegalArgumentException("$at stringSet needs an array v")
                if (items.any { it !is String }) throw IllegalArgumentException("$at stringSet holds a non-string")
                @Suppress("UNCHECKED_CAST")
                (items as List<String>).toSet()
            }
            else -> throw IllegalArgumentException("$at has unknown type \"$tag\"")
        }
    }

    private fun Long.toIntExact(at: String): Int {
        if (this < Int.MIN_VALUE || this > Int.MAX_VALUE) {
            throw IllegalArgumentException("$at int v out of range: $this")
        }
        return toInt()
    }

    private fun esc(s: String): String = buildString(s.length + 8) {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }

    /** Minimal JSON reader: objects, arrays, strings, numbers, true/false/null. */
    private class JsonParser(private val text: String) {
        private var pos = 0

        fun parse(): Any? {
            val value = parseValue(0)
            skipWs()
            if (pos != text.length) fail("trailing characters")
            return value
        }

        private fun parseValue(depth: Int): Any? {
            if (depth > 64) fail("nesting too deep")
            skipWs()
            if (pos >= text.length) fail("unexpected end")
            return when (val c = text[pos]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
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
            pos++ // "
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) fail("unterminated string")
                when (val c = text[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= text.length) fail("unterminated escape")
                        when (val e = text[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) fail("bad \\u escape")
                                val code = text.substring(pos, pos + 4).toIntOrNull(16)
                                    ?: fail("bad \\u escape")
                                pos += 4
                                sb.append(code.toChar())
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    else -> {
                        if (c < ' ') fail("unescaped control character")
                        sb.append(c)
                    }
                }
            }
        }

        private fun parseNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && text[pos].isDigit()) pos++
            var isDouble = false
            if (pos < text.length && text[pos] == '.') {
                isDouble = true
                pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                isDouble = true
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            val raw = text.substring(start, pos)
            if (isDouble) return raw.toDoubleOrNull() ?: fail("bad number $raw")
            return raw.toLongOrNull() ?: fail("bad number $raw")
        }

        private fun parseLiteral(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) fail("expected $word")
            pos += word.length
            return value
        }

        private fun skipWs() {
            while (pos < text.length && text[pos] in " \t\n\r") pos++
        }

        private fun peek(): Char = if (pos < text.length) text[pos] else 0.toChar()

        private fun fail(message: String): Nothing =
            throw IllegalArgumentException("invalid settings backup: $message (at $pos)")
    }
}
