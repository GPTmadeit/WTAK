package com.atakwatch.minimap.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.wear.compose.foundation.SwipeToDismissBoxState
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.navigation.SwipeDismissableNavHostState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.compose.navigation.composable
import com.atakwatch.minimap.ATAKWatchApp
import com.atakwatch.minimap.data.ChatSender
import com.atakwatch.minimap.data.Settings
import com.atakwatch.minimap.data.SettingsRepository
import com.atakwatch.minimap.ui.about.AboutScreen
import com.atakwatch.minimap.ui.chat.ChatScreen
import com.atakwatch.minimap.ui.contacts.ContactsScreen
import com.atakwatch.minimap.ui.detail.EntityDetailScreen
import com.atakwatch.minimap.ui.map.MapScreen
import com.atakwatch.minimap.ui.menu.MenuScreen
import com.atakwatch.minimap.ui.onboarding.OnboardingScreen
import com.atakwatch.minimap.ui.radar.RadarScreen
import com.atakwatch.minimap.ui.radio.RadioScreen
import com.atakwatch.minimap.ui.settings.SettingsScreen
import com.atakwatch.minimap.ui.update.UpdateScreen

object Routes {
    const val MAP = "map"
    const val MENU = "menu"
    const val CONTACTS = "contacts"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val DETAIL = "detail"
    const val CHAT = "chat"
    const val RADAR = "radar"
    const val RADIO = "radio"
    const val UPDATE = "update"

    fun detail(uid: String): String = "$DETAIL/${android.net.Uri.encode(uid)}"
}

@Composable
fun ATAKWatchRoot() {
    val nav = rememberSwipeDismissableNavController()
    val repo = rememberSettingsRepository()
    val settings by collectSettings()

    // Wear's swipe-to-dismiss claims left-to-right drags across the *whole*
    // screen — it is not an edge gesture — which is why panning the map used to
    // back you out of the app.
    //
    // It cannot simply be switched off for the map: with nothing in the app
    // consuming the drag, the platform's own dismissal takes over and closes
    // the activity, which is the same behaviour by a different route. The nav
    // host has to receive the gesture and decline to act on it, which is what
    // `edgeSwipeToDismiss` does — applied by the map itself.
    //
    // That modifier arbitrates through this shared state, and the map stays
    // composed as the background of whatever you open, so its verdict ("this
    // drag didn't start at the edge") used to be applied to the screen in front
    // of it as well — leaving the map by tapping anything away from the left
    // edge made the next screen impossible to swipe back from. The current
    // route is published so the map can apply the restriction only while it is
    // actually the screen in front.
    val swipeState = rememberSwipeToDismissBoxState()
    val navHostState = remember(swipeState) { SwipeDismissableNavHostState(swipeState) }

    var currentRoute by remember { mutableStateOf<String?>(Routes.MAP) }
    DisposableEffect(nav) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            currentRoute = destination.route
        }
        nav.addOnDestinationChangedListener(listener)
        onDispose { nav.removeOnDestinationChangedListener(listener) }
    }

    CompositionLocalProvider(
        LocalSwipeState provides swipeState,
        LocalCurrentRoute provides currentRoute,
    ) {
        AppScaffold {
            // First run goes through setup, which can pull the operator's
            // identity straight from a paired EUD instead of asking again.
            if (!settings.onboarded) {
                OnboardingScreen(repo) { /* settings flow flips onboarded */ }
                return@AppScaffold
            }
            SwipeDismissableNavHost(
                navController = nav,
                startDestination = Routes.MAP,
                state = navHostState,
            ) {
                composable(Routes.MAP) { MapScreen(nav) }
                composable(Routes.RADAR) { RadarScreen(nav) }
                composable(Routes.RADIO) { RadioScreen(nav) }
                composable(Routes.MENU) { MenuScreen(nav) }
                composable(Routes.CONTACTS) { ContactsScreen(nav) }
                composable(Routes.SETTINGS) { SettingsScreen(nav) }
                composable(Routes.ABOUT) { AboutScreen() }
                composable(Routes.UPDATE) { UpdateScreen() }
                composable(Routes.CHAT) { ChatScreen(onSend = { ChatSender.send(it) }) }
                composable("${Routes.DETAIL}/{uid}") { entry ->
                    EntityDetailScreen(nav, entry.arguments?.getString("uid").orEmpty())
                }
            }
        }
    }
}

/**
 * The nav host's swipe-to-dismiss state, so the map can restrict dismissal to
 * its left edge and keep the rest of the surface free to pan.
 */
val LocalSwipeState = compositionLocalOf<SwipeToDismissBoxState?> { null }

/**
 * The destination actually in front. A screen kept composed as the swipe
 * background must not arbitrate gestures for the one on top of it.
 */
val LocalCurrentRoute = compositionLocalOf<String?> { null }

@Composable
fun rememberSettingsRepository(): SettingsRepository {
    val ctx = LocalContext.current
    return (ctx.applicationContext as ATAKWatchApp).settings
}

@Composable
fun collectSettings(): State<Settings> =
    rememberSettingsRepository().settings.collectAsStateWithLifecycle(initialValue = Settings())
