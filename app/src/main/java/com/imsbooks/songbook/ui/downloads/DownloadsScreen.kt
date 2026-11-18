package com.imsbooks.songbook.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imsbooks.songbook.data.OfflineBook
import com.imsbooks.songbook.data.OfflineLibrary
import com.imsbooks.songbook.data.formatBytes
import com.imsbooks.songbook.data.session.SessionState
import com.imsbooks.songbook.ui.admin.ConfirmDialog
import com.imsbooks.songbook.ui.appContainer
import com.imsbooks.songbook.ui.components.BookCover
import com.imsbooks.songbook.ui.components.EmptyState
import com.imsbooks.songbook.ui.components.LoadingBox
import com.imsbooks.songbook.ui.components.Notice
import com.imsbooks.songbook.util.formatDate
import kotlinx.coroutines.launch
import java.time.Instant

/** Books saved on this device; they open without a network (see [OfflineLibrary] for the rules). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onRead: (String) -> Unit, onBrowse: () -> Unit, onLogin: () -> Unit) {
    val c = appContainer()
    val session by c.session.state.collectAsStateWithLifecycle()
    val books by c.offline.books.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var removing by remember { mutableStateOf<OfflineBook?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("Downloads") }) }) { padding ->
        val s = session
        when {
            s == SessionState.Loading -> LoadingBox(Modifier.padding(padding))
            // Signed out (session expired): copies stay on the device but open only after signing in again.
            s == SessionState.SignedOut -> Column(
                Modifier.padding(padding).padding(24.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EmptyState(
                    "Read without a connection",
                    if (books.isEmpty()) "Log in, open a book and tap \"Download for offline\" to keep it on this phone."
                    else "Log in again to open the ${books.size} book(s) downloaded on this phone.",
                )
                Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("Log in") }
            }
            books.isEmpty() -> Column(
                Modifier.padding(padding).padding(24.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (s == SessionState.Offline) Notice("You are offline. Books you download open without a connection.")
                EmptyState("No downloads yet", "Open a book and tap \"Download for offline\" to read it without a connection.")
                OutlinedButton(onClick = onBrowse) { Text("Browse books") }
            }
            else -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (s == SessionState.Offline) {
                    item { Notice("You are offline. Your downloaded books still open.") }
                }
                items(books, key = { it.bookId }) { book ->
                    DownloadRow(book, c.offline.coverFile(book.bookId), onOpen = { onRead(book.bookId) }, onRemove = { removing = book })
                    HorizontalDivider()
                }
                item {
                    Text(
                        "${books.size} book(s) · ${formatBytes(books.sumOf { it.sizeBytes })} on this phone. " +
                            "Logging out removes them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }

    removing?.let { book ->
        ConfirmDialog(
            title = "Remove download?",
            body = "\"${book.title}\" will be removed from this phone. You can download it again while online.",
            confirmLabel = "Remove",
            busy = false,
            error = null,
            danger = true,
            onConfirm = {
                scope.launch { c.offline.delete(book.bookId) }
                removing = null
            },
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun DownloadRow(book: OfflineBook, cover: java.io.File, onOpen: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(null, book.title, Modifier.width(48.dp), localFile = cover)
        Column(Modifier.weight(1f)) {
            Text(book.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val details = buildList {
                book.lastPage?.let { add("Page $it" + (book.pageCount?.let { n -> " of $n" } ?: "")) }
                add(formatBytes(book.sizeBytes))
                add("Saved ${formatDate(Instant.ofEpochMilli(book.downloadedAt).toString())}")
            }
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                !book.verifiedRecently() -> Text(
                    "Connect to the internet to keep reading offline",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                book.updateAvailable -> Text(
                    "A newer version is available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove ${book.title}") }
    }
}
