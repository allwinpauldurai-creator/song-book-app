package com.imsbooks.songbook.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.text.DecimalFormat

/** A file the user picked with the system picker (Storage Access Framework). */
data class PickedFile(val uri: Uri, val name: String, val size: Long, val mimeType: String?)

fun ContentResolver.describe(uri: Uri): PickedFile {
    var name = "file"
    var size = -1L
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { i -> c.getString(i)?.let { name = it } }
            c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
        }
    }
    return PickedFile(uri, name, size, getType(uri))
}

/** Same quick checks as the web FilePicker; the backend still validates magic bytes and parses every file. */
fun checkPdf(f: PickedFile?): String? = when {
    f == null -> null
    f.mimeType != null && f.mimeType != "application/pdf" && !f.name.lowercase().endsWith(".pdf") -> "Choose a PDF file."
    f.size > PDF_MAX_BYTES -> "PDF is ${formatBytes(f.size)}; the limit is 100 MB."
    f.size == 0L -> "The file is empty."
    else -> null
}

fun checkImage(f: PickedFile?): String? = when {
    f == null -> null
    f.mimeType !in IMAGE_TYPES -> "Cover must be a JPEG, PNG or WebP image."
    f.size > IMAGE_MAX_BYTES -> "Image is ${formatBytes(f.size)}; the limit is 5 MB."
    else -> null
}

fun formatBytes(n: Long): String {
    if (n < 1024) return "$n B"
    val units = listOf("KB", "MB", "GB")
    var v = n / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return DecimalFormat(if (v < 10) "0.#" else "0").format(v) + " " + units[i]
}

/** Streams a content Uri as a request body, reporting 0..1 progress. Never loads the file into memory. */
class UriRequestBody(
    private val resolver: ContentResolver,
    private val file: PickedFile,
    private val type: MediaType,
    private val onProgress: (Float) -> Unit = {},
) : RequestBody() {
    override fun contentType() = type
    override fun contentLength() = file.size
    override fun writeTo(sink: BufferedSink) {
        val input = resolver.openInputStream(file.uri) ?: throw IOException("Cannot read ${file.name}")
        input.use {
            val buffer = ByteArray(64 * 1024)
            var sent = 0L
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                sink.write(buffer, 0, n)
                sent += n
                if (file.size > 0) onProgress((sent.toFloat() / file.size).coerceAtMost(1f))
            }
        }
    }
}

fun ContentResolver.part(name: String, file: PickedFile, fallbackType: String, onProgress: (Float) -> Unit = {}): MultipartBody.Part =
    MultipartBody.Part.createFormData(
        name,
        file.name,
        UriRequestBody(this, file, (file.mimeType ?: fallbackType).toMediaType(), onProgress),
    )
