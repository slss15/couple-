package com.qimingnan.couple.data.bootstrap

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.qimingnan.couple.core.CoupleJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** 引导状态存储的抽象，方便 BootstrapManager 单测。 */
interface BootstrapStore {
    /** null 表示尚未从磁盘加载。 */
    val state: StateFlow<BootstrapState?>
    suspend fun load(): BootstrapState
    suspend fun save(state: BootstrapState)

    /** 供 OkHttp 拦截器同步读取（已在 IO 线程）。 */
    fun currentConfigBlocking(): ServerConfig?
}

private val Context.bootstrapDataStore: DataStore<Preferences> by preferencesDataStore(name = "bootstrap")

@Singleton
class DataStoreBootstrapStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : BootstrapStore {
    private val key = stringPreferencesKey("state_json")
    private val mutex = Mutex()
    private val _state = MutableStateFlow<BootstrapState?>(null)
    override val state: StateFlow<BootstrapState?> = _state.asStateFlow()

    override suspend fun load(): BootstrapState = mutex.withLock {
        _state.value?.let { return it }
        val json = context.bootstrapDataStore.data.first()[key]
        val loaded = json?.let {
            runCatching { CoupleJson.decodeFromString(BootstrapState.serializer(), it) }.getOrNull()
        } ?: BootstrapState()
        _state.value = loaded
        loaded
    }

    override suspend fun save(state: BootstrapState) = mutex.withLock {
        context.bootstrapDataStore.edit {
            it[key] = CoupleJson.encodeToString(BootstrapState.serializer(), state)
        }
        _state.value = state
    }

    override fun currentConfigBlocking(): ServerConfig? =
        (_state.value ?: runBlocking { load() }).current
}
