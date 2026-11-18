package com.imsbooks.songbook.ui.admin

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.imsbooks.songbook.AppContainer
import com.imsbooks.songbook.data.AdminBookDetail
import com.imsbooks.songbook.data.AdminBookSummary
import com.imsbooks.songbook.data.AdminCategory
import com.imsbooks.songbook.data.ApiException
import com.imsbooks.songbook.data.BOOK_STATUSES
import com.imsbooks.songbook.data.BookMetadata
import com.imsbooks.songbook.data.BookVersion
import com.imsbooks.songbook.data.EDITABLE_BOOK_STATUSES
import com.imsbooks.songbook.data.PickedFile
import com.imsbooks.songbook.data.UpdateBookRequest
import com.imsbooks.songbook.data.apiCall
import com.imsbooks.songbook.data.checkImage
import com.imsbooks.songbook.data.checkPdf
import com.imsbooks.songbook.data.formatBytes
import com.imsbooks.songbook.data.part
import com.imsbooks.songbook.ui.appViewModel
import com.imsbooks.songbook.ui.components.BookCover
import com.imsbooks.songbook.ui.components.DownloadIcon
import com.imsbooks.songbook.ui.components.ErrorState
import com.imsbooks.songbook.ui.components.LoadingBox
import com.imsbooks.songbook.ui.components.Notice
import com.imsbooks.songbook.util.formatDate
import com.imsbooks.songbook.util.titleCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

// ================================================================ list (web: /admin/books)

data class BookFilters(val q: String = "", val status: String? = null, val categoryId: String? = null)

@OptIn(FlowPreview::class)
class AdminBooksViewModel(private val c: AppContainer, initial: BookFilters) : ViewModel() {
    private val _filters = MutableStateFlow(initial)
    val filters = _filters.asStateFlow()
    private val _categories = MutableStateFlow<List<AdminCategory>>(emptyList())
    val categories = _categories.asStateFlow()
    val list = PagedLoader(viewModelScope, c.json) { page, size ->
        val f = _filters.value
        c.adminApi.books(f.q.trim().ifBlank { null }, f.status, f.categoryId, page, size)
    }

    init {
        list.refresh()
        viewModelScope.launch { _filters.drop(1).debounce(300).collect { list.refresh() } }
        viewModelScope.launch { _categories.value = runCatching { apiCall(c.json) { c.adminApi.categories() } }.getOrDefault(emptyList()) }
    }

    fun setFilters(f: BookFilters) {
        _filters.value = f
    }
}

@Composable
fun AdminBooksScreen(status: String?, onBack: (() -> Unit)?, onOpen: (String) -> Unit, onNew: () -> Unit) {
    RequireStaff(adminOnly = false, onBack = onBack) { me ->
        val vm = appViewModel(key = "admin-books-$status") { AdminBooksViewModel(it, BookFilters(status = status)) }
        val filters by vm.filters.collectAsStateWithLifecycle()
        val categories by vm.categories.collectAsStateWithLifecycle()
        val list by vm.list.state.collectAsStateWithLifecycle()
        // Coming back from the editor: show its changes.
        androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
            if (list.page >= 0) vm.list.refresh()
            onPauseOrDispose { }
        }

        Scaffold(
            topBar = { AdminTopBar(if (me.isAdmin) "Books" else "My books", onBack) },
            floatingActionButton = {
                FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, contentDescription = "New book") }
            },
        ) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            (if (me.isAdmin) "All books" else "Books you published") +
                                " in every status. Only published books inside their date window appear in the catalogue.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            filters.q, { vm.setFilters(filters.copy(q = it)) }, label = { Text("Search title") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DropdownField(
                                "Status", filters.status,
                                listOf(null to "Any status") + BOOK_STATUSES.map { it to titleCase(it) },
                                { vm.setFilters(filters.copy(status = it)) }, Modifier.weight(1f),
                            )
                            DropdownField(
                                "Category", filters.categoryId,
                                listOf(null to "Any category") + categories.map { it.id to it.name },
                                { vm.setFilters(filters.copy(categoryId = it)) }, Modifier.weight(1f),
                            )
                        }
                    }
                }
                pagedItems(list, "No books match these filters", vm.list::refresh, vm.list::loadMore, key = { it.id }) { b ->
                    AdminBookRow(b) { onOpen(b.id) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun AdminBookRow(b: AdminBookSummary, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(b.coverUrl, b.title, Modifier.width(44.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(b.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            b.category?.let { Text(it.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BookChip(b.status)
                if (b.availableToday) StatusChip("Public now", MaterialTheme.colorScheme.secondary)
            }
            Text(
                "From ${formatDate(b.effectiveDate)}" + (b.expiryDate?.let { " to ${formatDate(it)}" } ?: "") +
                    " · updated ${formatDate(b.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ================================================================ create / edit (web: BookFormPage)

data class BookEditState(
    val bookId: String? = null,
    val book: AdminBookDetail? = null,
    val versions: List<BookVersion> = emptyList(),
    val categories: List<AdminCategory> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val save: FormStatus = FormStatus(),
    val uploadProgress: Float? = null,
    val cover: FormStatus = FormStatus(),
    val newVersion: FormStatus = FormStatus(),
    val archive: FormStatus = FormStatus(),
    val message: String? = null,
)

class BookEditViewModel(private val c: AppContainer, bookId: String?) : ViewModel() {
    private val _state = MutableStateFlow(BookEditState(bookId = bookId, loading = bookId != null))
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val cats = runCatching { apiCall(c.json) { c.adminApi.categories() } }.getOrDefault(emptyList())
            _state.update { it.copy(categories = cats) }
        }
        if (bookId != null) load()
    }

    fun load() {
        val id = _state.value.bookId ?: return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val book = apiCall(c.json) { c.adminApi.book(id) }
                val versions = apiCall(c.json) { c.adminApi.versions(id) }
                _state.update { it.copy(book = book, versions = versions, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = adminErrorMessage(e)) }
            }
        }
    }

    fun create(metadata: BookMetadata, pdf: PickedFile, cover: PickedFile?, resolver: ContentResolver) {
        _state.update { it.copy(save = FormStatus(busy = true), uploadProgress = 0f) }
        viewModelScope.launch {
            try {
                val meta = c.json.encodeToString(BookMetadata.serializer(), metadata).toRequestBody("application/json".toMediaType())
                val book = apiCall(c.json) {
                    c.uploadApi.createBook(
                        meta,
                        resolver.part("pdf", pdf, "application/pdf") { p -> _state.update { it.copy(uploadProgress = p) } },
                        cover?.let { resolver.part("cover", it, "image/jpeg") },
                    )
                }
                _state.update { it.copy(bookId = book.id, book = book, save = FormStatus(done = true), uploadProgress = null, message = "Book created.") }
                load()
            } catch (e: Exception) {
                _state.update { it.copy(save = FormStatus(error = e), uploadProgress = null) }
            }
        }
    }

    fun update(metadata: BookMetadata) {
        val book = _state.value.book ?: return
        _state.update { it.copy(save = FormStatus(busy = true)) }
        viewModelScope.launch {
            try {
                val saved = apiCall(c.json) {
                    c.adminApi.updateBook(
                        book.id,
                        UpdateBookRequest(
                            metadata.title, metadata.description, metadata.categoryId, metadata.effectiveDate,
                            metadata.expiryDate, metadata.status, book.version,
                        ),
                    )
                }
                _state.update { it.copy(book = saved, save = FormStatus(done = true), message = "Saved.") }
            } catch (e: Exception) {
                _state.update { it.copy(save = FormStatus(error = e)) }
            }
        }
    }

    fun replaceCover(file: PickedFile, resolver: ContentResolver) {
        val book = _state.value.book ?: return
        _state.update { it.copy(cover = FormStatus(busy = true)) }
        viewModelScope.launch {
            try {
                val saved = apiCall(c.json) { c.uploadApi.replaceCover(book.id, resolver.part("cover", file, "image/jpeg")) }
                _state.update { it.copy(book = saved, cover = FormStatus(done = true), message = "Cover replaced.") }
            } catch (e: Exception) {
                _state.update { it.copy(cover = FormStatus(error = e)) }
            }
        }
    }

    fun addVersion(file: PickedFile, note: String, resolver: ContentResolver) {
        val book = _state.value.book ?: return
        _state.update { it.copy(newVersion = FormStatus(busy = true), uploadProgress = 0f) }
        viewModelScope.launch {
            try {
                apiCall(c.json) {
                    c.uploadApi.addVersion(
                        book.id,
                        resolver.part("pdf", file, "application/pdf") { p -> _state.update { it.copy(uploadProgress = p) } },
                        note.ifBlank { null },
                    )
                }
                _state.update {
                    it.copy(
                        newVersion = FormStatus(done = true), uploadProgress = null,
                        message = "New version uploaded and now current.",
                    )
                }
                load()
            } catch (e: Exception) {
                _state.update { it.copy(newVersion = FormStatus(error = e), uploadProgress = null) }
            }
        }
    }

    fun archive(onDone: () -> Unit) {
        val book = _state.value.book ?: return
        _state.update { it.copy(archive = FormStatus(busy = true)) }
        viewModelScope.launch {
            try {
                apiCall(c.json) { c.adminApi.archiveBook(book.id).also { if (!it.isSuccessful) throw ApiException.from(it, c.json) } }
                _state.update { it.copy(archive = FormStatus(done = true), message = "Book archived.") }
                load()
                onDone()
            } catch (e: Exception) {
                _state.update { it.copy(archive = FormStatus(error = e)) }
            }
        }
    }

    /** Saves any version (current or old) to a file the user picked. */
    fun downloadVersion(version: BookVersion, destination: Uri, resolver: ContentResolver) {
        val book = _state.value.book ?: return
        viewModelScope.launch {
            try {
                val res = apiCall(c.json) { c.adminApi.versionContent(book.id, version.id) }
                if (!res.isSuccessful) throw ApiException.from(res, c.json)
                withContext(Dispatchers.IO) {
                    val out = resolver.openOutputStream(destination, "wt") ?: throw IOException("Cannot write the file")
                    out.use { o -> res.body()!!.byteStream().use { it.copyTo(o) } }
                }
                _state.update { it.copy(message = "Version ${version.versionNumber} saved.") }
            } catch (e: Exception) {
                runCatching { android.provider.DocumentsContract.deleteDocument(resolver, destination) }
                _state.update { it.copy(message = adminErrorMessage(e)) }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}

@Composable
fun BookEditScreen(bookId: String?, onBack: () -> Unit, onOpenPublic: (String) -> Unit) {
    RequireStaff(adminOnly = false, onBack = onBack) {
        val vm = appViewModel(key = "admin-book-${bookId ?: "new"}") { BookEditViewModel(it, bookId) }
        val state by vm.state.collectAsStateWithLifecycle()
        val resolver = LocalContext.current.contentResolver
        val snackbar = remember { SnackbarHostState() }
        var confirmArchive by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(state.message) {
            state.message?.let {
                snackbar.showSnackbar(it)
                vm.messageShown()
            }
        }

        val book = state.book
        Scaffold(
            topBar = { AdminTopBar(book?.title ?: if (state.bookId == null) "New book" else "Book", onBack) },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            when {
                state.bookId != null && book == null && state.loading -> LoadingBox(Modifier.padding(padding))
                state.bookId != null && book == null -> ErrorState(state.error ?: "", Modifier.padding(padding), onRetry = vm::load)
                else -> Column(
                    Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (book != null) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            BookChip(book.status)
                            Text(
                                if (book.availableToday) "Visible in the catalogue today." else "Not visible in the catalogue today.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    key(book?.version) {
                        MetadataForm(
                            book = book,
                            categories = state.categories,
                            status = state.save,
                            uploadProgress = state.uploadProgress.takeIf { book == null },
                            onCreate = { meta, pdf, cover -> vm.create(meta, pdf, cover, resolver) },
                            onUpdate = vm::update,
                        )
                    }
                    if (book != null) {
                        CoverCard(book, state.cover) { vm.replaceCover(it, resolver) }
                        VersionsCard(book, state.versions, state.newVersion, state.uploadProgress, vm, resolver)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (book.availableToday) OutlinedButton(onClick = { onOpenPublic(book.id) }) { Text("Public page") }
                            if (book.status != "ARCHIVED") {
                                OutlinedButton(onClick = { confirmArchive = true }) {
                                    Text("Archive", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        Text(
                            "Created ${formatDate(book.createdAt)} · updated ${formatDate(book.updatedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (confirmArchive && book != null) {
            ConfirmDialog(
                title = "Archive this book?",
                body = "\"${book.title}\" will be removed from the catalogue and members will no longer be able to read or " +
                    "download it. Its files and history are kept. This cannot be undone here.",
                confirmLabel = "Archive",
                busy = state.archive.busy,
                error = state.archive.error?.let { adminErrorMessage(it) },
                danger = true,
                onConfirm = { vm.archive { confirmArchive = false } },
                onDismiss = { confirmArchive = false },
            )
        }
    }
}

@Composable
private fun MetadataForm(
    book: AdminBookDetail?,
    categories: List<AdminCategory>,
    status: FormStatus,
    uploadProgress: Float?,
    onCreate: (BookMetadata, PickedFile, PickedFile?) -> Unit,
    onUpdate: (BookMetadata) -> Unit,
) {
    val archived = book?.status == "ARCHIVED"
    var title by rememberSaveable { mutableStateOf(book?.title.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(book?.description.orEmpty()) }
    var categoryId by rememberSaveable { mutableStateOf(book?.category?.id) }
    var effectiveDate by rememberSaveable { mutableStateOf(book?.effectiveDate ?: todayIso()) }
    var expiryDate by rememberSaveable { mutableStateOf(book?.expiryDate) }
    var bookStatus by rememberSaveable { mutableStateOf(book?.status ?: "DRAFT") }
    var pdf by remember { mutableStateOf<PickedFile?>(null) }
    var cover by remember { mutableStateOf<PickedFile?>(null) }

    val pdfError = checkPdf(pdf)
    val coverError = checkImage(cover)
    val badRange = expiryDate != null && expiryDate!! < effectiveDate
    val fields = status.error.fields()
    val canSave = !archived && title.isNotBlank() && !badRange && (book != null || (pdf != null && pdfError == null)) &&
        coverError == null && !status.busy

    fun metadata() = BookMetadata(
        title = title.trim(),
        description = description.trim().ifBlank { null },
        categoryId = categoryId,
        effectiveDate = effectiveDate,
        expiryDate = expiryDate,
        status = bookStatus,
    )

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (book == null) "Book details" else "Details", style = MaterialTheme.typography.titleMedium)
            if (archived) Notice("This book is archived. Archived books cannot be edited and never appear in the catalogue.")
            if (status.error != null && fields.isEmpty()) Notice(adminErrorMessage(status.error), isError = true)
            OutlinedTextField(
                title, { title = it.take(200) }, label = { Text("Title") }, singleLine = true, enabled = !archived,
                isError = fields["title"] != null, supportingText = fields["title"]?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownField(
                "Category", categoryId,
                listOf(null to "None") + categories.filter { it.active || it.id == categoryId }.map { it.id to it.name + if (it.active) "" else " (inactive)" },
                { categoryId = it }, enabled = !archived,
            )
            OutlinedTextField(
                description, { description = it.take(10000) }, label = { Text("Description") }, minLines = 3, enabled = !archived,
                isError = fields["description"] != null, supportingText = { Text(fields["description"] ?: "${description.length}/10000") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (book == null) {
                FilePickField(
                    "PDF", arrayOf("application/pdf"), pdf, { pdf = it }, "Required. Max 100 MB.", pdfError, enabled = !status.busy,
                )
                FilePickField(
                    "Cover image", arrayOf("image/jpeg", "image/png", "image/webp"), cover, { cover = it },
                    "Optional. JPEG, PNG or WebP, max 5 MB.", coverError, enabled = !status.busy,
                )
            }
            DateField(
                "Effective date", effectiveDate, { if (it != null) effectiveDate = it }, enabled = !archived,
                isError = fields["effectiveDate"] != null,
                supportingText = fields["effectiveDate"] ?: "First day it can appear in the catalogue.",
            )
            DateField(
                "Expiry date", expiryDate, { expiryDate = it }, clearable = true, enabled = !archived,
                isError = badRange || fields["expiryDate"] != null,
                supportingText = if (badRange) "Must be on or after the effective date." else fields["expiryDate"] ?: "Leave empty for no expiry.",
            )
            if (!archived) {
                Text("Status", style = MaterialTheme.typography.labelLarge)
                EDITABLE_BOOK_STATUSES.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { bookStatus = s },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = bookStatus == s, onClick = { bookStatus = s })
                        Column {
                            Text(titleCase(s))
                            Text(statusHint(s), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (uploadProgress != null) {
                LinearProgressIndicator(progress = { uploadProgress }, modifier = Modifier.fillMaxWidth())
                Text(
                    if (uploadProgress < 1f) "Uploading… ${(uploadProgress * 100).toInt()}%" else "Checking the file…",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!archived) {
                Button(
                    onClick = { if (book == null) onCreate(metadata(), pdf!!, cover) else onUpdate(metadata()) },
                    enabled = canSave,
                ) {
                    Text(
                        when {
                            status.busy -> if (book == null) "Uploading…" else "Saving…"
                            book == null -> "Create book"
                            else -> "Save details"
                        },
                    )
                }
            }
        }
    }
}

private fun statusHint(status: String) = when (status) {
    "DRAFT" -> "Not visible to members."
    "PUBLISHED" -> "Visible in the catalogue between the effective and expiry dates."
    else -> "Temporarily hidden; can be published again."
}

@Composable
private fun CoverCard(book: AdminBookDetail, status: FormStatus, onUpload: (PickedFile) -> Unit) {
    var file by remember { mutableStateOf<PickedFile?>(null) }
    val error = checkImage(file)
    LaunchedEffect(status.done) { if (status.done) file = null }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cover", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BookCover(book.coverUrl, book.title, Modifier.width(96.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status.error != null) Notice(adminErrorMessage(status.error), isError = true)
                    FilePickField(
                        if (book.coverUrl != null) "Replace cover" else "Add cover",
                        arrayOf("image/jpeg", "image/png", "image/webp"), file, { file = it },
                        "JPEG, PNG or WebP, max 5 MB.", error, enabled = !status.busy && book.status != "ARCHIVED",
                    )
                    Button(onClick = { file?.let(onUpload) }, enabled = file != null && error == null && !status.busy) {
                        Text(if (status.busy) "Uploading…" else "Upload cover")
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionsCard(
    book: AdminBookDetail,
    versions: List<BookVersion>,
    status: FormStatus,
    uploadProgress: Float?,
    vm: BookEditViewModel,
    resolver: ContentResolver,
) {
    var pdf by remember { mutableStateOf<PickedFile?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf<BookVersion?>(null) }
    val pdfError = checkPdf(pdf)
    LaunchedEffect(status.done) {
        if (status.done) {
            pdf = null
            note = ""
        }
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val v = saving
        if (uri != null && v != null) vm.downloadVersion(v, uri, resolver)
        saving = null
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("PDF versions", style = MaterialTheme.typography.titleMedium)
            Text(
                "Uploading a new version makes it current. Previous versions are kept and can be downloaded here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            versions.forEach { v ->
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("v${v.versionNumber}", fontWeight = FontWeight.SemiBold)
                            if (v.current) StatusChip("Current", MaterialTheme.colorScheme.primary)
                        }
                        Text(v.originalFilename, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(formatBytes(v.fileSize), v.pageCount?.let { "$it pages" }, formatDate(v.createdAt)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        v.changeNote?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    IconButton(onClick = {
                        saving = v
                        saveLauncher.launch(v.originalFilename.ifBlank { "book.pdf" })
                    }) { androidx.compose.material3.Icon(DownloadIcon, contentDescription = "Download version ${v.versionNumber}") }
                }
            }
            if (book.status != "ARCHIVED") {
                HorizontalDivider()
                Text("Upload a new version", fontWeight = FontWeight.SemiBold)
                if (status.error != null) Notice(adminErrorMessage(status.error), isError = true)
                FilePickField("PDF", arrayOf("application/pdf"), pdf, { pdf = it }, "Max 100 MB.", pdfError, enabled = !status.busy)
                OutlinedTextField(
                    note, { note = it.take(1000) }, label = { Text("Change note (optional)") },
                    placeholder = { Text("e.g. Fixed typos in songs 12 and 40") }, enabled = !status.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (status.busy && uploadProgress != null) LinearProgressIndicator(progress = { uploadProgress }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { pdf?.let { vm.addVersion(it, note.trim(), resolver) } }, enabled = pdf != null && pdfError == null && !status.busy) {
                    Text(
                        when {
                            !status.busy -> "Upload version"
                            uploadProgress != null && uploadProgress < 1f -> "Uploading ${(uploadProgress * 100).toInt()}%"
                            else -> "Checking…"
                        },
                    )
                }
            }
        }
    }
}
