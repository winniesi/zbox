package dev.winniesi.zbox.di

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import dev.winniesi.zbox.core.AppLink
import dev.winniesi.zbox.core.DeviceRepository
import dev.winniesi.zbox.core.DeviceStore
import dev.winniesi.zbox.core.SecretCipher
import dev.winniesi.zbox.platform.AndroidShortcuts
import dev.winniesi.zbox.platform.DataStoreDeviceStore
import dev.winniesi.zbox.platform.DeviceWebViewFactory
import dev.winniesi.zbox.platform.KeystoreAesGcmCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Compose 树内获取依赖容器的入口。 */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}

/** 手工依赖容器：规模小，不上 DI 框架。 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val cipher: SecretCipher = KeystoreAesGcmCipher()
    val store: DeviceStore = DataStoreDeviceStore(appContext)
    val repository = DeviceRepository(store, cipher)

    /** 深链 / 快捷方式带进来的待处理跳转。 */
    val pendingAppLink = MutableStateFlow<AppLink?>(null)

    private val shortcuts = AndroidShortcuts(appContext)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        // 设备列表每次变化：同步桌面快捷方式；顺带清理已删设备的 WebView Profile
        appScope.launch {
            var knownMids: Set<String>? = null
            repository.devices.filterNotNull().collect { devices ->
                shortcuts.sync(devices)
                val mids = devices.map { it.mid }.toSet()
                knownMids?.let { prev -> (prev - mids).forEach(DeviceWebViewFactory::dispose) }
                knownMids = mids
            }
        }
    }
}
