// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import java.util.Locale
import java.util.concurrent.Executors

/** Route kind drives grouping in the lens picker UI. */
enum class LensRouteKind(val sectionTitle: String) {
    STANDALONE("Direct cameras"),
    LOGICAL_PHYSICAL("Logical → physical"),
    VENDOR_COMPOSITE("Vendor routes"),
    UNAVAILABLE("Currently unavailable")
}

/** Coarse optical role derived from 35mm-equivalent focal length. */
enum class LensRole(val displayName: String) {
    ULTRA_WIDE("Ultra-wide"),
    WIDE("Wide"),
    NORMAL("Normal"),
    TELE("Tele"),
    SUPER_TELE("Super-tele"),
    UNKNOWN("Lens");

    companion object {
        fun forEquivalentMm(equivalentMm: Float?): LensRole = when {
            equivalentMm == null || !equivalentMm.isFinite() || equivalentMm <= 0f -> UNKNOWN
            equivalentMm < 20f -> ULTRA_WIDE
            equivalentMm < 32f -> WIDE
            equivalentMm < 60f -> NORMAL
            equivalentMm < 100f -> TELE
            else -> SUPER_TELE
        }
    }
}

/** A selectable camera route. `id` remains persistence-compatible with the old UI. */
data class DiscoveredLens(
    val id: String,
    val label: String,
    val opticalMetric: Float = Float.MAX_VALUE,
    val kind: LensRouteKind = LensRouteKind.STANDALONE,
    val role: LensRole = LensRole.UNKNOWN,
    val logicalId: String? = null,
    val physicalId: String? = null,
    val focalMm: Float? = null,
    val equivalentMm: Float? = null,
    val resolutionLabel: String? = null,
    val megapixels: Float? = null,
    /** Short prominent line, e.g. "Wide · 24 mm eq". */
    val title: String = label,
    /** One or two detail lines, e.g. "4.35 mm lens · 4000×3000 · 12.0 MP". */
    val details: String = "",
    /** Route explanation, e.g. "Direct camera 0" or "Logical 0 → physical 2". */
    val route: String = "",
    /** Probed ISO range label, e.g. "50–12800". Null when unreadable. */
    val isoRange: String? = null,
    /** Slowest advertised exposure in ms. Null when unreadable. */
    val maxExposureMs: Long? = null,
    /**
     * True when the route advertises a sensor-mode session switch
     * (e.g. `vivo.control.forceSensorMode`). Means "special sensor mode
     * available", never a claim that DCG itself works.
     */
    val sensorModeSwitch: Boolean = false,
    /**
     * Largest advertised RAW_SENSOR output, e.g. "4080×3060". Shown only
     * when it differs from the pixel array: equal means redundant, absent
     * means nothing (physical blocks report sizes inconsistently).
     */
    val maxRawSize: String? = null,
    /** Every advertised RAW_SENSOR size, largest first, e.g. "8160×6144". */
    val rawSizes: List<String> = emptyList(),
    /**
     * Packed formats the map lists (subset of RAW10/RAW12/RAW14).
     * Informational only: no unpack path exists, so sessions stay
     * RAW_SENSOR. RAW_PRIVATE is intentionally never listed (opaque,
     * never DNG-usable).
     */
    val packedFormats: List<String> = emptyList(),
    /** Open-time verdict from real `openCamera` calls; UNKNOWN until opened. */
    val verdict: LensVerdict = LensVerdict.UNKNOWN
)

/** A picker section with a header and an explanatory subtitle. */
data class LensGroup(
    val kind: LensRouteKind,
    val title: String,
    val subtitle: String,
    val lenses: List<DiscoveredLens>
)

/**
 * Universal Camera2 lens discovery.
 *
 * We expose two independent kinds of routes whenever Android/the vendor makes them usable:
 *  1. Standalone camera IDs (for example `0` or `2`).
 *  2. Logical -> physical routes (displayed/persisted as `logical/physical`, for example `3/2`).
 *
 * Standard logical/physical routes are discovered from CameraCharacteristics.physicalCameraIds and
 * DO NOT require `logical/physical` to be a real Camera2 ID. RawCameraController understands that
 * syntax and opens the logical CameraDevice while binding its outputs to the physical camera.
 *
 * As a best-effort compatibility layer for OEMs that hide additional numeric/vendor IDs, we also
 * probe numeric IDs and slash/dash combinations made only from IDs that the camera service itself
 * recognizes. No focal length, zoom factor, or particular ID number is hard-coded.
 */
class LensDiscovery(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    fun discover(
        verdicts: LensVerdicts = LensVerdicts.EMPTY,
        onComplete: (List<DiscoveredLens>) -> Unit
    ) {
        executor.execute {
            try {
                onComplete(discoverBlocking(verdicts))
            } catch (t: Throwable) {
                // Never let a vendor characteristics bug kill discovery: report what we have.
                Log.w(LOG_TAG, "Lens discovery failed; returning empty list", t)
                try {
                    onComplete(emptyList())
                } catch (_: Throwable) {
                    // Caller callback must never crash the discovery thread.
                }
            }
        }
    }

    private fun discoverBlocking(verdicts: LensVerdicts): List<DiscoveredLens> {
            val official = cameraIds()
            Log.i(LOG_TAG, "Official camera IDs: $official")

            // Official IDs are the only Camera2 IDs Android guarantees can be opened as
            // standalone devices. Keep a wider set of queryable IDs only for OEM alias probing;
            // querying characteristics for a hidden physical ID does NOT imply it can be opened.
            val queryableIds = linkedSetOf<String>()
            queryableIds += official
            // Physical IDs are often alphanumeric (for example A or B) and may be hidden from
            // cameraIdList. Include every ID advertised by a logical camera so OEM aliases such
            // as A-B and A/B can be discovered as well as numeric forms such as 0-1 and 3/2.
            official.forEach { id ->
                val physicalIds = characteristics(id)?.let(::safePhysicalIds) ?: emptySet()
                physicalIds.forEach { physicalId ->
                    if (characteristics(physicalId) != null) queryableIds += physicalId
                }
            }
            for (number in 0..MAX_NUMERIC_CAMERA_ID) {
                val id = number.toString()
                characteristics(id)?.let { c ->
                    queryableIds += id
                    Log.i(LOG_TAG, "Queryable camera $id: ${summary(c)}")
                }
            }

            val found = linkedMapOf<String, DiscoveredLens>()

            // A) Every queryable standalone RAW camera. Hidden numeric IDs are deliberately
            // included: on affected Xiaomi/MediaTek devices getCameraCharacteristics succeeds
            // for rear RAW cameras omitted from cameraIdList.
            queryableIds.forEach { id ->
                val c = characteristics(id) ?: return@forEach
                if (runCatching { isRearRaw(c) }.getOrDefault(false)) {
                    runCatching { discovered(id, c, "standalone") }
                        .onSuccess { found[id] = it }
                        .onFailure { Log.w(LOG_TAG, "Skipping standalone $id", it) }
                }
            }

            // B) Every logical -> physical relationship advertised by a queryable camera,
            // including hidden logical cameras omitted from cameraIdList.
            // The relationship itself is authoritative; the synthetic display identity need not
            // be accepted by getCameraCharacteristics().
            queryableIds.forEach { logicalId ->
                val logical = characteristics(logicalId) ?: return@forEach
                val physicalIds = safePhysicalIds(logical)
                if (physicalIds.isEmpty()) return@forEach

                physicalIds.forEach physicalLoop@ { physicalId ->
                    // API 29+ (below the RawLens minSdk 30) explicitly permits querying characteristics
                    // for physical IDs that are hidden from cameraIdList.
                    val physical = characteristics(physicalId) ?: return@physicalLoop
                    if (runCatching { isRearRaw(physical, logical) }.getOrDefault(false)) {
                        runCatching {
                            discovered(
                                id = "$logicalId/$physicalId",
                                c = physical,
                                route = "logical $logicalId → physical $physicalId",
                                logicalFallback = logical
                            )
                        }.onSuccess { found["$logicalId/$physicalId"] = it }
                            .onFailure { Log.w(LOG_TAG, "Skipping route $logicalId/$physicalId", it) }
                    }
                }
            }

            // C) OEM vendor composite IDs. Some camera services expose only the composite ID:
            // getCameraCharacteristics("3/2") succeeds even though querying "3" or "2" alone
            // does not. Consequently these numeric forms must be generated unconditionally.
            // This mirrors the proven PhotonCamera discovery range while the combinations of
            // service-reported IDs below additionally cover alphanumeric forms such as A/B.
            val compositeCandidates = linkedSetOf<String>()
            for (logical in 0 until MAX_COMPOSITE_LOGICAL_ID) {
                for (physical in 0 until MAX_COMPOSITE_PHYSICAL_ID) {
                    compositeCandidates += "$logical/$physical"
                    compositeCandidates += "$logical-$physical"
                }
            }

            val components = queryableIds.toList()
            for (logicalId in components) {
                for (physicalId in components) {
                    if (logicalId == physicalId) continue
                    for (separator in charArrayOf('/', '-')) {
                        compositeCandidates += "$logicalId$separator$physicalId"
                    }
                }
            }
            compositeCandidates.forEach { composite ->
                if (composite in found) return@forEach
                runCatching {
                    val compositeCharacteristics = characteristics(composite) ?: return@runCatching null
                    val physicalId = composite.substringAfterLast('/', composite.substringAfterLast('-'))
                    val logicalId = composite.substringBefore('/').substringBefore('-')
                    val logical = characteristics(logicalId)
                    val usable = when {
                        isRearRaw(compositeCharacteristics, logical) -> compositeCharacteristics
                        else -> characteristics(physicalId)?.takeIf { isRearRaw(it, logical) }
                    } ?: return@runCatching null
                    discovered(composite, usable, "vendor route", logicalFallback = logical)
                }.onSuccess { lens ->
                    if (lens != null) found[composite] = lens
                }.onFailure {
                    Log.w(LOG_TAG, "Skipping vendor route $composite", it)
                }
            }

            Log.i(LOG_TAG, "Discovered RAW lenses: ${found.keys}")
            return found.values
                .map { lens -> applyVerdict(lens, routeVerdict(lens.id, verdicts)) }
                .sortedWith(compareBy<DiscoveredLens> { it.opticalMetric }.thenBy { it.id })
    }

    fun close() = executor.shutdownNow()

    private fun cameraIds(): List<String> = try {
        manager.cameraIdList.toList()
    } catch (_: Throwable) {
        // CameraAccessException, SecurityException, and vendor RuntimeExceptions/Errors.
        emptyList()
    }

    private fun characteristics(id: String): CameraCharacteristics? = try {
        manager.getCameraCharacteristics(id)
    } catch (_: Throwable) {
        // Vendor camera services occasionally throw non-standard runtime failures,
        // IllegalArgumentException, or even Errors for hidden/probed IDs.
        null
    }

    /**
     * Every [CameraCharacteristics.get] call can throw on vendor ROMs. The reported crash
     * is a NullPointerException inside
     * `CameraMetadataNative.getStreamConfigurationMap` when reading
     * `SCALER_STREAM_CONFIGURATION_MAP`, so all key reads go through here.
     */
    private fun <T> safeGet(c: CameraCharacteristics, key: CameraCharacteristics.Key<T>): T? =
        try {
            c.get(key)
        } catch (_: Throwable) {
            null
        }

    private fun safePhysicalIds(c: CameraCharacteristics): Set<String> = try {
        c.physicalCameraIds ?: emptySet()
    } catch (_: Throwable) {
        emptySet()
    }

    private fun hasRawStreamConfig(c: CameraCharacteristics): Boolean = try {
        safeGet(c, CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.outputFormats?.contains(ImageFormat.RAW_SENSOR) == true
    } catch (_: Throwable) {
        false
    }

    private fun isRearRaw(
        c: CameraCharacteristics,
        logicalFallback: CameraCharacteristics? = null
    ): Boolean {
        return try {
            val facing = safeGet(c, CameraCharacteristics.LENS_FACING)
                ?: logicalFallback?.let { safeGet(it, CameraCharacteristics.LENS_FACING) }
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                false
            } else {
                val capabilities =
                    safeGet(c, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                if (capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
                    true
                } else {
                    // Some physical-camera characteristic blocks omit the RAW capability bit even though the
                    // physical stream configuration explicitly exposes RAW_SENSOR. Accept that authoritative
                    // stream declaration as well.
                    hasRawStreamConfig(c)
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun discovered(
        id: String,
        c: CameraCharacteristics,
        route: String,
        logicalFallback: CameraCharacteristics? = null
    ): DiscoveredLens {
        val focal = safeGet(c, CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val sensor = safeGet(c, CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pixels = safeGet(c, CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val equivalent = if (focal != null && sensor != null && sensor.width > 0f) {
            36f * focal / sensor.width
        } else null
        val megapixels = if (pixels != null) pixels.width * pixels.height / 1_000_000f else null
        val resolutionLabel = pixels?.let { "${it.width}×${it.height}" }
        val role = LensRole.forEquivalentMm(equivalent)
        val isoRange = safeGet(c, CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?.let { "${it.lower}–${it.upper}" }
        val maxExposureMs = safeGet(c, CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?.upper?.div(1_000_000L)
        // Logical/physical routes open the logical device, so the logical
        // camera's session keys decide whether a sensor-mode switch exists.
        val sensorModeSwitch = hasSensorModeSwitch(c) ||
            (logicalFallback != null && hasSensorModeSwitch(logicalFallback))
        // Cheapest stream-level signal: every RAW_SENSOR output the map
        // actually lists, plus packed formats. Informational only —
        // physical blocks report sizes inconsistently, so absence never
        // disqualifies a route.
        val rawSizeList = runCatching {
            safeGet(c, CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.RAW_SENSOR)
                ?.filter { it.width > 0 && it.height > 0 }
                ?.sortedByDescending { it.width.toLong() * it.height }
                ?.map { "${it.width}×${it.height}" }
        }.getOrNull().orEmpty()
        val maxRawSize = rawSizeList.firstOrNull()
        val packedFormats = listOf(
            "RAW10" to ImageFormat.RAW10,
            "RAW12" to ImageFormat.RAW12,
            "RAW14" to IMAGE_FORMAT_RAW14
        )
            .filter { (_, format) ->
                runCatching {
                    safeGet(c, CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?.getOutputSizes(format)?.isNotEmpty() == true
                }.getOrDefault(false)
            }.map { it.first }
        val (kind, logicalId, physicalId) = parseRoute(id, route)
        val routeText = when (kind) {
            LensRouteKind.STANDALONE -> "Direct camera $id"
            LensRouteKind.LOGICAL_PHYSICAL -> {
                val logical = logicalId ?: id.substringBefore('/')
                val physical = physicalId ?: id.substringAfter('/')
                "Logical $logical → physical $physical"
            }
            LensRouteKind.VENDOR_COMPOSITE -> "Vendor route $id"
            LensRouteKind.UNAVAILABLE -> route
        }
        val title = if (equivalent != null) {
            "${role.displayName} · ≈${String.format(Locale.US, "%.0f", equivalent)} mm eq"
        } else if (focal != null) {
            "${role.displayName} · ${String.format(Locale.US, "%.2f", focal)} mm lens"
        } else {
            "${role.displayName} · Camera $id"
        }
        val probe = DiscoveredLens(
            id = id,
            label = "",
            opticalMetric = Float.MAX_VALUE,
            kind = kind,
            isoRange = isoRange,
            maxExposureMs = maxExposureMs,
            sensorModeSwitch = sensorModeSwitch,
            rawSizes = rawSizeList,
            packedFormats = packedFormats
        )
        val details = (buildList {
            if (focal != null) add(String.format(Locale.US, "%.2f mm", focal))
            if (equivalent != null) add(String.format(Locale.US, "≈%.0f mm eq", equivalent))
            if (resolutionLabel != null) add(resolutionLabel)
            if (megapixels != null) add(String.format(Locale.US, "%.1f MP", megapixels))
            if (maxRawSize != null && maxRawSize != resolutionLabel) add("RAW $maxRawSize")
        } + capabilityBadges(probe)).joinToString(" · ").ifEmpty { "Characteristics unavailable" }
        // Legacy single-line label stays for callers/tests that only render `label`.
        val label = "Camera $id · $title • $details • $routeText"
        val metric = if (focal != null && sensor != null && sensor.width > 0f) focal / sensor.width else Float.MAX_VALUE
        return DiscoveredLens(
            id = id,
            label = label,
            opticalMetric = metric,
            kind = kind,
            role = role,
            logicalId = logicalId,
            physicalId = physicalId,
            focalMm = focal,
            equivalentMm = equivalent,
            resolutionLabel = resolutionLabel,
            megapixels = megapixels,
            title = title,
            details = details,
            route = routeText,
            isoRange = isoRange,
            maxExposureMs = maxExposureMs,
            sensorModeSwitch = sensorModeSwitch,
            maxRawSize = maxRawSize,
            rawSizes = rawSizeList,
            packedFormats = packedFormats
        )
    }

    private fun hasSensorModeSwitch(c: CameraCharacteristics): Boolean = try {
        c.availableSessionKeys.any { key ->
            val name = key.name.lowercase(Locale.US)
            "sensormode" in name.replace(".", "").replace("_", "")
        }
    } catch (_: Throwable) {
        false
    }

    private fun parseRoute(id: String, route: String): Triple<LensRouteKind, String?, String?> {
        if (route == "standalone") return Triple(LensRouteKind.STANDALONE, null, null)
        if (route.startsWith("logical")) {
            // id form is "logical/physical".
            val logical = id.substringBefore('/').ifEmpty { null }
            val physical = id.substringAfter('/', "").ifEmpty { null }
            return Triple(LensRouteKind.LOGICAL_PHYSICAL, logical, physical)
        }
        // Vendor composite: "3/2" or "A-B".
        val separator = when {
            '/' in id -> '/'
            '-' in id && id.length > 2 -> '-'
            else -> null
        }
        if (separator != null) {
            val logical = id.substringBefore(separator).ifEmpty { null }
            val physical = id.substringAfter(separator).ifEmpty { null }
            return Triple(LensRouteKind.VENDOR_COMPOSITE, logical, physical)
        }
        return Triple(LensRouteKind.VENDOR_COMPOSITE, null, null)
    }

    private fun summary(c: CameraCharacteristics): String = try {
        val capabilities = safeGet(c, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val rawCapability = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val rawStream = hasRawStreamConfig(c)
        val facing = safeGet(c, CameraCharacteristics.LENS_FACING)
        val physical = safePhysicalIds(c)
        "facing=$facing rawCapability=$rawCapability rawStream=$rawStream physical=$physical"
    } catch (t: Throwable) {
        // Logging must never crash discovery on malformed vendor metadata.
        "unavailable(${t.javaClass.simpleName})"
    }

    private companion object {
        // Numeric probing is a compatibility fallback only. Standard Camera2 discovery is not
        // bounded by this number because cameraIdList supports arbitrary string IDs.
        const val MAX_NUMERIC_CAMERA_ID = 200
        const val MAX_COMPOSITE_LOGICAL_ID = 150
        const val MAX_COMPOSITE_PHYSICAL_ID = 10
        const val LOG_TAG = "RawLensDiscovery"
    }
}

/** Placeholder for a previously saved lens that discovery did not report this run. */
fun unavailableLens(id: String): DiscoveredLens = DiscoveredLens(
    id = id,
    label = "Camera $id • currently unavailable",
    opticalMetric = Float.MAX_VALUE,
    kind = LensRouteKind.UNAVAILABLE,
    role = LensRole.UNKNOWN,
    title = "Camera $id",
    details = "Saved earlier · not reported by the camera service right now",
    route = "Currently unavailable"
)

/**
 * Groups lenses into readable picker sections, ordered for display.
 *
 * - Direct cameras first (wide → tele).
 * - One section per logical parent for logical → physical routes.
 * - Vendor composites next (wide → tele).
 * - Unavailable entries (explicit kind or "unavailable" label fallback) last.
 */
fun groupLenses(lenses: List<DiscoveredLens>): List<LensGroup> {
    val sorted = lenses.sortedWith(compareBy<DiscoveredLens> { it.opticalMetric }.thenBy { it.id })
    fun effectiveKind(lens: DiscoveredLens): LensRouteKind =
        if (lens.kind == LensRouteKind.UNAVAILABLE || "unavailable" in lens.label.lowercase()) {
            LensRouteKind.UNAVAILABLE
        } else lens.kind

    val groups = mutableListOf<LensGroup>()
    val direct = sorted.filter { effectiveKind(it) == LensRouteKind.STANDALONE }
    if (direct.isNotEmpty()) {
        groups += LensGroup(
            kind = LensRouteKind.STANDALONE,
            title = "Direct cameras · ${direct.size}",
            subtitle = "Open straight from the camera service. Pick these first when in doubt.",
            lenses = direct
        )
    }
    val logical = sorted.filter { effectiveKind(it) == LensRouteKind.LOGICAL_PHYSICAL }
        .groupBy { it.logicalId ?: it.id.substringBefore('/') }
        .toSortedMap()
    logical.forEach { (logicalId, items) ->
        groups += LensGroup(
            kind = LensRouteKind.LOGICAL_PHYSICAL,
            title = "Logical $logicalId · ${items.size} lens${if (items.size == 1) "" else "es"}",
            subtitle = "Opens logical camera $logicalId and binds output to one physical lens.",
            lenses = items
        )
    }
    val vendor = sorted.filter { effectiveKind(it) == LensRouteKind.VENDOR_COMPOSITE }
    if (vendor.isNotEmpty()) {
        groups += LensGroup(
            kind = LensRouteKind.VENDOR_COMPOSITE,
            title = "Vendor routes · ${vendor.size}",
            subtitle = "OEM composite IDs (such as 3/2 or A-B). Only needed when direct routes miss a lens.",
            lenses = vendor
        )
    }
    val unavailable = sorted.filter { effectiveKind(it) == LensRouteKind.UNAVAILABLE }
        .map { if (it.kind == LensRouteKind.UNAVAILABLE) it else it.copy(kind = LensRouteKind.UNAVAILABLE) }
    if (unavailable.isNotEmpty()) {
        groups += LensGroup(
            kind = LensRouteKind.UNAVAILABLE,
            title = "Currently unavailable · ${unavailable.size}",
            subtitle = "Saved before but not reported now. Keep checked to preserve them, uncheck to forget.",
            lenses = unavailable.sortedBy { it.id }
        )
    }
    return groups
}

/**
 * Android 17 (API 37) packed 14-bit RAW format. This project's compile SDK
 * predates the `ImageFormat.RAW14` constant, so it is addressed by value
 * (44, verified against the android-37 SDK platform). `getOutputSizes`
 * accepts any format int; on older releases it simply reports empty.
 */
internal const val IMAGE_FORMAT_RAW14 = 44

/** Short capability badges for one route, e.g. `["ISO 50–12800", "Sensor-mode switch"]`. */
fun capabilityBadges(lens: DiscoveredLens): List<String> = buildList {
    if (lens.isoRange != null) add("ISO ${lens.isoRange}")
    val maxMs = lens.maxExposureMs
    if (maxMs != null && maxMs > 0) {
        add(if (maxMs >= 1000) "Exp ≤ ${maxMs / 1000}s" else "Exp ≤ ${maxMs}ms")
    }
    if (lens.sensorModeSwitch) add("Sensor-mode switch")
    if (lens.rawSizes.size > 1) add("${lens.rawSizes.size} RAW sizes")
    if (lens.packedFormats.isNotEmpty()) add("Lists ${lens.packedFormats.joinToString("+")}")
}

/**
 * Optic fingerprint: routes to the same physical lens share focal length,
 * 35mm-equivalent, and sensor resolution, so they collapse into one card.
 * Lenses without any optical data fall back to their route ID and never merge.
 */
fun opticFingerprint(lens: DiscoveredLens): String {
    val focal = lens.focalMm?.let { String.format(Locale.US, "%.2f", it) }
    val equivalent = lens.equivalentMm?.let { String.format(Locale.US, "%.0f", it) }
    val mega = lens.megapixels?.let { String.format(Locale.US, "%.1f", it) }
    if (focal == null && equivalent == null && lens.resolutionLabel == null && mega == null) {
        return "id:${lens.id}"
    }
    return "f${focal ?: "?"}|eq${equivalent ?: "?"}|${lens.resolutionLabel ?: "?"}|mp${mega ?: "?"}"
}

/** One physical lens with every route that reaches it. First lens is recommended. */
data class OpticGroup(
    val key: String,
    val title: String,
    val subtitle: String,
    val lenses: List<DiscoveredLens>
) {
    val recommended: DiscoveredLens get() = lenses.first()
    val alternates: List<DiscoveredLens> get() = lenses.drop(1)
}

private fun isUnavailableLens(lens: DiscoveredLens): Boolean =
    lens.kind == LensRouteKind.UNAVAILABLE || "unavailable" in lens.label.lowercase()

private fun routePriority(kind: LensRouteKind): Int = when (kind) {
    LensRouteKind.STANDALONE -> 0
    LensRouteKind.LOGICAL_PHYSICAL -> 1
    LensRouteKind.VENDOR_COMPOSITE -> 2
    LensRouteKind.UNAVAILABLE -> 3
}

private fun verdictPriority(verdict: LensVerdict): Int = when (verdict) {
    LensVerdict.VERIFIED -> 0
    LensVerdict.OPENABLE -> 1
    LensVerdict.UNKNOWN -> 2
    LensVerdict.DEAD -> 3
}

private val opticRouteOrder: Comparator<DiscoveredLens> =
    compareBy<DiscoveredLens> { verdictPriority(it.verdict) }
        .thenBy { routePriority(it.kind) }
        .thenBy { it.opticalMetric }
        .thenBy { it.id }

/** Recommended route inside one optic: stream-verified first, then opened, direct, logical, vendor. */
fun recommendRoute(lenses: List<DiscoveredLens>): DiscoveredLens =
    lenses.sortedWith(opticRouteOrder).first()

/**
 * Evidence for one route ID. Every verdict is keyed by the exact route
 * identity or open ID recorded when the camera call happened: an
 * openable logical camera says nothing about its composites, so there is
 * deliberately no prefix inference. Unknown until some camera call on
 * this device proves otherwise.
 */
fun routeVerdict(routeId: String, verdicts: LensVerdicts): LensVerdict {
    if (routeId in verdicts.verified) return LensVerdict.VERIFIED
    if (routeId in verdicts.dead) return LensVerdict.DEAD
    if (routeId in verdicts.openable) return LensVerdict.OPENABLE
    return LensVerdict.UNKNOWN
}

/** One-line verdict note for picker details; null when there is nothing to say. */
fun verdictNote(verdict: LensVerdict): String? = when (verdict) {
    LensVerdict.VERIFIED -> "RAW verified on this device"
    LensVerdict.OPENABLE -> "Opened OK before"
    LensVerdict.DEAD -> "Open rejected before · try anyway"
    LensVerdict.UNKNOWN -> null
}

/** Attaches an open-time verdict to a lens, keeping the legacy label in sync. */
fun applyVerdict(lens: DiscoveredLens, verdict: LensVerdict): DiscoveredLens {
    if (verdict == LensVerdict.UNKNOWN || lens.kind == LensRouteKind.UNAVAILABLE) {
        return lens.copy(verdict = verdict)
    }
    val note = verdictNote(verdict) ?: return lens.copy(verdict = verdict)
    if (note in lens.details) return lens.copy(verdict = verdict)
    val details = "${lens.details} · $note"
    return lens.copy(
        verdict = verdict,
        details = details,
        label = "Camera ${lens.id} · ${lens.title} • $details • ${lens.route}"
    )
}

/**
 * Groups available routes by physical lens, widest first. Each group holds
 * the recommended route first with the remaining routes as alternates.
 * Unavailable placeholders stay in one trailing group, unmerged.
 */
fun groupByOptic(lenses: List<DiscoveredLens>): List<OpticGroup> {
    val (missing, available) = lenses.partition(::isUnavailableLens)
    val groups = available.groupBy(::opticFingerprint).values.map { routes ->
        val ordered = routes.sortedWith(opticRouteOrder)
        val head = ordered.first()
        val routeText = if (ordered.size == 1) "1 route" else "${ordered.size} routes"
        OpticGroup(
            key = opticFingerprint(head),
            title = head.title,
            subtitle = "$routeText · recommended ID ${head.id}",
            lenses = ordered
        )
    }.sortedWith(compareBy<OpticGroup> { it.recommended.opticalMetric }.thenBy { it.recommended.id })
    if (missing.isEmpty()) return groups
    return groups + OpticGroup(
        key = "unavailable",
        title = "Currently unavailable · ${missing.size}",
        subtitle = "Saved before but not reported now. Keep checked to preserve them, uncheck to forget.",
        lenses = missing.sortedBy { it.id }
    )
}

/** One recommended route ID per optic; the first-run default check set. */
fun recommendedLensIds(lenses: List<DiscoveredLens>): Set<String> =
    groupByOptic(lenses.filterNot(::isUnavailableLens)).map { it.recommended.id }.toSet()

/** Plain-text report of every route for support chats; no second app needed. */
fun lensReport(
    lenses: List<DiscoveredLens>,
    chosenSize: (routeId: String) -> String? = { null },
    chosenFormat: (routeId: String) -> String? = { null }
): String = buildString {
    appendLine("RawLens lens report · ${lenses.size} routes")
    groupByOptic(lenses).forEach { group ->
        appendLine()
        appendLine("${group.title} (${group.lenses.size} route(s))")
        group.lenses.forEach { lens ->
            val badges = capabilityBadges(lens).ifEmpty { listOf("no extras probed") }
            append("- ID ${lens.id} · ${lens.route} · ${lens.details} · ${badges.joinToString(", ")}")
            if (lens.rawSizes.size > 1) append(" · sizes: ${lens.rawSizes.joinToString(", ")}")
            chosenSize(lens.id)?.let { append(" · chosen $it") }
            chosenFormat(lens.id)?.let { append(" · format $it") }
            appendLine()
        }
    }
}
