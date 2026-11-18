package com.imsbooks.songbook.data

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * Member PDF access, same two steps as the web reader (ARCHITECTURE.md §5): an eligible member gets a
 * 60-second ticket, then fetches /content?ticket=... . Android's PdfRenderer needs a seekable file, so
 * the book is downloaded into the app's private cache (sandboxed, not backed up). Only the most recent
 * book is kept, keyed by version, so reopening it skips the download but still re-checks eligibility.
 */
class PdfRepository(
    private val api: SongBookApi,
    private val http: OkHttpClient,
    private val json: Json,
    private val apiBaseUrl: String,
    cacheRoot: File,
) {
    private val dir = File(cacheRoot, "books").apply { mkdirs() }

    data class OpenedPdf(val file: File, val ticket: AccessTicket)

    /** Downloads (or reuses) the current version of a book for reading. [onProgress] gets 0..1, or -1 if unknown. */
    suspend fun openForReading(bookId: String, onProgress: (Float) -> Unit): OpenedPdf {
        val ticket = apiCall(json) { api.readAccess(bookId) }
        val target = File(dir, "${safe(bookId)}-${safe(ticket.versionId)}.pdf")
        if (!target.exists() || target.length() == 0L) {
            dir.listFiles()?.forEach { it.delete() } // keep only one book on the device
            val partial = File(dir, target.name + ".part")
            try {
                partial.outputStream().use { out -> fetch(ticket, out, onProgress) }
                if (!partial.renameTo(target)) throw IOException("Could not store the book")
            } finally {
                partial.delete()
            }
        }
        return OpenedPdf(target, ticket)
    }

    /** Saves the PDF (download ticket) to a location the member picked with the system file picker. */
    suspend fun download(bookId: String, destination: Uri, resolver: ContentResolver, onProgress: (Float) -> Unit) {
        val ticket = apiCall(json) { api.downloadAccess(bookId) }
        withContext(Dispatchers.IO) {
            val out = resolver.openOutputStream(destination, "wt") ?: throw IOException("Cannot write the file")
            out.use { fetch(ticket, it, onProgress) }
        }
    }

    fun clearCache() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private suspend fun fetch(ticket: AccessTicket, out: OutputStream, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val url = if (ticket.contentUrl.startsWith("http")) ticket.contentUrl else apiBaseUrl + ticket.contentUrl
            val call = http.newCall(Request.Builder().url(url).build())
            val response = try {
                call.execute()
            } catch (e: IOException) {
                throw ApiException(0, "NETWORK_ERROR")
            }
            response.use { res ->
                if (!res.isSuccessful) {
                    val body = try {
                        res.body.string().let { json.decodeFromString(ApiErrorBody.serializer(), it) }
                    } catch (_: Exception) {
                        null
                    }
                    throw ApiException(res.code, body?.code ?: "HTTP_${res.code}", body?.message)
                }
                val total = res.body.contentLength()
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                res.body.byteStream().use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        onProgress(if (total > 0) done.toFloat() / total else -1f)
                    }
                }
            }
        }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9-]"), "_")
}
