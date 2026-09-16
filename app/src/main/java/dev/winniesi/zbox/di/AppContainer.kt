package dev.winniesi.zbox.di

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import dev.winniesi.zbox.core.AppLink
import dev.winniesi.zbox.core.DeviceRepository
import dev.winniesi.zbox.core.DeviceStore
import dev.winniesi.zbox.core.SecretCipher
import dev.winniesi.zbox.platform.DataStoreDeviceStore
import dev.winniesi.zbox.platform.KeystoreAesGcmCipher
import kotlinx.coroutines.flow.MutableStateFlow

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
}
