// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
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
    val route: String = ""
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

    fun discover(onComplete: (List<DiscoveredLens>) -> Unit) {
        executor.execute {
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
                characteristics(id)?.physicalCameraIds?.forEach { physicalId ->
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
                if (isRearRaw(c)) found[id] = discovered(id, c, "standalone")
            }

            // B) Every logical -> physical relationship advertised by a queryable camera,
            // including hidden logical cameras omitted from cameraIdList.
            // The relationship itself is authoritative; the synthetic display identity need not
            // be accepted by getCameraCharacteristics().
            queryableIds.forEach { logicalId ->
                val logical = characteristics(logicalId) ?: return@forEach
                val physicalIds = logical.physicalCameraIds
                if (physicalIds.isEmpty()) return@forEach

                physicalIds.forEach physicalLoop@ { physicalId ->
                    // API 29+ (RawLens minSdk) explicitly permits querying characteristics
                    // for physical IDs that are hidden from cameraIdList.
                    val physical = characteristics(physicalId) ?: return@physicalLoop
                    if (!isRearRaw(physical, logical)) return@physicalLoop
                    val routeId = "$logicalId/$physicalId"
                    found[routeId] = discovered(routeId, physical, "logical $logicalId → physical $physicalId")
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
                val compositeCharacteristics = characteristics(composite) ?: return@forEach
                val physicalId = composite.substringAfterLast('/', composite.substringAfterLast('-'))
                val logicalId = composite.substringBefore('/').substringBefore('-')
                val logical = characteristics(logicalId)
                val usable = when {
                    isRearRaw(compositeCharacteristics, logical) -> compositeCharacteristics
                    else -> characteristics(physicalId)?.takeIf { isRearRaw(it, logical) }
                } ?: return@forEach
                found[composite] = discovered(composite, usable, "vendor route")
            }

            Log.i(LOG_TAG, "Discovered RAW lenses: ${found.keys}")
            onComplete(found.values.sortedWith(compareBy<DiscoveredLens> { it.opticalMetric }.thenBy { it.id }))
        }
    }

    fun close() = executor.shutdownNow()

    private fun cameraIds(): List<String> = try {
        manager.cameraIdList.toList()
    } catch (_: CameraAccessException) {
        emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }

    private fun characteristics(id: String): CameraCharacteristics? = try {
        manager.getCameraCharacteristics(id)
    } catch (_: CameraAccessException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        // Vendor camera services occasionally throw non-standard runtime failures for hidden IDs.
        null
    }

    private fun isRearRaw(
        c: CameraCharacteristics,
        logicalFallback: CameraCharacteristics? = null
    ): Boolean {
        val facing = c.get(CameraCharacteristics.LENS_FACING)
            ?: logicalFallback?.get(CameraCharacteristics.LENS_FACING)
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) return false

        val capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        if (capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) return true

        // Some physical-camera characteristic blocks omit the RAW capability bit even though the
        // physical stream configuration explicitly exposes RAW_SENSOR. Accept that authoritative
        // stream declaration as well.
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        return map?.outputFormats?.contains(ImageFormat.RAW_SENSOR) == true
    }

    private fun discovered(id: String, c: CameraCharacteristics, route: String): DiscoveredLens {
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val equivalent = if (focal != null && sensor != null && sensor.width > 0f) {
            36f * focal / sensor.width
        } else null
        val megapixels = if (pixels != null) pixels.width * pixels.height / 1_000_000f else null
        val resolutionLabel = pixels?.let { "${it.width}×${it.height}" }
        val role = LensRole.forEquivalentMm(equivalent)
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
        val details = buildList {
            if (focal != null) add(String.format(Locale.US, "%.2f mm", focal))
            if (equivalent != null) add(String.format(Locale.US, "≈%.0f mm eq", equivalent))
            if (resolutionLabel != null) add(resolutionLabel)
            if (megapixels != null) add(String.format(Locale.US, "%.1f MP", megapixels))
        }.joinToString(" · ").ifEmpty { "Characteristics unavailable" }
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
            route = routeText
        )
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

    private fun summary(c: CameraCharacteristics): String {
        val capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val rawCapability = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val rawStream = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.outputFormats?.contains(ImageFormat.RAW_SENSOR) == true
        return "facing=${c.get(CameraCharacteristics.LENS_FACING)} rawCapability=$rawCapability " +
            "rawStream=$rawStream physical=${c.physicalCameraIds}"
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
