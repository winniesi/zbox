package dev.winniesi.zbox

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import dev.winniesi.zbox.core.AppLinks
import dev.winniesi.zbox.di.LocalAppContainer
import dev.winniesi.zbox.ui.ZBoxApp
import dev.winniesi.zbox.ui.theme.ZBoxTheme

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as ZBoxApplication).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 注意：不要在此开启 enableEdgeToEdge —— 边到边窗口下 WebView 的
        // vh/dvh 视口单位会解析为 0（官方远程页整页高度依赖 dvh，会导致黑屏）。
        handleIntent(intent)
        setContent {
            ZBoxTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    ZBoxApp(container = container)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** zcode:// 深链入口（扫码绑定 / 快捷方式直达）。 */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.dataString ?: return
        consumeDeepLink(data)
    }

    /** 供 UI 测试直接注入深链，绕过系统 Intent 分发。 */
    @androidx.annotation.VisibleForTesting
    fun consumeDeepLink(data: String) {
        val link = AppLinks.parse(data) ?: return
        container.pendingAppLink.value = link
    }
}
