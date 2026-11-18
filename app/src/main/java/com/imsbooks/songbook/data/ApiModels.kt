package com.imsbooks.songbook.data

import kotlinx.serialization.Serializable

// Mirrors the Spring Boot DTOs (Song-book-Webapp/backend/.../dto and frontend/src/types/api.ts).
// The backend omits null fields (Jackson non_null), so every optional field has a default.

@Serializable
data class FieldError(val field: String, val message: String)

@Serializable
data class ApiErrorBody(
    val status: Int = 0,
    val code: String = "",
    val message: String? = null,
    val fieldErrors: List<FieldError> = emptyList(),
)

@Serializable
data class PageResponse<T>(
    val content: List<T> = emptyList(),
    val page: Int = 0,
    val size: Int = 0,
    val totalElements: Long = 0,
    val totalPages: Int = 0,
)

@Serializable
data class MembershipSummary(
    val id: String,
    val status: String,
    val startDate: String,
    val endDate: String? = null,
    val valid: Boolean = false,
)

@Serializable
data class Me(
    val id: String,
    val fullName: String,
    val mobile: String? = null,
    val mobileVerified: Boolean = false,
    val email: String? = null,
    val roles: List<String> = emptyList(),
    val membership: MembershipSummary? = null,
) {
    val isAdmin: Boolean get() = "ADMIN" in roles

    /** Same rule as the backend eligibility check: admins bypass membership (decision D3). */
    val canRead: Boolean get() = isAdmin || membership?.valid == true
}

@Serializable
data class LoginResponse(val accessToken: String, val expiresIn: Long = 0, val user: Me)

@Serializable
data class AccessTokenResponse(val accessToken: String, val expiresIn: Long = 0)

@Serializable
data class MessageResponse(val message: String = "")

@Serializable
data class Category(val id: String, val name: String, val description: String? = null)

@Serializable
data class BookSummary(
    val id: String,
    val title: String,
    val description: String? = null,
    val category: Category? = null,
    val coverUrl: String? = null,
)

@Serializable
data class BookDetail(
    val id: String,
    val title: String,
    val description: String? = null,
    val category: Category? = null,
    val coverUrl: String? = null,
    val effectiveDate: String? = null,
    val expiryDate: String? = null,
    val pageCount: Int? = null,
    val updatedAt: String? = null,
)

@Serializable
data class AccessTicket(
    val ticket: String,
    val expiresAt: String,
    val versionId: String,
    val versionNumber: Int = 0,
    val pageCount: Int? = null,
    /** Relative API path including the ticket, e.g. /api/v1/books/{id}/content?ticket=... */
    val contentUrl: String,
)

@Serializable
data class ReadingProgress(
    val bookId: String,
    val lastPage: Int,
    val versionId: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class HistoryItem(
    val bookId: String,
    val title: String,
    val coverUrl: String? = null,
    val lastPage: Int,
    val updatedAt: String,
)

@Serializable
data class ModificationRequest(
    val id: String,
    val bookId: String? = null,
    val bookTitle: String? = null,
    val requestType: String,
    val description: String,
    val status: String,
    val reviewNote: String? = null,
    val reviewedAt: String? = null,
    val createdAt: String,
)

// ---------------------------------------------------------------- request bodies

@Serializable
data class LoginRequest(val mobile: String, val password: String)

@Serializable
data class RegisterRequest(val fullName: String, val mobile: String, val email: String?, val password: String)

@Serializable
data class MobileRequest(val mobile: String)

@Serializable
data class ResetPasswordRequest(val mobile: String, val code: String, val newPassword: String)

@Serializable
data class UpdateProfileRequest(val fullName: String, val email: String?)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class ChangeMobileRequest(val newMobile: String, val currentPassword: String)

@Serializable
data class ConfirmMobileRequest(val code: String)

@Serializable
data class SaveProgressRequest(val lastPage: Int)

@Serializable
data class CreateRequestBody(val bookId: String?, val requestType: String, val description: String)
