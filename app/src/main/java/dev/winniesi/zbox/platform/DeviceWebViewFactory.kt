package dev.winniesi.zbox.platform

import android.content.Context
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.winniesi.zbox.core.DeviceWebProfiles

/**
 * M2：给每台设备独立的 WebView Profile（cookie / localStorage / sessionStorage 隔离），
 * 避免多台设备的远程页面互相污染。MULTI_PROFILE 不可用时退回共享默认 profile。
 */
object DeviceWebViewFactory {

    fun isProfileStoreSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    fun create(context: Context, mid: String): WebView {
        val webView = WebView(context)
        if (DeviceWebProfiles.shouldIsolate(isProfileStoreSupported())) {
            val name = DeviceWebProfiles.profileNameFor(mid)
            // setProfile 必须在 WebView 开始加载前调用
            runCatching {
                ProfileStore.getInstance().getOrCreateProfile(name)
                WebViewCompat.setProfile(webView, name)
            }
        }
        return webView
    }

    /** 删除设备时清理其 Profile（尽力而为）。 */
    fun dispose(mid: String) {
        if (DeviceWebProfiles.shouldIsolate(isProfileStoreSupported())) {
            runCatching {
                ProfileStore.getInstance().deleteProfile(DeviceWebProfiles.profileNameFor(mid))
            }
        }
    }
}
