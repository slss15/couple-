package com.qimingnan.couple.data.net

import com.qimingnan.couple.core.CoupleJson
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Err(val error: ApiError) : ApiResult<Nothing>
}

sealed class ApiError(open val code: String, override val message: String) : Exception(message) {
    data class Server(val status: Int, override val code: String, override val message: String) : ApiError(code, message)
    data class Network(override val message: String) : ApiError("NETWORK", message)
    data class Unexpected(override val message: String) : ApiError("UNEXPECTED", message)
}

fun parseErrorBody(status: Int, body: String?): ApiError.Server {
    val parsed = body?.takeIf { it.isNotBlank() }?.let {
        runCatching { CoupleJson.decodeFromString(ErrorBody.serializer(), it) }.getOrNull()
    }
    return ApiError.Server(status, parsed?.code ?: "HTTP_$status", parsed?.message ?: "HTTP $status")
}

suspend fun <T> apiCall(block: suspend () -> T): ApiResult<T> = try {
    ApiResult.Ok(block())
} catch (e: CancellationException) {
    throw e
} catch (e: HttpException) {
    ApiResult.Err(parseErrorBody(e.code(), e.response()?.errorBody()?.string()))
} catch (e: IOException) {
    ApiResult.Err(ApiError.Network(e.message ?: "网络错误"))
} catch (e: ApiError) {
    ApiResult.Err(e)
} catch (e: Exception) {
    ApiResult.Err(ApiError.Unexpected(e.message ?: e.javaClass.simpleName))
}

/** 把 Response<T> 的非 2xx 转成 ApiError（用于 204 / 404 需要区分的接口）。 */
fun <T> Response<T>.orThrow(): Response<T> {
    if (!isSuccessful) throw parseErrorBody(code(), errorBody()?.string())
    return this
}

/** 常见错误码的中文提示。 */
fun ApiError.humanMessage(): String = when (code) {
    "NETWORK" -> "网络不可用：$message"
    "INVALID_CREDENTIALS" -> "用户名或密码错误"
    "USERNAME_TAKEN" -> "用户名已被占用"
    "INVITE_CODE_INVALID", "REGISTRATION_CODE_INVALID" -> "注册码不正确"
    "ALREADY_PAIRED" -> "你们之中有人已经配对了"
    "CODE_INVALID" -> "配对码不正确"
    "CODE_EXPIRED" -> "配对码已过期，请让 TA 重新生成"
    "SELF_PAIR" -> "不能和自己配对"
    "NOT_PAIRED" -> "还没有配对"
    "UNAUTHORIZED" -> "登录已失效，请重新登录"
    else -> message.ifBlank { code }
}
