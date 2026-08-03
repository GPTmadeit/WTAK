package com.atakwatch.minimap.data

import android.content.Context
import com.atakwatch.minimap.model.CotEvent
import com.atakwatch.minimap.model.CotType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory store of CoT entities — analogous to ATAK's MapComponent holding the
 * map's markers. Tracks the self marker, live network contacts (from CoT mesh or
 * a TAK server) and user waypoints, and publishes a single combined [entities]
 * stream. Mutations recompute that list once, so the map/contacts UI only
 * re-diffs when something actually changed.
 *
 * Every entity here is real: either received over the wire or placed by the user.
 */
object CotRepository {

    private val _self = MutableStateFlow<CotEvent?>(null)
    val self: StateFlow<CotEvent?> = _self.asStateFlow()

    private val network = LinkedHashMap<String, CotEvent>()
    private var waypoints: List<CotEvent> = emptyList()

    private val _entities = MutableStateFlow<List<CotEvent>>(emptyList())
    /** Every non-self entity: network contacts + user waypoints. */
    val entities: StateFlow<List<CotEvent>> = _entities.asStateFlow()

    private val _focus = MutableStateFlow<Pair<Double, Double>?>(null)
    val focus: StateFlow<Pair<Double, Double>?> = _focus.asStateFlow()

    /**
     * The entity you are navigating to ("bloodhound" in ATAK terms). While set,
     * the map draws a line to it and the HUD reports range and bearing
     * continuously, so you can walk it in without looking for it in the roster.
     */
    private val _navTargetUid = MutableStateFlow<String?>(null)
    val navTargetUid: StateFlow<String?> = _navTargetUid.asStateFlow()

    fun setNavTarget(uid: String?) { _navTargetUid.value = uid }

    /** Resolve the current nav target against live entities, or null if it's gone. */
    fun navTarget(): CotEvent? {
        val uid = _navTargetUid.value ?: return null
        return _entities.value.firstOrNull { it.uid == uid }
    }

    @Synchronized
    private fun recompute() {
        val all = synchronized(network) { network.values.toList() } + waypoints
        _entities.value = all
        // Don't keep navigating to something that no longer exists — deleted,
        // pruned as stale, or simply no longer reported.
        val target = _navTargetUid.value
        if (target != null && all.none { it.uid == target }) _navTargetUid.value = null
    }

    /** Publish the self PLI (built by [SelfEventFactory]). */
    fun setSelf(event: CotEvent) { _self.value = event }

    fun requestFocus(lat: Double, lon: Double) { _focus.value = lat to lon }
    fun consumeFocus() { _focus.value = null }

    // ---- Network (CoT mesh / TAK server) -------------------------------------

    fun upsertNetwork(event: CotEvent) {
        // Never ingest our own traffic looped back off the network. This covers
        // the self PLI and anything derived from our UID — notably our own
        // emergency beacon, whose uid is "<selfUid>-9-1-1" and would otherwise
        // show up as a separate contact standing on top of us.
        val selfUid = com.atakwatch.minimap.net.DeviceIdentity.uid
        if (event.uid == selfUid || event.uid.startsWith("$selfUid-")) return
        if (event.uid == _self.value?.uid) return
        synchronized(network) { network[event.uid] = event }
        recompute()
    }

    fun pruneStale(nowMillis: Long) {
        val changed = synchronized(network) {
            var c = false
            val it = network.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value.staleMillis < nowMillis) { it.remove(); c = true }
            }
            c
        }
        if (changed) recompute()
    }

    // ---- Waypoints -----------------------------------------------------------

    fun loadWaypoints(context: Context) { waypoints = WaypointStore.load(context); recompute() }

    fun addWaypoint(context: Context, lat: Double, lon: Double, name: String): CotEvent {
        val wp = CotEvent(
            uid = "WP-${System.currentTimeMillis()}", callsign = name,
            type = CotType.waypoint(), lat = lat, lon = lon, staleMillis = Long.MAX_VALUE,
        )
        waypoints = waypoints + wp
        WaypointStore.save(context, waypoints)
        recompute()
        return wp
    }

    /** Rename a waypoint in place, keeping its uid so nav targets survive. */
    fun renameWaypoint(context: Context, uid: String, name: String) {
        val clean = name.trim().take(32)
        if (clean.isEmpty()) return
        waypoints = waypoints.map { if (it.uid == uid) it.copy(callsign = clean) else it }
        WaypointStore.save(context, waypoints)
        recompute()
    }

    fun removeWaypoint(context: Context, uid: String) {
        waypoints = waypoints.filterNot { it.uid == uid }
        WaypointStore.save(context, waypoints)
        recompute()
    }

    val waypointCount: Int get() = waypoints.size
}
