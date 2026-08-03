package com.atakwatch.minimap.bridge

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Talks to a paired EUD phone running ATAK (via the companion plugin) over the
 * Wearable Data Layer.
 *
 * This is what makes the watch a *member* of an existing TAK setup rather than
 * a device you configure twice: the operator's callsign, team, role and server
 * are already correct in ATAK, so the watch reads them instead of asking.
 *
 * Everything degrades cleanly — no phone, no plugin, or no Play Services all
 * end in [Result.Unavailable] rather than an error the user can't act on.
 */
object PhoneBridge {

    private const val TAG = "PhoneBridge"

    sealed interface Result {
        /** Identity pulled successfully. */
        data class Success(val identity: EudProtocol.Identity, val node: String) : Result
        /** No reachable phone advertising the bridge capability. */
        data object Unavailable : Result
        /** A phone is reachable but has published nothing yet. */
        data object NoData : Result
        data class Failed(val reason: String) : Result
    }

    private val _lastResult = MutableStateFlow<Result?>(null)
    val lastResult: StateFlow<Result?> = _lastResult.asStateFlow()

    /** True when a paired phone currently advertises the bridge capability. */
    suspend fun isCompanionAvailable(context: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Wearable.getCapabilityClient(context)
                .getCapability(EudProtocol.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes.isNotEmpty()
        }.getOrElse {
            Log.d(TAG, "capability lookup failed: ${it.message}")
            false
        }
    }

    /**
     * Read the identity the phone published. Asks the phone to refresh first;
     * if that message can't be delivered we still try whatever data item is
     * already synced, so a phone that has since gone out of range still
     * onboards the watch.
     */
    suspend fun pullIdentity(context: Context): Result = withContext(Dispatchers.IO) {
        val result = runCatching { doPull(context) }
            .getOrElse { Result.Failed(it.message ?: it.javaClass.simpleName) }
        _lastResult.value = result
        result
    }

    private suspend fun doPull(context: Context): Result {
        val capability = runCatching {
            Wearable.getCapabilityClient(context)
                .getCapability(EudProtocol.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .await()
        }.getOrNull()

        val node = capability?.nodes?.firstOrNull()

        // Nudge the phone to republish, so we get current values rather than
        // whatever was cached from a previous session. Best-effort by design.
        if (node != null) {
            runCatching {
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, EudProtocol.PATH_REQUEST_SYNC, ByteArray(0))
                    .await()
            }.onFailure { Log.d(TAG, "sync request not delivered: ${it.message}") }
        }

        val nodeName: String = node?.let { runCatching { it.displayName }.getOrNull() ?: it.id } ?: "phone"

        val buffer = runCatching {
            Wearable.getDataClient(context).getDataItems(
                Uri.Builder().scheme("wear").path(EudProtocol.PATH_IDENTITY).build()
            ).await()
        }.getOrNull() ?: return if (node == null) Result.Unavailable else Result.NoData

        // The buffer wraps shared memory and must be closed, or it leaks.
        var identity: EudProtocol.Identity? = null
        buffer.use { b ->
            for (i in 0 until b.count) {
                identity = readIdentity(b.get(i))
                if (identity != null) break
            }
        }

        val found = identity
        return when {
            found != null -> Result.Success(found, nodeName)
            node != null -> Result.NoData
            else -> Result.Unavailable
        }
    }

    private fun readIdentity(item: DataItem): EudProtocol.Identity? = runCatching {
        val json = DataMapItem.fromDataItem(item).dataMap.getString(EudProtocol.KEY_PAYLOAD)
        json?.let { EudProtocol.Identity.fromJson(it) }
    }.getOrNull()
}
