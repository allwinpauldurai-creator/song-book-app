package com.imsbooks.songbook

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.imsbooks.songbook.data.session.RefreshCookieJar
import com.imsbooks.songbook.data.session.SecureCookieStorage
import com.imsbooks.songbook.data.session.SecureStore
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On a real device: the refresh cookie survives an app restart (new store and jar instances). */
@RunWith(AndroidJUnit4::class)
class SecureSessionDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun secureStoreRoundTrip() {
        SecureStore(context).put("probe", "hello")
        assertEquals("hello", SecureStore(context).get("probe"))
        SecureStore(context).remove("probe")
    }

    @Test
    fun refreshCookieSurvivesRestart() {
        val api = "https://app-songbook-dev-eys56l.azurewebsites.net".toHttpUrl()
        val login = api.resolve("/api/v1/auth/login")!!
        val refresh = api.resolve("/api/v1/auth/refresh")!!
        val header = "sb_refresh=abc123; Path=/api/v1/auth; Max-Age=1209600; " +
            "Expires=Wed, 02 Dec 2026 20:00:00 GMT; Secure; HttpOnly; SameSite=Strict"

        RefreshCookieJar(api, SecureCookieStorage(SecureStore(context)))
            .saveFromResponse(login, listOfNotNull(Cookie.parse(login, header)))

        val restarted = RefreshCookieJar(api, SecureCookieStorage(SecureStore(context)))
        assertTrue("session after restart", restarted.hasSession())
        assertEquals(listOf("abc123"), restarted.loadForRequest(refresh).map { it.value })
        restarted.clear()
    }
}
