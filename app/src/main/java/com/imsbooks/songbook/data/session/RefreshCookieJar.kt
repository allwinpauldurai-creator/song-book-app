package com.imsbooks.songbook.data.session

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Where the refresh cookie is persisted. [SecureStore] in the app, an in-memory map in tests. */
interface CookieStorage {
    fun load(): String?
    fun save(setCookie: String)
    fun clear()
}

/**
 * Cookie jar for the API. The backend keeps the refresh token in the HttpOnly `sb_refresh` cookie
 * (path /api/v1/auth, rotated on every refresh). A browser keeps it for us; here this jar does, and
 * persists only that cookie so the member stays signed in across app restarts (14-day session).
 * SameSite does not apply to native clients, and the backend's Origin check only rejects a *wrong*
 * Origin header, which native requests never send, so the web API works unchanged.
 */
class RefreshCookieJar(private val apiBase: HttpUrl, private val storage: CookieStorage) : CookieJar {

    private val lock = Any()
    private var refreshCookie: Cookie? = storage.load()?.let { Cookie.parse(apiBase, it) }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val incoming = cookies.lastOrNull { it.name == REFRESH_COOKIE } ?: return
        synchronized(lock) {
            if (incoming.expiresAt <= System.currentTimeMillis() || incoming.value.isEmpty()) {
                refreshCookie = null // the server cleared it (logout, revoked or invalid token)
                storage.clear()
            } else {
                refreshCookie = incoming
                storage.save(incoming.toString())
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        val cookie = refreshCookie ?: return emptyList()
        if (cookie.expiresAt <= System.currentTimeMillis()) {
            refreshCookie = null
            storage.clear()
            return emptyList()
        }
        if (cookie.matches(url)) listOf(cookie) else emptyList()
    }

    fun hasSession(): Boolean = synchronized(lock) {
        refreshCookie?.let { it.expiresAt > System.currentTimeMillis() } ?: false
    }

    fun clear() = synchronized(lock) {
        refreshCookie = null
        storage.clear()
    }

    companion object {
        const val REFRESH_COOKIE = "sb_refresh"
    }
}

/** [CookieStorage] backed by the Keystore-encrypted [SecureStore]. */
class SecureCookieStorage(private val store: SecureStore) : CookieStorage {
    override fun load(): String? = store.get(KEY)
    override fun save(setCookie: String) = store.put(KEY, setCookie)
    override fun clear() = store.remove(KEY)

    private companion object {
        const val KEY = "refresh_cookie"
    }
}
