package com.qimingnan.couple.data.net

import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import com.qimingnan.couple.data.update.HealthReport
import com.qimingnan.couple.data.update.UpdateResponse

/** contract.md 的 REST 接口（相对 {baseUrl}/api/v1/）。 */
interface CoupleApi {
    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): TokenPair

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): TokenPair

    @POST("auth/logout")
    suspend fun logout(@Body body: LogoutRequest): Response<Unit>

    @POST("pair/code")
    suspend fun createPairCode(): PairCode

    @POST("pair/join")
    suspend fun joinPair(@Body body: JoinRequest): Couple

    @GET("couple")
    suspend fun getCouple(): Response<Couple>

    @POST("couple/unpair/request")
    suspend fun requestUnpair(): UnpairRequestResponse

    @POST("couple/unpair/confirm")
    suspend fun confirmUnpair(@Body body: UnpairConfirmRequest): Response<Unit>

    // ---- 媒体 + 动态（contract "Media storage" / "Feed"） ----

    @Multipart
    @POST("media")
    suspend fun uploadMedia(@Part file: MultipartBody.Part): MediaView

    @POST("users/me/avatar")
    suspend fun setAvatar(@Body body: AvatarRequest): Response<Unit>

    @POST("feed/posts")
    suspend fun createPost(@Body body: CreatePostRequest): PostView

    @GET("feed/posts")
    suspend fun feedPosts(@Query("before") before: String?, @Query("limit") limit: Int): FeedPage

    @DELETE("feed/posts/{id}")
    suspend fun deletePost(@Path("id") id: String): Response<Unit>

    @POST("feed/posts/{id}/like")
    suspend fun likePost(@Path("id") id: String): LikeCountView

    @DELETE("feed/posts/{id}/like")
    suspend fun unlikePost(@Path("id") id: String): LikeCountView

    @POST("feed/posts/{id}/comments")
    suspend fun addComment(@Path("id") postId: String, @Body body: AddCommentRequest): CommentView

    @DELETE("feed/comments/{id}")
    suspend fun deleteComment(@Path("id") id: String): Response<Unit>

    /** Also reports this device's app/partner-protocol versions to the server (OTA v2 partner gate). */
    @GET("app/update")
    suspend fun checkUpdate(
        @Query("versionCode") versionCode: Int,
        @Query("deviceId") deviceId: String,
        @Query("protocolVersion") protocolVersion: Int,
        @Query("minPeerProtocol") minPeerProtocol: Int,
        @Query("sdkInt") sdkInt: Int,
    ): UpdateResponse

    @POST("app/health-report")
    suspend fun healthReport(@Body body: HealthReport): Response<Unit>

    companion object {
        /** Retrofit 需要一个固定 baseUrl；真实地址由 [BaseUrlInterceptor] 按当前配置改写。 */
        const val PLACEHOLDER_BASE = "http://couple.invalid/api/v1/"

        /** 相对媒体路径（avatarUrl 等）解析成绝对地址时使用；thumb=true 加 /thumb。 */
        const val MEDIA_PATH = "/api/v1/media/"
    }
}
