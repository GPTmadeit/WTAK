package com.atakwatch.minimap.net

import android.content.Context
import com.atakwatch.minimap.data.CotRepository
import com.atakwatch.minimap.data.MeshFormat
import com.atakwatch.minimap.data.Settings
import com.atakwatch.minimap.model.CotEvent

/**
 * The mesh socket and TAK server connection, owned at app scope.
 *
 * These used to live in the map screen's composition, which meant opening
 * Contacts or GeoChat tore them down — your position stopped being shared the
 * moment you looked at anything else, and an outgoing chat had no transport to
 * leave by. Ownership belongs above the navigation graph.
 *
 * When background tracking is on, the foreground service owns the transports
 * instead and this stays idle, so there is still exactly one sender on the wire.
 */
object Transports {

    private var mesh: CotMulticast? = null
    private var server: TakClient? = null
    private var radio: com.atakwatch.minimap.net.meshtastic.MeshtasticLink? = null
    private var applied: Settings? = null

    /** Reconcile live transports with settings. Safe to call repeatedly. */
    @Synchronized
    fun apply(context: Context, s: Settings) {
        // The service is the single owner while background tracking is on.
        if (s.backgroundTracking) { stopAll(); applied = s; return }

        val prev = applied
        applied = s

        if (prev == null || s.cotMesh != prev.cotMesh || s.meshFormat != prev.meshFormat) {
            mesh?.stop(); mesh = null
            if (s.cotMesh) {
                mesh = CotMulticast(context.applicationContext).also {
                    it.start(proto = s.meshFormat == MeshFormat.TAK_PROTO) { CotRepository.self.value }
                }
            }
        }

        if (prev == null || s.takServer != prev.takServer ||
            s.takServerHost != prev.takServerHost || s.takTls != prev.takTls
        ) {
            server?.stop(); server = null
            if (s.takServer) {
                val useTls = s.takTls && CertStore.hasIdentity(context)
                val hostPort = if (useTls) {
                    val cfg = CertEnrollment.loadConfig(context)
                    "${s.takServerHost.substringBefore(':')}:${cfg?.tlsPort ?: 8089}"
                } else s.takServerHost
                val ssl = if (useTls) runCatching { CertStore.sslContext(context) }.getOrNull() else null
                server = TakClient().also { it.start(hostPort, ssl) { CotRepository.self.value } }
            }
        }

        if (prev == null || s.meshtastic != prev.meshtastic ||
            s.meshtasticAddress != prev.meshtasticAddress
        ) {
            radio?.stop(); radio = null
            if (s.meshtastic && s.meshtasticAddress.isNotBlank()) {
                radio = com.atakwatch.minimap.net.meshtastic.MeshtasticLink(context).also {
                    it.start(s.meshtasticAddress) { CotRepository.self.value }
                }
            }
        }
    }

    @Synchronized
    fun stopAll() {
        mesh?.stop(); mesh = null
        server?.stop(); server = null
        radio?.stop(); radio = null
    }

    /**
     * Broadcast a CoT event (waypoint share, emergency beacon).
     *
     * The radio is deliberately excluded from routine position traffic — it
     * paces that itself, because LoRa airtime is shared with the whole team —
     * but a one-shot event is exactly the kind of thing that should go out on
     * every link there is.
     */
    fun sendEvent(event: CotEvent) {
        mesh?.sendEvent(event)
        server?.sendEvent(event)
        radio?.sendEvent(event)
    }

    /** Broadcast pre-built XML (GeoChat) on the IP transports. */
    fun sendRaw(xml: String) {
        mesh?.sendRaw(xml.toByteArray(Charsets.UTF_8))
        server?.sendRaw(xml)
    }

    /**
     * Broadcast a chat message on every link.
     *
     * Chat is the one payload the transports genuinely disagree about: CoT
     * carries it as a `b-t-f` XML event, LoRa as a TAK Packet GeoChat. Callers
     * shouldn't have to know that, so the split lives here.
     */
    fun sendChat(text: String, self: CotEvent) {
        sendRaw(GeoChat.build(text, self))
        radio?.sendChat(text, self.callsign)
    }

    /** True when at least one link is carrying traffic. */
    val hasLink: Boolean
        get() = mesh != null || server != null || radio != null
}
