package com.atakwatch.minimap.net.meshtastic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Finds Meshtastic radios in range.
 *
 * Filtered on the Meshtastic service UUID at the scanner, so the radio's own
 * hardware filter does the work and the watch's CPU never sees the rest of the
 * BLE noise around it — which matters when the scan is running on a 455 mAh
 * battery. Results accumulate and are re-emitted whenever one changes, strongest
 * signal first, because the radio in your pack should be the top of the list.
 */
object MeshtasticScanner {

    private const val TAG = "MeshtasticScan"

    data class Radio(
        val address: String,
        val name: String,
        val rssi: Int,
        val bonded: Boolean,
    ) {
        /** Rough proximity for the picker — a radio on your body pegs the meter. */
        val signal: Int get() = when {
            rssi >= -60 -> 3
            rssi >= -75 -> 2
            rssi >= -90 -> 1
            else -> 0
        }
    }

    /**
     * Emits the growing set of radios seen. Cancel the collection to stop the
     * scan — BLE scanning is expensive and must not outlive the screen using it.
     */
    @SuppressLint("MissingPermission")
    fun scan(context: Context): Flow<List<Radio>> {
        if (!MeshtasticLink.hasPermission(context)) return flowOf(emptyList())
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: return flowOf(emptyList())

        return callbackFlow {
            val found = LinkedHashMap<String, Radio>()

            // A radio already bonded to this watch may be connected and therefore
            // silent, so seed it from the bond list. Only devices whose cached
            // GATT services include the Meshtastic service qualify — the bond
            // list also holds the paired phone, earbuds and anything else, none
            // of which belong in a radio picker.
            runCatching {
                val meshUuid = ParcelUuid(MeshtasticLink.SERVICE_UUID)
                adapter.bondedDevices.orEmpty()
                    .filter { it.uuids?.any { u -> u == meshUuid } == true }
                    .forEach { d ->
                        found[d.address] = Radio(
                            address = d.address,
                            name = d.name ?: d.address,
                            rssi = 0,
                            bonded = true,
                        )
                    }
            }
            if (found.isNotEmpty()) trySend(found.values.sortedByDescending { it.rssi })

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device: BluetoothDevice = result.device
                    val radio = Radio(
                        address = device.address,
                        name = result.scanRecord?.deviceName
                            ?: runCatching { device.name }.getOrNull()
                            ?: device.address,
                        rssi = result.rssi,
                        bonded = device.bondState == BluetoothDevice.BOND_BONDED,
                    )
                    val previous = found.put(device.address, radio)
                    // Only wake the UI when something actually changed; a radio
                    // advertises several times a second.
                    if (previous == null || previous.signal != radio.signal || previous.name != radio.name) {
                        trySend(found.values.sortedByDescending { it.rssi })
                    }
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "scan failed: $errorCode")
                    close()
                }
            }

            val filters = listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(MeshtasticLink.SERVICE_UUID))
                    .build()
            )
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            runCatching { scanner.startScan(filters, settings, callback) }
                .onFailure { Log.w(TAG, "startScan: ${it.message}"); close() }

            awaitClose { runCatching { scanner.stopScan(callback) } }
        }
    }
}
