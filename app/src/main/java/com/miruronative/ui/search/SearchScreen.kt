package com.miruronative.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.miruronative.data.model.DiscoverFilters
import com.miruronative.data.model.DiscoverOptions
import com.miruronative.data.model.Media
import com.miruronative.ui.UiState
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight
import com.miruronative.ui.components.AnimeCard
import com.miruronative.ui.components.ErrorBox
import com.miruronative.ui.components.LoadingBox
import com.miruronative.ui.components.PullRefreshContainer

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onAnimeClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    vm: SearchViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val isRefreshing by vm.isRefreshing.collectAsState()
    val options by vm.options.collectAsState()
    val device = LocalAppDeviceProfile.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var showFilters by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(
            modifier = Modifier.statusBarsPadding(),
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = device.pagePadding, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Browse", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                        Text(
                            "Find your next obsession",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = vm::clearAll) { Text("Reset") }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = vm.query,
                        onValueChange = vm::onQueryChange,
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(min = 0.dp, max = 720.dp)
                            .focusRequester(focusRequester)
                            // TV: the text field consumes D-pad Down for cursor handling, so
                            // focus can never escape into the results. Hand it off manually.
                            .onPreviewKeyEvent { event ->
                                if (device.isTv &&
                                    event.type == KeyEventType.KeyDown &&
                                    event.key == Key.DirectionDown
                                ) {
                                    keyboard?.hide()
                                    focusManager.moveFocus(FocusDirection.Down)
                                    true
                                } else {
                                    false
                                }
                            },
                        placeholder = { Text("Search anime…") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (vm.query.isNotEmpty()) {
                                IconButton(onClick = { vm.onQueryChange("") }, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear search")
                                }
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboard?.hide()
                            focusManager.moveFocus(FocusDirection.Down)
                        }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                    Button(
                        onClick = { showFilters = true },
                        contentPadding = PaddingValues(horizontal = 13.dp),
                        modifier = Modifier.height(56.dp).focusHighlight(RoundedCornerShape(10.dp)),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Default.FilterList, contentDescription = "Open filters")
                        if (vm.filters.activeCount > 0) {
                            Text(" ${vm.filters.activeCount}", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Text(
                    "Categories",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth().focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    item(key = "format-movie") {
                        FilterChip(
                            selected = vm.filters.format == "MOVIE",
                            onClick = { vm.setFormat(if (vm.filters.format == "MOVIE") null else "MOVIE") },
                            label = { Text("Movies") },
                        )
                    }
                    items(options.genres.take(14), key = { it }) { genre ->
                        FilterChip(
                            selected = genre in vm.filters.genres,
                            onClick = { vm.toggleGenre(genre) },
                            label = { Text(genre) },
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .7f))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (val current = state) {
                is UiState.Loading -> LoadingBox()
                is UiState.Error -> ErrorBox(current.message, vm::retry)
                is UiState.Success -> PullRefreshContainer(
                    isRefreshing = isRefreshing,
                    onRefresh = vm::refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    ResultsGrid(current.data, vm.filters, onAnimeClick)
                }
            }
        }
    }

    if (showFilters) {
        FilterSheet(
            filters = vm.filters,
            options = options,
            vm = vm,
            onDismiss = { showFilters = false },
        )
    }
}

@Composable
private fun ResultsGrid(results: List<Media>, filters: DiscoverFilters, onAnimeClick: (Int) -> Unit) {
    val device = LocalAppDeviceProfile.current
    val tileMinWidth = if (device.isTv) 118.dp else device.gridMinWidth
    if (results.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Nothing matched", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Try removing a filter or searching another title.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(tileMinWidth),
        contentPadding = PaddingValues(horizontal = device.pagePadding, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(if (device.isTv) 14.dp else 9.dp),
        verticalArrangement = Arrangement.spacedBy(if (device.isTv) 16.dp else 14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (filters.query.isBlank()) "Discover anime" else "Results for “${filters.query}”",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    SearchViewModel.SORTS.firstOrNull { it.value == filters.sort }?.label.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        gridItems(results, key = { it.id }) { media ->
            AnimeCard(media, onClick = { onAnimeClick(media.id) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    filters: DiscoverFilters,
    options: DiscoverOptions,
    vm: SearchViewModel,
    onDismiss: () -> Unit,
) {
    var tagSearch by remember { mutableStateOf("") }
    val visibleTags = remember(options.tags, tagSearch) {
        options.tags
            .filter { tagSearch.isBlank() || it.name.contains(tagSearch, ignoreCase = true) }
            .take(36)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Filter catalog", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                        Text(
                            "Combine filters to narrow the full AniList catalog.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = vm::clearFilters) { Text("Clear") }
                }
            }
            item { FilterSection("Sort by") { ChoiceFlow(SearchViewModel.SORTS, filters.sort, vm::setSort) } }
            item {
                FilterSection("Release year") {
                    OutlinedTextField(
                        value = filters.year?.toString().orEmpty(),
                        onValueChange = { value ->
                            val digits = value.filter(Char::isDigit).take(4)
                            vm.setYear(digits.toIntOrNull()?.takeIf { it in 1900..2100 })
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Any year (for example 2024)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                    )
                }
            }
            item { FilterSection("Status") { NullableChoiceFlow(SearchViewModel.STATUSES, filters.status, vm::setStatus) } }
            item { FilterSection("Format") { NullableChoiceFlow(SearchViewModel.FORMATS, filters.format, vm::setFormat) } }
            item {
                FilterSection("Minimum rating") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = filters.minimumScore == null, onClick = { vm.setMinimumScore(null) }, label = { Text("Any") })
                        SearchViewModel.RATINGS.forEach { rating ->
                            FilterChip(
                                selected = filters.minimumScore == rating,
                                onClick = { vm.setMinimumScore(rating) },
                                label = { Text("$rating%+") },
                            )
                        }
                    }
                }
            }
            item {
                FilterSection("Genres") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        options.genres.forEach { genre ->
                            FilterChip(
                                selected = genre in filters.genres,
                                onClick = { vm.toggleGenre(genre) },
                                label = { Text(genre) },
                            )
                        }
                    }
                }
            }
            if (options.tags.isNotEmpty()) {
                item {
                    FilterSection("Tags") {
                        OutlinedTextField(
                            value = tagSearch,
                            onValueChange = { tagSearch = it },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            placeholder = { Text("Find a tag") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            visibleTags.forEach { tag ->
                                AssistChip(
                                    onClick = { vm.toggleTag(tag.name) },
                                    label = { Text(tag.name) },
                                    leadingIcon = if (tag.name in filters.tags) {
                                        { Text("✓", color = MaterialTheme.colorScheme.primary) }
                                    } else null,
                                )
                            }
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Show results", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun FilterSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 5.dp))
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceFlow(choices: List<CatalogChoice>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { choice ->
            FilterChip(selected = selected == choice.value, onClick = { onSelect(choice.value) }, label = { Text(choice.label) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NullableChoiceFlow(choices: List<CatalogChoice>, selected: String?, onSelect: (String?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("Any") })
        choices.forEach { choice ->
            FilterChip(selected = selected == choice.value, onClick = { onSelect(choice.value) }, label = { Text(choice.label) })
        }
    }
}
