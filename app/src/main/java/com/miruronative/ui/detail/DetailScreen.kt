package com.miruronative.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import coil.compose.AsyncImage
import com.miruronative.data.library.HistoryEntry
import com.miruronative.data.library.LibraryStore
import com.miruronative.data.library.WatchlistEntry
import com.miruronative.data.model.Category
import com.miruronative.data.model.Media
import com.miruronative.ui.UiState
import com.miruronative.ui.components.ErrorBox
import com.miruronative.ui.components.LoadingBox
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight
import com.miruronative.ui.components.AnimeCard
import com.miruronative.ui.components.PullRefreshContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    animeId: Int,
    onBack: () -> Unit,
    onPlay: (provider: String, category: String, episode: String) -> Unit,
    onAnimeClick: (Int) -> Unit,
    onSeasonWatch: (Int) -> Unit,
    vm: DetailViewModel = viewModel(),
) {
    LaunchedEffect(animeId) { vm.load(animeId) }
    val state by vm.state.collectAsState()
    val isRefreshing by vm.isRefreshing.collectAsState()
    val watchlist by LibraryStore.watchlist.collectAsState()
    val history by LibraryStore.history.collectAsState()
    val info = (state as? UiState.Success)?.data?.info

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.focusHighlight(RoundedCornerShape(24.dp)),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (info != null) {
                        val saved = watchlist.any { it.anilistId == info.id }
                        IconButton(
                            onClick = {
                                LibraryStore.toggleWatchlist(
                                    WatchlistEntry(
                                        info.id,
                                        info.title.preferred,
                                        info.coverImage.best,
                                        info.format,
                                        info.averageScore,
                                    ),
                                )
                            },
                            modifier = Modifier.focusHighlight(RoundedCornerShape(24.dp)),
                        ) {
                            Icon(
                                if (saved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = "Watchlist",
                                tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        when (val s = state) {
            is UiState.Loading -> LoadingBox(Modifier.padding(padding))
            is UiState.Error -> ErrorBox(s.message, { vm.load(animeId, force = true) }, Modifier.padding(padding))
            is UiState.Success -> PullRefreshContainer(
                isRefreshing = isRefreshing,
                onRefresh = { vm.refresh(animeId) },
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                DetailContent(
                    data = s.data,
                    selectedProvider = vm.selectedProvider,
                    selectedCategory = vm.selectedCategory,
                    onPlay = onPlay,
                    onAnimeClick = onAnimeClick,
                    onSeasonWatch = onSeasonWatch,
                    resume = history.firstOrNull { it.anilistId == animeId },
                )
            }
        }
    }
}

@Composable
private fun DetailContent(
    data: DetailData,
    selectedProvider: String?,
    selectedCategory: Category,
    onPlay: (String, String, String) -> Unit,
    onAnimeClick: (Int) -> Unit,
    onSeasonWatch: (Int) -> Unit,
    resume: HistoryEntry?,
    modifier: Modifier = Modifier,
) {
    val info = data.info
    val provider = selectedProvider?.let { data.episodes.provider(it) }
    val episodes = provider?.episodes(selectedCategory).orEmpty()
    val canWatch = selectedProvider != null && episodes.isNotEmpty()

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item { Header(info) }
        item { GenreRow(info.genres) }
        item {
            WatchButton(
                resume = resume,
                canWatch = canWatch,
                resolving = !canWatch && resume == null && data.episodesError == null,
                onWatch = {
                    when {
                        resume != null -> onPlay(resume.provider, resume.category, resume.episodeLabel)
                        canWatch -> onPlay(selectedProvider!!, selectedCategory.api, episodes.first().displayNumber)
                    }
                },
            )
        }
        item { Description(info.description) }
        if (data.seriesLoading || data.series.size > 1) {
            item {
                SeriesSection(
                    series = data.series,
                    currentId = info.id,
                    loading = data.seriesLoading,
                    onAnimeClick = onAnimeClick,
                    onSeasonWatch = onSeasonWatch,
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * The anime page's single call to action. Episode selection and source switching live on the
 * watch screen, so this just enters playback — resuming if there's history, else the first episode.
 */
@Composable
private fun WatchButton(
    resume: HistoryEntry?,
    canWatch: Boolean,
    resolving: Boolean,
    onWatch: () -> Unit,
) {
    val device = LocalAppDeviceProfile.current
    val enabled = resume != null || canWatch
    Button(
        onClick = onWatch,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = device.pagePadding, vertical = 8.dp)
            .focusHighlight(RoundedCornerShape(24.dp)),
    ) {
        if (resolving) {
            CircularProgressIndicator(
                Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Text("Finding sources…", Modifier.padding(start = 8.dp))
        } else {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Text(
                when {
                    resume != null -> "Continue Episode ${resume.episodeLabel}"
                    canWatch -> "Watch"
                    else -> "No sources available"
                },
                Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** TV formats are the numbered "seasons"; everything else (movies, ONAs, specials) is "Related". */
private val TV_SEASON_FORMATS = setOf("TV", "TV_SHORT")

@Composable
private fun SeriesSection(
    series: List<Media>,
    currentId: Int,
    loading: Boolean,
    onAnimeClick: (Int) -> Unit,
    onSeasonWatch: (Int) -> Unit,
) {
    val seasons = remember(series) { series.filter { it.format in TV_SEASON_FORMATS } }
    val related = remember(series) { series.filter { it.format !in TV_SEASON_FORMATS } }

    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        if (seasons.size > 1) {
            SectionHeader("Seasons", loading = loading)
            SeasonsDropdown(seasons = seasons, currentId = currentId, onSeasonWatch = onSeasonWatch)
        }
        if (related.isNotEmpty()) {
            RelatedSection(related = related, currentId = currentId, onAnimeClick = onAnimeClick)
        }
        // Still walking the prequel/sequel chain and nothing groupable yet.
        if (loading && seasons.size <= 1 && related.isEmpty()) {
            SectionHeader("Seasons", loading = true)
        }
    }
}

@Composable
private fun SectionHeader(title: String, loading: Boolean) {
    val device = LocalAppDeviceProfile.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = device.pagePadding, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun CurrentBadge(modifier: Modifier = Modifier) {
    Text(
        "Current",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/** Compact season picker — one dropdown instead of a row of per-season cards. */
@Composable
private fun SeasonsDropdown(
    seasons: List<Media>,
    currentId: Int,
    onSeasonWatch: (Int) -> Unit,
) {
    val device = LocalAppDeviceProfile.current
    var expanded by remember { mutableStateOf(false) }
    val currentIndex = seasons.indexOfFirst { it.id == currentId }.coerceAtLeast(0)

    Box(Modifier.fillMaxWidth().padding(horizontal = device.pagePadding, vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusHighlight(RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                .clickable { expanded = true }
                .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Season ${currentIndex + 1}",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = "Choose season",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            seasons.forEachIndexed { index, entry ->
                val selected = entry.id == currentId
                DropdownMenuItem(
                    text = {
                        Text(
                            "Season ${index + 1}",
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
                    },
                    onClick = {
                        expanded = false
                        if (!selected) onSeasonWatch(entry.id)
                    },
                    trailingIcon = if (selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                )
            }
        }
    }
}

/** Movies, ONAs, and specials — collapsed by default so the page stays focused on seasons. */
@Composable
private fun RelatedSection(
    related: List<Media>,
    currentId: Int,
    onAnimeClick: (Int) -> Unit,
) {
    val device = LocalAppDeviceProfile.current
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusHighlight(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = device.pagePadding, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Related",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "  ${related.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = device.pagePadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                items(related, key = Media::id) { entry ->
                    Box(Modifier.width(device.posterWidth)) {
                        AnimeCard(
                            media = entry,
                            onClick = { if (entry.id != currentId) onAnimeClick(entry.id) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (entry.id == currentId) {
                            CurrentBadge(Modifier.align(Alignment.TopEnd).padding(5.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(info: Media) {
    val device = LocalAppDeviceProfile.current
    val coverWidth = when {
        device.isTv -> 156.dp
        device.isExpanded -> 140.dp
        device.isTablet -> 124.dp
        else -> 110.dp
    }
    Row(Modifier.padding(device.pagePadding)) {
        AsyncImage(
            model = info.coverImage.best,
            contentDescription = info.title.preferred,
            modifier = Modifier
                .width(coverWidth)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(info.title.preferred, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            info.title.romaji?.takeIf { it != info.title.preferred }?.let { romaji ->
                Text(
                    romaji,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                listOfNotNull(info.format, info.seasonYear?.toString()).joinToString(" • "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            info.episodes?.let {
                Text("$it episodes", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            info.averageScore?.let {
                Text("★ $it%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            info.status?.let {
                Text(it.replace('_', ' '), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreRow(genres: List<String>) {
    if (genres.isEmpty()) return
    val device = LocalAppDeviceProfile.current
    FlowRow(
        modifier = Modifier.padding(horizontal = device.pagePadding, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        genres.forEach { genre ->
            Box(
                Modifier
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    genre,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun Description(description: String?) {
    if (description.isNullOrBlank()) return
    val device = LocalAppDeviceProfile.current
    var expanded by remember { mutableStateOf(false) }
    val clean = remember(description) { description.replace(Regex("<[^>]*>"), "").trim() }
    Text(
        text = clean,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(horizontal = device.pagePadding, vertical = 8.dp)
            .fillMaxWidth()
            .focusHighlight(RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(12.dp),
    )
}

