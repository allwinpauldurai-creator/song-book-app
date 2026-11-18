package com.imsbooks.songbook.data

import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Song Book API v1 (docs/API_SPECIFICATION.md in the web repo). Base URL ends with /api/v1/. */
interface SongBookApi {

    // ------------------------------------------------------------ auth (public)

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): LoginResponse

    /** Blocking on purpose: called from the OkHttp authenticator thread. */
    @POST("auth/refresh")
    fun refresh(): Call<AccessTokenResponse>

    @POST("auth/logout")
    suspend fun logout(): Response<Unit>

    @POST("auth/forgot-password")
    suspend fun forgotPassword(@Body body: MobileRequest): MessageResponse

    @POST("auth/reset-password")
    suspend fun resetPassword(@Body body: ResetPasswordRequest): Response<Unit>

    // ------------------------------------------------------------ current user

    @GET("users/me")
    suspend fun me(): Me

    @PUT("users/me")
    suspend fun updateProfile(@Body body: UpdateProfileRequest): Me

    @PUT("users/me/password")
    suspend fun changePassword(@Body body: ChangePasswordRequest): Response<Unit>

    @POST("users/me/mobile")
    suspend fun requestMobileChange(@Body body: ChangeMobileRequest): MessageResponse

    @POST("users/me/mobile/confirm")
    suspend fun confirmMobileChange(@Body body: ConfirmMobileRequest): Me

    // ------------------------------------------------------------ catalogue (public)

    @GET("categories")
    suspend fun categories(): List<Category>

    @GET("books")
    suspend fun books(
        @Query("q") q: String?,
        @Query("categoryId") categoryId: String?,
        @Query("page") page: Int,
        @Query("size") size: Int,
        @Query("sort") sort: String,
    ): PageResponse<BookSummary>

    @GET("books/{id}")
    suspend fun book(@Path("id") id: String): BookDetail

    // ------------------------------------------------------------ member

    @GET("books/{id}/read-access")
    suspend fun readAccess(@Path("id") id: String): AccessTicket

    @GET("books/{id}/download-access")
    suspend fun downloadAccess(@Path("id") id: String): AccessTicket

    @GET("members/books/{id}/progress")
    suspend fun progress(@Path("id") id: String): Response<ReadingProgress>

    @PUT("members/books/{id}/progress")
    suspend fun saveProgress(@Path("id") id: String, @Body body: SaveProgressRequest): ReadingProgress

    @GET("members/progress")
    suspend fun history(@Query("page") page: Int, @Query("size") size: Int): PageResponse<HistoryItem>

    @GET("members/modification-requests")
    suspend fun myRequests(@Query("page") page: Int, @Query("size") size: Int): PageResponse<ModificationRequest>

    @POST("modification-requests")
    suspend fun createRequest(@Body body: CreateRequestBody): ModificationRequest
}
