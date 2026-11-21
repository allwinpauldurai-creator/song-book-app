package com.imsbooks.songbook.ui.catalogue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imsbooks.songbook.data.BookSummary
import com.imsbooks.songbook.ui.appViewModel
import com.imsbooks.songbook.ui.components.BookCover
import com.imsbooks.songbook.ui.components.EmptyState
import com.imsbooks.songbook.ui.components.ErrorState
import com.imsbooks.songbook.ui.components.LoadingBox

/** Public catalogue: search, category chips, sort, infinite grid (web: /books). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogueScreen(onOpenBook: (String) -> Unit) {
    val vm = appViewModel { CatalogueViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val grid = rememberLazyGridState()

    val nearEnd by remember {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= grid.layoutInfo.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, state.books.size) { if (nearEnd) vm.loadMore() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("IMS Books", fontWeight = FontWeight.Bold) },
                actions = { SortMenu(state.sort, vm::onSort) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQueryChange,
                placeholder = { Text("Search song books") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { vm.onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            if (state.categories.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = state.categoryId == null,
                            onClick = { vm.onCategory(null) },
                            label = { Text("All") },
                        )
                    }
                    items(state.categories, key = { it.id }) { cat ->
                        FilterChip(
                            selected = state.categoryId == cat.id,
                            onClick = { vm.onCategory(if (state.categoryId == cat.id) null else cat.id) },
                            label = { Text(cat.name) },
                        )
                    }
                }
            }

            PullToRefreshBox(
                isRefreshing = state.loading && state.books.isNotEmpty(),
                onRefresh = vm::reload,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.loading && state.books.isEmpty() -> LoadingBox()
                    state.error != null && state.books.isEmpty() -> ErrorState(state.error!!, onRetry = vm::reload)
                    state.books.isEmpty() -> EmptyState(
                        "No books match your search",
                        "Try a different word or category.",
                    )
                    else -> LazyVerticalGrid(
                        state = grid,
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.books, key = { it.id }) { book -> BookCard(book) { onOpenBook(book.id) } }
                        if (state.loadingMore || (state.error != null)) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                if (state.loadingMore) {
                                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator()
                                    }
                                } else {
                                    ErrorState(state.error!!, onRetry = vm::loadMore)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookCard(book: BookSummary, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClickLabel = "Open ${book.title}", onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BookCover(book.coverUrl, book.title, Modifier.fillMaxWidth())
        Text(
            book.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        book.category?.let {
            Text(it.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        }
        book.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SortMenu(current: BookSort, onSort: (BookSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row {
        TextButton(onClick = { open = true }) { Text("Sort: ${current.label}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            BookSort.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label, fontWeight = if (s == current) FontWeight.Bold else null) },
                    onClick = {
                        open = false
                        onSort(s)
                    },
                )
            }
        }
    }
}
