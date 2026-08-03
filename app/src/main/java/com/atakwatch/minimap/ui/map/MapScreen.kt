package com.atakwatch.minimap.ui.map

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.wear.compose.foundation.edgeSwipeToDismiss
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.atakwatch.minimap.data.CotRepository
import com.atakwatch.minimap.data.EmergencyBeacon
import com.atakwatch.minimap.data.MapOrientation
import com.atakwatch.minimap.data.MapSource
import com.atakwatch.minimap.data.Positioning
import com.atakwatch.minimap.map.CoordinateFormatter
import com.atakwatch.minimap.map.MilStdIcons
import com.atakwatch.minimap.model.Affiliation
import com.atakwatch.minimap.model.CotEvent
import com.atakwatch.minimap.model.Geo
import com.atakwatch.minimap.model.EmergencyType
import com.atakwatch.minimap.net.TakClient
import com.atakwatch.minimap.net.Transports
import com.atakwatch.minimap.ui.Routes
import com.atakwatch.minimap.ui.collectSettings
import com.atakwatch.minimap.ui.motion.Hole
import com.atakwatch.minimap.ui.motion.Motion
import com.atakwatch.minimap.ui.motion.NoHoles
import com.atakwatch.minimap.ui.motion.pressScale
import com.atakwatch.minimap.ui.motion.punchedBy
import kotlinx.coroutines.delay
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import kotlin.math.abs
import kotlin.math.roundToInt

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

@Composable
fun MapScreen(nav: NavController) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasLocationPermission(context)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> granted = result.values.any { it } }

    LaunchedEffect(Unit) { if (!granted) launcher.launch(LOCATION_PERMISSIONS) }

    if (granted) MapContent(nav) else PermissionScreen { launcher.launch(LOCATION_PERMISSIONS) }
}

private fun tileSourceFor(src: MapSource): ITileSource = when (src) {
    MapSource.STANDARD, MapSource.OFFLINE -> TileSourceFactory.MAPNIK
    MapSource.TOPO -> XYTileSource(
        "OpenTopoMap", 0, 17, 256, ".png",
        arrayOf(
            "https://a.tile.opentopomap.org/",
            "https://b.tile.opentopomap.org/",
            "https://c.tile.opentopomap.org/",
        ),
        "© OpenTopoMap (CC-BY-SA)",
    )
}

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MapContent(nav: NavController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings by collectSettings()
    val self by CotRepository.self.collectAsStateWithLifecycle()
    val entities by CotRepository.entities.collectAsStateWithLifecycle()
    val focus by CotRepository.focus.collectAsStateWithLifecycle()

    val ambient = com.atakwatch.minimap.ui.LocalAmbientState.current
    val haptics = LocalHapticFeedback.current

    // GPS and compass are owned app-wide by Positioning (or by the tracking
    // service when background tracking is on), so leaving the map for the
    // radar, contacts or chat no longer freezes your own position.
    val heading by Positioning.heading.collectAsStateWithLifecycle()
    val headingDeg = heading?.let { it.roundToInt() % 360 } ?: -1

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setUseDataConnection(true)
            controller.setZoom(17.0)
            controller.setCenter(GeoPoint(0.0, 0.0))
        }
    }
    val entitiesFolder = remember { FolderOverlay() }
    val selfMarker = remember {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = BitmapDrawable(context.resources, MilStdIcons.selfIcon(context))
            isEnabled = false
            title = "Self"
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ ->
                nav.navigate(Routes.detail(com.atakwatch.minimap.net.DeviceIdentity.uid)); true
            }
        }
    }

    // Long-press the map to drop a waypoint exactly where you pressed — the core
    // ATAK gesture — and share it on whatever transports are up.
    val eventsOverlay = remember {
        MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?) = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                p ?: return false
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                val wp = CotRepository.addWaypoint(
                    context, p.latitude, p.longitude, "WP ${CotRepository.waypointCount + 1}",
                )
                Transports.sendEvent(wp)
                return true
            }
        })
    }
    // Bloodhound line: self -> nav target, redrawn as either end moves.
    val navLine = remember {
        Polyline(mapView).apply {
            outlinePaint.color = android.graphics.Color.parseColor("#FF00E0C0")
            outlinePaint.strokeWidth = 5f
            outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 12f), 0f)
            isEnabled = false
        }
    }

    // Breadcrumb trail — where you have been, for backtracking.
    val trailLine = remember {
        Polyline(mapView).apply {
            outlinePaint.color = android.graphics.Color.parseColor("#B33D9BE9")
            outlinePaint.strokeWidth = 4f
            isEnabled = false
        }
    }

    // Distance scale — a tactical map without one can't answer "how far?".
    val scaleBar = remember {
        ScaleBarOverlay(mapView).apply {
            setCentred(true)
            setAlignBottom(true)
            setTextSize(26f)
            setLineWidth(3f)
            setMaxLength(0.9f)   // keep the bar inside the round display
        }
    }

    LaunchedEffect(Unit) {
        mapView.overlays.add(eventsOverlay)   // beneath markers, so marker taps win
        mapView.overlays.add(trailLine)
        mapView.overlays.add(navLine)
        mapView.overlays.add(entitiesFolder)
        mapView.overlays.add(selfMarker)
        mapView.overlays.add(scaleBar)
        // The view has no width until it is laid out, so the centring offset has
        // to be applied afterwards or the bar is drawn off the left edge.
        mapView.post {
            scaleBar.setScaleBarOffset(mapView.width / 2, 96)
            mapView.invalidate()
        }
    }

    // Scale bar follows the unit setting.
    LaunchedEffect(settings.imperialUnits) {
        scaleBar.setUnitsOfMeasure(
            if (settings.imperialUnits) ScaleBarOverlay.UnitsOfMeasure.imperial
            else ScaleBarOverlay.UnitsOfMeasure.metric
        )
        mapView.invalidate()
    }

    val entityMarkers = remember { HashMap<String, Marker>() }

    // Incremental marker diff — reuse Marker objects, cached bitmaps, only touch what changed.
    LaunchedEffect(entities) {
        val seen = HashSet<String>()
        entities.forEach { e ->
            seen.add(e.uid)
            val marker = entityMarkers.getOrPut(e.uid) {
                Marker(mapView).apply {
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setInfoWindow(null) // tap opens our detail screen, not a bubble
                    val markerUid = e.uid
                    setOnMarkerClickListener { _, _ ->
                        nav.navigate(Routes.detail(markerUid)); true
                    }
                    entitiesFolder.add(this)
                }
            }
            // Icon can change when an entity's team/affiliation updates over the
            // network — re-rasterise only when the icon key actually changes.
            val iconKey = iconKeyFor(e)
            if (marker.relatedObject != iconKey) {
                marker.icon = BitmapDrawable(context.resources, iconBitmapFor(context, e))
                marker.relatedObject = iconKey
            }
            marker.position = GeoPoint(e.lat, e.lon)
            marker.title = e.callsign
            marker.snippet = e.type.raw
        }
        (entityMarkers.keys - seen).forEach { uid ->
            entityMarkers.remove(uid)?.let { entitiesFolder.remove(it) }
        }
        mapView.invalidate()
    }

    // Tile source. Offline swaps in a provider backed by local archives so the
    // map keeps working with no network at all; online sources restore the
    // default provider.
    LaunchedEffect(settings.mapSource) {
        val offlineProvider = if (settings.mapSource == MapSource.OFFLINE) {
            com.atakwatch.minimap.map.OfflineMaps.provider(context)
        } else null

        if (offlineProvider != null) {
            val previous = mapView.tileProvider
            mapView.tileProvider = offlineProvider
            runCatching { previous?.detach() }
            mapView.setUseDataConnection(false)
            mapView.setTileSource(com.atakwatch.minimap.map.OfflineMaps.tileSource(context))
        } else {
            if (mapView.tileProvider is org.osmdroid.tileprovider.modules.OfflineTileProvider) {
                // Leaving offline (or no archives present): rebuild the normal stack.
                val previous = mapView.tileProvider
                mapView.tileProvider = org.osmdroid.tileprovider.MapTileProviderBasic(context)
                runCatching { previous?.detach() }
            }
            mapView.setUseDataConnection(!ambient.isAmbient)
            mapView.setTileSource(tileSourceFor(settings.mapSource))
        }
        mapView.invalidate()
    }

    // Keep-screen-on window flag.
    val activity = context as? Activity
    DisposableEffect(settings.keepScreenOn) {
        activity?.window?.let {
            if (settings.keepScreenOn) it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else it.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Latest settings for long-lived collectors that must not capture a stale copy.
    val settingsRef = rememberUpdatedState(settings)
    val liveSettings by settingsRef

    // Render from the repository, so the map behaves identically whether the
    // fixes come from Positioning or from the tracking service.
    LaunchedEffect(Unit) {
        CotRepository.self.collect { s ->
            s ?: return@collect
            val gp = GeoPoint(s.lat, s.lon)
            selfMarker.position = gp
            selfMarker.isEnabled = true
            if (liveSettings.followGps && CotRepository.focus.value == null) {
                mapView.controller.animateTo(gp)
            }
            // Record the trail; only redraw when a point was actually added.
            if (com.atakwatch.minimap.data.Breadcrumbs.record(s.lat, s.lon)) {
                val pts = com.atakwatch.minimap.data.Breadcrumbs.points.value
                if (pts.size >= 2) {
                    trailLine.setPoints(pts.map { GeoPoint(it.first, it.second) })
                    trailLine.isEnabled = true
                }
            }
            mapView.invalidate()
        }
    }

    // Ambient: stop tile downloads and the compass while the screen is dimmed;
    // the GPS engine drops to its low-power cadence. Restored on exit.
    LaunchedEffect(ambient.isAmbient) {
        Positioning.setAmbient(ambient.isAmbient)
        // Offline maps never want the network back on.
        mapView.setUseDataConnection(
            !ambient.isAmbient && settings.mapSource != MapSource.OFFLINE
        )
    }

    // Compass: rotate the self marker (north-up) or the whole map (track-up).
    LaunchedEffect(settings.mapOrientation, heading) {
        val hdg = heading
        if (settings.mapOrientation == MapOrientation.NORTH_UP) {
            if (mapView.mapOrientation != 0f) mapView.setMapOrientation(0f)
            selfMarker.rotation = -(hdg ?: 0f)
        } else if (hdg != null) {
            val target = (-hdg + 360f) % 360f
            if (abs(shortestDelta(target, mapView.mapOrientation)) > 2f) {
                mapView.setMapOrientation(target)
            }
            selfMarker.rotation = 0f
        }
        mapView.invalidate()
    }

    // (The tracking service is started/stopped by MainActivity, which observes
    // the setting app-wide — tying it to this screen would mean it never starts
    // when the user enables it from Settings.)

    // Range rings — ATAK's standard distance reference around yourself.
    val rangeRings = remember { mutableStateListOf<Polyline>() }
    LaunchedEffect(self, settings.rangeRings, settings.imperialUnits) {
        rangeRings.forEach { mapView.overlays.remove(it) }
        rangeRings.clear()
        val me = self
        if (settings.rangeRings && me != null) {
            RING_RADII_M.forEach { radius ->
                val ring = Polyline(mapView).apply {
                    outlinePaint.color = android.graphics.Color.parseColor("#553D9BE9")
                    outlinePaint.strokeWidth = 2f
                    // 48 segments reads as a circle at watch size without the
                    // cost of a finer polygon.
                    setPoints((0..48).map { i ->
                        val (la, lo) = Geo.destination(me.lat, me.lon, i * 360.0 / 48, radius)
                        GeoPoint(la, lo)
                    })
                }
                rangeRings.add(ring)
                mapView.overlays.add(0, ring)
            }
        }
        mapView.invalidate()
    }

    // Prune stale network contacts.
    LaunchedEffect(Unit) {
        while (true) {
            CotRepository.pruneStale(System.currentTimeMillis())
            delay(5_000)
        }
    }

    // While an emergency is raised, re-broadcast it so it can't be missed and
    // won't silently expire on the network.
    val emergency by EmergencyBeacon.active.collectAsStateWithLifecycle()
    LaunchedEffect(emergency) {
        if (emergency == null) return@LaunchedEffect
        while (true) {
            EmergencyBeacon.buildEvent(CotRepository.self.value)?.let { alert ->
                Transports.sendEvent(alert)
            }
            delay(10_000)
        }
    }

    // Center on a contact requested from the Contacts screen.
    LaunchedEffect(focus) {
        focus?.let { (lat, lon) ->
            mapView.controller.animateTo(GeoPoint(lat, lon))
            CotRepository.consumeFocus()
        }
    }

    // Lifecycle: the MapView's own resume/pause. Keyed on the lifecycle owner
    // alone so disposal happens exactly once, on real teardown — MapView
    // .onDetach() is terminal, and re-running it would leave a dead MapView
    // whose repository is null and crash the next Marker().
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    val focusRequester = remember { FocusRequester() }

    // Drives the fix-age readout so a frozen GPS is visible even with no updates.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ambient.isAmbient) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(if (ambient.isAmbient) 30_000 else 5_000)
        }
    }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    val linkState by TakClient.linkState.collectAsStateWithLifecycle()
    val navTargetUid by CotRepository.navTargetUid.collectAsStateWithLifecycle()
    val navTarget = navTargetUid?.let { uid -> entities.firstOrNull { it.uid == uid } }

    // When navigating, the bottom pill reports the target instead of whatever
    // happens to be closest — that is the whole point of choosing a target.
    val nearest = navTarget
        ?: self?.let { s -> entities.minByOrNull { Geo.distanceMeters(s.lat, s.lon, it.lat, it.lon) } }

    // Keep the bloodhound line pinned to both ends.
    LaunchedEffect(navTarget, self) {
        val s = self
        if (navTarget != null && s != null) {
            navLine.setPoints(listOf(GeoPoint(s.lat, s.lon), GeoPoint(navTarget.lat, navTarget.lon)))
            navLine.isEnabled = true
        } else {
            navLine.isEnabled = false
        }
        mapView.invalidate()
    }

    // Always-on: render the low-power readout instead of the live map.
    if (ambient.isAmbient) {
        AmbientMapOverlay(
            ambient = ambient,
            callsign = settings.callsign,
            coordinate = self?.let { CoordinateFormatter.format(it.lat, it.lon, settings.coordFormat) }
                ?: "NO GPS",
            heading = if (headingDeg >= 0) "HDG %03d°".format(headingDeg) else null,
            accent = settings.teamColor.color,
        )
        return
    }

    // Swipe-to-dismiss is disabled for this destination by the nav host (see
    // ATAKWatchRoot), so a west-to-east pan stays a pan instead of backing you
    // out of the app.

    // The two edge controls are cut *out* of the map rather than laid on top of
    // it, the way Wear shapes content around its own controls. Each cutout
    // breathes open when its button is pressed.
    val menuInteraction = remember { MutableInteractionSource() }
    val radarInteraction = remember { MutableInteractionSource() }
    val menuPressed by menuInteraction.collectIsPressedAsState()
    val radarPressed by radarInteraction.collectIsPressedAsState()
    val menuHole by animateFloatAsState(
        targetValue = if (menuPressed) 1.16f else 1f,
        animationSpec = Motion.fastSpatial(), label = "menuHole",
    )
    val radarHole by animateFloatAsState(
        targetValue = if (radarPressed) 1.16f else 1f,
        animationSpec = Motion.fastSpatial(), label = "radarHole",
    )
    // Motion specs, resolved once: they come from the theme, so reading them
    // inside a transition lambda would be a composable call in a plain one.
    val fadeFast = Motion.fastEffects<Float>()
    val fade = Motion.effects<Float>()
    val slide = Motion.spatial<IntOffset>()
    val scaleSpec = Motion.spatial<Float>()

    val density = LocalDensity.current
    val holeRadiusPx = with(density) { CONTROL_HOLE_RADIUS.toPx() }
    val holeInsetPx = with(density) { CONTROL_HOLE_INSET.toPx() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Restrict dismissal to a narrow strip at the leading edge. Wear's back
    // swipe is full-screen, so without this every west-to-east pan backs you out
    // of the app — and simply disabling the nav host's swipe doesn't help,
    // because then the platform's own dismissal takes over instead. The gesture
    // has to be received and declined, which is what this does.
    //
    // Applied only while the map is the screen in front: it stays composed as
    // the background during a swipe on another screen, and arbitrating from
    // back there would veto that screen's own back gesture.
    val swipeState = com.atakwatch.minimap.ui.LocalSwipeState.current
    val isFrontmost = com.atakwatch.minimap.ui.LocalCurrentRoute.current == Routes.MAP
    val edgeSwipeModifier = if (swipeState != null && isFrontmost) {
        Modifier.edgeSwipeToDismiss(swipeState, edgeWidth = BACK_EDGE_WIDTH)
    } else Modifier

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(edgeSwipeModifier)
            .onRotaryScrollEvent { ev ->
                if (ev.verticalScrollPixels > 0f) mapView.controller.zoomIn() else mapView.controller.zoomOut()
                true
            }
            .focusRequester(focusRequester)
            .focusable()
    ) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .punchedBy {
                    if (canvasSize == IntSize.Zero || ambient.isAmbient) NoHoles
                    else {
                        val midY = canvasSize.height / 2f
                        listOf(
                            Hole(Offset(holeInsetPx, midY), holeRadiusPx * menuHole),
                            Hole(
                                Offset(canvasSize.width - holeInsetPx, midY),
                                holeRadiusPx * radarHole,
                            ),
                        )
                    }
                },
        )

        // Top HUD: callsign, coordinate, heading. Tapping it recenters on you —
        // the pill shows YOUR position, so tapping it goes to your position.
        HudPill(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 28.dp)
                .clickable {
                    CotRepository.self.value?.let {
                        mapView.controller.animateTo(GeoPoint(it.lat, it.lon))
                    }
                },
        ) {
            // Callsign in your team color, the way ATAK identifies you.
            Text(settings.callsign, style = MaterialTheme.typography.labelSmall,
                color = settings.teamColor.color, textAlign = TextAlign.Center)
            // The coordinate crossfades as it changes rather than snapping —
            // it is the one readout that updates constantly, and a hard swap
            // reads as flicker at this size.
            val coordinate = self
                ?.let { CoordinateFormatter.format(it.lat, it.lon, settings.coordFormat) }
                ?: "Acquiring GPS…"
            AnimatedContent(
                targetState = coordinate,
                transitionSpec = { fadeIn(fadeFast) togetherWith fadeOut(fadeFast) },
                label = "coordinate",
            ) { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
            // Heading + fix quality. An unqualified position is worse than no
            // position, so accuracy and staleness are always on screen.
            val fix = self
            val fixAge = fix?.let { nowMillis - it.timeMillis } ?: 0L
            val fixStale = fix != null && fixAge > FIX_STALE_MS
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (headingDeg >= 0) {
                    Text("HDG %03d°".format(headingDeg),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF9FE0FF))
                }
                if (fix != null && fix.ce < 9_999_999.0) {
                    Text(
                        if (fixStale) "±%.0fm ${Geo.formatAge(fixAge)}".format(fix.ce)
                        else "±%.0fm".format(fix.ce),
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            fixStale -> Color(0xFFE23B3B)
                            fix.ce <= 10 -> Color(0xFF35C759)   // dual-band quality
                            fix.ce <= 30 -> Color(0xFFF2C037)
                            else -> Color(0xFFE23B3B)
                        },
                    )
                }
            }
        }

        // Bottom HUD: range/bearing to nearest entity. Tap opens its detail.
        // Suppressed during an emergency — the alert owns that space.
        // It slides up from the bezel as the first contact arrives, so a team
        // appearing on the network is something you see rather than something
        // you notice later.
        AnimatedVisibility(
            visible = nearest != null && self != null && emergency == null,
            enter = slideInVertically(slide) { it } + fadeIn(fade),
            exit = slideOutVertically(slide) { it } + fadeOut(fade),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
        ) {
            // Held so the pill can animate out with its last contents intact
            // instead of blanking mid-slide.
            val shown = remember { mutableStateOf(nearest) }
            if (nearest != null) shown.value = nearest
            val contact = shown.value
            val me = self
            if (contact == null || me == null) return@AnimatedVisibility
            val d = Geo.distanceMeters(me.lat, me.lon, contact.lat, contact.lon)
            val b = Geo.bearingDegrees(me.lat, me.lon, contact.lat, contact.lon)
            HudPill(modifier = Modifier.clickable { nav.navigate(Routes.detail(contact.uid)) }) {
                Text(
                    // "› " marks an actively navigated target vs just the nearest.
                    if (navTarget != null) "› ${contact.callsign}" else contact.callsign,
                    style = MaterialTheme.typography.labelSmall,
                    color = entityColor(contact),
                    textAlign = TextAlign.Center,
                )
                Text("${Geo.formatRange(d, settings.imperialUnits)}  ${Geo.formatBearing(b)}",
                    style = MaterialTheme.typography.labelSmall, color = Color.White, textAlign = TextAlign.Center)
            }
        }

        // Link warning: only visible when the server is enabled but not actually
        // connected. Silence means the link is good — no badge to ignore.
        if (settings.takServer && linkState != TakClient.State.CONNECTED) {
            HudPill(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 74.dp, end = 10.dp),
            ) {
                Text(
                    if (linkState == TakClient.State.CONNECTING) "LINK…" else "NO LINK",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (linkState == TakClient.State.CONNECTING) Color(0xFFF2C037)
                    else Color(0xFFE23B3B),
                    textAlign = TextAlign.Center,
                )
            }
        }

        // The one remaining button. Everything else is a gesture: crown, pinch
        // or double-tap zooms; long-press drops a waypoint; markers and both
        // HUD pills are tappable.
        // Tap for the menu; hold to raise an emergency. Holding keeps a 911
        // beacon out of reach of an accidental tap.
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 4.dp)
                .size(38.dp)
                .pressScale(menuInteraction)
                .background(Color(0xE010161C), CircleShape)
                .combinedClickable(
                    interactionSource = menuInteraction,
                    indication = null,
                    onClick = { nav.navigate(Routes.MENU) },
                    onLongClick = {
                        if (!EmergencyBeacon.isActive) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            EmergencyBeacon.raise(EmergencyType.NINE_ONE_ONE)
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Menu, contentDescription = "Menu, hold for emergency",
                tint = Color.White, modifier = Modifier.size(20.dp))
        }

        // The scope, opposite the menu. Same view of the same contacts with the
        // basemap taken away — which is the view you want when the tiles are
        // missing, or when the only question is "how far, which way".
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
                .size(38.dp)
                .pressScale(radarInteraction)
                .background(Color(0xE010161C), CircleShape)
                .clickable(
                    interactionSource = radarInteraction,
                    indication = null,
                ) { nav.navigate(Routes.RADAR) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.TrackChanges, contentDescription = "Radar scope",
                tint = Color.White, modifier = Modifier.size(20.dp))
        }

        // Active emergency: impossible to miss, one tap to stand down. Sits at
        // the bottom, where a round display has usable width — the top centre is
        // occupied by the system clock and clipped by the bezel.
        AnimatedVisibility(
            visible = emergency != null,
            enter = slideInVertically(slide) { it } + fadeIn(fade),
            exit = fadeOut(fadeFast),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            // A slow breath, not a strobe: enough to keep pulling the eye back
            // without making the label hard to read while it moves.
            val alarm = rememberInfiniteTransition(label = "alarm")
            val glow by alarm.animateFloat(
                initialValue = 0f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "glow",
            )
            Box(
                modifier = Modifier
                    // Lifted off the very bottom, where a round display narrows
                    // to almost nothing.
                    .padding(bottom = 26.dp)
                    .graphicsLayer {
                        val s = 1f + glow * 0.035f
                        scaleX = s; scaleY = s
                    }
                    .background(
                        lerp(Color(0xF0C42121), Color(0xFFF04A4A), glow),
                        RoundedCornerShape(10.dp),
                    )
                    .clickable {
                        // Transmit an explicit cancel before clearing, so the
                        // team is told it is over rather than just going quiet.
                        EmergencyBeacon.buildEvent(CotRepository.self.value, cancelling = true)
                            ?.let { Transports.sendEvent(it) }
                        EmergencyBeacon.clear()
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    .padding(horizontal = 12.dp, vertical = 3.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        emergency?.label ?: EmergencyType.NINE_ONE_ONE.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "tap to cancel",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFD9D9),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // One-time gesture hint so the controls are discoverable. It fades away
        // on its own rather than vanishing between frames.
        var hintVisible by remember { mutableStateOf(true) }
        AnimatedVisibility(
            visible = !settings.mapHintShown && hintVisible,
            enter = fadeIn(fade) + scaleIn(scaleSpec, initialScale = 0.9f),
            exit = fadeOut(fade) + scaleOut(scaleSpec, targetScale = 0.9f),
            modifier = Modifier.align(Alignment.Center),
        ) {
            HudPill(modifier = Modifier.padding(horizontal = 30.dp)) {
                Text(
                    "Long-press: drop waypoint\nCrown / double-tap: zoom\nTap a marker: details",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White, textAlign = TextAlign.Center,
                )
            }
        }
        if (!settings.mapHintShown) {
            val repo = com.atakwatch.minimap.ui.rememberSettingsRepository()
            LaunchedEffect(Unit) {
                delay(8_000)
                hintVisible = false
                repo.setMapHintShown(true)
            }
        }
    }
}

/** A GPS fix older than this is called out — you may have lost lock. */
private const val FIX_STALE_MS = 20_000L

/**
 * Width of the leading strip that still dismisses on the map. Wear's default is
 * 32.dp; 24.dp keeps the gesture reachable while leaving the map almost the
 * whole surface to pan on.
 */
private val BACK_EDGE_WIDTH = 24.dp

/** Ring radii in metres — close-in reference, not a planning tool. */
private val RING_RADII_M = listOf(100.0, 250.0, 500.0, 1000.0)

/**
 * The map's cutouts for the two edge controls. A shade wider than the 38.dp
 * buttons so a rim of background shows around each one, which is what makes it
 * read as inset rather than as a hole that happens to be exactly button-sized.
 */
private val CONTROL_HOLE_RADIUS = 25.dp

/** Distance from the display edge to each cutout's centre. */
private val CONTROL_HOLE_INSET = 23.dp

private fun shortestDelta(a: Float, b: Float): Float {
    var d = a - b
    while (d > 180f) d -= 360f
    while (d < -180f) d += 360f
    return d
}

/** Stable key describing which icon an entity should carry. */
private fun iconKeyFor(e: CotEvent): String = when {
    e.type.isWaypoint -> "wp"
    e.affiliation == Affiliation.FRIEND && e.teamColor != null -> "team:${e.teamColor!!.name}"
    else -> "aff:${e.affiliation.name}"
}

private fun iconBitmapFor(context: Context, e: CotEvent): android.graphics.Bitmap = when {
    e.type.isWaypoint -> MilStdIcons.waypointIcon(context)
    e.affiliation == Affiliation.FRIEND && e.teamColor != null ->
        MilStdIcons.teamIcon(context, android.graphics.Color.argb(
            255,
            (e.teamColor!!.color.red * 255).toInt(),
            (e.teamColor!!.color.green * 255).toInt(),
            (e.teamColor!!.color.blue * 255).toInt(),
        ))
    else -> MilStdIcons.affiliationIcon(context, e.affiliation)
}

/** Roster dot / HUD accent color for an entity. */
fun entityColor(e: CotEvent): androidx.compose.ui.graphics.Color = when {
    e.type.isWaypoint -> MilStdIcons.waypointColor
    e.affiliation == Affiliation.FRIEND && e.teamColor != null -> e.teamColor!!.color
    else -> e.affiliation.color
}

@Composable
private fun HudPill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .background(Color(0xB3000000), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun MapButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = modifier.size(38.dp).background(Color(0xCC1B1B1B), CircleShape)) {
        Icon(icon, contentDescription = desc, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Location access is needed to place you on the map.",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            androidx.wear.compose.material3.Button(onClick = onRequest) { Text("Grant") }
        }
    }
}

