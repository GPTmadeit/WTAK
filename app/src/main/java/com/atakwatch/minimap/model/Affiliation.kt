package com.atakwatch.minimap.model

import androidx.compose.ui.graphics.Color

/**
 * Standard CoT / MIL-STD-2525 affiliation, as used throughout the ATAK ecosystem.
 * The single-letter [code] is the second atom of a CoT type string (e.g. the `f`
 * in `a-f-G`). Colors and frame shapes follow MIL-STD-2525:
 *
 *  - Friend  → blue, rounded rectangle
 *  - Hostile → red, diamond
 *  - Neutral → green, square
 *  - Unknown → yellow, quatrefoil
 */
enum class Affiliation(
    val code: Char,
    val label: String,
    val color: Color,
    val frame: Frame
) {
    FRIEND('f', "Friendly", Color(0xFF3D9BE9), Frame.RECTANGLE),
    HOSTILE('h', "Hostile", Color(0xFFE23B3B), Frame.DIAMOND),
    NEUTRAL('n', "Neutral", Color(0xFF35C759), Frame.SQUARE),
    UNKNOWN('u', "Unknown", Color(0xFFF2C037), Frame.QUATREFOIL);

    enum class Frame { RECTANGLE, DIAMOND, SQUARE, QUATREFOIL }

    companion object {
        fun fromCode(c: Char): Affiliation = entries.firstOrNull { it.code == c } ?: UNKNOWN
    }
}
