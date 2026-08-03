package com.atakwatch.minimap.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.atakwatch.minimap.data.CotRepository
import com.atakwatch.minimap.model.CotEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket

/**
 * Mesh SA over UDP multicast — the TAK default group 239.2.3.1:6969. Sends the
 * self PLI every 3 s as either TAK Protocol v1 (modern ATAK default) or legacy
 * CoT XML, and receives both (auto-detected per packet, exactly like ATAK's
 * mesh listener). Opt-in; all I/O on a background scope; malformed/hostile
 * packets are dropped, never crash.
 */
class CotMulticast(private val context: Context) {

    companion object {
        private const val TAG = "CotMulticast"
        private const val GROUP = "239.2.3.1"
        private const val PORT = 6969
        private const val PLI_INTERVAL_MS = 3_000L
    }

    private var scope: CoroutineScope? = null
    private var socket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var useProto: Boolean = true

    val isRunning: Boolean get() = scope != null

    fun start(proto: Boolean, selfProvider: () -> CotEvent?) {
        useProto = proto
        if (isRunning) return
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = s

        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("atakwatch-cot").apply {
            setReferenceCounted(true); runCatching { acquire() }
        }

        s.launch {
            try {
                val group = InetAddress.getByName(GROUP)
                @Suppress("DEPRECATION")
                val sock = MulticastSocket(null as InetSocketAddress?).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                    soTimeout = 1_000
                    joinGroup(group)
                }
                socket = sock
                launch { receiveLoop(sock) }
                sendLoop(sock, group, selfProvider)
            } catch (e: Exception) {
                Log.w(TAG, "multicast start failed: ${e.message}")
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        runCatching { socket?.close() }
        socket = null
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    /** Send pre-built XML (GeoChat), fire-and-forget. */
    fun sendRaw(bytes: ByteArray) {
        val s = scope ?: return
        s.launch {
            runCatching {
                val sock = socket ?: return@launch
                sock.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(GROUP), PORT))
            }
        }
    }

    /** One-shot send (e.g. broadcasting a dropped waypoint), fire-and-forget. */
    fun sendEvent(event: CotEvent) {
        val s = scope ?: return
        s.launch {
            runCatching {
                val sock = socket ?: return@launch
                val bytes = encode(event)
                sock.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(GROUP), PORT))
                Log.d(TAG, "sent ${event.type.raw} '${event.callsign}' (${bytes.size} B)")
            }
        }
    }

    private fun encode(event: CotEvent): ByteArray =
        if (useProto) TakProtocol.encodeMeshFrame(event)
        else TakProtocol.buildXml(event).toByteArray(Charsets.UTF_8)

    private suspend fun sendLoop(sock: MulticastSocket, group: InetAddress, selfProvider: () -> CotEvent?) {
        val cs = scope ?: return
        while (cs.isActive) {
            selfProvider()?.let { self ->
                runCatching {
                    val bytes = encode(self)
                    sock.send(DatagramPacket(bytes, bytes.size, group, PORT))
                    Log.d(TAG, "sent PLI ${if (useProto) "proto" else "xml"} (${bytes.size} B) as ${self.callsign}")
                }.onFailure { Log.w(TAG, "send failed: ${it.message}") }
            }
            delay(PLI_INTERVAL_MS)
        }
    }

    private fun receiveLoop(sock: MulticastSocket) {
        val buf = ByteArray(65_535)
        val cs = scope
        while (cs?.isActive == true) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                sock.receive(packet)
                // GeoChat is a message, not a contact — route it before the
                // entity pipeline, which would otherwise discard it.
                val raw = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val chat = if (raw.contains(GeoChat.COT_TYPE)) GeoChat.parse(raw) else null
                if (chat != null && chat.senderUid != DeviceIdentity.uid) {
                    Log.d(TAG, "chat from ${chat.senderCallsign}")
                    com.atakwatch.minimap.data.ChatRepository.add(chat)
                    continue
                }
                val event = TakProtocol.decodeDatagram(packet.data, packet.length) ?: continue
                if (event.uid == DeviceIdentity.uid) continue // our own loopback
                if (!event.type.isRenderable) continue        // ignore tasking/control
                Log.d(TAG, "recv ${event.type.raw} '${event.callsign}' from ${packet.address}")
                CotRepository.upsertNetwork(event)
            } catch (_: java.net.SocketTimeoutException) {
                // Normal — loop back and check isActive.
            } catch (e: Exception) {
                if (cs?.isActive == true) Log.w(TAG, "receive error: ${e.message}")
            }
        }
    }
}
