package com.atakwatch.minimap.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.atakwatch.minimap.model.Affiliation
import com.atakwatch.minimap.model.TeamColor
import com.atakwatch.minimap.model.TeamRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class CoordFormat(val label: String) { MGRS("MGRS"), LATLON("Lat / Lon") }
enum class MapSource(val label: String) {
    STANDARD("Standard"), TOPO("Topographic"), OFFLINE("Offline")
}
enum class MapOrientation(val label: String) { NORTH_UP("North up"), HEADING_UP("Track up") }
enum class MeshFormat(val label: String) { TAK_PROTO("TAK proto"), LEGACY_XML("Legacy XML") }

/** User-configurable state for the app. */
data class Settings(
    val callsign: String = "WATCH-1",
    val selfAffiliation: Affiliation = Affiliation.FRIEND,
    val teamColor: TeamColor = TeamColor.CYAN,
    val teamRole: TeamRole = TeamRole.TEAM_MEMBER,
    val coordFormat: CoordFormat = CoordFormat.MGRS,
    val imperialUnits: Boolean = false,
    val mapSource: MapSource = MapSource.STANDARD,
    val mapOrientation: MapOrientation = MapOrientation.NORTH_UP,
    val keepScreenOn: Boolean = true,
    val followGps: Boolean = true,
    /** ATAK-style distance rings around your own position. */
    val rangeRings: Boolean = false,
    /** Animated sweep on the radar scope. Off saves a continuous animation. */
    val radarSweep: Boolean = true,
    val cotMesh: Boolean = false,
    val meshFormat: MeshFormat = MeshFormat.TAK_PROTO,
    val takServer: Boolean = false,
    val takServerHost: String = "10.0.2.2:8087",
    val takTls: Boolean = false,
    /** Team link over a Meshtastic LoRa radio — no server, no IP network. */
    val meshtastic: Boolean = false,
    /** BLE address of the paired radio, empty until one is chosen. */
    val meshtasticAddress: String = "",
    /** Its advertised name, so settings can show it without a scan. */
    val meshtasticName: String = "",
    /** Keep tracking + CoT publishing running with the screen off. */
    val backgroundTracking: Boolean = false,
    /** First-run setup finished (from a paired EUD or on the watch). */
    val onboarded: Boolean = false,
    /** One-time gesture hint on the map, cleared after it has been seen. */
    val mapHintShown: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("atak_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val CALLSIGN = stringPreferencesKey("callsign")
        val AFFILIATION = stringPreferencesKey("affiliation")
        val TEAM = stringPreferencesKey("team_color")
        val ROLE = stringPreferencesKey("team_role")
        val COORD = stringPreferencesKey("coord_format")
        val IMPERIAL = booleanPreferencesKey("imperial")
        val MAP = stringPreferencesKey("map_source")
        val ORIENT = stringPreferencesKey("map_orientation")
        val KEEP_ON = booleanPreferencesKey("keep_on")
        val FOLLOW = booleanPreferencesKey("follow_gps")
        val RINGS = booleanPreferencesKey("range_rings")
        val RADAR_SWEEP = booleanPreferencesKey("radar_sweep")
        val MESH = booleanPreferencesKey("cot_mesh")
        val MESH_FMT = stringPreferencesKey("mesh_format")
        val TAK_SERVER = booleanPreferencesKey("tak_server")
        val TAK_HOST = stringPreferencesKey("tak_server_host")
        val TAK_TLS = booleanPreferencesKey("tak_tls")
        val MESHTASTIC = booleanPreferencesKey("meshtastic")
        val MESHTASTIC_ADDR = stringPreferencesKey("meshtastic_address")
        val MESHTASTIC_NAME = stringPreferencesKey("meshtastic_name")
        val BG_TRACK = booleanPreferencesKey("background_tracking")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val MAP_HINT = booleanPreferencesKey("map_hint_shown")
    }

    private inline fun <reified T : Enum<T>> parse(v: String?, default: T): T =
        v?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            callsign = p[Keys.CALLSIGN] ?: "WATCH-1",
            selfAffiliation = parse(p[Keys.AFFILIATION], Affiliation.FRIEND),
            teamColor = parse(p[Keys.TEAM], TeamColor.CYAN),
            teamRole = parse(p[Keys.ROLE], TeamRole.TEAM_MEMBER),
            coordFormat = parse(p[Keys.COORD], CoordFormat.MGRS),
            imperialUnits = p[Keys.IMPERIAL] ?: false,
            mapSource = parse(p[Keys.MAP], MapSource.STANDARD),
            mapOrientation = parse(p[Keys.ORIENT], MapOrientation.NORTH_UP),
            keepScreenOn = p[Keys.KEEP_ON] ?: true,
            followGps = p[Keys.FOLLOW] ?: true,
            rangeRings = p[Keys.RINGS] ?: false,
            radarSweep = p[Keys.RADAR_SWEEP] ?: true,
            cotMesh = p[Keys.MESH] ?: false,
            meshFormat = parse(p[Keys.MESH_FMT], MeshFormat.TAK_PROTO),
            takServer = p[Keys.TAK_SERVER] ?: false,
            takServerHost = p[Keys.TAK_HOST] ?: "10.0.2.2:8087",
            takTls = p[Keys.TAK_TLS] ?: false,
            meshtastic = p[Keys.MESHTASTIC] ?: false,
            meshtasticAddress = p[Keys.MESHTASTIC_ADDR] ?: "",
            meshtasticName = p[Keys.MESHTASTIC_NAME] ?: "",
            backgroundTracking = p[Keys.BG_TRACK] ?: false,
            // Upgrade path: the onboarding flag arrived in 1.4.0, so installs
            // from earlier versions have no value for it. They are already
            // configured, and must not be dropped back into first-run setup —
            // any stored preference at all means this is not a fresh install.
            onboarded = p[Keys.ONBOARDED] ?: p.asMap().isNotEmpty(),
            mapHintShown = p[Keys.MAP_HINT] ?: false,
        )
    }

    suspend fun setCallsign(v: String) = context.dataStore.edit { it[Keys.CALLSIGN] = v }
    suspend fun setAffiliation(v: Affiliation) = context.dataStore.edit { it[Keys.AFFILIATION] = v.name }
    suspend fun setTeamColor(v: TeamColor) = context.dataStore.edit { it[Keys.TEAM] = v.name }
    suspend fun setTeamRole(v: TeamRole) = context.dataStore.edit { it[Keys.ROLE] = v.name }
    suspend fun setCoordFormat(v: CoordFormat) = context.dataStore.edit { it[Keys.COORD] = v.name }
    suspend fun setImperial(v: Boolean) = context.dataStore.edit { it[Keys.IMPERIAL] = v }
    suspend fun setMapSource(v: MapSource) = context.dataStore.edit { it[Keys.MAP] = v.name }
    suspend fun setMapOrientation(v: MapOrientation) = context.dataStore.edit { it[Keys.ORIENT] = v.name }
    suspend fun setKeepScreenOn(v: Boolean) = context.dataStore.edit { it[Keys.KEEP_ON] = v }
    suspend fun setFollowGps(v: Boolean) = context.dataStore.edit { it[Keys.FOLLOW] = v }
    suspend fun setRangeRings(v: Boolean) = context.dataStore.edit { it[Keys.RINGS] = v }
    suspend fun setRadarSweep(v: Boolean) = context.dataStore.edit { it[Keys.RADAR_SWEEP] = v }
    suspend fun setCotMesh(v: Boolean) = context.dataStore.edit { it[Keys.MESH] = v }
    suspend fun setMeshFormat(v: MeshFormat) = context.dataStore.edit { it[Keys.MESH_FMT] = v.name }
    suspend fun setTakServer(v: Boolean) = context.dataStore.edit { it[Keys.TAK_SERVER] = v }
    suspend fun setTakServerHost(v: String) = context.dataStore.edit { it[Keys.TAK_HOST] = v }
    suspend fun setTakTls(v: Boolean) = context.dataStore.edit { it[Keys.TAK_TLS] = v }
    suspend fun setMeshtastic(v: Boolean) = context.dataStore.edit { it[Keys.MESHTASTIC] = v }
    suspend fun setMeshtasticRadio(address: String, name: String) = context.dataStore.edit {
        it[Keys.MESHTASTIC_ADDR] = address
        it[Keys.MESHTASTIC_NAME] = name
    }
    suspend fun setBackgroundTracking(v: Boolean) = context.dataStore.edit { it[Keys.BG_TRACK] = v }
    suspend fun setOnboarded(v: Boolean) = context.dataStore.edit { it[Keys.ONBOARDED] = v }
    suspend fun setMapHintShown(v: Boolean) = context.dataStore.edit { it[Keys.MAP_HINT] = v }
}
