package com.miruronative

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.miruronative.ui.detail.DetailScreen
import com.miruronative.ui.home.HomeScreen
import com.miruronative.ui.PipeWebView
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight
import com.miruronative.ui.adaptive.rememberAppDeviceProfile
import com.miruronative.ui.nav.Routes
import com.miruronative.ui.profile.ProfileScreen
import com.miruronative.ui.schedule.ScheduleScreen
import com.miruronative.ui.search.SearchScreen
import com.miruronative.ui.theme.MiruroTheme
import com.miruronative.ui.watch.WatchScreen
import com.miruronative.playback.PlaybackStatus
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var inPictureInPicture by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            MiruroTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MiruroRoot(inPictureInPicture)
                }
            }
        }
        lifecycleScope.launch {
            PlaybackStatus.isPlaying.collect { playing ->
                if (playing) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !isTelevision()) {
                    val root = window.decorView
                    setPictureInPictureParams(
                        PictureInPictureParams.Builder()
                            .setAspectRatio(Rational(16, 9))
                            .setAutoEnterEnabled(playing)
                            .setSourceRectHint(Rect(0, 0, root.width.coerceAtLeast(1), root.height.coerceAtLeast(1)))
                            .build(),
                    )
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isTelevision() && PlaybackStatus.isPlaying.value) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build(),
            )
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
    }

    private fun isTelevision(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
            Configuration.UI_MODE_TYPE_TELEVISION
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME(Routes.HOME, "Home", Icons.Default.Home),
    SEARCH(Routes.SEARCH, "Search", Icons.Default.Search),
    SCHEDULE(Routes.SCHEDULE, "Schedule", Icons.Default.DateRange),
    MORE(Routes.MORE, "Library", Icons.AutoMirrored.Filled.List),
}

@Composable
private fun MiruroRoot(inPictureInPicture: Boolean) {
    val deviceProfile = rememberAppDeviceProfile()
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = currentRoute in Routes.tabRoutes

    CompositionLocalProvider(LocalAppDeviceProfile provides deviceProfile) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    if (showBottomBar && !deviceProfile.useNavigationRail) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            Tab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = currentRoute == tab.route,
                                    onClick = { nav.navigateTab(tab.route) },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                        indicatorColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                    ),
                                )
                            }
                        }
                    }
                },
            ) { innerPadding ->
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(bottom = innerPadding.calculateBottomPadding()),
                ) {
                    if (showBottomBar && deviceProfile.useNavigationRail) {
                        AppNavigationRail(
                            currentRoute = currentRoute,
                            onNavigate = nav::navigateTab,
                            modifier = Modifier.fillMaxHeight(),
                        )
                    }
                    AppNavHost(
                        nav = nav,
                        inPictureInPicture = inPictureInPicture,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            // Hidden Cloudflare-cleared WebView that carries all pipe requests.
            PipeWebView()
        }
    }
}

@Composable
private fun AppNavigationRail(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = LocalAppDeviceProfile.current
    val focusRequesters = remember { Tab.entries.associateWith { FocusRequester() } }
    LaunchedEffect(currentRoute, device.isTv) {
        if (device.isTv) {
            Tab.entries.firstOrNull { it.route == currentRoute }
                ?.let { focusRequesters.getValue(it).requestFocus() }
        }
    }
    NavigationRail(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        header = {
            Text(
                "anilili",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 20.dp),
            )
        },
    ) {
        Tab.entries.forEach { tab ->
            NavigationRailItem(
                selected = currentRoute == tab.route,
                onClick = { onNavigate(tab.route) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
                alwaysShowLabel = device.isTv,
                modifier = Modifier
                    .focusRequester(focusRequesters.getValue(tab))
                    .focusHighlight(),
            )
        }
    }
}

@Composable
private fun AppNavHost(
    nav: androidx.navigation.NavHostController,
    inPictureInPicture: Boolean,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = modifier,
    ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onAnimeClick = { id -> nav.navigate(Routes.detail(id)) },
                    onWatchNow = { id ->
                        val saved = com.miruronative.data.library.LibraryStore.historyFor(id)
                        if (saved != null) nav.navigate(Routes.watch(id, saved.provider, saved.category, saved.episodeLabel))
                        else nav.navigate(Routes.watch(id, "auto", if (com.miruronative.data.settings.SettingsStore.preferDub.value) "dub" else "sub", "1"))
                    },
                    onResume = { e -> nav.navigate(Routes.watch(e.anilistId, e.provider, e.category, e.episodeLabel)) },
                    onSearchClick = { nav.navigateTab(Routes.SEARCH) },
                )
            }
            composable(Routes.SEARCH) {
                SearchScreen(onAnimeClick = { id -> nav.navigate(Routes.detail(id)) })
            }
            composable(Routes.SCHEDULE) {
                ScheduleScreen(onAnimeClick = { id -> nav.navigate(Routes.detail(id)) })
            }
            composable(Routes.MORE) {
                ProfileScreen(
                    onAnimeClick = { id -> nav.navigate(Routes.detail(id)) },
                    onResume = { e ->
                        nav.navigate(Routes.watch(e.anilistId, e.provider, e.category, e.episodeLabel))
                    },
                )
            }

            composable(
                route = Routes.DETAIL,
                arguments = listOf(navArgument(Routes.Arg.ID) { type = NavType.IntType }),
            ) { entry ->
                val id = entry.arguments?.getInt(Routes.Arg.ID) ?: return@composable
                DetailScreen(
                    animeId = id,
                    onBack = { nav.popBackStack() },
                    onPlay = { provider, category, episode ->
                        nav.navigate(Routes.watch(id, provider, category, episode))
                    },
                )
            }

            composable(
                route = Routes.WATCH,
                arguments = listOf(
                    navArgument(Routes.Arg.ID) { type = NavType.IntType },
                    navArgument(Routes.Arg.PROVIDER) { type = NavType.StringType },
                    navArgument(Routes.Arg.CATEGORY) { type = NavType.StringType },
                    navArgument(Routes.Arg.EPISODE) { type = NavType.StringType },
                ),
            ) { entry ->
                val args = entry.arguments ?: return@composable
                WatchScreen(
                    animeId = args.getInt(Routes.Arg.ID),
                    provider = args.getString(Routes.Arg.PROVIDER).orEmpty(),
                    category = args.getString(Routes.Arg.CATEGORY).orEmpty(),
                    episode = args.getString(Routes.Arg.EPISODE).orEmpty(),
                    inPictureInPicture = inPictureInPicture,
                    onBack = { nav.popBackStack() },
                )
            }
        }
}

private fun NavController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
