package com.imsbooks.songbook.data.session

import com.imsbooks.songbook.data.Me
import com.imsbooks.songbook.data.SongBookApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException

sealed interface SessionState {
    /** Restoring a saved session at app start. */
    data object Loading : SessionState
    data object SignedOut : SessionState
    /** A saved session exists but the server could not be reached to resume it. */
    data object Offline : SessionState
    data class SignedIn(val user: Me) : SessionState
}

/**
 * Owns the signed-in state. The 15-minute access token lives in memory only; the refresh cookie is
 * kept by [RefreshCookieJar]. Refreshing is single-flight (synchronized): the backend rotates the
 * refresh token on every use and treats a reused one as theft, so two refreshes must never overlap.
 */
class SessionManager(
    private val cookieJar: RefreshCookieJar,
    private val userCache: UserCache = UserCache.None,
) {

    /** Retrofit API on a client *without* the authenticator, used only to refresh. */
    lateinit var refreshApi: SongBookApi

    /**
     * The last signed-in user, kept with the session so the app can greet them and open their downloads
     * when it starts without a network. Not used for any access decision (the server makes those).
     */
    @Volatile
    var cachedUser: Me? = userCache.load()
        private set

    @Volatile
    var accessToken: String? = null
        private set

    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val currentUser: Me? get() = (state.value as? SessionState.SignedIn)?.user

    private val lock = Any()

    sealed interface RefreshResult {
        data class Ok(val token: String) : RefreshResult
        data object Rejected : RefreshResult
        data object NetworkError : RefreshResult
    }

    /** Blocking; safe to call from OkHttp threads. */
    fun refreshBlocking(): RefreshResult = synchronized(lock) {
        if (!cookieJar.hasSession()) return RefreshResult.Rejected
        try {
            val res = refreshApi.refresh().execute()
            val body = res.body()
            if (res.isSuccessful && body != null) {
                accessToken = body.accessToken
                RefreshResult.Ok(body.accessToken)
            } else {
                accessToken = null
                if (res.code() == 401 || res.code() == 403) cookieJar.clear()
                RefreshResult.Rejected
            }
        } catch (_: IOException) {
            RefreshResult.NetworkError
        }
    }

    /** Resumes a saved session at app start (or on "Retry" when offline). */
    suspend fun restore(api: SongBookApi) {
        if (!cookieJar.hasSession()) {
            _state.value = SessionState.SignedOut
            return
        }
        _state.value = SessionState.Loading
        when (withContext(Dispatchers.IO) { refreshBlocking() }) {
            is RefreshResult.Ok -> try {
                val user = api.me()
                remember(user)
                _state.value = SessionState.SignedIn(user)
            } catch (_: Exception) {
                _state.value = if (cookieJar.hasSession()) SessionState.Offline else SessionState.SignedOut
            }
            RefreshResult.Rejected -> _state.value = SessionState.SignedOut
            RefreshResult.NetworkError -> _state.value = SessionState.Offline
        }
    }

    fun signedIn(token: String, user: Me) {
        accessToken = token
        remember(user)
        _state.value = SessionState.SignedIn(user)
    }

    fun userUpdated(user: Me) {
        if (state.value is SessionState.SignedIn) {
            remember(user)
            _state.value = SessionState.SignedIn(user)
        }
    }

    /** Drops the local session (after logout, a password change, or a failed refresh). */
    fun clearLocal() {
        synchronized(lock) {
            accessToken = null
            cookieJar.clear()
            cachedUser = null
            userCache.clear()
        }
        _state.value = SessionState.SignedOut
    }

    private fun remember(user: Me) {
        if (cachedUser != user) {
            cachedUser = user
            userCache.save(user)
        }
    }

    fun hasSavedSession(): Boolean = cookieJar.hasSession()

    // ------------------------------------------------------------------ OkHttp plumbing

    /** Adds the bearer token to API calls (not to the cookie-authenticated /auth endpoints). */
    val authInterceptor = Interceptor { chain ->
        val request = chain.request()
        val token = accessToken
        if (token == null || request.isAuthEndpoint() || request.header("Authorization") != null) {
            chain.proceed(request)
        } else {
            chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
        }
    }

    /** On 401: refresh once (single-flight) and retry; if the session is gone, sign out locally. */
    val authenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            val request = response.request
            if (request.isAuthEndpoint() || response.priorResponse != null) return null
            val sent = request.header("Authorization")?.removePrefix("Bearer ")
            val token = synchronized(lock) {
                val current = accessToken
                if (current != null && current != sent) {
                    current // another request refreshed meanwhile
                } else {
                    when (val result = refreshBlocking()) {
                        is RefreshResult.Ok -> result.token
                        RefreshResult.Rejected -> {
                            if (sent != null || state.value is SessionState.SignedIn) clearLocal()
                            null
                        }
                        RefreshResult.NetworkError -> null
                    }
                }
            } ?: return null
            return request.newBuilder().header("Authorization", "Bearer $token").build()
        }
    }

    private fun Request.isAuthEndpoint() = url.encodedPath.contains("/api/v1/auth/")
}
