package com.miruronative.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import coil.compose.AsyncImage
import com.miruronative.data.ProviderCatalog
import com.miruronative.data.library.LibraryStore
import com.miruronative.data.library.WatchlistEntry
import com.miruronative.data.model.Category
import com.miruronative.data.model.EpisodeItem
import com.miruronative.data.model.Media
import com.miruronative.ui.UiState
import com.miruronative.ui.components.ErrorBox
import com.miruronative.ui.components.LoadingBox
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    animeId: Int,
    onBack: () -> Unit,
    onPlay: (provider: String, category: String, episode: String) -> Unit,
    vm: DetailViewModel = viewModel(),
) {
    LaunchedEffect(animeId) { vm.load(animeId) }
    val state by vm.state.collectAsState()
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
                                    WatchlistEntry(info.id, info.title.preferred, info.coverImage.best, info.format),
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
            is UiState.Success -> DetailContent(
                data = s.data,
                selectedProvider = vm.selectedProvider,
                selectedCategory = vm.selectedCategory,
                onSelectProvider = vm::selectProvider,
                onSelectCategory = vm::selectCategory,
                onPlay = onPlay,
                resume = history.firstOrNull { it.anilistId == animeId },
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailContent(
    data: DetailData,
    selectedProvider: String?,
    selectedCategory: Category,
    onSelectProvider: (String) -> Unit,
    onSelectCategory: (Category) -> Unit,
    onPlay: (String, String, String) -> Unit,
    resume: com.miruronative.data.library.HistoryEntry?,
    modifier: Modifier = Modifier,
) {
    val device = LocalAppDeviceProfile.current
    val info = data.info
    val provider = selectedProvider?.let { data.episodes.provider(it) }
    val episodes = provider?.episodes(selectedCategory).orEmpty()
    val episodeRows = remember(episodes, device.episodeColumns) {
        episodes.chunked(device.episodeColumns)
    }
    val visibleProviders = remember(data.episodes.providerNames, data.loadingMore) {
        val pending = if (data.loadingMore) ProviderCatalog.anivexaProviders else emptyList()
        (data.episodes.providerNames + pending)
            .distinct()
            .sortedBy(ProviderCatalog::sortKey)
    }
    val pendingProviders = remember(visibleProviders, data.episodes.providerNames) {
        visibleProviders.toSet() - data.episodes.providerNames.toSet()
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item { Header(info) }
        item { GenreRow(info.genres) }
        item { Description(info.description) }

        if (resume != null) {
            item {
                Button(
                    onClick = { onPlay(resume.provider, resume.category, resume.episodeLabel) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = device.pagePadding, vertical = 8.dp)
                        .focusHighlight(RoundedCornerShape(24.dp)),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text("Continue Episode ${resume.episodeLabel}", Modifier.padding(start = 6.dp))
                }
            }
        }

        if (visibleProviders.isNotEmpty()) {
            item {
                SelectorSection(
                    providers = visibleProviders,
                    pendingProviders = pendingProviders,
                    selectedProvider = selectedProvider,
                    categories = provider?.categories.orEmpty(),
                    selectedCategory = selectedCategory,
                    onSelectProvider = onSelectProvider,
                    onSelectCategory = onSelectCategory,
                )
            }
            if (selectedProvider != null && episodes.isNotEmpty()) {
                item {
                    Button(
                        onClick = { onPlay(selectedProvider, selectedCategory.api, episodes.first().displayNumber) },
                        modifier = Modifier
                            .padding(horizontal = device.pagePadding, vertical = 4.dp)
                            .focusHighlight(RoundedCornerShape(24.dp)),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text("Play Episode ${episodes.first().displayNumber}", Modifier.padding(start = 6.dp))
                    }
                }
                item {
                    Text(
                        "Episodes",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = device.pagePadding, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(episodeRows) { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = device.pagePadding, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { ep ->
                            EpisodeChip(
                                episode = ep,
                                modifier = Modifier.weight(1f),
                                onClick = { onPlay(selectedProvider, selectedCategory.api, ep.displayNumber) },
                            )
                        }
                        repeat(device.episodeColumns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        } else {
            item {
                Text(
                    text = data.episodesError ?: "No streaming sources found for this title.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(device.pagePadding),
                )
            }
        }
        if (data.loadingMore) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Checking more servers…", Modifier.padding(start = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Header(info: Media) {
    val device = LocalAppDeviceProfile.current
    val bannerHeight = when {
        device.isTv -> 260.dp
        device.isExpanded -> 230.dp
        device.isTablet -> 200.dp
        else -> 170.dp
    }
    val coverWidth = when {
        device.isTv -> 156.dp
        device.isExpanded -> 140.dp
        device.isTablet -> 124.dp
        else -> 110.dp
    }
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = if (device.isTv) device.pagePadding else 0.dp)
                .height(bannerHeight)
                .clip(if (device.isTv) RoundedCornerShape(18.dp) else RoundedCornerShape(0.dp)),
        ) {
            AsyncImage(
                model = info.bannerImage ?: info.coverImage.best,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to MaterialTheme.colorScheme.background,
                    ),
                ),
            )
        }
        Row(Modifier.padding(device.pagePadding)) {
            AsyncImage(
                model = info.coverImage.best,
                contentDescription = info.title.preferred,
                modifier = Modifier
                    .width(coverWidth)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(info.title.preferred, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
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
}

@Composable
private fun GenreRow(genres: List<String>) {
    if (genres.isEmpty()) return
    val device = LocalAppDeviceProfile.current
    Text(
        text = genres.joinToString(" • "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = device.pagePadding),
    )
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
            .padding(device.pagePadding)
            .focusHighlight(RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SelectorSection(
    providers: List<String>,
    pendingProviders: Set<String>,
    selectedProvider: String?,
    categories: List<Category>,
    selectedCategory: Category,
    onSelectProvider: (String) -> Unit,
    onSelectCategory: (Category) -> Unit,
) {
    val device = LocalAppDeviceProfile.current
    Column(Modifier.padding(horizontal = device.pagePadding)) {
        Text("Server", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            providers.forEach { name ->
                val pending = name in pendingProviders
                FilterChip(
                    selected = name == selectedProvider,
                    enabled = !pending,
                    onClick = { onSelectProvider(name) },
                    modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                    label = {
                        val suffix = if (ProviderCatalog.isEmbed(name)) " ⧉" else ""
                        Text(ProviderCatalog.label(name) + suffix)
                    },
                    leadingIcon = if (pending) {
                        {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.5.dp,
                            )
                        }
                    } else null,
                )
            }
        }
        if (categories.size > 1 || categories.firstOrNull() == Category.DUB) {
            Text("Audio", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { cat ->
                    FilterChip(
                        selected = cat == selectedCategory,
                        onClick = { onSelectCategory(cat) },
                        modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                        label = { Text(cat.api.uppercase()) },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeChip(episode: EpisodeItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg = if (episode.filler) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    Box(
        modifier = modifier
            .focusHighlight(RoundedCornerShape(8.dp))
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(episode.displayNumber, style = MaterialTheme.typography.labelLarge)
    }
}
