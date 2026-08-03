package com.atakwatch.minimap.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.atakwatch.minimap.ui.motion.Motion
import com.atakwatch.minimap.ui.motion.pressScale

/** A navigation row: leading icon, label, trailing chevron. */
@Composable
fun NavRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().pressScale(interaction, pressedScale = 0.96f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ChevronRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

/** A settings row: label on the left, current value (accent-coloured) on the right. */
@Composable
fun ValueRow(label: String, value: String, valueColor: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().pressScale(interaction, pressedScale = 0.96f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val fadeSpec = Motion.fastEffects<Float>()
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            // Values change under you — a settings row cycling, a link state
            // going live. Crossfading says "this just changed" where a hard
            // swap reads as a glitch.
            AnimatedContent(
                targetState = value,
                transitionSpec = { fadeIn(fadeSpec) togetherWith fadeOut(fadeSpec) },
                label = "value",
            ) { shown ->
                Text(
                    shown,
                    style = MaterialTheme.typography.labelMedium,
                    color = valueColor,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A small filled affiliation dot. */
@Composable
fun AffiliationDot(color: Color, size: Int = 12) {
    Box(modifier = Modifier.size(size.dp).clip(CircleShape).background(color))
}

/** Uppercase section label for grouping settings. */
@Composable
fun SectionHeader(text: String) {
    androidx.wear.compose.material3.ListSubHeader {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** A real switch for boolean settings — state readable at a glance. */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    secondary: String? = null,
    onChange: (Boolean) -> Unit,
) {
    if (secondary == null) {
        androidx.wear.compose.material3.SwitchButton(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
    } else {
        androidx.wear.compose.material3.SwitchButton(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            secondaryLabel = { Text(secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
    }
}
