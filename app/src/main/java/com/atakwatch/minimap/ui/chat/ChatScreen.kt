package com.atakwatch.minimap.ui.chat

import android.app.RemoteInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import com.atakwatch.minimap.data.ChatRepository
import com.atakwatch.minimap.model.ChatMessage
import com.atakwatch.minimap.model.Geo
import com.atakwatch.minimap.ui.components.RotaryScalingLazyColumn

private const val CHAT_KEY = "chat_text"

/**
 * GeoChat, broadcast to All Chat Rooms.
 *
 * Sending is voice-first: the watch's input surface offers dictation, and
 * speaking a short message is the only realistic way to compose one on a wrist
 * while moving. Canned phrases cover the cases where speaking aloud isn't an
 * option.
 */
@Composable
fun ChatScreen(onSend: (String) -> Unit) {
    val messages by ChatRepository.messages.collectAsStateWithLifecycle()

    // Opening the screen clears the badge.
    LaunchedEffect(Unit) { ChatRepository.markRead() }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data
            ?.let { RemoteInput.getResultsFromIntent(it) }
            ?.getCharSequence(CHAT_KEY)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(onSend)
    }

    fun compose() {
        launcher.launch(
            RemoteInputIntentHelper.createActionRemoteInputIntent().apply {
                RemoteInputIntentHelper.putRemoteInputsExtra(
                    this,
                    listOf(RemoteInput.Builder(CHAT_KEY).setLabel("Message").build()),
                )
            }
        )
    }

    val now = System.currentTimeMillis()

    RotaryScalingLazyColumn {
        item { ListHeader { Text("GeoChat") } }

        item {
            Button(onClick = { compose() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.width(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Send message")
            }
        }

        // Quick phrases for when speaking isn't practical.
        item {
            Text(
                "Quick send",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        items@ for (phrase in QUICK_PHRASES) {
            item {
                Card(onClick = { onSend(phrase) }, modifier = Modifier.fillMaxWidth()) {
                    Text(phrase, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (messages.isEmpty()) {
            item {
                Text(
                    "No messages yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }

        // Newest first — on a watch you read the top of the list, not the end.
        items@ for (m in messages.asReversed()) {
            item {
                Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text(
                            if (m.outgoing) "You" else m.senderCallsign,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (m.outgoing) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary,
                        )
                        Text(m.text, style = MaterialTheme.typography.bodySmall)
                        Text(
                            Geo.formatAge(now - m.timeMillis),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private val QUICK_PHRASES = listOf(
    "Roger",
    "In position",
    "Moving now",
    "Need assistance",
    "Negative contact",
)
