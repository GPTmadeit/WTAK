package com.atakwatch.minimap.model

/**
 * A Cursor-on-Target (CoT) type string.
 *
 * The set of CoT types is a hyphen-delimited tree: `atoms - affiliation -
 * battle-dimension - MIL-STD-2525 function code`. For example:
 *
 *  - `a-f-G`       → atoms, friendly, Ground
 *  - `a-f-G-U-C`   → atoms, friendly, Ground, Unit, Combat  (a typical self/BFT type)
 *  - `a-h-A-MFA`   → atoms, hostile, Air, Military Fixed-wing Attack
 */
data class CotType(val raw: String) {

    val tokens: List<String> = raw.split("-")

    /** `a` = atom (a real thing), `b` = bit/sensor, `t` = tasking, etc. */
    val isAtom: Boolean get() = tokens.firstOrNull() == "a"

    val affiliation: Affiliation
        get() = tokens.getOrNull(1)?.firstOrNull()
            ?.let { Affiliation.fromCode(it) } ?: Affiliation.UNKNOWN

    /** Battle dimension: G ground, A air, S sea-surface, U subsurface, P space. */
    val dimension: Char? get() = tokens.getOrNull(2)?.firstOrNull()

    val dimensionLabel: String
        get() = when (dimension) {
            'G' -> "Ground"
            'A' -> "Air"
            'S' -> "Surface"
            'U' -> "Subsurface"
            'P' -> "Space"
            else -> "—"
        }

    /** User waypoints/markers are the `b-m-p-*` (bits, map, point) branch, not atoms. */
    val isWaypoint: Boolean get() = raw.startsWith("b-m-p")

    /**
     * Whether this event should appear on the map: atoms (real things) and map
     * points. Filters out chat (`b-t-f`), tasking (`t-*`), and TAK-protocol
     * control events (`t-x-takp-*`).
     */
    val isRenderable: Boolean
        get() = raw.startsWith("a-") || isWaypoint || raw.startsWith("b-a-o")

    companion object {
        /** The self marker type for a given affiliation (friendly ground combat unit). */
        fun self(aff: Affiliation = Affiliation.FRIEND) = CotType("a-${aff.code}-G-U-C")

        /** A generic ground entity for the given affiliation. */
        fun ground(aff: Affiliation) = CotType("a-${aff.code}-G")

        /** A user-placed waypoint (ATAK's `b-m-p-w`). */
        fun waypoint() = CotType("b-m-p-w")
    }
}
