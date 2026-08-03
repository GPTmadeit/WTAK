package com.atakwatch.minimap.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * Always-on-display state, published by [com.atakwatch.minimap.MainActivity] via
 * [LocalAmbientState] and consumed by screens so they can shed power while the
 * watch is in ambient.
 *
 * The Pixel Watch 4 runs always-on by default, so the map spends real time in
 * this state: we dim the UI, drop to a low-rate GPS cadence, stop tile fetching
 * and skip per-second HUD churn.
 */
@Immutable
data class AmbientState(
    val isAmbient: Boolean = false,
    /** Ambient is 1-bit on some hardware — avoid anti-aliasing and gradients. */
    val lowBitAmbient: Boolean = false,
    /** OLED burn-in mitigation: shift content a few px and avoid solid fills. */
    val burnInProtection: Boolean = false,
    /** Increments on each ~1-minute ambient tick, so UI can refresh sparingly. */
    val tick: Int = 0,
)

val LocalAmbientState = compositionLocalOf { AmbientState() }
