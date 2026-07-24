package com.miruronative.ui.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
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
import com.miruronative.data.model.EpisodeItem
import com.miruronative.data.model.Media
import com.miruronative.data.model.StudioNode
import com.miruronative.ui.adaptive.focusHighlight
import com.miruronative.ui.components.WatchProgressBar
import com.miruronative.ui.components.episodeWatchFraction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val TvDetailPadding = 48.dp
private val TvDetailCardShape = RoundedCornerShape(12.dp)
private val TvEpisodeCardWidth = 270.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvDetailContent(
    data: DetailData,
    saved: Boolean,
    listStatusLabel: String?,
    resume: HistoryEntry?,
    history: List<HistoryEntry>,
    onToggleSaved: () -> Unit,
    onPlay: (animeId: Int, provider: String, category: String, episode: String) -> Unit,
    onAnimeClick: (Int) -> Unit,
    onStudioClick: (StudioNode) -> Unit,
    onSelectSeason: (Int) -> Unit,
    primaryActionFocusRequester: FocusRequester,
    onPrimaryFocusAcquired: () -> Unit,
) {
    val info = data.info
    val episodes = data.episodes
    val playCurrent: () -> Unit = {
        when {
            resume != null -> onPlay(info.id, resume.provider, resume.category, resume.episodeLabel)
            episodes.isNotEmpty() -> onPlay(
                data.selectedSeasonId,
                "auto",
                data.preferredCategory.api,
                episodes.first().displayNumber,
            )
        }
    }
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

    CompositionLocalProvider(LocalBringIntoViewSpec provides edgeBringIntoViewSpec) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentPadding = PaddingValues(bottom = 42.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(key = "detail-hero") {
                TvDetailHero(
                    info = info,
                    resume = resume,
                    saved = saved,
                    listStatusLabel = listStatusLabel,
                    canWatch = episodes.isNotEmpty() || resume != null,
                    onWatch = playCurrent,
                    onToggleSaved = onToggleSaved,
                    onStudioClick = onStudioClick,
                    primaryActionFocusRequester = primaryActionFocusRequester,
                    onPrimaryFocusAcquired = onPrimaryFocusAcquired,
                )
            }

            if (data.seasons.size > 1) {
                item(key = "seasons") {
                    TvSeasonRail(
                        seasons = data.seasons,
                        selectedSeasonId = data.selectedSeasonId,
                        onSelect = onSelectSeason,
                    )
                }
            }

            item(key = "episodes") {
                TvEpisodeRail(
                    episodes = episodes,
                    fallbackImage = data.seasons.firstOrNull { it.id == data.selectedSeasonId }
                        ?.let { it.bannerImage ?: it.coverImage.best }
                        ?: info.bannerImage
                        ?: info.coverImage.best,
                    resume = history.firstOrNull { it.anilistId == data.selectedSeasonId },
                    loading = data.seasonEpisodesLoading,
                    onPlay = { episode ->
                        onPlay(
                            data.selectedSeasonId,
                            "auto",
                            data.preferredCategory.api,
                            episode.displayNumber,
                        )
                    },
                )
            }

            item(key = "overview") { TvOverview(info) }

            val related = data.series.filter { it.id != info.id }
            if (related.isNotEmpty()) {
                item(key = "related") {
                    TvRelatedRail(related = related, onAnimeClick = onAnimeClick)
                }
            }
        }
    }
}

@Composable
private fun TvDetailHero(
    info: Media,
    resume: HistoryEntry?,
    saved: Boolean,
    listStatusLabel: String?,
    canWatch: Boolean,
    onWatch: () -> Unit,
    onToggleSaved: () -> Unit,
    onStudioClick: (StudioNode) -> Unit,
    primaryActionFocusRequester: FocusRequester,
    onPrimaryFocusAcquired: () -> Unit,
) {
    val image = info.bannerImage ?: info.coverImage.extraLarge ?: info.coverImage.best
    val description = remember(info.description) {
        info.description
            ?.replace(Regex("<[^>]*>"), "")
            ?.replace("&amp;", "&")
            ?.replace("&quot;", "\"")
            ?.trim()
            .orEmpty()
    }
    val studio = info.studios.nodes.firstOrNull { it.isAnimationStudio && !it.name.isNullOrBlank() }

    Box(Modifier.fillMaxWidth().height(305.dp)) {
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
                    .44f to Color.Black.copy(.92f),
                    .74f to Color.Black.copy(.30f),
                    1f to Color.Black.copy(.04f),
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(.20f),
                    .60f to Color.Transparent,
                    1f to Color.Black,
                ),
            ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(545.dp)
                .padding(start = TvDetailPadding, top = 55.dp),
        ) {
            Text(
                info.title.preferred,
                color = Color.White,
                fontSize = 36.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TvDetailMetadata(info)
            if (description.isNotBlank()) {
                Text(
                    description,
                    color = Color.White.copy(.74f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 7.dp),
                )
            }
            Row(
                modifier = Modifier.padding(top = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onWatch,
                    enabled = canWatch,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                    ),
                    contentPadding = PaddingValues(horizontal = 19.dp, vertical = 9.dp),
                    modifier = Modifier
                        .focusRequester(primaryActionFocusRequester)
                        .onFocusChanged {
                            if (it.isFocused || it.hasFocus) onPrimaryFocusAcquired()
                        }
                        .focusHighlight(RoundedCornerShape(12.dp), focusedScale = 1.04f),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(21.dp))
                    Text(
                        resume?.let { "Continue E${it.episodeLabel}" } ?: "Watch",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 7.dp),
                    )
                }
                OutlinedButton(
                    onClick = onToggleSaved,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Black.copy(.42f),
                        contentColor = Color.White,
                    ),
                    contentPadding = PaddingValues(horizontal = 17.dp, vertical = 9.dp),
                    modifier = Modifier.focusHighlight(RoundedCornerShape(12.dp), focusedScale = 1.04f),
                ) {
                    Icon(
                        if (saved || listStatusLabel != null) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        listStatusLabel ?: if (saved) "In library" else "Add to list",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 7.dp),
                    )
                }
                studio?.let {
                    Text(
                        it.name.orEmpty(),
                        color = Color.White.copy(.68f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black.copy(.34f))
                            .clickable(enabled = it.id > 0) { onStudioClick(it) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TvDetailMetadata(info: Media) {
    val cells = buildList {
        info.averageScore?.takeIf { it > 0 }?.let { add("score" to "$it%") }
        info.format?.let { add("text" to tvDetailPretty(it)) }
        (info.seasonYear ?: info.startDate?.year)?.let { add("text" to it.toString()) }
        info.duration?.takeIf { it > 0 }?.let { add("text" to "${it}m") }
        info.genres.take(3).forEach { add("text" to it) }
    }
    Row(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
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
                Text(label, color = Color.White.copy(.74f), fontWeight = FontWeight.Medium, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun TvSeasonRail(
    seasons: List<Media>,
    selectedSeasonId: Int,
    onSelect: (Int) -> Unit,
) {
    Column {
        TvDetailSectionTitle("Seasons")
        LazyRow(
            modifier = Modifier.focusGroup(),
            contentPadding = PaddingValues(horizontal = TvDetailPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            items(seasons.size) { index ->
                val season = seasons[index]
                val active = season.id == selectedSeasonId
                Text(
                    text = buildString {
                        append("Season ${index + 1}")
                        (season.seasonYear ?: season.startDate?.year)?.let { append("  •  $it") }
                    },
                    color = if (active) Color.White else Color.White.copy(.62f),
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .focusHighlight(RoundedCornerShape(10.dp), focusedScale = 1.05f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primary.copy(.30f)
                            else MaterialTheme.colorScheme.surface,
                        )
                        .border(
                            1.dp,
                            if (active) MaterialTheme.colorScheme.primary.copy(.72f)
                            else Color.White.copy(.09f),
                            RoundedCornerShape(10.dp),
                        )
                        .clickable { onSelect(season.id) }
                        .padding(horizontal = 15.dp, vertical = 9.dp),
                )
            }
        }
    }
}

@Composable
private fun TvEpisodeRail(
    episodes: List<EpisodeItem>,
    fallbackImage: String?,
    resume: HistoryEntry?,
    loading: Boolean,
    onPlay: (EpisodeItem) -> Unit,
) {
    Column {
        TvDetailSectionTitle("Episodes")
        when {
            episodes.isNotEmpty() -> LazyRow(
                modifier = Modifier.focusGroup(),
                contentPadding = PaddingValues(horizontal = TvDetailPadding, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(episodes, key = { it.pipeId }) { episode ->
                    TvEpisodeCard(
                        episode = episode,
                        image = episode.image ?: fallbackImage,
                        watchedFraction = episodeWatchFraction(resume, episode.number),
                        onClick = { onPlay(episode) },
                    )
                }
            }
            loading -> Text(
                "Loading episodes…",
                color = Color.White.copy(.55f),
                modifier = Modifier.padding(horizontal = TvDetailPadding, vertical = 18.dp),
            )
            else -> Text(
                "Episode information is not available yet.",
                color = Color.White.copy(.55f),
                modifier = Modifier.padding(horizontal = TvDetailPadding, vertical = 18.dp),
            )
        }
    }
}

@Composable
private fun TvEpisodeCard(
    episode: EpisodeItem,
    image: String?,
    watchedFraction: Float,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.045f else 1f, label = "tv-episode-card-scale")

    Column(
        modifier = Modifier
            .width(TvEpisodeCardWidth)
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClickLabel = "Play episode ${episode.displayNumber}", onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(TvDetailCardShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    if (focused) 3.dp else 1.dp,
                    if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(.08f),
                    TvDetailCardShape,
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
                        0f to Color.Black.copy(.02f),
                        .50f to Color.Black.copy(.08f),
                        1f to Color.Black.copy(.86f),
                    ),
                ),
            )
            Text(
                "S1 • E${episode.displayNumber}",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(7.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.Black.copy(.70f))
                    .padding(horizontal = 7.dp, vertical = 4.dp),
            )
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp),
            ) {
                Text(
                    episode.distinctTitle ?: "Episode ${episode.displayNumber}",
                    color = Color.White,
                    fontSize = 14.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                WatchProgressBar(
                    fraction = watchedFraction,
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun TvOverview(info: Media) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = TvDetailPadding),
    ) {
        TvDetailSectionTitle("About", includePadding = false)
        val facts = buildList {
            info.status?.let { add(tvDetailPretty(it)) }
            info.episodes?.let { add("$it episodes") }
            info.season?.let { add(tvDetailPretty(it)) }
        }.joinToString("  •  ")
        if (facts.isNotBlank()) {
            Text(
                facts,
                color = Color.White.copy(.72f),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        info.nextAiringEpisode?.airingAt?.let { airingAt ->
            val date = Instant.ofEpochSecond(airingAt)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a"))
            Row(
                modifier = Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "Next episode${info.nextAiringEpisode.episode?.let { " $it" }.orEmpty()}  •  $date",
                    color = Color.White.copy(.64f),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TvRelatedRail(
    related: List<Media>,
    onAnimeClick: (Int) -> Unit,
) {
    Column {
        TvDetailSectionTitle("Related")
        LazyRow(
            modifier = Modifier.focusGroup(),
            contentPadding = PaddingValues(horizontal = TvDetailPadding, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(related, key = { it.id }) { media ->
                var focused by remember { mutableStateOf(false) }
                val scale by animateFloatAsState(if (focused) 1.045f else 1f, label = "tv-related-scale")
                Column(
                    Modifier
                        .width(TvEpisodeCardWidth)
                        .zIndex(if (focused) 1f else 0f)
                        .scale(scale)
                        .onFocusChanged { focused = it.isFocused }
                        .clickable { onAnimeClick(media.id) },
                ) {
                    AsyncImage(
                        model = media.bannerImage ?: media.coverImage.best,
                        contentDescription = media.title.preferred,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(TvDetailCardShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                if (focused) 3.dp else 1.dp,
                                if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(.08f),
                                TvDetailCardShape,
                            ),
                        contentScale = ContentScale.Crop,
                    )
                    Text(
                        media.title.preferred,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TvDetailSectionTitle(
    title: String,
    includePadding: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (includePadding) Modifier.padding(horizontal = TvDetailPadding) else Modifier),
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

private fun tvDetailPretty(value: String): String =
    value.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
