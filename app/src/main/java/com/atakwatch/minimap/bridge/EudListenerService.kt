package com.atakwatch.minimap.bridge

import android.util.Log
import com.atakwatch.minimap.data.CotRepository
import com.atakwatch.minimap.net.TakProtocol
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives CoT relayed from the EUD phone.
 *
 * This is the payoff of pairing: ATAK on the phone is already connected to the
 * team — mesh, TAK server, whatever the operator configured — so the watch can
 * show the same picture without running its own radios. On a 455 mAh watch that
 * is the difference between hours and a full day.
 *
 * The service is started by the platform when a message arrives, so it works
 * whether or not the app is open.
 */
class EudListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            EudProtocol.PATH_COT -> ingestCot(event.data)
            else -> super.onMessageReceived(event)
        }
    }

    private fun ingestCot(data: ByteArray) {
        if (data.isEmpty()) return
        // The phone may relay either legacy CoT XML or a TAK protobuf frame;
        // decodeDatagram sniffs which and never throws on malformed input.
        val event = TakProtocol.decodeDatagram(data, data.size) ?: return
        if (!event.type.isRenderable) return
        Log.d(TAG, "relayed ${event.type.raw} '${event.callsign}' from EUD")
        CotRepository.upsertNetwork(event)
    }

    private companion object {
        const val TAG = "EudListener"
    }
}
