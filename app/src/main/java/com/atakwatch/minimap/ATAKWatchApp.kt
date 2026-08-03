package com.atakwatch.minimap

import android.app.Application
import android.content.Context
import com.atakwatch.minimap.data.CotRepository
import com.atakwatch.minimap.data.SettingsRepository
import org.osmdroid.config.Configuration

/**
 * Application entry point — the rough equivalent of an ATAK plugin's
 * lifecycle/`IPlugin` object: it wires up shared services (settings, the tile
 * engine) that the rest of the app builds on.
 */
class ATAKWatchApp : Application() {

    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)

        // osmdroid: disk-cached tiles, no external storage, descriptive user agent.
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        // TAK client identity: ANDROID-<id> uid + takv fields, like a real EUD.
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "0"
        com.atakwatch.minimap.net.DeviceIdentity.init(this, version)

        // Restore user waypoints from disk.
        CotRepository.loadWaypoints(this)
    }
}
