package com.miruronative.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.miruronative.data.library.HistoryEntry
import com.miruronative.data.library.LibraryStore
import com.miruronative.data.model.Media
import com.miruronative.ui.adaptive.focusHighlight
import com.miruronative.ui.components.ContinueWatchingActionsDialog
import kotlinx.coroutines.delay
import kotlin.math.abs

private val TvPagePadding = 48.dp
private val TvRailCardWidth = 222.dp
private val TvCardShape = RoundedCornerShape(12.dp)
private const val TV_HERO_AUTO_ADVANCE_MS = 7_000L

/**
 * Cinematic 10-foot Home surface. This deliberately lives beside the existing responsive Home
 * implementation so phone and tablet layouts remain unchanged.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvHomeContent(
    data: HomeData,
    history: List<HistoryEntry>,
    onAnimeClick: (Int) -> Unit,
    onWatchNow: (Int) -> Unit,
    onResume: (HistoryEntry) -> Unit,
    primaryActionFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val spotlight = data.spotlight.take(7)
    var spotlightIndex by remember(spotlight.map(Media::id)) { mutableIntStateOf(0) }
    var heroHasFocus by remember { mutableStateOf(false) }
    var railPreview by remember(spotlight.map(Media::id)) { mutableStateOf<Media?>(null) }
    val spotlightHero = spotlight.getOrNull(spotlightIndex.coerceIn(0, (spotlight.size - 1).coerceAtLeast(0)))
    val hero = railPreview ?: spotlightHero
    val edgeBringIntoViewSpec = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val trailingEdge = offset + size
                return when {
                    offset >= 0f && trailingEdge <= containerSize -> 0f
                    offset < 0f && trailingEdge > containerSize -> 0f
                    abs(offset) < abs(trailingEdge - containerSize) -> offset
                    else -> trailingEdge - containerSize
                }
            }
        }
    }

    LaunchedEffect(spotlight.map(Media::id), spotlightIndex, heroHasFocus, railPreview) {
        if (spotlight.size <= 1 || heroHasFocus || railPreview != null) return@LaunchedEffect
        delay(TV_HERO_AUTO_ADVANCE_MS)
        spotlightIndex = nextHeroPage(spotlightIndex, spotlight.size)
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides edgeBringIntoViewSpec) {
        LazyColumn(
            modifier = modifier.fillMaxSize().background(Color.Black),
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (hero != null) {
                item(key = "tv-hero") {
                    TvHero(
                        media = hero,
                        pageIndex = spotlightIndex,
                        pageCount = spotlight.size,
                        onWatchNow = onWatchNow,
                        onAnimeClick = onAnimeClick,
                        onFocusChanged = { heroHasFocus = it },
                        primaryActionFocusRequester = primaryActionFocusRequester,
                    )
                }
            }
            if (history.isNotEmpty()) {
                item(key = "continue-watching") {
                    TvContinueRail(
                        history = history.take(12),
                        onResume = onResume,
                    )
                }
            }
            if (data.spotlight.isNotEmpty()) {
                item(key = "trending") {
                    TvMediaRail(
                        title = "Trending now",
                        media = data.spotlight,
                        onAnimeClick = onAnimeClick,
                        onPreview = { railPreview = it },
                    )
                }
            }
            if (data.newest.isNotEmpty()) {
                item(key = "newest") {
                    TvMediaRail(
                        title = "Latest releases",
                        media = data.newest,
                        onAnimeClick = onAnimeClick,
                        onPreview = { railPreview = it },
                    )
                }
            }
            if (data.popular.isNotEmpty()) {
                item(key = "popular") {
                    TvMediaRail(
                        title = "Popular this season",
                        media = data.popular,
                        onAnimeClick = onAnimeClick,
                        onPreview = { railPreview = it },
                    )
                }
            }
            if (data.movies.isNotEmpty()) {
                item(key = "movies") {
                    TvMediaRail(
                        title = "Movies",
                        media = data.movies,
                        onAnimeClick = onAnimeClick,
                        onPreview = { railPreview = it },
                    )
                }
            }
            if (data.topRated.isNotEmpty()) {
                item(key = "top-rated") {
                    TvMediaRail(
                        title = "Top rated",
                        media = data.topRated,
                        onAnimeClick = onAnimeClick,
                        onPreview = { railPreview = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun TvHero(
    media: Media,
    pageIndex: Int,
    pageCount: Int,
    onWatchNow: (Int) -> Unit,
    onAnimeClick: (Int) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    primaryActionFocusRequester: FocusRequester?,
) {
    val resume = LibraryStore.historyFor(media.id)
    val description = remember(media.description) {
        media.description
            ?.replace(Regex("<[^>]*>"), "")
            ?.replace("&amp;", "&")
            ?.replace("&quot;", "\"")
            ?.trim()
            .orEmpty()
    }
    val image = media.bannerImage ?: media.coverImage.extraLarge ?: media.coverImage.best

    Box(Modifier.fillMaxWidth().height(390.dp)) {
        AsyncImage(
            model = image,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.CenterEnd,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Black,
                    .43f to Color.Black.copy(.91f),
                    .72f to Color.Black.copy(.30f),
                    1f to Color.Black.copy(.04f),
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(.24f),
                    .58f to Color.Transparent,
                    1f to Color.Black,
                ),
            ),
        )
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .width(410.dp)
                .height(150.dp)
                .background(
                    Brush.radialGradient(
                        listOf(MaterialTheme.colorScheme.primary.copy(.13f), Color.Transparent),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(520.dp)
                .padding(start = TvPagePadding, top = 58.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Text(
                    "ANILILI FEATURED",
                    color = Color.White.copy(.78f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 1.2.sp,
                )
            }
            Text(
                text = media.title.preferred,
                color = Color.White,
                fontSize = 38.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            TvHeroMetadata(media)
            if (description.isNotBlank()) {
                Text(
                    description,
                    color = Color.White.copy(.78f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(475.dp).padding(top = 8.dp),
                )
            }
            Row(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { onWatchNow(media.id) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                    ),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    modifier = Modifier
                        .then(
                            primaryActionFocusRequester?.let { Modifier.focusRequester(it) }
                                ?: Modifier,
                        )
                        .onFocusChanged { onFocusChanged(it.hasFocus) }
                        .focusHighlight(RoundedCornerShape(12.dp), focusedScale = 1.04f),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(21.dp))
                    Text(
                        text = resume?.let { "Continue E${it.episodeLabel}" } ?: "Watch now",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 7.dp),
                    )
                }
                OutlinedButton(
                    onClick = { onAnimeClick(media.id) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Black.copy(.44f),
                        contentColor = Color.White,
                    ),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                    modifier = Modifier
                        .onFocusChanged { onFocusChanged(it.hasFocus) }
                        .focusHighlight(RoundedCornerShape(12.dp), focusedScale = 1.04f),
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(19.dp))
                    Text("More info", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 7.dp))
                }
            }
        }

        if (pageCount > 1) {
            Row(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = TvPagePadding, bottom = 30.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(pageCount) { index ->
                    Box(
                        Modifier
                            .height(4.dp)
                            .width(if (index == pageIndex) 20.dp else 5.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == pageIndex) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.White.copy(.32f)
                                },
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun TvHeroMetadata(media: Media) {
    val cells = buildList {
        media.averageScore?.takeIf { it > 0 }?.let { add("score" to "$it%") }
        media.format?.let { add("text" to tvFormatLabel(it)) }
        (media.seasonYear ?: media.startDate?.year)?.let { add("text" to it.toString()) }
        media.duration?.takeIf { it > 0 }?.let { add("text" to "${it}m") }
        media.genres.take(2).forEach { add("text" to it) }
    }
    Row(
        modifier = Modifier.padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        cells.forEachIndexed { index, (kind, label) ->
            if (index > 0) {
                Box(Modifier.size(3.dp).clip(CircleShape).background(Color.White.copy(.38f)))
            }
            if (kind == "score") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        label,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 3.dp),
                    )
                }
            } else {
                Text(label, color = Color.White.copy(.76f), fontWeight = FontWeight.Medium, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun TvMediaRail(
    title: String,
    media: List<Media>,
    onAnimeClick: (Int) -> Unit,
    onPreview: (Media?) -> Unit,
) {
    Column {
        TvRailTitle(title)
        LazyRow(
            modifier = Modifier.focusGroup(),
            contentPadding = PaddingValues(horizontal = TvPagePadding, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(media.take(20), key = { it.id }) { item ->
                TvMediaCard(
                    media = item,
                    onClick = { onAnimeClick(item.id) },
                    onFocused = { focused -> if (focused) onPreview(item) },
                )
            }
        }
    }
}

@Composable
private fun TvMediaCard(
    media: Media,
    onClick: () -> Unit,
    onFocused: (Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.045f else 1f, label = "tv-media-card-scale")
    val image = media.bannerImage ?: media.coverImage.extraLarge ?: media.coverImage.best

    Column(
        modifier = Modifier
            .width(TvRailCardWidth)
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .onFocusChanged {
                focused = it.isFocused
                onFocused(it.isFocused)
            }
            .clickable(onClickLabel = "Open ${media.title.preferred}", onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(TvCardShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = if (focused) 3.dp else 1.dp,
                    color = if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(.08f),
                    shape = TvCardShape,
                ),
        ) {
            AsyncImage(
                model = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(.34f),
                    ),
                ),
            )
            media.averageScore?.takeIf { it > 0 }?.let { score ->
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(7.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(.72f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        score.toString(),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 3.dp),
                    )
                }
            }
        }
        Text(
            media.title.preferred,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            buildList {
                media.format?.let { add(tvFormatLabel(it)) }
                (media.seasonYear ?: media.startDate?.year)?.let { add(it.toString()) }
            }.joinToString("  •  ").ifBlank { media.genres.firstOrNull().orEmpty() },
            color = Color.White.copy(.52f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvContinueRail(
    history: List<HistoryEntry>,
    onResume: (HistoryEntry) -> Unit,
) {
    var managedEntry by remember { mutableStateOf<HistoryEntry?>(null) }
    managedEntry?.let { entry ->
        ContinueWatchingActionsDialog(entry = entry, onDismiss = { managedEntry = null })
    }

    Column {
        TvRailTitle("Continue watching")
        LazyRow(
            modifier = Modifier.focusGroup(),
            contentPadding = PaddingValues(horizontal = TvPagePadding, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            itemsIndexed(history, key = { _, item -> item.anilistId }) { _, entry ->
                TvContinueCard(
                    entry = entry,
                    onClick = { onResume(entry) },
                    onLongClick = { managedEntry = entry },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvContinueCard(
    entry: HistoryEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.045f else 1f, label = "tv-continue-card-scale")

    Column(
        modifier = Modifier
            .width(TvRailCardWidth)
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                onClickLabel = "Resume ${entry.title}",
                onLongClickLabel = "Manage Continue Watching",
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(TvCardShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = if (focused) 3.dp else 1.dp,
                    color = if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(.08f),
                    shape = TvCardShape,
                ),
        ) {
            AsyncImage(
                model = entry.cover,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(.24f)))
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(.74f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text("UP NEXT", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black)
            }
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(32.dp),
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.White.copy(.24f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(entry.progressFraction.coerceAtLeast(.03f))
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Text(
            entry.title,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "Episode ${entry.episodeLabel}" +
                entry.episodeTitle?.takeIf { it.isNotBlank() }?.let { "  •  $it" }.orEmpty(),
            color = Color.White.copy(.52f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun TvRailTitle(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = TvPagePadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .height(2.dp)
                .width(30.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(.7f)),
        )
    }
}

private fun tvFormatLabel(format: String): String = when (format) {
    "TV" -> "TV"
    "TV_SHORT" -> "TV Short"
    "MOVIE" -> "Movie"
    "SPECIAL" -> "Special"
    "OVA" -> "OVA"
    "ONA" -> "ONA"
    "MUSIC" -> "Music"
    else -> format.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}
