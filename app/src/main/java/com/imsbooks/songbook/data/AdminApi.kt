package com.imsbooks.songbook.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

// Management area DTOs, mirroring frontend/src/types/admin.ts and the backend admin controllers.
// Everything under /admin needs ADMIN, except /admin/books/** (ADMIN or PUBLISHER; publishers only see
// the books they uploaded, anyone else's answers 404) and GET /admin/categories (also PUBLISHER).

object Roles {
    const val MEMBER = "MEMBER"
    const val PUBLISHER = "PUBLISHER"
    const val ADMIN = "ADMIN"
}

val ACCOUNT_STATUSES = listOf("ACTIVE", "DISABLED", "LOCKED")
val BOOK_STATUSES = listOf("DRAFT", "PUBLISHED", "INACTIVE", "ARCHIVED")
val EDITABLE_BOOK_STATUSES = listOf("DRAFT", "PUBLISHED", "INACTIVE")
val MEMBERSHIP_STATUSES = listOf("ACTIVE", "SUSPENDED", "EXPIRED", "CANCELLED")
val REQUEST_STATUSES = listOf("PENDING", "APPROVED", "REJECTED")
val AUDIT_ENTITY_TYPES = listOf("USER", "MEMBERSHIP", "CATEGORY", "BOOK", "MODIFICATION_REQUEST")
val AUDIT_ACTIONS = listOf(
    "USER_REGISTERED", "EMAIL_VERIFIED", "LOGIN_SUCCEEDED", "LOGIN_FAILED", "ACCOUNT_TEMPORARILY_LOCKED",
    "REFRESH_TOKEN_REUSE", "PASSWORD_RESET_REQUESTED", "PASSWORD_RESET", "PASSWORD_CHANGED", "PROFILE_UPDATED",
    "ADMIN_BOOTSTRAPPED", "USER_CREATED", "USER_UPDATED", "MEMBERSHIP_CREATED", "MEMBERSHIP_UPDATED",
    "CATEGORY_CREATED", "CATEGORY_UPDATED", "BOOK_CREATED", "BOOK_UPDATED", "BOOK_COVER_REPLACED",
    "BOOK_VERSION_UPLOADED", "BOOK_ARCHIVED", "PDF_READ_ACCESS", "PDF_DOWNLOAD_ACCESS", "MODIFICATION_REQUESTED",
    "MODIFICATION_REVIEWED",
)

/** Same limits as the backend (songbook.storage.max-pdf-bytes / max-image-bytes). */
const val PDF_MAX_BYTES = 100L * 1024 * 1024
const val IMAGE_MAX_BYTES = 5L * 1024 * 1024
val IMAGE_TYPES = listOf("image/jpeg", "image/png", "image/webp")

@Serializable
data class UserCounts(
    val total: Long = 0,
    val active: Long = 0,
    val disabled: Long = 0,
    val locked: Long = 0,
    val unverified: Long = 0,
    val activeAdmins: Long = 0,
)

@Serializable
data class AdminStats(
    val users: UserCounts = UserCounts(),
    val membershipsValidToday: Long = 0,
    val booksByStatus: Map<String, Long> = emptyMap(),
    val pendingRequests: Long = 0,
    val asOf: String? = null,
)

@Serializable
data class AdminUserSummary(
    val id: String,
    val fullName: String,
    val mobile: String? = null,
    val mobileVerified: Boolean = false,
    val email: String? = null,
    val accountStatus: String = "ACTIVE",
    val roles: List<String> = emptyList(),
    val createdAt: String? = null,
)

@Serializable
data class AdminMembership(
    val id: String,
    val userId: String,
    val userMobile: String? = null,
    val userEmail: String? = null,
    val userFullName: String = "",
    val status: String,
    val startDate: String,
    val endDate: String? = null,
    val validToday: Boolean = false,
    val notes: String? = null,
    val createdBy: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val version: Long = 0,
)

@Serializable
data class AdminUserDetail(
    val id: String,
    val fullName: String,
    val mobile: String? = null,
    val mobileVerified: Boolean = false,
    val mobileVerifiedAt: String? = null,
    val email: String? = null,
    val accountStatus: String = "ACTIVE",
    val roles: List<String> = emptyList(),
    val lockedUntil: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val version: Long = 0,
    val memberships: List<AdminMembership> = emptyList(),
)

@Serializable
data class AdminCategory(
    val id: String,
    val name: String,
    val description: String? = null,
    val active: Boolean = true,
    val version: Long = 0,
)

@Serializable
data class BookVersion(
    val id: String,
    val versionNumber: Int,
    val originalFilename: String = "book.pdf",
    val fileSize: Long = 0,
    val pageCount: Int? = null,
    val checksumSha256: String = "",
    val changeNote: String? = null,
    val uploadedBy: String? = null,
    val createdAt: String? = null,
    val current: Boolean = false,
)

@Serializable
data class AdminBookSummary(
    val id: String,
    val title: String,
    val category: Category? = null,
    val status: String,
    val effectiveDate: String,
    val expiryDate: String? = null,
    val availableToday: Boolean = false,
    val coverUrl: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class AdminBookDetail(
    val id: String,
    val title: String,
    val description: String? = null,
    val category: Category? = null,
    val status: String,
    val effectiveDate: String,
    val expiryDate: String? = null,
    val availableToday: Boolean = false,
    val coverUrl: String? = null,
    val currentVersion: BookVersion? = null,
    val uploadedBy: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val version: Long = 0,
)

@Serializable
data class AuditLogEntry(
    val id: String,
    val actorUserId: String? = null,
    val action: String,
    val entityType: String? = null,
    val entityId: String? = null,
    val details: JsonObject? = null,
    val ipAddress: String? = null,
    val createdAt: String,
)

// ---------------------------------------------------------------- request bodies

@Serializable
data class CreateUserRequest(
    val fullName: String,
    val mobile: String,
    val email: String?,
    val password: String,
    val roles: List<String>,
    val mobileVerified: Boolean,
)

@Serializable
data class UpdateUserRequest(
    val fullName: String,
    val mobile: String,
    val mobileVerified: Boolean,
    val email: String?,
    val accountStatus: String,
    val roles: List<String>,
    val version: Long,
)

@Serializable
data class CreateMembershipRequest(
    val userId: String,
    val startDate: String,
    val endDate: String?,
    val status: String,
    val notes: String?,
)

@Serializable
data class UpdateMembershipRequest(
    val startDate: String,
    val endDate: String?,
    val status: String,
    val notes: String?,
    val version: Long,
)

@Serializable
data class CreateCategoryRequest(val name: String, val description: String?)

@Serializable
data class UpdateCategoryRequest(val name: String, val description: String?, val active: Boolean, val version: Long)

/** The "metadata" part of a new book, and (with [UpdateBookRequest]) the editable details. */
@Serializable
data class BookMetadata(
    val title: String,
    val description: String?,
    val categoryId: String?,
    val effectiveDate: String,
    val expiryDate: String?,
    val status: String,
)

@Serializable
data class UpdateBookRequest(
    val title: String,
    val description: String?,
    val categoryId: String?,
    val effectiveDate: String,
    val expiryDate: String?,
    val status: String,
    val version: Long,
)

@Serializable
data class ReviewRequestBody(val status: String, val reviewNote: String?, val version: Long)

/** Endpoints under /api/v1/admin (docs/API_SPECIFICATION.md in the web repo). */
interface AdminApi {

    @GET("admin/stats")
    suspend fun stats(): AdminStats

    // ------------------------------------------------------------ users (admin)

    @GET("admin/users")
    suspend fun users(
        @Query("q") q: String?,
        @Query("status") status: String?,
        @Query("role") role: String?,
        @Query("page") page: Int,
        @Query("size") size: Int,
        @Query("sort") sort: String = "createdAt,desc",
    ): PageResponse<AdminUserSummary>

    @GET("admin/users/{id}")
    suspend fun user(@Path("id") id: String): AdminUserDetail

    @POST("admin/users")
    suspend fun createUser(@Body body: CreateUserRequest): AdminUserDetail

    @PUT("admin/users/{id}")
    suspend fun updateUser(@Path("id") id: String, @Body body: UpdateUserRequest): AdminUserDetail

    // ------------------------------------------------------------ memberships (admin)

    @GET("admin/memberships")
    suspend fun memberships(
        @Query("userId") userId: String?,
        @Query("status") status: String?,
        @Query("page") page: Int,
        @Query("size") size: Int,
        @Query("sort") sort: String = "startDate,desc",
    ): PageResponse<AdminMembership>

    @POST("admin/memberships")
    suspend fun createMembership(@Body body: CreateMembershipRequest): AdminMembership

    @PUT("admin/memberships/{id}")
    suspend fun updateMembership(@Path("id") id: String, @Body body: UpdateMembershipRequest): AdminMembership

    // ------------------------------------------------------------ categories (read: admin + publisher)

    @GET("admin/categories")
    suspend fun categories(): List<AdminCategory>

    @POST("admin/categories")
    suspend fun createCategory(@Body body: CreateCategoryRequest): AdminCategory

    @PUT("admin/categories/{id}")
    suspend fun updateCategory(@Path("id") id: String, @Body body: UpdateCategoryRequest): AdminCategory

    // ------------------------------------------------------------ books (admin + publisher)

    @GET("admin/books")
    suspend fun books(
        @Query("q") q: String?,
        @Query("status") status: String?,
        @Query("categoryId") categoryId: String?,
        @Query("page") page: Int,
        @Query("size") size: Int,
        @Query("sort") sort: String = "updatedAt,desc",
    ): PageResponse<AdminBookSummary>

    @GET("admin/books/{id}")
    suspend fun book(@Path("id") id: String): AdminBookDetail

    /** Parts: "metadata" (JSON), "pdf", optional "cover". */
    @Multipart
    @POST("admin/books")
    suspend fun createBook(
        @Part("metadata") metadata: RequestBody,
        @Part pdf: MultipartBody.Part,
        @Part cover: MultipartBody.Part?,
    ): AdminBookDetail

    @PUT("admin/books/{id}")
    suspend fun updateBook(@Path("id") id: String, @Body body: UpdateBookRequest): AdminBookDetail

    @Multipart
    @PUT("admin/books/{id}/cover")
    suspend fun replaceCover(@Path("id") id: String, @Part cover: MultipartBody.Part): AdminBookDetail

    @GET("admin/books/{id}/versions")
    suspend fun versions(@Path("id") id: String): List<BookVersion>

    /** changeNote is a request parameter, not a multipart part (AdminBookController#addVersion). */
    @Multipart
    @POST("admin/books/{id}/versions")
    suspend fun addVersion(
        @Path("id") id: String,
        @Part pdf: MultipartBody.Part,
        @Query("changeNote") changeNote: String?,
    ): BookVersion

    @Streaming
    @GET("admin/books/{id}/versions/{versionId}/content")
    suspend fun versionContent(@Path("id") id: String, @Path("versionId") versionId: String): Response<ResponseBody>

    @POST("admin/books/{id}/archive")
    suspend fun archiveBook(@Path("id") id: String): Response<Unit>

    // ------------------------------------------------------------ modification requests (admin)

    @GET("admin/modification-requests")
    suspend fun requests(
        @Query("status") status: String?,
        @Query("page") page: Int,
        @Query("size") size: Int,
        @Query("sort") sort: String = "createdAt,desc",
    ): PageResponse<ModificationRequest>

    @PUT("admin/modification-requests/{id}")
    suspend fun reviewRequest(@Path("id") id: String, @Body body: ReviewRequestBody): ModificationRequest

    // ------------------------------------------------------------ audit (admin)

    @GET("admin/audit-logs")
    suspend fun audit(
        @Query("action") action: String?,
        @Query("entityType") entityType: String?,
        @Query("entityId") entityId: String?,
        @Query("actorId") actorId: String?,
        @Query("from") from: String?,
        @Query("to") to: String?,
        @Query("page") page: Int,
        @Query("size") size: Int,
        @Query("sort") sort: String = "createdAt,desc",
    ): PageResponse<AuditLogEntry>
}
