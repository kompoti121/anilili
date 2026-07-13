package com.miruronative.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.miruronative.data.AppGraph
import com.miruronative.data.auth.AuthManager
import com.miruronative.data.model.AppNotification
import com.miruronative.ui.UiState
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight
import com.miruronative.ui.components.ErrorBox
import com.miruronative.ui.components.LoadingBox
import com.miruronative.ui.rethrowIfCancellation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NotificationsViewModel : ViewModel() {
    private val repo = AppGraph.repository

    private val _state = MutableStateFlow<UiState<List<AppNotification>>>(UiState.Loading)
    val state = _state.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount = _unreadCount.asStateFlow()

    init {
        load(markAllRead = false)
    }

    fun refresh() = load(markAllRead = false)

    fun markAllRead() = load(markAllRead = true, keepUnreadHighlight = true)

    private fun load(markAllRead: Boolean, keepUnreadHighlight: Boolean = false) {
        viewModelScope.launch {
            if (_state.value !is UiState.Success) _state.value = UiState.Loading
            try {
                val (items, unread) = repo.notifications(markAllRead)
                // After mark-all-read the highlight stays for this visit so the user still
                // sees what was new; the counter itself is cleared.
                _state.value = UiState.Success(items)
                _unreadCount.value = if (markAllRead) 0 else unread
                if (keepUnreadHighlight) _unreadCount.value = 0
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                _state.value = UiState.Error(e.message ?: "Couldn't load notifications")
            }
        }
    }
}

private enum class Tab(val label: String) {
    ALL("All"),
    AIRING("Airing"),
    SOCIAL("Social"),
    MEDIA("Media");

    fun matches(item: AppNotification): Boolean = when (this) {
        ALL -> true
        AIRING -> item.kind == AppNotification.Kind.AIRING
        SOCIAL -> item.kind == AppNotification.Kind.SOCIAL
        MEDIA -> item.kind == AppNotification.Kind.MEDIA
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    onAnimeClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    vm: NotificationsViewModel = viewModel(),
) {
    val device = LocalAppDeviceProfile.current
    val token by AuthManager.token.collectAsState()
    val state by vm.state.collectAsState()
    val unreadCount by vm.unreadCount.collectAsState()
    var tab by remember { mutableStateOf(Tab.ALL) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Notifications", fontWeight = FontWeight.Black) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.focusHighlight(RoundedCornerShape(24.dp)),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = vm::refresh,
                        modifier = Modifier.focusHighlight(RoundedCornerShape(24.dp)),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    TextButton(
                        onClick = vm::markAllRead,
                        enabled = unreadCount > 0,
                        modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                    ) {
                        Text("Mark all read")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (token == null) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Log in with AniList", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Notifications for new episodes and activity come from your AniList account.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                return@Box
            }

            when (val current = state) {
                is UiState.Loading -> LoadingBox()
                is UiState.Error -> ErrorBox(current.message, vm::refresh)
                is UiState.Success -> NotificationList(
                    items = current.data,
                    tab = tab,
                    unreadTotal = unreadCount,
                    onTab = { tab = it },
                    onAnimeClick = onAnimeClick,
                    pagePadding = device.pagePadding,
                )
            }
        }
    }
}

@Composable
private fun NotificationList(
    items: List<AppNotification>,
    tab: Tab,
    unreadTotal: Int,
    onTab: (Tab) -> Unit,
    onAnimeClick: (Int) -> Unit,
    pagePadding: androidx.compose.ui.unit.Dp,
) {
    val filtered = items.filter(tab::matches)
    val now = System.currentTimeMillis() / 1000
    val sections = filtered.groupBy { item ->
        val age = now - item.createdAt
        when {
            age < 7L * 24 * 3600 -> "THIS WEEK"
            age < 30L * 24 * 3600 -> "LAST 30 DAYS"
            else -> "OVER 1 MONTH AGO"
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = pagePadding, end = pagePadding, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                items(Tab.entries.size) { index ->
                    val entry = Tab.entries[index]
                    val count = items.count { it.unread && entry.matches(it) }
                    FilterChip(
                        selected = tab == entry,
                        onClick = { onTab(entry) },
                        label = { Text(if (unreadTotal > 0) "${entry.label}  $count" else entry.label) },
                        modifier = Modifier.focusHighlight(RoundedCornerShape(8.dp)),
                    )
                }
            }
        }
        if (filtered.isEmpty()) {
            item {
                Text(
                    "Nothing here yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 32.dp).fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        sections.forEach { (section, sectionItems) ->
            item(key = "header-$section") {
                Text(
                    section,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
            }
            sectionItems.forEach { item ->
                item(key = item.id) {
                    NotificationCard(item, onClick = { item.mediaId?.let(onAnimeClick) })
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(item: AppNotification, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .focusHighlight(shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (item.unread) 1.5.dp else 1.dp,
                color = if (item.unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                shape = shape,
            )
            .clickable(enabled = item.mediaId != null, onClick = onClick),
    ) {
        item.banner?.let { banner ->
            AsyncImage(
                model = banner,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.18f,
                modifier = Modifier.matchParentSize(),
            )
        }
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = item.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 58.dp, height = 76.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.detail?.let { detail ->
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                item.badge?.let { badge ->
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Text(
                formatDay(item.createdAt),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Bottom)
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

private val dayFormatter = DateTimeFormatter.ofPattern("d MMM")

private fun formatDay(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(dayFormatter)
