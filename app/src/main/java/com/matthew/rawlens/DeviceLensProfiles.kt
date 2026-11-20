// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.os.Build

/**
 * Built-in lens setups for known phones, ported from rawr's device profiles
 * (github.com/adityawarmanfw/rawr, GPL-3.0, app/src/main/cpp/camera/profiles,
 * revision b6c05a0). Only lenses RawLens can run today are included: plain
 * routes and session sensor-mode keys with fixed levels. rawr's high-res
 * photo-stream lenses (vivo H/F, OPPO H/1xF/3xF) need a separate full-size
 * stills stream and are left out.
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
    )

    data class Profile(
        val id: String,
        val name: String,
        val description: String,
        val models: Set<String>,
        val lenses: List<Lens>,
    )

    /** Virtual lenses created by a profile carry this id prefix, so reloading replaces them. */
    const val VIRTUAL_PREFIX = "dp_"

    private const val FORCE_MODE = "vivo.control.forceSensorMode"

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
                "Z = in-sensor zoom. High-res H/F lenses from rawr are not included yet.",
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
