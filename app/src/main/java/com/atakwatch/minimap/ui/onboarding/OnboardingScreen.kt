package com.atakwatch.minimap.ui.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Watch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.atakwatch.minimap.bridge.EudProtocol
import com.atakwatch.minimap.bridge.PhoneBridge
import com.atakwatch.minimap.data.SettingsRepository
import com.atakwatch.minimap.ui.components.RotaryScalingLazyColumn
import kotlinx.coroutines.launch

/**
 * First-run setup. The fast path is pulling everything from a paired EUD already
 * running ATAK, so the operator's callsign, team, role and server match the
 * phone exactly and nothing has to be typed on a watch.
 *
 * Setting up on the watch stays available, because the watch is a standalone
 * client — a phone is an accelerator, not a requirement.
 */
@Composable
fun OnboardingScreen(repo: SettingsRepository, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var phoneAvailable by remember { mutableStateOf<Boolean?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        phoneAvailable = PhoneBridge.isCompanionAvailable(context)
    }

    fun pull() {
        if (busy) return
        busy = true
        status = "Reading from phone…"
        scope.launch {
            when (val r = PhoneBridge.pullIdentity(context)) {
                is PhoneBridge.Result.Success -> {
                    applyIdentity(repo, r.identity)
                    repo.setOnboarded(true)
                    status = "Set up as ${r.identity.callsign ?: "operator"}"
                    onDone()
                }
                PhoneBridge.Result.NoData ->
                    status = "Phone found, but ATAK hasn't shared anything yet. Open ATAK, then retry."
                PhoneBridge.Result.Unavailable ->
                    status = "No phone found. Install the ATAK Watch plugin on your EUD, or set up here."
                is PhoneBridge.Result.Failed ->
                    status = "Couldn't read from phone: ${r.reason}"
            }
            busy = false
        }
    }

    RotaryScalingLazyColumn {
        item { ListHeader { Text("ATAK Watch") } }
        item {
            Text(
                "Pull your callsign, team and server from a phone running ATAK — or set the watch up on its own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(
                onClick = { pull() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Icon(Icons.Filled.PhoneAndroid, contentDescription = null, modifier = Modifier.width(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (phoneAvailable == true) "Set up from phone" else "Look for phone")
            }
        }
        item {
            Button(
                onClick = {
                    scope.launch { repo.setOnboarded(true); onDone() }
                },
                enabled = !busy,
                colors = androidx.wear.compose.material3.ButtonDefaults.outlinedButtonColors(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Watch, contentDescription = null, modifier = Modifier.width(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Set up on watch")
            }
        }
        if (status != null) {
            item {
                Text(
                    status!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

/** Map an EUD identity onto watch settings, ignoring anything the phone omitted. */
internal suspend fun applyIdentity(repo: SettingsRepository, id: EudProtocol.Identity) {
    id.callsign?.let { repo.setCallsign(it) }
    id.teamColor?.let { repo.setTeamColor(it) }
    id.teamRole?.let { repo.setTeamRole(it) }
    id.affiliation?.let { repo.setAffiliation(it) }
    id.hostPort?.let { repo.setTakServerHost(it) }
}
