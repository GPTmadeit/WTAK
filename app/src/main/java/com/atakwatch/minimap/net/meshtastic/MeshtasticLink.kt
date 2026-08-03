package com.atakwatch.minimap.net.meshtastic

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.atakwatch.minimap.data.ChatRepository
import com.atakwatch.minimap.data.CotRepository
import com.atakwatch.minimap.model.ChatMessage
import com.atakwatch.minimap.model.CotEvent
import com.atakwatch.minimap.model.Geo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Meshtastic radio link — team situational awareness over LoRa, with no server,
 * no cell coverage and no phone in the loop.
 *
 * The watch is a BLE central talking to a Meshtastic radio's GATT service. That
 * radio is the team's transport: position reports and GeoChat go out as
 * [MeshtasticProto.PORT_ATAK_PLUGIN] TAK Packets, the same port and payload
 * Meshtastic's own ATAK plugin speaks, so a watch, a phone running that plugin
 * and any other TAK-aware node all see each other.
 *
 * The client protocol is the documented one:
 *
 *  1. Connect, discover services, negotiate a large MTU (the default 23-byte MTU
 *     truncates almost every real packet).
 *  2. Subscribe to `fromNum`. The radio notifies on it whenever it has queued
 *     something for us.
 *  3. Write `ToRadio { want_config_id }`. The radio replays its identity and
 *     node database, then echoes the nonce back as `config_complete_id`.
 *  4. Read `fromRadio` repeatedly until it returns an empty value — that is the
 *     drain signal — and repeat that drain on every `fromNum` notification.
 *
 * GATT allows exactly one outstanding operation per connection, so every access
 * goes through [gattLock] and completes against the callback. Everything runs on
 * a background scope; a radio that vanishes mid-operation times out rather than
 * wedging the link.
 *
 * ### Airtime
 *
 * LoRa is a shared, slow, duty-cycle-limited medium — a few hundred bits per
 * second on the long-range presets. Broadcasting a position every 3 s like the
 * IP transports do would flood the mesh for the whole team. Instead this sends
 * on movement ([PLI_MOVE_METERS]) with a floor of [PLI_MIN_INTERVAL_MS] between
 * packets and a keepalive every [PLI_MAX_INTERVAL_MS] while stationary.
 */
class MeshtasticLink(context: Context) {

    private val appContext = context.applicationContext

    /**
     * What the radio told us about itself.
     *
     * Shown verbatim on the radio screen rather than being second-guessed: the
     * whole point of reading it is to tell the operator what the hardware is
     * actually doing, which is not always what they think they configured.
     */
    data class RadioProfile(
        val firmware: String? = null,
        val deviceRole: Int? = null,
        val region: Int? = null,
        val modemPreset: Int? = null,
        val hopLimit: Int? = null,
        val txEnabled: Boolean = true,
        val channel: String? = null,
        val takTeam: com.atakwatch.minimap.model.TeamColor? = null,
        val takRole: com.atakwatch.minimap.model.TeamRole? = null,
    ) {
        /** A radio with no region set is legally barred from transmitting. */
        val regionUnset: Boolean get() = region == MeshtasticProto.REGION_UNSET

        /** True once the radio is in the role and identity a TAK link wants. */
        fun matches(
            team: com.atakwatch.minimap.model.TeamColor,
            role: com.atakwatch.minimap.model.TeamRole,
        ): Boolean = deviceRole == MeshtasticProto.DEVICE_ROLE_TAK &&
            takTeam == team && takRole == role
    }

    enum class ConfigureState(val label: String) {
        IDLE("Configure"),
        WORKING("Applying…"),
        DONE("Applied"),
        FAILED("Failed"),
        NO_LINK("No radio"),
    }

    enum class State(val label: String) {
        OFF("Off"),
        NO_PERMISSION("Needs Bluetooth"),
        NO_ADAPTER("No Bluetooth"),
        ADAPTER_OFF("Bluetooth off"),
        NO_DEVICE("No radio paired"),
        BONDING("Pairing…"),
        CONNECTING("Connecting…"),
        SYNCING("Syncing…"),
        CONNECTED("Connected"),
    }

    companion object {
        private const val TAG = "MeshtasticLink"

        /** Meshtastic's BLE service and its three characteristics. */
        val SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val TORADIO_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val FROMRADIO_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        val FROMNUM_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")

        /** Firmware before 2.0 exposed fromRadio under a different id. */
        val FROMRADIO_LEGACY_UUID: UUID = UUID.fromString("8ba2bcc2-ee02-4a55-a531-c525c5e454d5")

        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Meshtastic packets can reach 512 bytes; the default 23-byte MTU can't carry them. */
        private const val TARGET_MTU = 512

        private const val GATT_TIMEOUT_MS = 8_000L
        private const val BOND_TIMEOUT_MS = 90_000L
        private const val RECONNECT_MIN_MS = 3_000L
        private const val RECONNECT_MAX_MS = 60_000L

        /** Radios drop an idle BLE link; the phone API heartbeats well inside that. */
        private const val HEARTBEAT_MS = 5 * 60_000L

        /** Position pacing — see the class docs on airtime. */
        private const val PLI_MIN_INTERVAL_MS = 30_000L
        private const val PLI_MAX_INTERVAL_MS = 5 * 60_000L
        private const val PLI_MOVE_METERS = 30.0

        /**
         * How long a mesh contact stays on the map without being heard from.
         * Far longer than the IP transports use: on LoRa, minutes of silence is
         * normal traffic shaping, not a lost contact.
         */
        private const val MESH_STALE_MS = 20 * 60_000L

        /** `BluetoothStatusCodes.SUCCESS`; inlined so minSdk 30 needn't see the class. */
        private const val STATUS_SUCCESS = 0

        /** Process-wide link state, so any screen can report the radio honestly. */
        private val _state = MutableStateFlow(State.OFF)
        val state: StateFlow<State> = _state.asStateFlow()

        /** Nodes the radio knows about — the mesh's size, shown on the radar. */
        private val _nodeCount = MutableStateFlow(0)
        val nodeCount: StateFlow<Int> = _nodeCount.asStateFlow()

        /** Name of the radio we're attached to, for the settings row. */
        private val _radioName = MutableStateFlow<String?>(null)
        val radioName: StateFlow<String?> = _radioName.asStateFlow()

        /** What the radio reports about itself once it has replayed its config. */
        private val _profile = MutableStateFlow(RadioProfile())
        val profile: StateFlow<RadioProfile> = _profile.asStateFlow()

        /** Progress of the last "configure for TAK" attempt. */
        private val _configureState = MutableStateFlow(ConfigureState.IDLE)
        val configureState: StateFlow<ConfigureState> = _configureState.asStateFlow()

        /**
         * The running link, so a screen can act on the radio without owning it.
         * There is at most one: [Transports] and the tracking service hand
         * ownership back and forth but never run two at once.
         */
        @Volatile private var active: MeshtasticLink? = null

        /** Put the attached radio into the TAK role and identity. */
        fun configureForTak(
            team: com.atakwatch.minimap.model.TeamColor,
            role: com.atakwatch.minimap.model.TeamRole,
        ) {
            val link = active
            if (link == null) { _configureState.value = ConfigureState.NO_LINK; return }
            link.applyTakConfiguration(team, role)
        }

        /** Re-read the radio's settings without changing any of them. */
        fun refreshProfile() = active?.requestProfile() ?: Unit

        fun hasPermission(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
            } else true

        val runtimePermissions: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            } else emptyArray()
    }

    // ---- connection state ----------------------------------------------------

    private var scope: CoroutineScope? = null
    private var gatt: BluetoothGatt? = null
    private var toRadio: BluetoothGattCharacteristic? = null
    private var fromRadio: BluetoothGattCharacteristic? = null

    // One GATT operation may be outstanding at a time; the result is parked in
    // whichever of these the caller armed. Written from coroutines, completed on
    // the Bluetooth binder thread, hence volatile.
    private val gattLock = Mutex()
    @Volatile private var pendingRead: CompletableDeferred<ByteArray?>? = null
    @Volatile private var pendingWrite: CompletableDeferred<Boolean>? = null
    @Volatile private var pendingDescriptor: CompletableDeferred<Boolean>? = null
    @Volatile private var pendingServices: CompletableDeferred<Boolean>? = null
    @Volatile private var pendingMtu: CompletableDeferred<Boolean>? = null
    @Volatile private var connectSignal: CompletableDeferred<Boolean>? = null

    /** Signalled by the fromNum notification and by connection loss. */
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val disconnected = Channel<Unit>(Channel.CONFLATED)

    /** Outgoing chat waiting for a link, so a dictated message isn't just lost. */
    private val outbox = Channel<ByteArray>(capacity = 16)

    /** nodeNum → best known name, so a bare position report still has a callsign. */
    private val nodeNames = ConcurrentHashMap<Int, String>()

    @Volatile private var myNodeNum: Int = 0
    @Volatile private var lastPliMillis: Long = 0
    @Volatile private var lastPliLat: Double = Double.NaN
    @Volatile private var lastPliLon: Double = Double.NaN

    val isRunning: Boolean get() = scope != null

    // ---- lifecycle -----------------------------------------------------------

    /**
     * Attach to the radio at [address] (a bonded BLE MAC chosen in settings) and
     * keep the link up until [stop]. Safe to call when already running.
     */
    fun start(address: String?, selfProvider: () -> CotEvent?) {
        if (isRunning) return
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = s
        active = this
        s.launch { linkLoop(address, selfProvider) }
    }

    fun stop() {
        val s = scope ?: return
        scope = null
        if (active === this) active = null
        s.cancel()
        // Say goodbye on a detached scope so the radio frees the client slot
        // rather than waiting out a supervision timeout — the link's own scope
        // is already cancelled and can't run anything.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                withTimeoutOrNull(2_000) { writeToRadio(MeshtasticProto.toRadioDisconnect()) }
            }
            teardown()
        }
        _state.value = State.OFF
        _nodeCount.value = 0
        _radioName.value = null
        _profile.value = RadioProfile()
        _configureState.value = ConfigureState.IDLE
    }

    // ---- outbound ------------------------------------------------------------

    /** Broadcast a CoT event over LoRa as a TAK Packet. */
    fun sendEvent(event: CotEvent) {
        val payload = MeshtasticProto.takPliPacket(event, com.atakwatch.minimap.net.DeviceIdentity.uid)
        enqueue(MeshtasticProto.PORT_ATAK_PLUGIN, payload)
    }

    /** Broadcast a chat message over LoRa as a TAK Packet GeoChat. */
    fun sendChat(text: String, callsign: String) {
        val payload = MeshtasticProto.takChatPacket(
            text, callsign, com.atakwatch.minimap.net.DeviceIdentity.uid,
        )
        enqueue(MeshtasticProto.PORT_ATAK_PLUGIN, payload)
    }

    /**
     * Put the radio into the role and identity a TAK link wants.
     *
     * Three changes, wrapped in a begin/commit pair so the radio applies them
     * as one edit: device role to [MeshtasticProto.DEVICE_ROLE_TAK], and the
     * radio's own TAK team and role set to match the watch's. The operator
     * chooses their team once, on the device they can actually read.
     *
     * Region is deliberately *not* touched. Which band a radio may transmit on
     * is a regulatory decision belonging to whoever operates it, not something
     * an app should pick on their behalf — so an unset region is reported
     * loudly and left alone.
     */
    private fun applyTakConfiguration(
        team: com.atakwatch.minimap.model.TeamColor,
        role: com.atakwatch.minimap.model.TeamRole,
    ) {
        val s = scope
        if (s == null || _state.value != State.CONNECTED) {
            _configureState.value = ConfigureState.NO_LINK
            return
        }
        _configureState.value = ConfigureState.WORKING
        s.launch {
            val ok = runCatching {
                sendAdmin(MeshtasticProto.adminBeginEdit()) &&
                    sendAdmin(MeshtasticProto.adminSetDeviceRole(MeshtasticProto.DEVICE_ROLE_TAK)) &&
                    sendAdmin(MeshtasticProto.adminSetTakModule(team, role)) &&
                    sendAdmin(MeshtasticProto.adminCommitEdit())
            }.getOrDefault(false)

            _configureState.value = if (ok) ConfigureState.DONE else ConfigureState.FAILED
            if (ok) {
                Log.i(TAG, "radio configured as a TAK connector (${team.label}/${role.label})")
                // Committing reboots the radio, so the link will drop and come
                // back; ask for the new state once it does.
                _profile.value = _profile.value.copy(
                    deviceRole = MeshtasticProto.DEVICE_ROLE_TAK,
                    takTeam = team,
                    takRole = role,
                )
            }
        }
    }

    /** Re-read the settings we display, without changing anything. */
    private fun requestProfile() {
        val s = scope ?: return
        s.launch {
            sendAdmin(MeshtasticProto.adminGetConfig(MeshtasticProto.CONFIG_DEVICE))
            sendAdmin(MeshtasticProto.adminGetConfig(MeshtasticProto.CONFIG_LORA))
            sendAdmin(MeshtasticProto.adminGetModuleConfig(MeshtasticProto.MODULE_CONFIG_TAK))
        }
    }

    /**
     * Admin traffic is addressed to our own node, not broadcast — this is the
     * radio administering itself on behalf of its local client.
     */
    private suspend fun sendAdmin(payload: ByteArray): Boolean {
        val target = myNodeNum
        if (target == 0) return false
        val packet = MeshtasticProto.meshPacket(
            payload = payload,
            portNum = MeshtasticProto.PORT_ADMIN,
            packetId = nextPacketId(),
            to = target,
            wantAck = true,
        )
        return writeToRadio(MeshtasticProto.toRadioPacket(packet))
    }

    private fun enqueue(portNum: Int, payload: ByteArray) {
        val packet = MeshtasticProto.meshPacket(
            payload = payload,
            portNum = portNum,
            packetId = nextPacketId(),
        )
        val frame = MeshtasticProto.toRadioPacket(packet)
        // trySend drops rather than blocks: a full outbox means the radio is
        // gone, and a stale position is worth less than the next fresh one.
        if (outbox.trySend(frame).isFailure) Log.w(TAG, "outbox full, dropped ${frame.size} B")
    }

    /** Mesh packet ids are 32-bit and must be non-zero. */
    private fun nextPacketId(): Int {
        var id = Random.nextInt()
        while (id == 0) id = Random.nextInt()
        return id
    }

    // ---- the link loop -------------------------------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun linkLoop(address: String?, selfProvider: () -> CotEvent?) {
        var backoff = RECONNECT_MIN_MS
        val cs = scope ?: return

        while (cs.isActive) {
            val device = resolveDevice(address)
            if (device == null) {
                // State was set by resolveDevice; nothing to retry quickly for.
                delay(RECONNECT_MAX_MS)
                continue
            }
            _radioName.value = runCatching { device.name }.getOrNull() ?: device.address

            // Meshtastic requires an encrypted link; without a bond every read
            // fails with an authentication error that looks like a broken radio.
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                _state.value = State.BONDING
                if (!awaitBond(device)) {
                    Log.w(TAG, "bonding with ${device.address} failed")
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(RECONNECT_MAX_MS)
                    continue
                }
            }

            _state.value = State.CONNECTING
            val ok = runCatching { session(device, selfProvider) }
                .onFailure { if (cs.isActive) Log.w(TAG, "session ended: ${it.message}") }
                .getOrDefault(false)

            teardown()
            if (!cs.isActive) break
            _state.value = State.CONNECTING
            backoff = if (ok) RECONNECT_MIN_MS else (backoff * 2).coerceAtMost(RECONNECT_MAX_MS)
            delay(backoff)
        }
        _state.value = State.OFF
    }

    @SuppressLint("MissingPermission")
    private fun resolveDevice(address: String?): BluetoothDevice? {
        if (!hasPermission(appContext)) { _state.value = State.NO_PERMISSION; return null }
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = manager?.adapter
        if (adapter == null) { _state.value = State.NO_ADAPTER; return null }
        if (!adapter.isEnabled) { _state.value = State.ADAPTER_OFF; return null }
        if (address.isNullOrBlank()) { _state.value = State.NO_DEVICE; return null }
        return runCatching { adapter.getRemoteDevice(address) }.getOrNull()
            .also { if (it == null) _state.value = State.NO_DEVICE }
    }

    /**
     * One connected session: handshake, then pump traffic until the radio goes
     * away. Returns true if we reached a synced state, which resets the backoff.
     */
    @SuppressLint("MissingPermission")
    private suspend fun session(device: BluetoothDevice, selfProvider: () -> CotEvent?): Boolean {
        val cs = scope ?: return false

        // Drain any stale wake-ups from a previous session.
        while (wakeups.tryReceive().isSuccess) { /* discard */ }
        while (disconnected.tryReceive().isSuccess) { /* discard */ }

        val connectedSignal = CompletableDeferred<Boolean>()
        this.connectSignal = connectedSignal

        val g = device.connectGatt(
            appContext, /* autoConnect = */ false, callback, BluetoothDevice.TRANSPORT_LE,
        ) ?: return false
        gatt = g

        val connected = withTimeoutOrNull(GATT_TIMEOUT_MS * 2) { connectedSignal.await() } ?: false
        if (!connected) { Log.w(TAG, "connect timed out"); return false }

        // MTU first: everything after this reads or writes packets that do not
        // fit in the default 23-byte MTU.
        if (!request { pendingMtu = it; g.requestMtu(TARGET_MTU) }) {
            Log.w(TAG, "MTU negotiation failed; continuing at default")
        }

        if (!request { pendingServices = it; g.discoverServices() }) {
            Log.w(TAG, "service discovery failed")
            return false
        }

        val service = g.getService(SERVICE_UUID) ?: run {
            Log.w(TAG, "${device.address} is not a Meshtastic radio")
            return false
        }
        toRadio = service.getCharacteristic(TORADIO_UUID)
        fromRadio = service.getCharacteristic(FROMRADIO_UUID)
            ?: service.getCharacteristic(FROMRADIO_LEGACY_UUID)
        val fromNum = service.getCharacteristic(FROMNUM_UUID)
        if (toRadio == null || fromRadio == null) {
            Log.w(TAG, "radio is missing toRadio/fromRadio")
            return false
        }

        // Subscribe before asking for config, or the first notification races us.
        if (fromNum != null) enableNotifications(g, fromNum)

        _state.value = State.SYNCING

        // Clear anything the radio queued while we were away, then ask it to
        // replay its identity and node database.
        drainFromRadio()
        val nonce = nextPacketId()
        if (!writeToRadio(MeshtasticProto.toRadioWantConfig(nonce))) {
            Log.w(TAG, "want_config write failed")
            return false
        }

        // The config burst arrives as ordinary fromRadio traffic.
        val synced = withTimeoutOrNull(15_000) {
            while (cs.isActive) {
                if (drainFromRadio(untilConfigId = nonce)) return@withTimeoutOrNull true
                withTimeoutOrNull(2_000) { wakeups.receive() }
            }
            false
        } ?: false

        if (!synced) Log.w(TAG, "config sync did not complete; running anyway")
        _state.value = State.CONNECTED
        Log.i(TAG, "linked to ${device.address} as node ${MeshtasticProto.nodeId(myNodeNum)}, " +
            "${nodeNames.size} nodes known")

        // Pumps: inbound drain, outbound queue, position pacing, heartbeat. Each
        // runs until the session ends so none of them can starve another.
        val jobs = listOf(
            cs.launch { inboundPump() },
            cs.launch { outboundPump() },
            cs.launch { positionPump(selfProvider) },
            cs.launch { heartbeatPump() },
        )

        disconnected.receive()
        jobs.forEach { it.cancel() }
        return synced
    }

    private suspend fun inboundPump() {
        val cs = scope ?: return
        while (cs.isActive) {
            wakeups.receive()
            drainFromRadio()
        }
    }

    private suspend fun outboundPump() {
        val cs = scope ?: return
        while (cs.isActive) {
            val frame = outbox.receive()
            if (!writeToRadio(frame)) {
                Log.w(TAG, "send failed (${frame.size} B)")
                disconnected.trySend(Unit)
            }
        }
    }

    private suspend fun heartbeatPump() {
        val cs = scope ?: return
        while (cs.isActive) {
            delay(HEARTBEAT_MS)
            if (!writeToRadio(MeshtasticProto.toRadioHeartbeat())) disconnected.trySend(Unit)
        }
    }

    /**
     * Position pacing. Sends when you have actually moved, with a hard floor
     * between packets and a slow keepalive when you haven't — so a stationary
     * team doesn't spend the mesh's airtime saying nothing.
     */
    private suspend fun positionPump(selfProvider: () -> CotEvent?) {
        val cs = scope ?: return
        // Announce immediately on connect: the team should see you join.
        lastPliMillis = 0
        while (cs.isActive) {
            val self = selfProvider()
            if (self != null) {
                val now = System.currentTimeMillis()
                val sinceLast = now - lastPliMillis
                val moved = if (lastPliLat.isNaN()) Double.MAX_VALUE
                else Geo.distanceMeters(lastPliLat, lastPliLon, self.lat, self.lon)

                val due = sinceLast >= PLI_MAX_INTERVAL_MS ||
                    (sinceLast >= PLI_MIN_INTERVAL_MS && moved >= PLI_MOVE_METERS)
                if (due) {
                    sendEvent(self)
                    lastPliMillis = now
                    lastPliLat = self.lat
                    lastPliLon = self.lon
                }
            }
            delay(5_000)
        }
    }

    // ---- GATT plumbing -------------------------------------------------------

    /**
     * Run one GATT operation under the single-operation-at-a-time rule, and wait
     * for its callback. [issue] receives the deferred to park the result in and
     * must return the framework's own "did the request start" boolean.
     */
    private suspend fun request(issue: (CompletableDeferred<Boolean>) -> Boolean): Boolean =
        gattLock.withLock {
            val deferred = CompletableDeferred<Boolean>()
            val started = runCatching { issue(deferred) }.getOrDefault(false)
            if (!started) { clearPending(); return@withLock false }
            val result = withTimeoutOrNull(GATT_TIMEOUT_MS) { deferred.await() }
            clearPending()
            result ?: false
        }

    private fun clearPending() {
        pendingWrite = null; pendingDescriptor = null
        pendingServices = null; pendingMtu = null; pendingRead = null
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun writeToRadio(bytes: ByteArray): Boolean {
        val g = gatt ?: return false
        val ch = toRadio ?: return false
        return request { deferred ->
            pendingWrite = deferred
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    STATUS_SUCCESS
            } else {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = bytes
                g.writeCharacteristic(ch)
            }
        }
    }

    /** `BluetoothStatusCodes.SUCCESS`, inlined so minSdk 30 doesn't need the class. */
    private val STATUS_SUCCESS = 0

    @SuppressLint("MissingPermission")
    private suspend fun readFromRadio(): ByteArray? {
        val g = gatt ?: return null
        val ch = fromRadio ?: return null
        return gattLock.withLock {
            val deferred = CompletableDeferred<ByteArray?>()
            pendingRead = deferred
            val started = runCatching { g.readCharacteristic(ch) }.getOrDefault(false)
            if (!started) { clearPending(); return@withLock null }
            val result = withTimeoutOrNull(GATT_TIMEOUT_MS) { deferred.await() }
            clearPending()
            result
        }
    }

    /**
     * Read fromRadio until the radio hands back an empty value, which is how it
     * says "nothing more queued". Returns true if [untilConfigId] was echoed
     * back during this drain.
     */
    private suspend fun drainFromRadio(untilConfigId: Int? = null): Boolean {
        var sawConfigComplete = false
        // A radio that never empties must not spin us forever.
        repeat(256) {
            val bytes = readFromRadio() ?: return sawConfigComplete
            if (bytes.isEmpty()) return sawConfigComplete
            val inbound = MeshtasticProto.decodeFromRadio(bytes)
            if (inbound is MeshtasticProto.Inbound.ConfigComplete &&
                untilConfigId != null && inbound.id == untilConfigId
            ) {
                sawConfigComplete = true
            }
            inbound?.let { handle(it) }
        }
        return sawConfigComplete
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun enableNotifications(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        if (!g.setCharacteristicNotification(ch, true)) return false
        val cccd = ch.getDescriptor(CCCD_UUID) ?: return false
        return request { deferred ->
            pendingDescriptor = deferred
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    STATUS_SUCCESS
            } else {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun teardown() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        toRadio = null
        fromRadio = null
        connectSignal = null
        clearPending()
    }

    @Suppress("DEPRECATION")
    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> connectSignal?.complete(status == BluetoothGatt.GATT_SUCCESS)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "disconnected (status $status)")
                    connectSignal?.complete(false)
                    // Unblock anything parked on a callback that will never come.
                    pendingRead?.complete(null)
                    pendingWrite?.complete(false)
                    pendingDescriptor?.complete(false)
                    pendingServices?.complete(false)
                    pendingMtu?.complete(false)
                    disconnected.trySend(Unit)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU now $mtu (status $status)")
            pendingMtu?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pendingServices?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // The API 33 overloads' default implementations forward to these, so
        // overriding the older signatures covers every supported platform.
        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int,
        ) {
            pendingRead?.complete(
                if (status == BluetoothGatt.GATT_SUCCESS) ch.value ?: ByteArray(0) else null
            )
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int,
        ) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int,
        ) {
            pendingDescriptor?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            // fromNum carries a counter; the value doesn't matter, the fact that
            // it fired does — it means "read fromRadio until it's empty".
            if (ch.uuid == FROMNUM_UUID) wakeups.trySend(Unit)
        }
    }

    // ---- bonding -------------------------------------------------------------

    /**
     * Meshtastic radios require an encrypted link, so the watch has to bond
     * first. Android drives the PIN prompt; we just wait for the outcome.
     */
    @SuppressLint("MissingPermission")
    private suspend fun awaitBond(device: BluetoothDevice): Boolean {
        val done = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                @Suppress("DEPRECATION")
                val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (d?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                    BluetoothDevice.BOND_BONDED -> done.complete(true)
                    BluetoothDevice.BOND_NONE -> done.complete(false)
                }
            }
        }
        ContextCompat.registerReceiver(
            appContext, receiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return try {
            if (!device.createBond()) return false
            withTimeoutOrNull(BOND_TIMEOUT_MS) { done.await() } ?: false
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    // ---- inbound handling ----------------------------------------------------

    private fun handle(inbound: MeshtasticProto.Inbound) {
        when (inbound) {
            is MeshtasticProto.Inbound.MyInfo -> {
                myNodeNum = inbound.nodeNum
                Log.d(TAG, "our node number is ${MeshtasticProto.nodeId(inbound.nodeNum)}")
            }

            is MeshtasticProto.Inbound.ConfigComplete ->
                Log.d(TAG, "config replay complete (${inbound.id})")

            is MeshtasticProto.Inbound.Metadata ->
                _profile.value = _profile.value.copy(firmware = inbound.firmwareVersion)

            is MeshtasticProto.Inbound.DeviceConfig -> {
                _profile.value = _profile.value.copy(deviceRole = inbound.role)
                Log.d(TAG, "radio role: ${MeshtasticProto.deviceRoleName(inbound.role)}")
            }

            is MeshtasticProto.Inbound.LoRaConfig -> {
                _profile.value = _profile.value.copy(
                    region = inbound.region,
                    modemPreset = inbound.modemPreset,
                    hopLimit = inbound.hopLimit,
                    txEnabled = inbound.txEnabled,
                )
                if (inbound.region == MeshtasticProto.REGION_UNSET) {
                    // Worth a warning in the log as well as on screen: this is
                    // the single most common reason a mesh looks dead.
                    Log.w(TAG, "radio region is UNSET — it cannot legally transmit")
                }
            }

            is MeshtasticProto.Inbound.TakConfig ->
                _profile.value = _profile.value.copy(
                    takTeam = inbound.team, takRole = inbound.role,
                )

            is MeshtasticProto.Inbound.ChannelInfo ->
                if (inbound.isPrimary) {
                    _profile.value = _profile.value.copy(channel = inbound.name)
                }

            is MeshtasticProto.Inbound.Node -> {
                if (inbound.nodeNum == myNodeNum) return
                val name = inbound.longName?.takeIf { it.isNotBlank() }
                    ?: inbound.shortName?.takeIf { it.isNotBlank() }
                    ?: inbound.id
                name?.let { nodeNames[inbound.nodeNum] = it }
                _nodeCount.value = nodeNames.size
                // The node database replays positions too, so a node that was
                // heard before we connected is on the map immediately.
                if (inbound.lat != null && inbound.lon != null) {
                    CotRepository.upsertNetwork(
                        MeshtasticProto.toCotEvent(
                            inbound.nodeNum, name, inbound.lat, inbound.lon, inbound.alt,
                            inbound.timeMillis, MESH_STALE_MS,
                        )
                    )
                }
            }

            is MeshtasticProto.Inbound.Position -> {
                if (inbound.nodeNum == myNodeNum) return
                CotRepository.upsertNetwork(
                    MeshtasticProto.toCotEvent(
                        inbound.nodeNum, nodeNames[inbound.nodeNum],
                        inbound.lat, inbound.lon, inbound.alt,
                        inbound.timeMillis, MESH_STALE_MS,
                    )
                )
            }

            is MeshtasticProto.Inbound.TakPli -> {
                if (inbound.nodeNum == myNodeNum) return
                val event = MeshtasticProto.toCotEvent(inbound, MESH_STALE_MS)
                // Our own report heard back off the mesh is not a contact.
                if (event.uid == com.atakwatch.minimap.net.DeviceIdentity.uid) return
                inbound.callsign?.let { nodeNames[inbound.nodeNum] = it }
                _nodeCount.value = nodeNames.size
                Log.d(TAG, "TAK PLI from ${event.callsign} over LoRa")
                CotRepository.upsertNetwork(event)
            }

            is MeshtasticProto.Inbound.Text -> {
                if (inbound.nodeNum == myNodeNum) return
                val sender = inbound.callsign?.takeIf { it.isNotBlank() }
                    ?: nodeNames[inbound.nodeNum]
                    ?: MeshtasticProto.nodeId(inbound.nodeNum)
                ChatRepository.add(
                    ChatMessage(
                        // Keyed on the mesh packet id so a rebroadcast heard
                        // twice is one message.
                        id = "mesh-${inbound.packetId.toUInt().toString(16)}",
                        senderCallsign = sender,
                        text = inbound.text,
                        timeMillis = inbound.timeMillis,
                        senderUid = "MESH-${MeshtasticProto.nodeId(inbound.nodeNum)}",
                    )
                )
            }
        }
    }
}
