package com.imsbooks.songbook

import com.imsbooks.songbook.data.Me
import com.imsbooks.songbook.data.MembershipSummary
import com.imsbooks.songbook.data.OfflineLibrary
import com.imsbooks.songbook.data.PdfRepository
import com.imsbooks.songbook.data.SongBookApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Offline downloads against a fake backend: storage, ownership and re-verification. */
class OfflineLibraryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val pdfBytes = "%PDF-1.7 fake book".toByteArray()

    /** Book ids that the fake catalogue reports as withdrawn (404). */
    private val withdrawn = mutableSetOf<String>()

    private val member = Me(
        id = "u1", fullName = "Asha Member", roles = listOf("MEMBER"),
        membership = MembershipSummary(id = "m1", status = "ACTIVE", startDate = "2026-01-01", valid = true),
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val id = path.removePrefix("/api/v1/books/").substringBefore('/')
                return when {
                    path.endsWith("/download-access") -> json(
                        """{"ticket":"t","expiresAt":"2026-12-01T00:00:00Z","versionId":"v-$id","versionNumber":2,"pageCount":12,""" +
                            """"contentUrl":"/api/v1/books/$id/content?ticket=t"}""",
                    )
                    path.endsWith("/content") -> MockResponse.Builder().body(okio.Buffer().write(pdfBytes)).build()
                    path.endsWith("/cover") -> MockResponse.Builder().body("img").build()
                    id in withdrawn -> MockResponse.Builder().code(404).build()
                    path.startsWith("/api/v1/books/") -> json("""{"id":"$id","title":"Book $id"}""")
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    private fun library(): OfflineLibrary {
        val base = server.url("/").toString().trimEnd('/')
        val http = OkHttpClient()
        val api = Retrofit.Builder()
            .baseUrl("$base/api/v1/")
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SongBookApi::class.java)
        val pdfs = PdfRepository(api, http, json, base, tmp.root)
        return OfflineLibrary(api, json, pdfs, http, { url -> url?.let { base + it } }, tmp.root)
    }

    @Test
    fun downloadKeepsTheBookAcrossRestarts() = runBlocking {
        val lib = library()
        lib.download("u1", "b1", "Book b1", "/api/v1/books/b1/cover?v=1")

        val saved = lib.get("b1")
        assertNotNull(saved)
        assertEquals("v-b1", saved!!.versionId)
        assertEquals(12, saved.pageCount)
        assertTrue(saved.verifiedRecently())
        assertEquals(pdfBytes.size.toLong(), lib.pdfFile("b1").length())
        assertTrue(lib.coverFile("b1").exists())

        val restarted = library()
        assertEquals(listOf("b1"), restarted.books.value.map { it.bookId })
        assertEquals("u1", restarted.ownerUserId)
    }

    @Test
    fun anotherAccountDoesNotSeePreviousDownloads() = runBlocking {
        val lib = library()
        lib.download("u1", "b1", "Book b1", null)
        lib.onSignedIn(member) // same user: kept
        assertNotNull(lib.get("b1"))

        lib.onSignedIn(member.copy(id = "u2"))
        assertNull(lib.get("b1"))
        assertFalse(lib.pdfFile("b1").exists())
        assertEquals("u2", lib.ownerUserId)
    }

    @Test
    fun revalidateRemovesWithdrawnBooksAndKeepsTheRest() = runBlocking {
        val lib = library()
        lib.download("u1", "b1", "Book b1", null)
        lib.download("u1", "b2", "Book b2", null)
        withdrawn += "b2"

        lib.revalidate(member)

        assertNotNull(lib.get("b1"))
        assertNull(lib.get("b2"))
        assertFalse(lib.pdfFile("b2").exists())
    }

    @Test
    fun lastPageAndLogoutCleanup() = runBlocking {
        val lib = library()
        lib.download("u1", "b1", "Book b1", null)
        lib.saveLastPage("b1", 7)
        assertEquals(7, library().get("b1")?.lastPage)

        lib.deleteAll()
        assertTrue(lib.books.value.isEmpty())
        assertFalse(lib.pdfFile("b1").exists())
        assertTrue(library().books.value.isEmpty())
    }
}
