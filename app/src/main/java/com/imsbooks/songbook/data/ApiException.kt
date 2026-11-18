package com.imsbooks.songbook.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/** Any failed API call, normalised like the web client's ApiError (status 0 = network failure). */
class ApiException(
    val status: Int,
    val code: String,
    serverMessage: String? = null,
    val fieldErrors: List<FieldError> = emptyList(),
) : Exception(serverMessage?.takeIf { it.isNotBlank() } ?: defaultMessage(status)) {

    /** Field-level messages keyed by field name, for form supporting text. */
    val fieldMap: Map<String, String> get() = fieldErrors.associate { it.field to it.message }

    companion object {
        fun defaultMessage(status: Int): String = when {
            status == 0 -> "Cannot reach the server. Check your connection and try again."
            status == 429 -> "Too many attempts. Please wait a few minutes and try again."
            status >= 500 -> "Something went wrong on our side. Please try again."
            else -> "The request could not be completed."
        }

        fun from(response: Response<*>, json: Json): ApiException {
            val body = try {
                response.errorBody()?.string()?.let { json.decodeFromString(ApiErrorBody.serializer(), it) }
            } catch (_: Exception) {
                null
            }
            return ApiException(
                status = response.code(),
                code = body?.code?.takeIf { it.isNotBlank() } ?: "HTTP_${response.code()}",
                serverMessage = body?.message,
                fieldErrors = body?.fieldErrors.orEmpty(),
            )
        }
    }
}

/** Runs a Retrofit call and turns HTTP and network failures into [ApiException]. */
suspend fun <T> apiCall(json: Json, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: ApiException) {
    throw e
} catch (e: HttpException) {
    throw e.response()?.let { ApiException.from(it, json) } ?: ApiException(e.code(), "HTTP_${e.code()}")
} catch (e: IOException) {
    throw ApiException(0, "NETWORK_ERROR")
}

/** For endpoints declared as Response<T> (204 bodies, 404-as-null). */
fun <T> Response<T>.orThrow(json: Json): Response<T> = if (isSuccessful) this else throw ApiException.from(this, json)

/** User-facing message for any thrown value. */
fun Throwable.userMessage(): String = when (this) {
    is ApiException -> message ?: ApiException.defaultMessage(status)
    else -> message ?: "Something went wrong. Please try again."
}
