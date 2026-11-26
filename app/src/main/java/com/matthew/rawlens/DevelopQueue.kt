// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class DevelopQueueItem(val id: String, val uri: String, val name: String,
    val state: String = "Waiting", val detail: String = "", val output: String? = null)

/**
 * The develop queue's own JPEG look. Settings → Photo edits the camera look;
 * the queue keeps a separate copy in its own preferences, seeded from the
 * camera once and changed only from the queue. Export reads it when each photo
 * starts, so waiting photos all use the look that was set when export began.
 */
internal object DevelopQueueLook {
    private const val PREFS = "develop_queue_look"
    private const val KEY_EXPOSURE = "queue_exposure_ev"
    private const val KEY_SEEDED = "queue_seeded"

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Every key the JPEG look reads: tone, AgX, Sigmoid/ACES/Basic, OpenDRT, colour, highlights, Active D-Lighting. */
    private fun isLookKey(key: String) = key.startsWith("jpeg_") || key.startsWith("tone_") ||
        key.startsWith("odrt_") || key == ActiveDLighting.PREFERENCE_KEY

    /** Replace the queue's look with the camera's current look. Queue-only values such as exposure are kept. */
    fun copyFromCamera(context: Context) {
        val camera = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val target = prefs(context)
        val edit = target.edit()
        target.all.keys.filter(::isLookKey).forEach(edit::remove)
        camera.all.forEach { (key, value) ->
            if (!isLookKey(key)) return@forEach
            when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is String -> edit.putString(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        edit.putBoolean(KEY_SEEDED, true)
        check(edit.commit()) { "Could not save develop queue look" }
    }

    /** First use starts from the camera look, so the queue matches the photos you already see. */
    fun ensureSeeded(context: Context) {
        if (!prefs(context).getBoolean(KEY_SEEDED, false)) copyFromCamera(context)
    }

    /** The queue's JPEG output settings (no mono or portrait: those are camera capture modes). */
    fun settings(context: Context): JpegOutputSettings = MergeScreenSettings.jpeg(prefs(context))

    fun exposureEv(context: Context): Double =
        prefs(context).getFloat(KEY_EXPOSURE, 0f).toDouble().takeIf(Double::isFinite)?.coerceIn(-4.0, 4.0) ?: 0.0

    fun setExposureEv(context: Context, ev: Double) {
        prefs(context).edit().putFloat(KEY_EXPOSURE, ev.coerceIn(-4.0, 4.0).toFloat()).apply()
    }
}

/** Persisted source grants and job results. Processing never depends on an Activity instance. */
internal object DevelopQueue {
    private var items: MutableList<DevelopQueueItem>? = null
    private fun prefs(context: Context) = context.getSharedPreferences("develop_queue",Context.MODE_PRIVATE)
    @Synchronized fun snapshot(context: Context): List<DevelopQueueItem> {
        if (items == null) {
            val array = runCatching { JSONArray(prefs(context).getString("items","[]")) }.getOrElse { JSONArray() }
            items = (0 until array.length()).mapNotNull { i -> runCatching {
                val j=array.getJSONObject(i)
                val interrupted=j.optString("state") == "Developing"
                DevelopQueueItem(j.getString("id"),j.getString("uri"),j.getString("name"),
                    if(interrupted) "Failed" else j.optString("state","Waiting"),
                    if(interrupted) "Interrupted. Check exports before retrying." else j.optString("detail"),
                    j.optString("output").takeIf { it.isNotEmpty() })
            }.getOrNull() }.toMutableList()
        }
        return items!!.toList()
    }
    private fun save(context: Context) {
        val array=JSONArray()
        items!!.forEach { item -> array.put(JSONObject().put("id",item.id).put("uri",item.uri).put("name",item.name)
            .put("state",item.state).put("detail",item.detail).put("output",item.output ?: "")) }
        check(prefs(context).edit().putString("items",array.toString()).commit()) { "Could not save development queue" }
    }
    @Synchronized fun add(context: Context, sources: List<Pair<String,String>>): Int {
        snapshot(context); var added=0
        sources.forEach { (uri,name) ->
            if(items!!.none { it.uri==uri && it.state in listOf("Waiting","Developing") } && items!!.size < 200) {
                items!!.add(DevelopQueueItem(UUID.randomUUID().toString(),uri,name)); added++
            }
        }
        save(context); return added
    }
    @Synchronized fun update(context: Context, item: DevelopQueueItem) {
        snapshot(context); val index=items!!.indexOfFirst { it.id==item.id }
        if(index>=0) items!![index]=item
        save(context)
    }
    @Synchronized fun claimNext(context: Context): DevelopQueueItem? {
        snapshot(context)
        val index=items!!.indexOfFirst { it.state=="Waiting" }
        if(index<0) return null
        val item=items!![index].copy(state="Developing",detail="Waiting for photo processor…")
        items!![index]=item;save(context);return item
    }
    @Synchronized fun remove(context: Context,id: String) {
        snapshot(context);items!!.removeAll { it.id==id && it.state!="Developing" };save(context)
    }
    @Synchronized fun move(context: Context,id: String, delta: Int) {
        snapshot(context); val index=items!!.indexOfFirst { it.id==id }
        val target=index+delta
        if(index>=0 && target in items!!.indices && items!![index].state=="Waiting" && items!![target].state=="Waiting") {
            java.util.Collections.swap(items!!,index,target);save(context)
        }
    }
    @Synchronized fun clearCompleted(context: Context) {
        snapshot(context);items!!.removeAll { it.state=="Exported" };save(context)
    }
}
