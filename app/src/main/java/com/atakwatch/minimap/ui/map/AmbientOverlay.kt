package com.atakwatch.minimap.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import com.atakwatch.minimap.ui.AmbientState

/**
 * Low-power always-on presentation. Replaces the live map with a black screen
 * carrying only the essentials (callsign, position, heading) so the OLED draws
 * almost nothing.
 *
 * Burn-in mitigation: when the platform asks for it, content is nudged by a few
 * pixels on every ambient tick and rendered as thin text on black rather than
 * filled shapes.
 */
@Composable
fun AmbientMapOverlay(
    ambient: AmbientState,
    callsign: String,
    coordinate: String,
    heading: String?,
    accent: Color,
) {
    // Shift within a small box on each tick so no pixel stays lit indefinitely.
    val shift = if (ambient.burnInProtection) (ambient.tick % 5) - 2 else 0

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .offset(x = shift.dp, y = shift.dp)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = callsign,
                color = if (ambient.lowBitAmbient) Color.White else accent,
                textAlign = TextAlign.Center,
                fontSize = 15.sp,
            )
            Text(
                text = coordinate,
                color = if (ambient.lowBitAmbient) Color.White else Color(0xFFBBBBBB),
                textAlign = TextAlign.Center,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
            if (heading != null) {
                Text(
                    text = heading,
                    color = if (ambient.lowBitAmbient) Color.White else Color(0xFF8FB8CC),
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
