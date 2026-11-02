package com.qimingnan.couple.data.chat

import com.qimingnan.couple.data.net.CoupleApi
import com.qimingnan.couple.data.net.CreateScheduledRequest
import com.qimingnan.couple.data.net.PatchScheduledRequest
import com.qimingnan.couple.data.net.ScheduleRule
import com.qimingnan.couple.data.net.ScheduledMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 定时消息：规则只存在服务器（到点由服务端代发，手机关机也会送达），本地只缓存列表用于展示。
 * 不走 outbox——创建失败直接提示用户，避免“以为定好了其实没定上”。
 */
@Singleton
class ScheduledRepository @Inject constructor(private val api: CoupleApi) {
    private val _items = MutableStateFlow<List<ScheduledMessage>>(emptyList())
    val items: StateFlow<List<ScheduledMessage>> = _items.asStateFlow()

    suspend fun refresh(): Result<Unit> = call { _items.value = api.scheduledList().items }

    suspend fun createText(text: String, rule: ScheduleRule, tz: String): Result<ScheduledMessage> = call {
        api.createScheduled(CreateScheduledRequest(type = "text", text = text.trim(), rule = rule, tz = tz))
            .also { created -> _items.value = listOf(created) + _items.value.filterNot { it.id == created.id } }
    }

    suspend fun setPaused(id: String, paused: Boolean): Result<ScheduledMessage> = call {
        api.patchScheduled(id, PatchScheduledRequest(paused = paused)).also(::replace)
    }

    suspend fun delete(id: String): Result<Unit> = call {
        val r = api.deleteScheduled(id)
        if (!r.isSuccessful && r.code() != 404) throw HttpException(r)
        _items.value = _items.value.filterNot { it.id == id }
    }

    private fun replace(m: ScheduledMessage) {
        _items.value = _items.value.map { if (it.id == m.id) m else it }
    }

    private suspend fun <T> call(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: HttpException) {
        Result.failure(IllegalStateException(errorMessage(e), e))
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun errorMessage(e: HttpException): String {
        val code = runCatching {
            e.response()?.errorBody()?.string()?.let { Regex("\"code\"\\s*:\\s*\"([A-Z_]+)\"").find(it)?.groupValues?.get(1) }
        }.getOrNull()
        return when (code) {
            "SCHEDULE_IN_PAST" -> "发送时间已经过去了"
            "SCHEDULE_FINISHED" -> "这条单次消息已经发出"
            "SCHEDULE_LIMIT" -> "定时消息太多了，先删掉一些吧"
            "NOT_PAIRED" -> "还没有配对"
            null -> "请求失败（HTTP ${e.code()}）"
            else -> code
        }
    }
}
