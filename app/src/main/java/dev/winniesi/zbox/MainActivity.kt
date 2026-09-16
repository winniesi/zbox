package dev.winniesi.zbox

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.winniesi.zbox.core.AppLinks
import dev.winniesi.zbox.ui.ZBoxApp
import dev.winniesi.zbox.ui.theme.ZBoxTheme

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as ZBoxApplication).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            ZBoxTheme {
                ZBoxApp(container = container)
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
        val link = AppLinks.parse(data) ?: return
        container.pendingAppLink.value = link
    }
}
