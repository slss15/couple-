package com.qimingnan.couple.data.couple

import com.qimingnan.couple.data.auth.SessionManager
import com.qimingnan.couple.data.auth.SessionState
import com.qimingnan.couple.data.db.ProfileDao
import com.qimingnan.couple.data.db.toModel
import com.qimingnan.couple.data.net.ApiError
import com.qimingnan.couple.data.net.ApiResult
import com.qimingnan.couple.data.net.Couple
import com.qimingnan.couple.data.net.CoupleApi
import com.qimingnan.couple.data.net.JoinRequest
import com.qimingnan.couple.data.net.PairCode
import com.qimingnan.couple.data.net.UnpairConfirmRequest
import com.qimingnan.couple.data.net.apiCall
import com.qimingnan.couple.data.net.orThrow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface CoupleState {
    data object Unknown : CoupleState
    data object NotPaired : CoupleState
    data class Paired(val couple: Couple, val fromCache: Boolean = false) : CoupleState
    data class Error(val error: ApiError) : CoupleState
}

@Singleton
class CoupleRepository @Inject constructor(
    private val api: CoupleApi,
    private val dao: ProfileDao,
    private val session: SessionManager,
) {
    private val _state = MutableStateFlow<CoupleState>(CoupleState.Unknown)
    val state: StateFlow<CoupleState> = _state.asStateFlow()

    private fun selfId(): String? = (session.state.value as? SessionState.LoggedIn)?.user?.id

    /** GET /couple；网络失败时退回 Room 缓存，保证离线也能进主界面。 */
    suspend fun refresh(): CoupleState {
        val result = apiCall {
            val resp = api.getCouple()
            if (resp.code() == 404) null else resp.orThrow().body()
        }
        val next = when (result) {
            is ApiResult.Ok -> {
                val couple = result.value
                if (couple == null) {
                    dao.clearCouple()
                    CoupleState.NotPaired
                } else {
                    selfId()?.let { dao.saveCouple(couple, it) }
                    CoupleState.Paired(couple)
                }
            }
            is ApiResult.Err -> {
                if (result.error.code == "NOT_PAIRED") {
                    dao.clearCouple(); CoupleState.NotPaired
                } else cached()?.let { CoupleState.Paired(it, fromCache = true) } ?: CoupleState.Error(result.error)
            }
        }
        _state.value = next
        return next
    }

    private suspend fun cached(): Couple? {
        val c = dao.couple() ?: return null
        val members = listOfNotNull(dao.user(c.memberA)?.toModel(), dao.user(c.memberB)?.toModel())
        if (members.size != 2) return null
        return Couple(c.id, members, c.pairedAt, c.anniversary)
    }

    suspend fun createCode(): ApiResult<PairCode> = apiCall { api.createPairCode() }

    suspend fun join(code: String): ApiResult<Couple> {
        val r = apiCall { api.joinPair(JoinRequest(code.trim())) }
        if (r is ApiResult.Ok) onPaired(r.value)
        return r
    }

    /** 发码方轮询：对方输入成功后 GET /couple 会变成已配对。 */
    suspend fun pollPaired(): Boolean {
        val s = refresh()
        if (s is CoupleState.Paired && !s.fromCache) {
            session.forceRefresh()
            return true
        }
        return false
    }

    private suspend fun onPaired(couple: Couple) {
        selfId()?.let { dao.saveCouple(couple, it) }
        session.forceRefresh() // 新 access token 带上 cid
        _state.value = CoupleState.Paired(couple)
    }

    suspend fun requestUnpair(): ApiResult<String> = apiCall { api.requestUnpair().requestId }

    /** requestId 为空时由服务端取当前情侣最新一条未处理的申请（后端已支持）。 */
    suspend fun confirmUnpair(requestId: String?): ApiResult<Unit> {
        val r = apiCall { api.confirmUnpair(UnpairConfirmRequest(requestId?.trim()?.ifBlank { null })).orThrow(); Unit }
        if (r is ApiResult.Ok) onUnpaired()
        return r
    }

    suspend fun onUnpaired() {
        dao.clearCouple()
        session.forceRefresh()
        _state.value = CoupleState.NotPaired
    }

    fun reset() {
        _state.value = CoupleState.Unknown
    }
}
