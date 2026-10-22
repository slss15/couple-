package com.qimingnan.couple

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qimingnan.couple.theme.CoupleTheme
import com.qimingnan.couple.ui.CoupleRoot
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var deepLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLink = extractBootstrap(intent)
        setContent {
            CoupleTheme {
                CoupleRoot(deepLinkUri = deepLink, onDeepLinkConsumed = { deepLink = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractBootstrap(intent)?.let { deepLink = it }
    }

    private fun extractBootstrap(intent: Intent?): String? =
        intent?.data?.takeIf { it.scheme == "couple" && it.host == "bootstrap" }?.toString()
}
