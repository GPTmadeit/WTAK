package com.atakwatch.minimap.data

import com.atakwatch.minimap.model.ChatMessage
import com.atakwatch.minimap.net.Transports
import java.util.UUID

/**
 * Where an outgoing chat message goes.
 *
 * The chat screen doesn't know or care which transports are live — that is
 * owned at app scope by [Transports], so a message sent from the chat screen
 * leaves the device even though the map isn't composed.
 */
object ChatSender {

    /** Optional override, for tests. Normally transports are app-scoped. */
    @Volatile
    var transport: ((String) -> Unit)? = null

    fun send(text: String) {
        // Echo locally first, so a dictated message always appears — silently
        // dropping it when no transport is up would be worse than showing it
        // undelivered.
        ChatRepository.add(
            ChatMessage(
                id = UUID.randomUUID().toString(),
                senderCallsign = "You",
                text = text,
                timeMillis = System.currentTimeMillis(),
                outgoing = true,
            )
        )

        transport?.let { it(text); return }

        // GeoChat events carry a point like any other CoT, so a fix is needed.
        val me = CotRepository.self.value ?: return
        Transports.sendChat(text, me)
    }
}
