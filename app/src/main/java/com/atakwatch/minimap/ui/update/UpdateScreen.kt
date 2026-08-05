package com.atakwatch.minimap.ui.update

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.atakwatch.minimap.BuildConfig
import com.atakwatch.minimap.ui.components.RotaryScalingLazyColumn
import com.atakwatch.minimap.ui.components.SectionHeader
import com.atakwatch.minimap.update.UpdateChecker
import com.atakwatch.minimap.update.Updater
import kotlin.math.roundToInt

/**
 * Update the app from its own GitHub releases, on the watch.
 *
 * The release notes are shown before anything is downloaded, because deciding
 * whether to take an update in the field is a judgement call — and this is a
 * sideloaded app, so nobody else is going to make it for you.
 */
@Composable
fun UpdateScreen() {
    val context = LocalContext.current
    val state by Updater.state.collectAsStateWithLifecycle()

    // Check on arrival; the screen exists to answer one question.
    LaunchedEffect(Unit) {
        if (state is Updater.State.Idle) Updater.check()
    }

    RotaryScalingLazyColumn {
        item { ListHeader { Text("Update") } }

        item {
            Text(
                "Installed  ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        when (val s = state) {
            is Updater.State.Idle, is Updater.State.Checking -> item {
                Status("Checking GitHub…")
            }

            is Updater.State.UpToDate -> {
                item { Status("You are on the latest release.") }
                item { CheckAgain() }
            }

            is Updater.State.Available -> {
                item {
                    Text(
                        s.release.versionName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text(
                        "${(s.release.apkBytes / 1024f / 1024f).let { "%.1f".format(it) }} MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // The action comes before the notes. Release notes are written
                // for a browser and run to several screens of scrolling on a
                // watch, which buried the one control this screen exists for.
                item {
                    Button(
                        onClick = { Updater.install(context, s.release) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Download & install") }
                }
                item {
                    Text(
                        "Your settings, waypoints and certificates are kept.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (s.release.notes.isNotBlank()) {
                    item { SectionHeader("What changed") }
                    item {
                        Text(
                            s.release.notes.summarise(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            is Updater.State.NeedsPermission -> {
                item {
                    Text(
                        "This watch has to allow WTAK to install apps before it " +
                            "can update itself. It is a one-time switch.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                val settings = Updater.unknownSourcesSettings(context)
                if (settings != null) {
                    item {
                        Button(
                            onClick = {
                                context.startActivity(
                                    settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Open settings") }
                    }
                    item {
                        Text(
                            "Turn on \"Allow from this source\", come back, and " +
                                "tap install again.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    item {
                        Text(
                            "This watch has no screen for that, so this build " +
                                "must be installed over adb.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    Button(
                        onClick = { Updater.install(context, s.release) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Try again") }
                }
            }

            is Updater.State.Downloading -> item {
                Status("Downloading  ${(s.fraction * 100).roundToInt()}%")
            }

            is Updater.State.Verifying -> item { Status("Verifying signature…") }

            is Updater.State.Confirming -> item {
                Status("Confirm the install when the watch asks.")
            }

            is Updater.State.Failed -> {
                item {
                    Text(
                        s.reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item { CheckAgain() }
            }
        }

        item {
            Text(
                "github.com/${UpdateChecker.REPO}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun Status(text: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CheckAgain() {
    Button(
        onClick = { Updater.check() },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Check again") }
}

/**
 * Release notes are written for a browser, not a 1.4" screen. Keep the opening
 * prose and drop the markdown furniture so the gist survives the trip.
 */
private fun String.summarise(maxChars: Int = 300): String {
    val plain = lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("|") && !it.startsWith("```") }
        .map { line ->
            line.removePrefix("### ").removePrefix("## ").removePrefix("# ")
                .removePrefix("- ").removePrefix("> ")
                .replace("**", "").replace("`", "")
        }
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return if (plain.length <= maxChars) plain else plain.take(maxChars).substringBeforeLast(' ') + "…"
}
