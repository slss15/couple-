package com.qimingnan.couple.data

import com.qimingnan.couple.data.auth.SessionManager
import com.qimingnan.couple.data.auth.SessionState
import com.qimingnan.couple.data.bootstrap.BootstrapManager
import com.qimingnan.couple.data.bootstrap.BootstrapStore
import com.qimingnan.couple.data.couple.CoupleRepository
import com.qimingnan.couple.data.couple.CoupleState
import com.qimingnan.couple.data.demo.DemoBackend
import com.qimingnan.couple.data.mqtt.MqttManager
import com.qimingnan.couple.data.mqtt.Presence
import com.qimingnan.couple.data.mqtt.MqttState
import com.qimingnan.couple.data.mqtt.MqttTarget
import com.qimingnan.couple.data.update.UpdateRepository
import com.qimingnan.couple.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程级编排：
 * - 启动时补做未完成的配置试用（防止上次在 30 秒窗口内被杀）
 * - 根据 配置 + 登录 + 配对 状态决定 MQTT 是否连接
 * - 登录后检查 App 更新（最多每 6 小时一次）
 */
@Singleton
class AppCoordinator @Inject constructor(
    private val bootstrapStore: BootstrapStore,
    private val bootstrapManager: BootstrapManager,
    private val session: SessionManager,
    private val couple: CoupleRepository,
    private val mqtt: MqttManager,
    private val updates: UpdateRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            bootstrapStore.load()
            bootstrapManager.verifyPending()
        }
        scope.launch {
            combine(bootstrapStore.state, session.state, couple.state) { b, s, c ->
                val url = b?.takeIf { it.pendingSince == null }?.current?.mqttUrl
                val user = (s as? SessionState.LoggedIn)?.user
                val cid = (c as? CoupleState.Paired)?.couple?.id
                if (url != null && user != null && cid != null) MqttTarget(url, user.id, cid) else null
            }.distinctUntilChanged().collect { target ->
                if (target != null && DemoBackend.isDemoUrl(target.url)) {
                    // 调试账号：没有 broker，伴侣永远“在线”
                    mqtt.setTarget(null)
                    mqtt.setDemoPresence(mapOf(DemoBackend.partner.id to Presence(DemoBackend.partner.id, true, System.currentTimeMillis())))
                } else {
                    mqtt.setTarget(target)
                }
            }
        }
        scope.launch {
            // 已确认生效的服务器地址变了 → 旧服务器的 token 作废，回到登录页
            var lastCommitted: String? = null
            bootstrapStore.state.collect { st ->
                if (st == null || st.pendingSince != null) return@collect
                val base = st.current?.baseUrl
                val prev = lastCommitted
                lastCommitted = base
                if (prev != null && base != prev && session.state.value is SessionState.LoggedIn) {
                    session.clearLocal("已切换服务器，请重新登录")
                }
            }
        }
        scope.launch {
            // 退出登录 / 会话失效 → 清空配对状态，回到未知
            session.state.collect { if (it is SessionState.LoggedOut) couple.reset() }
        }
        scope.launch {
            // App update check (/app/update needs a session): on start / login, at most once per 6 h.
            combine(bootstrapStore.state, session.state) { b, s ->
                b?.pendingSince == null && b?.current != null && s is SessionState.LoggedIn
            }.distinctUntilChanged().collect { ready ->
                if (ready) {
                    updates.checkIfDue()
                    // OTA v2: first run of a self-installed version reports OK on the first MQTT connect.
                    updates.startHealthWatch(mqtt.state.map { it is MqttState.Connected })
                }
            }
        }
    }
}
