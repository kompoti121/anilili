package com.miruronative

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.miruronative.data.auth.AuthManager
import com.miruronative.data.library.LibraryStore
import com.miruronative.data.reminder.AutomaticReleaseManager
import com.miruronative.data.reminder.ReleaseSyncScheduler
import com.miruronative.diagnostics.CrashReportDialog
import com.miruronative.diagnostics.CrashReporter
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.data.settings.SettingsStore
import com.miruronative.data.settings.MenuLanguage
import com.miruronative.data.update.UpdateManager
import com.miruronative.ui.detail.DetailScreen
import com.miruronative.ui.FlixcloudResolverWebView
import com.miruronative.ui.HanimeResolverWebView
import com.miruronative.ui.home.HomeScreen
import com.miruronative.ui.PipeWebView
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight
import com.miruronative.ui.adaptive.rememberAppDeviceProfile
import com.miruronative.ui.nav.Routes
import com.miruronative.ui.components.LocalAppChromeBottomInset
import com.miruronative.ui.components.LocalAppChromeVisible
import com.miruronative.ui.notifications.NotificationsScreen
import com.miruronative.ui.profile.ProfileScreen
import com.miruronative.ui.schedule.ScheduleScreen
import com.miruronative.ui.search.SearchScreen
import com.miruronative.ui.settings.SettingsScreen
import com.miruronative.ui.settings.UpdatePromptHost
import com.miruronative.ui.theme.MiruroTheme
import com.miruronative.ui.watch.WatchScreen
import com.miruronative.playback.PlaybackStatus
import com.miruronative.playback.PlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private var inPictureInPicture by mutableStateOf(false)
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        DiagnosticsLog.event("MainActivity.onCreate start savedState=${savedInstanceState != null}")
        window.setBackgroundDrawable(ColorDrawable(Color.rgb(5, 5, 6)))
        window.decorView.setBackgroundColor(Color.rgb(5, 5, 6))
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Color.rgb(5, 5, 6))
        DiagnosticsLog.snapshot(this, "MainActivity.afterSuper")
        DiagnosticsLog.event(
            "MainActivity intent action=${intent.action ?: "none"} " +
                "data=${intent.dataString ?: "none"} categories=${intent.categories?.joinToString() ?: "none"} " +
                "routeExtra=${intent.getStringExtra(Routes.EXTRA_ROUTE) ?: "none"}",
        )
        DiagnosticsLog.watchFirstDraw(window.decorView, "MainActivity")
        pendingRoute = intent.getStringExtra(Routes.EXTRA_ROUTE)
        DiagnosticsLog.event("MainActivity pendingRoute=${pendingRoute ?: "none"}")
        handleAuthRedirect(intent)
        DiagnosticsLog.event("MainActivity.setContent start")
        setContent {
            LaunchedEffect(Unit) {
                DiagnosticsLog.event("MainActivity content composed")
            }
            MiruroTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MiruroRoot(
                        inPictureInPicture = inPictureInPicture,
                        pendingRoute = pendingRoute,
                        onRouteConsumed = { pendingRoute = null },
                    )
                    var crashReport by remember { mutableStateOf(CrashReporter.pendingReport()) }
                    crashReport?.let { report ->
                        CrashReportDialog(report) {
                            CrashReporter.clear()
                            crashReport = null
                        }
                    }
                }
            }
        }
        DiagnosticsLog.event("MainActivity.setContent complete")
        window.decorView.post {
            DiagnosticsLog.event(
                "MainActivity decor after setContent attached=${window.decorView.isAttachedToWindow} " +
                    "shown=${window.decorView.isShown} size=${window.decorView.width}x${window.decorView.height} " +
                    "visibility=${window.decorView.visibilityName()} focus=${window.decorView.hasWindowFocus()}",
            )
        }
        lifecycleScope.launch {
            PlaybackStatus.isPlaying.collect { playing ->
                DiagnosticsLog.event("PlaybackStatus.isPlaying=$playing")
                if (playing) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        DiagnosticsLog.event("MainActivity.onStart")
        PlaybackService.allowMediaButtonResume()
    }

    override fun onResume() {
        super.onResume()
        DiagnosticsLog.event("MainActivity.onResume")
        DiagnosticsLog.snapshot(this, "MainActivity.onResume")
    }

    override fun onPause() {
        super.onPause()
        DiagnosticsLog.event("MainActivity.onPause")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoute = intent.getStringExtra(Routes.EXTRA_ROUTE)
        DiagnosticsLog.event(
            "MainActivity.onNewIntent pendingRoute=${pendingRoute ?: "none"} " +
                "action=${intent.action ?: "none"} data=${intent.dataString ?: "none"}",
        )
        handleAuthRedirect(intent)
    }

    private fun handleAuthRedirect(intent: Intent?) {
        val url = intent?.dataString ?: return
        if (!AuthManager.isRedirect(url)) return
        DiagnosticsLog.event("Auth redirect received")
        AuthManager.extractToken(url)?.let { token ->
            AuthManager.setToken(token)
            LibraryStore.syncSavedToRemote()
            pendingRoute = Routes.MORE
            DiagnosticsLog.event("Auth redirect accepted")
        }
    }

    override fun onStop() {
        super.onStop()
        DiagnosticsLog.event("MainActivity.onStop")
        PlaybackService.pauseActivePlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        DiagnosticsLog.event("MainActivity.onDestroy finishing=$isFinishing changingConfigurations=$isChangingConfigurations")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        DiagnosticsLog.event(
            "MainActivity.onWindowFocusChanged hasFocus=$hasFocus " +
                "decorShown=${window.decorView.isShown} size=${window.decorView.width}x${window.decorView.height}",
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        DiagnosticsLog.event(
            "MainActivity.onConfigurationChanged orientation=${newConfig.orientation} " +
                "screenDp=${newConfig.screenWidthDp}x${newConfig.screenHeightDp} uiMode=${newConfig.uiMode}",
        )
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
        DiagnosticsLog.event("PictureInPicture changed active=$isInPictureInPictureMode")
    }

}

private fun View.visibilityName(): String = when (visibility) {
    View.VISIBLE -> "visible"
    View.INVISIBLE -> "invisible"
    View.GONE -> "gone"
    else -> visibility.toString()
}

private enum class Tab(
    val route: String,
    private val englishLabel: String,
    private val spanishLabel: String,
    val icon: ImageVector,
) {
    HOME(Routes.HOME, "Home", "Inicio", Icons.Default.Home),
    SEARCH(Routes.SEARCH, "Search", "Buscar", Icons.Default.Search),
    SCHEDULE(Routes.SCHEDULE, "Schedule", "Calendario", Icons.Default.DateRange),
    MORE(Routes.MORE, "Library", "Biblioteca", Icons.AutoMirrored.Filled.List),
    SETTINGS(Routes.SETTINGS, "Settings", "Ajustes", Icons.Default.Settings),
    ;

    fun label(language: MenuLanguage): String = if (language.usesSpanish()) spanishLabel else englishLabel
}

/** Search is launched from Home's top action on phones; TV keeps it in the navigation rail. */
private val phoneTabs = Tab.entries.filterNot { it == Tab.SEARCH }

/** Compact phone navigation content height; the system navigation inset is added separately. */
private val PhoneNavigationBarHeight = 64.dp

@Composable
private fun MiruroRoot(
    inPictureInPicture: Boolean,
    pendingRoute: String?,
    onRouteConsumed: () -> Unit,
) {
    val deviceProfile = rememberAppDeviceProfile()
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = currentRoute in Routes.tabRoutes
    val menuLanguage by SettingsStore.menuLanguage.collectAsState()
    var resolverWebViewsReady by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    val chromeScope = rememberCoroutineScope()
    var restoreChromeJob by remember { mutableStateOf<Job?>(null) }
    val tvSearchRailFocusRequester = remember { FocusRequester() }
    val tvSearchFieldFocusRequester = remember { FocusRequester() }
    // Direction-based like YouTube/Chrome: hide once a downward scroll passes a small threshold,
    // show the moment the user scrolls up (or goes idle). The threshold stops micro-scrolls from
    // flickering the chrome, and hide/show firing once per direction change (instead of on every
    // scroll frame) is what keeps the animation smooth.
    val chromeHideThresholdPx = with(LocalDensity.current) { 24.dp.toPx() }
    val chromeScrollConnection = remember(deviceProfile.isTv, chromeHideThresholdPx) {
        object : NestedScrollConnection {
            private var accumulated = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (deviceProfile.isTv || available.y == 0f) return Offset.Zero
                if ((accumulated < 0f) != (available.y < 0f)) accumulated = 0f
                accumulated += available.y
                if (accumulated < -chromeHideThresholdPx && chromeVisible) {
                    chromeVisible = false
                } else if (accumulated > chromeHideThresholdPx && !chromeVisible) {
                    chromeVisible = true
                    restoreChromeJob?.cancel()
                }
                if (!chromeVisible) {
                    // Long enough that a pause mid-scroll does not summon the navigation bar
                    // under a thumb already on its way to a poster, short enough that the bars
                    // feel available rather than dismissed.
                    restoreChromeJob?.cancel()
                    restoreChromeJob = chromeScope.launch {
                        delay(1_000)
                        chromeVisible = true
                    }
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(Unit) {
        DiagnosticsLog.event(
            "MiruroRoot composed formFactor=${deviceProfile.formFactor} " +
                "widthDp=${deviceProfile.widthDp} navRail=${deviceProfile.useNavigationRail}",
        )
    }

    LaunchedEffect(Unit) {
        delay(if (deviceProfile.isTv) 3_000 else 1_000)
        resolverWebViewsReady = true
        DiagnosticsLog.event("Resolver WebView startup delay elapsed")
    }

    LaunchedEffect(currentRoute) {
        DiagnosticsLog.event("Nav route=${currentRoute ?: "none"}")
    }

    LaunchedEffect(pendingRoute) {
        pendingRoute?.takeIf { it.isNotBlank() }?.let { route ->
            DiagnosticsLog.event("Consuming pending route=$route")
            // Tabs must go through navigateTab: a plain navigate pushes the tab on top of the
            // start destination, and the next Home tap then restores that entry as Home's state.
            if (route in Routes.tabRoutes) nav.navigateTab(route) else nav.navigate(route) { launchSingleTop = true }
            onRouteConsumed()
        }
    }

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        SettingsStore.awaitLoaded()
        if (!SettingsStore.updateCheckOnLaunch.value) {
            DiagnosticsLog.event("UpdateManager.autoCheckIfDue skipped (disabled in settings)")
            return@LaunchedEffect
        }
        DiagnosticsLog.event("UpdateManager.autoCheckIfDue start")
        UpdateManager.autoCheckIfDue(context)
        DiagnosticsLog.event("UpdateManager.autoCheckIfDue complete")
    }

    val navBarInsetForChrome = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val hasPhoneBottomBarForChrome = showBottomBar && !deviceProfile.useNavigationRail
    CompositionLocalProvider(
        LocalAppDeviceProfile provides deviceProfile,
        LocalAppChromeVisible provides (chromeVisible || deviceProfile.isTv),
        LocalAppChromeBottomInset provides if (hasPhoneBottomBarForChrome) {
            PhoneNavigationBarHeight + navBarInsetForChrome
        } else {
            0.dp
        },
    ) {
        NotificationPermissionEffect()
        UpdatePromptHost()
        Box(Modifier.fillMaxSize().nestedScroll(chromeScrollConnection)) {
            val hasPhoneBottomBar = showBottomBar && !deviceProfile.useNavigationRail
            // The bar overlays the content and slides out via graphicsLayer, so it neither
            // re-lays out the screen during animation nor leaves a reserved background band.
            // Tab content also draws behind the system navigation area for a fully edge-to-edge
            // viewport when the bar is hidden; non-tab screens retain Scaffold's safe inset.
            val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
            ) { innerPadding ->
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(
                            bottom = if (hasPhoneBottomBar) 0.dp else innerPadding.calculateBottomPadding(),
                        ),
                ) {
                    if (showBottomBar && deviceProfile.useNavigationRail) {
                        AppNavigationRail(
                            currentRoute = currentRoute,
                            menuLanguage = menuLanguage,
                            onNavigate = nav::navigateTab,
                            searchRailFocusRequester = tvSearchRailFocusRequester,
                            searchFieldFocusRequester = tvSearchFieldFocusRequester,
                            modifier = Modifier.fillMaxHeight(),
                        )
                    }
                    AppNavHost(
                        nav = nav,
                        inPictureInPicture = inPictureInPicture,
                        tvSearchFieldFocusRequester = tvSearchFieldFocusRequester,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (hasPhoneBottomBar) {
                val chromeShift by animateFloatAsState(
                    targetValue = if (chromeVisible) 0f else 1f,
                    animationSpec = tween(220),
                    label = "chromeShift",
                )
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .height(PhoneNavigationBarHeight + navBarInset)
                        .graphicsLayer { translationY = size.height * chromeShift },
                ) {
                    phoneTabs.forEach { tab ->
                        val label = tab.label(menuLanguage)
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = label) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                indicatorColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            }
            // Hidden resolver WebViews are not needed for Home. On slow Android TV boxes,
            // creating WebView during first composition can delay the first visible frame.
            if (resolverWebViewsReady) {
                PipeWebView()
                FlixcloudResolverWebView()
                // Only carried once adult content is on. A viewer who never enables it does not
                // pay for a third resident WebView — which matters most on memory-starved sticks.
                val hideAdult by SettingsStore.hideAdultContent.collectAsState()
                if (!hideAdult) {
                    HanimeResolverWebView()
                    // The catalogue is a single bulk file with no per-title endpoint behind it, so
                    // fetch it while the viewer is still in Settings rather than making their
                    // first search wait for it.
                    LaunchedEffect(Unit) {
                        com.miruronative.data.AppGraph.repository.warmHanimeCatalogue()
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationPermissionEffect() {
    val context = LocalContext.current
    val device = LocalAppDeviceProfile.current
    val enabled by SettingsStore.releaseNotifications.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        SettingsStore.setReleaseNotifications(granted)
        if (granted) ReleaseSyncScheduler.runNow(context) else AutomaticReleaseManager.cancelAll()
    }

    LaunchedEffect(enabled, device.isTv) {
        if (!enabled) {
            AutomaticReleaseManager.cancelAll()
            return@LaunchedEffect
        }
        if (device.isTv || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            ReleaseSyncScheduler.runNow(context)
            return@LaunchedEffect
        }
        val prefs = context.getSharedPreferences("anilili_permissions", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("release_notifications_prompted", false)) {
            prefs.edit().putBoolean("release_notifications_prompted", true).apply()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            SettingsStore.setReleaseNotifications(false)
            AutomaticReleaseManager.cancelAll()
        }
    }
}

@Composable
private fun AppNavigationRail(
    currentRoute: String?,
    menuLanguage: MenuLanguage,
    onNavigate: (String) -> Unit,
    searchRailFocusRequester: FocusRequester,
    searchFieldFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val device = LocalAppDeviceProfile.current
    val focusRequesters = remember(searchRailFocusRequester) {
        Tab.entries.associateWith { tab ->
            if (tab == Tab.SEARCH) searchRailFocusRequester else FocusRequester()
        }
    }
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
            val label = tab.label(menuLanguage)
            NavigationRailItem(
                selected = currentRoute == tab.route,
                onClick = { onNavigate(tab.route) },
                icon = { Icon(tab.icon, contentDescription = label) },
                label = { Text(label) },
                alwaysShowLabel = device.isTv,
                modifier = Modifier
                    .focusRequester(focusRequesters.getValue(tab))
                    .focusProperties {
                        // Spatial focus search prefers the Movies chip because it is horizontally
                        // aligned with the rail item. Route Right to the actual search box.
                        if (device.isTv && tab == Tab.SEARCH && currentRoute == Routes.SEARCH) {
                            right = searchFieldFocusRequester
                        }
                    }
                    .focusHighlight(),
            )
        }
    }
}

@Composable
private fun AppNavHost(
    nav: androidx.navigation.NavHostController,
    inPictureInPicture: Boolean,
    tvSearchFieldFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = modifier,
    ) {
            composable(Routes.HOME) {
                LaunchedEffect(Unit) { DiagnosticsLog.event("Route HOME content entered") }
                HomeScreen(
                    onAnimeClick = { id -> nav.navigate(Routes.detail(id)) },
                    onWatchNow = { id ->
                        val saved = com.miruronative.data.library.LibraryStore.historyFor(id)
                        if (saved != null) nav.navigate(Routes.watch(id, saved.provider, saved.category, saved.episodeLabel))
                        else nav.navigate(Routes.watch(id, "auto", if (com.miruronative.data.settings.SettingsStore.preferDub.value) "dub" else "sub", "1"))
                    },
                    onResume = { e -> nav.navigate(Routes.watch(e.anilistId, e.provider, e.category, e.episodeLabel)) },
                    onSearchClick = { nav.navigateTab(Routes.SEARCH) },
                    onNotificationsClick = { nav.navigate(Routes.NOTIFICATIONS) { launchSingleTop = true } },
                )
            }
            composable(Routes.NOTIFICATIONS) {
                LaunchedEffect(Unit) { DiagnosticsLog.event("Route NOTIFICATIONS content entered") }
                NotificationsScreen(
                    onBack = { nav.popBackStack() },
                    onAnimeClick = { id -> nav.navigate(Routes.detail(id)) },
                )
            }
            composable(Routes.SEARCH) {
                LaunchedEffect(Unit) { DiagnosticsLog.event("Route SEARCH content entered") }
                SearchScreen(
                    onAnimeClick = { id -> nav.navigate(Routes.detail(id)) },
                    tvFieldFocusRequester = tvSearchFieldFocusRequester,
                )
            }
            composable(Routes.SCHEDULE) {
                LaunchedEffect(Unit) { DiagnosticsLog.event("Route SCHEDULE content entered") }
                ScheduleScreen(onAnimeClick = { id -> nav.navigate(Routes.detail(id)) })
            }
            composable(Routes.MORE) {
                LaunchedEffect(Unit) { DiagnosticsLog.event("Route MORE content entered") }
                ProfileScreen(
                    onAnimeClick = { id -> nav.navigate(Routes.detail(id)) },
                    onResume = { e ->
                        nav.navigate(Routes.watch(e.anilistId, e.provider, e.category, e.episodeLabel))
                    },
                )
            }
            composable(Routes.SETTINGS) {
                LaunchedEffect(Unit) { DiagnosticsLog.event("Route SETTINGS content entered") }
                SettingsScreen()
            }

            composable(
                route = Routes.DETAIL,
                arguments = listOf(navArgument(Routes.Arg.ID) { type = NavType.IntType }),
            ) { entry ->
                val id = entry.arguments?.getInt(Routes.Arg.ID) ?: return@composable
                val deviceProfile = LocalAppDeviceProfile.current
                LaunchedEffect(id) { DiagnosticsLog.event("Route DETAIL content entered id=$id") }
                DetailScreen(
                    animeId = id,
                    onBack = { nav.popBackStack() },
                    onAnimeClick = { relatedId ->
                        if (relatedId != id) nav.navigate(Routes.detail(relatedId))
                    },
                    onPlay = { playId, provider, category, episode ->
                        // TV: Watch lands on the episode grid (playback starts inline) so the
                        // user picks an episode; going straight to fullscreen autoplay left no
                        // way to choose one. Phones keep the direct-to-player behavior.
                        // playId may be another season of the same series — the detail page
                        // hosts the whole chain and its Episodes tab filters between seasons.
                        if (deviceProfile.isTv) {
                            nav.navigate(Routes.episodes(playId, provider, category, episode))
                        } else {
                            nav.navigate(Routes.watch(playId, provider, category, episode))
                        }
                    },
                    onSeasonWatch = { seasonId ->
                        val saved = com.miruronative.data.library.LibraryStore.historyFor(seasonId)
                        if (saved != null) {
                            nav.navigate(Routes.episodes(seasonId, saved.provider, saved.category, saved.episodeLabel))
                        } else {
                            nav.navigate(Routes.episodes(seasonId, "auto", if (com.miruronative.data.settings.SettingsStore.preferDub.value) "dub" else "sub", "1"))
                        }
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
                    navArgument(Routes.Arg.SHOW_EPISODES) {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
            ) { entry ->
                val args = entry.arguments ?: return@composable
                val watchId = args.getInt(Routes.Arg.ID)
                val watchProvider = args.getString(Routes.Arg.PROVIDER).orEmpty()
                val watchCategory = args.getString(Routes.Arg.CATEGORY).orEmpty()
                val watchEpisode = args.getString(Routes.Arg.EPISODE).orEmpty()
                val showEpisodes = args.getBoolean(Routes.Arg.SHOW_EPISODES)
                LaunchedEffect(watchId, watchProvider, watchCategory, watchEpisode) {
                    DiagnosticsLog.event(
                        "Route WATCH content entered id=$watchId provider=$watchProvider " +
                            "category=$watchCategory episode=$watchEpisode",
                    )
                }
                WatchScreen(
                    animeId = watchId,
                    provider = watchProvider,
                    category = watchCategory,
                    episode = watchEpisode,
                    showEpisodeListInitially = showEpisodes,
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
