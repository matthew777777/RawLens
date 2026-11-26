// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.os.SystemClock
import android.util.Size
import java.lang.reflect.Method

/**
 * vivo's high-resolution permission: VivoCameraManager key 40 (useVivoCamera) = 1 makes
 * cameraserver treat the app as vivo's camera, which unlocks the 50/200 MP sensor modes;
 * key 33 (VCFAppType) = 2 is the optional VCF bypass. Recipe: PhotonVidCam 99c10faf and
 * rawr VendorCameraGate.kt (e01fb01). Held from before the device opens until after it closes:
 * the HAL rebuilds its still pipeline per full-size request and crashes if the gate is gone.
 *
 * The values are system-wide, so those found are journaled and given back; a journal left by
 * a killed process is given back on the next open.
 */
internal class VivoCameraGate(
    private val store: Store,
    private val journal: Journal,
    private val settle: () -> Unit = { SystemClock.sleep(SETTLE_MS) },
) {
    enum class Mode(val label: String) { OFF("Off"), ON("On"), ON_VCF("On + VCF bypass") }

    interface Store {
        fun get(key: Int): Int
        fun set(key: Int, value: Int)
    }

    /** Values found before our change: useVivoCamera and, when changed, VCFAppType. */
    interface Journal {
        fun read(): Pair<Int, Int?>?
        fun write(useVivoCamera: Int, vcf: Int?)
        fun clear()
    }

    /** Never throws; false leaves the vendor values as found. */
    @Synchronized fun acquire(vcf: Boolean): Boolean = try {
        // Gives back what a previous hold (or a killed process) left.
        release()
        journal.write(store.get(KEY), if (vcf) store.get(VCF_KEY) else null)
        if (vcf) store.set(VCF_KEY, VCF_OURS)
        store.set(KEY, OURS)
        settle() // setValue is a one-way Binder call.
        val held = store.get(KEY) == OURS && (!vcf || store.get(VCF_KEY) == VCF_OURS)
        Log.i(TAG, "vivo gate acquire vcf=$vcf held=$held ${describe()}")
        if (!held) release()
        held
    } catch (failure: Exception) {
        Log.w(TAG, "vivo gate refused", failure)
        release()
        false
    }

    /** No-op unless held. Keeps the journal for the next open when the restore fails. */
    @Synchronized fun release() {
        val (key, vcf) = journal.read() ?: return
        try {
            vcf?.let { store.set(VCF_KEY, it) }
            store.set(KEY, key)
            journal.clear()
            Log.i(TAG, "vivo gate released")
        } catch (failure: Exception) {
            Log.w(TAG, "vivo gate not restored", failure)
        }
    }

    private fun describe() = listOf(APP_TYPE_KEY, VCF_KEY, KEY).joinToString(" ") { key ->
        "key$key=" + (runCatching { store.get(key).toString() }.getOrNull() ?: "?")
    }

    companion object {
        const val KEY = 40
        const val OURS = 1
        const val VCF_KEY = 33
        const val VCF_OURS = 2
        private const val APP_TYPE_KEY = 1
        private const val SETTLE_MS = 100L
        private const val TAG = "RawLensVivoGate"
        private const val PREFS = "vivo_camera_gate"

        private val reflected: Store? by lazy {
            try {
                val api = Class.forName("android.hardware.vivocamera.VivoCameraManager")
                val int = Int::class.javaPrimitiveType
                ReflectionStore(
                    api.getMethod("getInstance").invoke(null)!!,
                    api.getMethod("getInfo", int),
                    api.getMethod("setValue", int, int),
                )
            } catch (_: Exception) {
                null
            } catch (_: LinkageError) {
                null
            }
        }

        /** Only vivo firmware has the manager. */
        fun supported(): Boolean = reflected != null

        fun create(context: Context): VivoCameraGate? =
            reflected?.let { VivoCameraGate(it, PrefsJournal(context)) }

        fun mode(context: Context): Mode = if (!supported()) Mode.OFF else
            Mode.entries.firstOrNull { it.name == prefs(context).getString("mode", null) } ?: Mode.ON

        fun setMode(context: Context, mode: Mode) =
            prefs(context).edit().putString("mode", mode.name).apply()

        /** Sizes that need the gate and fill only single stills. */
        fun stillOnly(context: Context, size: Size?): Boolean = mode(context) != Mode.OFF &&
            size != null && size.width.toLong() * size.height >= 47_000_000L

        /**
         * Ids vivo's camera sends with every still; a full-size still without them crashes the
         * provider (rawr VendorGatePreset.h).
         */
        fun stillKeys(stampMs: Long): VendorKeyProfile = VendorKeyProfile(listOf(
            "vivo.control.requestId" to stampMs,
            "vivo.control.frameFlag" to stampMs,
            "vivo.control.globalCaptureId" to stampMs + 5,
        ).map { (name, value) ->
            VendorKeyEntry(name, VendorKeyType.LONG, VendorKeyScope.REQUEST, value.toString())
        })

        /** rawr vivo_x300_ultra.json, 50 MP H lenses. */
        val highResKeys: VendorKeyProfile = VendorKeyProfile(listOf(
            "vivo.control.ai_highresolution" to 1,
            "vivo.control.is_ProRaw_on" to 2,
            "vivo.control.is_pro_mode" to 1,
            "vivo.control.scenery.subfunction" to 5,
        ).flatMap { (name, value) ->
            VendorKeyScope.entries.map { VendorKeyEntry(name, VendorKeyType.INT, it, value.toString()) }
        })

        val uiModule: VendorKeyProfile = VendorKeyProfile(listOf(
            VendorKeyEntry("com.vivo.current_ui_module", VendorKeyType.INT, VendorKeyScope.REQUEST, "74")))

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private class ReflectionStore(
        private val manager: Any, private val getInfo: Method, private val setValue: Method
    ) : Store {
        override fun get(key: Int) = getInfo.invoke(manager, key) as Int
        override fun set(key: Int, value: Int) {
            setValue.invoke(manager, key, value)
        }
    }

    private class PrefsJournal(context: Context) : Journal {
        private val prefs = context.applicationContext.getSharedPreferences("vivo_camera_gate_journal", Context.MODE_PRIVATE)
        override fun read(): Pair<Int, Int?>? = if (!prefs.contains("use")) null else
            prefs.getInt("use", 0) to (if (prefs.contains("vcf")) prefs.getInt("vcf", 0) else null)

        // commit(): the process may die right after the change.
        override fun write(useVivoCamera: Int, vcf: Int?) {
            prefs.edit().clear().putInt("use", useVivoCamera)
                .apply { vcf?.let { putInt("vcf", it) } }.commit()
        }

        override fun clear() {
            prefs.edit().clear().commit()
        }
    }
}
