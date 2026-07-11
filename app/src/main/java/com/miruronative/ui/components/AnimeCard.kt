package com.miruronative.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.miruronative.data.model.Media
import com.miruronative.ui.adaptive.focusHighlight

/** Poster card used across grids and horizontal rows. Fixed 2:3 poster + two text lines. */
@Composable
fun AnimeCard(
    media: Media,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .focusHighlight()
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = media.coverImage.best,
                contentDescription = media.title.preferred,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            media.averageScore?.let { score ->
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "$score%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Text(
            text = media.title.preferred,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = listOfNotNull(media.format, media.seasonYear?.toString()).joinToString(" • "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Convenience content-padding for the app's grids. */
val GridContentPadding = PaddingValues(16.dp)
