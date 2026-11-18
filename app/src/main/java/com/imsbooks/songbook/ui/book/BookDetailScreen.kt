package com.imsbooks.songbook.ui.book

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.imsbooks.songbook.AppContainer
import com.imsbooks.songbook.data.ApiException
import com.imsbooks.songbook.data.BookDetail
import com.imsbooks.songbook.data.Me
import com.imsbooks.songbook.data.apiCall
import com.imsbooks.songbook.data.session.SessionState
import com.imsbooks.songbook.data.userMessage
import com.imsbooks.songbook.ui.appContainer
import com.imsbooks.songbook.ui.appViewModel
import com.imsbooks.songbook.ui.components.BookCover
import com.imsbooks.songbook.ui.components.BookIcon
import com.imsbooks.songbook.ui.components.DownloadIcon
import com.imsbooks.songbook.ui.components.EmptyState
import com.imsbooks.songbook.ui.components.ErrorState
import com.imsbooks.songbook.ui.components.LoadingBox
import com.imsbooks.songbook.ui.components.Notice
import com.imsbooks.songbook.util.formatDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BookDetailState(
    val book: BookDetail? = null,
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val error: String? = null,
    /** null = idle, -1 = indeterminate, 0..1 = progress. */
    val downloadProgress: Float? = null,
    val message: String? = null,
)

class BookDetailViewModel(private val c: AppContainer, private val bookId: String) : ViewModel() {
    private val _state = MutableStateFlow(BookDetailState())
    val state: StateFlow<BookDetailState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val book = apiCall(c.json) { c.api.book(bookId) }
                _state.update { it.copy(book = book, loading = false) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, notFound = e.status == 404, error = e.userMessage()) }
            }
        }
    }

    fun download(destination: Uri, resolver: ContentResolver) {
        _state.update { it.copy(downloadProgress = -1f) }
        viewModelScope.launch {
            try {
                c.pdfRepository.download(bookId, destination, resolver) { p ->
                    _state.update { it.copy(downloadProgress = p) }
                }
                _state.update { it.copy(downloadProgress = null, message = "Download complete.") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { android.provider.DocumentsContract.deleteDocument(resolver, destination) }
                _state.update { it.copy(downloadProgress = null, message = eligibilityMessage(e)) }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    /** Keeps the book in the app for offline reading (Downloads tab). Runs on, even if the screen closes. */
    fun saveOffline(userId: String, book: BookDetail) {
        c.applicationScope.launch {
            try {
                c.offline.download(userId, book.id, book.title, book.coverUrl)
                _state.update { it.copy(message = "Downloaded. \"${book.title}\" now opens without a connection.") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = "Download failed. ${eligibilityMessage(e)}") }
            }
        }
    }

    fun removeOffline() {
        viewModelScope.launch {
            c.offline.delete(bookId)
            _state.update { it.copy(message = "Removed from this phone.") }
        }
    }
}

/** Maps the 403 eligibility codes of /read-access and /download-access to friendly text. */
fun eligibilityMessage(e: Throwable): String = when ((e as? ApiException)?.code) {
    "MEMBERSHIP_INVALID" -> "Your membership is not active. Please contact an administrator."
    "ACCOUNT_INACTIVE" -> "Your account is not active. Please contact an administrator."
    else -> if ((e as? ApiException)?.status == 404) "This book is no longer available." else e.userMessage()
}

private fun membershipText(user: Me): String = when (user.membership?.status) {
    null -> "You do not have a membership yet."
    "SUSPENDED" -> "Your membership is suspended."
    "CANCELLED" -> "Your membership was cancelled."
    else -> "Your membership has expired."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    bookId: String,
    onBack: () -> Unit,
    onRead: (String) -> Unit,
    onLogin: (readBookId: String) -> Unit,
    onRegister: () -> Unit,
    onSuggestCorrection: (bookId: String, title: String) -> Unit,
) {
    val vm = appViewModel(key = "book-$bookId") { BookDetailViewModel(it, bookId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val session by appContainer().session.state.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    val snackbar = remember { SnackbarHostState() }
    val offlineBooks by appContainer().offline.books.collectAsStateWithLifecycle()
    val offlineProgress by appContainer().offline.downloading.collectAsStateWithLifecycle()

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) vm.download(uri, resolver)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.book?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val book = state.book
        when {
            state.loading && book == null -> LoadingBox(Modifier.padding(padding))
            state.notFound -> EmptyState(
                "Book not found",
                "It may have been removed or is not available yet.",
                Modifier.padding(padding),
            )
            book == null -> ErrorState(state.error ?: "", Modifier.padding(padding), onRetry = vm::load)
            else -> Column(
                Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BookCover(
                    book.coverUrl,
                    book.title,
                    Modifier.widthIn(max = 220.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
                )
                Text(book.title, style = MaterialTheme.typography.headlineMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    book.category?.let { AssistChip(onClick = {}, label = { Text(it.name) }) }
                    book.pageCount?.let { SuggestionChip(onClick = {}, label = { Text("$it pages") }) }
                    book.expiryDate?.let {
                        SuggestionChip(onClick = {}, label = { Text("Available until ${formatDate(it)}") })
                    }
                }
                book.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }

                when (val s = session) {
                    is SessionState.SignedIn -> {
                        if (!s.user.canRead) {
                            Notice("${membershipText(s.user)} Please contact an administrator to read this book.")
                        } else {
                            Button(onClick = { onRead(book.id) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(BookIcon, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text("Read")
                            }
                            OfflineButton(
                                saved = offlineBooks.firstOrNull { it.bookId == book.id },
                                progress = offlineProgress[book.id],
                                onDownload = { vm.saveOffline(s.user.id, book) },
                                onRemove = vm::removeOffline,
                            )
                            TextButton(
                                onClick = { saveLauncher.launch(safeFileName(book.title)) },
                                enabled = state.downloadProgress == null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (state.downloadProgress == null) "Save a copy of the PDF to Files" else "Saving the PDF…")
                            }
                            state.downloadProgress?.let { p ->
                                if (p < 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                                else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    SessionState.Loading -> LoadingBox()
                    SessionState.Offline -> if (offlineBooks.any { it.bookId == book.id }) {
                        Button(onClick = { onRead(book.id) }, modifier = Modifier.fillMaxWidth()) { Text("Read offline") }
                    } else {
                        Notice("You are offline. Connect to the internet to read or download this book.")
                    }
                    else -> {
                        Button(onClick = { onLogin(book.id) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Lock, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text("Log in to read")
                        }
                        TextButton(onClick = onRegister, modifier = Modifier.fillMaxWidth()) {
                            Text("Not a member? Create an account")
                        }
                    }
                }

                Text(
                    "Last updated ${formatDate(book.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (session is SessionState.SignedIn) {
                    TextButton(onClick = { onSuggestCorrection(book.id, book.title) }) { Text("Suggest a correction") }
                }
            }
        }
    }
}

/** "Download for offline" / progress / "Downloaded" with remove and update. */
@Composable
private fun OfflineButton(
    saved: com.imsbooks.songbook.data.OfflineBook?,
    progress: Float?,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            progress != null -> {
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text(if (progress < 0) "Downloading…" else "Downloading… ${(progress * 100).toInt()}%")
                }
                if (progress < 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            saved == null -> OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Icon(DownloadIcon, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Download for offline")
            }
            else -> {
                Notice(
                    if (saved.updateAvailable) "Downloaded (version ${saved.versionNumber}). A newer version is available."
                    else "Downloaded on this phone. It opens without a connection.",
                )
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (saved.updateAvailable) OutlinedButton(onClick = onDownload) { Text("Update download") }
                    TextButton(onClick = onRemove) { Text("Remove download") }
                }
            }
        }
    }
}

private fun safeFileName(title: String): String =
    title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "song-book" }.take(80) + ".pdf"
