package com.qimingnan.couple

import android.content.Intent
import android.app.ActivityManager
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.qimingnan.couple.data.keepalive.KeepAliveController
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qimingnan.couple.theme.CoupleTheme
import com.qimingnan.couple.ui.CoupleRoot
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qimingnan.couple.data.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var keepAliveController: KeepAliveController
    private var deepLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLink = extractBootstrap(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepo.settings.map { it.retainRecentTask }.distinctUntilChanged().collect { retain ->
                    runCatching {
                        getSystemService(ActivityManager::class.java)?.appTasks
                            ?.firstOrNull { task ->
                                val info = task.taskInfo
                                @Suppress("DEPRECATION")
                                val id = if (Build.VERSION.SDK_INT >= 29) info.taskId else info.id
                                id == taskId
                            }?.setExcludeFromRecents(!retain)
                    }.onFailure { Log.w("CoupleTasks", "Cannot update recent task visibility", it) }
                }
            }
        }
        setContent {
            val settings by settingsRepo.settings.collectAsStateWithLifecycle()
            CoupleTheme(settings) {
                CoupleRoot(deepLinkUri = deepLink, onDeepLinkConsumed = { deepLink = null })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 后台启动前台服务可能被系统拒绝；回到前台时按用户开关补尝试。
        keepAliveController.startIfEnabled()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractBootstrap(intent)?.let { deepLink = it }
    }

    private fun extractBootstrap(intent: Intent?): String? =
        intent?.data?.takeIf { it.scheme == "couple" && it.host == "bootstrap" }?.toString()
}
