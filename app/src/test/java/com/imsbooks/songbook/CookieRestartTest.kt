package com.imsbooks.songbook

import com.imsbooks.songbook.data.session.CookieStorage
import com.imsbooks.songbook.data.session.RefreshCookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The refresh cookie must survive an app restart exactly as the deployed (HTTPS, Secure) backend sets it. */
class CookieRestartTest {

    private class MemoryStorage : CookieStorage {
        var value: String? = null
        override fun load() = value
        override fun save(setCookie: String) { value = setCookie }
        override fun clear() { value = null }
    }

    @Test
    fun secureCookieIsSentAfterRestart() {
        val api = "https://app-songbook-dev-eys56l.azurewebsites.net".toHttpUrl()
        val login = "https://app-songbook-dev-eys56l.azurewebsites.net/api/v1/auth/login".toHttpUrl()
        val refresh = "https://app-songbook-dev-eys56l.azurewebsites.net/api/v1/auth/refresh".toHttpUrl()
        val header = "sb_refresh=abc123; Path=/api/v1/auth; Max-Age=1209600; " +
            "Expires=Wed, 02 Dec 2026 20:00:00 GMT; Secure; HttpOnly; SameSite=Strict"
        val storage = MemoryStorage()

        RefreshCookieJar(api, storage).saveFromResponse(login, listOfNotNull(Cookie.parse(login, header)))
        println("stored: ${storage.value}")

        val restarted = RefreshCookieJar(api, storage)
        assertTrue("session after restart", restarted.hasSession())
        assertEquals(listOf("abc123"), restarted.loadForRequest(refresh).map { it.value })
    }
}
