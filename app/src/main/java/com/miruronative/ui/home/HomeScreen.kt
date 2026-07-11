package com.miruronative.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.miruronative.R
import com.miruronative.data.library.HistoryEntry
import com.miruronative.data.library.LibraryStore
import com.miruronative.data.model.Media
import com.miruronative.ui.UiState
import com.miruronative.ui.components.ErrorBox
import com.miruronative.ui.components.LoadingBox
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAnimeClick: (Int) -> Unit,
    onWatchNow: (Int) -> Unit,
    onResume: (HistoryEntry) -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
    vm: HomeViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val history by LibraryStore.history.collectAsState()
    val device = LocalAppDeviceProfile.current
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
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
                    IconButton(
                        onClick = onSearchClick,
                        modifier = Modifier.focusHighlight(CircleShape),
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Search anime")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        when (val s = state) {
            is UiState.Loading -> LoadingBox(Modifier.padding(padding))
            is UiState.Error -> ErrorBox(s.message, vm::load, Modifier.padding(padding))
            is UiState.Success -> HomeContent(
                data = s.data,
                history = history,
                onAnimeClick = onAnimeClick,
                onWatchNow = onWatchNow,
                onResume = onResume,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun HomeContent(
    data: HomeData,
    history: List<HistoryEntry>,
    onAnimeClick: (Int) -> Unit,
    onWatchNow: (Int) -> Unit,
    onResume: (HistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { HeroPager(data.spotlight.take(6), onAnimeClick, onWatchNow) }
        if (history.isNotEmpty()) {
            item { ContinueRail(history.take(12), onResume) }
        }
        item { MediaRail("Trending Now", data.spotlight, onAnimeClick) }
        item { MediaRail("New Episodes", data.newest, onAnimeClick) }
        item { MediaRail("Popular This Season", data.popular, onAnimeClick) }
        item { MediaRail("Critically Acclaimed", data.topRated, onAnimeClick) }
    }
}

@Composable
private fun HeroPager(items: List<Media>, onAnimeClick: (Int) -> Unit, onWatchNow: (Int) -> Unit) {
    if (items.isEmpty()) return
    val device = LocalAppDeviceProfile.current
    val pagerState = rememberPagerState(pageCount = { items.size })
    val heroHeight = when {
        device.isTv -> 420.dp
        device.isExpanded -> 400.dp
        device.isTablet -> 370.dp
        else -> 340.dp
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = if (device.isTv) device.pagePadding else 0.dp)
            .height(heroHeight)
            .clip(if (device.isTv) RoundedCornerShape(18.dp) else RoundedCornerShape(0.dp)),
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            HeroCard(items[page], onAnimeClick, onWatchNow)
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(items.size) { i ->
                Box(
                    Modifier
                        .height(5.dp)
                        .width(if (i == pagerState.currentPage) 18.dp else 5.dp)
                        .clip(CircleShape)
                        .background(if (i == pagerState.currentPage) MaterialTheme.colorScheme.primary else Color.White.copy(.4f)),
                )
            }
        }
    }
}

@Composable
private fun HeroCard(media: Media, onAnimeClick: (Int) -> Unit, onWatchNow: (Int) -> Unit) {
    val device = LocalAppDeviceProfile.current
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = media.bannerImage ?: media.coverImage.best,
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
                    modifier = Modifier.focusHighlight(RoundedCornerShape(24.dp)),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text("Play", Modifier.padding(start = 4.dp), fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { onAnimeClick(media.id) },
                    modifier = Modifier.focusHighlight(RoundedCornerShape(24.dp)),
                ) {
                    Icon(Icons.Default.Info, contentDescription = null)
                    Text("Details", Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun MediaRail(title: String, media: List<Media>, onAnimeClick: (Int) -> Unit) {
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
                Column(
                    Modifier
                        .width(device.posterWidth)
                        .focusHighlight()
                        .clickable { onAnimeClick(item.id) },
                ) {
                    AsyncImage(
                        model = item.coverImage.best,
                        contentDescription = item.title.preferred,
                        modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Text(item.title.preferred, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                    Text(item.format.orEmpty().replace('_', ' '), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ContinueRail(history: List<HistoryEntry>, onResume: (HistoryEntry) -> Unit) {
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
            items(history, key = { it.anilistId }) { entry ->
                Column(
                    Modifier
                        .width(cardWidth)
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
