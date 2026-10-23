package com.qimingnan.couple.data.net

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
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

    @GET("app/update")
    suspend fun checkUpdate(@Query("versionCode") versionCode: Int, @Query("deviceId") deviceId: String): UpdateResponse

    companion object {
        /** Retrofit 需要一个固定 baseUrl；真实地址由 [BaseUrlInterceptor] 按当前配置改写。 */
        const val PLACEHOLDER_BASE = "http://couple.invalid/api/v1/"
    }
}
