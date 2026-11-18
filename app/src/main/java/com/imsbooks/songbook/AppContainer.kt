package com.imsbooks.songbook

import android.content.Context
import com.imsbooks.songbook.data.PdfRepository
import com.imsbooks.songbook.data.SongBookApi
import com.imsbooks.songbook.data.session.RefreshCookieJar
import com.imsbooks.songbook.data.session.SecureCookieStorage
import com.imsbooks.songbook.data.session.SecureStore
import com.imsbooks.songbook.data.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** Manual dependency wiring; one instance per process, held by [SongBookApplication]. */
class AppContainer(context: Context) {

    /** API origin for this build flavour, e.g. https://api.songbook.example.org (no trailing slash). */
    val apiBaseUrl: String = BuildConfig.API_BASE_URL.trimEnd('/')

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val cookieJar = RefreshCookieJar(apiBaseUrl.toHttpUrl(), SecureCookieStorage(SecureStore(context)))

    val session = SessionManager(cookieJar)

    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Client for API calls: bearer token + refresh-and-retry on 401. Also used for covers. */
    val httpClient: OkHttpClient = baseClient.newBuilder()
        .addInterceptor(session.authInterceptor)
        .authenticator(session.authenticator)
        .build()

    private fun retrofit(client: OkHttpClient) = Retrofit.Builder()
        .baseUrl("$apiBaseUrl/api/v1/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val api: SongBookApi = retrofit(httpClient).create(SongBookApi::class.java)

    init {
        session.refreshApi = retrofit(baseClient).create(SongBookApi::class.java)
    }

    /** Outlives screens, e.g. to save reading progress when the reader closes. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val pdfRepository =PdfRepository(api, httpClient, json, apiBaseUrl, context.cacheDir)

    /** Backend-relative paths such as "/api/v1/books/x/cover?v=..." become absolute URLs. */
    fun absoluteUrl(path: String?): String? = when {
        path.isNullOrBlank() -> null
        path.startsWith("http://") || path.startsWith("https://") -> path
        else -> apiBaseUrl + path
    }
}
