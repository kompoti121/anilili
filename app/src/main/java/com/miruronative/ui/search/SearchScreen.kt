package com.miruronative.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.miruronative.data.model.Media
import com.miruronative.ui.UiState
import com.miruronative.ui.components.AnimeCard
import com.miruronative.ui.components.ErrorBox
import com.miruronative.ui.components.LoadingBox
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onAnimeClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    vm: SearchViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val device = LocalAppDeviceProfile.current
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(device.isTv) {
        if (!device.isTv) focusRequester.requestFocus()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = vm.query,
                        onValueChange = vm::onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = if (device.isTv) device.pagePadding else 0.dp)
                            .focusHighlight(RoundedCornerShape(12.dp))
                            .focusRequester(focusRequester),
                        placeholder = { Text("Search anime…") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        trailingIcon = {
                            if (vm.query.isNotEmpty()) {
                                IconButton(
                                    onClick = { vm.onQueryChange("") },
                                    modifier = Modifier.focusHighlight(RoundedCornerShape(24.dp)),
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                is UiState.Loading -> LoadingBox()
                is UiState.Error -> ErrorBox(s.message)
            is UiState.Success -> ResultsGrid(s.data, vm.query, onAnimeClick)
            }
        }
    }
}

@Composable
private fun ResultsGrid(results: List<Media>, query: String, onAnimeClick: (Int) -> Unit) {
    val device = LocalAppDeviceProfile.current
    if (results.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = if (query.isBlank()) "Search for any anime" else "No results",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(device.gridMinWidth),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = device.pagePadding,
            vertical = 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(if (device.isTv) 20.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (device.isTv) 22.dp else 16.dp),
    ) {
        if (query.isBlank()) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Text(
                    "Popular searches",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        items(results, key = { it.id }) { media ->
            AnimeCard(media, onClick = { onAnimeClick(media.id) })
        }
    }
}
