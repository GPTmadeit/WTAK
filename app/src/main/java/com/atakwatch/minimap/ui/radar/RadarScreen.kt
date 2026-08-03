package com.atakwatch.minimap.ui.radar

import android.graphics.Paint
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.atakwatch.minimap.data.CotRepository
import com.atakwatch.minimap.data.MapOrientation
import com.atakwatch.minimap.data.Positioning
import com.atakwatch.minimap.model.Geo
import com.atakwatch.minimap.net.meshtastic.MeshtasticLink
import com.atakwatch.minimap.ui.LocalAmbientState
import com.atakwatch.minimap.ui.Routes
import com.atakwatch.minimap.ui.collectSettings
import com.atakwatch.minimap.ui.motion.Motion
import com.atakwatch.minimap.ui.rememberSettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Proximity scope — the map with the map taken away.
 *
 * No tiles, no basemap, no network: just you at the centre and everyone else
 * plotted by range and bearing. That makes it the one view that works
 * identically with a full tile cache, an empty one, or no data connection at
 * all, and it is the view you actually want when the question is "where is my
 * team relative to me right now".
 *
 * Gestures, in the app's usual language:
 *  - crown / rotary — range in and out through the scale ladder
 *  - tap the range — back to automatic ranging
 *  - tap a blip — that contact's detail
 *  - long-press a blip — navigate to it (bloodhound)
 *  - tap the centre — back to the map
 *  - tap the chip — north-up / track-up
 *
 * Contacts are touched through real composables laid over the canvas rather
 * than a pointer-input handler on it. A handler that awaits pointer events
 * across the scope stops the nav host's back swipe from ever starting — no
 * consumption involved, and no way to hand the gesture back — which traps you
 * on the screen. Ordinary clickables arbitrate correctly, and bring their own
 * accessibility semantics with them.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun RadarScreen(nav: NavController) {
    val settings by collectSettings()
    val repo = rememberSettingsRepository()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val ambient = LocalAmbientState.current

    val self by CotRepository.self.collectAsStateWithLifecycle()
    val entities by CotRepository.entities.collectAsStateWithLifecycle()
    val navTargetUid by CotRepository.navTargetUid.collectAsStateWithLifecycle()
    val heading by Positioning.heading.collectAsStateWithLifecycle()
    val meshState by MeshtasticLink.state.collectAsStateWithLifecycle()

    // Ages drive the stale/live distinction, so the scope has to keep ticking
    // even when nothing new arrives — a frozen contact must look frozen.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ambient.isAmbient) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(if (ambient.isAmbient) 30_000 else 1_000)
        }
    }

    // null = automatic ranging; the crown pins it until the range label is tapped.
    var manualRange by remember { mutableStateOf<Double?>(null) }

    val trackUp = settings.mapOrientation == MapOrientation.HEADING_UP
    val furthest = self?.let { me ->
        entities.maxOfOrNull { Geo.distanceMeters(me.lat, me.lon, it.lat, it.lon) }
    }
    val targetRange = manualRange ?: RadarGeometry.autoRange(furthest)

    // The scope zooms rather than cutting. A range change moves every contact
    // at once, and snapping between two scales is the fastest way to lose track
    // of which blip was which.
    val scopeRange by animateFloatAsState(
        targetValue = targetRange.toFloat(),
        animationSpec = Motion.slowSpatial(),
        label = "scopeRange",
    )

    // Track-up rotation is interpolated the short way round, so passing north
    // doesn't spin the whole scope through 359°.
    val rotation = remember { Animatable(heading ?: 0f) }
    LaunchedEffect(heading, trackUp) {
        val target = if (trackUp) (heading ?: 0f) else 0f
        val current = rotation.value
        var delta = target - current
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        rotation.animateTo(current + delta, animationSpec = spring(stiffness = 120f))
    }

    val blips = remember(entities, self, scopeRange, rotation.value, trackUp, nowMillis) {
        val me = self
        if (me == null) emptyList()
        else Blip.plot(
            me, entities, scopeRange.toDouble(),
            rotation.value, trackUp, nowMillis,
        )
    }

    // A contact arriving grows into place and a lost one shrinks away, so the
    // roster changing is something you see rather than a blip that was simply
    // there the next time you looked.
    val appearance = remember { mutableStateMapOf<String, Animatable<Float, *>>() }
    LaunchedEffect(blips.map { it.uid }) {
        blips.forEach { blip ->
            if (appearance[blip.uid] == null) {
                val anim = Animatable(0f)
                appearance[blip.uid] = anim
                launch { anim.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 220f)) }
            }
        }
        (appearance.keys - blips.map { it.uid }.toSet()).forEach { appearance.remove(it) }
    }

    // The scope powers on: rings sweep outward once as the screen arrives.
    val powerOn = remember { Animatable(0f) }
    LaunchedEffect(Unit) { powerOn.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }

    // The sweep is the only continuous animation in the app; it stops dead in
    // always-on, where the display refreshes about once a minute anyway.
    val sweeping = settings.radarSweep && !ambient.isAmbient
    val transition = rememberInfiniteTransition(label = "radar")
    val sweepDeg by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )
    val pulse by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse",
    )

    val density = LocalDensity.current
    val labelPaint = remember(density) {
        Paint().apply {
            isAntiAlias = true
            textSize = with(density) { 11.dp.toPx() }
            color = RadarPalette.Label.toArgb()
        }
    }
    val insetPx = with(density) { SCOPE_INSET.toPx() }
    val touchTargetPx = with(density) { TOUCH_TARGET.toPx() }

    val accent = settings.teamColor.color
    val fadeSpec = Motion.fastEffects<Float>()
    val slideSpec = Motion.spatial<IntOffset>()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(RadarPalette.Background)
            .onRotaryScrollEvent { event ->
                // Stepped from the target, not from the animated value — mid
                // animation the live value sits between rungs and the crown
                // would land on the one it just left.
                manualRange = RadarGeometry.step(targetRange, zoomIn = event.verticalScrollPixels < 0f)
                true
            }
            .focusRequester(focusRequester)
            .focusable()
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val centre = Offset(widthPx / 2f, heightPx / 2f)
        val scopeRadius = min(widthPx, heightPx) / 2f - insetPx

        Canvas(modifier = Modifier.fillMaxSize()) {
            val minimal = ambient.isAmbient
            val opening = powerOn.value

            drawScopeRings(centre, scopeRadius * opening, minimal)
            drawCardinals(centre, scopeRadius * opening, rotation.value)
            if (sweeping) drawSweep(centre, scopeRadius, sweepDeg, accent)

            blips.firstOrNull { it.uid == navTargetUid }
                ?.let { drawNavLine(centre, it, scopeRadius, accent) }

            blips.forEach { blip ->
                drawBlip(
                    blip = blip,
                    centre = centre,
                    scopeRadius = scopeRadius,
                    sweepDeg = sweepDeg,
                    sweeping = sweeping,
                    selected = blip.uid == navTargetUid,
                    pulse = pulse,
                    // Grows in on arrival; full size once settled.
                    emergence = appearance[blip.uid]?.value ?: 1f,
                )
            }
            // Labels only when the scope is sparse enough to read them, and
            // never in always-on — text is the most expensive thing here.
            if (!minimal && blips.size <= LABEL_LIMIT) {
                // Claim the HUD's own space up front so no callsign lands on the
                // range readout, your own marker, or the orientation chip.
                val reserved = mutableListOf(
                    Rect(centre.x - 78f, 0f, centre.x + 78f, centre.y - scopeRadius * 0.44f),
                    Rect(centre.x - 26f, centre.y - 26f, centre.x + 26f, centre.y + 26f),
                    Rect(centre.x - 110f, size.height - 92f, centre.x + 110f, size.height),
                )
                // Nearest first, so the contact that matters most keeps its name
                // when two labels want the same patch of screen.
                blips.asReversed().filterNot { it.offScope }.forEach {
                    drawBlipLabel(it, centre, scopeRadius, labelPaint, reserved)
                }
            }

            drawSelf(centre, accent, heading, trackUp, minimal)
        }

        // Touch targets. Added furthest-first so the nearest contact ends up on
        // top — under load the thing next to you outranks the thing 2 km away.
        if (!ambient.isAmbient) {
            blips.forEach { blip ->
                val at = blip.offset(centre, scopeRadius)
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (at.x - touchTargetPx / 2).roundToInt(),
                                (at.y - touchTargetPx / 2).roundToInt(),
                            )
                        }
                        .size(TOUCH_TARGET)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { nav.navigate(Routes.detail(blip.uid)) },
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                CotRepository.setNavTarget(
                                    if (navTargetUid == blip.uid) null else blip.uid
                                )
                            },
                        )
                )
            }

            // The centre is you; tapping yourself goes back to the map, which is
            // where "you" lives. Added last so it wins over a blip on top of you.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(36.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { nav.popBackStack() }
            )
        }

        // Scope range and how many contacts are on it.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { manualRange = null },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Both readouts slide as they change: the range because it steps
            // through a ladder, the count because a contact arriving is worth
            // registering even when you are looking at the middle of the scope.
            AnimatedContent(
                targetState = Geo.formatRange(targetRange, settings.imperialUnits),
                transitionSpec = {
                    (slideInVertically(slideSpec) { -it / 2 } + fadeIn(fadeSpec))
                        .togetherWith(slideOutVertically(slideSpec) { it / 2 } + fadeOut(fadeSpec))
                },
                label = "range",
            ) { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (manualRange == null) RadarPalette.Label else accent,
                    textAlign = TextAlign.Center,
                )
            }
            AnimatedContent(
                targetState = blips.size,
                transitionSpec = {
                    (slideInVertically(slideSpec) { it } + fadeIn(fadeSpec))
                        .togetherWith(slideOutVertically(slideSpec) { -it } + fadeOut(fadeSpec))
                },
                label = "nodes",
            ) { count ->
                Text(
                    if (count == 1) "1 node" else "$count nodes",
                    style = MaterialTheme.typography.labelSmall,
                    color = RadarPalette.Dim,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // The radio, when one is attached — on LoRa the link state is the single
        // most important thing about the scope you're looking at.
        if (meshState != MeshtasticLink.State.OFF) {
            val linked = meshState == MeshtasticLink.State.CONNECTED
            Text(
                if (linked) "LoRa" else meshState.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (linked) Color(0xFF35C759) else Color(0xFFF2C037),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 74.dp, end = 12.dp),
            )
        }

        // Orientation, and the one control that changes how the scope reads.
        Text(
            if (trackUp) "Heading up" else "North up",
            style = MaterialTheme.typography.labelMedium,
            color = RadarPalette.Label,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
                .background(Color(0xCC15191E), RoundedCornerShape(18.dp))
                .clickable {
                    scope.launch {
                        repo.setMapOrientation(
                            if (trackUp) MapOrientation.NORTH_UP else MapOrientation.HEADING_UP
                        )
                    }
                }
                .padding(horizontal = 18.dp, vertical = 8.dp),
        )

        // Without a fix the scope has no origin, and everything on it would be a
        // lie. Say so rather than drawing an empty, confident-looking circle.
        if (self == null) {
            Text(
                "NO GPS",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFE23B3B),
                modifier = Modifier.align(Alignment.Center).padding(top = 26.dp),
            )
        }
    }
}

/** Keeps the outer ring clear of the bezel on a round display. */
private val SCOPE_INSET = 10.dp

/** Wear's minimum comfortable touch target; blips themselves are ~5 px. */
private val TOUCH_TARGET = 40.dp

/** Above this many contacts the labels stop helping and start covering blips. */
private const val LABEL_LIMIT = 7
