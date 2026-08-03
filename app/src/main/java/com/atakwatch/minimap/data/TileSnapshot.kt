package com.atakwatch.minimap.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Last known status, persisted for the tile.
 *
 * A tile is rendered by the system on its own schedule — often when the app has
 * not run for a while and the in-memory [CotRepository] is empty. So whatever
 * the tile shows has to survive on disk, and it has to be honest about *when*
 * it was true: a stale position presented as current is worse than no position.
 */
data class TileSnapshot(
    val callsign: String = "—",
    val coordinate: String? = null,
    val accuracyMeters: Double? = null,
    val nearestCallsign: String? = null,
    val nearestRange: String? = null,
    val nearestBearing: String? = null,
    val contactCount: Int = 0,
    val updatedAt: Long = 0L,
) {
    companion object {
        private const val FILE = "tile_snapshot.json"

        fun load(context: Context): TileSnapshot = runCatching {
            val f = File(context.filesDir, FILE)
            if (!f.exists()) return TileSnapshot()
            val o = JSONObject(f.readText())
            TileSnapshot(
                callsign = o.optString("callsign", "—"),
                coordinate = o.optString("coordinate").takeIf { it.isNotBlank() },
                accuracyMeters = if (o.has("accuracy")) o.optDouble("accuracy") else null,
                nearestCallsign = o.optString("nearCall").takeIf { it.isNotBlank() },
                nearestRange = o.optString("nearRange").takeIf { it.isNotBlank() },
                nearestBearing = o.optString("nearBearing").takeIf { it.isNotBlank() },
                contactCount = o.optInt("contacts", 0),
                updatedAt = o.optLong("updatedAt", 0L),
            )
        }.getOrElse { TileSnapshot() }

        fun save(context: Context, snapshot: TileSnapshot) {
            runCatching {
                val o = JSONObject()
                    .put("callsign", snapshot.callsign)
                    .put("contacts", snapshot.contactCount)
                    .put("updatedAt", snapshot.updatedAt)
                snapshot.coordinate?.let { o.put("coordinate", it) }
                snapshot.accuracyMeters?.let { o.put("accuracy", it) }
                snapshot.nearestCallsign?.let { o.put("nearCall", it) }
                snapshot.nearestRange?.let { o.put("nearRange", it) }
                snapshot.nearestBearing?.let { o.put("nearBearing", it) }
                File(context.filesDir, FILE).writeText(o.toString())
            }
        }
    }
}
