package com.atakwatch.minimap.data

import android.content.Context
import com.atakwatch.minimap.model.CotEvent
import com.atakwatch.minimap.model.CotType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Tiny JSON-file persistence for user waypoints (survives app restarts). */
object WaypointStore {
    private const val FILE = "waypoints.json"

    fun load(context: Context): List<CotEvent> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CotEvent(
                    uid = o.getString("uid"),
                    callsign = o.getString("callsign"),
                    type = CotType(o.optString("type", "b-m-p-w")),
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon"),
                    staleMillis = Long.MAX_VALUE,
                )
            }
        }.getOrElse { emptyList() }
    }

    fun save(context: Context, list: List<CotEvent>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("uid", e.uid).put("callsign", e.callsign)
                    .put("type", e.type.raw).put("lat", e.lat).put("lon", e.lon)
            )
        }
        runCatching { File(context.filesDir, FILE).writeText(arr.toString()) }
    }
}
