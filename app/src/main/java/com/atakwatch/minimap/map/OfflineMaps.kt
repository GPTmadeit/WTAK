package com.atakwatch.minimap.map

import android.content.Context
import android.util.Log
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.modules.ArchiveFileFactory
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.tilesource.FileBasedTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import java.io.File

/**
 * Offline map archives — the difference between a map that works in the field
 * and one that needs Wi-Fi.
 *
 * Drop `.mbtiles`, `.sqlite`, `.gemf` or `.zip` tile archives into the app's
 * external files directory under `maps/`:
 *
 *     adb push area.mbtiles \
 *       /sdcard/Android/data/com.atakwatch.minimap/files/maps/
 *
 * With an archive present the map renders entirely from local storage — no
 * network, no tile requests. Online sources remain available for planning.
 */
object OfflineMaps {

    private const val TAG = "OfflineMaps"
    private const val DIR = "maps"

    /** Where the user should put archives. Created on demand so it's discoverable. */
    fun mapsDir(context: Context): File =
        File(context.getExternalFilesDir(null), DIR).apply { if (!exists()) mkdirs() }

    /** Archives osmdroid can actually open, newest first. */
    fun archives(context: Context): List<File> {
        val dir = mapsDir(context)
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.length() > 0 }
            .filter { ArchiveFileFactory.isFileExtensionRegistered(it.extension.lowercase()) }
            .sortedByDescending { it.lastModified() }
    }

    fun hasArchives(context: Context): Boolean = archives(context).isNotEmpty()

    /**
     * A tile provider backed purely by the local archives, or null when there is
     * nothing to read. Caller owns the returned provider and must `detach()` it
     * when swapping away.
     */
    fun provider(context: Context): MapTileProviderBase? {
        val files = archives(context)
        if (files.isEmpty()) return null
        return runCatching {
            OfflineTileProvider(SimpleRegisterReceiver(context), files.toTypedArray())
        }.onFailure { Log.w(TAG, "offline provider failed: ${it.message}") }.getOrNull()
    }

    /**
     * Tile source matching the first archive. osmdroid resolves tiles inside an
     * archive by source name; using the file's own name is what the common
     * MOBAC/mbtiles exports expect.
     */
    fun tileSource(context: Context) =
        FileBasedTileSource.getSource(archives(context).firstOrNull()?.nameWithoutExtension ?: "offline")

    /**
     * Compact summary for the settings row. Kept short deliberately — a watch
     * row has room for a few words, and a long filename pushes the label off
     * the screen entirely.
     */
    fun summary(context: Context): String {
        val n = archives(context).size
        return when (n) {
            0 -> "none found"
            1 -> "1 file"
            else -> "$n files"
        }
    }
}
