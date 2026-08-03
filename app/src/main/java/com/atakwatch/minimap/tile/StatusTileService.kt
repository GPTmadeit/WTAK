package com.atakwatch.minimap.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.Layout
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.atakwatch.minimap.MainActivity
import com.atakwatch.minimap.data.TileSnapshot
import com.atakwatch.minimap.model.Geo
import androidx.concurrent.futures.ResolvableFuture
import com.google.common.util.concurrent.ListenableFuture

/**
 * Glanceable status, one swipe from the watch face — your callsign, position,
 * fix quality and nearest contact without opening the app.
 *
 * Everything shown comes from [TileSnapshot] on disk, because the system renders
 * tiles on its own schedule and the app process is frequently not alive. The age
 * of that snapshot is always displayed: on a tactical device, a position you
 * can't date is a position you can't use.
 */
class StatusTileService : TileService() {

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> =
        ResolvableFuture.create<ResourceBuilders.Resources>().apply {
            set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())
        }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        val snapshot = TileSnapshot.load(this)

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            // Refresh roughly every couple of minutes; the app also asks for an
            // immediate update when the position changes meaningfully.
            .setFreshnessIntervalMillis(120_000)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                Layout.Builder().setRoot(layout(snapshot)).build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        return ResolvableFuture.create<TileBuilders.Tile>().apply { set(tile) }
    }

    private fun layout(s: TileSnapshot): LayoutElementBuilders.LayoutElement {
        val stale = s.updatedAt > 0 && System.currentTimeMillis() - s.updatedAt > STALE_AFTER_MS
        val age = if (s.updatedAt > 0) Geo.formatAge(System.currentTimeMillis() - s.updatedAt) else null

        val column = Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    // Tapping anywhere opens the map.
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId("open")
                            .setOnClick(
                                ActionBuilders.LaunchAction.Builder()
                                    .setAndroidActivity(
                                        ActionBuilders.AndroidActivity.Builder()
                                            .setPackageName(packageName)
                                            .setClassName(MainActivity::class.java.name)
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )

        // Callsign, in the team accent.
        column.addContent(
            Text.Builder(this, s.callsign)
                .setTypography(Typography.TYPOGRAPHY_TITLE3)
                .setColor(argb(COLOR_ACCENT))
                .build()
        )

        // Position, or an honest absence of one.
        column.addContent(
            Text.Builder(this, s.coordinate ?: "NO FIX")
                .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                .setColor(argb(if (s.coordinate == null) COLOR_BAD else COLOR_TEXT))
                .setMaxLines(2)
                .build()
        )

        // Fix quality plus how old this reading is.
        val quality = buildString {
            s.accuracyMeters?.let { append("±%.0fm".format(it)) }
            if (age != null) {
                if (isNotEmpty()) append("  ")
                append(age)
            }
        }
        if (quality.isNotEmpty()) {
            column.addContent(Spacer.Builder().setHeight(dp(2f)).build())
            column.addContent(
                Text.Builder(this, quality)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                    .setColor(argb(if (stale) COLOR_BAD else COLOR_DIM))
                    .build()
            )
        }

        // Nearest contact, when there is one.
        if (s.nearestCallsign != null) {
            column.addContent(Spacer.Builder().setHeight(dp(6f)).build())
            column.addContent(
                Text.Builder(this, s.nearestCallsign)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                    .setColor(argb(COLOR_TEXT))
                    .build()
            )
            val rb = listOfNotNull(s.nearestRange, s.nearestBearing).joinToString("  ")
            if (rb.isNotEmpty()) {
                column.addContent(
                    Text.Builder(this, rb)
                        .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                        .setColor(argb(COLOR_DIM))
                        .build()
                )
            }
        } else if (s.contactCount == 0) {
            column.addContent(Spacer.Builder().setHeight(dp(6f)).build())
            column.addContent(
                Text.Builder(this, "No contacts")
                    .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                    .setColor(argb(COLOR_DIM))
                    .build()
            )
        }

        return column.build()
    }

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val STALE_AFTER_MS = 120_000L

        // Matches the app: team cyan, blue-force text, MIL-STD hostile red.
        const val COLOR_ACCENT = 0xFF00E0C0.toInt()
        const val COLOR_TEXT = 0xFFE4EDF4.toInt()
        const val COLOR_DIM = 0xFF8C9BA8.toInt()
        const val COLOR_BAD = 0xFFE23B3B.toInt()
    }
}
