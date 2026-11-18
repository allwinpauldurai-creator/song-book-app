package com.imsbooks.songbook.ui.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.core.graphics.createBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imsbooks.songbook.AppContainer
import com.imsbooks.songbook.data.ApiException
import com.imsbooks.songbook.data.OfflineBook
import com.imsbooks.songbook.data.OfflineLibrary
import com.imsbooks.songbook.data.SaveProgressRequest
import com.imsbooks.songbook.data.apiCall
import com.imsbooks.songbook.data.session.SessionState
import com.imsbooks.songbook.data.userMessage
import com.imsbooks.songbook.ui.book.eligibilityMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class ReaderState(
    val title: String = "",
    /** -1 = indeterminate download, 0..1 = download progress, null = not downloading. */
    val downloadProgress: Float? = -1f,
    val pageCount: Int = 0,
    /** Zero-based page to open at (saved progress). */
    val initialPage: Int = 0,
    val ready: Boolean = false,
    val error: String? = null,
    /** Reading the copy saved on this phone. */
    val offlineCopy: Boolean = false,
    /** Short message above the pages, e.g. a newer version exists. */
    val notice: String? = null,
)

/** Thrown to show a message instead of the book. */
private class ReaderBlocked(message: String) : Exception(message)

/**
 * Opens a book with Android's PdfRenderer. A copy downloaded for offline reading is opened from the phone,
 * and re-checked with the server in the background when online; otherwise the current version is
 * streamed into the cache (ticket flow, same as the web reader).
 *
 * Pages are rendered on demand off the main thread (the renderer allows one open page at a time, hence the
 * mutex) and a few bitmaps are cached. Progress is saved 1.5 s after the page settles and when leaving:
 * on the phone for downloaded books, and on the server whenever it can be reached.
 */
class ReaderViewModel(private val c: AppContainer, private val bookId: String) : ViewModel() {

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private var fd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val renderLock = Mutex()
    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private var saveJob: Job? = null
    private var lastSaved = -1
    private var currentPage = 0

    init {
        open()
    }

    fun open() {
        _state.update { it.copy(error = null, downloadProgress = -1f, notice = null) }
        viewModelScope.launch {
            try {
                val saved = c.offline.get(bookId)
                if (saved != null) openDownloaded(saved) else openOnline()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ReaderBlocked) {
                _state.update { it.copy(error = e.message, downloadProgress = null) }
            } catch (e: SecurityException) {
                _state.update { it.copy(error = "This PDF is password protected and cannot be opened.", downloadProgress = null) }
            } catch (e: Exception) {
                val message = if (e is java.io.IOException) "The book could not be opened. ${e.userMessage()}" else eligibilityMessage(e)
                _state.update { it.copy(error = message, downloadProgress = null) }
            }
        }
    }

    private suspend fun openOnline() {
        launchTitleLookup()
        val progress = launchProgressLookup()
        val opened = c.pdfRepository.openForReading(bookId) { p -> _state.update { it.copy(downloadProgress = p) } }
        val count = openRenderer(opened.file)
        finishOpening(count, progress.await())
    }

    private suspend fun openDownloaded(saved: OfflineBook) {
        _state.update { it.copy(title = saved.title, offlineCopy = true) }
        val session = c.session.state.value
        if (session == SessionState.SignedOut) throw ReaderBlocked("Log in again to open your downloaded books.")
        val user = (session as? SessionState.SignedIn)?.user
        if (user != null && !user.canRead) throw ReaderBlocked(eligibilityText(user.membership?.status))

        val online = session is SessionState.SignedIn
        if (!saved.verifiedRecently()) {
            // Past the offline period: the server must confirm access once before the copy opens again.
            when (if (online) verify() else Verified.Unreachable) {
                Verified.Ok -> Unit
                Verified.Blocked -> return
                Verified.Unreachable -> throw ReaderBlocked(
                    "Connect to the internet to keep reading \"${saved.title}\". Downloaded books are re-checked " +
                        "at least every ${OfflineLibrary.OFFLINE_GRACE_DAYS} days.",
                )
            }
        } else if (online) {
            viewModelScope.launch { verify() }
        }

        val progress = if (online) launchProgressLookup() else CompletableDeferred<Int?>(null)
        val count = openRenderer(c.offline.pdfFile(bookId))
        finishOpening(count, progress.await() ?: saved.lastPage)
    }

    private enum class Verified { Ok, Unreachable, Blocked }

    /**
     * Confirms with the server that the member may still read this book (read ticket, same check as online
     * reading). Access withdrawn: the reader closes with the reason; book gone: the copy is deleted.
     */
    private suspend fun verify(): Verified = try {
        val ticket = apiCall(c.json) { c.api.readAccess(bookId) }
        c.offline.markVerified(bookId, ticket.versionId)
        if (c.offline.get(bookId)?.updateAvailable == true) {
            _state.update { it.copy(notice = "A newer version of this book is available. Update the download on the book page.") }
        }
        Verified.Ok
    } catch (e: ApiException) {
        when {
            e.status == 0 || e.status >= 500 -> Verified.Unreachable
            e.status == 404 -> {
                c.offline.delete(bookId)
                block("This book is no longer available, so the downloaded copy was removed.")
            }
            e.status == 403 -> block(eligibilityMessage(e))
            else -> Verified.Unreachable
        }
    }

    /** Closes the book and shows why. */
    private suspend fun block(message: String): Verified {
        renderLock.withLock { closeRenderer() }
        cache.evictAll()
        _state.update { it.copy(ready = false, error = message, downloadProgress = null) }
        return Verified.Blocked
    }

    private suspend fun openRenderer(file: File): Int = withContext(Dispatchers.IO) {
        renderLock.withLock {
            closeRenderer()
            fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(fd!!)
            renderer!!.pageCount
        }
    }

    private fun finishOpening(count: Int, savedPage: Int?) {
        val start = ((savedPage ?: 1) - 1).coerceIn(0, (count - 1).coerceAtLeast(0))
        currentPage = start
        lastSaved = savedPage?.minus(1) ?: -1
        _state.update { it.copy(pageCount = count, initialPage = start, ready = true, downloadProgress = null) }
    }

    private fun launchTitleLookup() {
        viewModelScope.launch {
            runCatching { apiCall(c.json) { c.api.book(bookId) } }.getOrNull()?.let { b -> _state.update { it.copy(title = b.title) } }
        }
    }

    /** Last page read (1-based) or null. Never fails: progress is a convenience. */
    private fun launchProgressLookup() = CompletableDeferred<Int?>().also { deferred ->
        viewModelScope.launch {
            val page = try {
                val res = c.api.progress(bookId)
                if (res.isSuccessful) res.body()?.lastPage else null
            } catch (_: Exception) {
                null
            }
            deferred.complete(page)
        }
    }

    /** Renders a page at [widthPx] wide (height follows the page's aspect ratio). */
    suspend fun renderPage(index: Int, widthPx: Int): Bitmap? {
        val width = widthPx.coerceIn(200, MAX_RENDER_WIDTH)
        val key = "$index@$width"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            renderLock.withLock {
                val r = renderer ?: return@withLock null
                if (index !in 0 until r.pageCount) return@withLock null
                r.openPage(index).use { page ->
                    val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    val bitmap = createBitmap(width, height)
                    bitmap.eraseColor(Color.WHITE) // PdfRenderer draws on a transparent background
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    cache.put(key, bitmap)
                    bitmap
                }
            }
        }
    }

    fun onPageSettled(index: Int) {
        currentPage = index
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DELAY_MS)
            save(index)
        }
    }

    private suspend fun save(index: Int) {
        if (!_state.value.ready) return
        if (_state.value.offlineCopy) c.offline.saveLastPage(bookId, index + 1)
        if (index == lastSaved || c.session.state.value !is SessionState.SignedIn) return
        try {
            apiCall(c.json) { c.api.saveProgress(bookId, SaveProgressRequest(index + 1)) }
            lastSaved = index
        } catch (_: Exception) {
            // Progress is best effort; the next page turn retries.
        }
    }

    override fun onCleared() {
        val page = currentPage
        val s = _state.value
        if (s.ready) {
            c.applicationScope.launch {
                if (s.offlineCopy) c.offline.saveLastPage(bookId, page + 1)
                if (page != lastSaved && c.session.state.value is SessionState.SignedIn) {
                    runCatching { c.api.saveProgress(bookId, SaveProgressRequest(page + 1)) }
                }
            }
        }
        // Close under the lock: a page may still be rendering on an IO thread.
        c.applicationScope.launch { renderLock.withLock { closeRenderer() } }
        cache.evictAll()
    }

    private fun closeRenderer() {
        runCatching { renderer?.close() }
        runCatching { fd?.close() }
        renderer = null
        fd = null
    }

    private companion object {
        const val SAVE_DELAY_MS = 1500L
        const val MAX_RENDER_WIDTH = 2400
        const val MAX_CACHE_BYTES = 64 * 1024 * 1024
    }
}

private fun eligibilityText(status: String?): String = when (status) {
    null -> "You do not have an active membership. Please contact an administrator."
    "SUSPENDED" -> "Your membership is suspended. Please contact an administrator."
    "CANCELLED" -> "Your membership was cancelled. Please contact an administrator."
    else -> "Your membership has expired. Please contact an administrator to renew it."
}
