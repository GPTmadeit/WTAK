package com.atakwatch.minimap.data

import com.atakwatch.minimap.model.CotEvent
import com.atakwatch.minimap.model.CotType
import com.atakwatch.minimap.model.EmergencyType
import com.atakwatch.minimap.net.DeviceIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Your own emergency beacon.
 *
 * While active, an alert event is broadcast in place of nothing — it reuses the
 * self UID with an `-9-1-1` suffix, which is the convention ATAK follows, so a
 * client that already tracks you associates the alert with you rather than
 * showing an unrelated contact.
 *
 * Raising an alert is deliberately a held gesture and cancelling is one tap:
 * a false alarm should be easy to stop, and a real one hard to trigger by accident.
 */
object EmergencyBeacon {

    private val _active = MutableStateFlow<EmergencyType?>(null)
    val active: StateFlow<EmergencyType?> = _active.asStateFlow()

    fun raise(type: EmergencyType) { _active.value = type }

    fun clear() { _active.value = null }

    val isActive: Boolean get() = _active.value != null

    /**
     * The event to transmit for the current state: the alert while active, or a
     * single cancel event to stand it down. Null when there is nothing to send.
     */
    fun buildEvent(self: CotEvent?, cancelling: Boolean = false): CotEvent? {
        self ?: return null
        val type = _active.value
        if (type == null && !cancelling) return null

        val now = System.currentTimeMillis()
        return CotEvent(
            uid = "${DeviceIdentity.uid}-9-1-1",
            callsign = self.callsign,
            type = CotType(if (cancelling) EmergencyType.CANCEL_TYPE else type!!.cotType),
            lat = self.lat,
            lon = self.lon,
            hae = self.hae,
            ce = self.ce,
            le = self.le,
            timeMillis = now,
            // Alerts are re-sent while active, so a short stale keeps a stale
            // alert from lingering on the network if the watch goes silent.
            staleMillis = now + 90_000,
            teamName = self.teamName,
            teamRole = self.teamRole,
            emergency = (type ?: EmergencyType.NINE_ONE_ONE).emergencyLabel,
            emergencyCancel = cancelling,
        )
    }
}
