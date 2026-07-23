package com.miruronative.ui.search

import android.view.inputmethod.EditorInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import com.miruronative.ui.adaptive.TvNativeTextField
import com.miruronative.ui.adaptive.TvTextInputType
import com.miruronative.ui.adaptive.focusHighlight
import com.miruronative.ui.components.AnimeCard
import com.miruronative.ui.components.ErrorBox
import com.miruronative.ui.components.LoadingBox
import com.miruronative.ui.components.PullRefreshContainer
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onAnimeClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    vm: SearchViewModel = viewModel(),
    tvFieldFocusRequester: FocusRequester? = null,
    initialStudioId: Int? = null,
    initialStudioName: String? = null,
) {
    val state by vm.state.collectAsState()
    val isRefreshing by vm.isRefreshing.collectAsState()
    val isLoadingMore by vm.isLoadingMore.collectAsState()
    val options by vm.options.collectAsState()
    val device = LocalAppDeviceProfile.current
    val gridState = rememberLazyGridState()
    var showFilters by remember { mutableStateOf(false) }
    // Reveal the search + categories bar when the grid is at the top or being dragged upward,
    // and tuck it away while scrolling down so results get the full screen height. TV keeps the
    // bar pinned so D-pad focus can always return to the search field.
    val scrollingUp = gridState.isScrollingUp()
    val topBarVisible = device.isTv || scrollingUp

    LaunchedEffect(initialStudioId, initialStudioName) {
        val studioId = initialStudioId ?: return@LaunchedEffect
        val studioName = initialStudioName ?: return@LaunchedEffect
        vm.applyStudioFilter(studioId, studioName)
    }

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AnimatedVisibility(visible = topBarVisible) {
            Column {
                SearchTopBar(
                    vm = vm,
                    options = options,
                    onOpenFilters = { showFilters = true },
                    tvFieldFocusRequester = tvFieldFocusRequester,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .7f))
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (val current = state) {
                is UiState.Loading -> LoadingBox()
                is UiState.Error -> ErrorBox(current.message, vm::retry)
                is UiState.Success -> PullRefreshContainer(
                    isRefreshing = isRefreshing,
                    onRefresh = vm::refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    ResultsGrid(
                        results = current.data,
                        filters = vm.filters,
                        // A tapped result is proof the query mattered, so record it before leaving.
                        onAnimeClick = { id -> vm.recordCurrentSearch(); onAnimeClick(id) },
                        gridState = gridState,
                        isLoadingMore = isLoadingMore,
                        onLoadMore = vm::loadMore,
                    )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    vm: SearchViewModel,
    options: DiscoverOptions,
    onOpenFilters: () -> Unit,
    tvFieldFocusRequester: FocusRequester?,
) {
    val device = LocalAppDeviceProfile.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    Surface(
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        val history by vm.searchHistory.collectAsState()
        var fieldFocused by remember { mutableStateOf(false) }
        // Past searches surfaced as an autocomplete list while the field is focused: everything
        // recent when it's empty, narrowing to matches as the user types (the exact current term
        // drops out — it's already in the box). TV keeps its D-pad grid, so it opts out.
        val suggestions = remember(history, vm.query, fieldFocused, device.isTv) {
            if (device.isTv || !fieldFocused) {
                emptyList()
            } else {
                val term = vm.query.trim()
                history.filter { it.contains(term, ignoreCase = true) && !it.equals(term, ignoreCase = true) }
            }
        }
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
                if (device.isTv) {
                    TvNativeTextField(
                        value = vm.query,
                        onValueChange = vm::onQueryChange,
                        hint = "Search anime",
                        modifier = Modifier.weight(1f).widthIn(min = 0.dp, max = 720.dp),
                        imeAction = EditorInfo.IME_ACTION_SEARCH,
                        onImeAction = {
                            vm.recordCurrentSearch()
                            focusManager.moveFocus(FocusDirection.Down)
                        },
                        onMoveDown = { focusManager.moveFocus(FocusDirection.Down) },
                        tvFocusRequester = tvFieldFocusRequester,
                    )
                } else {
                    OutlinedTextField(
                        value = vm.query,
                        onValueChange = vm::onQueryChange,
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(min = 0.dp, max = 720.dp)
                            .fillMaxWidth()
                            .onFocusChanged { fieldFocused = it.isFocused },
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
                            vm.recordCurrentSearch()
                            keyboard?.hide()
                            focusManager.moveFocus(FocusDirection.Down)
                        }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                }
                Button(
                    onClick = onOpenFilters,
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

            SearchSuggestions(
                suggestions = suggestions,
                onPick = { term ->
                    vm.applyHistoryQuery(term)
                    keyboard?.hide()
                    focusManager.clearFocus()
                },
                onRemove = vm::removeHistoryQuery,
            )

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
}

/**
 * Autocomplete list of past searches shown under the field while it is focused. Animated so it
 * slides in on focus rather than snapping the layout, and capped so a long history never pushes
 * the categories off-screen — the field's own scroll reaches the rest.
 */
@Composable
private fun SearchSuggestions(
    suggestions: List<String>,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    AnimatedVisibility(visible = suggestions.isNotEmpty()) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
            tonalElevation = 2.dp,
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                suggestions.take(8).forEach { term ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(term) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            term,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 12.dp),
                        )
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove $term",
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .clickable { onRemove(term) }
                                .padding(6.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultsGrid(
    results: List<Media>,
    filters: DiscoverFilters,
    onAnimeClick: (Int) -> Unit,
    gridState: LazyGridState,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
) {
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
    var focusedResultIndex by remember(results) { mutableStateOf<Int?>(null) }
    val horizontalSpacing = if (device.isTv) 14.dp else 9.dp

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columnCount = adaptiveColumnCount(
            availableWidth = maxWidth,
            horizontalPadding = device.pagePadding,
            minimumTileWidth = tileMinWidth,
            spacing = horizontalSpacing,
        )

        LaunchedEffect(focusedResultIndex, columnCount, device.isTv) {
            val resultIndex = focusedResultIndex ?: return@LaunchedEffect
            if (device.isTv && resultIndex >= columnCount) {
                val rowStart = (resultIndex / columnCount) * columnCount
                // Keep the focused TV row together instead of exposing the previous row's
                // detached title and metadata strip above it.
                gridState.scrollToItem(rowStart + 1)
            }
        }

        val lastVisibleIndex by remember(gridState) {
            derivedStateOf { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
        }
        LaunchedEffect(lastVisibleIndex, results.size, columnCount) {
            if (lastVisibleIndex >= results.size - columnCount * 2) onLoadMore()
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(tileMinWidth),
            state = gridState,
            contentPadding = PaddingValues(horizontal = device.pagePadding, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
            verticalArrangement = Arrangement.spacedBy(if (device.isTv) 16.dp else 14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            filters.query.isNotBlank() -> "Results for “${filters.query}”"
                            !filters.studioName.isNullOrBlank() -> "Anime by ${filters.studioName}"
                            else -> "Discover anime"
                        },
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
            gridItemsIndexed(results, key = { _, media -> media.id }) { index, media ->
                AnimeCard(
                    media = media,
                    onClick = { onAnimeClick(media.id) },
                    modifier = if (device.isTv) {
                        Modifier.onFocusChanged { state ->
                            if (state.isFocused) focusedResultIndex = index
                        }
                    } else {
                        Modifier
                    },
                )
            }
            if (isLoadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                }
            }
        }
    }
}

private fun adaptiveColumnCount(
    availableWidth: androidx.compose.ui.unit.Dp,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    minimumTileWidth: androidx.compose.ui.unit.Dp,
    spacing: androidx.compose.ui.unit.Dp,
): Int {
    val contentWidth = availableWidth - horizontalPadding * 2
    return ((contentWidth.value + spacing.value) / (minimumTileWidth.value + spacing.value))
        .toInt()
        .coerceAtLeast(1)
}

/**
 * True while the grid is resting at the top or the user is dragging it back upward — the cue for
 * showing the search bar. Flips to false the moment scrolling moves downward so the bar hides.
 */
@Composable
private fun LazyGridState.isScrollingUp(): Boolean {
    var lastRealDirectionWasUp by remember(this) { mutableStateOf(true) }
    val atTop by remember(this) {
        derivedStateOf { firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0 }
    }
    LaunchedEffect(this) {
        var previousIndex = firstVisibleItemIndex
        var previousOffset = firstVisibleItemScrollOffset
        snapshotFlow { firstVisibleItemIndex to firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                lastRealDirectionWasUp = index < previousIndex ||
                    (index == previousIndex && offset < previousOffset)
                previousIndex = index
                previousOffset = offset
            }
        }
    return atTop || lastRealDirectionWasUp
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    filters: DiscoverFilters,
    options: DiscoverOptions,
    vm: SearchViewModel,
    onDismiss: () -> Unit,
) {
    val device = LocalAppDeviceProfile.current
    val studioSuggestions by vm.studioSuggestions.collectAsState()
    val studioLookupLoading by vm.isStudioLookupLoading.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
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
                FilterSection("Studio") {
                    if (device.isTv) {
                        TvNativeTextField(
                            value = vm.studioQuery,
                            onValueChange = vm::onStudioQueryChange,
                            hint = "Find a studio, for example MAPPA",
                            modifier = Modifier.fillMaxWidth(),
                            imeAction = EditorInfo.IME_ACTION_SEARCH,
                            onImeAction = {
                                vm.selectFirstStudioSuggestion()
                                focusManager.moveFocus(FocusDirection.Down)
                            },
                            onMoveDown = { focusManager.moveFocus(FocusDirection.Down) },
                        )
                    } else {
                        OutlinedTextField(
                            value = vm.studioQuery,
                            onValueChange = vm::onStudioQueryChange,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Find a studio (for example MAPPA)") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                when {
                                    studioLookupLoading -> CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    vm.studioQuery.isNotEmpty() -> IconButton(onClick = vm::clearStudio) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear studio")
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                vm.selectFirstStudioSuggestion()
                                keyboard?.hide()
                                focusManager.moveFocus(FocusDirection.Down)
                            }),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                        )
                    }
                    if (studioSuggestions.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            studioSuggestions.forEach { studio ->
                                val name = studio.name ?: return@forEach
                                FilterChip(
                                    selected = filters.studioId == studio.id,
                                    onClick = { vm.selectStudio(studio) },
                                    label = { Text(name) },
                                    modifier = Modifier.focusHighlight(RoundedCornerShape(8.dp)),
                                )
                            }
                        }
                    } else if (filters.studioId != null && !filters.studioName.isNullOrBlank()) {
                        Text(
                            "Filtering by ${filters.studioName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            item {
                FilterSection("Release year") {
                    if (device.isTv) {
                        TvNativeTextField(
                            value = filters.year?.toString().orEmpty(),
                            onValueChange = { value ->
                                val digits = value.filter(Char::isDigit).take(4)
                                vm.setYear(digits.toIntOrNull()?.takeIf { it in 1900..2100 })
                            },
                            hint = "Any year, for example 2024",
                            modifier = Modifier.fillMaxWidth(),
                            inputType = TvTextInputType.NUMBER,
                            onMoveDown = { focusManager.moveFocus(FocusDirection.Down) },
                        )
                    } else {
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
                        if (device.isTv) {
                            TvNativeTextField(
                                value = tagSearch,
                                onValueChange = { tagSearch = it },
                                hint = "Find a tag",
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                onMoveDown = { focusManager.moveFocus(FocusDirection.Down) },
                            )
                        } else {
                            OutlinedTextField(
                                value = tagSearch,
                                onValueChange = { tagSearch = it },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                placeholder = { Text("Find a tag") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                            )
                        }
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
