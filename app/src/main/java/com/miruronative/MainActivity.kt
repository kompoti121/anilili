package com.miruronative

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
import com.miruronative.ui.components.AppLaunchSplash
import com.miruronative.ui.components.LaunchSplashFadeMillis
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
import com.miruronative.ui.watch.DownloadedEpisodeScreen
import com.miruronative.playback.PlaybackStatus
import com.miruronative.playback.PlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : FragmentActivity() {
    private var inPictureInPicture by mutableStateOf(false)
    private var pendingRoute by mutableStateOf<String?>(null)
    private var pictureInPictureReady = false

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
                    var showLaunchSplash by rememberSaveable { mutableStateOf(true) }
                    Box(Modifier.fillMaxSize()) {
                        MiruroRoot(
                            inPictureInPicture = inPictureInPicture,
                            onPictureInPictureReadyChanged = ::setPictureInPictureReady,
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
                        AnimatedVisibility(
                            visible = showLaunchSplash,
                            modifier = Modifier.fillMaxSize(),
                            exit = fadeOut(tween(LaunchSplashFadeMillis)),
                        ) {
                            AppLaunchSplash(onFinished = { showLaunchSplash = false })
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
        LibraryStore.refreshRemoteLibrary()
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
            LibraryStore.refreshRemoteLibrary(force = true)
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

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT !in Build.VERSION_CODES.O until Build.VERSION_CODES.S) return
        if (!pictureInPictureReady || inPictureInPicture) return
        enterPictureInPicture()
    }

    private fun setPictureInPictureReady(ready: Boolean) {
        if (pictureInPictureReady == ready) return
        pictureInPictureReady = ready
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !supportsPictureInPicture()) return
        runCatching {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setAutoEnterEnabled(ready)
                        setSeamlessResizeEnabled(true)
                    }
                }
                .build()
            setPictureInPictureParams(params)
        }.onFailure { DiagnosticsLog.throwable("PictureInPicture params failed", it) }
    }

    private fun enterPictureInPicture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !supportsPictureInPicture()) return
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build(),
            )
        }.onSuccess { entered ->
            DiagnosticsLog.event("PictureInPicture manual enter accepted=$entered")
        }.onFailure { DiagnosticsLog.throwable("PictureInPicture enter failed", it) }
    }

    private fun supportsPictureInPicture(): Boolean =
        packageManager.hasSystemFeature("android.software.picture_in_picture")

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
    onPictureInPictureReadyChanged: (Boolean) -> Unit,
    pendingRoute: String?,
    onRouteConsumed: () -> Unit,
) {
    val deviceProfile = rememberAppDeviceProfile()
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = Routes.tabRoute(backStack?.destination?.route)
    val showBottomBar = currentRoute in Routes.tabRoutes
    val menuLanguage by SettingsStore.menuLanguage.collectAsState()
    var resolverWebViewsReady by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    val chromeScope = rememberCoroutineScope()
    var restoreChromeJob by remember { mutableStateOf<Job?>(null) }
    val tvSearchRailFocusRequester = remember { FocusRequester() }
    val tvSearchFieldFocusRequester = remember { FocusRequester() }
    val tvHomePrimaryFocusRequester = remember { FocusRequester() }
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
                val showTvTopNavigation = deviceProfile.isTv &&
                    (showBottomBar || currentRoute == Routes.DETAIL)
                Box(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxSize()
                            .padding(
                                bottom = if (hasPhoneBottomBar) 0.dp else innerPadding.calculateBottomPadding(),
                            ),
                    ) {
                        if (showBottomBar && deviceProfile.useNavigationRail && !deviceProfile.isTv) {
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
                            onPictureInPictureReadyChanged = onPictureInPictureReadyChanged,
                            tvSearchFieldFocusRequester = tvSearchFieldFocusRequester,
                            tvHomePrimaryFocusRequester = tvHomePrimaryFocusRequester,
                            modifier = Modifier
                                .weight(1f)
                                .then(
                                    if (
                                        showTvTopNavigation &&
                                        currentRoute != Routes.HOME &&
                                        currentRoute != Routes.DETAIL
                                    ) {
                                        Modifier.padding(top = 82.dp)
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                    if (showTvTopNavigation) {
                        AppTvTopNavigation(
                            currentRoute = currentRoute,
                            menuLanguage = menuLanguage,
                            onNavigate = nav::navigateTab,
                            onNotificationsClick = {
                                nav.navigate(Routes.NOTIFICATIONS) { launchSingleTop = true }
                            },
                            searchNavFocusRequester = tvSearchRailFocusRequester,
                            searchFieldFocusRequester = tvSearchFieldFocusRequester,
                            homeContentFocusRequester = tvHomePrimaryFocusRequester,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
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
            // Account OAuth uses another full-screen WebView. Release the resident resolver
            // WebViews while Library/Profile is open so memory-starved TVs do not make AniList's
            // login renderer stall before its JavaScript app mounts.
            if (resolverWebViewsReady && currentRoute != Routes.MORE) {
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

private val tvPrimaryTabs = Tab.entries.filterNot { it == Tab.SETTINGS }
private val TvClockFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun AppTvTopNavigation(
    currentRoute: String?,
    menuLanguage: MenuLanguage,
    onNavigate: (String) -> Unit,
    onNotificationsClick: () -> Unit,
    searchNavFocusRequester: FocusRequester,
    searchFieldFocusRequester: FocusRequester,
    homeContentFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val focusRequesters = remember(searchNavFocusRequester) {
        Tab.entries.associateWith { tab ->
            if (tab == Tab.SEARCH) searchNavFocusRequester else FocusRequester()
        }
    }
    val unread by com.miruronative.data.reminder.NotificationCenter.unread.collectAsState()
    var clockText by remember { mutableStateOf(LocalTime.now().format(TvClockFormatter)) }

    LaunchedEffect(Unit) {
        while (true) {
            clockText = LocalTime.now().format(TvClockFormatter)
            delay(30_000)
        }
    }
    LaunchedEffect(currentRoute) {
        Tab.entries.firstOrNull { it.route == currentRoute }
            ?.let { tab -> runCatching { focusRequesters.getValue(tab).requestFocus() } }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(82.dp)
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to ComposeColor.Black.copy(.74f),
                    1f to ComposeColor.Transparent,
                ),
            )
            .padding(horizontal = 34.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.anilili_launcher),
                contentDescription = null,
                modifier = Modifier.size(42.dp).clip(CircleShape),
            )
            Text(
                text = stringResource(R.string.app_name).uppercase(),
                color = ComposeColor.White,
                fontWeight = FontWeight.Black,
                fontSize = 16.sp,
                letterSpacing = 1.5.sp,
                modifier = Modifier.padding(start = 10.dp),
            )
        }

        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tvPrimaryTabs.forEach { tab ->
                TvTopNavigationItem(
                    tab = tab,
                    label = tab.label(menuLanguage),
                    selected = currentRoute == tab.route ||
                        (currentRoute == Routes.DETAIL && tab == Tab.HOME),
                    onClick = { onNavigate(tab.route) },
                    focusRequester = focusRequesters.getValue(tab),
                    searchFieldFocusRequester = searchFieldFocusRequester,
                    homeContentFocusRequester = homeContentFocusRequester,
                )
            }
        }

        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BadgedBox(
                badge = {
                    if (unread > 0) {
                        Badge { Text(if (unread > 99) "99+" else unread.toString()) }
                    }
                },
            ) {
                IconButton(
                    onClick = onNotificationsClick,
                    modifier = Modifier.focusHighlight(CircleShape, focusedScale = 1.08f),
                ) {
                    Icon(
                        Icons.Default.Notifications,
                        contentDescription = if (unread > 0) "Notifications, $unread unread" else "Notifications",
                        tint = ComposeColor.White.copy(.78f),
                    )
                }
            }
            IconButton(
                onClick = { onNavigate(Tab.SETTINGS.route) },
                modifier = Modifier
                    .focusRequester(focusRequesters.getValue(Tab.SETTINGS))
                    .background(
                        if (currentRoute == Tab.SETTINGS.route) {
                            MaterialTheme.colorScheme.primary.copy(.24f)
                        } else {
                            ComposeColor.Transparent
                        },
                        CircleShape,
                    )
                    .focusHighlight(CircleShape, focusedScale = 1.08f),
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = Tab.SETTINGS.label(menuLanguage),
                    tint = if (currentRoute == Tab.SETTINGS.route) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        ComposeColor.White.copy(.78f)
                    },
                )
            }
            Spacer(Modifier.width(3.dp))
            Text(
                clockText,
                color = ComposeColor.White.copy(.72f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun TvTopNavigationItem(
    tab: Tab,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    searchFieldFocusRequester: FocusRequester,
    homeContentFocusRequester: FocusRequester,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val container = when {
        focused -> ComposeColor.White
        selected -> MaterialTheme.colorScheme.primary.copy(.24f)
        else -> ComposeColor.Transparent
    }
    val content = when {
        focused -> ComposeColor.Black
        selected -> ComposeColor.White
        else -> ComposeColor.White.copy(.68f)
    }

    Row(
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusProperties {
                if (selected) {
                    when (tab) {
                        Tab.HOME -> down = homeContentFocusRequester
                        Tab.SEARCH -> down = searchFieldFocusRequester
                        else -> Unit
                    }
                }
            }
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(container)
            .border(
                width = if (focused) 0.dp else if (selected) 1.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.primary.copy(.5f) else ComposeColor.Transparent,
                shape = shape,
            )
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(tab.icon, contentDescription = null, tint = content, modifier = Modifier.size(17.dp))
        Text(label, color = content, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
                stringResource(R.string.app_name),
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
    onPictureInPictureReadyChanged: (Boolean) -> Unit,
    tvSearchFieldFocusRequester: FocusRequester,
    tvHomePrimaryFocusRequester: FocusRequester,
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
                    tvPrimaryFocusRequester = tvHomePrimaryFocusRequester,
                )
            }
            composable(Routes.NOTIFICATIONS) {
                LaunchedEffect(Unit) { DiagnosticsLog.event("Route NOTIFICATIONS content entered") }
                NotificationsScreen(
                    onBack = { nav.popBackStack() },
                    onAnimeClick = { id -> nav.navigate(Routes.detail(id)) },
                )
            }
            composable(
                route = Routes.SEARCH_DESTINATION,
                arguments = listOf(
                    navArgument(Routes.Arg.STUDIO_ID) {
                        type = NavType.IntType
                        defaultValue = -1
                    },
                    navArgument(Routes.Arg.STUDIO_NAME) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                LaunchedEffect(Unit) { DiagnosticsLog.event("Route SEARCH content entered") }
                SearchScreen(
                    onAnimeClick = { id -> nav.navigate(Routes.detail(id)) },
                    tvFieldFocusRequester = tvSearchFieldFocusRequester,
                    initialStudioId = entry.arguments?.getInt(Routes.Arg.STUDIO_ID)?.takeIf { it > 0 },
                    initialStudioName = entry.arguments?.getString(Routes.Arg.STUDIO_NAME),
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
                    onPlayDownload = { downloadId ->
                        nav.navigate(Routes.download(downloadId))
                    },
                )
            }
            composable(Routes.SETTINGS) {
                LaunchedEffect(Unit) { DiagnosticsLog.event("Route SETTINGS content entered") }
                SettingsScreen()
            }

            composable(
                route = Routes.DOWNLOAD,
                arguments = listOf(navArgument(Routes.Arg.DOWNLOAD_ID) { type = NavType.StringType }),
            ) { entry ->
                val downloadId = entry.arguments?.getString(Routes.Arg.DOWNLOAD_ID)
                    ?: return@composable
                LaunchedEffect(downloadId) {
                    DiagnosticsLog.event("Route DOWNLOAD content entered id=$downloadId")
                }
                DownloadedEpisodeScreen(
                    downloadId = downloadId,
                    onBack = { nav.popBackStack() },
                )
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
                    onStudioClick = { studio ->
                        val name = studio.name
                        if (studio.id > 0 && !name.isNullOrBlank()) {
                            nav.navigate(Routes.studioSearch(studio.id, name)) { launchSingleTop = true }
                        }
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
                    onPictureInPictureReadyChanged = onPictureInPictureReadyChanged,
                    onBack = { nav.popBackStack() },
                )
            }
        }
}

private fun NavController.navigateTab(route: String) {
    val restoreTabState = Routes.shouldRestoreTabState(route)
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = restoreTabState }
        launchSingleTop = true
        restoreState = restoreTabState
    }
}
