package com.atakwatch.minimap.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable

/**
 * A [ScalingLazyColumn] wired to the rotary side button / crown — the primary
 * scroll input on a Wear device. Without `Modifier.rotaryScrollable` the crown
 * does nothing on a list, so every list screen should use this instead of a
 * bare ScalingLazyColumn.
 *
 * Uses snap behaviour so the crown settles items on the centre of the round
 * display, which is the standard Wear list feel.
 */
@Composable
fun RotaryScalingLazyColumn(
    modifier: Modifier = Modifier,
    state: ScalingLazyListState = rememberScalingLazyListState(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 28.dp),
    content: ScalingLazyListScope.() -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    // The rotary modifier only receives events while its node holds focus.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    ScalingLazyColumn(
        state = state,
        contentPadding = contentPadding,
        modifier = modifier
            .fillMaxSize()
            .rotaryScrollable(
                behavior = RotaryScrollableDefaults.snapBehavior(state),
                focusRequester = focusRequester,
            ),
        content = content,
    )
}
