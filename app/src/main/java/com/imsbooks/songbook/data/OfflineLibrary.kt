package com.imsbooks.songbook.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One book saved on the device for offline reading. Times are epoch milliseconds. */
@Serializable
data class OfflineBook(
    val bookId: String,
    val title: String,
    val versionId: String,
    val versionNumber: Int = 0,
    val pageCount: Int? = null,
    val sizeBytes: Long = 0,
    val downloadedAt: Long,
    /** Last time the server confirmed this member may still read this book. */
    val verifiedAt: Long,
    /** Last page read (1-based), kept locally so offline reading resumes; synced when online. */
    val lastPage: Int? = null,
    /** A newer PDF version exists on the server (seen when opening the book online). */
    val updateAvailable: Boolean = false,
) {
    fun verifiedRecently(now: Long = System.currentTimeMillis()) = now - verifiedAt <= OfflineLibrary.OFFLINE_GRACE_MS
}

@Serializable
private data class OfflineIndex(val ownerUserId: String? = null, val books: List<OfflineBook> = emptyList())

/**
 * Books downloaded for offline reading ("Downloads" tab). Files live in the app's private storage
 * (sandboxed, excluded from backups) and belong to one account:
 *
 * - Logging out deletes them; signing in as a different user deletes the previous user's copies.
 * - An expired session keeps them (hidden) until the same user signs in again.
 * - Like the web app, eligibility is the server's call: a copy is re-verified whenever the member is online
 *   (book still available, membership valid). Offline reading is allowed for [OFFLINE_GRACE_MS] after the
 *   last successful check, then the app asks to go online once. A withdrawn book is deleted.
 */
class OfflineLibrary(
    private val api: SongBookApi,
    private val json: Json,
    private val pdfs: PdfRepository,
    private val http: OkHttpClient,
    private val absoluteUrl: (String?) -> String?,
    filesRoot: File,
) {
    private val dir = File(filesRoot, "offline").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val mutex = Mutex()

    private var index: OfflineIndex = load()
    private val _books = MutableStateFlow(index.books)
    val books: StateFlow<List<OfflineBook>> = _books.asStateFlow()

    /** bookId -> 0..1 (or -1 when the size is unknown) while a download runs. */
    private val _downloading = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloading: StateFlow<Map<String, Float>> = _downloading.asStateFlow()

    val ownerUserId: String? get() = index.ownerUserId

    fun get(bookId: String): OfflineBook? = _books.value.firstOrNull { it.bookId == bookId }

    fun pdfFile(bookId: String): File = File(dir, "${safe(bookId)}.pdf")

    fun coverFile(bookId: String): File = File(dir, "${safe(bookId)}.cover")

    fun totalBytes(): Long = _books.value.sumOf { it.sizeBytes }

    /** Downloads the current version (download ticket, so it is audited as a download like on the web). */
    suspend fun download(userId: String, bookId: String, title: String, coverUrl: String?) {
        if (_downloading.value.containsKey(bookId)) return
        _downloading.update { it + (bookId to -1f) }
        try {
            mutex.withLock { claimFor(userId) }
            val ticket = apiCall(json) { api.downloadAccess(bookId) }
            val partial = File(dir, "${safe(bookId)}.part")
            try {
                withContext(Dispatchers.IO) {
                    partial.outputStream().use { out ->
                        pdfs.fetch(ticket, out) { p -> _downloading.update { it + (bookId to p) } }
                    }
                }
                val target = pdfFile(bookId)
                if (!partial.renameTo(target)) {
                    target.delete()
                    if (!partial.renameTo(target)) throw IOException("Could not store the book")
                }
            } finally {
                partial.delete()
            }
            saveCover(bookId, coverUrl)
            val now = System.currentTimeMillis()
            mutex.withLock {
                val previous = get(bookId)
                upsert(
                    OfflineBook(
                        bookId = bookId,
                        title = title,
                        versionId = ticket.versionId,
                        versionNumber = ticket.versionNumber,
                        pageCount = ticket.pageCount,
                        sizeBytes = pdfFile(bookId).length(),
                        downloadedAt = now,
                        verifiedAt = now,
                        lastPage = previous?.lastPage,
                    ),
                )
            }
        } finally {
            _downloading.update { it - bookId }
        }
    }

    suspend fun delete(bookId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            pdfFile(bookId).delete()
            coverFile(bookId).delete()
        }
        index = index.copy(books = index.books.filterNot { it.bookId == bookId })
        persist()
    }

    /** Log out: remove every downloaded book. */
    suspend fun deleteAll() = mutex.withLock {
        withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }
        index = OfflineIndex()
        persist()
    }

    /** Called when a user signs in: another account's downloads must not stay on the device. */
    suspend fun onSignedIn(user: Me) = mutex.withLock { claimFor(user.id) }

    /**
     * Re-checks every copy while online and signed in: deleted when the book was withdrawn (404),
     * marked verified when it is still available and the member may read. Network errors change nothing.
     */
    suspend fun revalidate(user: Me) {
        if (!user.canRead) return
        for (book in _books.value) {
            val stillAvailable = try {
                apiCall(json) { api.book(book.bookId) }
                true
            } catch (e: ApiException) {
                if (e.status == 404) false else continue
            }
            if (stillAvailable) markVerified(book.bookId) else delete(book.bookId)
        }
    }

    suspend fun markVerified(bookId: String, currentVersionId: String? = null) = mutex.withLock {
        val book = get(bookId) ?: return@withLock
        upsert(
            book.copy(
                verifiedAt = System.currentTimeMillis(),
                updateAvailable = if (currentVersionId != null) currentVersionId != book.versionId else book.updateAvailable,
            ),
        )
    }

    suspend fun saveLastPage(bookId: String, page: Int) = mutex.withLock {
        val book = get(bookId) ?: return@withLock
        if (book.lastPage != page) upsert(book.copy(lastPage = page))
    }

    // ------------------------------------------------------------------ internals

    private suspend fun claimFor(userId: String) {
        if (index.ownerUserId == userId) return
        if (index.ownerUserId != null) withContext(Dispatchers.IO) { dir.listFiles()?.forEach { if (it != indexFile) it.delete() } }
        index = OfflineIndex(ownerUserId = userId)
        persist()
    }

    private suspend fun upsert(book: OfflineBook) {
        index = index.copy(books = index.books.filterNot { it.bookId == book.bookId } + book)
        persist()
    }

    private suspend fun persist() {
        _books.value = index.books.sortedBy { it.title.lowercase() }
        withContext(Dispatchers.IO) {
            val tmp = File(dir, "index.json.tmp")
            tmp.writeText(json.encodeToString(OfflineIndex.serializer(), index))
            if (!tmp.renameTo(indexFile)) {
                indexFile.delete()
                tmp.renameTo(indexFile)
            }
        }
    }

    private fun load(): OfflineIndex {
        val loaded = try {
            if (indexFile.exists()) json.decodeFromString(OfflineIndex.serializer(), indexFile.readText()) else OfflineIndex()
        } catch (_: Exception) {
            OfflineIndex()
        }
        // Drop entries whose file is gone (e.g. storage cleared by the system).
        return loaded.copy(books = loaded.books.filter { pdfFile(it.bookId).length() > 0 })
    }

    /** Best effort: the cover is only decoration. */
    private suspend fun saveCover(bookId: String, coverUrl: String?) = withContext(Dispatchers.IO) {
        val url = absoluteUrl(coverUrl) ?: return@withContext
        try {
            val client = http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (res.isSuccessful) coverFile(bookId).outputStream().use { res.body.byteStream().copyTo(it) }
            }
        } catch (_: Exception) {
            coverFile(bookId).delete()
        }
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9-]"), "_")

    companion object {
        /** How long a copy can be read without the server re-confirming access. */
        const val OFFLINE_GRACE_DAYS = 30L
        val OFFLINE_GRACE_MS: Long = TimeUnit.DAYS.toMillis(OFFLINE_GRACE_DAYS)
    }
}
