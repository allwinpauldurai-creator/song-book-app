package com.imsbooks.songbook.ui.member

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.imsbooks.songbook.AppContainer
import com.imsbooks.songbook.data.ApiException
import com.imsbooks.songbook.data.BookSummary
import com.imsbooks.songbook.data.CreateRequestBody
import com.imsbooks.songbook.data.ModificationRequest
import com.imsbooks.songbook.data.apiCall
import com.imsbooks.songbook.data.userMessage
import com.imsbooks.songbook.ui.appViewModel
import com.imsbooks.songbook.ui.auth.FormState
import com.imsbooks.songbook.ui.components.EmptyState
import com.imsbooks.songbook.ui.components.ErrorState
import com.imsbooks.songbook.ui.components.LoadingBox
import com.imsbooks.songbook.ui.components.Notice
import com.imsbooks.songbook.util.formatDate
import com.imsbooks.songbook.util.titleCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val REQUEST_TYPES = listOf(
    Triple("CORRECTION", "Correction", "A mistake in lyrics, chords, numbering or metadata."),
    Triple("ADDITION", "Addition", "A song or book you would like added."),
    Triple("OTHER", "Other", "Anything else about the library."),
)
private const val MAX_DESCRIPTION = 5000

data class RequestsState(
    val items: List<ModificationRequest> = emptyList(),
    val page: Int = 0,
    val totalPages: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
    val create: FormState = FormState(),
    val bookResults: List<BookSummary> = emptyList(),
)

class RequestsViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(RequestsState())
    val state: StateFlow<RequestsState> = _state.asStateFlow()
    private var searchJob: Job? = null

    init {
        load(0)
    }

    fun load(page: Int) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val res = apiCall(c.json) { c.api.myRequests(page, 20) }
                _state.update {
                    it.copy(
                        items = if (page == 0) res.content else it.items + res.content,
                        page = res.page,
                        totalPages = res.totalPages,
                        loading = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    fun searchBooks(term: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            val res = runCatching { apiCall(c.json) { c.api.books(term.trim().ifEmpty { null }, null, 0, 8, "title,asc") } }
            _state.update { it.copy(bookResults = res.getOrNull()?.content.orEmpty()) }
        }
    }

    fun create(type: String, bookId: String?, description: String) {
        _state.update { it.copy(create = FormState(busy = true)) }
        viewModelScope.launch {
            try {
                apiCall(c.json) { c.api.createRequest(CreateRequestBody(bookId, type, description)) }
                _state.update { it.copy(create = FormState(done = true)) }
                load(0)
            } catch (e: ApiException) {
                _state.update { it.copy(create = FormState(error = e)) }
            }
        }
    }

    fun resetCreate() = _state.update { it.copy(create = FormState()) }
}

/** My modification requests + new-request dialog (web: /me/requests). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestsScreen(initialBookId: String?, initialBookTitle: String?, onBack: () -> Unit) {
    val vm = appViewModel { RequestsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var dialogOpen by rememberSaveable { mutableStateOf(initialBookId != null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My requests") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { dialogOpen = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New request") },
            )
        },
    ) { padding ->
        when {
            state.loading && state.items.isEmpty() -> LoadingBox(Modifier.padding(padding))
            state.error != null && state.items.isEmpty() -> ErrorState(state.error!!, Modifier.padding(padding)) { vm.load(0) }
            state.items.isEmpty() -> EmptyState(
                "No requests yet",
                "Spotted a mistake or missing song? Send a request to the administrators.",
                Modifier.padding(padding),
            )
            else -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.items, key = { it.id }) { RequestCard(it) }
                if (state.page + 1 < state.totalPages) {
                    item {
                        TextButton(onClick = { vm.load(state.page + 1) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                            Text("Show more")
                        }
                    }
                }
            }
        }
    }

    if (dialogOpen) {
        NewRequestDialog(
            state = state,
            initialBook = initialBookId?.let { BookSummary(it, initialBookTitle ?: "") },
            onSearch = vm::searchBooks,
            onSubmit = vm::create,
            onClose = {
                dialogOpen = false
                vm.resetCreate()
            },
        )
    }
}

@Composable
private fun RequestCard(r: ModificationRequest) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val color = when (r.status) {
                    "APPROVED" -> MaterialTheme.colorScheme.secondary
                    "REJECTED" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.tertiary
                }
                Text(titleCase(r.status), color = color, fontWeight = FontWeight.Bold)
                Text("· ${titleCase(r.requestType)} · ${formatDate(r.createdAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(r.bookTitle ?: "General request", style = MaterialTheme.typography.titleSmall)
            Text(r.description, maxLines = 6, overflow = TextOverflow.Ellipsis)
            r.reviewNote?.takeIf { it.isNotBlank() }?.let {
                Text("Reply: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NewRequestDialog(
    state: RequestsState,
    initialBook: BookSummary?,
    onSearch: (String) -> Unit,
    onSubmit: (type: String, bookId: String?, description: String) -> Unit,
    onClose: () -> Unit,
) {
    var type by rememberSaveable { mutableStateOf("CORRECTION") }
    var description by rememberSaveable { mutableStateOf("") }
    var book by androidx.compose.runtime.remember { mutableStateOf(initialBook) }
    var bookSearch by rememberSaveable { mutableStateOf("") }
    val form = state.create
    val fields = form.error?.fieldMap.orEmpty()

    LaunchedEffect(form.done) { if (form.done) onClose() }

    AlertDialog(
        onDismissRequest = { if (!form.busy) onClose() },
        title = { Text("New request") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (form.error != null && fields.isEmpty()) Notice(form.error.message ?: "", isError = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    REQUEST_TYPES.forEach { (value, label) ->
                        FilterChip(selected = type == value, onClick = { type = value }, label = { Text(label) })
                    }
                }
                Text(
                    REQUEST_TYPES.first { it.first == type }.third,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val selected = book
                if (selected != null) {
                    InputChip(
                        selected = true,
                        onClick = { book = null },
                        label = { Text(selected.title.ifEmpty { "Selected book" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { Text("✕") },
                    )
                } else {
                    OutlinedTextField(
                        value = bookSearch,
                        onValueChange = {
                            bookSearch = it
                            onSearch(it)
                        },
                        label = { Text("Book (optional)") },
                        supportingText = { Text("Leave empty for a general request.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (bookSearch.isNotBlank()) {
                        state.bookResults.take(5).forEach { b ->
                            TextButton(onClick = { book = b }, modifier = Modifier.fillMaxWidth()) {
                                Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(MAX_DESCRIPTION) },
                    label = { Text("Description") },
                    minLines = 4,
                    isError = fields["description"] != null,
                    supportingText = { Text(fields["description"] ?: "${description.length}/$MAX_DESCRIPTION") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (description.isNotBlank()) onSubmit(type, book?.id, description.trim()) },
                enabled = !form.busy && description.isNotBlank(),
            ) { Text(if (form.busy) "Sending…" else "Send") }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !form.busy) { Text("Cancel") } },
    )
}
