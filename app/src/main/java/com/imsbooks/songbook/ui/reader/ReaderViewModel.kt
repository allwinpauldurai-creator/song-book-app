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
import com.imsbooks.songbook.data.SaveProgressRequest
import com.imsbooks.songbook.data.apiCall
import com.imsbooks.songbook.data.userMessage
import com.imsbooks.songbook.ui.book.eligibilityMessage
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

data class ReaderState(
    val title: String = "",
    /** -1 = indeterminate download, 0..1 = download progress, null = not downloading. */
    val downloadProgress: Float? = -1f,
    val pageCount: Int = 0,
    /** Zero-based page to open at (saved progress). */
    val initialPage: Int = 0,
    val ready: Boolean = false,
    val error: String? = null,
)

/**
 * Opens a book with Android's PdfRenderer. Pages are rendered on demand off the main thread (the
 * renderer allows one open page at a time, hence the mutex) and a few bitmaps are cached.
 * Reading progress is saved 1.5 s after the page settles, and once more when leaving the reader.
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
        _state.update { it.copy(error = null, downloadProgress = -1f) }
        viewModelScope.launch {
            launch {
                runCatching { apiCall(c.json) { c.api.book(bookId) } }.getOrNull()?.let { b -> _state.update { it.copy(title = b.title) } }
            }
            try {
                val progress = launchProgressLookup()
                val opened = c.pdfRepository.openForReading(bookId) { p -> _state.update { it.copy(downloadProgress = p) } }
                val count = withContext(Dispatchers.IO) {
                    renderLock.withLock {
                        closeRenderer()
                        fd = ParcelFileDescriptor.open(opened.file, ParcelFileDescriptor.MODE_READ_ONLY)
                        renderer = PdfRenderer(fd!!)
                        renderer!!.pageCount
                    }
                }
                val saved = progress.await()
                val start = ((saved ?: 1) - 1).coerceIn(0, (count - 1).coerceAtLeast(0))
                currentPage = start
                lastSaved = saved?.minus(1) ?: -1
                _state.update { it.copy(pageCount = count, initialPage = start, ready = true, downloadProgress = null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SecurityException) {
                _state.update { it.copy(error = "This PDF is password protected and cannot be opened.", downloadProgress = null) }
            } catch (e: Exception) {
                val message = if (e is java.io.IOException) "The book could not be opened. ${e.userMessage()}" else eligibilityMessage(e)
                _state.update { it.copy(error = message, downloadProgress = null) }
            }
        }
    }

    /** Last page read (1-based) or null. Never fails: progress is a convenience. */
    private fun launchProgressLookup() = kotlinx.coroutines.CompletableDeferred<Int?>().also { deferred ->
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
        if (index == lastSaved || !_state.value.ready) return
        try {
            apiCall(c.json) { c.api.saveProgress(bookId, SaveProgressRequest(index + 1)) }
            lastSaved = index
        } catch (_: Exception) {
            // Progress is best effort; the next page turn retries.
        }
    }

    override fun onCleared() {
        val page = currentPage
        if (_state.value.ready && page != lastSaved) {
            c.applicationScope.launch { runCatching { c.api.saveProgress(bookId, SaveProgressRequest(page + 1)) } }
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
