// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class DevelopQueueOptions(
    val mapper: PhotoToneMapper = PhotoToneMapper.SIGMOID,
    val autoExposure: Boolean = true,
    val exposureEv: Double = 0.0,
    val quality: Int = 100,
    val displayP3: Boolean = false,
    val shoulder: Float = 1f,
    val falseColor: Boolean = true,
    val defringe: Float = 1f
) {
    fun look() = JpegOutputSettings(toneMapper=mapper, jpegQuality=quality, displayP3=displayP3,
        highlightShoulder=shoulder, chromaCleanup=ChromaCleanup(falseColor,defringe), chromaSubsampling=JpegChromaSubsampling.YUV_444)
    fun json() = JSONObject().put("mapper",mapper.name).put("auto",autoExposure).put("ev",exposureEv)
        .put("quality",quality).put("p3",displayP3).put("shoulder",shoulder.toDouble()).put("falseColor",falseColor).put("defringe",defringe.toDouble())
    companion object {
        fun read(j: JSONObject) = DevelopQueueOptions(PhotoToneMapper.fromPreference(j.optString("mapper")),
            j.optBoolean("auto",true),j.optDouble("ev",0.0).takeIf(Double::isFinite)?.coerceIn(-4.0,4.0) ?: 0.0,
            j.optInt("quality",100).coerceIn(1,100),j.optBoolean("p3",false),
            j.optDouble("shoulder",1.0).takeIf(Double::isFinite)?.toFloat()?.coerceIn(0f,1f) ?: 1f, j.optBoolean("falseColor",true),
            j.optDouble("defringe",1.0).takeIf(Double::isFinite)?.toFloat()?.coerceIn(0f,1f) ?: 1f)
    }
}

internal data class DevelopQueueItem(val id: String, val uri: String, val name: String,
    val state: String = "Waiting", val detail: String = "", val output: String? = null,
    val options: DevelopQueueOptions = DevelopQueueOptions())

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
                    j.optString("output").takeIf { it.isNotEmpty() },
                    DevelopQueueOptions.read(j.optJSONObject("options") ?: JSONObject()))
            }.getOrNull() }.toMutableList()
        }
        return items!!.toList()
    }
    private fun save(context: Context) {
        val array=JSONArray()
        items!!.forEach { item -> array.put(JSONObject().put("id",item.id).put("uri",item.uri).put("name",item.name)
            .put("state",item.state).put("detail",item.detail).put("output",item.output ?: "").put("options",item.options.json())) }
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
    @Synchronized fun prepare(context: Context, options: DevelopQueueOptions) {
        snapshot(context)
        items=items!!.map { if(it.state=="Waiting") it.copy(options=options) else it }.toMutableList(); save(context)
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
