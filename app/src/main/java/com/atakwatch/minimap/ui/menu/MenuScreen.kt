package com.atakwatch.minimap.ui.menu

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Text
import com.atakwatch.minimap.ui.Routes
import com.atakwatch.minimap.data.ChatRepository
import com.atakwatch.minimap.ui.components.NavRow
import com.atakwatch.minimap.ui.components.RotaryScalingLazyColumn

@Composable
fun MenuScreen(nav: NavController) {
    RotaryScalingLazyColumn {
        item { ListHeader { Text("WTAK") } }
        item { NavRow(Icons.Filled.TrackChanges, "Radar") { nav.navigate(Routes.RADAR) } }
        item { NavRow(Icons.Filled.Groups, "Contacts") { nav.navigate(Routes.CONTACTS) } }
        item {
            val unread by ChatRepository.unread.collectAsStateWithLifecycle()
            NavRow(
                Icons.AutoMirrored.Filled.Chat,
                if (unread > 0) "GeoChat ($unread)" else "GeoChat",
            ) { nav.navigate(Routes.CHAT) }
        }
        item { NavRow(Icons.Filled.Settings, "Settings") { nav.navigate(Routes.SETTINGS) } }
        item { NavRow(Icons.Filled.Info, "About") { nav.navigate(Routes.ABOUT) } }
    }
}
