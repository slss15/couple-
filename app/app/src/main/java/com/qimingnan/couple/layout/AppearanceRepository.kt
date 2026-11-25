package com.qimingnan.couple.layout

import com.qimingnan.couple.core.CoupleJson
import com.qimingnan.couple.data.net.ApiResult
import com.qimingnan.couple.data.net.CoupleApi
import com.qimingnan.couple.data.net.apiCall
import com.qimingnan.couple.data.settings.SettingsRepository
import com.qimingnan.couple.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务端外观仓库：先读 DataStore 缓存，登录后再拉取最新布局/主题。
 * 网络失败不回退到空白，继续使用上次缓存或默认布局。
 */
@Singleton
class AppearanceRepository @Inject constructor(
    private val api: CoupleApi,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(AppearanceState())
    val state: StateFlow<AppearanceState> = _state.asStateFlow()

    init {
        scope.launch {
            settings.initialSettings().serverAppearanceJson?.let(::applyCached)
        }
    }

    suspend fun refresh() {
        when (val result = apiCall { api.appearance() }) {
            is ApiResult.Ok -> {
                val normalized = result.value.normalized()
                _state.value = AppearanceState(normalized)
                settings.cacheServerAppearance(CoupleJson.encodeToString(AppAppearance.serializer(), normalized))
            }
            is ApiResult.Err -> _state.update { it.copy(error = result.error.message) }
        }
    }

    private fun applyCached(json: String) {
        runCatching { CoupleJson.decodeFromString(AppAppearance.serializer(), json) }
            .onSuccess { cached ->
                _state.value = AppearanceState(cached.normalized(), fromCache = true)
            }
    }

    private fun AppAppearance.normalized(): AppAppearance {
        val validLayout = (LayoutValidator.validate(layout) as? LayoutValidation.Valid)?.spec ?: LayoutRules.DEFAULT
        return copy(layout = validLayout, theme = theme.normalized())
    }
}
