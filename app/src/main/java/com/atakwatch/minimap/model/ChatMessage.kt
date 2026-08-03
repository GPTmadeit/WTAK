package com.atakwatch.minimap.model

/**
 * A GeoChat message.
 *
 * ATAK carries chat as CoT type `b-t-f` with a `__chat` detail and a `<remarks>`
 * body, addressed to a chatroom. "All Chat Rooms" is the broadcast room every
 * client is in, which is the only one that makes sense to expose on a watch —
 * picking a recipient on a 1.4" screen is worse than useless under load.
 */
data class ChatMessage(
    val id: String,
    val senderCallsign: String,
    val text: String,
    val timeMillis: Long,
    val room: String = ALL_ROOMS,
    /** UID of the sending device, used to drop our own loopback copy. */
    val senderUid: String? = null,
    /** True for messages this device sent, so the UI can align them. */
    val outgoing: Boolean = false,
) {
    companion object {
        const val ALL_ROOMS = "All Chat Rooms"
    }
}
