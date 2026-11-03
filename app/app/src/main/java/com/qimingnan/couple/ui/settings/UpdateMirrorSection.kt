package com.qimingnan.couple.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.qimingnan.couple.data.demo.DemoBackend
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.components.SectionCard

/**
 * 设置 → 关于/更新：调试通道（离线演示账号的「检查更新」）用的 GitHub 加速前缀。
 * 只在 debug / edge 构建出现；正式通道的镜像由服务器管理端和签名清单决定（contract "Auto-update → mirrors"）。
 */
object DemoOtaPrefs {
    private const val PREFS = "demo_ota"
    private const val KEY = "gh_accelerators"

    /** App 启动时调用：把保存的前缀装进 [DemoBackend.ghAccelerators]（没保存过 = 默认列表）。 */
    fun load(context: Context) {
        if (!DemoBackend.available) return
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        DemoBackend.ghAccelerators = DemoBackend.parseAccelerators(saved)
    }

    fun save(context: Context, text: String): List<String> {
        val list = DemoBackend.parseAccelerators(text)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, list.joinToString("\n")).apply()
        DemoBackend.ghAccelerators = list
        return list
    }
}

@Composable
fun UpdateMirrorSection() {
    if (!DemoBackend.available) return
    val t = LocalCoupleTokens.current
    val context = LocalContext.current
    var text by remember { mutableStateOf(DemoBackend.ghAccelerators.joinToString("\n")) }
    SectionCard("关于/更新") {
        Text(
            "调试通道 GitHub 加速前缀（每行一个，https）。检查更新时 App 会对 GitHub 直连和这些前缀并行测速，选最快的下载，卡住自动换下一个。",
            style = t.typography.caption, color = t.colors.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 2,
            placeholder = { Text("https://ghfast.top/") },
        )
        Text(
            "注意：这些都是第三方非官方代理，可能随时失效或限速。安装包仍必须通过 sha256、大小和签名证书校验，代理无法篡改。留空 = 只用 GitHub 直连。",
            style = t.typography.caption, color = t.colors.onSurfaceVariant,
        )
        TextButton(onClick = {
            val saved = DemoOtaPrefs.save(context, text)
            text = saved.joinToString("\n")
            Toast.makeText(context, if (saved.isEmpty()) "已清空，只走 GitHub 直连" else "已保存 ${saved.size} 个加速前缀", Toast.LENGTH_SHORT).show()
        }) { Text("保存") }
        TextButton(onClick = { text = DemoBackend.DEFAULT_GH_ACCELERATORS.joinToString("\n") }) { Text("恢复默认") }
    }
}
