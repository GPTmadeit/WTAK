package com.atakwatch.minimap.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.RemoteInput
import androidx.wear.input.RemoteInputIntentHelper
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.atakwatch.minimap.bridge.PhoneBridge
import com.atakwatch.minimap.data.CoordFormat
import com.atakwatch.minimap.ui.onboarding.applyIdentity
import com.atakwatch.minimap.data.MapOrientation
import com.atakwatch.minimap.data.MapSource
import com.atakwatch.minimap.data.MeshFormat
import com.atakwatch.minimap.model.Affiliation
import com.atakwatch.minimap.model.TeamColor
import com.atakwatch.minimap.model.TeamRole
import com.atakwatch.minimap.ui.Routes
import com.atakwatch.minimap.ui.collectSettings
import com.atakwatch.minimap.ui.components.NavRow
import com.atakwatch.minimap.ui.components.RotaryScalingLazyColumn
import com.atakwatch.minimap.ui.components.SectionHeader
import com.atakwatch.minimap.ui.components.ToggleRow
import com.atakwatch.minimap.ui.components.ValueRow
import com.atakwatch.minimap.ui.rememberSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CALLSIGN_KEY = "callsign"

@Composable
fun SettingsScreen(nav: NavController) {
    val repo = rememberSettingsRepository()
    val settings by collectSettings()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary

    var syncing by remember { mutableStateOf(false) }
    var syncStatus by remember { mutableStateOf("Pull") }

    // Android 13+ requires runtime consent before the tracking service can post
    // its ongoing notification; ask at the moment the user opts in.
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    // Wear's remote-input surface returns the typed/spoken callsign.
    val callsignLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data
            ?.let { RemoteInput.getResultsFromIntent(it) }
            ?.getCharSequence(CALLSIGN_KEY)
            ?.toString()
            ?.trim()
            ?.uppercase()
            ?.take(24)
        if (!text.isNullOrEmpty()) scope.launch { repo.setCallsign(text) }
    }

    val notificationPermission: (() -> Unit)? = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        } else null
    }

    // Talking to a LoRa radio needs Bluetooth consent on Android 12+; ask at the
    // moment the user turns the link on rather than up front.
    val btLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    val bluetoothPermission: (() -> Unit)? = remember {
        com.atakwatch.minimap.net.meshtastic.MeshtasticLink.runtimePermissions
            .takeIf { it.isNotEmpty() }
            ?.let { perms -> { btLauncher.launch(perms) } }
    }

    RotaryScalingLazyColumn {
        item { ListHeader { Text("Settings") } }

        // ---- who you are on the network --------------------------------
        item { SectionHeader("Identity") }
        item {
            // Your callsign is how the team identifies you — it has to be
            // yours, not one of a fixed list. Opens the Wear input surface
            // (keyboard, handwriting or voice).
            ValueRow("Callsign", settings.callsign, accent) {
                callsignLauncher.launch(
                    RemoteInputIntentHelper.createActionRemoteInputIntent().apply {
                        RemoteInputIntentHelper.putRemoteInputsExtra(
                            this,
                            listOf(
                                RemoteInput.Builder(CALLSIGN_KEY)
                                    .setLabel("Callsign")
                                    .build()
                            ),
                        )
                    }
                )
            }
        }
        item {
            ValueRow("Affiliation", settings.selfAffiliation.label, settings.selfAffiliation.color) {
                val order = Affiliation.entries
                val next = order[(order.indexOf(settings.selfAffiliation) + 1) % order.size]
                scope.launch { repo.setAffiliation(next) }
            }
        }
        item {
            ValueRow("Team", settings.teamColor.label, settings.teamColor.color) {
                val order = TeamColor.entries
                val next = order[(order.indexOf(settings.teamColor) + 1) % order.size]
                scope.launch { repo.setTeamColor(next) }
            }
        }
        item {
            // Re-pull identity from the EUD, e.g. after the operator changes
            // their callsign or team in ATAK.
            ValueRow("Sync from phone", syncStatus, accent) {
                if (!syncing) {
                    syncing = true
                    syncStatus = "Reading…"
                    scope.launch {
                        when (val r = PhoneBridge.pullIdentity(context)) {
                            is PhoneBridge.Result.Success -> {
                                applyIdentity(repo, r.identity)
                                syncStatus = r.identity.callsign ?: "Synced"
                            }
                            PhoneBridge.Result.NoData -> syncStatus = "Nothing shared"
                            PhoneBridge.Result.Unavailable -> syncStatus = "No phone"
                            is PhoneBridge.Result.Failed -> syncStatus = "Failed"
                        }
                        syncing = false
                    }
                }
            }
        }
        item {
            ValueRow("Role", settings.teamRole.label, accent) {
                val order = TeamRole.entries
                val next = order[(order.indexOf(settings.teamRole) + 1) % order.size]
                scope.launch { repo.setTeamRole(next) }
            }
        }
        // ---- how the map reads ----------------------------------------
        item { SectionHeader("Display") }
        item {
            ValueRow("Coordinates", settings.coordFormat.label, accent) {
                val next = if (settings.coordFormat == CoordFormat.MGRS) CoordFormat.LATLON else CoordFormat.MGRS
                scope.launch { repo.setCoordFormat(next) }
            }
        }
        item {
            ValueRow("Units", if (settings.imperialUnits) "Imperial" else "Metric", accent) {
                scope.launch { repo.setImperial(!settings.imperialUnits) }
            }
        }
        item {
            // When Offline is selected, say whether any archives were actually
            // found — otherwise a blank map looks like a bug.
            val offlineInfo by produceState("", settings.mapSource) {
                value = if (settings.mapSource == MapSource.OFFLINE) {
                    withContext(Dispatchers.IO) { com.atakwatch.minimap.map.OfflineMaps.summary(context) }
                } else ""
            }
            val label = settings.mapSource.label + if (offlineInfo.isNotEmpty()) " · $offlineInfo" else ""
            val warn = settings.mapSource == MapSource.OFFLINE && offlineInfo == "none found"
            ValueRow("Map", label, if (warn) MaterialTheme.colorScheme.error else accent) {
                val order = MapSource.entries
                val next = order[(order.indexOf(settings.mapSource) + 1) % order.size]
                scope.launch { repo.setMapSource(next) }
            }
        }
        item {
            ValueRow("Orientation", settings.mapOrientation.label, accent) {
                val next = if (settings.mapOrientation == MapOrientation.NORTH_UP)
                    MapOrientation.HEADING_UP else MapOrientation.NORTH_UP
                scope.launch { repo.setMapOrientation(next) }
            }
        }
        item {
            ToggleRow("Follow GPS", settings.followGps) {
                scope.launch { repo.setFollowGps(it) }
            }
        }
        item {
            ToggleRow(
                "Range rings",
                settings.rangeRings,
                secondary = if (settings.rangeRings) "100 · 250 · 500 · 1000 m" else "Distance reference",
            ) { scope.launch { repo.setRangeRings(it) } }
        }
        item {
            ToggleRow(
                "Radar sweep",
                settings.radarSweep,
                secondary = if (settings.radarSweep) "Animated scope" else "Static scope",
            ) { scope.launch { repo.setRadarSweep(it) } }
        }

        // ---- battery-facing choices ------------------------------------
        item { SectionHeader("Power") }
        item {
            ToggleRow("Keep screen on", settings.keepScreenOn) {
                scope.launch { repo.setKeepScreenOn(it) }
            }
        }
        item {
            // Foreground service: keeps tracking + CoT alive with the screen off.
            ToggleRow(
                "Background track",
                settings.backgroundTracking,
                secondary = if (settings.backgroundTracking) "Tracking with screen off" else "Map screen only",
            ) { on ->
                if (on) notificationPermission?.invoke()
                scope.launch { repo.setBackgroundTracking(on) }
            }
        }

        // ---- sharing your position -------------------------------------
        item { SectionHeader("Network") }
        item {
            ToggleRow(
                "CoT mesh",
                settings.cotMesh,
                secondary = if (settings.cotMesh) "239.2.3.1:6969" else "Local team sharing",
            ) { scope.launch { repo.setCotMesh(it) } }
        }
        item {
            ValueRow("Mesh format", settings.meshFormat.label, accent) {
                val next = if (settings.meshFormat == MeshFormat.TAK_PROTO)
                    MeshFormat.LEGACY_XML else MeshFormat.TAK_PROTO
                scope.launch { repo.setMeshFormat(next) }
            }
        }
        item {
            ToggleRow(
                "TAK Server",
                settings.takServer,
                secondary = if (settings.takServer) settings.takServerHost else "Streaming CoT",
            ) { scope.launch { repo.setTakServer(it) } }
        }
        item {
            // Tapping re-reads tak_server.json from the app's external files dir
            // (adb push …/files/tak_server.json), so a host can be set without
            // typing an IP on a watch.
            ValueRow("Server host", settings.takServerHost, MaterialTheme.colorScheme.onSurfaceVariant) {
                scope.launch {
                    com.atakwatch.minimap.net.CertEnrollment.loadConfig(context)?.let { cfg ->
                        repo.setTakServerHost("${cfg.host}:${cfg.port}")
                    }
                }
            }
        }
        item {
            // Re-check the cert whenever enrollment status changes, so this row
            // flips to "cert ✓" the moment an enrollment completes. The check
            // touches the filesystem, so it runs off the main thread.
            val enrollStatus by com.atakwatch.minimap.net.CertEnrollment.status.collectAsStateWithLifecycle()
            val hasCert by produceState(initialValue = false, enrollStatus) {
                value = withContext(Dispatchers.IO) {
                    com.atakwatch.minimap.net.CertStore.hasIdentity(context)
                }
            }
            ToggleRow(
                "TLS",
                settings.takTls,
                secondary = if (hasCert) "Certificate held" else "No certificate — enroll first",
            ) { scope.launch { repo.setTakTls(it) } }
        }
        item {
            val enrollStatus by com.atakwatch.minimap.net.CertEnrollment.status.collectAsStateWithLifecycle()
            ValueRow("Enroll cert", enrollStatus.label, MaterialTheme.colorScheme.primary) {
                scope.launch { com.atakwatch.minimap.net.CertEnrollment.enroll(context) }
            }
        }
        // ---- LoRa: the team link with no infrastructure at all ----------
        item { SectionHeader("Radio") }
        item {
            val linkState by com.atakwatch.minimap.net.meshtastic.MeshtasticLink.state
                .collectAsStateWithLifecycle()
            ToggleRow(
                "Meshtastic",
                settings.meshtastic,
                secondary = when {
                    settings.meshtasticAddress.isBlank() -> "Pick a radio first"
                    settings.meshtastic -> linkState.label
                    else -> settings.meshtasticName.ifBlank { settings.meshtasticAddress }
                },
            ) { on ->
                if (on) bluetoothPermission?.invoke()
                scope.launch { repo.setMeshtastic(on) }
            }
        }
        item {
            NavRow(Icons.Filled.SettingsRemote, "Radio setup") { nav.navigate(Routes.RADIO) }
        }

        item { SectionHeader("App") }
        item {
            NavRow(Icons.Filled.SystemUpdate, "Update") { nav.navigate(Routes.UPDATE) }
        }
        item { NavRow(Icons.Filled.Info, "About") { nav.navigate(Routes.ABOUT) } }
    }
}
