// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

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
                if (key in out) fail("duplicate object key")
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
                    if (c < ' ') fail("unescaped control character")
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
                    'f' -> sb.append('\u000c')
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
            if (peek() == '0') pos++ else {
                if (peek() !in '1'..'9') fail("bad number")
                while (peek() in '0'..'9') pos++
            }
            if (peek() == '.') {
                pos++
                if (peek() !in '0'..'9') fail("bad fraction")
                while (peek() in '0'..'9') pos++
            }
            if (peek() == 'e' || peek() == 'E') {
                pos++
                if (peek() == '+' || peek() == '-') pos++
                if (peek() !in '0'..'9') fail("bad exponent")
                while (peek() in '0'..'9') pos++
            }
            val raw = text.substring(start, pos)
            raw.toLongOrNull()?.let { return it }
            return raw.toDoubleOrNull()?.takeIf { it.isFinite() } ?: fail("bad number")
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

