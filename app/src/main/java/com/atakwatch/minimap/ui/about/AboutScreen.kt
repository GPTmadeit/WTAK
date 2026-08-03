package com.atakwatch.minimap.ui.about

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.atakwatch.minimap.data.CotRepository
import com.atakwatch.minimap.model.CotEvent
import com.atakwatch.minimap.model.CotType
import com.atakwatch.minimap.ui.components.RotaryScalingLazyColumn

@Composable
fun AboutScreen() {
    val self by CotRepository.self.collectAsStateWithLifecycle()

    val sample = self ?: CotEvent(
        uid = "ANDROID-watch",
        callsign = "WATCH-1",
        type = CotType.self(),
        lat = 40.75800, lon = -73.98550, hae = 12.0, ce = 10.0, le = 10.0,
        isSelf = true,
        endpoint = "*:-1:stcp", teamName = "Cyan", teamRole = "Team Member", battery = 100,
    )

    RotaryScalingLazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 28.dp),
    ) {
        item { ListHeader { Text("WTAK") } }
        item {
            Text(
                "v${com.atakwatch.minimap.BuildConfig.VERSION_NAME} · Wear OS 6",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text(
                "by NEO207",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text(
                "Tactical minimap for the TAK ecosystem. Entities use the " +
                    "Cursor-on-Target (CoT) model with MIL-STD-2525 affiliations. " +
                    "Not affiliated with TAK Product Center.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { ListHeader { Text("Self CoT type") } }
        item {
            Text(
                sample.type.raw,
                style = MaterialTheme.typography.numeralSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { ListHeader { Text("CoT event (wire format)") } }
        item {
            Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                Text(
                    sample.toCotXml(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(2.dp),
                )
            }
        }
    }
}
