package com.imsbooks.songbook.ui.catalogue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imsbooks.songbook.AppContainer
import com.imsbooks.songbook.data.BookSummary
import com.imsbooks.songbook.data.Category
import com.imsbooks.songbook.data.apiCall
import com.imsbooks.songbook.data.userMessage
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BookSort(val api: String, val label: String) {
    TITLE("title,asc", "Title A–Z"),
    UPDATED("updatedAt,desc", "Recently updated"),
    NEWEST("effectiveDate,desc", "Newest"),
}

data class CatalogueState(
    val query: String = "",
    val categoryId: String? = null,
    val sort: BookSort = BookSort.TITLE,
    val categories: List<Category> = emptyList(),
    val books: List<BookSummary> = emptyList(),
    val page: Int = 0,
    val totalPages: Int = 0,
    val totalElements: Long = 0,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
) {
    val canLoadMore: Boolean get() = page + 1 < totalPages
}

@OptIn(FlowPreview::class)
class CatalogueViewModel(private val c: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(CatalogueState())
    val state: StateFlow<CatalogueState> = _state.asStateFlow()

    private val queryInput = MutableStateFlow("")
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                val categories = apiCall(c.json) { c.api.categories() }
                _state.update { it.copy(categories = categories) }
            } catch (_: Exception) {
                // Category chips are optional; the list still works without them.
            }
        }
        queryInput.drop(1).debounce(350).distinctUntilChanged().onEach { reload() }.launchIn(viewModelScope)
        reload()
    }

    fun onQueryChange(q: String) {
        _state.update { it.copy(query = q) }
        queryInput.value = q.trim()
    }

    fun onCategory(id: String?) {
        _state.update { it.copy(categoryId = id) }
        reload()
    }

    fun onSort(sort: BookSort) {
        _state.update { it.copy(sort = sort) }
        reload()
    }

    fun reload() = load(page = 0)

    fun loadMore() {
        val s = _state.value
        if (s.canLoadMore && !s.loading && !s.loadingMore) load(page = s.page + 1)
    }

    private fun load(page: Int) {
        loadJob?.cancel()
        _state.update { if (page == 0) it.copy(loading = true, error = null) else it.copy(loadingMore = true, error = null) }
        loadJob = viewModelScope.launch {
            val s = _state.value
            try {
                val result = apiCall(c.json) {
                    c.api.books(s.query.trim().ifEmpty { null }, s.categoryId, page, PAGE_SIZE, s.sort.api)
                }
                _state.update {
                    it.copy(
                        books = if (page == 0) result.content else it.books + result.content,
                        page = result.page,
                        totalPages = result.totalPages,
                        totalElements = result.totalElements,
                        loading = false,
                        loadingMore = false,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadingMore = false, error = e.userMessage()) }
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}
