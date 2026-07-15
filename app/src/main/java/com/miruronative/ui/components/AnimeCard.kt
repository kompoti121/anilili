package com.miruronative.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.miruronative.data.model.Media
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight

/** Dense poster card shared by Home, Browse, and AniList library rows. */
@Composable
fun AnimeCard(
    media: Media,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = LocalAppDeviceProfile.current
    val titleStyle = MaterialTheme.typography.labelLarge
    val titleMaxLines = if (device.isTv) 3 else 1
    val tvTitleHeight = with(LocalDensity.current) {
        titleStyle.lineHeight.toDp() * titleMaxLines
    }
    Column(
        modifier = modifier
            .focusHighlight()
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = media.coverImage.best,
                contentDescription = media.title.preferred,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            media.averageScore?.let { score ->
                RatingBadge(score, Modifier.align(Alignment.TopStart).padding(5.dp))
            }
            if (media.isAdult) {
                AdultBadge(Modifier.align(Alignment.TopEnd).padding(5.dp))
            }
        }
        Text(
            text = media.title.preferred,
            style = titleStyle,
            maxLines = titleMaxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = 5.dp)
                .then(if (device.isTv) Modifier.height(tvTitleHeight) else Modifier),
        )
        Text(
            text = listOfNotNull(
                media.format?.replace('_', ' '),
                media.seasonYear?.toString(),
                media.episodes?.let { "$it EP" },
            ).joinToString("  ·  "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AdultBadge(modifier: Modifier = Modifier) {
    Text(
        "18+",
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.error)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onError,
        fontWeight = FontWeight.Bold,
    )
}

/** Small, high-contrast score treatment shared by every media card presentation. */
@Composable
fun RatingBadge(score: Int, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = .78f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(12.dp).padding(end = 2.dp),
        )
        Text(
            "$score%",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

val GridContentPadding = PaddingValues(16.dp)
