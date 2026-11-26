// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.os.Build

/**
 * Built-in lens setups for known phones, ported from rawr's device profiles
 * (github.com/adityawarmanfw/rawr, GPL-3.0, app/src/main/cpp/camera/profiles,
 * revision b6c05a0). Only lenses RawLens can run today are included: plain
 * routes and session sensor-mode keys with fixed levels. vivo's
 * full-size H/F lenses are from revision e01fb01; OPPO's are left out.
 *
 * Applying a profile writes the same per-lens stores the Lenses page edits;
 * a second lens on one camera ID becomes a virtual lens on that route.
 */
internal object DeviceLensProfiles {

    data class Lens(
        /** Switcher name, rawr's lens id ("UW", "D14"). */
        val name: String,
        val cameraId: String,
        /** "RAW10"; null = RAW_SENSOR (rawr's RAW16). */
        val rawFormat: String? = null,
        val rawSize: String? = null,
        /** Session-scope Int32 vendor keys. */
        val sessionKeys: List<Pair<String, Int>> = emptyList(),
        /** Fixed RGGB black and white level; null = the camera's dynamic levels. */
        val black: Double? = null,
        val white: Double? = null,
        val photo: Photo? = null,
    )

    /** rawr's full-size photo stream: the viewfinder stays binned, stills fill a second RAW16 output. */
    data class Photo(
        val width: Int,
        val height: Int,
        /** Photos get a session of their own (X200 Ultra); otherwise a decision frame precedes them (X300 Ultra). */
        val separateSession: Boolean,
        /** Int32 keys on the session and every request. */
        val keys: List<Pair<String, Int>> = emptyList(),
        /** Int32 keys on the still (and, with a decision frame, on that). */
        val stillKeys: List<Pair<String, Int>> = emptyList(),
        val buffers: Int = 4,
        /** Same-colour block of the frame: 1 Bayer, 2 quad, 4 (stored as 2x2 sums at half size). */
        val block: Int = 1,
        /** Camera2 CFA code when the photo's order must be stated. */
        val colourOrder: Int? = null,
    ) {
        val megapixels: Int get() = Math.round(width.toLong() * height / 1e6 / 10).toInt() * 10
        /** 2x2 sums multiply the levels. */
        val levelScale: Double get() = if (block == 4) 4.0 else 1.0

        fun profile(): VendorKeyProfile = VendorKeyProfile(keys.flatMap { (name, value) ->
            VendorKeyScope.entries.map { VendorKeyEntry(name, VendorKeyType.INT, it, value.toString()) }
        })
        fun scoped(scope: VendorKeyScope): VendorKeyProfile = VendorKeyProfile(stillKeys.map { (name, value) ->
            VendorKeyEntry(name, VendorKeyType.INT, scope, value.toString())
        })
    }

    private fun x300Photo(width: Int, height: Int) = Photo(width, height, separateSession = false,
        keys = listOf(
            (if (width > 10000) "vivo.control.ultra_highresolution" else "vivo.control.ai_highresolution") to 1,
            "vivo.control.is_ProRaw_on" to 2,
            "vivo.control.is_pro_mode" to 1,
            "vivo.control.scenery.subfunction" to 5),
        stillKeys = listOf("com.vivo.current_ui_module" to 74),
        buffers = if (width > 10000) 2 else 4)

    private fun x200Photo(width: Int, height: Int, mode: Int, block: Int = 1, order: Int? = null) =
        Photo(width, height, separateSession = true, stillKeys = listOf(FORCE_MODE to mode),
            buffers = if (width > 10000) 2 else 4, block = block, colourOrder = order)

    data class Profile(
        val id: String,
        val name: String,
        val description: String,
        val models: Set<String>,
        val lenses: List<Lens>,
    )

    /** Virtual lenses created by a profile carry this id prefix, so reloading replaces them. */
    const val VIRTUAL_PREFIX = "dp_"

    const val FORCE_MODE = "vivo.control.forceSensorMode"

    val all: List<Profile> = listOf(
        Profile(
            id = "vivo_x300_pro",
            name = "vivo X300 Pro",
            description = "Ultra-wide, main and 3.5× telephoto at full RAW16 size.",
            models = setOf("V2514", "V2502A", "V2502DA"),
            lenses = listOf(
                Lens("UW", "4", rawSize = "4080x3060"),
                Lens("1x", "2", rawSize = "4096x3072"),
                Lens("3.5x", "3", rawSize = "4080x3072"),
            ),
        ),
        Profile(
            id = "vivo_x300_ultra",
            name = "vivo X300 Ultra",
            description = "Sensor modes per lens: L = RAW10 live modes, D = 14-bit DCG RAW16, " +
                "Z = in-sensor zoom, H = 50 MP and F = 200 MP single photos.",
            models = setOf("V2562", "V2547A", "V2547DA"),
            lenses = listOf(
                Lens("L14", "4", "RAW10", "4096x3072", listOf(FORCE_MODE to 17)),
                Lens("D14", "4", null, "4096x3072", listOf(FORCE_MODE to 23), 1024.0, 8712.0),
                Lens("L35", "3", "RAW10", "4080x3064", listOf(FORCE_MODE to 31)),
                Lens("D35", "3", null, "4080x3064", listOf(FORCE_MODE to 17), 1024.0, 8712.0),
                Lens("L85", "5", "RAW10", "4080x3072", listOf(FORCE_MODE to 23)),
                Lens("D85", "5", null, "4080x3072", listOf(FORCE_MODE to 19), 1024.0, 16383.0),
                Lens("Z170", "5", "RAW10", "4080x3072", listOf(FORCE_MODE to 31), 64.0, 1023.0),
                Lens("Z340", "5", "RAW10", "4080x3072", listOf(FORCE_MODE to 6), 64.0, 1023.0),
                Lens("H14", "4", null, "4096x3072", emptyList(), 64.0, 1023.0, x300Photo(8192, 6144)),
                Lens("H35", "3", null, "4080x3064", emptyList(), 64.0, 1023.0, x300Photo(8192, 6144)),
                Lens("H85", "5", null, "4080x3072", emptyList(), 64.0, 1023.0, x300Photo(8192, 6144)),
                Lens("F35", "3", null, "4080x3064", emptyList(), 64.0, 1023.0, x300Photo(16320, 12256)),
                Lens("F85", "5", null, "4080x3072", emptyList(), 64.0, 1023.0, x300Photo(16320, 12288)),
            ),
        ),
        Profile(
            id = "vivo_x200_ultra",
            name = "vivo X200 Ultra",
            description = "Standard lenses, H = 50 MP and F = 200 MP sensor mode saved at 50 MP, single photos.",
            models = setOf("V2454A", "V2454", "V2454DA"),
            lenses = listOf(
                Lens("14", "4", null, null, emptyList(), 64.0, 1023.0),
                Lens("35", "3", null, "4096x3072", emptyList(), 64.0, 1023.0),
                Lens("85", "5", null, "4080x3072", emptyList(), 64.0, 1023.0),
                Lens("H14", "4", null, null, emptyList(), 64.0, 1023.0, x200Photo(8192, 6144, 0)),
                Lens("H35", "3", null, "4080x3064", emptyList(), 64.0, 1023.0, x200Photo(8192, 6144, 13)),
                Lens("H85", "5", null, "4080x3072", photo = x200Photo(8160, 6144, 1, block = 2, order = 3)),
                Lens("F85", "5", null, "4080x3072", emptyList(), 64.0, 1023.0,
                    x200Photo(16320, 12288, 0, block = 4, order = 3)),
                Lens("SELF", "1"),
            ),
        ),
        Profile(
            id = "oppo_x9u_standard",
            name = "OPPO Find X9 Ultra",
            description = "Standard lenses, RAW10. No root needed. rawr's 50/200 MP H and F lenses " +
                "are not included yet.",
            models = setOf("CPH2841", "PMA110", "PMA120"),
            lenses = listOf(
                Lens("0.6x", "3", "RAW10", "4096x3072"),
                Lens("1x", "2", "RAW10", "4080x3064"),
                Lens("3x", "4", "RAW10", "4096x3072"),
                Lens("10x", "5", "RAW10", "4096x3072"),
            ),
        ),
    )

    /** Profiles made for this phone model. */
    fun matching(model: String = Build.MODEL): List<Profile> =
        all.filter { model.trim() in it.models }

    /** The mode-selected stream can be absent from the default Camera2 map. */
    fun vivoUltraStream(
        cameraId: String?, key: String?, vendor: VendorKeyProfile, model: String = Build.MODEL
    ): Lens? {
        val profile = matching(model).firstOrNull { it.id == "vivo_x300_ultra" } ?: return null
        return plan(profile).firstOrNull { (lens, profileKey) ->
            lens.cameraId == cameraId && profileKey == key && vendor.sessionEntries().any {
                it.name == FORCE_MODE && it.type == VendorKeyType.INT &&
                    it.value.trim().toIntOrNull() == lens.sessionKeys.singleOrNull()?.second && lens.sessionKeys.isNotEmpty()
            }
        }?.first
    }

    /**
     * Full-size lens for a route: the profile's own H/F lens, or the one a plain
     * [customSize] on the same camera asks for (its keys then come along by themselves).
     */
    fun photoLens(cameraId: String?, key: String?, customSize: String?, model: String = Build.MODEL): Lens? {
        val lenses = matching(model).flatMap(::plan).filter { (lens, _) -> lens.photo != null && lens.cameraId == cameraId }
        lenses.firstOrNull { it.second == key }?.let { return it.first }
        val (w, h) = customSize?.split('x')?.mapNotNull(String::toLongOrNull)?.takeIf { it.size == 2 } ?: return null
        return lenses.map { it.first }.firstOrNull { lens ->
            val pixels = lens.photo!!.width.toLong() * lens.photo.height
            w * h in pixels * 9 / 10..pixels * 11 / 10
        }
    }

    /**
     * Settings key per lens: the first lens on a camera ID uses the route
     * itself, later ones a profile virtual lens on it. Pure, unit-tested.
     */
    fun plan(profile: Profile): List<Pair<Lens, String>> {
        val seen = mutableSetOf<String>()
        return profile.lenses.map { lens ->
            val key = if (seen.add(lens.cameraId)) lens.cameraId
                else VirtualLens.settingsKeyOf(virtualId(profile, lens))
            lens to key
        }
    }

    fun virtualId(profile: Profile, lens: Lens) = "$VIRTUAL_PREFIX${profile.id}_${lens.name}"

    /** Writes [profile] into the lens stores. The caller reloads the camera. */
    fun apply(context: Context, profile: Profile) {
        val virtuals = VirtualLensStore(context)
        virtuals.all().filter { it.id.startsWith(VIRTUAL_PREFIX) }.forEach { virtuals.delete(it.id) }
        val vendor = VendorKeyStore(context)
        val sizes = RawSizePreferenceStore(context)
        val formats = RawFormatPreferenceStore(context)
        val levels = DngMetadataOverrideStore(context)
        val names = LensListStore(context)
        plan(profile).forEach { (lens, key) ->
            if (key != lens.cameraId) {
                virtuals.save(VirtualLens(virtualId(profile, lens), lens.cameraId,
                    zoom = lens.name.take(VirtualLens.MAX_ZOOM), tag = "",
                    description = "${profile.name} · ${lens.name}"))
            } else {
                names.setName(lens.cameraId, lens.name)
            }
            sizes.set(key, lens.rawSize)
            formats.set(key, lens.rawFormat)
            if (lens.sessionKeys.isEmpty()) vendor.clear(key)
            else vendor.save(key, VendorKeyProfile(lens.sessionKeys.map { (tag, value) ->
                VendorKeyEntry(tag, VendorKeyType.INT, VendorKeyScope.SESSION, value.toString())
            }))
            val current = levels.get(key)
            levels.save(key, current.copy(
                blackLevels = lens.black?.let { b -> List(4) { b } },
                whiteLevel = lens.white,
            ))
        }
        val routes = profile.lenses.map { it.cameraId }.distinct()
        names.setOrder(routes)
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putStringSet(MainActivity.KEY_SELECTED_LENSES, routes.toSet())
            .putBoolean(MainActivity.KEY_LENS_SETUP_COMPLETE, true)
            .apply()
    }
}
