package com.miruronative.ui.schedule

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.NotificationsNone
import coil.compose.AsyncImage
import com.miruronative.data.model.AiringSchedule
import com.miruronative.data.reminder.ReminderManager
import com.miruronative.ui.UiState
import com.miruronative.ui.components.ErrorBox
import com.miruronative.ui.components.LoadingBox
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("hh:mm a")
private val dateFmt = DateTimeFormatter.ofPattern("EEEE, MMM d")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    onAnimeClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    vm: ScheduleViewModel = viewModel(),
) {
    val device = LocalAppDeviceProfile.current
    val state by vm.state.collectAsState()
    val scheduled by ReminderManager.scheduled.collectAsState()
    val context = LocalContext.current
    var pendingReminder by remember { mutableStateOf<AiringSchedule?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingReminder?.let(ReminderManager::toggle)
        pendingReminder = null
    }
    val toggleReminder: (AiringSchedule) -> Unit = { item ->
        val alreadyScheduled = ReminderManager.isScheduled(item)
        if (!alreadyScheduled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingReminder = item
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ReminderManager.toggle(item)
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Schedule") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            DayTabs(vm.selectedDay, vm::selectDay)
            Text(
                text = LocalDate.now(ZoneId.systemDefault()).plusDays(vm.selectedDay.toLong()).format(dateFmt),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = device.pagePadding, top = 4.dp, bottom = 8.dp),
            )
            when (val s = state) {
                is UiState.Loading -> LoadingBox()
                is UiState.Error -> ErrorBox(s.message, vm::load)
                is UiState.Success -> ScheduleList(s.data, scheduled, onAnimeClick, toggleReminder)
            }
        }
    }
}

@Composable
private fun DayTabs(selected: Int, onSelect: (Int) -> Unit) {
    val device = LocalAppDeviceProfile.current
    val today = LocalDate.now(ZoneId.systemDefault())
    val tabs = (0..6).map { offset ->
        offset to when (offset) {
            0 -> "Today"
            1 -> "Tomorrow"
            else -> today.plusDays(offset.toLong()).format(DateTimeFormatter.ofPattern("EEE"))
        }
    }
    LazyRow(
        modifier = Modifier.focusGroup(),
        contentPadding = PaddingValues(horizontal = device.pagePadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tabs.size) { index ->
            val (offset, label) = tabs[index]
            val active = offset == selected
            Box(
                Modifier
                    .focusHighlight(RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                    .clickable { onSelect(offset) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ScheduleList(
    items: List<AiringSchedule>,
    scheduled: Set<String>,
    onAnimeClick: (Int) -> Unit,
    onToggleReminder: (AiringSchedule) -> Unit,
) {
    val device = LocalAppDeviceProfile.current
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing scheduled", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val zone = ZoneId.systemDefault()
    val grouped = items.groupBy { Instant.ofEpochSecond(it.airingAt).atZone(zone).hour }.toSortedMap()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        grouped.forEach { (hour, list) ->
            item {
                Text(
                    text = LocalTime.of(hour, 0).format(timeFmt),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = device.pagePadding, top = 14.dp, bottom = 6.dp),
                )
            }
            items(list.size) { i ->
                val item = list[i]
                ScheduleRow(item, zone, ReminderManager.id(item) in scheduled, onAnimeClick, onToggleReminder)
            }
        }
    }
}

@Composable
private fun ScheduleRow(
    item: AiringSchedule,
    zone: ZoneId,
    reminded: Boolean,
    onAnimeClick: (Int) -> Unit,
    onToggleReminder: (AiringSchedule) -> Unit,
) {
    val media = item.media ?: return
    val device = LocalAppDeviceProfile.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = device.pagePadding, vertical = 7.dp)
            .focusHighlight(RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onAnimeClick(media.id) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = media.coverImage.best,
            contentDescription = media.title.preferred,
            modifier = Modifier
                .size(width = 54.dp, height = 74.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                media.title.preferred,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    "EP ${item.episode}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(
            text = Instant.ofEpochSecond(item.airingAt).atZone(zone).format(timeFmt),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(
            onClick = { onToggleReminder(item) },
            modifier = Modifier.focusHighlight(RoundedCornerShape(24.dp)),
        ) {
            Icon(
                if (reminded) Icons.Filled.Notifications else Icons.Outlined.NotificationsNone,
                contentDescription = if (reminded) "Remove reminder" else "Remind me",
                tint = if (reminded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
