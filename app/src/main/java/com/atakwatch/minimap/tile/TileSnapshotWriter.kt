package com.atakwatch.minimap.tile

import android.content.Context
import androidx.wear.tiles.TileService
import com.atakwatch.minimap.data.CoordFormat
import com.atakwatch.minimap.data.CotRepository
import com.atakwatch.minimap.data.TileSnapshot
import com.atakwatch.minimap.map.CoordinateFormatter
import com.atakwatch.minimap.model.Geo

/**
 * Keeps the tile's on-disk snapshot current.
 *
 * Writes are throttled: the tile is only redrawn every couple of minutes, so
 * persisting on every GPS fix would be pure I/O for no visible benefit.
 */
object TileSnapshotWriter {

    private const val MIN_WRITE_INTERVAL_MS = 20_000L
    private var lastWrite = 0L

    fun update(context: Context, coordFormat: CoordFormat, imperial: Boolean, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastWrite < MIN_WRITE_INTERVAL_MS) return

        val self = CotRepository.self.value ?: return
        lastWrite = now

        val entities = CotRepository.entities.value
        val nearest = entities.minByOrNull { Geo.distanceMeters(self.lat, self.lon, it.lat, it.lon) }

        TileSnapshot.save(
            context,
            TileSnapshot(
                callsign = self.callsign,
                coordinate = CoordinateFormatter.format(self.lat, self.lon, coordFormat),
                accuracyMeters = self.ce.takeIf { it < 9_999_999.0 },
                nearestCallsign = nearest?.callsign,
                nearestRange = nearest?.let {
                    Geo.formatRange(Geo.distanceMeters(self.lat, self.lon, it.lat, it.lon), imperial)
                },
                nearestBearing = nearest?.let {
                    Geo.formatBearing(Geo.bearingDegrees(self.lat, self.lon, it.lat, it.lon))
                },
                contactCount = entities.size,
                updatedAt = now,
            ),
        )

        // Ask the system to redraw sooner than its own schedule would.
        runCatching { TileService.getUpdater(context).requestUpdate(StatusTileService::class.java) }
    }
}
