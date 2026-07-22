package com.miruronative.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.miruronative.R
import com.miruronative.data.library.HistoryEntry
import com.miruronative.data.library.LibraryStore
import com.miruronative.data.model.Media
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.ui.UiState
import com.miruronative.ui.components.ErrorBox
import com.miruronative.ui.components.LoadingBox
import com.miruronative.ui.components.AnimeCard
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight
import com.miruronative.ui.components.LocalAppChromeBottomInset
import com.miruronative.ui.components.PullRefreshContainer
import com.miruronative.ui.components.ScrollAwareTopBar
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAnimeClick: (Int) -> Unit,
    onWatchNow: (Int) -> Unit,
    onResume: (HistoryEntry) -> Unit,
    onSearchClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    modifier: Modifier = Modifier,
    vm: HomeViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val isRefreshing by vm.isRefreshing.collectAsState()
    val history by LibraryStore.history.collectAsState()
    val device = LocalAppDeviceProfile.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var slowStartup by remember { mutableStateOf(false) }
    var diagnosticsMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state) {
        slowStartup = false
        diagnosticsMessage = null
        if (state is UiState.Loading) {
            delay(10_000)
            slowStartup = true
            DiagnosticsLog.event("Home still loading after 10 seconds")
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            ScrollAwareTopBar { TopAppBar(
                title = {
                    if (!device.useNavigationRail) {
                        Text(
                            stringResource(R.string.app_name),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                        )
                    }
                },
                actions = {
                    val unread by com.miruronative.data.reminder.NotificationCenter.unread.collectAsState()
                    IconButton(
                        onClick = onNotificationsClick,
                        modifier = Modifier.focusHighlight(CircleShape),
                    ) {
                        androidx.compose.material3.BadgedBox(
                            badge = {
                                if (unread > 0) {
                                    androidx.compose.material3.Badge {
                                        Text(if (unread > 99) "99+" else unread.toString())
                                    }
                                }
                            },
                        ) {
                            Icon(
                                Icons.Default.Notifications,
                                contentDescription = if (unread > 0) "Notifications, $unread unread" else "Notifications",
                            )
                        }
                    }
                    IconButton(
                        onClick = onSearchClick,
                        modifier = Modifier.focusHighlight(CircleShape),
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Search anime")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            ) }
        },
    ) { padding ->
        when (val s = state) {
            is UiState.Loading -> {
                if (slowStartup) {
                    StartupStillLoading(
                        message = diagnosticsMessage,
                        onRetry = { vm.load(force = true) },
                        onShareDiagnostics = {
                            DiagnosticsLog.share(context)
                                .onFailure { diagnosticsMessage = it.message ?: "Couldn't share diagnostics" }
                        },
                        modifier = Modifier.padding(padding),
                    )
                } else {
                    LoadingBox(Modifier.padding(padding))
                }
            }
            is UiState.Error -> ErrorBox(s.message, vm::load, Modifier.padding(padding))
            // The grid fills the window and reserves the chrome as scroll padding, so rows pass
            // under the bars as they retreat instead of being shunted about by them.
            is UiState.Success -> PullRefreshContainer(
                isRefreshing = isRefreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                HomeContent(
                    data = s.data,
                    selectedTab = vm.selectedTab,
                    onSelectTab = vm::selectTab,
                    history = history,
                    onAnimeClick = onAnimeClick,
                    onWatchNow = onWatchNow,
                    onResume = onResume,
                    contentPadding = padding,
                )
            }
        }
    }
}

@Composable
private fun StartupStillLoading(
    message: String?,
    onRetry: () -> Unit,
    onShareDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            com.miruronative.ui.components.WaterFillLogoIndicator(size = 72.dp)
            Text(
                "Still loading",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "If the screen stays blank, share diagnostics from here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onRetry, modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp))) {
                    Text("Retry")
                }
                Button(onClick = onShareDiagnostics, modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp))) {
                    Text("Share diagnostics")
                }
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HomeContent(
    data: HomeData,
    selectedTab: HomeTab,
    onSelectTab: (HomeTab) -> Unit,
    history: List<HistoryEntry>,
    onAnimeClick: (Int) -> Unit,
    onWatchNow: (Int) -> Unit,
    onResume: (HistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val device = LocalAppDeviceProfile.current
    val chromeBottomInset = LocalAppChromeBottomInset.current
    val continueFocusRequester = remember { FocusRequester() }
    LaunchedEffect(data, history.size) {
        DiagnosticsLog.event(
            "HomeContent rendered spotlight=${data.spotlight.size} " +
                "history=${history.size} selectedTab=${selectedTab.name}",
        )
    }
    val catalog = data.tab(selectedTab).take(if (device.isTv) 28 else 18)
    val gridSpacing = if (device.isTv) 16.dp else 9.dp
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Handheld sizes every home poster card to a single width, derived from posterWidth, so
        // the catalog grid and the "Trending" rail share one universal card size. Columns are
        // fitted to the real content width (maxWidth already excludes any navigation rail).
        // TV keeps its fixed 7-column grid and posterWidth rail: fitting a 10-foot width to
        // posterWidth leaves only four columns and blows the cards up.
        val available = maxWidth - device.pagePadding * 2f
        val columns = if (device.isTv) {
            7
        } else {
            maxOf(
                1,
                ((available.value + gridSpacing.value) / (device.posterWidth.value + gridSpacing.value)).toInt(),
            )
        }
        val cardWidth = if (device.isTv) {
            device.posterWidth
        } else {
            (available - gridSpacing * (columns - 1).toFloat()) / columns.toFloat()
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // The navigation bar floats over the list, so the tail has to clear it by its own
            // height or the last row — Trending, usually — stays buried under the tabs.
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + chromeBottomInset + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                HeroPager(
                    items = data.spotlight.take(6),
                    onAnimeClick = onAnimeClick,
                    onWatchNow = onWatchNow,
                    // Reports whether focus actually landed on the rail. A miss (rail not
                    // composed yet) must not swallow the key, or Down dies inside the hero.
                    onMoveDown = if (history.isNotEmpty()) {
                        { runCatching { continueFocusRequester.requestFocus() }.isSuccess }
                    } else {
                        null
                    },
                )
            }
            if (history.isNotEmpty()) {
                item { ContinueRail(history.take(12), onResume, continueFocusRequester) }
            }
            item { HomeCatalogTabs(selectedTab, onSelectTab) }
            items(catalog.chunked(columns)) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = device.pagePadding),
                    horizontalArrangement = Arrangement.spacedBy(gridSpacing),
                ) {
                    row.forEach { media ->
                        AnimeCard(media, { onAnimeClick(media.id) }, Modifier.weight(1f))
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            item { MediaRail("Trending this week", data.spotlight, onAnimeClick, cardWidth) }
        }
    }
}

@Composable
private fun HomeCatalogTabs(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    val device = LocalAppDeviceProfile.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = device.pagePadding)
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HomeTab.entries.forEach { tab ->
            val active = tab == selected
            Box(
                Modifier
                    .weight(1f)
                    .focusHighlight(RoundedCornerShape(7.dp))
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = .24f) else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun HeroPager(
    items: List<Media>,
    onAnimeClick: (Int) -> Unit,
    onWatchNow: (Int) -> Unit,
    onMoveDown: (() -> Boolean)?,
) {
    if (items.isEmpty()) return
    val device = LocalAppDeviceProfile.current
    val pagerState = rememberPagerState(pageCount = { items.size })
    val heroIds = items.map(Media::id)
    var tvPage by remember(heroIds) { mutableIntStateOf(0) }
    val safeTvPage = tvPage.coerceIn(0, items.lastIndex)
    val focusRequesters = remember(heroIds) {
        List(items.size) { HeroFocusRequesters(FocusRequester(), FocusRequester()) }
    }
    val tvFocusRequesters = remember { HeroFocusRequesters(FocusRequester(), FocusRequester()) }
    val heroHeight = when {
        device.isTv -> 420.dp
        device.isExpanded -> 360.dp
        device.isTablet -> 320.dp
        else -> 270.dp
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = if (device.isTv) device.pagePadding else 0.dp)
            .height(heroHeight)
            .clip(if (device.isTv) RoundedCornerShape(18.dp) else RoundedCornerShape(0.dp)),
    ) {
        if (device.isTv) {
            val page = safeTvPage
            HeroCard(
                media = items[page],
                onAnimeClick = onAnimeClick,
                onWatchNow = onWatchNow,
                canGoPrevious = page > 0,
                canGoNext = page < items.lastIndex,
                navigationInProgress = false,
                playFocusRequester = tvFocusRequesters.play,
                detailsFocusRequester = tvFocusRequesters.details,
                onPrevious = { tvPage = (page - 1).coerceAtLeast(0) },
                onNext = { tvPage = (page + 1).coerceAtMost(items.lastIndex) },
                onMoveDown = onMoveDown,
            )
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                key = { page -> items[page].id },
            ) { page ->
                HeroCard(
                    media = items[page],
                    onAnimeClick = onAnimeClick,
                    onWatchNow = onWatchNow,
                    canGoPrevious = page > 0,
                    canGoNext = page < items.lastIndex,
                    navigationInProgress = false,
                    playFocusRequester = focusRequesters[page].play,
                    detailsFocusRequester = focusRequesters[page].details,
                    onPrevious = {},
                    onNext = {},
                    onMoveDown = onMoveDown,
                )
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(items.size) { i ->
                Box(
                    Modifier
                        .height(5.dp)
                        .width(if (i == if (device.isTv) safeTvPage else pagerState.currentPage) 18.dp else 5.dp)
                        .clip(CircleShape)
                        .background(
                            if (i == if (device.isTv) safeTvPage else pagerState.currentPage) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.White.copy(.4f)
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun HeroCard(
    media: Media,
    onAnimeClick: (Int) -> Unit,
    onWatchNow: (Int) -> Unit,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    navigationInProgress: Boolean,
    playFocusRequester: FocusRequester,
    detailsFocusRequester: FocusRequester,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onMoveDown: (() -> Boolean)?,
) {
    val device = LocalAppDeviceProfile.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val heroImage = media.bannerImage ?: media.coverImage.best
    val heroImageRequest = remember(heroImage) {
        ImageRequest.Builder(context)
            .data(heroImage)
            .crossfade(false)
            .build()
    }
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = heroImageRequest,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.05f to Color.Black.copy(.08f), .62f to Color.Black.copy(.35f), 1f to MaterialTheme.colorScheme.background),
            ),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(if (device.isExpanded) 0.65f else 1f)
                .padding(horizontal = device.pagePadding, vertical = 24.dp),
        ) {
            media.nextAiringEpisode?.episode?.let { Badge("NEW EPISODE $it SOON") }
            Text(
                media.title.preferred,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                listOfNotNull(media.seasonYear?.toString(), media.format, media.averageScore?.let { "$it% Match" }).joinToString("  •  "),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(.82f),
                modifier = Modifier.padding(top = 5.dp),
            )
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onWatchNow(media.id) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    modifier = Modifier
                        .focusRequester(playFocusRequester)
                        .onPreviewKeyEvent { event ->
                            if (device.isTv && event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionLeft -> {
                                        if (event.nativeKeyEvent.repeatCount > 0 || navigationInProgress) {
                                            true
                                        } else if (canGoPrevious) {
                                            onPrevious()
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    Key.DirectionDown -> {
                                        onMoveDown?.invoke() == true ||
                                            focusManager.moveFocus(FocusDirection.Down)
                                    }
                                    else -> false
                                }
                            } else {
                                false
                            }
                        }
                        .focusHighlight(RoundedCornerShape(24.dp)),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text("Play", Modifier.padding(start = 4.dp), fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { onAnimeClick(media.id) },
                    modifier = Modifier
                        .focusRequester(detailsFocusRequester)
                        .onPreviewKeyEvent { event ->
                            if (device.isTv && event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionRight -> {
                                        if (event.nativeKeyEvent.repeatCount > 0 || navigationInProgress) {
                                            true
                                        } else if (canGoNext) {
                                            onNext()
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    Key.DirectionDown -> {
                                        onMoveDown?.invoke() == true ||
                                            focusManager.moveFocus(FocusDirection.Down)
                                    }
                                    else -> false
                                }
                            } else {
                                false
                            }
                        }
                        .focusHighlight(RoundedCornerShape(24.dp)),
                ) {
                    Icon(Icons.Default.Info, contentDescription = null)
                    Text("Details", Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

private data class HeroFocusRequesters(
    val play: FocusRequester,
    val details: FocusRequester,
)

@Composable
private fun MediaRail(title: String, media: List<Media>, onAnimeClick: (Int) -> Unit, cardWidth: Dp) {
    val device = LocalAppDeviceProfile.current
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = device.pagePadding),
        )
        LazyRow(
            modifier = Modifier.focusGroup(),
            contentPadding = PaddingValues(horizontal = device.pagePadding, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (device.isTv) 18.dp else 10.dp),
        ) {
            items(media, key = { it.id }) { item ->
                AnimeCard(
                    media = item,
                    onClick = { onAnimeClick(item.id) },
                    modifier = Modifier.width(cardWidth),
                )
            }
        }
    }
}

@Composable
private fun ContinueRail(
    history: List<HistoryEntry>,
    onResume: (HistoryEntry) -> Unit,
    firstItemFocusRequester: FocusRequester,
) {
    val device = LocalAppDeviceProfile.current
    val cardWidth = when {
        device.isTv -> 240.dp
        device.isExpanded -> 220.dp
        device.isTablet -> 200.dp
        else -> 174.dp
    }
    Column {
        Text(
            "Continue Watching",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = device.pagePadding),
        )
        LazyRow(
            modifier = Modifier.focusGroup(),
            contentPadding = PaddingValues(horizontal = device.pagePadding, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (device.isTv) 18.dp else 10.dp),
        ) {
            itemsIndexed(history, key = { _, item -> item.anilistId }) { index, entry ->
                Column(
                    Modifier
                        .width(cardWidth)
                        .then(if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                        .focusHighlight()
                        .clickable { onResume(entry) },
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        AsyncImage(model = entry.cover, contentDescription = "Resume ${entry.title}", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(.3f)))
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.align(Alignment.Center))
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(.25f))) {
                            Box(Modifier.fillMaxWidth(entry.progressFraction.coerceAtLeast(.03f)).height(4.dp).background(MaterialTheme.colorScheme.primary))
                        }
                    }
                    Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                    Text("Episode ${entry.episodeLabel}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Box(Modifier.clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
    }
}
