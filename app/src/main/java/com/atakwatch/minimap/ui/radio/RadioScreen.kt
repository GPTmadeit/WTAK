package com.atakwatch.minimap.ui.radio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.atakwatch.minimap.net.meshtastic.MeshtasticLink
import com.atakwatch.minimap.net.meshtastic.MeshtasticProto
import com.atakwatch.minimap.net.meshtastic.MeshtasticScanner
import com.atakwatch.minimap.ui.collectSettings
import com.atakwatch.minimap.ui.components.RotaryScalingLazyColumn
import com.atakwatch.minimap.ui.components.SectionHeader
import com.atakwatch.minimap.ui.components.ValueRow
import androidx.compose.runtime.LaunchedEffect
import com.atakwatch.minimap.ui.rememberSettingsRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Pick the LoRa radio the team link runs over.
 *
 * Scanning is filtered to Meshtastic's service UUID, so the list is radios and
 * nothing else. Choosing one stores its address and brings the link up; the
 * first connection triggers Android's own pairing prompt, which is where the
 * radio's PIN is entered — this app never handles it.
 */
@Composable
fun RadioScreen(nav: NavController) {
    val context = LocalContext.current
    val repo = rememberSettingsRepository()
    val settings by collectSettings()
    val scope = rememberCoroutineScope()
    val accent = MaterialTheme.colorScheme.primary

    val linkState by MeshtasticLink.state.collectAsStateWithLifecycle()
    val nodeCount by MeshtasticLink.nodeCount.collectAsStateWithLifecycle()
    val profile by MeshtasticLink.profile.collectAsStateWithLifecycle()
    val configureState by MeshtasticLink.configureState.collectAsStateWithLifecycle()

    // The radio replays its config on connect, but a link that was already up
    // when this screen opened won't. Ask once so the readouts are never blank
    // for a radio that is right there.
    LaunchedEffect(linkState) {
        if (linkState == MeshtasticLink.State.CONNECTED) MeshtasticLink.refreshProfile()
    }

    var granted by remember { mutableStateOf(MeshtasticLink.hasPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = MeshtasticLink.hasPermission(context) }

    var radios by remember { mutableStateOf<List<MeshtasticScanner.Radio>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }

    // The scan lives exactly as long as this screen does. BLE scanning is one of
    // the more expensive things a watch can do; leaving one running behind a
    // closed screen would be a battery bug the user could never find.
    LaunchedEffect(granted, scanning) {
        if (!granted || !scanning) return@LaunchedEffect
        MeshtasticScanner.scan(context).collectLatest { radios = it }
    }

    RotaryScalingLazyColumn {
        item { ListHeader { Text("Radio") } }

        item {
            ValueRow("Link", linkState.label, when (linkState) {
                MeshtasticLink.State.CONNECTED -> accent
                MeshtasticLink.State.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.error
            }) { }
        }

        if (linkState == MeshtasticLink.State.CONNECTED) {
            item {
                ValueRow(
                    "Mesh", if (nodeCount == 1) "1 node" else "$nodeCount nodes",
                    MaterialTheme.colorScheme.onSurfaceVariant,
                ) { }
            }
        }

        item {
            val paired = settings.meshtasticName.ifBlank {
                settings.meshtasticAddress.ifBlank { "None" }
            }
            ValueRow("Paired", paired, MaterialTheme.colorScheme.onSurfaceVariant) { }
        }

        // ---- what the radio says about itself ---------------------------
        if (linkState == MeshtasticLink.State.CONNECTED) {
            item { SectionHeader("Radio") }

            // A radio with no region cannot legally transmit, and from here that
            // looks exactly like a mesh with nobody on it. Say which it is.
            if (profile.regionUnset) {
                item {
                    Text(
                        "No region set — this radio cannot transmit. Set its " +
                            "region in the Meshtastic app; it is a licensing " +
                            "choice this app won't make for you.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            profile.region?.let { region ->
                item {
                    ValueRow(
                        "Region",
                        MeshtasticProto.regionName(region),
                        if (profile.regionUnset) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    ) { }
                }
            }
            profile.modemPreset?.let {
                item {
                    ValueRow("Preset", MeshtasticProto.modemPresetName(it),
                        MaterialTheme.colorScheme.onSurfaceVariant) { }
                }
            }
            profile.channel?.let {
                item {
                    ValueRow("Channel", it, MaterialTheme.colorScheme.onSurfaceVariant) { }
                }
            }
            profile.deviceRole?.let { role ->
                val isTak = role == MeshtasticProto.DEVICE_ROLE_TAK
                item {
                    ValueRow(
                        "Role",
                        MeshtasticProto.deviceRoleName(role),
                        if (isTak) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    ) { }
                }
            }
            profile.firmware?.let {
                item {
                    ValueRow("Firmware", it, MaterialTheme.colorScheme.onSurfaceVariant) { }
                }
            }

            // ---- make it the ATAK connector -----------------------------
            item { SectionHeader("TAK connector") }
            item {
                val configured = profile.matches(settings.teamColor, settings.teamRole)
                ValueRow(
                    if (configured) "Configured" else "Configure radio",
                    if (configured) "${settings.teamColor.label} · ${settings.teamRole.label}"
                    else configureState.label,
                    when {
                        configureState == MeshtasticLink.ConfigureState.FAILED ->
                            MaterialTheme.colorScheme.error
                        configured -> accent
                        else -> MaterialTheme.colorScheme.primary
                    },
                ) {
                    if (!configured) {
                        MeshtasticLink.configureForTak(settings.teamColor, settings.teamRole)
                    }
                }
            }
            item {
                Text(
                    "Sets the radio's device role to TAK and writes your team " +
                        "and role into it, so it stops emitting routine chatter " +
                        "and identifies the same way this watch does. The radio " +
                        "reboots once to apply.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { SectionHeader("Nearby") }

        if (!granted) {
            item {
                ValueRow("Bluetooth", "Grant", accent) {
                    permissionLauncher.launch(MeshtasticLink.runtimePermissions)
                }
            }
        } else {
            item {
                ValueRow(
                    "Scan",
                    if (scanning) "Scanning…" else "Start",
                    if (scanning) MaterialTheme.colorScheme.onSurfaceVariant else accent,
                ) { scanning = !scanning }
            }
            if (scanning && radios.isEmpty()) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ScanPulse(accent)
                        Text(
                            "No radios yet — make sure it is powered on and not " +
                                "already connected to a phone.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            items(radios.size) { index ->
                val radio = radios[index]
                val selected = radio.address == settings.meshtasticAddress
                ValueRow(
                    radio.name,
                    if (selected) "Selected" else signalLabel(radio),
                    if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    scope.launch {
                        repo.setMeshtasticRadio(radio.address, radio.name)
                        repo.setMeshtastic(true)
                    }
                    scanning = false
                }
            }
        }

        if (settings.meshtasticAddress.isNotBlank()) {
            item { SectionHeader("Pairing") }
            item {
                ValueRow("Forget radio", "Clear", MaterialTheme.colorScheme.error) {
                    scope.launch {
                        repo.setMeshtastic(false)
                        repo.setMeshtasticRadio("", "")
                    }
                }
            }
        }
    }
}

/**
 * Rings expanding outward while a scan runs.
 *
 * A scan can legitimately take many seconds with nothing to show, and a static
 * "Scanning…" gives the operator no way to tell a working scan from a hung one.
 * Three offset rings make the wait legible.
 */
@Composable
private fun ScanPulse(accent: Color) {
    val transition = rememberInfiniteTransition(label = "scan")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    Canvas(modifier = Modifier.size(46.dp).padding(vertical = 6.dp)) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        repeat(3) { i ->
            val p = ((phase + i / 3f) % 1f)
            drawCircle(
                color = accent.copy(alpha = (1f - p) * 0.7f),
                radius = size.minDimension / 2f * p,
                center = centre,
                style = Stroke(width = 2f),
            )
        }
        drawCircle(accent, radius = 3f, center = centre)
    }
}

private fun signalLabel(radio: MeshtasticScanner.Radio): String = when {
    radio.bonded && radio.rssi == 0 -> "Paired"
    else -> "▁▃▅▇".take(radio.signal + 1).ifEmpty { "·" }
}
