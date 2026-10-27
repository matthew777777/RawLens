// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import java.util.WeakHashMap

/** Writable metadata only. Static characteristics are not offered as request controls. */
data class DiscoveredVendorKey(
    val name: String,
    val scopeHint: VendorKeyScope,
    val source: String,
    val tagHex: String? = null
)

/**
 * Lens-scoped advertised keys retain the framework's native type and vendor ID.
 * Numeric aliases resolve to these keys, never to a fabricated "0x..." name.
 * Tag reflection is optional: denial fails numeric resolution explicitly and
 * leaves named controls usable. No hidden-API exemption/bypass is installed.
 */
object VendorKeyAndroid {
    private class AdvertisedKey(val key: CaptureRequest.Key<*>) {
        val nativeType: Class<*>? by lazy {
            runCatching {
                val native = nativeKeyOf(key) ?: return@runCatching null
                native.javaClass.getDeclaredMethod("getType").invoke(native) as? Class<*>
            }.getOrNull()
        }
    }

    private data class Catalog(
        val request: List<CaptureRequest.Key<*>>,
        val session: List<CaptureRequest.Key<*>>,
        val physical: List<CaptureRequest.Key<*>>
    ) {
        val all = (request + session + physical).distinctBy { it.name }
        // Lazy: named application needs no tag reflection or full-list scans.
        val byName = all.associate { it.name to AdvertisedKey(it) }
        val byTag by lazy { byName.values.mapNotNull { key -> tagOf(key.key)?.let { it to key } }.toMap() }
        val physicalNames = physical.map { it.name }.toSet()
    }

    private val catalogs = WeakHashMap<CameraCharacteristics, Catalog>()

    @Synchronized private fun catalog(chars: CameraCharacteristics): Catalog =
        catalogs.getOrPut(chars) {
            Catalog(
                runCatching { chars.availableCaptureRequestKeys.orEmpty() }.getOrDefault(emptyList()),
                runCatching { chars.availableSessionKeys.orEmpty() }.getOrDefault(emptyList()),
                runCatching { chars.availablePhysicalCameraRequestKeys.orEmpty() }.getOrDefault(emptyList())
            )
        }

    fun discover(characteristics: CameraCharacteristics): List<DiscoveredVendorKey> {
        val catalog = catalog(characteristics)
        val found = linkedMapOf<String, DiscoveredVendorKey>()
        fun add(keys: List<CaptureRequest.Key<*>>, scope: VendorKeyScope, source: String) {
            keys.forEach { key ->
                found[key.name] = DiscoveredVendorKey(key.name, scope, source, tagHexOf(key))
            }
        }
        add(catalog.request, VendorKeyScope.REQUEST, "request")
        add(catalog.physical, VendorKeyScope.REQUEST, "physical request")
        add(catalog.session, VendorKeyScope.SESSION, "session")
        return found.values.sortedBy { it.name }
    }

    fun resolveNativeTag(characteristics: CameraCharacteristics, name: String): Int? {
        val clean = name.trim()
        val catalog = catalog(characteristics)
        val tag = VendorKeyEntry.numericTagValue(clean)
        return if (tag != null) tag.takeIf { it in catalog.byTag }
        else catalog.byName[clean]?.key?.let(::tagOf)
    }

    /** Preserve advertised vendor identity and type. Unknown numeric tags are never written. */
    @Suppress("UNCHECKED_CAST")
    fun <T> requestKeyOf(
        name: String,
        clazz: Class<T>,
        characteristics: CameraCharacteristics
    ): CaptureRequest.Key<T>? {
        val clean = name.trim()
        val catalog = catalog(characteristics)
        val key = if (VendorKeyEntry.isNumericTag(clean)) {
            catalog.byTag[VendorKeyEntry.numericTagValue(clean)] ?: return null
        } else catalog.byName[clean]
        if (key != null) {
            // Resolve the hidden type once per advertised key, rather than per builder.
            val nativeType = key.nativeType
            if (nativeType != null && boxed(nativeType) != boxed(clazz)) return null
            return key.key as CaptureRequest.Key<T>
        }
        return runCatching { CaptureRequest.Key(clean, clazz) }.getOrNull()
    }

    fun isPhysicalKey(characteristics: CameraCharacteristics, name: String): Boolean =
        name in catalog(characteristics).physicalNames

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Long::class.javaPrimitiveType -> Long::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType
        Double::class.javaPrimitiveType -> Double::class.javaObjectType
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
        else -> type
    }

    fun tagOf(key: Any): Int? = runCatching {
        val native = nativeKeyOf(key) ?: return@runCatching null
        native.javaClass.getDeclaredMethod("getTag").invoke(native) as? Int
    }.getOrNull()

    fun tagHexOf(key: Any): String? = tagOf(key)?.let(::tagHex)
    fun tagHex(tag: Int): String = "0x" + tag.toUInt().toString(16).uppercase()

    private fun nativeKeyOf(key: Any): Any? = runCatching {
        key.javaClass.getDeclaredMethod("getNativeKey").invoke(key)
    }.getOrNull()
}
