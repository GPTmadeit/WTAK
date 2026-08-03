package com.atakwatch.minimap.data

import com.atakwatch.minimap.model.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * GeoChat traffic, newest last. Capped — a watch has no business holding an
 * unbounded backlog, and anything older than the last few exchanges is better
 * read on the EUD.
 *
 * Unread is tracked so the menu can badge without the user opening chat.
 */
object ChatRepository {

    private const val MAX_MESSAGES = 100

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread.asStateFlow()

    @Synchronized
    fun add(message: ChatMessage) {
        // Same message can arrive over both mesh and the server; de-duplicate.
        if (_messages.value.any { it.id == message.id }) return
        val next = (_messages.value + message).takeLast(MAX_MESSAGES)
        _messages.value = next
        if (!message.outgoing) _unread.value = _unread.value + 1
    }

    fun markRead() { _unread.value = 0 }

    fun clear() { _messages.value = emptyList(); _unread.value = 0 }
}
