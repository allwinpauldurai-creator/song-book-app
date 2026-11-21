package com.imsbooks.songbook

import com.imsbooks.songbook.data.SongBookApi
import com.imsbooks.songbook.data.session.CookieStorage
import com.imsbooks.songbook.data.session.RefreshCookieJar
import com.imsbooks.songbook.data.session.SessionManager
import com.imsbooks.songbook.data.session.SessionState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The session plumbing against a fake backend: the refresh cookie is stored and sent back, a 401
 * triggers exactly one refresh and a retry, and a rejected refresh signs the user out.
 */
class SessionTest {

    private class MemoryStorage : CookieStorage {
        var value: String? = null
        override fun load() = value
        override fun save(setCookie: String) { value = setCookie }
        override fun clear() { value = null }
    }

    private lateinit var server: MockWebServer
    private lateinit var storage: MemoryStorage
    private lateinit var jar: RefreshCookieJar
    private lateinit var session: SessionManager
    private lateinit var api: SongBookApi

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val meJson = """{"id":"u1","fullName":"Asha Member","mobile":"+919876543210","mobileVerified":true,"roles":["MEMBER"]}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        storage = MemoryStorage()
        jar = RefreshCookieJar(server.url("/"), storage)
        session = SessionManager(jar)
        val base = OkHttpClient.Builder().cookieJar(jar).build()
        val client = base.newBuilder().addInterceptor(session.authInterceptor).authenticator(session.authenticator).build()
        fun retrofit(c: OkHttpClient) = Retrofit.Builder()
            .baseUrl(server.url("/api/v1/"))
            .client(c)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SongBookApi::class.java)
        api = retrofit(client)
        session.refreshApi = retrofit(base)
    }

    @After
    fun tearDown() = server.close()

    private fun jsonResponse(code: Int, body: String, setCookie: String? = null) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .apply { if (setCookie != null) addHeader("Set-Cookie", setCookie) }
        .body(body)
        .build()

    private fun refreshCookie(value: String, maxAge: Int = 1209600) =
        "sb_refresh=$value; Path=/api/v1/auth; Max-Age=$maxAge; HttpOnly; SameSite=Strict"

    @Test
    fun loginStoresCookieAndRefreshRetriesOnce() = runBlocking {
        server.enqueue(jsonResponse(200, """{"accessToken":"a1","expiresIn":900,"user":$meJson}""", refreshCookie("r1")))
        val login = api.login(com.imsbooks.songbook.data.LoginRequest("+919876543210", "secret-password"))
        session.signedIn(login.accessToken, login.user)
        assertTrue(jar.hasSession())
        server.takeRequest()

        // Access token expired: 401, then refresh (rotates the cookie), then the retried call succeeds.
        server.enqueue(jsonResponse(401, """{"status":401,"code":"UNAUTHORIZED"}"""))
        server.enqueue(jsonResponse(200, """{"accessToken":"a2","expiresIn":900}""", refreshCookie("r2")))
        server.enqueue(jsonResponse(200, meJson))

        val me = api.me()
        assertEquals("Asha Member", me.fullName)

        val first = server.takeRequest()
        assertEquals("Bearer a1", first.headers["Authorization"])
        val refresh = server.takeRequest()
        assertEquals("/api/v1/auth/refresh", refresh.url.encodedPath)
        assertEquals("sb_refresh=r1", refresh.headers["Cookie"])
        assertNull(refresh.headers["Authorization"])
        val retry = server.takeRequest()
        assertEquals("Bearer a2", retry.headers["Authorization"])
        assertTrue(storage.value!!.startsWith("sb_refresh=r2"))
    }

    @Test
    fun rejectedRefreshSignsOut() {
        runBlocking {
            server.enqueue(jsonResponse(200, """{"accessToken":"a1","expiresIn":900,"user":$meJson}""", refreshCookie("r1")))
            val login = api.login(com.imsbooks.songbook.data.LoginRequest("+919876543210", "secret-password"))
            session.signedIn(login.accessToken, login.user)

            server.enqueue(jsonResponse(401, """{"status":401,"code":"UNAUTHORIZED"}"""))
            server.enqueue(jsonResponse(401, """{"status":401,"code":"INVALID_REFRESH_TOKEN"}""", refreshCookie("", maxAge = 0)))
            val result = runCatching { api.me() }
            assertTrue(result.isFailure)
        }
        assertEquals(SessionState.SignedOut, session.state.value)
        assertFalse(jar.hasSession())
        assertNull(storage.value)
    }

    @Test
    fun restoreUsesSavedCookie() {
        storage.value = refreshCookie("saved") + "; Path=/api/v1/auth"
        jar = RefreshCookieJar(server.url("/"), storage)
        assertTrue(jar.hasSession())
    }
}
