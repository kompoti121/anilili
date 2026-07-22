package com.miruronative.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.miruronative.data.library.HistoryEntry
import com.miruronative.data.library.LibraryStore
import com.miruronative.data.library.WatchlistEntry
import com.miruronative.data.model.EpisodeItem
import com.miruronative.data.model.FuzzyDate
import com.miruronative.data.model.Media
import com.miruronative.data.settings.EpisodeLayout
import com.miruronative.data.settings.SettingsStore
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.ui.UiState
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight
import com.miruronative.ui.components.EPISODE_BROWSER_MIN_EPISODES
import com.miruronative.ui.components.EpisodeBrowserBar
import com.miruronative.ui.components.EpisodeNumberChip
import com.miruronative.ui.components.ErrorBox
import com.miruronative.ui.components.LoadingBox
import com.miruronative.ui.components.blockIndexContaining
import com.miruronative.ui.components.episodeBlocks
import com.miruronative.ui.components.filterEpisodes
import com.miruronative.ui.components.PullRefreshContainer
import com.miruronative.ui.components.ScrollAwareTopBar
import com.miruronative.ui.components.WatchProgressBar
import com.miruronative.ui.components.episodeWatchFraction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.android.awaitFrame

private enum class DetailTab(val label: String) {
    HOME("Home"),
    EPISODES("Episodes"),
    RELATED("Related"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun DetailScreen(
    animeId: Int,
    onBack: () -> Unit,
    onPlay: (animeId: Int, provider: String, category: String, episode: String) -> Unit,
    onAnimeClick: (Int) -> Unit,
    onSeasonWatch: (Int) -> Unit,
    vm: DetailViewModel = viewModel(),
) {
    LaunchedEffect(animeId) { vm.load(animeId) }
    val state by vm.state.collectAsState()
    val isRefreshing by vm.isRefreshing.collectAsState()
    val watchlist by LibraryStore.watchlist.collectAsState()
    val history by LibraryStore.history.collectAsState()
    val backFocusRequester = remember { FocusRequester() }
    val primaryActionFocusRequester = remember { FocusRequester() }
    val detailActionsReady = state is UiState.Success
    val device = LocalAppDeviceProfile.current
    val inputModeManager = LocalInputModeManager.current
    var backFocusEnabled by remember(animeId, device.isTv) { mutableStateOf(!device.isTv) }

    // Mouse navigation leaves Compose in Touch mode. Keep the top-left Back button out of the
    // initial TV focus search, switch to Keyboard mode, and request the primary action once its
    // lazy row exists. Back becomes focusable again as soon as an action actually receives focus.
    LaunchedEffect(detailActionsReady, device.isTv) {
        if (device.isTv && detailActionsReady) {
            val keyboardMode = inputModeManager.requestInputMode(InputMode.Keyboard)
            awaitFrame()
            runCatching { primaryActionFocusRequester.requestFocus() }
            DiagnosticsLog.event("Detail primary action focus requested keyboardMode=$keyboardMode")
        }
    }

    Scaffold(
        topBar = {
            ScrollAwareTopBar {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .focusRequester(backFocusRequester)
                                .focusProperties {
                                    if (device.isTv && !backFocusEnabled) canFocus = false
                                    if (detailActionsReady) down = primaryActionFocusRequester
                                }
                                .focusHighlight(RoundedCornerShape(24.dp)),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
    ) { padding ->
        when (val s = state) {
            is UiState.Loading -> LoadingBox(Modifier.padding(padding))
            is UiState.Error -> ErrorBox(
                message = s.message,
                onRetry = { vm.load(animeId, force = true) },
                modifier = Modifier.padding(padding),
            )
            is UiState.Success -> {
                val info = s.data.info
                val saved = watchlist.any { it.anilistId == info.id }
                PullRefreshContainer(
                    isRefreshing = isRefreshing,
                    onRefresh = { vm.refresh(animeId) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    DetailContent(
                        contentPadding = padding,
                        data = s.data,
                        saved = saved,
                        resume = history.firstOrNull { it.anilistId == animeId },
                        history = history,
                        onToggleSaved = {
                            LibraryStore.toggleWatchlist(
                                WatchlistEntry(
                                    anilistId = info.id,
                                    title = info.title.preferred,
                                    cover = info.coverImage.best,
                                    format = info.format,
                                    averageScore = info.averageScore,
                                ),
                            )
                        },
                        onPlay = onPlay,
                        onAnimeClick = onAnimeClick,
                        onSeasonWatch = onSeasonWatch,
                        onSelectSeason = vm::selectSeason,
                        backFocusRequester = backFocusRequester,
                        primaryActionFocusRequester = primaryActionFocusRequester,
                        onPrimaryFocusAcquired = { backFocusEnabled = true },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailContent(
    data: DetailData,
    saved: Boolean,
    resume: HistoryEntry?,
    history: List<HistoryEntry> = emptyList(),
    onToggleSaved: () -> Unit,
    onPlay: (Int, String, String, String) -> Unit,
    onAnimeClick: (Int) -> Unit,
    onSeasonWatch: (Int) -> Unit,
    onSelectSeason: (Int) -> Unit,
    backFocusRequester: FocusRequester,
    primaryActionFocusRequester: FocusRequester,
    onPrimaryFocusAcquired: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val info = data.info
    val device = LocalAppDeviceProfile.current
    val episodes = data.episodes
    var selectedTab by remember(info.id) { mutableStateOf(DetailTab.HOME) }
    val canWatch = episodes.isNotEmpty()
    val primaryActionIsWatch = canWatch || resume != null
    val playCurrent: () -> Unit = {
        when {
            resume != null -> onPlay(info.id, resume.provider, resume.category, resume.episodeLabel)
            canWatch -> onPlay(
                data.selectedSeasonId,
                "auto",
                data.preferredCategory.api,
                episodes.first().displayNumber,
            )
        }
    }
    val playEpisode: (EpisodeItem) -> Unit = { episode ->
        onPlay(data.selectedSeasonId, "auto", data.preferredCategory.api, episode.displayNumber)
    }

    // Episode browsing. Query and range reset per season; a null range means "not chosen yet",
    // which is what lets the resumed episode's block be the one that opens.
    val episodeLayout by SettingsStore.episodeLayout.collectAsState()
    var episodeQuery by remember(data.selectedSeasonId) { mutableStateOf("") }
    var chosenBlockIndex by remember(data.selectedSeasonId) { mutableStateOf<Int?>(null) }
    val blocks = remember(episodes) { episodeBlocks(episodes) }
    val browserVisible = selectedTab == DetailTab.EPISODES && episodes.size > EPISODE_BROWSER_MIN_EPISODES
    val browserFocus = remember { FocusRequester() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Scroll padding, not layout padding, so rows travel under the bar as it retreats.
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 30.dp,
        ),
    ) {
        item { DetailHero(info) }
        item {
            DetailActions(
                saved = saved,
                canWatch = primaryActionIsWatch,
                resolving = false,
                resume = resume,
                onToggleSaved = onToggleSaved,
                onWatch = playCurrent,
                backFocusRequester = backFocusRequester,
                primaryActionFocusRequester = primaryActionFocusRequester,
                onPrimaryFocusAcquired = onPrimaryFocusAcquired,
            )
        }
        resume?.let { entry ->
            item {
                SeriesWatchProgress(
                    resume = entry,
                    totalEpisodes = maxOf(episodes.size, info.episodes ?: 0),
                )
            }
        }
        stickyHeader {
            DetailTabs(
                selected = selectedTab,
                onSelect = { selectedTab = it },
                downFocus = browserFocus.takeIf { browserVisible },
            )
        }

        when (selectedTab) {
            DetailTab.HOME -> {
                item { QuickFacts(info) }
                info.nextAiringEpisode?.airingAt?.let { airingAt ->
                    item { NextAiringCard(airingAt, info.nextAiringEpisode.episode) }
                }
                if (info.genres.isNotEmpty()) item { GenreRow(info.genres) }
                item { Description(info.description) }
                item { MetadataCard(info) }
            }

            DetailTab.EPISODES -> {
                val seasons = data.seasons
                if (seasons.size > 1) {
                    item {
                        SeasonFilterRow(
                            seasons = seasons,
                            selectedSeasonId = data.selectedSeasonId,
                            onSelect = onSelectSeason,
                        )
                    }
                }
                val seasonCover = seasons.firstOrNull { it.id == data.selectedSeasonId }
                    ?.let { it.bannerImage ?: it.coverImage.best }
                val seasonResume = history.firstOrNull { it.anilistId == data.selectedSeasonId }
                // Until the viewer picks a range, open on the one holding their resume point —
                // otherwise a thousand-episode series always drops them back at episode 1.
                val blockIndex = (chosenBlockIndex ?: blockIndexContaining(blocks, seasonResume?.episodeNumber))
                    .coerceIn(0, (blocks.size - 1).coerceAtLeast(0))
                // A search spans the whole season; only an unfiltered list is bounded by the block.
                val filtering = episodeQuery.isNotBlank()
                val shown = if (filtering) {
                    filterEpisodes(episodes, episodeQuery)
                } else {
                    blocks.getOrNull(blockIndex)?.episodes.orEmpty()
                }
                if (browserVisible) {
                    item {
                        EpisodeBrowserBar(
                            blocks = blocks,
                            selectedBlockIndex = blockIndex,
                            onSelectBlock = { chosenBlockIndex = it },
                            query = episodeQuery,
                            onQueryChange = { episodeQuery = it },
                            layout = episodeLayout,
                            onToggleLayout = { SettingsStore.setEpisodeLayout(episodeLayout.toggled()) },
                            focusRequester = browserFocus,
                            modifier = Modifier.padding(horizontal = device.pagePadding, vertical = 6.dp),
                        )
                    }
                }
                // Only the first row needs redirecting; the rest reach the bar through it.
                val firstItemUp: Modifier = if (browserVisible) {
                    Modifier.focusProperties { up = browserFocus }
                } else {
                    Modifier
                }
                when {
                    shown.isNotEmpty() && episodeLayout == EpisodeLayout.GRID -> itemsIndexed(
                        items = shown.chunked(device.episodeColumns),
                        key = { _, row -> row.first().pipeId },
                    ) { rowIndex, row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = device.pagePadding, vertical = 4.dp)
                                .then(if (rowIndex == 0) firstItemUp else Modifier),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { episode ->
                                EpisodeNumberChip(
                                    episode = episode,
                                    selected = false,
                                    watchedFraction = episodeWatchFraction(seasonResume, episode.number),
                                    modifier = Modifier.weight(1f),
                                    onClick = { playEpisode(episode) },
                                )
                            }
                            repeat(device.episodeColumns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    shown.isNotEmpty() -> itemsIndexed(
                        items = shown,
                        key = { _, episode -> episode.pipeId },
                    ) { index, episode ->
                        DetailEpisodeRow(
                            episode = episode,
                            fallbackImage = seasonCover ?: info.bannerImage ?: info.coverImage.best,
                            watchedFraction = episodeWatchFraction(seasonResume, episode.number),
                            onClick = { playEpisode(episode) },
                            modifier = if (index == 0) firstItemUp else Modifier,
                        )
                    }
                    filtering -> item {
                        InlineStatus("No episode matches “$episodeQuery”.", loading = false)
                    }
                    data.seasonEpisodesLoading -> item { InlineStatus("Loading episodes…", loading = true) }
                    else -> item { InlineStatus("No episode information is available yet.", loading = false) }
                }
            }

            DetailTab.RELATED -> {
                val related = data.series.filter { it.id != info.id }
                when {
                    related.isNotEmpty() -> items(related, key = Media::id) { media ->
                        RelatedRow(
                            media = media,
                            onOpen = { onAnimeClick(media.id) },
                            onWatch = { onSeasonWatch(media.id) },
                        )
                    }
                    data.seriesLoading -> item { InlineStatus("Finding related titles…", loading = true) }
                    else -> item { InlineStatus("No related titles found.", loading = false) }
                }
            }
        }
        item { Spacer(Modifier.height(device.pagePadding)) }
    }
}

@Composable
private fun DetailHero(info: Media) {
    val device = LocalAppDeviceProfile.current
    val banner = info.bannerImage ?: info.coverImage.best
    Box(Modifier.fillMaxWidth().height(if (device.isExpanded) 330.dp else 286.dp)) {
        AsyncImage(
            model = banner,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().height(if (device.isExpanded) 230.dp else 190.dp),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (device.isExpanded) 230.dp else 190.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = device.pagePadding, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            AsyncImage(
                model = info.coverImage.best,
                contentDescription = info.title.preferred,
                modifier = Modifier
                    .width(if (device.isExpanded) 126.dp else 98.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
                Text(
                    text = info.title.preferred,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                info.studios.nodes.firstOrNull { it.isAnimationStudio }?.name?.let { studio ->
                    Text(
                        text = studio,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Text(
                    text = listOfNotNull(
                        info.averageScore?.let { "$it% score" },
                        info.episodes?.let { "$it episodes" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun DetailActions(
    saved: Boolean,
    canWatch: Boolean,
    resolving: Boolean,
    resume: HistoryEntry?,
    onToggleSaved: () -> Unit,
    onWatch: () -> Unit,
    backFocusRequester: FocusRequester,
    primaryActionFocusRequester: FocusRequester,
    onPrimaryFocusAcquired: () -> Unit,
) {
    val pad = LocalAppDeviceProfile.current.pagePadding
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = pad, end = pad, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(
            onClick = onToggleSaved,
            modifier = Modifier
                .weight(1f)
                .then(
                    if (!canWatch) Modifier.focusRequester(primaryActionFocusRequester)
                    else Modifier,
                )
                .onFocusChanged { state ->
                    if (state.isFocused || state.hasFocus) onPrimaryFocusAcquired()
                }
                .focusProperties { up = backFocusRequester }
                .focusHighlight(RoundedCornerShape(24.dp)),
        ) {
            Icon(
                imageVector = if (saved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(if (saved) "In library" else "Add to list", Modifier.padding(start = 6.dp))
        }
        Button(
            onClick = onWatch,
            enabled = canWatch,
            modifier = Modifier
                .weight(1f)
                .then(
                    if (canWatch) Modifier.focusRequester(primaryActionFocusRequester)
                    else Modifier,
                )
                .onFocusChanged { state ->
                    if (state.isFocused || state.hasFocus) onPrimaryFocusAcquired()
                }
                .focusProperties { up = backFocusRequester }
                .focusHighlight(RoundedCornerShape(24.dp)),
        ) {
            if (resolving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(17.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(Icons.Default.PlayArrow, contentDescription = null, Modifier.size(19.dp))
            }
            Text(
                text = if (resume != null) "Continue ${resume.episodeLabel}" else "Watch",
                modifier = Modifier.padding(start = 5.dp),
                maxLines = 1,
            )
        }
    }
}

/** Series-level progress like list apps show: how far through the whole anime the user is. */
@Composable
private fun SeriesWatchProgress(resume: HistoryEntry, totalEpisodes: Int) {
    if (totalEpisodes <= 0) return
    val watched = ((resume.episodeNumber - 1).coerceAtLeast(0.0) + resume.progressFraction).toFloat()
    val fraction = (watched / totalEpisodes).coerceIn(0f, 1f)
    val pad = LocalAppDeviceProfile.current.pagePadding
    Column(Modifier.fillMaxWidth().padding(start = pad, end = pad, bottom = 12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Episode ${resume.episodeLabel} of $totalEpisodes",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${(fraction * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        WatchProgressBar(
            fraction = fraction,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

@Composable
private fun DetailTabs(
    selected: DetailTab,
    onSelect: (DetailTab) -> Unit,
    // The episode browser below is invisible to 2D focus search, so the way down is named.
    downFocus: FocusRequester? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .horizontalScroll(rememberScrollState())
            .then(downFocus?.let { target -> Modifier.focusProperties { down = target } } ?: Modifier)
            .padding(horizontal = LocalAppDeviceProfile.current.pagePadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DetailTab.entries.forEach { tab ->
            val active = tab == selected
            Text(
                text = tab.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (active) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .focusHighlight(RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface,
                    )
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun QuickFacts(info: Media) {
    val facts = listOfNotNull(
        info.status?.pretty()?.let { it to "Status" },
        info.format?.pretty()?.let { it to "Format" },
        info.seasonYear?.toString()?.let { it to "Year" },
        info.duration?.let { "$it min" to "Duration" },
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = LocalAppDeviceProfile.current.pagePadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(facts) { (value, label) ->
            Column(
                modifier = Modifier
                    .width(112.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun NextAiringCard(airingAt: Long, episode: Int?) {
    val date = Instant.ofEpochSecond(airingAt)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a"))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalAppDeviceProfile.current.pagePadding, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(start = 10.dp)) {
            Text("Next episode${episode?.let { " $it" } ?: ""}", style = MaterialTheme.typography.labelLarge)
            Text(date, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GenreRow(genres: List<String>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = LocalAppDeviceProfile.current.pagePadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        genres.forEach { genre ->
            Text(
                text = genre,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 11.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Description(description: String?) {
    if (description.isNullOrBlank()) return
    var expanded by remember { mutableStateOf(false) }
    val clean = remember(description) { description.replace(Regex("<[^>]*>"), "").trim() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalAppDeviceProfile.current.pagePadding, vertical = 8.dp)
            .clickable { expanded = !expanded },
    ) {
        Text("Overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            text = clean,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp),
        )
        Text(
            text = if (expanded) "Show less" else "More",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

@Composable
private fun MetadataCard(info: Media) {
    val rows = listOfNotNull(
        info.startDate.display()?.let { "Start date" to it },
        info.endDate.display()?.let { "End date" to it },
        info.season?.pretty()?.let { "Season" to it },
        info.popularity?.let { "Popularity" to "#,${it}".replace("#,", "#") },
        info.favourites?.let { "Favorites" to it.toString() },
    )
    if (rows.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalAppDeviceProfile.current.pagePadding, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(value, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Season chips for series with multiple episodic entries (JJK-style): the whole prequel/sequel
 * chain lives on one page and this row swaps which season's episodes are listed below.
 */
@Composable
private fun SeasonFilterRow(
    seasons: List<Media>,
    selectedSeasonId: Int,
    onSelect: (Int) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(
            horizontal = LocalAppDeviceProfile.current.pagePadding,
            vertical = 8.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(seasons.size) { index ->
            val season = seasons[index]
            val active = season.id == selectedSeasonId
            Column(
                modifier = Modifier
                    .focusHighlight(RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface,
                    )
                    .border(
                        1.dp,
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(12.dp),
                    )
                    .clickable { onSelect(season.id) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Season ${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                )
                (season.seasonYear ?: season.startDate?.year)?.let { year ->
                    Text(
                        text = year.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailEpisodeRow(
    episode: EpisodeItem,
    fallbackImage: String?,
    onClick: () -> Unit,
    watchedFraction: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val selectedImage = episode.image ?: fallbackImage
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LocalAppDeviceProfile.current.pagePadding, vertical = 6.dp)
            .focusHighlight(RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The width belongs on the box, not the image: the progress bar below fills its parent,
        // and an unconstrained box would take the whole row from the title beside it.
        Box(Modifier.width(132.dp)) {
            AsyncImage(
                model = selectedImage,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop,
            )
            Text(
                text = "EP ${episode.displayNumber}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(if (watchedFraction > 0.01f) Alignment.TopEnd else Alignment.BottomEnd)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
            WatchProgressBar(
                fraction = watchedFraction,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 5.dp),
            )
        }
        Column(Modifier.weight(1f).padding(start = 13.dp)) {
            val title = episode.distinctTitle
            Text(
                text = title ?: "Episode ${episode.displayNumber}",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (title != null) {
                Text(
                    "Episode ${episode.displayNumber}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun RelatedRow(media: Media, onOpen: () -> Unit, onWatch: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalAppDeviceProfile.current.pagePadding, vertical = 6.dp)
            .focusHighlight(RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = media.coverImage.best,
            contentDescription = media.title.preferred,
            modifier = Modifier.width(58.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(media.title.preferred, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(media.format?.pretty(), media.seasonYear?.toString()).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        IconButton(onClick = onWatch, modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp))) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Watch ${media.title.preferred}")
        }
    }
}

@Composable
private fun InlineStatus(message: String, loading: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (loading) com.miruronative.ui.components.NoFaceLoadingIndicator(size = 48.dp)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun String.pretty(): String = lowercase()
    .replace('_', ' ')
    .replaceFirstChar { it.uppercase() }

private fun FuzzyDate?.display(): String? {
    this ?: return null
    val values = listOfNotNull(month?.toString(), day?.toString(), year?.toString())
    return values.takeIf { it.isNotEmpty() }?.joinToString("/")
}
