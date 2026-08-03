package com.atakwatch.minimap.ui.motion

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.wear.compose.material3.MaterialTheme

/**
 * The app's motion vocabulary.
 *
 * Everything animated here is driven by Wear Material 3's own
 * [androidx.wear.compose.material3.MotionScheme] rather than hand-picked
 * durations, so the app moves with the same weight and timing as the rest of
 * the watch. Spatial specs move things; effects specs change how things look.
 * Using the platform's curves is what stops a custom UI feeling like a port.
 */
object Motion {

    /** Position, size, rotation — anything that travels. */
    @Composable
    fun <T> spatial(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultSpatialSpec()

    /** Small, immediate movement: a press, a chip settling. */
    @Composable
    fun <T> fastSpatial(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.fastSpatialSpec()

    /** Deliberate movement: a scope opening, a panel arriving. */
    @Composable
    fun <T> slowSpatial(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.slowSpatialSpec()

    /** Colour and alpha — things that change appearance without moving. */
    @Composable
    fun <T> effects(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultEffectsSpec()

    @Composable
    fun <T> fastEffects(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.fastEffectsSpec()
}

/**
 * Presses shrink slightly and spring back, the way every control on the watch
 * does. Applied to custom controls that aren't already a Material component.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.86f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = Motion.fastSpatial(),
        label = "pressScale",
    )
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * Cuts real holes in whatever this modifies.
 *
 * Wear's own surfaces don't float controls on top of content — the content is
 * shaped around them, so a button reads as part of the display rather than
 * something dropped onto it. The map gets the same treatment: the tile layer is
 * genuinely absent behind each edge control instead of being covered by a
 * translucent circle, which is what makes the control look inset rather than
 * stuck on.
 *
 * Needs an offscreen layer, because [BlendMode.Clear] has to erase pixels that
 * have already been drawn rather than blend with the window behind them.
 */
fun Modifier.punchedBy(holes: () -> List<Hole>): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        holes().forEach { hole ->
            if (hole.radius <= 0f) return@forEach
            // A soft edge sells the cut; a hard one looks like a bug.
            drawCircle(
                color = Color.Black,
                radius = hole.radius,
                center = hole.centre,
                blendMode = BlendMode.Clear,
            )
        }
    }

/** A circular cutout: where, and how big. */
data class Hole(val centre: Offset, val radius: Float)

/** Convenience for a modifier chain that needs no cutouts this frame. */
val NoHoles: List<Hole> = emptyList()
