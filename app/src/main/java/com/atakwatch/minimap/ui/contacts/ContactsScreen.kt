package com.atakwatch.minimap.ui.contacts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.atakwatch.minimap.data.CotRepository
import com.atakwatch.minimap.map.CoordinateFormatter
import com.atakwatch.minimap.model.CotEvent
import com.atakwatch.minimap.ui.map.entityColor
import kotlinx.coroutines.delay
import com.atakwatch.minimap.model.Geo
import com.atakwatch.minimap.ui.Routes
import com.atakwatch.minimap.ui.collectSettings
import com.atakwatch.minimap.ui.components.AffiliationDot
import com.atakwatch.minimap.ui.components.RotaryScalingLazyColumn

@Composable
fun ContactsScreen(nav: NavController) {
    val self by CotRepository.self.collectAsStateWithLifecycle()
    val entities by CotRepository.entities.collectAsStateWithLifecycle()
    val settings by collectSettings()

    // Self first, then everything else nearest-first — on a watch the closest
    // contact is nearly always the one you are looking for.
    val sorted = remember(self, entities) {
        val s = self
        if (s == null) entities
        else entities.sortedBy { Geo.distanceMeters(s.lat, s.lon, it.lat, it.lon) }
    }
    val all = listOfNotNull(self) + sorted

    // Re-tick so relative ages in the list stay honest without a fix arriving.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { nowMillis = System.currentTimeMillis(); delay(5_000) }
    }

    RotaryScalingLazyColumn {
        item { ListHeader { Text("Contacts (${all.size})") } }
        // Contacts are real: they arrive over CoT mesh or a TAK server, so say
        // what is missing rather than showing a blank list.
        if (self == null) {
            item {
                Text(
                    "Acquiring GPS…",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else if (entities.isEmpty()) {
            item {
                Text(
                    if (settings.cotMesh || settings.takServer)
                        "No contacts yet — waiting for team traffic."
                    else
                        "No contacts. Turn on CoT mesh or TAK Server in Settings to see your team.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        items(all) { e ->
            ContactRow(
                event = e,
                self = self,
                imperial = settings.imperialUnits,
                coordFormat = settings.coordFormat,
                nowMillis = nowMillis,
            ) {
                nav.navigate(Routes.detail(e.uid))
            }
        }
    }
}

@Composable
private fun ContactRow(
    event: CotEvent,
    self: CotEvent?,
    imperial: Boolean,
    coordFormat: com.atakwatch.minimap.data.CoordFormat,
    nowMillis: Long,
    onClick: () -> Unit,
) {
    val secondary = when {
        event.isSelf -> CoordinateFormatter.format(event.lat, event.lon, coordFormat)
        self != null -> {
            val d = Geo.distanceMeters(self.lat, self.lon, event.lat, event.lon)
            val b = Geo.bearingDegrees(self.lat, self.lon, event.lat, event.lon)
            "${Geo.formatRange(d, imperial)}   ${Geo.formatBearing(b)}"
        }
        else -> event.type.raw
    }

    // A position report that has stopped updating is dangerous to trust, so age
    // it visibly rather than letting it look live. Waypoints never go stale.
    val ageMillis = nowMillis - event.timeMillis
    val tracked = !event.isSelf && !event.type.isWaypoint
    val isStale = tracked && ageMillis > STALE_AFTER_MS
    val dotColor = entityColor(event).let { if (isStale) it.copy(alpha = 0.45f) else it }

    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AffiliationDot(dotColor, size = 14)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when {
                        event.isSelf -> "${event.callsign} (self)"
                        event.type.isWaypoint -> "${event.callsign} (WP)"
                        else -> event.callsign
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isStale) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (isStale) "$secondary · ${Geo.formatAge(ageMillis)} old" else secondary,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isStale) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Past this, a contact's last report is old enough to call out. */
private const val STALE_AFTER_MS = 45_000L
