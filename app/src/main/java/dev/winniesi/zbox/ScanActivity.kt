package dev.winniesi.zbox

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import dev.winniesi.zbox.core.RemoteLinkParser
import dev.winniesi.zbox.di.LocalAppContainer
import dev.winniesi.zbox.ui.scan.ScanScreen
import kotlinx.coroutines.launch

/**
 * 为 RemoteActivity 提供的扫码换钥匙入口（Compose 承载扫码界面，无 WebView）。
 * 扫到有效链接后按 mid 更新设备钥匙并结束，RemoteActivity 会检测到 issuedAt 变化自动重载。
 */
class ScanActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as ZBoxApplication).container
        val forDevice = intent.getStringExtra(EXTRA_FOR_DEVICE)

        setContent {
            ScanScreen(
                forDeviceMid = forDevice,
                onCancel = { finish() },
                onScannedLink = { raw ->
                    val parsed = RemoteLinkParser().parse(raw)
                    if (parsed !is RemoteLinkParser.Result.Ok) {
                        Toast.makeText(this, "二维码内容不是有效的远程控制链接", Toast.LENGTH_LONG).show()
                        return@ScanScreen
                    }
                    lifecycleScope.launch {
                        container.repository.addOrUpdateFromLink(parsed.link)
                        finish()
                    }
                },
            )
        }
    }

    companion object {
        const val EXTRA_FOR_DEVICE = "forDevice"
    }
}
