package com.qimingnan.couple.data.auth

import com.qimingnan.couple.data.db.ProfileDao
import com.qimingnan.couple.data.db.toModel
import com.qimingnan.couple.data.net.ApiResult
import com.qimingnan.couple.data.net.CoupleApi
import com.qimingnan.couple.data.net.LoginRequest
import com.qimingnan.couple.data.net.LogoutRequest
import com.qimingnan.couple.data.net.RegisterRequest
import com.qimingnan.couple.data.net.TokenPair
import com.qimingnan.couple.data.net.User
import com.qimingnan.couple.data.net.apiCall
import com.qimingnan.couple.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SessionState {
    data object Loading : SessionState
    data object LoggedOut : SessionState
    data class LoggedIn(val user: User) : SessionState
}

@Singleton
class SessionManager @Inject constructor(
    private val api: CoupleApi,
    private val tokenStore: TokenStore,
    private val refresher: TokenRefresher,
    private val device: DeviceIdentity,
    private val dao: ProfileDao,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** 需要提示用户的事件，例如“登录已失效”。 */
    val messages: SharedFlow<String> = _messages

    init {
        scope.launch {
            val hasTokens = withContext(Dispatchers.IO) { tokenStore.read() != null }
            val self = dao.self()?.toModel()
            _state.value = if (hasTokens && self != null) SessionState.LoggedIn(self) else {
                if (hasTokens) withContext(Dispatchers.IO) { tokenStore.clear() }
                SessionState.LoggedOut
            }
        }
        scope.launch {
            refresher.sessionExpired.collect { msg ->
                dao.clearAll()
                _state.value = SessionState.LoggedOut
                _messages.tryEmit(msg)
            }
        }
        scope.launch {
            refresher.userUpdates.collect { user -> updateUser(user) }
        }
    }

    suspend fun login(username: String, password: String): ApiResult<User> = apiCall {
        api.login(LoginRequest(username.trim(), password, device.deviceId, device.deviceName))
    }.also { if (it is ApiResult.Ok) onTokens(it.value) }.mapUser()

    suspend fun register(username: String, password: String, displayName: String, inviteCode: String): ApiResult<User> = apiCall {
        api.register(
            RegisterRequest(username.trim(), password, displayName.trim(), device.deviceId, device.deviceName, inviteCode.trim()),
        )
    }.also { if (it is ApiResult.Ok) onTokens(it.value) }.mapUser()

    suspend fun logout() {
        val refresh = withContext(Dispatchers.IO) { tokenStore.read()?.refreshToken }
        if (refresh != null) apiCall { api.logout(LogoutRequest(refresh)) } // 尽力而为
        withContext(Dispatchers.IO) { tokenStore.clear() }
        dao.clearAll()
        _state.value = SessionState.LoggedOut
    }

    /** 仅清除本地会话（例如切换到另一台服务器，旧 token 已无意义）。 */
    suspend fun clearLocal(message: String? = null) {
        withContext(Dispatchers.IO) { tokenStore.clear() }
        dao.clearAll()
        _state.value = SessionState.LoggedOut
        message?.let { _messages.tryEmit(it) }
    }

    /** 配对/解绑后强制刷新，拿到带新 cid 的 access token 与最新 User。 */
    suspend fun forceRefresh(): Boolean = withContext(Dispatchers.IO) {
        refresher.refreshBlocking(staleAccessToken = null, force = true) != null
    }

    suspend fun updateUser(user: User) {
        val cur = _state.value
        if (cur is SessionState.LoggedIn && cur.user.id != user.id) return
        dao.upsertUsers(listOf(com.qimingnan.couple.data.db.UserEntity(
            user.id, user.username, user.displayName, user.avatarUrl, user.coupleId, user.createdAt, isSelf = true,
        )))
        if (cur !is SessionState.LoggedOut) _state.value = SessionState.LoggedIn(user)
    }

    private suspend fun onTokens(pair: TokenPair) {
        withContext(Dispatchers.IO) {
            tokenStore.write(StoredTokens(pair.accessToken, pair.refreshToken, pair.accessExpiresAt))
        }
        dao.replaceSelf(pair.user)
        _state.value = SessionState.LoggedIn(pair.user)
    }

    private fun ApiResult<TokenPair>.mapUser(): ApiResult<User> = when (this) {
        is ApiResult.Ok -> ApiResult.Ok(value.user)
        is ApiResult.Err -> this
    }
}
