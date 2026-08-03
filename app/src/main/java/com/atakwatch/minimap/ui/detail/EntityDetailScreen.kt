package com.atakwatch.minimap.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import android.app.RemoteInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.atakwatch.minimap.data.CotRepository
import com.atakwatch.minimap.map.CoordinateFormatter
import com.atakwatch.minimap.model.CotEvent
import com.atakwatch.minimap.model.Geo
import com.atakwatch.minimap.ui.Routes
import com.atakwatch.minimap.ui.collectSettings
import com.atakwatch.minimap.ui.components.AffiliationDot
import com.atakwatch.minimap.ui.components.RotaryScalingLazyColumn
import androidx.wear.input.RemoteInputIntentHelper
import com.atakwatch.minimap.ui.map.entityColor

private const val RENAME_KEY = "waypoint_name"

/**
 * Full detail for one entity: everything the CoT event actually carries, plus
 * the two actions that matter on a watch — go to it, or (for your own
 * waypoints) delete it.
 */
@Composable
fun EntityDetailScreen(nav: NavController, uid: String) {
    val context = LocalContext.current
    val self by CotRepository.self.collectAsStateWithLifecycle()
    val entities by CotRepository.entities.collectAsStateWithLifecycle()
    val navTargetUid by CotRepository.navTargetUid.collectAsStateWithLifecycle()
    val settings by collectSettings()

    // Wear text/voice entry for renaming a waypoint.
    val renameLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data
            ?.let { RemoteInput.getResultsFromIntent(it) }
            ?.getCharSequence(RENAME_KEY)
            ?.toString()
            ?.let { CotRepository.renameWaypoint(context, uid, it) }
    }

    val event: CotEvent? = if (self?.uid == uid) self else entities.firstOrNull { it.uid == uid }

    if (event == null) {
        // The entity aged out or was deleted while we were looking at it.
        RotaryScalingLazyColumn {
            item { ListHeader { Text("Not available") } }
            item {
                Text(
                    "This contact is no longer being reported.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        return
    }

    val now = System.currentTimeMillis()
    val rows = buildList {
        add("Type" to event.type.raw)
        add("Domain" to event.type.dimensionLabel)
        if (!event.type.isWaypoint) add("Affiliation" to event.affiliation.label)
        event.teamName?.let { add("Team" to it) }
        event.teamRole?.let { add("Role" to it) }
        add("Position" to CoordinateFormatter.format(event.lat, event.lon, settings.coordFormat))
        if (event.hae != 0.0) add("Altitude" to "%.0f m HAE".format(event.hae))
        self?.takeIf { it.uid != event.uid }?.let { s ->
            val d = Geo.distanceMeters(s.lat, s.lon, event.lat, event.lon)
            val b = Geo.bearingDegrees(s.lat, s.lon, event.lat, event.lon)
            add("Range" to Geo.formatRange(d, settings.imperialUnits))
            add("Bearing" to Geo.formatBearing(b))
        }
        if (event.ce < 9_999_999.0) add("Accuracy" to "±%.0f m".format(event.ce))
        event.battery?.let { add("Battery" to "$it%") }
        event.speed?.let { if (it > 0.1) add("Speed" to "%.1f m/s".format(it)) }
        if (!event.isSelf && event.staleMillis != Long.MAX_VALUE) {
            add("Age" to Geo.formatAge(now - event.timeMillis))
        }
        add("UID" to event.uid)
    }

    RotaryScalingLazyColumn {
        item {
            ListHeader {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AffiliationDot(entityColor(event), size = 12)
                    Spacer(Modifier.width(8.dp))
                    Text(event.callsign)
                }
            }
        }

        items@ for (row in rows) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        row.first,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        row.second,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }

        item {
            Button(
                onClick = {
                    CotRepository.requestFocus(event.lat, event.lon)
                    nav.popBackStack(Routes.MAP, inclusive = false)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.width(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Show on map")
            }
        }

        // Bloodhound: keep range and bearing to this entity on the map until
        // you arrive (or stop). Not offered for yourself.
        if (!event.isSelf) {
            item {
                val isTarget = navTargetUid == event.uid
                Button(
                    onClick = {
                        CotRepository.setNavTarget(if (isTarget) null else event.uid)
                        if (!isTarget) nav.popBackStack(Routes.MAP, inclusive = false)
                    },
                    colors = if (isTarget) ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) else ButtonDefaults.buttonColors(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        if (isTarget) Icons.Filled.Close else Icons.Filled.NearMe,
                        contentDescription = null, modifier = Modifier.width(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (isTarget) "Stop navigating" else "Navigate to")
                }
            }
        }

        if (event.type.isWaypoint) {
            item {
                Button(
                    onClick = {
                        renameLauncher.launch(
                            RemoteInputIntentHelper.createActionRemoteInputIntent().apply {
                                RemoteInputIntentHelper.putRemoteInputsExtra(
                                    this,
                                    listOf(
                                        RemoteInput.Builder(RENAME_KEY).setLabel("Waypoint name").build()
                                    ),
                                )
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Rename")
                }
            }
            item {
                Button(
                    onClick = {
                        CotRepository.removeWaypoint(context, event.uid)
                        nav.popBackStack()
                    },
                    colors = androidx.wear.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Delete waypoint")
                }
            }
        }
    }
}
