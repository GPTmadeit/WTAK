package com.atakwatch.minimap.net

import android.util.Log
import com.atakwatch.minimap.data.CotRepository
import com.atakwatch.minimap.model.CotEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * TAK Server streaming-CoT client (STCP): a persistent TCP connection that
 * sends the self PLI as CoT XML and parses inbound CoT events from the stream.
 *
 * Two modes:
 *  - **Plain STCP** (default 8087) — no TLS, any FreeTAKServer/OpenTAKServer
 *    TCP CoT input.
 *  - **Mutual TLS** (default 8089) — client authenticates with the certificate
 *    obtained via [CertEnrollment]; the server is verified against the CAs
 *    pinned at enrollment. (Hostname verification is intentionally not applied:
 *    TAK connections are typically by IP against CN-named server certs, and
 *    trust is anchored to the enrolled CA — the same posture ATAK takes.)
 *
 * Speaking XML on the stream is the universally-compatible baseline: TAK
 * servers accept legacy XML clients without TAK-protocol negotiation (the
 * proto upgrade is negotiated via t-x-takp-* control events, which we simply
 * never request; control events from the server are filtered out).
 *
 * Reconnects with backoff while enabled. All I/O on a background scope.
 */
class TakClient {

    companion object {
        private const val TAG = "TakClient"
        private const val PLI_INTERVAL_MS = 3_000L
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 6_000

        /**
         * Process-wide link state, so the map can warn that the team link is
         * down regardless of whether the connection is owned by the map screen
         * or by the background tracking service.
         */
        private val _linkState = MutableStateFlow(State.OFF)
        val linkState: StateFlow<State> = _linkState.asStateFlow()
    }

    enum class State { OFF, CONNECTING, CONNECTED }

    private var scope: CoroutineScope? = null
    @Volatile private var output: java.io.OutputStream? = null
    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state.asStateFlow()

    val isRunning: Boolean get() = scope != null

    /** Send pre-built XML (GeoChat) on the live stream. */
    fun sendRaw(xml: String) {
        val s = scope ?: return
        s.launch {
            runCatching {
                output?.let { it.write(xml.toByteArray(Charsets.UTF_8)); it.flush() }
            }
        }
    }

    /** One-shot send on the live stream (e.g. sharing a dropped waypoint). */
    fun sendEvent(event: CotEvent) {
        val s = scope ?: return
        s.launch {
            runCatching {
                output?.let {
                    it.write(TakProtocol.buildXml(event).toByteArray(Charsets.UTF_8))
                    it.flush()
                    Log.d(TAG, "sent ${event.type.raw} '${event.callsign}' to server")
                }
            }
        }
    }

    fun start(hostPort: String, ssl: SSLContext? = null, selfProvider: () -> CotEvent?) {
        if (isRunning) return
        val host = hostPort.substringBefore(':').trim()
        val port = hostPort.substringAfter(':', "8087").trim().toIntOrNull() ?: 8087
        if (host.isEmpty()) return

        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = s
        s.launch { connectionLoop(host, port, ssl, selfProvider) }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        _state.value = State.OFF; _linkState.value = State.OFF
    }

    private suspend fun connectionLoop(host: String, port: Int, ssl: SSLContext?, selfProvider: () -> CotEvent?) {
        val cs = scope ?: return
        while (cs.isActive) {
            _state.value = State.CONNECTING; _linkState.value = State.CONNECTING
            var socket: Socket? = null
            try {
                socket = if (ssl != null) {
                    (ssl.socketFactory.createSocket() as SSLSocket).apply {
                        tcpNoDelay = true
                        soTimeout = 30_000
                        connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                        startHandshake()
                        Log.i(TAG, "TLS handshake OK: ${session.protocol} ${session.cipherSuite}, " +
                            "server=${session.peerPrincipal?.name}")
                    }
                } else {
                    Socket().apply {
                        tcpNoDelay = true
                        soTimeout = 30_000
                        connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    }
                }
                Log.i(TAG, "connected to $host:$port${if (ssl != null) " (mTLS)" else ""}")
                _state.value = State.CONNECTED; _linkState.value = State.CONNECTED

                val reader = cs.launch { readLoop(socket) }

                val out = socket.getOutputStream()
                output = out
                while (cs.isActive && !socket.isClosed) {
                    selfProvider()?.let { self ->
                        val xml = TakProtocol.buildXml(self)
                        out.write(xml.toByteArray(Charsets.UTF_8))
                        out.flush()
                        Log.d(TAG, "sent PLI to server (${xml.length} B)")
                    }
                    delay(PLI_INTERVAL_MS)
                }
                reader.cancel()
            } catch (e: Exception) {
                if (cs.isActive) Log.w(TAG, "connection error: ${e.message}")
            } finally {
                output = null
                runCatching { socket?.close() }
            }
            if (!cs.isActive) break
            _state.value = State.CONNECTING; _linkState.value = State.CONNECTING
            delay(RECONNECT_DELAY_MS)
        }
        _state.value = State.OFF; _linkState.value = State.OFF
    }

    /**
     * Read the inbound stream and extract complete `<event …>…</event>` chunks.
     * Servers we haven't negotiated proto with send XML; anything else
     * (partial frames, control chatter) stays in the buffer or is dropped.
     */
    private fun readLoop(socket: Socket) {
        val input = socket.getInputStream()
        val chunk = ByteArray(16_384)
        val buffer = StringBuilder()
        val cs = scope
        while (cs?.isActive == true && !socket.isClosed) {
            try {
                val n = input.read(chunk)
                if (n < 0) break
                buffer.append(String(chunk, 0, n, Charsets.UTF_8))
                extractEvents(buffer)
                // Safety valve: a server spamming non-CoT data can't grow the buffer forever.
                if (buffer.length > 512 * 1024) buffer.setLength(0)
            } catch (_: java.net.SocketTimeoutException) {
                // Idle stream — keep waiting.
            } catch (e: Exception) {
                if (cs?.isActive == true) Log.w(TAG, "read error: ${e.message}")
                break
            }
        }
    }

    private fun extractEvents(buffer: StringBuilder) {
        while (true) {
            val end = buffer.indexOf("</event>")
            if (end < 0) {
                // Also handle self-closing <event .../> control messages (rare).
                return
            }
            val start = buffer.indexOf("<event")
            if (start < 0 || start > end) { buffer.delete(0, end + 8); continue }
            val xml = buffer.substring(start, end + 8)
            buffer.delete(0, end + 8)
            // Chat first — it is a message, not a map entity.
            val chat = if (xml.contains(GeoChat.COT_TYPE)) GeoChat.parse(xml) else null
            if (chat != null && chat.senderUid != DeviceIdentity.uid) {
                Log.d(TAG, "chat from ${chat.senderCallsign}")
                com.atakwatch.minimap.data.ChatRepository.add(chat)
                continue
            }
            val event = TakProtocol.parseXml(xml) ?: continue
            if (event.uid == DeviceIdentity.uid) continue
            if (!event.type.isRenderable) continue
            Log.d(TAG, "recv ${event.type.raw} '${event.callsign}' from server")
            CotRepository.upsertNetwork(event)
        }
    }
}
