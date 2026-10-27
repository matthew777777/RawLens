// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Per-lens vendor keys / vendor tags the user adds in settings.
 *
 * Data model for the per-lens editor and request/session application.
 *
 * Scope mirrors Camera2 reality (see upstream camera apps):
 * - [VendorKeyScope.SESSION] keys must be loaded into
 *   `SessionConfiguration.setSessionParameters()` *before* session creation.
 *   OEM-specific effects require device verification; see
 *   `docs/refactor/vendor-keys-study.md`.
 * - [VendorKeyScope.REQUEST] keys are applied after standard request controls.
 *   Static black/white-level characteristics are not request overrides.
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
    BYTE_ARRAY("Byte[]"),
    STRING("String");

    /** Parse a user-typed value into the boxed/array object this key carries. */
    fun parse(raw: String): Any {
        require(raw.length <= MAX_VALUE_CHARS) { "Value too long" }
        if (this == STRING) {
            require('\u0000' !in raw) { "String must not contain NUL" }
            return raw
        }
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
            STRING -> raw
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
                cells[it].toIntOrNull()?.takeIf { v -> v in -128..255 }?.toByte()
                    ?: throw IllegalArgumentException("Not a byte (-128..255): ${cells[it]}")
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
        if (isNumericTag(clean)) {
            require(numericTagValue(clean) != null) { "Numeric tag out of 32-bit range: $clean" }
        } else {
            require(' ' !in clean && '\t' !in clean && '@' !in clean) { "Key name must not contain spaces or @" }
            require('.' in clean) { "Key name must be reverse-domain (section.key) or a numeric tag (0x…)" }
            require(NAME_CHARS.matches(clean)) { "Key name uses unsupported characters: $clean" }
        }
        type.parse(value)
    }

    /** Typed payload for request/session application. */
    fun parsedValue(): Any = type.parse(value)

    /** Canonical identity also deduplicates differently spelled numeric aliases. */
    fun identity(): String = numericTagValue(name)?.let { "0x" + it.toUInt().toString(16) } ?: name.trim()

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
        private val NUMERIC_TAG = Regex("0[xX][0-9a-fA-F]{1,8}")

        /** Numeric aliases resolve against the open camera's advertised request keys. */
        fun isNumericTag(name: String): Boolean = NUMERIC_TAG.matches(name.trim())

        /** 32-bit tag value, or null when out of range. */
        fun numericTagValue(name: String): Int? {
            if (!isNumericTag(name)) return null
            val bits = name.trim().substring(2).toLongOrNull(16) ?: return null
            if (bits !in 0..0xFFFFFFFFL) return null
            return bits.toInt()
        }

        fun isPlausibleName(name: String): Boolean = runCatching {
            VendorKeyEntry(name, VendorKeyType.INT, VendorKeyScope.REQUEST, "0").validate()
        }.isSuccess

        fun fromJson(obj: Map<String, Any?>): VendorKeyEntry {
            val type = VendorKeyType.fromLabel(obj["t"] as? String)
                ?: throw IllegalArgumentException("Unknown vendor key type: ${obj["t"]}")
            val scope = VendorKeyScope.fromId(obj["s"] as? String)
                ?: throw IllegalArgumentException("Unknown vendor key scope: ${obj["s"]}")
            require(!obj.containsKey("e") || obj["e"] is Boolean) { "Enabled must be Boolean" }
            return VendorKeyEntry(
                name = (obj["n"] as? String)?.trim() ?: throw IllegalArgumentException("Vendor key needs a name"),
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
data class VendorKeyProfile(
    val entries: List<VendorKeyEntry> = emptyList(),
    /**
     * Vendor stream operating mode (`0` = default). Non-zero opens the session
     * through `SessionConfiguration` so early session keys travel with it. OEM values and sensor effects require device verification.
     * `0` keeps the standard session path untouched.
     */
    val operatingMode: Int = 0
) {
    fun isEmpty(): Boolean = entries.isEmpty() && operatingMode == 0

    fun activeEntries(): List<VendorKeyEntry> = entries.filter { it.enabled }

    fun sessionEntries(): List<VendorKeyEntry> = activeEntries().filter { it.scope == VendorKeyScope.SESSION }

    fun requestEntries(): List<VendorKeyEntry> = activeEntries().filter { it.scope == VendorKeyScope.REQUEST }

    /**
     * Preset application: this profile's keys win; [other]'s entries are
     * appended only where no same-named key exists. A nonzero lens mode wins;
     * otherwise adopt the imported mode. Never deletes.
     */
    fun mergedWith(other: VendorKeyProfile): VendorKeyProfile {
        val names = entries.map { it.identity() }.toSet()
        return VendorKeyProfile(
            (entries + other.entries.filter { it.identity() !in names }).sortedBy { it.name },
            operatingMode.takeIf { it != 0 } ?: other.operatingMode
        )
    }

    fun validate() {
        entries.forEach(VendorKeyEntry::validate)
        val names = entries.map { it.identity() }
        require(names.size == names.toSet().size) { "Duplicate vendor key name" }
        require(entries.size <= MAX_ENTRIES) { "Too many vendor keys (max $MAX_ENTRIES)" }
        require(VendorSessionAttempt.validMode(operatingMode)) { "Mode must be 0 or a vendor mode (32768 or higher)" }
    }

    fun toJson(): String = buildString {
        append("{\"v\":").append(FORMAT_VERSION).append(",\"op\":").append(operatingMode).append(",\"entries\":[")
        entries.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            append(entry.toJson())
        }
        append("]}")
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val MAX_ENTRIES = 32

        const val MAX_JSON_CHARS = 262144

        /** Tolerant persisted load; imports use strict=true to avoid silently dropping data. */
        fun fromJson(json: String?, strict: Boolean = false): VendorKeyProfile {
            if (json.isNullOrBlank()) return VendorKeyProfile()
            require(json.length <= MAX_JSON_CHARS) { "Vendor key profile too large" }
            val root = VendorKeyJson.parseObject(json)
            require(!root.containsKey("v") || root["v"] == FORMAT_VERSION.toLong()) {
                "Unsupported vendor key profile version"
            }
            val array = root["entries"] as? List<*>
                ?: throw IllegalArgumentException("Vendor key profile needs an entries array")
            val kept = linkedMapOf<String, VendorKeyEntry>()
            for (row in array) {
                val entry = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    val obj = row as? Map<String, Any?>
                        ?: throw IllegalArgumentException("Invalid vendor key row")
                    VendorKeyEntry.fromJson(obj)
                }.getOrElse { failure ->
                    if (strict) throw IllegalArgumentException("Invalid vendor key row", failure)
                    continue
                }
                if (entry.identity() in kept || kept.size >= MAX_ENTRIES) {
                    require(!strict) { "Duplicate or too many vendor keys" }
                    continue
                }
                kept[entry.identity()] = entry
            }
            val storedMode = root["op"] as? Long
            val mode = storedMode?.takeIf { it in 0..Int.MAX_VALUE.toLong() }
                ?.toInt()?.takeIf(VendorSessionAttempt::validMode)
            require(!strict || !root.containsKey("op") || mode != null) { "Invalid operating mode" }
            return VendorKeyProfile(kept.values.toList(), mode ?: 0)
        }

        /** Pure + host-tested: parses the session operating mode field. */
        fun parseOperatingMode(text: String): Int {
            val clean = text.trim()
            require(clean.isNotEmpty()) { "Operating mode must not be blank (0 = default)" }
            val value = clean.toIntOrNull()
                ?: throw IllegalArgumentException("Operating mode must be a whole number")
            require(VendorSessionAttempt.validMode(value)) { "Mode must be 0 or a vendor mode (32768 or higher)" }
            return value
        }

        /**
         * Disabled-by-default templates shown when a lens has no keys yet.
         * Enabling them is always explicit: nothing is sent until the user
         * toggles a key on (application reads only [VendorKeyProfile.activeEntries]).
         *
         * Black/white levels are deliberately NOT here: SDK-verified, no
         * `CaptureRequest` keys exist for them, so they stay on the per-lens
         * DNG calibration editor. See [VendorKeyApply.isLevelTag].
         */
        fun presets(): VendorKeyProfile = VendorKeyProfile(
            listOf(
                // Legacy opt-in template; device effect is not verified by this audit.
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
