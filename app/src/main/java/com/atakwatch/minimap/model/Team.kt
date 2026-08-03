package com.atakwatch.minimap.model

import androidx.compose.ui.graphics.Color

/**
 * ATAK team colors — the standard palette ATAK offers in its own settings, and
 * the exact strings it puts in the `<__group name="..."/>` detail element.
 * (In real ATAK, `__group` carries team color + role, NOT affiliation.)
 */
enum class TeamColor(val label: String, val color: Color) {
    WHITE("White", Color(0xFFFFFFFF)),
    YELLOW("Yellow", Color(0xFFFFFF00)),
    ORANGE("Orange", Color(0xFFFF7F00)),
    MAGENTA("Magenta", Color(0xFFFF00FF)),
    RED("Red", Color(0xFFFF0000)),
    MAROON("Maroon", Color(0xFF7F0000)),
    PURPLE("Purple", Color(0xFF7F007F)),
    DARK_BLUE("Dark Blue", Color(0xFF00007F)),
    BLUE("Blue", Color(0xFF0000FF)),
    CYAN("Cyan", Color(0xFF00FFFF)),
    TEAL("Teal", Color(0xFF007F7F)),
    GREEN("Green", Color(0xFF00FF00)),
    DARK_GREEN("Dark Green", Color(0xFF007F00)),
    BROWN("Brown", Color(0xFF7F3F00));

    companion object {
        /** Case-insensitive match on the wire string; null when unknown. */
        fun fromLabel(label: String?): TeamColor? =
            label?.let { l -> entries.firstOrNull { it.label.equals(l, ignoreCase = true) } }
    }
}

/** ATAK team roles, as offered by ATAK's own "My Callsign" settings. */
enum class TeamRole(val label: String) {
    TEAM_MEMBER("Team Member"),
    TEAM_LEAD("Team Lead"),
    HQ("HQ"),
    SNIPER("Sniper"),
    MEDIC("Medic"),
    FORWARD_OBSERVER("Forward Observer"),
    RTO("RTO"),
    K9("K9");
}
